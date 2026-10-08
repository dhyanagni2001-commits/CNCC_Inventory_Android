package com.cnanjappa.inventory.export

import android.content.Context
import android.net.Uri
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.cnanjappa.inventory.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupInfo(val createdAt: Long, val variants: Long, val pieces: Long, val movements: Long)

class BackupException(message: String) : Exception(message)

/**
 * Full backup = zip(manifest.json + inventory.db). The db is a fresh SQLite file built from one
 * DEFERRED read snapshot (schema from sqlite_master, then rows), so WAL state is always included and
 * sales are not blocked. The manifest carries format/schema versions, counts and a SHA-256 of the db.
 */
class Backup(private val context: Context, private val db: AppDatabase) {
    private val tmpDir get() = File(context.cacheDir, "backup").apply { mkdirs() }

    /** Writes a verified backup to [out] (a user-chosen document) and returns what it contains. */
    suspend fun backupTo(out: Uri): BackupInfo = withContext(Dispatchers.IO) {
        val zip = File(tmpDir, "outgoing.cncbak")
        try {
            val info = buildZip(zip)
            context.contentResolver.openOutputStream(out, "wt")?.use { o -> zip.inputStream().use { it.copyTo(o) } }
                ?: throw BackupException("Could not open the chosen file")
            // Read back what was actually written before calling the backup successful.
            context.contentResolver.openInputStream(out)?.use { inspect(it, File(tmpDir, "verify.db")) }
                ?: throw BackupException("Could not check the saved backup")
            File(tmpDir, "verify.db").delete()
            info
        } finally {
            zip.delete()
        }
    }

