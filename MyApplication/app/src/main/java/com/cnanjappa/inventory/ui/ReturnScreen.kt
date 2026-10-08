package com.cnanjappa.inventory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.data.Code
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date

class ReturnViewModel(c: Container, handle: SavedStateHandle) : OpViewModel(c, handle) {
    val id: Long = handle["id"]!!
    val variant = repo.dao.observeVariant(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sales = repo.dao.eligibleSales(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var done by mutableStateOf(false)
    var noSale by mutableStateOf(false)

    init {
        recover(KEY) { r -> done = true; msg = Msg(Tone.SUCCESS, "Return recorded — ${piecesLeft(r.remaining!!)}") }
    }

    /** saleId null = newest eligible sale for this exact variant (default accounting allocation). */
    fun submit(saleId: Long?, qty: Int, damaged: Boolean) {
        op(KEY, { repo.returnItem(it, id, saleId, qty, damaged) }) { r ->
            noSale = r.code == Code.NO_SALE
            if (r.ok) {
                done = true
                msg = if (damaged) Msg(Tone.SUCCESS, "Return recorded — stock unchanged (damaged)")
                else Msg(Tone.SUCCESS, "${if (qty == 1) "1 item" else "$qty items"} returned — ${piecesLeft(r.remaining!!)}")
            } else msg = failMsg(r.code)
        }
    }

    fun again() { done = false; msg = null }

    companion object { const val KEY = "returnOp" }
}

@Composable
fun ReturnScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> ReturnViewModel(c, h) }
    val v by vm.variant.collectAsStateWithLifecycle()
    val sales by vm.sales.collectAsStateWithLifecycle()
    var more by rememberSaveable { mutableStateOf(false) }
    var damaged by rememberSaveable { mutableStateOf(false) }
    var qty by rememberSaveable { mutableStateOf(1) }
    var saleId by rememberSaveable { mutableStateOf<Long?>(null) }
    SubScreen("Returning item", { nav.popBackStack() }) { pad ->
        val variant = v ?: return@SubScreen
        val eligible = sales ?: return@SubScreen
        val chosen = eligible.firstOrNull { it.id == saleId }
        val limit = (chosen ?: eligible.firstOrNull())?.remaining ?: 0
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = BodyArrangement) {
            VariantHeader(variant)
            vm.msg?.let { m ->
                MessageCard(m) {
                    if (vm.done) {
                        BigButton("Return another", { vm.again(); qty = 1; damaged = false; saleId = null })
                        SecondaryButton("Done", { nav.popBackStack() })
                    }
                    if (m.text == failMsg(Code.LIMIT).text) SecondaryButton("Choose another sale", { more = true; saleId = null; vm.msg = null })
                    if (vm.noSale) SecondaryButton("Correct stock", { nav.navigate(Routes.variant(vm.id)) })
                }
            }
            if (vm.done) return@Column
            if (eligible.isEmpty()) {
                MessageCard(Msg(Tone.ERROR, "No sale found for this item", "A return must match a recorded sale. If the count is wrong, correct the stock instead.")) {
                    SecondaryButton("Correct stock", { nav.navigate(Routes.variant(vm.id)) })
                }
                return@Column
            }
            val label = when {
                damaged -> "Record damaged return"
                qty > 1 -> "Return $qty"
                else -> "Return 1"
            }
            BigButton(label, { vm.submit(saleId, qty, damaged) }, enabled = !vm.busy && qty in 1..limit)
            if (!more) SecondaryButton("More options", { more = true })
            else {
                SectionTitle("Condition")
                ChoiceRow {
                    ChoiceButton("Good — back in stock", !damaged, { damaged = false })
                    ChoiceButton("Damaged — do not put back in stock", damaged, { damaged = true })
                }
                QtyStepper(qty.coerceAtMost(limit.coerceAtLeast(1)), { qty = it }, min = 1, max = limit.coerceAtLeast(1), label = "Pieces returned")
                SectionTitle("Which sale")
                Text("Default: the newest sale of this item.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceButton("Newest sale (default)", saleId == null, { saleId = null })
                val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                eligible.forEach { s ->
                    ChoiceButton("${fmt.format(Date(s.ts))} · ${s.remaining} returnable", saleId == s.id, { saleId = s.id; qty = 1 })
                }
            }
        }
    }
}
