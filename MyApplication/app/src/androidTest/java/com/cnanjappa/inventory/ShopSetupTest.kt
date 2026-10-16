package com.cnanjappa.inventory

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnanjappa.inventory.domain.ShopProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** First launch asks for the shop once; the saved name heads the app and is not asked for again. */
@RunWith(AndroidJUnit4::class)
class ShopSetupTest {
    @get:Rule(order = 0) val shop = ShopRule(null)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun asksOnceThenShowsShopNameInHeader() {
        waitFor("Set up your shop")
        // Both names are required.
        rule.onNodeWithText("Save and start").performClick()
        rule.onNodeWithText("Enter the shop name").assertExists()
        rule.onNodeWithTag("shopName").performTextInput("  Sri  Murugan Textiles ")
        rule.onNodeWithTag("ownerName").performTextInput("Ravi")
        rule.onNodeWithText("Save and start").performClick()
        waitFor("Sri Murugan Textiles")
        rule.onNodeWithText("Set up your shop").assertDoesNotExist()
        val c = ApplicationProvider.getApplicationContext<InventoryApp>().container
        assertEquals(ShopProfile("Sri Murugan Textiles", "Ravi"), runBlocking { c.repo.shopProfile() })
        // Opening the app again goes straight to Home.
        rule.activityRule.scenario.recreate()
        waitFor("Sri Murugan Textiles")
        rule.onNodeWithText("Set up your shop").assertDoesNotExist()
        runBlocking { c.saveShop(ShopRule.TEST_SHOP) }
    }
}
