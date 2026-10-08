package com.cnanjappa.inventory.scan

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cnanjappa.inventory.domain.ScannedCode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.Executors

/**
 * Camera preview + analysis bound only while [active]. Turning [active] off (success, pause,
 * leaving the screen) unbinds the camera and switches the torch off. Frames from a previous binding
 * are dropped via the disposed flag so stale callbacks never reach the scanner state.
 */
@Composable
fun CameraScanner(
    active: Boolean,
    torchOn: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    minIntervalMs: () -> Long,
    onFrame: (List<ScannedCode>) -> Unit,
    onCameraError: () -> Unit,
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
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, BarcodeAnalyzer(minIntervalMs) { codes -> if (!disposed) frameCallback(codes) })
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                torchCallback(cam.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                if (!disposed) errorCallback()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            camera?.cameraControl?.enableTorch(false)
            provider?.unbindAll()
            camera = null
        }
    }
    LaunchedEffect(torchOn, camera) { camera?.cameraControl?.enableTorch(torchOn) }
    AndroidView(factory = { previewView }, modifier = modifier)
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
