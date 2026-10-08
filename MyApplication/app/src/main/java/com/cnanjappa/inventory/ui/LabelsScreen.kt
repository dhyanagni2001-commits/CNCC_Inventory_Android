package com.cnanjappa.inventory.ui

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.export.LabelJob
import com.cnanjappa.inventory.export.LabelPdf
import com.cnanjappa.inventory.export.LabelPreset
import com.cnanjappa.inventory.export.LabelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PdfState {
    data object Idle : PdfState
    data class Making(val page: Int) : PdfState
    data class Ready(val file: File, val labels: Int, val status: String = "PDF ready") : PdfState
    data class Failed(val text: String) : PdfState
}

/** Makes label PDFs on request. Nothing here changes stock, variants or codes. */
class LabelsViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val id: Long = handle["id"]!!
    val variant = repo.dao.observeVariant(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var copies by mutableStateOf(handle["copies"] ?: (handle.get<Int>("count") ?: 1))
    var type by mutableStateOf(LabelType.valueOf(handle["type"] ?: LabelType.CODE128.name))
    var startCell by mutableStateOf(handle["start"] ?: 0)
    val preset = repo.dao.observeSetting(InventoryRepository.KEY_LABEL_PRESET)
        .map { s -> LabelPreset.entries.firstOrNull { it.name == s } ?: LabelPreset.A4_24 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LabelPreset.A4_24)
    var pdf by mutableStateOf<PdfState>(PdfState.Idle)
        private set
    private var job: Job? = null

    fun set(copies: Int = this.copies, type: LabelType = this.type, start: Int = startCell) {
        this.copies = copies; this.type = type; startCell = start
        handle["copies"] = copies; handle["type"] = type.name; handle["start"] = start
        pdf = PdfState.Idle
    }

    fun setPreset(p: LabelPreset) = viewModelScope.launch {
        repo.setSetting(InventoryRepository.KEY_LABEL_PRESET, p.name); set(start = 0)
    }

    fun make(context: Context) {
        val v = variant.value ?: return
        val file = File(context.cacheDir, "labels/labels.pdf").apply { parentFile?.mkdirs() }
        val n = copies
        pdf = PdfState.Making(0)
        job = viewModelScope.launch {
            pdf = try {
                LabelPdf.write(file, listOf(LabelJob(v, n)), type, preset.value, startCell) { p -> pdf = PdfState.Making(p) }
                PdfState.Ready(file, n)
            } catch (e: CancellationException) {
                PdfState.Idle
            } catch (e: Exception) {
                PdfState.Failed("Could not make the labels. Try again")
            }
        }
    }

    fun cancel() { job?.cancel(); pdf = PdfState.Idle }

    fun status(text: String) { (pdf as? PdfState.Ready)?.let { pdf = it.copy(status = text) } }

    fun savePdf(context: Context, uri: android.net.Uri?) {
        val ready = pdf as? PdfState.Ready ?: return
        if (uri == null) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { o -> ready.file.inputStream().use { it.copyTo(o) } }
                }
                status("PDF saved")
            } catch (e: Exception) {
                msg = Msg(Tone.ERROR, "Couldn't save. Check storage and try again.")
            }
        }
    }
}

@Composable
fun LabelsScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> LabelsViewModel(c, h) }
    val context = LocalContext.current
    val v by vm.variant.collectAsStateWithLifecycle()
    val preset by vm.preset.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { vm.savePdf(context, it) }
    var more by rememberSaveable { mutableStateOf(false) }
    SubScreen("Labels", { vm.cancel(); nav.popBackStack() }) { pad ->
        val variant = v ?: return@SubScreen
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = BodyArrangement) {
            VariantHeader(variant)
            Text("Making labels does not change stock.", fontSize = 15.sp)
            val preview by produceState<android.graphics.Bitmap?>(null, variant, vm.type, preset) {
                value = withContext(Dispatchers.Default) { LabelPdf.preview(variant, vm.type, preset, 600) }
            }
            preview?.let { Image(it.asImageBitmap(), "Label preview", Modifier.fillMaxWidth().border(1.dp, Color.Gray)) }
            QtyStepper(vm.copies, { vm.set(copies = it) }, min = 1, max = 20_000, label = "Number of labels")
            Text("Label type", fontSize = 17.sp)
            ChoiceRow {
                ChoiceButton("Barcode (Code 128)", vm.type == LabelType.CODE128, { vm.set(type = LabelType.CODE128) })
                ChoiceButton("QR code", vm.type == LabelType.QR, { vm.set(type = LabelType.QR) })
            }
            Text("Sticker sheet", fontSize = 17.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelPreset.entries.forEach { p -> ChoiceButton(p.title, preset == p, { vm.setPreset(p) }) }
            }
            if (!more) SecondaryButton("More options", { more = true })
            else if (preset.perPage > 1) QtyStepper(vm.startCell + 1, { vm.set(start = it - 1) }, min = 1, max = preset.perPage, label = "Start at sticker number (for part-used sheets)")
            Text("Print at actual size (100%). Check one sheet before printing many.", fontSize = 15.sp)

            when (val s = vm.pdf) {
                PdfState.Idle -> BigButton(if (vm.copies == 1) "Make 1 label" else "Make ${vm.copies} labels", { vm.make(context) })
                is PdfState.Making -> MessageCard(Msg(Tone.INFO, "Preparing labels…", if (s.page > 0) "Page ${s.page}" else null)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    SecondaryButton("Cancel", vm::cancel)
                }
                is PdfState.Ready -> MessageCard(Msg(Tone.SUCCESS, "Labels ready — ${s.labels} labels", s.status)) {
                    BigButton("Save PDF", { save.launch("Labels_${variant.code}.pdf") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Print", { if (print(context, s.file)) vm.status("Print submitted — check the printer") }, Modifier.weight(1f))
                        SecondaryButton("Share", {
                            shareDocument(context, FileProvider.getUriForFile(context, context.packageName + ".files", s.file), "application/pdf")
                        }, Modifier.weight(1f))
                    }
                    Text("No printer? Save PDF to print elsewhere.", fontSize = 15.sp)
                    SecondaryButton("Done", { nav.popBackStack() })
                }
                is PdfState.Failed -> MessageCard(Msg(Tone.ERROR, s.text)) { SecondaryButton("Try again", { vm.make(context) }) }
            }
            vm.msg?.let { MessageCard(it) }
        }
    }
}

/** Hands the PDF to Android's print dialog; the user picks a printer. Returns true if a job was created. */
private fun print(context: Context, file: File): Boolean {
    val pm = context.getSystemService(PrintManager::class.java) ?: return false
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel.isCanceled) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder("labels.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }

        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal, cb: WriteResultCallback) {
            // Copy off the main thread; callbacks may be invoked from any thread.
            Thread {
                try {
                    file.inputStream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(dest).use { input.copyTo(it) } }
                    if (cancel.isCanceled) cb.onWriteCancelled() else cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb.onWriteFailed("Could not send labels")
                }
            }.start()
        }
    }
    return runCatching { pm.print("Labels", adapter, null) }.isSuccess
}
