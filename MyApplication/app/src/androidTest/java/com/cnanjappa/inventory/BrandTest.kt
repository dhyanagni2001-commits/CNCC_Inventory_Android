package com.cnanjappa.inventory

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnanjappa.inventory.ui.AppTheme
import com.cnanjappa.inventory.ui.BRAND_MS
import com.cnanjappa.inventory.ui.Brand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opening animation: shows the shop name and hands over after exactly [BRAND_MS]. */
@RunWith(AndroidJUnit4::class)
class BrandTest {
    @get:Rule val rule = createComposeRule()

    @Test fun showsShopNameAndFinishesOnTime() {
        var shown = false
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { Brand(LaunchState.READY, slow = false, animate = true, onRetry = {}) { shown = true } } }
        rule.onNodeWithText("C Nanjappa").assertExists()
        rule.onNodeWithText("Cloth Center").assertExists()
        rule.mainClock.advanceTimeBy(BRAND_MS / 2)
        assertFalse(shown)
        rule.mainClock.advanceTimeBy(BRAND_MS)
        rule.waitForIdle()
        assertTrue(shown)
    }

    @Test fun replayDoesNotAnimate() {
        var shown = false
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { Brand(LaunchState.READY, slow = false, animate = false, onRetry = {}) { shown = true } } }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue(shown)
    }

}
