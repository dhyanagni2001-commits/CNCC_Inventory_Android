package com.cnanjappa.inventory.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.Resolution
import com.cnanjappa.inventory.domain.COLOUR_MAX
import com.cnanjappa.inventory.domain.Codes
import com.cnanjappa.inventory.domain.Colours
import com.cnanjappa.inventory.domain.Field
import com.cnanjappa.inventory.domain.FieldError
import androidx.compose.material.icons.filled.AddBox
import com.cnanjappa.inventory.domain.StoreBarcode
import com.cnanjappa.inventory.domain.NAME_MAX
import com.cnanjappa.inventory.domain.NOTES_MAX
import com.cnanjappa.inventory.domain.SIZE_MAX
import com.cnanjappa.inventory.domain.ScannedCode
import com.cnanjappa.inventory.domain.Sizes
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.Validate
import com.cnanjappa.inventory.domain.VariantInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class Saved(val id: Long, val qty: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class FormViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val editId: Long = handle["edit"] ?: -1L
    private val copyId: Long = handle["copy"] ?: -1L
    val isEdit get() = editId > 0

    private fun <T> field(key: String, initial: T) = mutableStateOf(handle[key] ?: initial)
    var company by field("company", "")
    var name by field("name", "")
    var model by field("model", "")
    var colour by field("colour", "")
    var size by field("size", "")
    // No default: Full and Half of one model are different items, so staff must choose.
    var sleeve by mutableStateOf(handle.get<String>("sleeve")?.let { Sleeve.valueOf(it) })
    var qty by field("qty", 0)
    var notes by field("notes", "")
    var code by mutableStateOf(handle.get<String>("codeRaw")?.let { ScannedCode(it, handle["codeFmt"] ?: Codes.TYPED) })
    var codeStatus by mutableStateOf<String?>(null)
    var shareCode by field("share", false)
    var errors by mutableStateOf<List<FieldError>>(emptyList())
    var saved by mutableStateOf<Saved?>(null)
    var duplicateOf by mutableStateOf<Long?>(null)
    var takenBy by mutableStateOf<Long?>(null)
    var confirmChange by mutableStateOf<Pair<String, String>?>(null)

    // Edit only: the item's barcodes as they will be after saving, plus what it had when loaded.
    var codes by mutableStateOf(
        handle.get<ArrayList<String>>("codesRaw").orEmpty().zip(handle.get<ArrayList<String>>("codesFmt").orEmpty()) { r, f -> ScannedCode(r, f) },
    )
    private var originalKeys: List<String> = handle["origKeys"] ?: emptyList()
    private var originalQty: Int = handle["origQty"] ?: 0
    private var initial = snapshot()
    private var original: com.cnanjappa.inventory.data.Variant? = null

    /** Most recently used saved choices; models follow the chosen product name. */
    val companies = repo.dao.companies(InventoryRepository.likePattern(""), 12).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val names = repo.dao.names(InventoryRepository.likePattern(""), 12).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val models = snapshotFlow { name.trim() }.debounce(150).flatMapLatest { repo.dao.models(it, InventoryRepository.likePattern(""), 12) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val colours = repo.dao.colours(100).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val extraSizes = repo.dao.extraSizes(
        (Sizes.LETTERS + Sizes.NUMBERS + Sizes.AGES).map { Sizes.canonical(it)!!.key },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        val source = if (isEdit) editId else copyId
        if (source > 0 && handle.get<Boolean>("loaded") != true) {
            viewModelScope.launch {
                val v = repo.dao.variant(source) ?: return@launch
                company = v.company; name = v.name; model = v.model; colour = v.colour; size = v.sizeLabel
                sleeve = if (v.sleeve == Sleeve.FULL.label) Sleeve.FULL else Sleeve.HALF
                notes = v.notes
                if (isEdit) {
                    val aliases = repo.dao.aliasesFor(v.id).first()
                    codes = aliases.map { ScannedCode(it.raw, it.format) }
                    originalKeys = aliases.map { it.key }; handle["origKeys"] = ArrayList(originalKeys)
                    qty = v.qty; originalQty = v.qty; handle["origQty"] = v.qty
                }
                handle["loaded"] = true
                persist()
                if (isEdit) { original = v; initial = snapshot() }
            }
        } else if (isEdit) viewModelScope.launch { original = repo.dao.variant(editId); initial = snapshot() }
        code?.let { checkCode(it) }
        recover(KEY) { r -> saved = Saved(r.variantId!!, r.qty ?: 0) }
        recover(KEY_EDIT) { saved = Saved(editId, 0) }
    }

    private fun snapshot() = listOf(company, name, model, colour, size, sleeve?.name, qty, code?.key, codes.map { it.key }, notes)
    val dirty get() = saved == null && snapshot() != initial

    /** Mirrors fields into saved state so rotation and process death keep the form. */
    fun persist() {
        handle["company"] = company; handle["name"] = name; handle["model"] = model; handle["colour"] = colour
        handle["size"] = size; handle["notes"] = notes; handle["sleeve"] = sleeve?.name; handle["qty"] = qty; handle["share"] = shareCode
        handle["codeRaw"] = code?.raw; handle["codeFmt"] = code?.format
        handle["codesRaw"] = ArrayList(codes.map { it.raw }); handle["codesFmt"] = ArrayList(codes.map { it.format })
    }

    fun update(block: FormViewModel.() -> Unit) {
        block()
        errors = emptyList(); duplicateOf = null
        persist()
    }

    var generating by mutableStateOf(false)
        private set

    /** A new in-store barcode for a tag that has none; it is never handed out again. */
    fun generateCode() {
        if (generating) return // a double tap must not use up two codes
        generating = true
        viewModelScope.launch {
            repo.newStoreBarcode()?.let(::onCode) ?: run { msg = Msg(Tone.ERROR, "Could not make a new barcode", "Scan or type the code instead.") }
            generating = false
        }
    }

    /** A scanned, typed or generated code: the new item's code, or one more barcode while editing. */
    fun onCode(c: ScannedCode) = if (isEdit) addCode(c) else changeCode(c)

    private fun addCode(c: ScannedCode) {
        if (codes.any { it.key == c.key }) return
        viewModelScope.launch {
            val other = when (val r = repo.resolve(c)) {
                is Resolution.One -> r.variant.takeIf { it.id != editId }?.description
                is Resolution.Many -> "${r.variants.size} items (shared label)"
                Resolution.Unknown -> null
            }
            if (other != null) msg = Msg(Tone.ERROR, "Label belongs to another item", "${c.raw.take(80)} is linked to $other")
            else { update { codes = codes + c }; msg = null }
        }
    }

    fun removeCode(c: ScannedCode) = update { codes = codes - c }

    fun changeCode(c: ScannedCode?) {
        update { code = c; shareCode = false; takenBy = null; codeStatus = null }
        c?.let { checkCode(it) }
    }

    private fun checkCode(c: ScannedCode) = viewModelScope.launch {
        codeStatus = when (val r = repo.resolve(c)) {
            is Resolution.One -> "Already linked to: ${r.variant.description}"
            is Resolution.Many -> "Shared label already used on ${r.variants.size} items"
            Resolution.Unknown -> null
        }
    }

    fun error(f: Field) = errors.firstOrNull { it.field == f }?.message

    fun save(confirmed: Boolean = false) {
        val (nv, errs) = Validate.variant(VariantInput(company, name, model, colour, size, sleeve))
        val qtyOk = qty in 0..com.cnanjappa.inventory.domain.MAX_QTY
        val cleanNotes = Validate.notes(notes)
        // Every item must stay scannable: the tag's barcode, or one generated here and printed.
        val codeMissing = if (isEdit) codes.isEmpty() else code == null
        if (nv == null || !qtyOk || cleanNotes == null || codeMissing) {
            errors = errs + listOfNotNull(
                FieldError(Field.QTY, "Use a whole number, 0 or more").takeIf { !qtyOk },
                FieldError(Field.CODE, "Scan, type or generate a barcode").takeIf { codeMissing },
                FieldError(Field.NOTES, "Use $NOTES_MAX letters or fewer").takeIf { cleanNotes == null },
            )
            return
        }
        if (isEdit) {
            val o = original ?: return
            val changed = o.sizeKey != nv.size.key || o.colour.lowercase() != nv.colour.lowercase() || o.sleeve != nv.sleeve.label
            if (changed && !confirmed) {
                confirmChange = o.detail to listOf(nv.colour, "Size ${nv.size.label}", nv.sleeve.label).filter { it.isNotEmpty() }.joinToString(" · ")
                return
            }
            val add = codes.filter { it.key !in originalKeys }
            val remove = originalKeys.filter { k -> codes.none { it.key == k } }
            val newQty = qty
            op(KEY_EDIT, { repo.editItem(it, editId, nv, cleanNotes, originalQty, newQty, add, remove) }) { r ->
                when (r.code) {
                    Code.OK -> { saved = Saved(editId, 0); msg = null }
                    Code.DUPLICATE -> { duplicateOf = r.variantId; msg = failMsg(r.code) }
                    Code.LABEL_TAKEN -> msg = Msg(Tone.ERROR, "Label belongs to another item", "Remove that barcode, then save again.")
                    // A sale or restock happened meanwhile: show the real count, keep the typed one to re-check.
                    Code.STALE -> viewModelScope.launch {
                        val now = repo.dao.variant(editId)?.qty ?: return@launch
                        originalQty = now; handle["origQty"] = now
                        msg = Msg(Tone.ERROR, "Stock changed while you were editing", "It is now ${pieces(now)}. Check the quantity and save again.")
                    }
                    else -> msg = failMsg(r.code)
                }
            }
            return
        }
        op(KEY, { repo.createVariant(it, nv, qty, code, shareCode, cleanNotes) }) { r ->
            when (r.code) {
                Code.OK -> { saved = Saved(r.variantId!!, qty); msg = null }
                Code.DUPLICATE -> { duplicateOf = r.variantId; msg = failMsg(r.code) }
                Code.LABEL_TAKEN -> { takenBy = r.variantId; msg = Msg(Tone.ERROR, "Label belongs to another item", "If the same barcode is printed on both items, save it as a shared label.") }
                else -> msg = failMsg(r.code)
            }
        }
    }

    /** "Add another size / sleeve": keeps the details, clears quantity and barcode. */
    fun another() {
        saved = null; msg = null
        update { qty = 0; code = null; codeStatus = null; shareCode = false; takenBy = null }
        initial = snapshot()
    }

    companion object {
        const val KEY = "createOp"
        const val KEY_EDIT = "editOp"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> FormViewModel(c, h) }
    CapturedCodeEffect(vm::onCode)
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.dirty) { confirmLeave = true }
    val back = { if (vm.dirty) confirmLeave = true else nav.back() }
    val title = if (vm.isEdit) "Edit details" else "Add Product"
    // Sections are built only as they scroll into view, so opening the form doesn't stall the phone.
    val list = rememberLazyListState()
    LaunchedEffect(vm.errors) {
        val first = vm.errors.firstOrNull()?.field ?: return@LaunchedEffect
        list.animateScrollToItem(SECTION_OF.getValue(first))
    }
    SubScreen(title, { back() }) { pad ->
        val saved = vm.saved
        val names by vm.names.collectAsStateWithLifecycle()
        val models by vm.models.collectAsStateWithLifecycle()
        val companies by vm.companies.collectAsStateWithLifecycle()
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).imePadding(), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = BodyArrangement,
        ) {
            if (saved != null) { item("saved") { SavedPanel(vm, nav, saved) }; return@LazyColumn }
            item("item") {
                SectionCard(title = "Item", subtitle = "Tap a saved choice, or Add new") {
                    PickField("Product name *", vm.name, names, NAME_MAX, vm.error(Field.NAME), "e.g. White Shirt") { vm.update { name = it } }
                    PickField("Model / sub-name *", vm.model, models, NAME_MAX, vm.error(Field.MODEL), "Use General if there is no model") { vm.update { model = it } }
                    PickField("Company / brand (optional)", vm.company, companies, NAME_MAX, vm.error(Field.COMPANY), "e.g. Ramraj") { vm.update { company = it } }
                }
            }
            item("size") { SectionCard(title = "Size *") { SizeField(vm) } }
            item("sleeve") {
                SectionCard(title = "Sleeve *") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Sleeve.entries.forEach { s -> ChoiceButton(s.label, vm.sleeve == s, { vm.update { sleeve = s } }, Modifier.weight(1f)) }
                    }
                    FieldHint(vm.error(Field.SLEEVE))
                }
            }
            item("colour") { SectionCard(title = "Colour *") { ColourField(vm) } }
            item("qty") {
                SectionCard(
                    title = if (vm.isEdit) "Quantity in stock *" else "Opening quantity *",
                    subtitle = if (vm.isEdit) "A change is saved in History as a correction" else "Pieces of this exact item received",
                ) {
                    QtyStepper(vm.qty, { vm.update { qty = it } }, min = 0)
                    FieldHint(vm.error(Field.QTY))
                }
            }
            item("code") {
                SectionCard(
                    title = if (vm.isEdit) "Barcodes *" else "Barcode *",
                    subtitle = "Scan or type the code on the tag. No barcode on the tag? Generate a new one.",
                ) { if (vm.isEdit) EditCodesField(vm, nav) else CodeField(vm, nav) }
            }
            item("notes") {
                SectionCard(title = "Notes", subtitle = "Optional — anything extra: fabric, supplier, rack, price tag...") {
                    OutlinedTextField(
                        value = vm.notes, onValueChange = { vm.update { notes = it.take(NOTES_MAX) } },
                        modifier = Modifier.fillMaxWidth().testTag("notes"), minLines = 3, maxLines = 6,
                        placeholder = { Text("e.g. Cotton, from Erode supplier, rack 4") }, isError = vm.error(Field.NOTES) != null,
                        supportingText = { Text("${vm.notes.length} / $NOTES_MAX") },
                    )
                    FieldHint(vm.error(Field.NOTES))
                }
            }
            item("save") {
                Column(verticalArrangement = BodyArrangement) {
                    vm.msg?.let { m ->
                        MessageCard(m) {
                            vm.duplicateOf?.let { id -> BigButton(if (vm.isEdit) "View item" else "Add stock", { nav.go(Routes.variant(id)) }) }
                            vm.takenBy?.let { id ->
                                SecondaryButton("View item", { nav.go(Routes.variant(id)) })
                                SecondaryButton("Save as shared label", { vm.update { shareCode = true }; vm.takenBy = null; vm.msg = null; vm.save() })
                            }
                        }
                    }
                    if (vm.errors.isNotEmpty()) FieldHint("Check the highlighted fields")
                    if (vm.isEdit) BigButton("Save changes", { vm.save() }, enabled = !vm.busy)
                    else {
                        BigButton("Save product", { vm.save() }, enabled = !vm.busy)
                    }
                }
            }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Unsaved changes") },
        text = { Text("Leave without saving?") },
        confirmButton = { TextButton({ confirmLeave = false; nav.back() }) { Text("Leave") } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("Keep editing") } },
    )
    vm.confirmChange?.let { (old, new) ->
        AlertDialog(
            onDismissRequest = { vm.confirmChange = null },
            title = { Text("Change item details?") },
            text = { Text("Old: $old\nNew: $new\n\nStock and history stay with this item. Old sales keep their old description.") },
            confirmButton = { TextButton({ vm.confirmChange = null; vm.save(confirmed = true) }) { Text("Change") } },
            dismissButton = { TextButton({ vm.confirmChange = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SavedPanel(vm: FormViewModel, nav: NavHostController, s: Saved) {
    if (vm.isEdit) {
        MessageCard(Msg(Tone.SUCCESS, "Changes saved")) { BigButton("Done", { nav.back() }) }
        return
    }
    MessageCard(Msg(Tone.SUCCESS, "Product added — ${pieces(s.qty)}")) {
        BigButton("Add another size / sleeve", vm::another)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("View product", { nav.go(Routes.variant(s.id)) { popUpTo(Routes.FORM) { inclusive = true } } }, Modifier.weight(1f))
            SecondaryButton("Done", { nav.back() }, Modifier.weight(1f))
        }
    }
}

/** Form section (LazyColumn item index) holding each field, for scrolling to the first error. */
private val SECTION_OF = mapOf(
    Field.NAME to 0, Field.MODEL to 0, Field.COMPANY to 0, Field.SIZE to 1, Field.SLEEVE to 2,
    Field.COLOUR to 3, Field.QTY to 4, Field.CODE to 5, Field.NOTES to 6,
)

/** Tap a saved value, or "Add new" to type one. Typing is only needed the first time. */
@Composable
private fun PickField(label: String, value: String, options: List<String>, max: Int, error: String?, placeholder: String, onValue: (String) -> Unit) {
    var typing by rememberSaveable { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val shown = options.filterNot { it.equals(value.trim(), ignoreCase = true) }
    val selectedKnown = value.isNotBlank() && options.any { it.equals(value.trim(), ignoreCase = true) }
    // A new (not yet saved) value keeps the text box open while it is typed.
    val showField = typing || options.isEmpty() || (value.isNotBlank() && !selectedKnown)
    if (options.isNotEmpty()) ChoiceRow {
        if (selectedKnown) ChoiceButton(value.trim(), true, { onValue("") })
        shown.forEach { o -> ChoiceButton(o, false, { onValue(o); typing = false }) }
        ChoiceButton("+ Add new", showField, { typing = true; if (selectedKnown) onValue("") })
    }
    if (showField) {
        OutlinedTextField(
            value = value, onValueChange = { onValue(it.take(max)) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, placeholder = { Text(placeholder) }, isError = error != null,
        )
    }
    FieldHint(error)
}

@Composable
private fun SizeField(vm: FormViewModel) {
    val extra by vm.extraSizes.collectAsStateWithLifecycle()
    var ages by rememberSaveable { mutableStateOf(Sizes.canonical(vm.size)?.type == com.cnanjappa.inventory.domain.SizeType.AGE) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val selectedKey = Sizes.canonical(vm.size)?.key
    fun pick(s: String) { vm.update { size = if (Sizes.canonical(s)?.key == selectedKey) "" else s } }
    @Composable fun option(s: String, text: String = s) = ChoiceButton(text, Sizes.canonical(s)?.key == selectedKey, { pick(s) })
    Text("Letter sizes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceRow { Sizes.LETTERS.forEach { option(it, Sizes.LETTER_HINTS[it]?.let { h -> "$it ($h)" } ?: it) } }
    Text("Number sizes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceRow { Sizes.NUMBERS.forEach { option(it) } }
    SecondaryButton(if (ages) "Hide age sizes" else "Age sizes (years)", { ages = !ages })
    if (ages) ChoiceRow { Sizes.AGES.forEach { option(it, "$it yrs") } }
    if (extra.isNotEmpty()) {
        Text("Saved sizes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceRow { extra.forEach { option(it) } }
    }
    val c = Sizes.canonical(vm.size)
    if (c != null && listOf(Sizes.LETTERS, Sizes.NUMBERS, Sizes.AGES, extra).none { l -> l.any { Sizes.canonical(it)?.key == c.key } }) {
        ChoiceRow { ChoiceButton(c.label, true, { pick(vm.size) }) }
    }
    if (!adding) SecondaryButton("Add size", { adding = true })
    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(typed, { typed = it.take(SIZE_MAX) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("e.g. 32 or Free Size") })
        SecondaryButton("Use size", { if (typed.isNotBlank()) { vm.update { size = typed.trim() }; typed = ""; adding = false } })
    }
    FieldHint(vm.error(Field.SIZE))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColourField(vm: FormViewModel) {
    val saved by vm.colours.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    val others = saved.filterNot { s -> Colours.FIRST.any { it.equals(s, true) } }
    fun pick(c: String) { vm.update { colour = if (colour.equals(c, true)) "" else c } }
    ChoiceRow {
        Colours.FIRST.forEach { ChoiceButton(it, vm.colour.equals(it, true), { pick(it) }, swatch = swatchFor(it)) }
        val current = vm.colour.takeIf { c -> c.isNotBlank() && (Colours.FIRST + others.take(10)).none { it.equals(c, true) } }
        current?.let { ChoiceButton(it, true, { pick(it) }, swatch = swatchFor(it)) }
        others.take(10).forEach { ChoiceButton(it, vm.colour.equals(it, true), { pick(it) }, swatch = swatchFor(it)) }
        ChoiceButton("+ More", false, { sheet = true })
    }
    FieldHint(vm.error(Field.COLOUR))
    if (sheet) {
        var typed by rememberSaveable { mutableStateOf("") }
        var hint by remember { mutableStateOf<String?>(null) }
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = BodyArrangement) {
                Text("Choose colour", style = MaterialTheme.typography.titleLarge)
                if (others.isNotEmpty()) ChoiceRow { others.forEach { ChoiceButton(it, vm.colour.equals(it, true), { pick(it); sheet = false }, swatch = swatchFor(it)) } }
                HorizontalDivider()
                OutlinedTextField(
                    typed, { typed = it.take(COLOUR_MAX); hint = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Type colour") }, supportingText = { Text("Up to $COLOUR_MAX letters") }, isError = hint != null,
                )
                FieldHint(hint)
                BigButton("Use colour", {
                    if (typed.isBlank()) hint = "Enter a colour"
                    else { vm.update { colour = Colours.resolve(typed, saved) }; sheet = false }
                })
            }
        }
    }
}

@Composable
private fun CodeField(vm: FormViewModel, nav: NavHostController) {
    val code = vm.code
    if (code != null) {
        Text("${code.raw.take(80)} (${Codes.formatLabel(code.format)})", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        vm.codeStatus?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 15.sp) }
        if (vm.shareCode) Text("Will be saved as a shared label", fontSize = 15.sp)
        if (StoreBarcode.isStore(code.raw)) Text("New shop barcode. After saving, print it from the product page (Labels).", fontSize = 15.sp)
        SecondaryButton("Remove barcode", { vm.changeCode(null) })
        return
    }
    AddCodeButtons(vm, nav)
}

/** Edit: every barcode on the item, each removable, and the same ways to add one more. */
@Composable
private fun EditCodesField(vm: FormViewModel, nav: NavHostController) {
    vm.codes.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${c.raw.take(60)} (${Codes.formatLabel(c.format)})", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            TextButton({ vm.removeCode(c) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
    }
    AddCodeButtons(vm, nav)
}

@Composable
private fun AddCodeButtons(vm: FormViewModel, nav: NavHostController) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Scan barcode", { nav.go(Routes.scan(ScanMode.CAPTURE)) }, Modifier.weight(1f), Icons.Default.QrCodeScanner)
        SecondaryButton("Type code", { typing = true }, Modifier.weight(1f))
    }
    if (typing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(typed, { typed = it.take(200).filterNot(Char::isISOControl) }, Modifier.weight(1f), singleLine = true, label = { Text("Barcode number") })
        SecondaryButton("Use", { if (typed.isNotBlank()) { vm.onCode(ScannedCode(typed.trim(), Codes.TYPED)); typed = ""; typing = false } })
    }
    SecondaryButton("Generate new barcode", vm::generateCode, Modifier.fillMaxWidth(), Icons.Default.AddBox, enabled = !vm.generating)
    FieldHint(vm.error(Field.CODE))
}
