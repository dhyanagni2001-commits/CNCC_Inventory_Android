package com.cnanjappa.inventory.export

import androidx.room.PooledConnection
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteStatement
import com.cnanjappa.inventory.data.AppDatabase
import com.cnanjappa.inventory.data.MoveType
import com.cnanjappa.inventory.domain.Codes
import com.cnanjappa.inventory.domain.SHOP_NAME
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * Builds the complete inventory workbook from one consistent snapshot: everything is read inside a
 * single DEFERRED read transaction on a WAL reader connection, so sales keep committing meanwhile and
 * appear in the next export rather than as a half-updated mixture. Rows are streamed, never held.
 */
class ExcelReport(private val db: AppDatabase) {

    suspend fun write(target: File, onProgress: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val zone = ZoneId.systemDefault()
        var sheetCount = 0
        try {
            target.outputStream().buffered(256 * 1024).use { out ->
                XlsxWriter(out, zone).use { x ->
                    db.useReaderConnection { conn ->
                        conn.deferredTransaction {
                            val asOf = System.currentTimeMillis()
                            var n = 0
                            fun tick() {
                                if (++n % 2000 == 0 && job?.isActive == false) throw CancellationException()
                            }
                            onProgress("Summary")
                            summary(x, asOf, zone); sheetCount++
                            onProgress("Inventory")
                            x.sheet("Inventory", listOf(16, 22, 18, 12, 10, 8, 10, 10, 16, 10, 30)) {
                                header("Company", "Product", "Model", "Colour", "Size", "Sleeve", "Pieces", "Status", "Variant code", "Barcodes", "Notes")
                                each(
                                    """SELECT company, name, model, colour, sizeLabel, sleeve, qty, archived, code,
                                    (SELECT COUNT(*) FROM aliases a WHERE a.variantId = v.id) + (SELECT COUNT(*) FROM alias_candidates c WHERE c.variantId = v.id),
                                    notes FROM variants v ORDER BY name COLLATE NOCASE, company COLLATE NOCASE, model COLLATE NOCASE, colour COLLATE NOCASE, sizeSort, sizeLabel, sleeve""",
                                ) { s ->
                                    tick()
                                    row(s.t(0), s.t(1), s.t(2), s.t(3), s.t(4), s.t(5), s.getLong(6), status(s.getLong(7)), s.t(8), s.getLong(9), s.t(10))
                                }
                            }; sheetCount++
                            onProgress("Product totals")
                            x.sheet("Product totals", listOf(16, 22, 18, 12, 10, 10, 10, 30)) {
                                header("Company", "Product", "Model", "Colour", "Full pieces", "Half pieces", "Total pieces", "Sizes")
                                each(
                                    """SELECT MIN(company), MIN(name), MIN(model), MIN(colour),
                                    SUM(CASE WHEN sleeve = 'Full' THEN qty ELSE 0 END), SUM(CASE WHEN sleeve = 'Half' THEN qty ELSE 0 END), SUM(qty),
                                    (SELECT GROUP_CONCAT(l, ', ') FROM (SELECT DISTINCT sizeLabel AS l FROM variants z WHERE z.groupKey = v.groupKey ORDER BY sizeSort))
                                    FROM variants v GROUP BY groupKey ORDER BY MIN(name) COLLATE NOCASE, MIN(company) COLLATE NOCASE, MIN(model) COLLATE NOCASE""",
                                ) { s -> tick(); row(s.t(0), s.t(1), s.t(2), s.t(3), s.getLong(4), s.getLong(5), s.getLong(6), s.t(7)) }
                            }; sheetCount++
                            onProgress("Size breakdown")
                            x.sheet("Size breakdown", listOf(16, 22, 18, 12, 10, 10, 10, 10)) {
                                header("Company", "Product", "Model", "Colour", "Size", "Full pieces", "Half pieces", "Total pieces")
                                each(
                                    """SELECT MIN(company), MIN(name), MIN(model), MIN(colour), MIN(sizeLabel),
                                    SUM(CASE WHEN sleeve = 'Full' THEN qty ELSE 0 END), SUM(CASE WHEN sleeve = 'Half' THEN qty ELSE 0 END), SUM(qty)
                                    FROM variants GROUP BY groupKey, sizeKey
                                    ORDER BY MIN(name) COLLATE NOCASE, MIN(company) COLLATE NOCASE, MIN(model) COLLATE NOCASE, groupKey, MIN(sizeSort)""",
                                ) { s -> tick(); row(s.t(0), s.t(1), s.t(2), s.t(3), s.t(4), s.getLong(5), s.getLong(6), s.getLong(7)) }
                            }; sheetCount++
                            sheetCount += movements(x, onProgress, ::tick)
                            onProgress("Barcodes")
                            x.sheet("Barcodes", listOf(22, 12, 14, 16, 22, 18, 10, 8, 16)) {
                                header("Barcode text", "Format", "Link", "Company", "Product", "Model", "Size", "Sleeve", "Variant code")
                                each(
                                    """SELECT a.raw, a.format, 'Direct', v.company, v.name, v.model, v.sizeLabel, v.sleeve, v.code
                                    FROM aliases a JOIN variants v ON v.id = a.variantId
                                    UNION ALL
                                    SELECT a.raw, a.format, 'Shared — choose item', v.company, v.name, v.model, v.sizeLabel, v.sleeve, v.code
                                    FROM aliases a JOIN alias_candidates c ON c.`key` = a.`key` JOIN variants v ON v.id = c.variantId
                                    ORDER BY 1, 3""",
                                ) { s -> tick(); row(s.t(0), Codes.formatLabel(s.t(1)), s.t(2), s.t(3), s.t(4), s.t(5), s.t(6), s.t(7), s.t(8)) }
                            }; sheetCount++
                        }
                    }
                }
            }
            check(XlsxWriter.validate(target, sheetCount)) { "invalid workbook" }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private suspend fun PooledConnection.summary(x: XlsxWriter, asOf: Long, zone: ZoneId) {
        val counts = usePrepared(
            """SELECT COUNT(DISTINCT groupKey), COUNT(*), IFNULL(SUM(qty), 0),
            SUM(archived), (SELECT COUNT(*) FROM aliases), (SELECT MIN(ts) FROM movements), (SELECT COUNT(*) FROM movements) FROM variants""",
        ) { s -> s.step(); LongArray(7) { if (s.isNull(it)) -1 else s.getLong(it) } }
        val byType = HashMap<String, LongArray>()
        usePrepared("SELECT type, IFNULL(SUM(qty), 0), IFNULL(SUM(delta), 0) FROM movements GROUP BY type") { s ->
            while (s.step()) byType[s.getText(0)] = longArrayOf(s.getLong(1), s.getLong(2))
        }
        fun pieces(t: String) = byType[t]?.get(0) ?: 0L
        val adjust = byType[MoveType.ADJUST]?.get(1) ?: 0L
        x.sheet("Summary", listOf(38, 30)) {
            header("Item", "Value")
            row("Shop", SHOP_NAME)
            row("As of", DateCell(asOf))
            row("Timezone", zone.id)
            row("Products (company/product/model/colour)", counts[0])
            row("Variants (incl. archived)", counts[1])
            row("Archived variants", counts[3].coerceAtLeast(0))
            row("Pieces in stock", counts[2])
            row("Linked barcodes", counts[4])
            row("Movement totals cover", if (counts[5] < 0) "No movements yet" else "All recorded history")
            row("Earliest movement", if (counts[5] < 0) null else DateCell(counts[5]))
            row("Movement rows", counts[6])
            row("Opening stock pieces", pieces(MoveType.OPENING))
            row("Gross pieces sold", pieces(MoveType.SALE))
            row("Sale pieces undone (reversed)", pieces(MoveType.REVERSAL))
            row("Good returns (pieces back in stock)", pieces(MoveType.RETURN))
            row("Damaged returns (not back in stock)", pieces(MoveType.RETURN_DAMAGED))
            row("Stock added (restock pieces)", pieces(MoveType.RESTOCK))
            row("Stock corrections (net change)", adjust)
            row("Note", "Piece counts only; this app records no prices, revenue or refunds.")
        }
    }

    /** Writes the ledger, splitting into "Movements 1", "Movements 2"… at Excel's row limit. */
    private suspend fun PooledConnection.movements(x: XlsxWriter, onProgress: (String) -> Unit, tick: () -> Unit): Int {
        val perSheet = XlsxWriter.MAX_ROWS - 1
        val total = usePrepared("SELECT COUNT(*) FROM movements") { it.step(); it.getLong(0) }
        val sheets = maxOf(1L, (total + perSheet - 1) / perSheet).toInt()
        var afterId = 0L
        for (i in 1..sheets) {
            val name = if (sheets == 1) "Movements" else "Movements $i"
            onProgress(name)
            x.sheet(name, listOf(17, 10, 18, 9, 9, 9, 8, 50, 22, 36)) {
                header("Time", "Movement ID", "Action", "Pieces", "Stock change", "Sale ID", "Return ID", "Item", "Note", "Operation ID")
                usePrepared(
                    "SELECT id, ts, type, qty, delta, saleId, returnId, description, note, opId FROM movements WHERE id > ? ORDER BY id LIMIT ?",
                ) { s ->
                    s.bindLong(1, afterId)
                    s.bindLong(2, perSheet.toLong())
                    while (s.step()) {
                        tick()
                        afterId = s.getLong(0)
                        row(
                            DateCell(s.getLong(1)), afterId, MoveType.label(s.getText(2)), s.getLong(3), s.getLong(4),
                            s.longOrNull(5), s.longOrNull(6), s.getText(7), s.t(8), s.getText(9),
                        )
                    }
                }
            }
        }
        return sheets
    }

    private suspend fun PooledConnection.each(sql: String, rowFn: (SQLiteStatement) -> Unit) =
        usePrepared(sql) { s -> while (s.step()) rowFn(s) }

    private fun status(archived: Long) = if (archived != 0L) "Archived" else "Active"
}

private fun SQLiteStatement.t(i: Int): String = if (isNull(i)) "" else getText(i)
private fun SQLiteStatement.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
