package com.cnanjappa.inventory

import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.Resolution
import com.cnanjappa.inventory.data.newOpId
import com.cnanjappa.inventory.domain.ScannedCode
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.Validate
import com.cnanjappa.inventory.domain.VariantInput
import com.cnanjappa.inventory.export.ExcelReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * 10,000-product stress run against the app's real on-disk database. Not part of the normal suite:
 * run it through scripts/stress.sh, which clears the .debug app first and keeps the data for the UI phase.
 * Every mutation goes through InventoryRepository and is checked against an independent stock model.
 */
@RunWith(AndroidJUnit4::class)
class StressTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo: InventoryRepository = ctx.container.repo
    private val rnd = Random(20261008)
    private val timings = linkedMapOf<String, MutableList<Long>>()

    private inline fun <T> timed(name: String, block: () -> T): T {
        val t = System.nanoTime()
        return block().also { timings.getOrPut(name) { mutableListOf() } += System.nanoTime() - t }
    }

    private fun report(line: String) = Log.i("STRESS", line)

    @Test fun tenThousandProducts(): Unit = runBlocking {
        assumeTrue("only via scripts/stress.sh", InstrumentationRegistry.getArguments().getString("stress") == "true")
        assertEquals("run scripts/stress.sh (needs an empty .debug app)", 0, repo.dao.variantCount())
        val companies = listOf("Ramraj", "Udyam", "Puma", "Siyaram", "Raymond")
        val names = listOf("Shirt", "Dhoti", "Kurta", "Vest", "Trouser", "Banian", "Towel", "Lungi", "Jeans", "Polo")
        val sizes = listOf("38", "40")
        val args = InstrumentationRegistry.getArguments()
        val n = args.getString("products")?.toInt() ?: 10_000
        val ids = LongArray(n)
        val expected = IntArray(n)
        val codes = Array(n) { "SKU-%06d".format(it) }

        // 1. Seed: n/4 product groups x 2 sizes x Full/Half = n variants, each with its own barcode.
        val seedStart = System.nanoTime()
        for (i in 0 until n) {
            val g = i / 4
            val input = VariantInput(
                companies[g % companies.size], names[g % names.size], "M%05d".format(g),
                if (g % 3 == 0) "Cream" else "White", sizes[(i / 2) % 2], if (i % 2 == 0) Sleeve.FULL else Sleeve.HALF,
            )
            val qty = if (i % 10 == 0) 0 else rnd.nextInt(1, 21)
            val r = timed("seed (create product)") {
                repo.createVariant(newOpId(), Validate.variant(input).first!!, qty, ScannedCode(codes[i], "CODE_128"), false)
            }
            assertEquals(Code.OK, r.code)
            ids[i] = r.variantId!!
            expected[i] = qty
        }
        val seedSec = (System.nanoTime() - seedStart) / 1e9
        val idx = ids.withIndex().associate { (i, id) -> id to i }

        // 2. Mixed workload. Scan-to-sell includes barcode lookup, like the camera path.
        val sold = mutableListOf<Long>()
        repeat(3000) {
            val i = rnd.nextInt(n)
            val r = timed("scan + sell") {
                val v = (repo.resolve(ScannedCode(codes[i], "CODE_128")) as Resolution.One).variant
                repo.sell(newOpId(), v.id)
            }
            if (expected[i] > 0) { assertEquals(Code.OK, r.code); expected[i]--; sold += r.refId!! }
            else assertEquals(Code.NO_STOCK, r.code)
        }
        sold.shuffle(rnd)
        val returned = sold.take(500)
        for (saleId in returned) {
            val vid = repo.dao.sale(saleId)!!.variantId
            val damaged = rnd.nextInt(5) == 0
            val r = timed("return") { repo.returnItem(newOpId(), vid, saleId, 1, damaged) }
            assertEquals(Code.OK, r.code)
            if (!damaged) expected[idx.getValue(vid)]++
        }
        for (saleId in sold.drop(500).take(200)) {
            val r = timed("undo sale") { repo.undoSale(newOpId(), saleId) }
            assertEquals(Code.OK, r.code)
            expected[idx.getValue(r.variantId!!)]++
        }
        repeat(1000) {
            val i = rnd.nextInt(n); val q = rnd.nextInt(1, 10)
            assertEquals(Code.OK, timed("restock") { repo.restock(newOpId(), ids[i], q) }.code)
            expected[i] += q
        }
        repeat(300) {
            val i = rnd.nextInt(n); val q = rnd.nextInt(0, 30)
            assertEquals(Code.OK, timed("stock correction") { repo.correct(newOpId(), ids[i], expected[i], q, "count") }.code)
            expected[i] = q
        }
        // Replayed operation id (double tap / retry) must not sell twice.
        val dupOp = newOpId(); val di = (0 until n).first { expected[it] > 1 }
        repo.sell(dupOp, ids[di]); repo.sell(dupOp, ids[di]); expected[di]--

        // 3. Last-piece race: two devices-worth of taps on stock 1, only one may win.
        var raceWins = 0
        repeat(50) {
            val i = rnd.nextInt(n)
            repo.correct(newOpId(), ids[i], expected[i], 1, "race")
            val rs = (1..2).map { async(Dispatchers.IO) { repo.sell(newOpId(), ids[i]) } }.awaitAll()
            assertEquals(1, rs.count { it.ok })
            raceWins++; expected[i] = 0
        }

        // 4. Search first page (what the Stock/Products screens load) for hits, rare terms and misses.
        val queries = listOf("shirt", "ramraj kurta", "m01234", "cream 40", "puma", "m00001", "m24999", "polo half", "zzz-none", "towel")
        repeat(300) {
            val q = InventoryRepository.likePattern(queries[it % queries.size])
            timed("search (stock groups)") { repo.dao.groupsPaged(q).load(PagingSource.LoadParams.Refresh(null, 50, false)) }
            timed("search (products)") { repo.dao.variantsPaged(q).load(PagingSource.LoadParams.Refresh(null, 50, false)) }
        }

        // 5. Excel and backup at 10k products.
        val xlsx = File(ctx.cacheDir, "stress.xlsx")
        timed("excel export (full)") { ExcelReport(repo.db).write(xlsx) {} }
        val bak = File(ctx.getExternalFilesDir(null), "stress.cncbak")  // kept for restoring into the preview app
        timed("backup (full)") { ctx.container.backup().backupTo(Uri.fromFile(bak)) }

        // 6. Correctness: ledger reconciles and every quantity matches the independent model.
        assertTrue(repo.dao.unreconciled().isEmpty())
        var mismatches = 0
        for (i in 0 until n) if (repo.dao.variant(ids[i])!!.qty != expected[i]) mismatches++
        assertEquals(0, mismatches)

        // Report.
        val rt = Runtime.getRuntime()
        report("products=$n seedTotal=%.1fs races=$raceWins mismatches=$mismatches unreconciled=0".format(seedSec))
        for ((name, ns) in timings) {
            val ms = ns.map { it / 1e6 }.sorted()
            fun p(q: Double) = ms[((ms.size - 1) * q).toInt()]
            report("%-24s n=%-5d p50=%7.2fms p95=%7.2fms p99=%7.2fms max=%8.1fms".format(name, ms.size, p(.5), p(.95), p(.99), ms.last()))
        }
        val dbFile = ctx.getDatabasePath("inventory.db")
        report("dbSize=%.1fMB wal=%.1fMB xlsx=%.1fMB backup=%.1fMB".format(
            dbFile.length() / 1e6, File(dbFile.path + "-wal").length() / 1e6, xlsx.length() / 1e6, bak.length() / 1e6))
        report("javaHeapUsed=%.1fMB nativeHeap=%.1fMB".format((rt.totalMemory() - rt.freeMemory()) / 1e6, Debug.getNativeHeapAllocatedSize() / 1e6))
        xlsx.delete()
    }
}
