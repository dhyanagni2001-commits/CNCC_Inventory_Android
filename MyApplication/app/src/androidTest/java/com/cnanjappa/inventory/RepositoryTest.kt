package com.cnanjappa.inventory

import androidx.room.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.data.AppDatabase
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.LinkResult
import com.cnanjappa.inventory.data.MoveType
import com.cnanjappa.inventory.data.Resolution
import com.cnanjappa.inventory.data.newOpId
import com.cnanjappa.inventory.domain.InternalCode
import com.cnanjappa.inventory.domain.ScannedCode
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.Validate
import com.cnanjappa.inventory.domain.VariantInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Stock rules through the single mutation path, on the real driver and triggers (in-memory DB). */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: InventoryRepository
    private val dao get() = repo.dao

    @Before fun setUp() {
        db = AppDatabase.build(InstrumentationRegistry.getInstrumentation().targetContext, name = null)
        repo = InventoryRepository(db)
    }

    @After fun tearDown() {
        runBlocking { assertTrue("ledger must reconcile", dao.unreconciled().isEmpty()) }
        db.close()
    }

    private fun nv(name: String = "Shirt", size: String = "L", sleeve: Sleeve = Sleeve.FULL) =
        Validate.variant(VariantInput("Ramraj", name, "General", "White", size, sleeve)).first!!

    private suspend fun create(qty: Int, size: String = "L", code: ScannedCode? = null, notes: String = ""): Long =
        repo.createVariant(newOpId(), nv(size = size), qty, code, false, notes).also { assertEquals(Code.OK, it.code) }.variantId!!

    private suspend fun qty(id: Long) = dao.variant(id)!!.qty

    @Test fun sellReducesStockByOneAndRecordsSale() = runBlocking {
        val id = create(3)
        val r = repo.sell(newOpId(), id)
        assertEquals(Code.OK, r.code); assertEquals(2, r.remaining); assertEquals(2, qty(id))
        assertNotNull(dao.sale(r.refId!!))
    }

    @Test fun replayedOperationIdNeverAppliesTwice() = runBlocking {
        val id = create(5)
        val op = newOpId()
        val first = repo.sell(op, id)
        val again = repo.sell(op, id)
        assertEquals(first, again)
        assertEquals(4, qty(id))
        assertEquals(first, repo.outcome(op))
    }

    @Test fun sameIdWithDifferentRequestIsRejected() = runBlocking {
        val a = create(5); val b = create(5, size = "XL")
        val op = newOpId()
        repo.sell(op, a)
        assertEquals(Code.MISMATCH, repo.sell(op, b).code)
        assertEquals(5, qty(b))
    }

    @Test fun cannotSellBelowZeroAndRejectionIsRetryable() = runBlocking {
        val id = create(0)
        val op = newOpId()
        assertEquals(Code.NO_STOCK, repo.sell(op, id).code)
        assertEquals(0, qty(id))
        assertEquals(Code.FAILED, repo.outcome(op)!!.code) // not committed: safe to retry
        assertEquals(Code.OK, repo.restock(newOpId(), id, 1).code)
        assertEquals(Code.OK, repo.sell(op, id).code)
        assertEquals(0, qty(id))
    }

    @Test fun concurrentSellsNeverOversell() = runBlocking {
        val id = create(5)
        val results = (1..25).map { async(Dispatchers.IO) { repo.sell(newOpId(), id).code } }.awaitAll()
        assertEquals(5, results.count { it == Code.OK })
        assertEquals(20, results.count { it == Code.NO_STOCK })
        assertEquals(0, qty(id))
    }

    @Test fun archivedItemsCannotBeSoldOrRestocked() = runBlocking {
        val id = create(2)
        repo.setArchived(id, true)
        assertEquals(Code.ARCHIVED, repo.sell(newOpId(), id).code)
        assertEquals(Code.ARCHIVED, repo.restock(newOpId(), id, 1).code)
        assertEquals(2, qty(id))
    }

    @Test fun duplicateIdentityIsRejected() = runBlocking {
        val id = create(1)
        val r = repo.createVariant(newOpId(), nv(name = " shirt "), 4, null, false)
        assertEquals(Code.DUPLICATE, r.code); assertEquals(id, r.variantId)
        assertEquals(1, dao.variantCount())
    }

    @Test fun returnsNeedAMatchingSaleAndCannotExceedIt() = runBlocking {
        val id = create(2)
        assertEquals(Code.NO_SALE, repo.returnItem(newOpId(), id, null, 1, false).code)
        repo.sell(newOpId(), id)
        assertEquals(1, qty(id))
        assertEquals(Code.OK, repo.returnItem(newOpId(), id, null, 1, false).code)
        assertEquals(2, qty(id))
        assertEquals(Code.NO_SALE, repo.returnItem(newOpId(), id, null, 1, false).code)
        assertEquals(2, qty(id))
    }

    @Test fun damagedReturnUsesUpSaleButKeepsStock() = runBlocking {
        val id = create(1)
        val sale = repo.sell(newOpId(), id).refId!!
        val r = repo.returnItem(newOpId(), id, sale, 1, damaged = true)
        assertEquals(Code.OK, r.code); assertEquals(0, qty(id))
        assertEquals(Code.LIMIT, repo.returnItem(newOpId(), id, sale, 1, false).code)
    }

    @Test fun undoSaleRestoresOnceOnly() = runBlocking {
        val id = create(1)
        val sale = repo.sell(newOpId(), id).refId!!
        assertEquals(Code.OK, repo.undoSale(newOpId(), sale).code)
        assertEquals(1, qty(id))
        assertEquals(Code.LIMIT, repo.undoSale(newOpId(), sale).code)
        assertEquals(Code.NO_SALE, repo.returnItem(newOpId(), id, null, 1, false).code)
        assertEquals(1, qty(id))
    }

    @Test fun correctionNeedsReasonAndFreshCount() = runBlocking {
        val id = create(10)
        assertEquals(Code.INVALID, repo.correct(newOpId(), id, 10, 8, " ").code)
        repo.sell(newOpId(), id) // someone sold while the count was typed
        assertEquals(Code.STALE, repo.correct(newOpId(), id, 10, 8, "Count correction").code)
        val r = repo.correct(newOpId(), id, 9, 7, "Damaged")
        assertEquals(Code.OK, r.code); assertEquals(7, qty(id)); assertEquals(-2, r.qty)
    }

    @Test fun invalidQuantitiesRejected() = runBlocking {
        val id = create(1)
        assertEquals(Code.INVALID, repo.restock(newOpId(), id, 0).code)
        assertEquals(Code.INVALID, repo.restock(newOpId(), id, -3).code)
        assertEquals(Code.INVALID, repo.createVariant(newOpId(), nv(size = "M"), -1, null, false).code)
        assertEquals(1, qty(id))
    }

    @Test fun manufacturerBarcodeResolvesAcrossUpcAndEan() = runBlocking {
        val id = create(1, code = ScannedCode("890123456789", "UPC_A"))
        val r = repo.resolve(ScannedCode("0890123456789", "EAN_13"))
        assertEquals(id, (r as Resolution.One).variant.id)
        assertEquals(Resolution.Unknown, repo.resolve(ScannedCode("890123456789", "CODE_128")))
    }

    @Test fun linkingNeverStealsACodeAndSharingNeedsChoice() = runBlocking {
        val code = ScannedCode("SHIRT-001", "CODE_128")
        val a = create(1, code = code)
        val b = create(1, size = "XL")
        assertEquals(LinkResult.TAKEN, repo.link(b, code, share = false).result)
        assertEquals(a, (repo.resolve(code) as Resolution.One).variant.id)
        assertEquals(LinkResult.SHARED, repo.link(b, code, share = true).result)
        val many = repo.resolve(code) as Resolution.Many
        assertEquals(setOf(a, b), many.variants.map { it.id }.toSet())
        assertEquals(LinkResult.ALREADY, repo.link(b, code, share = true).result)
        assertEquals(1, qty(a)); assertEquals(1, qty(b)) // linking never changes stock
    }

    @Test fun createWithTakenLabelRollsBackEverything() = runBlocking {
        val code = ScannedCode("TAG-9", "QR_CODE")
        create(1, code = code)
        val r = repo.createVariant(newOpId(), nv(size = "S"), 5, code, false)
        assertEquals(Code.LABEL_TAKEN, r.code)
        assertEquals(1, dao.variantCount())
    }

    @Test fun internalLabelResolvesOnlyForThisShop() = runBlocking {
        val id = create(1)
        val label = dao.variant(id)!!.code!!
        assertEquals(id, (repo.resolve(ScannedCode(label, "CODE_128")) as Resolution.One).variant.id)
        val other = InternalCode.make(if (repo.catalog() == "ZZZZZ") "YYYYY" else "ZZZZZ", id)
        assertEquals(Resolution.Unknown, repo.resolve(ScannedCode(other, "CODE_128")))
    }

    @Test fun notesAreStoredSearchableAndEditableWithoutChangingIdentity() = runBlocking {
        val id = create(2, notes = "Linen, rack 4")
        val v = dao.variant(id)!!
        assertEquals("Linen, rack 4", v.notes)
        assertTrue(v.search.contains("linen"))
        assertEquals(null, repo.edit(id, nv(), "Cotton"))
        val e = dao.variant(id)!!
        assertEquals("Cotton", e.notes); assertEquals(v.identityKey, e.identityKey); assertEquals(2, e.qty)
        assertTrue(!e.search.contains("linen") && e.search.contains("cotton"))
    }

    @Test fun editIntoAnExistingIdentityReportsClash() = runBlocking {
        val a = create(1)
        val b = create(1, size = "XL")
        assertEquals(a, repo.edit(b, nv(size = "L"), ""))
        assertEquals("XL", dao.variant(b)!!.sizeLabel)
    }

    @Test fun ledgerTypesMatchOperations() = runBlocking {
        val id = create(2)
        val sale = repo.sell(newOpId(), id).refId!!
        repo.returnItem(newOpId(), id, sale, 1, false)
        repo.restock(newOpId(), id, 3)
        val types = db.useWriterConnection { c ->
            c.usePrepared("SELECT type FROM movements WHERE variantId = $id ORDER BY id") { s -> buildList { while (s.step()) add(s.getText(0)) } }
        }
        assertEquals(listOf(MoveType.OPENING, MoveType.SALE, MoveType.RETURN, MoveType.RESTOCK), types)
    }

    @Test fun databaseTriggersGuardInvariantsEvenAgainstRawSql() = runBlocking {
        val id = create(1)
        val sale = repo.sell(newOpId(), id).refId!!
        fun rejects(sql: String) = runBlocking {
            try {
                db.useWriterConnection { c -> c.usePrepared(sql) { it.step() } }
                fail("Expected trigger to reject: $sql")
            } catch (e: Exception) {
                if (e is AssertionError) throw e
            }
        }
        rejects("UPDATE variants SET qty = -1 WHERE id = $id")
        rejects("UPDATE sales SET returnedQty = 2 WHERE id = $sale")
        rejects("UPDATE movements SET delta = 100")
        rejects("DELETE FROM movements")
        rejects("INSERT INTO aliases (`key`, raw, format, variantId, shared, createdAt) VALUES ('T:x', 'x', 'QR_CODE', NULL, 0, 0)")
        assertEquals(0, qty(id))
    }
}
