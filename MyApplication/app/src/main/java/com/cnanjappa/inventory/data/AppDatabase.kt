package com.cnanjappa.inventory.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        Variant::class, BarcodeAlias::class, AliasCandidate::class, Sale::class, ReturnRecord::class,
        Movement::class, Operation::class, Setting::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): InventoryDao

    companion object {
        /** v2: variants.notes. Add an AutoMigration (or Migration + test) for every bump. */
        const val VERSION = 2
        const val NAME = "inventory.db"

        /**
         * Last line of defence for the stock invariants, independent of app code. Also present in
         * backups because the backup copies the schema from sqlite_master.
         */
        val TRIGGERS = listOf(
            """CREATE TRIGGER IF NOT EXISTS variant_qty_ins BEFORE INSERT ON variants WHEN NEW.qty < 0
               BEGIN SELECT RAISE(ABORT, 'negative stock'); END""",
            """CREATE TRIGGER IF NOT EXISTS variant_qty_upd BEFORE UPDATE OF qty ON variants WHEN NEW.qty < 0
               BEGIN SELECT RAISE(ABORT, 'negative stock'); END""",
            """CREATE TRIGGER IF NOT EXISTS sale_limit_upd BEFORE UPDATE ON sales
               WHEN NEW.returnedQty < 0 OR NEW.reversedQty < 0 OR NEW.returnedQty + NEW.reversedQty > NEW.qty
               BEGIN SELECT RAISE(ABORT, 'return limit'); END""",
            """CREATE TRIGGER IF NOT EXISTS alias_shape BEFORE INSERT ON aliases
               WHEN (NEW.shared = 0 AND NEW.variantId IS NULL) OR (NEW.shared = 1 AND NEW.variantId IS NOT NULL)
               BEGIN SELECT RAISE(ABORT, 'alias shape'); END""",
            """CREATE TRIGGER IF NOT EXISTS movement_no_update BEFORE UPDATE ON movements
               BEGIN SELECT RAISE(ABORT, 'ledger is append-only'); END""",
            """CREATE TRIGGER IF NOT EXISTS movement_no_delete BEFORE DELETE ON movements
               BEGIN SELECT RAISE(ABORT, 'ledger is append-only'); END""",
        )

        /** [name] null builds an in-memory database with the same driver and triggers (tests). */
        fun build(context: Context, name: String? = NAME): AppDatabase =
            (if (name == null) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            else Room.databaseBuilder(context, AppDatabase::class.java, name))
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(object : Callback() {
                    override fun onCreate(connection: SQLiteConnection) {
                        TRIGGERS.forEach { connection.execSQL(it) }
                    }
                })
                .build()
    }
}
