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
 * The Home tab must always go Home, even when a tab screen was reached from inside another screen
 * (e.g. Sell → "Find product instead" → Products). Nothing is saved.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun grantCamera() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.CAMERA)
    }

    @Test fun homeTabReturnsHomeAfterFindProductFromScanner() {
        rule.waitUntil(LAUNCH_MS) { rule.onAllNodes(hasText("Sell")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Sell").performClick()
        rule.waitUntil(LAUNCH_MS) { rule.onAllNodes(hasText("Find product instead")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Find product instead").performScrollTo().performClick()
        rule.waitUntil(LAUNCH_MS) { rule.onAllNodes(hasText("Home")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Scan a label to sell 1 piece").assertExists()

        // And it keeps working on a second round trip.
        rule.onNodeWithText("Products").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Home").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Scan a label to sell 1 piece").assertExists()
    }

    private companion object { const val LAUNCH_MS = 5_000L }
}
