package com.cnanjappa.inventory.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cnanjappa.inventory.LaunchState
import com.cnanjappa.inventory.container
import com.cnanjappa.inventory.domain.SHOP_NAME
import kotlinx.coroutines.delay

object Routes {
    const val HOME = "home"
    const val PRODUCTS = "products?pickRaw={pickRaw}&pickFmt={pickFmt}"
    const val STOCK = "stock"
    const val SCAN = "scan/{mode}?variant={variant}"
    const val VARIANT = "variant/{id}?linkRaw={linkRaw}&linkFmt={linkFmt}"
    const val RETURN = "return/{id}"
    const val FORM = "form?copy={copy}&edit={edit}&codeRaw={codeRaw}&codeFmt={codeFmt}"
    const val LABELS = "labels/{id}?count={count}"
    const val HISTORY = "history"
    const val BACKUP = "backup"

    fun products(pickRaw: String? = null, pickFmt: String? = null) =
        "products" + if (pickRaw != null) "?pickRaw=${Uri.encode(pickRaw)}&pickFmt=$pickFmt" else ""
    fun scan(mode: ScanMode, variant: Long? = null) = "scan/${mode.name}" + (variant?.let { "?variant=$it" } ?: "")
    fun variant(id: Long, linkRaw: String? = null, linkFmt: String? = null) =
        "variant/$id" + if (linkRaw != null) "?linkRaw=${Uri.encode(linkRaw)}&linkFmt=$linkFmt" else ""
    fun returnItem(id: Long) = "return/$id"
    fun form(copy: Long? = null, edit: Long? = null, codeRaw: String? = null, codeFmt: String? = null) = buildString {
        append("form?")
        copy?.let { append("copy=$it&") }
        edit?.let { append("edit=$it&") }
        codeRaw?.let { append("codeRaw=${Uri.encode(it)}&codeFmt=$codeFmt") }
    }.trimEnd('&', '?')
    fun labels(id: Long, count: Int) = "labels/$id?count=$count"

    val TABS = listOf("home", "products", "stock")
}

/**
 * Animated shop-name opening, once per cold launch. It plays while the database opens in the
 * background, so it adds little to start-up; it ends after [BRAND_MS] or when the data is ready,
 * whichever is later. Rotation or resume never replays it.
 */
@Composable
fun AppRoot() {
    val container = LocalContext.current.container
    val launch by container.launch.collectAsStateWithLifecycle()
    var brandDone by rememberSaveable { mutableStateOf(container.brandingShown) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(launch) {
        slow = false
        if (launch == LaunchState.OPENING) { delay(1500); slow = true }
    }
    Crossfade(targetState = brandDone && launch == LaunchState.READY, animationSpec = tween(220), label = "launch") { ready ->
        if (ready) MainNav()
        else Brand(launch, slow, animate = !brandDone, onRetry = { container.warmUp() }) {
            brandDone = true
            container.brandingShown = true
        }
    }
}

/** Length of the opening animation. Kept short: the shop wants the Sell button quickly. */
const val BRAND_MS = 700L

@Composable
internal fun Brand(state: LaunchState, slow: Boolean, animate: Boolean, onRetry: () -> Unit, onShown: () -> Unit) {
    val start = if (animate) 0f else 1f
    val mark = remember { Animatable(start) }
    val lines = remember { List(3) { Animatable(start) } }
    val bar = remember { Animatable(start) }
    LaunchedEffect(Unit) {
        if (animate) {
            launch { mark.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 420f)) }
            lines.forEachIndexed { i, a -> launch { delay(110L + i * 80L); a.animateTo(1f, tween(340, easing = FastOutSlowInEasing)) } }
            launch { delay(420); bar.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
            delay(BRAND_MS)
        }
        onShown()
    }
    // Animated values are read only inside graphicsLayer/draw lambdas: no recomposition per frame.
    fun Modifier.rise(a: Animatable<Float, *>) = graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 18.dp.toPx()
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp).testTag("brand"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp)
                    .graphicsLayer {
                        alpha = mark.value.coerceIn(0f, 1f)
                        scaleX = 0.6f + 0.4f * mark.value; scaleY = scaleX
                        rotationZ = (1f - mark.value) * -8f
                    }
                    .shadow(14.dp, RoundedCornerShape(22.dp), spotColor = BrandBlue)
                    .background(Brush.linearGradient(listOf(BrandBlueLight, BrandBlue)), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("CN", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            }
            Spacer(Modifier.height(28.dp))
            val headline = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp, textAlign = TextAlign.Center)
            Text("C Nanjappa", Modifier.rise(lines[0]), style = headline, color = MaterialTheme.colorScheme.onBackground)
            Text("Cloth Center", Modifier.rise(lines[1]), style = headline, color = primary)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.width(56.dp).height(3.dp).drawBehind {
                    val w = size.width * bar.value
                    drawRoundRect(primary, topLeft = Offset((size.width - w) / 2, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height))
                },
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "INVENTORY", Modifier.rise(lines[2]), style = MaterialTheme.typography.labelLarge,
                letterSpacing = 4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            when {
                state == LaunchState.FAILED -> MessageCard(Msg(Tone.ERROR, "Could not open your shop data", "Your data is safe. Try again.")) {
                    SecondaryButton("Retry", onRetry)
                }
                slow -> Text("Opening your shop…", fontSize = 17.sp)
            }
        }
    }
}

