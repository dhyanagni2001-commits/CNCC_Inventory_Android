package com.cnanjappa.inventory.scan

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cnanjappa.inventory.domain.ScannedCode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.Executors

/** Why the camera could not start; each maps to a message telling staff what to do. */
enum class CameraProblem(val text: String) {
    NO_CAMERA("No camera found on this phone. Use Find product instead"),
    IN_USE("Camera is in use by another app. Close it, then tap to retry"),
    BLOCKED("Camera is blocked on this phone. Allow it, then tap to retry"),
    FAILED("Camera unavailable — tap to retry"),
}

/**
 * Camera preview + analysis bound only while [active]. Turning [active] off (success, pause,
 * leaving the screen) unbinds the camera and switches the torch off. Frames from a previous binding
 * are dropped via the disposed flag so stale callbacks never reach the scanner state. Uses the back
 * camera when there is one, otherwise an external or front camera (tablets, emulators without a
 * back camera).
 */
@Composable
fun CameraScanner(
    active: Boolean,
    torchOn: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    minIntervalMs: () -> Long,
    onFrame: (List<ScannedCode>) -> Unit,
    onCameraError: (CameraProblem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    val frameCallback by rememberUpdatedState(onFrame)
    val errorCallback by rememberUpdatedState(onCameraError)
    val torchCallback by rememberUpdatedState(onTorchAvailable)
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var analyzer: BarcodeAnalyzer? = null
        var stateObserver: Pair<Camera, Observer<CameraState>>? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get().also { provider = it }
                val selector = pickCamera(p) ?: return@addListener errorCallback(CameraProblem.NO_CAMERA)
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer = BarcodeAnalyzer(minIntervalMs) { codes -> if (!disposed) frameCallback(codes) }
                analysis.setAnalyzer(executor, analyzer!!)
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                camera = cam
                torchCallback(cam.cameraInfo.hasFlashUnit())
                // Errors after binding (another app takes the camera, policy blocks it) otherwise leave a black preview.
                val observer = Observer<CameraState> { st -> st.error?.let { problemOf(it) }?.let { if (!disposed) errorCallback(it) } }
                cam.cameraInfo.cameraState.observe(lifecycleOwner, observer)
                stateObserver = cam to observer
            } catch (e: Exception) {
                Log.w("CameraScanner", "Camera start failed", e)
                if (!disposed) errorCallback(if (cameraCount(context) == 0) CameraProblem.NO_CAMERA else CameraProblem.FAILED)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            stateObserver?.let { (cam, o) -> cam.cameraInfo.cameraState.removeObserver(o) }
            camera?.cameraControl?.enableTorch(false)
            provider?.unbindAll()
            // Close on the analysis thread so it never races a frame still being decoded.
            analyzer?.let { a -> runCatching { executor.execute { a.close() } } }
            camera = null
        }
    }
    LaunchedEffect(torchOn, camera) { camera?.cameraControl?.enableTorch(torchOn) }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Back camera first, then an external camera, then any other (usually front). */
private fun pickCamera(p: ProcessCameraProvider): CameraSelector? {
    val cams = p.availableCameraInfos
    return (cams.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        ?: cams.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_EXTERNAL }
        ?: cams.firstOrNull())?.cameraSelector
}

private fun cameraCount(context: Context): Int =
    runCatching { context.getSystemService(CameraManager::class.java).cameraIdList.size }.getOrDefault(-1)

/** Only errors CameraX does not recover from by itself are shown; null means keep waiting. */
private fun problemOf(e: CameraState.StateError): CameraProblem? = when (e.code) {
    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraProblem.IN_USE
    CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> CameraProblem.BLOCKED
    else -> if (e.type == CameraState.ErrorType.CRITICAL) CameraProblem.FAILED else null
}

object Power {
    /** Thermal status changes (PowerManager.THERMAL_STATUS_*); constant NONE before Android 10. */
    fun thermal(context: Context): Flow<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return flowOf(0)
        val pm = context.getSystemService(PowerManager::class.java)
        return callbackFlow {
            val listener = PowerManager.OnThermalStatusChangedListener { trySend(it) }
            trySend(pm.currentThermalStatus)
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            awaitClose { pm.removeThermalStatusListener(listener) }
        }
    }

    fun powerSave(context: Context) = context.getSystemService(PowerManager::class.java).isPowerSaveMode
}