    /** Safety copy in app storage, made before a restore replaces current data. Keeps the last 3. */
    suspend fun safetyBackup(): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "safety").apply { mkdirs() }
        val f = File(dir, "safety-${System.currentTimeMillis()}.cncbak")
        val tmp = File(dir, f.name + ".part")
        buildZip(tmp)
        inspect(tmp.inputStream(), File(tmpDir, "verify.db"))
        File(tmpDir, "verify.db").delete()
        if (!tmp.renameTo(f)) throw BackupException("Could not save safety copy")
        dir.listFiles { x -> x.name.endsWith(".cncbak") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
        f
    }

    private suspend fun buildZip(target: File): BackupInfo {
        val dbFile = File(tmpDir, "snapshot.db")
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        val info = snapshotInto(dbFile)
        val sha = sha256(dbFile.inputStream())
        ZipOutputStream(target.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(MANIFEST))
            z.write(
                JSONObject()
                    .put("format", FORMAT).put("formatVersion", 1).put("schemaVersion", AppDatabase.VERSION)
                    .put("createdAt", info.createdAt).put("variants", info.variants).put("pieces", info.pieces)
                    .put("movements", info.movements).put("sha256", sha)
                    .toString().toByteArray(),
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        dbFile.delete()
        return info
    }

    private suspend fun snapshotInto(dbFile: File): BackupInfo {
        val dest = BundledSQLiteDriver().open(dbFile.path)
        try {
            return db.useReaderConnection { conn ->
                conn.deferredTransaction {
                    val createdAt = System.currentTimeMillis()
                    val version = usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) }
                    val schema = usePrepared("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'") { s ->
                        buildList { while (s.step()) add(Triple(s.getText(0), s.getText(1), s.getText(2))) }
                    }
                    dest.execSQL("BEGIN")
                    schema.filter { it.first == "table" }.forEach { (_, name, sql) ->
                        dest.execSQL(sql)
                        copyTable(name) { sel, cols -> copyRows(sel, cols, dest, name) }
                    }
                    dest.execSQL("DELETE FROM sqlite_sequence")
                    usePrepared("SELECT name, seq FROM sqlite_sequence") { s ->
                        while (s.step()) dest.prepare("INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)").use { ins ->
                            ins.bindText(1, s.getText(0)); ins.bindLong(2, s.getLong(1)); ins.step()
                        }
                    }
                    schema.filter { it.first != "table" }.forEach { dest.execSQL(it.third) }
                    dest.execSQL("PRAGMA user_version = $version")
                    dest.execSQL("COMMIT")
                    val c = usePrepared("SELECT (SELECT COUNT(*) FROM variants), (SELECT IFNULL(SUM(qty),0) FROM variants), (SELECT COUNT(*) FROM movements)") {
                        it.step(); longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2))
                    }
                    BackupInfo(createdAt, c[0], c[1], c[2])
                }
            }
        } finally {
            dest.close()
        }
    }

    private suspend fun androidx.room.PooledConnection.copyTable(name: String, block: (androidx.sqlite.SQLiteStatement, Int) -> Unit) =
        usePrepared("SELECT * FROM \"$name\"") { s -> block(s, s.getColumnCount()) }

    private fun copyRows(sel: androidx.sqlite.SQLiteStatement, cols: Int, dest: SQLiteConnection, table: String) {
        val names = (0 until cols).joinToString(",") { "\"${sel.getColumnName(it)}\"" }
        dest.prepare("INSERT INTO \"$table\" ($names) VALUES (${(1..cols).joinToString(",") { "?" }})").use { ins ->
            while (sel.step()) {
                ins.reset()
                ins.clearBindings()
                for (i in 0 until cols) {
                    when (sel.getColumnType(i)) {
                        SQLITE_DATA_INTEGER -> ins.bindLong(i + 1, sel.getLong(i))
                        SQLITE_DATA_FLOAT -> ins.bindDouble(i + 1, sel.getDouble(i))
                        SQLITE_DATA_BLOB -> ins.bindBlob(i + 1, sel.getBlob(i))
                        SQLITE_DATA_NULL -> ins.bindNull(i + 1)
                        else -> ins.bindText(i + 1, sel.getText(i))
                    }
                }
                ins.step()
            }
        }
    }

    /**
     * Validates a backup stream: manifest, checksum, schema version, SQLite integrity, required tables
     * and ledger reconciliation. Leaves the extracted database at [dbOut] on success.
     */
    fun inspect(input: InputStream, dbOut: File): BackupInfo =
        try {
            inspectInto(input, dbOut)
        } catch (e: Exception) {
            dbOut.delete() // never leave a rejected file behind
            throw e
        }

    private fun inspectInto(input: InputStream, dbOut: File): BackupInfo {
        dbOut.delete()
        var manifest: JSONObject? = null
        var sha: String? = null
        try {
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        MANIFEST -> manifest = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        DB_ENTRY -> sha = sha256(z, dbOut)
                    }
                }
            }
        } catch (e: Exception) {
            throw BackupException("This backup file is damaged")
        }
        val m = manifest ?: throw BackupException("This is not a shop backup file")
        if (m.optString("format") != FORMAT || sha == null) throw BackupException("This is not a shop backup file")
        if (m.optInt("schemaVersion") > AppDatabase.VERSION) throw BackupException("Backup is from a newer app version. Update the app first")
        if (m.optString("sha256") != sha) throw BackupException("This backup file is damaged")
        val conn = try { BundledSQLiteDriver().open(dbOut.path) } catch (e: Exception) { throw BackupException("This backup file is damaged") }
        try {
            fun one(sql: String) = conn.prepare(sql).use { it.step(); it.getText(0) }
            if (one("PRAGMA integrity_check") != "ok") throw BackupException("This backup file is damaged")
            if (one("PRAGMA user_version").toInt() != m.optInt("schemaVersion")) throw BackupException("This backup file is damaged")
            val tables = conn.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { s -> buildSet { while (s.step()) add(s.getText(0)) } }
            if (!tables.containsAll(REQUIRED)) throw BackupException("This backup file is incomplete")
            val bad = one(
                """SELECT COUNT(*) FROM variants v LEFT JOIN (SELECT variantId, SUM(delta) s FROM movements GROUP BY variantId) x
                ON x.variantId = v.id WHERE v.qty != IFNULL(x.s, 0) OR v.qty < 0""",
            ).toInt()
            if (bad != 0) throw BackupException("Stock in this backup does not add up")
            return BackupInfo(m.optLong("createdAt"), m.optLong("variants"), m.optLong("pieces"), m.optLong("movements"))
        } catch (e: BackupException) {
            dbOut.delete(); throw e
        } catch (e: Exception) {
            dbOut.delete(); throw BackupException("This backup file is damaged")
        } finally {
            conn.close()
        }
    }

    /** Copies a picked backup into private storage and validates it without touching current data. */
    suspend fun prepareRestore(uri: Uri): Pair<BackupInfo, File> = withContext(Dispatchers.IO) {
        val dbOut = File(tmpDir, "restore.db")
        val info = context.contentResolver.openInputStream(uri)?.use { inspect(it, dbOut) }
            ?: throw BackupException("Could not open this file")
        info to dbOut
    }

    companion object {
        const val FORMAT = "cncc-inventory-backup"
        private const val MANIFEST = "manifest.json"
        private const val DB_ENTRY = "inventory.db"
        private val REQUIRED = setOf("variants", "aliases", "alias_candidates", "sales", "returns", "movements", "operations", "settings", "room_master_table")

        private fun sha256(input: InputStream, copyTo: File? = null): String {
            val md = MessageDigest.getInstance("SHA-256")
            val din = DigestInputStream(input, md)
            if (copyTo != null) {
                copyTo.outputStream().use { din.copyTo(it) }
            } else din.use {
                val buf = ByteArray(64 * 1024)
                while (it.read(buf) >= 0) Unit
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Replaces the live database file with a validated restore. Must run while the database is
         * closed. The old main file stays intact until the atomic rename.
         */
        fun swapIn(context: Context, restored: File) {
            val live = context.getDatabasePath(AppDatabase.NAME)
            val staged = File(live.path + ".restore")
            restored.copyTo(staged, overwrite = true)
            listOf("-wal", "-shm", "-journal").forEach { File(live.path + it).delete() }
            if (!staged.renameTo(live)) throw BackupException("Could not restore. Your current data is unchanged")
            restored.delete()
        }
    }
}
