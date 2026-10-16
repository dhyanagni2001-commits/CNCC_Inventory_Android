package com.cnanjappa.inventory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.cnanjappa.inventory.container
import com.cnanjappa.inventory.domain.SHOP_TEXT_MAX
import com.cnanjappa.inventory.domain.ShopProfile
import com.cnanjappa.inventory.domain.Text as Clean
import kotlinx.coroutines.launch

/**
 * First launch only: asks for the shop and owner names, then opens Home. Saved in the app's own
 * database (no online account), so it is never asked again and travels with backups.
 */
@Composable
fun ShopSetupScreen() {
    // Shown outside the Scaffold, so this Surface supplies the themed background and text colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Set up your shop", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Asked only once. The shop name is shown at the top of the app and in Excel reports.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ShopForm(initial = null, button = "Save and start")
        }
    }
}

/** ⋮ → Shop details: corrects a typo later; the app never asks again on its own. */
@Composable
fun ShopDetailsScreen(nav: NavHostController) {
    val shop by LocalContext.current.container.shop.collectAsStateWithLifecycle()
    SubScreen("Shop details", { nav.back() }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = BodyArrangement,
        ) {
            ShopForm(initial = shop, button = "Save") { nav.back() }
        }
    }
}

@Composable
private fun ShopForm(initial: ShopProfile?, button: String, onSaved: () -> Unit = {}) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var owner by rememberSaveable { mutableStateOf(initial?.owner.orEmpty()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    fun problem(v: String, empty: String) = when {
        Clean.hasControl(v) -> "Remove unusual characters"
        Clean.clean(v).isEmpty() -> empty
        else -> null
    }
    val nameError = problem(name, "Enter the shop name")
    val ownerError = problem(owner, "Enter the owner's name")
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)

    SectionCard(title = "Shop") {
        OutlinedTextField(
            name, { name = it.take(SHOP_TEXT_MAX) }, Modifier.fillMaxWidth().testTag("shopName"),
            label = { Text("Shop name *") }, placeholder = { Text("e.g. C Nanjappa Cloth Center") },
            singleLine = true, keyboardOptions = words, isError = tried && nameError != null,
        )
        FieldHint(nameError.takeIf { tried })
        OutlinedTextField(
            owner, { owner = it.take(SHOP_TEXT_MAX) }, Modifier.fillMaxWidth().testTag("ownerName"),
            label = { Text("Owner name *") }, singleLine = true,
            keyboardOptions = words.copy(imeAction = ImeAction.Done), isError = tried && ownerError != null,
        )
        FieldHint(ownerError.takeIf { tried })
    }
    BigButton(button, {
        tried = true
        if (nameError == null && ownerError == null && !saving) {
            saving = true
            scope.launch {
                container.saveShop(ShopProfile(Clean.clean(name), Clean.clean(owner)))
                saving = false
                onSaved()
            }
        }
    }, Modifier.fillMaxWidth(), enabled = !saving)
}
