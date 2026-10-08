package com.cnanjappa.inventory

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test of the real app: opening animation hands over to Home quickly, and Add Product has the
 * optional Notes box. Nothing is saved, so the device's shop data is untouched.
 */
@RunWith(AndroidJUnit4::class)
class LaunchUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun openingShowsShopNameThenHomeWithinBudget() {
        val start = System.currentTimeMillis()
        rule.waitUntil(LAUNCH_BUDGET_MS) { rule.onAllNodes(hasText("Sell")).fetchSemanticsNodes().isNotEmpty() }
        val took = System.currentTimeMillis() - start
        assert(took < LAUNCH_BUDGET_MS) { "Home took $took ms" }
        rule.onNodeWithTag("brand").assertDoesNotExist()
    }

    @Test fun addProductHasOptionalNotes() {
        rule.waitUntil(LAUNCH_BUDGET_MS) { rule.onAllNodes(hasText("Add Product")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add Product").performClick()
        rule.onNodeWithTag("notes").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("notes").performTextInput("Cotton, rack 4")
        rule.onNodeWithText("14 / 500").assertExists()
    }

    private companion object { const val LAUNCH_BUDGET_MS = 3_000L }
}
