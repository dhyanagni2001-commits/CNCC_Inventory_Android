package com.cnanjappa.inventory.ui

import android.Manifest
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.LinkResult
import com.cnanjappa.inventory.data.Resolution
import com.cnanjappa.inventory.data.Variant
import com.cnanjappa.inventory.domain.Codes
import com.cnanjappa.inventory.domain.ScannedCode
import com.cnanjappa.inventory.scan.CameraProblem
import com.cnanjappa.inventory.scan.CameraScanner
import com.cnanjappa.inventory.scan.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class ScanMode(val heading: String, val instruction: String) {
    SELL("Selling item", "Scanning here sells 1 piece."),
    RETURN("Returning item", "Scan the item. Stock does not change until you tap Return."),
    RESTOCK("Add stock — scan item", "Scan an item's label. Stock does not change until you tap Add stock."),
    CAPTURE("Scan existing barcode", "Scan the barcode on the garment's tag."),
    LINK("Link more labels", "Scan each new label for this item. Stock does not change."),
}

sealed interface ScanState {
    data object Scanning : ScanState
    data object Checking : ScanState
    data class Paused(val text: String, val warm: Boolean = false) : ScanState
    data class CameraError(val problem: CameraProblem) : ScanState
    data class Sold(val variant: Variant, val remaining: Int, val saleId: Long) : ScanState
    data class Undone(val variant: Variant, val remaining: Int, val qty: Int) : ScanState
    data class Failed(val msg: Msg, val retry: Variant? = null) : ScanState
    data class NotFound(val code: ScannedCode) : ScanState
    data class Choose(val variants: List<Variant>) : ScanState
}

sealed interface ScanNav {
    data class ToVariant(val id: Long) : ScanNav
    data class ToReturn(val id: Long) : ScanNav
    data class Captured(val code: ScannedCode) : ScanNav
}

data class BatchItem(val code: ScannedCode, val status: String, val linkable: Boolean)

/**
 * Scanner state machine: preparing → scanning → checking/committing → result → (Scan next) rearm.
 * Acceptance locks on the first valid frame. After Scan next, the previous label must leave the frame
 * (a few frames with no code) before it can be accepted again, so a held label never sells twice.
 */
class ScanViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val mode = ScanMode.valueOf(handle.get<String>("mode")!!)
    private val targetVariant = handle.get<Long>("variant") ?: -1L
    var state by mutableStateOf<ScanState>(ScanState.Scanning)
        private set
    var hint by mutableStateOf<String?>(null)
    var torchOn by mutableStateOf(false)
    var torchAvailable by mutableStateOf(false)
    var navEvent by mutableStateOf<ScanNav?>(null)
    var successTick by mutableIntStateOf(0)
        private set
    val batch = mutableStateListOf<BatchItem>()

    private val accepting = AtomicBoolean(true)
    @Volatile private var blockedKey: String? = null
    @Volatile private var emptyFrames = 0
    @Volatile private var thermal = 0
    private var lastKey: String? = handle["lastKey"]
    private val batchKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    private var idleJob: Job? = null
    private val powerSave = Power.powerSave(c.appContext)

    init {
        handle.get<String>(KEY_SELL)?.let { opId ->
            state = ScanState.Checking
            viewModelScope.launch {
                val r = repo.outcome(opId)
                val v = handle.get<Long>("sellVariant")?.let { repo.dao.variant(it) }
                state = when {
                    r != null && r.ok && v != null -> { handle.remove<String>(KEY_SELL); ScanState.Sold(v, r.remaining!!, r.refId!!) }
                    v != null -> ScanState.Failed(Msg(Tone.ERROR, "Could not save. Try again"), retry = v)
                    else -> ScanState.Scanning
                }
            }
        }
        viewModelScope.launch {
            Power.thermal(c.appContext).collect { t ->
                thermal = t
                if (t >= PowerManager.THERMAL_STATUS_SEVERE && state == ScanState.Scanning) {
                    state = ScanState.Paused("Phone is warm. Use product search", warm = true)
                    torchOn = false
                }
            }
        }
        startIdle()
    }

    /** Decoding cadence: ~15 fps normally, reduced under Battery Saver or moderate heat. */
    fun minIntervalMs(): Long = if (powerSave || thermal >= PowerManager.THERMAL_STATUS_MODERATE) 200L else 66L

    private fun startIdle() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(30_000)
            pause("Tap to scan again")
        }
    }

    fun pause(text: String) {
        if (state == ScanState.Scanning) { state = ScanState.Paused(text); torchOn = false; hint = null }
    }

    fun resume() {
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE && (state as? ScanState.Paused)?.warm == true) {
            hint = "Phone is still warm. Use product search"
            return
        }
        hint = null
        state = ScanState.Scanning
        accepting.set(true)
        startIdle()
    }

    fun cameraFailed(problem: CameraProblem) {
        if (state != ScanState.Scanning) return
        state = ScanState.CameraError(problem); torchOn = false; hint = null; idleJob?.cancel()
    }

    /** Called on the analyzer thread for every analysed frame. */
    fun onFrame(codes: List<ScannedCode>) {
        if (state != ScanState.Scanning) return
        val blocked = blockedKey
        if (blocked != null) {
            if (codes.any { it.key == blocked }) {
                emptyFrames = 0
                postHint("Move label away, then scan the next item")
                return
            }
            if (codes.isEmpty()) {
                if (++emptyFrames >= 3) { blockedKey = null; postHint(null) }
                return
            }
            blockedKey = null
        }
        if (codes.isEmpty()) return
        if (mode == ScanMode.LINK) { batchFrame(codes); return }
        if (!accepting.compareAndSet(true, false)) return
        viewModelScope.launch(Dispatchers.Main.immediate) { accept(codes) }
    }

    private fun postHint(text: String?) {
        if (hint != text) viewModelScope.launch(Dispatchers.Main.immediate) { hint = text }
    }

    private suspend fun accept(codes: List<ScannedCode>) {
        if (state != ScanState.Scanning) return
        idleJob?.cancel()
        state = ScanState.Checking
        val results = codes.map { repo.resolve(it) }
        if (codes.size > 1) {
            // Several visible labels: only proceed when every one is a verified alias of one variant.
            val ids = results.map { (it as? Resolution.One)?.variant?.id }
            if (ids.any { it == null } || ids.distinct().size != 1) {
                hint = "Point at one label"
                state = ScanState.Scanning
                accepting.set(true)
                startIdle()
                return
            }
        }
        val code = codes.first()
        lastKey = code.key
        handle["lastKey"] = code.key
        hint = null
        if (mode == ScanMode.CAPTURE) { navEvent = ScanNav.Captured(code); return }
        state = when (val r = results.first()) {
            is Resolution.One -> { chosen(r.variant); return }
            is Resolution.Many -> ScanState.Choose(r.variants)
            Resolution.Unknown -> ScanState.NotFound(code)
        }
    }

    /** Continues with an identified variant: sells in Sell mode, otherwise opens the next screen. */
    fun chosen(v: Variant) {
        when (mode) {
            ScanMode.SELL -> sell(v)
            ScanMode.RETURN -> navEvent = ScanNav.ToReturn(v.id)
            else -> navEvent = ScanNav.ToVariant(v.id)
        }
    }

    private fun sell(v: Variant) {
        state = ScanState.Checking
        handle["sellVariant"] = v.id
        op(KEY_SELL, { repo.sell(it, v.id) }) { r ->
            state = when {
                r.ok -> { successTick++; ScanState.Sold(v.copy(qty = r.remaining!!), r.remaining, r.refId!!) }
                r.code == Code.FAILED -> ScanState.Failed(failMsg(r.code), retry = v)
                else -> ScanState.Failed(failMsg(r.code))
            }
        }
    }

    fun retry(v: Variant) = sell(v)

    fun undo(sold: ScanState.Sold) {
        op(KEY_UNDO, { repo.undoSale(it, sold.saleId) }) { r ->
            state = if (r.ok) ScanState.Undone(sold.variant, r.remaining!!, r.qty!!)
            else ScanState.Failed(if (r.code == Code.LIMIT) Msg(Tone.ERROR, "This sale was already undone or returned") else failMsg(r.code))
        }
    }

    /** Deliberate rearm: the label just used must leave the frame before it can count again. */
    fun scanNext() {
        handle.remove<Long>("sellVariant")
        blockedKey = lastKey
        emptyFrames = 0
        hint = null
        state = ScanState.Scanning
        accepting.set(true)
        startIdle()
    }

    // ---- Batch linking ----

    private fun batchFrame(codes: List<ScannedCode>) {
        if (codes.size > 1) { postHint("Point at one label"); return }
        val code = codes.first()
        if (!batchKeys.add(code.key)) return
        viewModelScope.launch {
            startIdle()
            val item = when (val r = repo.resolve(code)) {
                is Resolution.One -> if (r.variant.id == targetVariant) BatchItem(code, "Already linked to this item", false)
                    else BatchItem(code, "Belongs to another item: ${r.variant.description}", false)
                is Resolution.Many -> BatchItem(code, "Shared label — linked to several items", r.variants.none { it.id == targetVariant })
                Resolution.Unknown -> BatchItem(code, "New label", true)
            }
            batch += item
            hint = null
        }
    }

    fun linkBatch() {
        val todo = batch.filter { it.linkable }
        if (todo.isEmpty() || busy) return
        viewModelScope.launch {
            var linked = 0
            for (item in todo) {
                val r = repo.link(targetVariant, item.code, share = item.status.startsWith("Shared"))
                if (r.result == LinkResult.LINKED || r.result == LinkResult.SHARED) linked++
            }
            batch.clear(); batchKeys.clear()
            msg = Msg(Tone.SUCCESS, "${if (linked == 1) "1 barcode" else "$linked barcodes"} linked", "Stock unchanged")
        }
    }

    companion object {
        const val KEY_SELL = "sellOp"
        const val KEY_UNDO = "undoOp"
    }
}

