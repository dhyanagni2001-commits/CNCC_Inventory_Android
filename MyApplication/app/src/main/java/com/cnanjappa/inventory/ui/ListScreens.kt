package com.cnanjappa.inventory.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.cnanjappa.inventory.data.StockGroup
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

    val groups = debounced.flatMapLatest { q ->
        Pager(PAGING) { dao.groupsPaged(InventoryRepository.likePattern(q)) }.flow
    }.cachedIn(viewModelScope)

    /** Today's activity in the device (shop) timezone. */
    val today = run {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.totalsBetween(start, start + 86_400_000L)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun breakdown(groupKey: String) = dao.sizeBreakdown(groupKey)
    fun groupVariants(groupKey: String) = dao.groupVariants(groupKey)
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
                    if (pickRaw == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Add product", { nav.navigate(Routes.form()) }, Modifier.weight(1f), Icons.Default.AddBox)
                            SecondaryButton("Scan to add stock", { nav.navigate(Routes.scan(ScanMode.RESTOCK)) }, Modifier.weight(1f), Icons.Default.QrCodeScanner)
                        }
                    } else {
                        MessageCard(Msg(Tone.INFO, "Choose the item for this barcode", pickRaw.take(80)))
                    }
                    SearchBox(vm, "Search name, brand, colour, size")
                }
            }
            items(items.itemCount, key = items.itemKey { it.id }) { i ->
                val v = items[i] ?: return@items
                VariantRow(v) {
                    nav.navigate(if (pickRaw != null) Routes.variant(v.id, pickRaw, pickFmt) else Routes.variant(v.id))
                }
            }
            if (items.itemCount == 0) item { EmptyText("No products yet. Tap Add product.") }
        }
    }
    if (pickRaw != null) SubScreen("Link barcode", { nav.popBackStack() }, body) else body(PaddingValues(0.dp))
}

@Composable
private fun VariantRow(v: Variant, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (v.company.isNotEmpty()) Text(v.company.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 0.8.sp)
                Text(listOf(v.name, v.model).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                Text(v.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (v.archived) Text("Archived", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${v.qty}", style = MaterialTheme.typography.headlineSmall)
                Text(if (v.qty == 1) "piece" else "pieces", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun StockScreen(nav: NavHostController) {
    val vm = appViewModel { c, h -> ListViewModel(c, h) }
    val groups = vm.groups.collectAsLazyPagingItems()
    val today by vm.today.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stock overview", style = MaterialTheme.typography.headlineMedium)
                val sold = today.firstOrNull { it.type == MoveType.SALE }?.pieces ?: 0
                val returned = today.filter { it.type == MoveType.RETURN || it.type == MoveType.RETURN_DAMAGED }.sumOf { it.pieces }
                val undone = today.firstOrNull { it.type == MoveType.REVERSAL }?.pieces ?: 0
                Text("Today: $sold sold · $returned returned · $undone sales undone", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SearchBox(vm, "Search name, brand, colour, size")
            }
        }
        items(groups.itemCount, key = groups.itemKey { it.groupKey }) { i ->
            val g = groups[i] ?: return@items
            GroupCard(vm, nav, g, expanded == g.groupKey) { expanded = if (expanded == g.groupKey) null else g.groupKey }
        }
        if (groups.itemCount == 0) item { EmptyText("No stock to show.") }
    }
}

@Composable
private fun GroupCard(vm: ListViewModel, nav: NavHostController, g: StockGroup, open: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle, shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(180)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (g.company.isNotEmpty()) Text(g.company.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 0.8.sp)
            Text(g.name, style = MaterialTheme.typography.titleLarge)
            Text(listOf(g.model, g.colour).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (g.anyArchived) Text("Includes archived items", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Full", g.fullQty, Modifier.weight(1f))
                Stat("Half", g.halfQty, Modifier.weight(1f))
                Stat("Total", g.total, Modifier.weight(1f), strong = true)
            }
            Text(
                if (open) "Hide sizes" else "Show sizes", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp),
            )
            if (open) SizeTable(vm, nav, g.groupKey)
        }
    }
}

/** Compact number tile: label above, count below; spoken as "Full: 4 pieces left". */
@Composable
private fun Stat(label: String, n: Int, modifier: Modifier, strong: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = if (strong) "Total: ${pieces(n)}" else "$label: ${piecesLeft(n)}" },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$n", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun SizeTable(vm: ListViewModel, nav: NavHostController, groupKey: String) {
    val rows by remember(groupKey) { vm.breakdown(groupKey) }.collectAsStateWithLifecycle(emptyList())
    val variants by remember(groupKey) { vm.groupVariants(groupKey) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.padding(top = 8.dp)) {
        TableRow("Size", "Full", "Half", "Total", bold = true)
        HorizontalDivider()
        rows.forEach { TableRow(it.sizeLabel, "${it.fullQty}", "${it.halfQty}", "${it.total}") }
        HorizontalDivider()
        TableRow("Total", "${rows.sumOf { it.fullQty }}", "${rows.sumOf { it.halfQty }}", "${rows.sumOf { it.total }}", bold = true)
        Text("Open an item", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
        variants.forEach { v ->
            Text(
                "Size ${v.sizeLabel} · ${v.sleeve} — ${piecesLeft(v.qty)}" + if (v.archived) " (Archived)" else "",
                Modifier.fillMaxWidth().clickable { nav.navigate(Routes.variant(v.id)) }.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.primary, fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun TableRow(a: String, b: String, c: String, d: String, bold: Boolean = false) {
    val w = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(a, Modifier.weight(1.4f), fontWeight = w, fontSize = 16.sp)
        Text(b, Modifier.weight(1f), fontWeight = w, fontSize = 16.sp, textAlign = TextAlign.End)
        Text(c, Modifier.weight(1f), fontWeight = w, fontSize = 16.sp, textAlign = TextAlign.End)
        Text(d, Modifier.weight(1f), fontWeight = w, fontSize = 16.sp, textAlign = TextAlign.End)
    }
}

@Composable
fun EmptyText(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
