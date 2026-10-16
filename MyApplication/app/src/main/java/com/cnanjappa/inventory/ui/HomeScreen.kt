package com.cnanjappa.inventory.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.export.ExcelReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface ExcelState {
    data object Idle : ExcelState
    data class Preparing(val stage: String) : ExcelState
    data class Ready(val file: File, val name: String) : ExcelState
    data class Saved(val uri: Uri) : ExcelState
    data class Failed(val text: String) : ExcelState
}

class ExcelViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    var excel by mutableStateOf<ExcelState>(ExcelState.Idle)
        private set
    private var job: Job? = null

    /** The save picker opens once per ready report, even if the screen is recreated meanwhile. */
    var pickerOpened = false

    fun startExcel(context: Context) {
        if (excel is ExcelState.Preparing) return
        val file = File(context.cacheDir, "exports/report.xlsx").apply { parentFile?.mkdirs() }
        val name = "C_Nanjappa_Inventory_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".xlsx"
        excel = ExcelState.Preparing("Starting")
        job = viewModelScope.launch {
            excel = try {
                ExcelReport(c.db).write(file) { stage -> excel = ExcelState.Preparing(stage) }
                ExcelState.Ready(file, name)
            } catch (e: CancellationException) {
                file.delete(); ExcelState.Idle
            } catch (e: Exception) {
                file.delete(); ExcelState.Failed("Could not prepare the Excel report. Try again")
            }
        }
    }

    fun cancelExcel() { job?.cancel(); excel = ExcelState.Idle }

    fun saveExcel(context: Context, uri: Uri?) {
        val ready = excel as? ExcelState.Ready ?: return
        pickerOpened = false
        if (uri == null) { ready.file.delete(); excel = ExcelState.Failed("Excel not saved"); return }
        viewModelScope.launch {
            excel = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { o -> ready.file.inputStream().use { it.copyTo(o) } }
                }
                ExcelState.Saved(uri)
            } catch (e: Exception) {
                ExcelState.Failed("Couldn't save. Check storage and try again.")
            } finally {
                ready.file.delete()
            }
        }
    }

    fun dismissExcel() { excel = ExcelState.Idle }
}

/** Tighter sides than a normal button so two fit one line each on a small phone. */
val PairPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Home: Sell fills the screen (the action used all day); Return is a small button under it. */
@Composable
fun HomeScreen(nav: NavHostController) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SellTile(Modifier.weight(1f)) { nav.go(Routes.scan(ScanMode.SELL)) }
        SecondaryButton("Return an item", { nav.go(Routes.scan(ScanMode.RETURN)) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), Icons.AutoMirrored.Filled.AssignmentReturn)
    }
}

/**
 * "Excel report" button (with [beside] sharing its row) plus its progress / saved / failed card;
 * shown at the top of the Stock tab.
 */
@Composable
fun ExcelPanel(beside: @Composable RowScope.() -> Unit = {}) {
    val vm = appViewModel { c, h -> ExcelViewModel(c, h) }
    val context = LocalContext.current
    val saveExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { vm.saveExcel(context, it) }
    val excel = vm.excel
    LaunchedEffect(excel) {
        if (excel is ExcelState.Ready && !vm.pickerOpened) { vm.pickerOpened = true; saveExcel.launch(excel.name) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Occasional office task: a small button, so daily stock actions stay the big ones.
        // Equal pair: same width and height (fillMaxHeight in [beside] too), even if large text wraps one.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            SecondaryButton(
                "Excel report", { vm.startExcel(context) }, Modifier.weight(1f).fillMaxHeight(), Icons.Default.TableChart,
                enabled = excel !is ExcelState.Preparing, contentPadding = PairPadding,
            )
            beside()
        }
        AnimatedVisibility(excel !is ExcelState.Idle) {
            when (excel) {
                is ExcelState.Preparing -> MessageCard(Msg(Tone.INFO, "Preparing Excel report…", excel.stage)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    SecondaryButton("Cancel", vm::cancelExcel)
                }
                is ExcelState.Saved -> MessageCard(Msg(Tone.SUCCESS, "Excel saved")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Open", { openDocument(context, excel.uri, XLSX_MIME) { vm.msg = Msg(Tone.INFO, "Excel saved. Open it on a computer") } }, Modifier.weight(1f))
                        SecondaryButton("Share", { shareDocument(context, excel.uri, XLSX_MIME) }, Modifier.weight(1f))
                        SecondaryButton("Done", vm::dismissExcel, Modifier.weight(1f))
                    }
                }
                is ExcelState.Failed -> MessageCard(Msg(Tone.ERROR, excel.text)) { SecondaryButton("OK", vm::dismissExcel) }
                else -> {}
            }
        }
        vm.msg?.let { MessageCard(it) { SecondaryButton("OK", { vm.msg = null }) } }
    }
}

/**
 * The main daily action: one huge tile. Colours come from the theme pair primary/onPrimary, so text
 * stays high-contrast in both modes (white on deep blue in light, dark navy on light blue in dark).
 */
@Composable
private fun SellTile(modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = CardShape, color = scheme.primary, contentColor = scheme.onPrimary,
        modifier = modifier.fillMaxWidth().heightIn(min = 260.dp),
    ) {
        Column(
            Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            IconBadge(Icons.Default.QrCodeScanner, scheme.onPrimary, scheme.onPrimary.copy(alpha = 0.12f), 120)
            Text("Sell", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = scheme.onPrimary)
            Text("Scan a label to sell 1 piece", style = MaterialTheme.typography.titleMedium, color = scheme.onPrimary.copy(alpha = 0.85f), textAlign = TextAlign.Center)
        }
    }
}

fun openDocument(context: Context, uri: Uri, mime: String, onNoApp: () -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        onNoApp()
    }
}

fun shareDocument(context: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(send, "Share")) } catch (_: ActivityNotFoundException) {}
}
