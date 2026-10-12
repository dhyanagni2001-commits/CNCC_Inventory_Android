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
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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

    /**
     * Code waiting for a decision: from "Link to existing product" (needs a deliberate tap), or a
     * scanned code that already belongs to another item (refused: one barcode, one item).
     */
    val pendingLink = combine(handle.getStateFlow<String?>("linkRaw", null), handle.getStateFlow<String?>("linkFmt", null)) { raw, fmt ->
        raw?.let { ScannedCode(it, fmt ?: Codes.TYPED) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var restockQty by mutableStateOf(handle["restockQty"] ?: 1)
    var lastSaleId by mutableStateOf<Long?>(null)
    var restocked by mutableStateOf<Int?>(null)
    var takenBy by mutableStateOf<Long?>(null)

    /** Opened from the scanner's "Link to existing product": after linking, offer the way straight back. */
    private val fromScan = handle.get<String>("linkRaw") != null
    var backToScanner by mutableStateOf(false)
        private set

    init {
        recover(KEY_SELL) { r -> msg = Msg(Tone.SUCCESS, "1 item reduced — ${piecesLeft(r.remaining!!)}"); lastSaleId = r.refId }
        recover(KEY_RESTOCK) { r -> msg = Msg(Tone.SUCCESS, "${pieces(r.qty!!)} added — ${piecesLeft(r.remaining!!)}") }
        recover(KEY_DELETE) { deleted = true }
        recover(KEY_CORRECT) { r -> msg = Msg(Tone.SUCCESS, "Stock corrected — ${piecesLeft(r.remaining!!)}") }
    }

    private fun clearResult() { lastSaleId = null; restocked = null; takenBy = null; backToScanner = false }

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
                LinkResult.LINKED -> { msg = Msg(Tone.SUCCESS, "Barcode linked", "Stock unchanged"); clearPending(); backToScanner = fromScan }
                LinkResult.ALREADY -> { msg = Msg(Tone.INFO, "This barcode is already linked to this item"); clearPending(); backToScanner = fromScan }
                LinkResult.SHARED -> { msg = Msg(Tone.SUCCESS, "Saved as shared label", "Staff will choose the exact item when scanning it. Stock unchanged."); clearPending(); backToScanner = fromScan }
                LinkResult.TAKEN -> {
                    // Keep the code on screen so staff see which label was refused.
                    handle["linkRaw"] = code.raw; handle["linkFmt"] = code.format
                    msg = Msg(Tone.ERROR, "Barcode already used", "One barcode can only be on one item. Use another barcode, or generate a new one."); takenBy = r.otherVariantId
                }
            }
        }
    }

    var generating by mutableStateOf(false)
        private set

    /** Gives an item without a tag barcode a new shop barcode (never reused), ready to print. */
    fun generateBarcode() {
        if (generating) return // a double tap must not link two codes
        generating = true
        clearResult()
        viewModelScope.launch {
            val code = repo.newStoreBarcode()
            msg = if (code != null && repo.link(id, code, share = false).result == LinkResult.LINKED)
                Msg(Tone.SUCCESS, "New barcode ${code.raw}", "Print labels to stick it on the tag. Stock unchanged.")
            else Msg(Tone.ERROR, "Could not make a new barcode", "Scan or type the code instead.")
            generating = false
        }
    }

    fun clearPending() {
        listOf("linkRaw", "linkFmt").forEach { handle[it] = null }
        takenBy = null
    }

    var deleted by mutableStateOf(false)
        private set

    /** [expectedQty] is the count shown in the confirmation, so a sale meanwhile isn't silently written off. */
    fun delete(expectedQty: Int) {
        clearResult()
        op(KEY_DELETE, { repo.deleteItem(it, id, expectedQty) }) { r ->
            when {
                r.ok -> deleted = true
                r.code == com.cnanjappa.inventory.data.Code.STALE -> msg = Msg(Tone.ERROR, "Stock changed", "Check the pieces left, then delete again.")
                else -> msg = failMsg(r.code)
            }
        }
    }

    companion object {
        const val KEY_SELL = "sellOp"
        const val KEY_UNDO = "undoOp"
        const val KEY_RESTOCK = "restockOp"
        const val KEY_CORRECT = "correctOp"
        const val KEY_DELETE = "deleteOp"
    }
}

private val REASONS = listOf("Count correction", "Damaged", "Lost", "Other")

