package com.cnanjappa.inventory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.data.LinkResult
import com.cnanjappa.inventory.data.Variant
import com.cnanjappa.inventory.domain.Codes
import com.cnanjappa.inventory.domain.NOTE_MAX
import com.cnanjappa.inventory.domain.ScannedCode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VariantViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val id: Long = handle["id"]!!
    val variant = repo.dao.observeVariant(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val aliases = repo.dao.aliasesFor(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Code waiting for a deliberate "Link barcode" tap: from the product picker or the scanner. */
    val pendingLink = combine(
        handle.getStateFlow<String?>("linkRaw", null), handle.getStateFlow<String?>("linkFmt", null),
        handle.getStateFlow<String?>("capturedRaw", null), handle.getStateFlow<String?>("capturedFmt", null),
    ) { lr, lf, cr, cf ->
        when {
            cr != null -> ScannedCode(cr, cf ?: Codes.TYPED)
            lr != null -> ScannedCode(lr, lf ?: Codes.TYPED)
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var restockQty by mutableStateOf(handle["restockQty"] ?: 1)
    var lastSaleId by mutableStateOf<Long?>(null)
    var restocked by mutableStateOf<Int?>(null)
    var takenBy by mutableStateOf<Long?>(null)

    init {
        recover(KEY_SELL) { r -> msg = Msg(Tone.SUCCESS, "1 item reduced — ${piecesLeft(r.remaining!!)}"); lastSaleId = r.refId }
        recover(KEY_RESTOCK) { r -> msg = Msg(Tone.SUCCESS, "${pieces(r.qty!!)} added — ${piecesLeft(r.remaining!!)}") }
        recover(KEY_CORRECT) { r -> msg = Msg(Tone.SUCCESS, "Stock corrected — ${piecesLeft(r.remaining!!)}") }
    }

    private fun clearResult() { lastSaleId = null; restocked = null; takenBy = null }

    fun sell() {
        clearResult()
        op(KEY_SELL, { repo.sell(it, id) }) { r ->
            if (r.ok) { msg = Msg(Tone.SUCCESS, "1 item reduced — ${piecesLeft(r.remaining!!)}"); lastSaleId = r.refId } else msg = failMsg(r.code)
        }
    }

    fun undo() {
        val sale = lastSaleId ?: return
        op(KEY_UNDO, { repo.undoSale(it, sale) }) { r ->
            lastSaleId = null
            msg = if (r.ok) Msg(Tone.SUCCESS, "Sale undone — ${piecesLeft(r.remaining!!)}") else failMsg(r.code)
        }
    }

    fun setRestock(n: Int) { restockQty = n; handle["restockQty"] = n }

    fun restock() {
        clearResult()
        val n = restockQty
        op(KEY_RESTOCK, { repo.restock(it, id, n) }) { r ->
            if (r.ok) { msg = Msg(Tone.SUCCESS, "${pieces(n)} added — ${piecesLeft(r.remaining!!)}"); restocked = n; setRestock(1) } else msg = failMsg(r.code)
        }
    }

    fun correct(expected: Int, newQty: Int, reason: String, onDone: () -> Unit) {
        clearResult()
        op(KEY_CORRECT, { repo.correct(it, id, expected, newQty, reason) }) { r ->
            if (r.ok) { msg = Msg(Tone.SUCCESS, "Stock corrected — ${piecesLeft(r.remaining!!)}"); onDone() } else msg = failMsg(r.code)
        }
    }

    fun link(code: ScannedCode, share: Boolean) {
        clearResult()
        viewModelScope.launch {
            val r = repo.link(id, code, share)
            when (r.result) {
                LinkResult.LINKED -> { msg = Msg(Tone.SUCCESS, "Barcode linked", "Stock unchanged"); clearPending() }
                LinkResult.ALREADY -> { msg = Msg(Tone.INFO, "This barcode is already linked to this item"); clearPending() }
                LinkResult.SHARED -> { msg = Msg(Tone.SUCCESS, "Saved as shared label", "Staff will choose the exact item when scanning it. Stock unchanged."); clearPending() }
                LinkResult.TAKEN -> { msg = failMsg(com.cnanjappa.inventory.data.Code.LABEL_TAKEN); takenBy = r.otherVariantId }
            }
        }
    }

    fun clearPending() {
        listOf("linkRaw", "linkFmt", "capturedRaw", "capturedFmt").forEach { handle[it] = null }
        takenBy = null
    }

    fun setArchived(archived: Boolean) = viewModelScope.launch {
        repo.setArchived(id, archived)
        msg = Msg(Tone.SUCCESS, if (archived) "Item archived" else "Item active again")
    }

    companion object {
        const val KEY_SELL = "sellOp"
        const val KEY_UNDO = "undoOp"
        const val KEY_RESTOCK = "restockOp"
        const val KEY_CORRECT = "correctOp"
    }
}

private val REASONS = listOf("Count correction", "Damaged", "Lost", "Other")

@Composable
fun VariantScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> VariantViewModel(c, h) }
    val v by vm.variant.collectAsStateWithLifecycle()
    val aliases by vm.aliases.collectAsStateWithLifecycle()
    val pending by vm.pendingLink.collectAsStateWithLifecycle()
    SubScreen("Product", { nav.popBackStack() }) { pad ->
        val variant = v ?: return@SubScreen
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = BodyArrangement,
        ) {
            VariantHeader(variant)
            pending?.let { code ->
                MessageCard(Msg(Tone.INFO, "Link this barcode?", "${code.raw.take(80)} (${Codes.formatLabel(code.format)})\nLinking does not change stock.")) {
                    if (vm.takenBy == null) {
                        BigButton("Link barcode", { vm.link(code, share = false) })
                        SecondaryButton("Cancel", vm::clearPending)
                    }
                }
            }
            vm.msg?.let { m ->
                MessageCard(m) {
                    vm.lastSaleId?.let { SecondaryButton("Undo", vm::undo, enabled = !vm.busy) }
                    vm.restocked?.let { n -> SecondaryButton("Generate labels for these $n", { nav.navigate(Routes.labels(vm.id, n)) }) }
                    vm.takenBy?.let { other ->
                        SecondaryButton("View item", { nav.navigate(Routes.variant(other)) })
                        pending?.let { code ->
                            SecondaryButton("It is printed on both items (shared label)", { vm.link(code, share = true) })
                            SecondaryButton("Cancel", vm::clearPending)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Sell 1", vm::sell, Modifier.weight(1f), icon = Icons.Default.RemoveShoppingCart, enabled = !vm.busy && !variant.archived && variant.qty > 0)
                BigButton(
                    "Return 1", { nav.navigate(Routes.returnItem(vm.id)) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.AssignmentReturn,
                    container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (variant.qty == 0 && !variant.archived) FieldHint("No pieces available")

            SectionCard(title = "Add stock", subtitle = "New pieces of this exact item") {
                if (variant.archived) Text("Archived items cannot receive new stock. Make it active first.", style = MaterialTheme.typography.bodyMedium)
                else {
                    QtyStepper(vm.restockQty, vm::setRestock, min = 1, label = "Pieces received")
                    BigButton("Add stock", vm::restock, enabled = !vm.busy)
                }
            }

            SectionCard(title = "Correct stock", subtitle = "Counting errors, losses, damaged pieces") { CorrectSection(vm, variant) }

            SectionCard(title = "Labels", subtitle = "Existing manufacturer barcodes work without printing") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Print labels", { nav.navigate(Routes.labels(vm.id, 1)) }, Modifier.weight(1f))
                    SecondaryButton("For current stock", { nav.navigate(Routes.labels(vm.id, variant.qty.coerceAtLeast(1))) }, Modifier.weight(1f))
                }
                Text("Item code  ${variant.code}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard(title = "Barcodes", subtitle = if (aliases.isEmpty()) "No manufacturer barcode linked yet" else null) {
                aliases.forEach { a ->
                    Text("${a.raw.take(60)}  ·  ${Codes.formatLabel(a.format)}" + if (a.shared) "  ·  shared label" else "", style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Link barcode", { nav.navigate(Routes.scan(ScanMode.CAPTURE)) }, Modifier.weight(1f))
                    SecondaryButton("Link many", { nav.navigate(Routes.scan(ScanMode.LINK, vm.id)) }, Modifier.weight(1f))
                }
            }

            SectionCard(title = "Item details") {
                if (variant.notes.isNotBlank()) {
                    Text("Notes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(variant.notes, style = MaterialTheme.typography.bodyLarge)
                }
                SecondaryButton("Add another size / sleeve", { nav.navigate(Routes.form(copy = vm.id)) }, Modifier.fillMaxWidth())
                SecondaryButton("Edit details", { nav.navigate(Routes.form(edit = vm.id)) }, Modifier.fillMaxWidth())
                SecondaryButton(if (variant.archived) "Make active again" else "Archive item", { vm.setArchived(!variant.archived) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CorrectSection(vm: VariantViewModel, v: Variant) {
    var open by rememberSaveable { mutableStateOf(false) }
    var counted by rememberSaveable(v.id) { mutableStateOf(v.qty) }
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var other by rememberSaveable { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(open) { if (open) counted = v.qty }
    if (!open) { SecondaryButton("Correct stock", { open = true }, Modifier.fillMaxWidth()); return }
    QtyStepper(counted, { counted = it }, min = 0, label = "Pieces actually on the shelf")
    Text("Reason", style = MaterialTheme.typography.titleMedium)
    ChoiceRow { REASONS.forEach { r -> ChoiceButton(r, reason == r, { reason = r; hint = null }) } }
    if (reason == "Other") OutlinedTextField(other, { other = it.take(NOTE_MAX) }, Modifier.fillMaxWidth(), label = { Text("Short explanation") }, singleLine = true)
    FieldHint(hint)
    Text("Current: ${pieces(v.qty)}  →  New: ${pieces(counted)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    BigButton("Confirm: change ${v.qty} to $counted", {
        val text = when (reason) {
            null -> null
            "Other" -> other.trim().takeIf { it.isNotEmpty() }?.let { "Other: $it" }
            else -> reason
        }
        when {
            reason == null -> hint = "Choose a reason"
            text == null -> hint = "Write a short explanation"
            counted == v.qty -> hint = "The count is the same as current stock"
            else -> vm.correct(v.qty, counted, text) { open = false; reason = null; other = "" }
        }
    }, enabled = !vm.busy)
    SecondaryButton("Cancel", { open = false; hint = null })
}
