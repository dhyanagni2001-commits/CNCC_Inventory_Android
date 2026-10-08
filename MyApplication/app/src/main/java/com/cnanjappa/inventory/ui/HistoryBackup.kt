package com.cnanjappa.inventory.ui

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.MainActivity
import com.cnanjappa.inventory.data.HistoryRow
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.MoveType
import com.cnanjappa.inventory.export.BackupException
import com.cnanjappa.inventory.export.BackupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

// ---------------- History ----------------

class HistoryViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val rows = Pager(PagingConfig(pageSize = 50, enablePlaceholders = false, maxSize = 500)) { repo.dao.history() }.flow.cachedIn(viewModelScope)
    var confirm by mutableStateOf<HistoryRow?>(null)

    init { recover(KEY) { r -> msg = Msg(Tone.SUCCESS, "Sale undone — ${piecesLeft(r.remaining!!)}") } }

    fun undo(row: HistoryRow) {
        val sale = row.saleId ?: return
        op(KEY, { repo.undoSale(it, sale) }) { r ->
            msg = if (r.ok) Msg(Tone.SUCCESS, "Sale undone — ${pieces(r.qty!!)} back, ${piecesLeft(r.remaining!!)}") else failMsg(r.code)
        }
    }

    companion object { const val KEY = "undoOp" }
}

@Composable
fun HistoryScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> HistoryViewModel(c, h) }
    val rows = vm.rows.collectAsLazyPagingItems()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    SubScreen("History", { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.msg?.let { m -> item { MessageCard(m) } }
            items(rows.itemCount, key = rows.itemKey { it.id }) { i ->
                val r = rows[i] ?: return@items
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val change = when {
                        r.type == MoveType.RETURN_DAMAGED -> "${pieces(r.qty)} · stock unchanged"
                        r.delta > 0 -> "+${r.delta}"
                        else -> "${r.delta}"
                    }
                    Text("${MoveType.label(r.type)}  $change", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(r.description, fontSize = 15.sp)
                    Text(fmt.format(Date(r.ts)) + (r.note?.let { " · $it" } ?: ""), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.type == MoveType.SALE && (r.saleRemaining ?: 0) > 0) SecondaryButton("Undo sale", { vm.confirm = r }, enabled = !vm.busy)
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
            if (rows.itemCount == 0) item { EmptyText("Nothing recorded yet.") }
        }
    }
    vm.confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.confirm = null },
            title = { Text("Undo this sale?") },
            text = { Text("${r.description}\nPuts ${pieces(r.saleRemaining ?: 0)} back in stock. The sale stays in history as undone.") },
            confirmButton = { TextButton({ vm.confirm = null; vm.undo(r) }) { Text("Undo sale") } },
            dismissButton = { TextButton({ vm.confirm = null }) { Text("Cancel") } },
        )
    }
}

// ---------------- Backup ----------------

class BackupViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val last = repo.dao.observeSetting(InventoryRepository.KEY_LAST_BACKUP).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var working by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Pair<BackupInfo, File>?>(null)
    var restored by mutableStateOf(false)

    fun backup(uri: android.net.Uri?) {
        if (uri == null || working != null) return
        working = "Backing up…"
        viewModelScope.launch {
            msg = try {
                val info = c.backup().backupTo(uri)
                repo.setSetting(InventoryRepository.KEY_LAST_BACKUP, info.createdAt.toString())
                Msg(Tone.SUCCESS, "Backup saved and checked", "${info.variants} items · ${pieces(info.pieces.toInt())} · ${info.movements} history rows")
            } catch (e: BackupException) {
                Msg(Tone.ERROR, e.message ?: "Backup failed")
            } catch (e: Exception) {
                Msg(Tone.ERROR, "Couldn't save. Check storage and try again.")
            }
            working = null
        }
    }

    fun pick(uri: android.net.Uri?) {
        if (uri == null || working != null) return
        working = "Checking backup…"
        viewModelScope.launch {
            try { preview = c.backup().prepareRestore(uri); msg = null }
            catch (e: BackupException) { msg = Msg(Tone.ERROR, e.message ?: "This backup file is damaged") }
            catch (e: Exception) { msg = Msg(Tone.ERROR, "Could not open this file") }
            working = null
        }
    }

    /** Safety backup first; if that fails nothing is replaced. */
    fun restore() {
        val (_, file) = preview ?: return
        preview = null
        working = "Restoring…"
        viewModelScope.launch {
            try {
                c.backup().safetyBackup()
            } catch (e: Exception) {
                msg = Msg(Tone.ERROR, "Could not make a safety copy. Nothing was changed")
                working = null
                return@launch
            }
            msg = try {
                withContext(Dispatchers.IO) { c.replaceDatabase(file) }
                restored = true
                Msg(Tone.SUCCESS, "Backup restored")
            } catch (e: Exception) {
                Msg(Tone.ERROR, "Could not restore. Your current data is unchanged")
            }
            working = null
        }
    }
}

@Composable
fun BackupScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> BackupViewModel(c, h) }
    val activity = LocalActivity.current
    val last by vm.last.collectAsStateWithLifecycle()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { vm.backup(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pick(it) }
    SubScreen("Backup", { nav.popBackStack() }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = BodyArrangement) {
            Text("Keep a backup outside this phone. Uninstalling the app or losing the phone can lose local data.", fontSize = 17.sp)
            Text("Last backup: " + (last?.toLongOrNull()?.let { fmt.format(Date(it)) } ?: "never"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            vm.working?.let { MessageCard(Msg(Tone.INFO, it)) { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            vm.msg?.let { MessageCard(it) }
            if (vm.restored) {
                BigButton("Open the app", {
                    activity?.let { a ->
                        a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                })
                return@Column
            }
            BigButton("Back up data", {
                create.launch("C_Nanjappa_Backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".cncbak")
            }, enabled = vm.working == null)
            SecondaryButton("Restore backup", { open.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = vm.working == null)
            Text("Restore replaces all stock and history on this phone with the backup. It does not merge.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    vm.preview?.let { (info, _) ->
        AlertDialog(
            onDismissRequest = { vm.preview = null },
            title = { Text("Replace current data?") },
            text = {
                Text(
                    "Backup from ${fmt.format(Date(info.createdAt))}\n${info.variants} items · ${pieces(info.pieces.toInt())}\n\n" +
                        "This replaces all current stock and history. A safety copy of the current data is made first.",
                )
            },
            confirmButton = { TextButton({ vm.restore() }) { Text("Restore") } },
            dismissButton = { TextButton({ vm.preview = null }) { Text("Cancel") } },
        )
    }
}
