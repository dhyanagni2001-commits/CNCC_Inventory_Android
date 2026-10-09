package com.cnanjappa.inventory

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.newOpId
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.Validate
import com.cnanjappa.inventory.domain.VariantInput
import com.cnanjappa.inventory.export.AutoBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Nightly backup in the .debug app: writes a checked file to Download/CNCC Backups, and skips nights with no changes. */
@RunWith(AndroidJUnit4::class)
class AutoBackupTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = ctx.container.repo

    @Test fun backsUpChangesToDownloadsAndSkipsWhenNothingChanged(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val v = Validate.variant(VariantInput("Test", "Shirt", "Auto ${System.nanoTime()}", "White", "40", Sleeve.FULL)).first!!
        assertEquals(Code.OK, repo.createVariant(newOpId(), v, 3, null, false, "").code)
        repo.setSetting(InventoryRepository.KEY_LAST_BACKUP, "0")
        try {
            assertTrue("changes were backed up", AutoBackup.runIfChanged(ctx))
            val today = AutoBackup.ownFiles(ctx).single { it.second == "cncc-backup-${LocalDate.now()}.cncbak" }
            val info = ctx.contentResolver.openInputStream(today.first)!!.use { ctx.container.backup().inspect(it, File(ctx.cacheDir, "auto-verify.db")) }
            assertTrue(info.variants >= 1)
            assertFalse("nothing changed since, so no new backup", AutoBackup.runIfChanged(ctx))
        } finally {
            AutoBackup.ownFiles(ctx).forEach { ctx.contentResolver.delete(it.first, null, null) }
        }
    }
}
