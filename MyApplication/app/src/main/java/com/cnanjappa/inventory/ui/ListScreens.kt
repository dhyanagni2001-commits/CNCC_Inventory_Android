package com.cnanjappa.inventory.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.cnanjappa.inventory.Container
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.MoveType
import com.cnanjappa.inventory.data.Variant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

private val PAGING = PagingConfig(pageSize = 40, prefetchDistance = 20, enablePlaceholders = false, maxSize = 400)

/** Search-driven paged lists. Typing is debounced 150 ms and only the latest query runs. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ListViewModel(c: Container, handle: SavedStateHandle) : ViewModel() {
    private val dao = c.repo.dao
    val query = handle.getMutableStateFlow("q", "")
    private val debounced = query.debounce(150).distinctUntilChanged()

    val variants = debounced.flatMapLatest { q ->
        Pager(PAGING) { dao.variantsPaged(InventoryRepository.likePattern(q)) }.flow
    }.cachedIn(viewModelScope)

    /** Stock tab: every item except deleted ones (an old archived item that still holds pieces stays visible). */
    val stock = debounced.flatMapLatest { q ->
        Pager(PAGING) { dao.stockPaged(InventoryRepository.likePattern(q)) }.flow
    }.cachedIn(viewModelScope)

    /** Today's activity in the device (shop) timezone. */
    val today = run {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.totalsBetween(start, start + 86_400_000L)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val total = dao.totalPieces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
private fun SearchBox(vm: ListViewModel, label: String) {
    val q by vm.query.collectAsStateWithLifecycle()
    OutlinedTextField(
        value = q,
        onValueChange = { vm.query.value = it.take(60) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, "Clear search") } },
        placeholder = { Text(label) },
    )
}

@Composable
fun ProductsScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> ListViewModel(c, h) }
    val entry = nav.currentBackStackEntry
    val pickRaw = entry?.arguments?.getString("pickRaw")
    val pickFmt = entry?.arguments?.getString("pickFmt")
    val items = vm.variants.collectAsLazyPagingItems()
    val body: @Composable (PaddingValues) -> Unit = { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pickRaw != null) MessageCard(Msg(Tone.INFO, "Choose the item for this barcode", pickRaw.take(80)))
                    SearchBox(vm, "Search company, product, colour, size")
                }
            }
            items(items.itemCount, key = items.itemKey { it.id }) { i ->
                val v = items[i] ?: return@items
                ItemRow(v, onEdit = null) {
                    nav.go(if (pickRaw != null) Routes.variant(v.id, pickRaw, pickFmt) else Routes.variant(v.id))
                }
            }
            if (items.itemCount == 0) item { EmptyText("No products yet. Add one from Home.") }
        }
    }
    // Not a tab any more: reached from Sell/Return ("Find product") and from "Link to existing product".
    SubScreen(if (pickRaw != null) "Link barcode" else "Find product", { nav.back() }, body)
}

/** One item: product on top, then size · sleeve · colour; pieces on the right. Tap opens the item. */
@Composable
private fun ItemRow(v: Variant, onEdit: (() -> Unit)?, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (onEdit != null) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(v.title, style = MaterialTheme.typography.titleMedium)
                Text(listOf("Size ${v.sizeLabel}", v.sleeve, v.colour).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (v.archived) Text("Deleted", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${v.qty}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp),
                color = if (v.qty == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit ${v.title}", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
fun StockScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> ListViewModel(c, h) }
    val items = vm.stock.collectAsLazyPagingItems()
    val today by vm.today.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExcelPanel()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Add product", { nav.go(Routes.form()) }, Modifier.weight(1f), Icons.Default.AddBox)
                    SecondaryButton("Scan to add stock", { nav.go(Routes.scan(ScanMode.RESTOCK)) }, Modifier.weight(1f), Icons.Default.QrCodeScanner)
                }
                Text(total?.let { "${pieces(it)} in stock" } ?: "Stock", style = MaterialTheme.typography.headlineMedium)
                val sold = today.firstOrNull { it.type == MoveType.SALE }?.pieces ?: 0
                val returned = today.filter { it.type == MoveType.RETURN || it.type == MoveType.RETURN_DAMAGED }.sumOf { it.pieces }
                val undone = today.firstOrNull { it.type == MoveType.REVERSAL }?.pieces ?: 0
                Text("Today: $sold sold · $returned returned · $undone sales undone", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SearchBox(vm, "Search company, product, colour, size")
            }
        }
        items(items.itemCount, key = items.itemKey { it.id }) { i ->
            val v = items[i] ?: return@items
            ItemRow(v, onEdit = { nav.go(Routes.form(edit = v.id)) }) { nav.go(Routes.variant(v.id)) }
        }
        if (items.itemCount == 0) item { EmptyText("No stock to show.") }
    }
}

@Composable
fun EmptyText(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
