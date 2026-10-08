package com.cnanjappa.inventory

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.data.AppDatabase
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.newOpId
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.Validate
import com.cnanjappa.inventory.domain.VariantInput
import com.cnanjappa.inventory.export.Backup
import com.cnanjappa.inventory.export.BackupException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Backup round trip on a separate test database; never touches the shop's real data or safety copies. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "backup-test").apply { deleteRecursively(); mkdirs() }
    private lateinit var db: AppDatabase

    @Before fun setUp() = runBlocking {
        context.deleteDatabase(DB)
        db = AppDatabase.build(context, DB)
        val repo = InventoryRepository(db)
        listOf("S", "M", "L").forEach { size ->
            val v = Validate.variant(VariantInput("", "Shirt", "General", "White", size, Sleeve.HALF)).first!!
            val id = repo.createVariant(newOpId(), v, 4, null, false, "notes $size").variantId!!
            repo.sell(newOpId(), id)
        }
    }

    @After fun tearDown() {
        db.close(); context.deleteDatabase(DB); dir.deleteRecursively()
    }

    private fun backup(): File = File(dir, "shop.cncbak").also { runBlocking { Backup(context, db).backupTo(Uri.fromFile(it)) } }

    @Test fun backupRoundTripValidatesAndKeepsData() {
        val out = File(dir, "restored.db")
        val info = Backup(context, db).inspect(backup().inputStream(), out)
        assertEquals(3L, info.variants); assertEquals(9L, info.pieces)
        assertTrue(out.exists())
        val restored = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(out.path)
        try {
            restored.prepare("SELECT COUNT(*), SUM(qty) FROM variants WHERE notes LIKE 'notes %'").use { s ->
                s.step(); assertEquals(3L, s.getLong(0)); assertEquals(9L, s.getLong(1))
            }
        } finally { restored.close() }
    }

    @Test fun corruptedBackupIsRejectedAndLeavesNoFile() {
        val bytes = backup().readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        val out = File(dir, "bad.db")
        try {
            Backup(context, db).inspect(bytes.inputStream(), out)
            fail("Corrupted backup accepted")
        } catch (e: BackupException) {
            assertTrue(!out.exists())
        }
    }

    @Test fun nonBackupFileIsRejected() {
        try {
            Backup(context, db).inspect("hello".byteInputStream(), File(dir, "x.db"))
            fail("Random file accepted")
        } catch (_: BackupException) {}
    }

    private companion object { const val DB = "backup-test.db" }
}
