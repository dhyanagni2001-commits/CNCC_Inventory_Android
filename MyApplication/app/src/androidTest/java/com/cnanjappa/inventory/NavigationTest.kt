package com.cnanjappa.inventory

import android.Manifest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home holds only Sell, Return and Add Product; stock and the Excel report live in the Stock tab.
 * "Find product" from the scanner is a sub-screen that Back leaves. Nothing is saved.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun grantCamera() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.CAMERA)
    }

    private fun waitFor(text: String) = rule.waitUntil(LAUNCH_MS) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun homeShowsOnlyTheThreeActions() {
        waitFor("Sell")
        rule.onNodeWithText("Return").assertExists()
        rule.onNodeWithText("Add Product").assertExists()
        rule.onNodeWithText("Excel report").assertDoesNotExist()
        rule.onNodeWithText("Products").assertDoesNotExist()
    }

    @Test fun stockTabHasExcelOnTopAndHomeReturnsHome() {
        waitFor("Stock")
        rule.onNodeWithText("Stock").performClick()
        waitFor("Excel report")
        rule.onNodeWithText("Scan to add stock").assertExists()
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Scan a label to sell 1 piece").assertExists()
    }

    @Test fun findProductFromScannerGoesBack() {
        waitFor("Sell")
        rule.onNodeWithText("Sell").performClick()
        waitFor("Find product instead")
        rule.onNodeWithText("Find product instead").performScrollTo().performClick()
        waitFor("Find product")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Find product instead")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Scan a label to sell 1 piece")
    }

    private companion object { const val LAUNCH_MS = 5_000L }
}