@Composable
fun ScanScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> ScanViewModel(c, h) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptic = LocalHapticFeedback.current
    var granted by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted && !asked) permission.launch(Manifest.permission.CAMERA) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause("Tap to scan again") }
    LaunchedEffect(vm.successTick) { if (vm.successTick > 0) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
    LaunchedEffect(vm.navEvent) {
        when (val e = vm.navEvent ?: return@LaunchedEffect) {
            is ScanNav.Captured -> {
                nav.previousBackStackEntry?.savedStateHandle?.apply { set("capturedRaw", e.code.raw); set("capturedFmt", e.code.format) }
                nav.popBackStack()
            }
            is ScanNav.ToReturn -> nav.navigate(Routes.returnItem(e.id)) { popUpTo(Routes.SCAN) { inclusive = true } }
            is ScanNav.ToVariant -> nav.navigate(Routes.variant(e.id)) { popUpTo(Routes.SCAN) { inclusive = true } }
        }
        vm.navEvent = null
    }
    val findProduct = { nav.navigate(Routes.products()) }

    SubScreen(vm.mode.heading, { nav.popBackStack() }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!granted) {
                val permanently = asked && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                MessageCard(Msg(Tone.INFO, "Allow camera to scan labels", "You can also find the product by name.")) {
                    if (permanently) SecondaryButton("Open settings", {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) else SecondaryButton("Allow camera", { permission.launch(Manifest.permission.CAMERA) })
                    SecondaryButton("Find product", findProduct, icon = Icons.Default.Search)
                }
                return@Column
            }
            when (val s = vm.state) {
                ScanState.Scanning, is ScanState.Paused, is ScanState.CameraError -> CameraBox(vm, s)
                ScanState.Checking -> MessageCard(Msg(Tone.INFO, "Checking…"))
                is ScanState.Sold -> SoldCard(s) {
                    BigButton("Scan next", vm::scanNext, container = Color.White, content = successColor())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OnGreenButton("Undo", { vm.undo(s) }, !vm.busy, Modifier.weight(1f))
                        OnGreenButton("Done", { nav.popBackStack() }, true, Modifier.weight(1f))
                    }
                }
                is ScanState.Undone -> MessageCard(Msg(Tone.SUCCESS, "Sale undone — ${pieces(s.qty)} back", "${s.variant.description}\n${piecesLeft(s.remaining)}")) {
                    BigButton("Scan next", vm::scanNext)
                    SecondaryButton("Done", { nav.popBackStack() })
                }
                is ScanState.Failed -> MessageCard(s.msg) {
                    if (s.retry != null) BigButton("Try again", { vm.retry(s.retry) }, enabled = !vm.busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Scan next", vm::scanNext, modifier = Modifier.weight(1f))
                        SecondaryButton("Done", { nav.popBackStack() }, modifier = Modifier.weight(1f))
                    }
                }
                is ScanState.NotFound -> NotFound(nav, vm, s.code, findProduct)
                is ScanState.Choose -> {
                    MessageCard(Msg(Tone.INFO, "Choose size and sleeve", "This label is on several items. Pick the exact one."))
                    s.variants.forEach { v ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                VariantHeader(v)
                                BigButton(
                                    when (vm.mode) { ScanMode.SELL -> "Sell 1"; ScanMode.RETURN -> "Return this item"; else -> "Choose this item" },
                                    { vm.chosen(v) }, enabled = !vm.busy,
                                )
                            }
                        }
                    }
                    SecondaryButton("Scan again", vm::scanNext)
                }
            }
            vm.hint?.let { Text(it, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error) }
            vm.msg?.let { MessageCard(it) }
            if (vm.mode == ScanMode.LINK) BatchList(vm, nav)
            if (vm.mode != ScanMode.CAPTURE && vm.mode != ScanMode.LINK) SecondaryButton("Find product instead", findProduct, Modifier.fillMaxWidth(), Icons.Default.Search)
        }
    }
}