private val BrandBlue = Color(0xFF1E3A8A)
private val BrandBlueLight = Color(0xFF2563EB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    val onTab = route in Routes.TABS && entry?.arguments?.getString("pickRaw") == null
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (onTab) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(SHOP_NAME, maxLines = 1, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("History", fontSize = 17.sp) }, leadingIcon = { Icon(Icons.Default.History, null) },
                            onClick = { menu = false; nav.navigate(Routes.HISTORY) })
                        DropdownMenuItem(text = { Text("Backup", fontSize = 17.sp) }, leadingIcon = { Icon(Icons.Default.Backup, null) },
                            onClick = { menu = false; nav.navigate(Routes.BACKUP) })
                    }
                },
            )
        },
        bottomBar = {
            if (onTab) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.drawTopHairline()) {
                listOf(Triple("home", "Home", Icons.Default.Home), Triple("products", "Products", Icons.AutoMirrored.Filled.ViewList), Triple("stock", "Stock", Icons.Default.Inventory2))
                    .forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                nav.navigate(r) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, null) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
            }
        },
    ) { pad ->
        NavGraph(nav, if (onTab) pad else PaddingValues(0.dp))
    }
}

@Composable
private fun NavGraph(nav: NavHostController, pad: PaddingValues) {
    val str = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
    val long = { name: String -> navArgument(name) { type = NavType.LongType; defaultValue = -1L } }
    NavHost(
        nav, startDestination = Routes.HOME, modifier = Modifier.padding(pad),
        // Short, quiet motion (≤200 ms): a slight slide + fade forward, reversed on back.
        enterTransition = { fadeIn(tween(180)) + slideInHorizontally(tween(200)) { it / 12 } },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { it / 12 } },
    ) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.PRODUCTS, arguments = listOf(str("pickRaw"), str("pickFmt"))) { ProductsScreen(nav) }
        composable(Routes.STOCK) { StockScreen(nav) }
        composable(Routes.SCAN, arguments = listOf(navArgument("mode") { type = NavType.StringType }, long("variant"))) { ScanScreen(nav) }
        composable(Routes.VARIANT, arguments = listOf(navArgument("id") { type = NavType.LongType }, str("linkRaw"), str("linkFmt"))) { VariantScreen(nav) }
        composable(Routes.RETURN, arguments = listOf(navArgument("id") { type = NavType.LongType })) { ReturnScreen(nav) }
        composable(Routes.FORM, arguments = listOf(long("copy"), long("edit"), str("codeRaw"), str("codeFmt"))) { FormScreen(nav) }
        composable(Routes.LABELS, arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("count") { type = NavType.IntType; defaultValue = 1 })) { LabelsScreen(nav) }
        composable(Routes.HISTORY) { HistoryScreen(nav) }
        composable(Routes.BACKUP) { BackupScreen(nav) }
    }
}

/** Sub-screen frame with a back arrow and a clear heading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { content(it) }
}

/** Standard padding for scrolling screen bodies. */
val BodyArrangement = Arrangement.spacedBy(14.dp)

@Composable
private fun Modifier.drawTopHairline(): Modifier {
    val c = MaterialTheme.colorScheme.outlineVariant
    return drawBehind { drawLine(c, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
}
