package com.cnanjappa.inventory

import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.domain.ShopProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource

/**
 * Runs before the activity starts (use order = 0): saves [profile] so the app opens on Home, or with
 * null clears the shop details so it opens on the one-time Shop setup.
 */
class ShopRule(private val profile: ShopProfile?) : ExternalResource() {
    override fun before(): Unit = runBlocking {
        val c = ApplicationProvider.getApplicationContext<InventoryApp>().container
        withTimeout(10_000) { c.launch.first { it == LaunchState.READY } }
        if (profile != null) c.saveShop(profile)
        else {
            c.repo.db.useWriterConnection { conn ->
                conn.usePrepared("DELETE FROM settings WHERE `key` IN ('${InventoryRepository.KEY_SHOP_NAME}', '${InventoryRepository.KEY_OWNER_NAME}')") { it.step() }
            }
            c.warmUp()
            withTimeout(10_000) { c.launch.first { it == LaunchState.READY } }
        }
    }

    companion object {
        val TEST_SHOP = ShopProfile("C Nanjappa Cloth Center", "Test Owner")
    }
}