@Composable
private fun CameraBox(vm: ScanViewModel, s: ScanState) {
    Text(vm.mode.instruction, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
            .clickable(enabled = s != ScanState.Scanning) { vm.resume() },
        contentAlignment = Alignment.Center,
    ) {
        CameraScanner(
            active = s == ScanState.Scanning,
            torchOn = vm.torchOn,
            onTorchAvailable = { vm.torchAvailable = it },
            minIntervalMs = vm::minIntervalMs,
            onFrame = vm::onFrame,
            onCameraError = vm::cameraFailed,
            modifier = Modifier.fillMaxSize(),
        )
        // Scan frame matching the analyser's crop (80% x 50% of the view).
        Box(Modifier.fillMaxWidth(0.8f).aspectRatio(0.8f * 3f / (0.5f * 4f)).border(BorderStroke(3.dp, Color.White), RoundedCornerShape(12.dp)))
        when (s) {
            is ScanState.Paused -> Overlay(s.text)
            is ScanState.CameraError -> Overlay(s.problem.text)
            else -> {}
        }
    }
    if (vm.torchAvailable && s == ScanState.Scanning) {
        SecondaryButton(
            if (vm.torchOn) "Torch off" else "Torch on", { vm.torchOn = !vm.torchOn },
            icon = if (vm.torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
        )
    }
}

@Composable
private fun Overlay(text: String) {
    Card(Modifier.padding(24.dp)) {
        Text(text, Modifier.padding(20.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NotFound(nav: NavHostController, vm: ScanViewModel, code: ScannedCode, findProduct: () -> Unit) {
    MessageCard(Msg(Tone.ERROR, "Product not found", "Label: ${code.raw.take(80)} (${Codes.formatLabel(code.format)})")) {
        if (vm.mode == ScanMode.RESTOCK || vm.mode == ScanMode.SELL) {
            BigButton("Link to existing product", { nav.navigate(Routes.products(code.raw, code.format)) })
            SecondaryButton("Add new product", { nav.navigate(Routes.form(codeRaw = code.raw, codeFmt = code.format)) }, Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Find product", findProduct, Modifier.weight(1f))
            SecondaryButton("Scan next", vm::scanNext, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatchList(vm: ScanViewModel, nav: NavHostController) {
    if (vm.batch.isEmpty()) return
    SectionTitle("Scanned labels")
    vm.batch.forEach { Text("• ${it.code.raw.take(60)} — ${it.status}", fontSize = 16.sp) }
    val n = vm.batch.count { it.linkable }
    BigButton(if (n == 1) "Link 1 barcode" else "Link $n barcodes", vm::linkBatch, enabled = n > 0 && !vm.busy)
    SecondaryButton("Done", { nav.popBackStack() })
}

/** Committed sale: a solid green card so the result reads instantly from arm's length. */
@Composable
private fun SoldCard(s: ScanState.Sold, actions: @Composable () -> Unit) {
    Surface(shape = CardShape, color = successColor(), contentColor = Color.White, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text("1 item reduced", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            Text(s.variant.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(s.variant.detail, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
            Text(piecesLeft(s.remaining), style = MaterialTheme.typography.headlineLarge, color = Color.White, modifier = Modifier.padding(vertical = 6.dp))
            actions()
        }
    }
}

@Composable
private fun OnGreenButton(text: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.8f)),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
