package com.cnanjappa.inventory.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalDate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.export.ExcelReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

class HomeViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val total = repo.dao.totalPieces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Gentle reminder: changes since the last backup and the last backup is over a day old. */
    val backupDue = combine(repo.dao.lastChange(), repo.dao.observeSetting(InventoryRepository.KEY_LAST_BACKUP)) { change, last ->
        val lastTs = last?.toLongOrNull() ?: 0L
        change != null && change > lastTs && System.currentTimeMillis() - lastTs > 24 * 3600_000L
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
fun HomeScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> HomeViewModel(c, h) }
    val context = LocalContext.current
    val total by vm.total.collectAsStateWithLifecycle()
    val backupDue by vm.backupDue.collectAsStateWithLifecycle()
    val saveExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { vm.saveExcel(context, it) }
    val excel = vm.excel
    LaunchedEffect(excel) {
        if (excel is ExcelState.Ready && !vm.pickerOpened) { vm.pickerOpened = true; saveExcel.launch(excel.name) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HeroTile(
            "Sell", "Scan a label to sell 1 piece", Icons.Default.QrCodeScanner,
        ) { nav.navigate(Routes.scan(ScanMode.SELL)) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            ActionTile("Return", "Put a sold item back", Icons.AutoMirrored.Filled.AssignmentReturn, Modifier.weight(1f).fillMaxHeight()) {
                nav.navigate(Routes.scan(ScanMode.RETURN))
            }
            ActionTile("Add Product", "New item or delivery", Icons.Default.AddBox, Modifier.weight(1f).fillMaxHeight()) {
                nav.navigate(Routes.form())
            }
        }
        ActionTile(
            "Stock overview", "Save the full stock report as Excel", Icons.Default.TableChart, Modifier.fillMaxWidth(),
            enabled = excel !is ExcelState.Preparing,
        ) { vm.startExcel(context) }
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
        total?.let { n ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.Inventory2, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, 44)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pieces in stock", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$n", style = MaterialTheme.typography.headlineMedium)
                    }
                    TextButton(onClick = { nav.navigate("stock") { launchSingleTop = true } }) { Text("View stock") }
                }
            }
        }
        if (backupDue) MessageCard(Msg(Tone.INFO, "Back up today", "Keep a copy outside this phone.")) {
            SecondaryButton("Back up data", { nav.navigate(Routes.BACKUP) })
        }
    }
}

/** The main daily action: a large filled tile. */
@Composable
private fun HeroTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CardShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            IconBadge(icon, Color.White, Color.White.copy(alpha = 0.18f), 52)
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.heightIn(min = 120.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconBadge(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, 44)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
