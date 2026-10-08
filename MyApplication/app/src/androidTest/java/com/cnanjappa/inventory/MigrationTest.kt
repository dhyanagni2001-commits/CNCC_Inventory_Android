package com.cnanjappa.inventory

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Every schema bump must keep existing shop data. Uses the exported schemas in app/schemas. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = File(instrumentation.targetContext.cacheDir, "migration-test.db").apply { delete() }

    @get:Rule val helper = MigrationTestHelper(instrumentation, file, BundledSQLiteDriver(), AppDatabase::class)

    @Test fun v1ToV2KeepsStockAndTriggersAndAddsEmptyNotes() {
        helper.createDatabase(1).use { c ->
            AppDatabase.TRIGGERS.forEach { c.execSQL(it) }
            c.execSQL(
                """INSERT INTO variants (id, code, company, name, model, colour, sizeLabel, sizeKey, sizeSort, sleeve,
                identityKey, groupKey, search, qty, archived, createdAt)
                VALUES (1, 'CNABCDE-1', 'Ramraj', 'Shirt', 'General', 'White', 'L', 'L:L', 2, 'Full', 'k', 'g', 's', 7, 0, 0)""",
            )
            c.execSQL("INSERT INTO movements (opId, variantId, type, delta, qty, ts, description) VALUES ('op', 1, 'OPENING', 7, 7, 0, 'd')")
        }
        helper.runMigrationsAndValidate(2, emptyList()).use { c ->
            c.prepare("SELECT qty, notes FROM variants WHERE id = 1").use { s ->
                s.step(); assertEquals(7L, s.getLong(0)); assertEquals("", s.getText(1))
            }
            c.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'").use { s ->
                s.step(); assertEquals(AppDatabase.TRIGGERS.size.toLong(), s.getLong(0))
            }
        }
    }
}