@Composable
fun VariantScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> VariantViewModel(c, h) }
    val v by vm.variant.collectAsStateWithLifecycle()
    val aliases by vm.aliases.collectAsStateWithLifecycle()
    val pending by vm.pendingLink.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // Scanned after tapping Link barcode: that tap was the decision, so link without asking again.
    CapturedCodeEffect { vm.link(it, share = false) }
    // Results show at the top; actions like Link barcode sit further down the page.
    LaunchedEffect(vm.msg) { if (vm.msg != null) scroll.animateScrollTo(0) }
    LaunchedEffect(vm.deleted) { if (vm.deleted) nav.popBackStack() }
    SubScreen("Product", { nav.back() }) { pad ->
        val variant = v ?: return@SubScreen
        if (variant.archived) {
            // Reached from an old record: a deleted item has nothing left to act on.
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = BodyArrangement) {
                VariantHeader(variant)
                MessageCard(Msg(Tone.INFO, "This item was deleted", "Its past sales stay in History and Excel."))
            }
            return@SubScreen
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = BodyArrangement,
        ) {
            VariantHeader(variant)
            pending?.takeIf { vm.takenBy == null }?.let { code ->
                MessageCard(Msg(Tone.INFO, "Link this barcode?", "${code.raw.take(80)} (${Codes.formatLabel(code.format)})\nLinking does not change stock.")) {
                    BigButton("Link barcode", { vm.link(code, share = false) })
                    SecondaryButton("Cancel", vm::clearPending)
                }
            }
            vm.msg?.let { m ->
                MessageCard(m) {
                    // Straight back to Sell/Return, skipping the product list it came through.
                    if (vm.backToScanner) BigButton("Back to scanner", { nav.popBackStack(Routes.SCAN, inclusive = false) })
                    vm.lastSaleId?.let { SecondaryButton("Undo", vm::undo, enabled = !vm.busy) }
                    vm.restocked?.let { n -> SecondaryButton("Generate labels for these $n", { nav.go(Routes.labels(vm.id, n)) }) }
                    vm.takenBy?.let { other ->
                        SecondaryButton("View item", { nav.go(Routes.variant(other)) })
                        if (pending != null) SecondaryButton("Cancel", vm::clearPending)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Sell 1", vm::sell, Modifier.weight(1f), icon = Icons.Default.RemoveShoppingCart, enabled = !vm.busy && variant.qty > 0)
                BigButton(
                    "Return 1", { nav.go(Routes.returnItem(vm.id)) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.AssignmentReturn,
                    container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (variant.qty == 0) FieldHint("No pieces available")

            SectionCard(title = "Add stock", subtitle = "New pieces of this exact item") {
                QtyStepper(vm.restockQty, vm::setRestock, min = 1, label = "Pieces received")
                BigButton("Add stock", vm::restock, enabled = !vm.busy)
            }

            SectionCard(title = "Correct stock", subtitle = "Counting errors, losses, damaged pieces") { CorrectSection(vm, variant) }

            SectionCard(title = "Labels", subtitle = "Existing manufacturer barcodes work without printing") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Print labels", { nav.go(Routes.labels(vm.id, 1)) }, Modifier.weight(1f))
                    SecondaryButton("For current stock", { nav.go(Routes.labels(vm.id, variant.qty.coerceAtLeast(1))) }, Modifier.weight(1f))
                }
                Text("Item code  ${variant.code}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard(title = "Barcodes", subtitle = if (aliases.isEmpty()) "No manufacturer barcode linked yet" else null) {
                aliases.forEach { a ->
                    Text("${a.raw.take(60)}  ·  ${Codes.formatLabel(a.format)}" + if (a.shared) "  ·  shared label" else "", style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Link barcode", { nav.go(Routes.scan(ScanMode.CAPTURE)) }, Modifier.weight(1f))
                    SecondaryButton("Link many", { nav.go(Routes.scan(ScanMode.LINK, vm.id)) }, Modifier.weight(1f))
                }
                SecondaryButton("Generate new barcode", vm::generateBarcode, Modifier.fillMaxWidth(), Icons.Default.AddBox, enabled = !vm.generating)
            }

            SectionCard(title = "Item details") {
                if (variant.notes.isNotBlank()) {
                    Text("Notes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(variant.notes, style = MaterialTheme.typography.bodyLarge)
                }
                SecondaryButton("Add another size / sleeve", { nav.go(Routes.form(copy = vm.id)) }, Modifier.fillMaxWidth())
                SecondaryButton("Edit details", { nav.go(Routes.form(edit = vm.id)) }, Modifier.fillMaxWidth())
            }
            DeleteItem(vm, variant)
        }
    }
}

/** Red "Delete item" with a confirmation that says exactly what happens. */
@Composable
private fun DeleteItem(vm: VariantViewModel, v: Variant) {
    var asking by rememberSaveable { mutableStateOf(false) }
    androidx.compose.material3.OutlinedButton(
        onClick = { asking = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Delete item", style = MaterialTheme.typography.labelLarge) }
    if (asking) {
        val qty = v.qty
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Delete this item?") },
            text = {
                Text(
                    "${v.title}\n${v.detail}\n\n" +
                        "• It disappears from Stock, search and scanning.\n" +
                        "• Its barcodes are freed for other items.\n" +
                        (if (qty > 0) "• Its ${pieces(qty)} are removed from stock.\n" else "") +
                        "• Past sales stay in History and Excel.\n\nThis can't be undone.",
                )
            },
            confirmButton = { TextButton({ asking = false; vm.delete(qty) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
        )
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
    if (reason == "Other") OutlinedTextField(other, { other = caps(it).take(NOTE_MAX) }, Modifier.fillMaxWidth(), label = { Text("Short explanation") }, singleLine = true, keyboardOptions = CapsKeyboard)
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
