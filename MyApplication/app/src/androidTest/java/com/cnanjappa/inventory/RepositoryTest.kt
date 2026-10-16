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
import com.cnanjappa.inventory.domain.Codes
import com.cnanjappa.inventory.domain.InternalCode
import com.cnanjappa.inventory.domain.ScannedCode
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.StoreBarcode
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

    @Test fun deletedItemIsGoneEverywhereButHistoryStays() = runBlocking {
        val tag = ScannedCode("8901000000019", "EAN_13")
        val id = create(3, code = tag)
        val sale = repo.sell(newOpId(), id).refId!!
        val label = dao.variant(id)!!.code!!
        assertEquals(Code.OK, repo.deleteItem(newOpId(), id, 2).code)

        // Pieces written off (tearDown checks the ledger still reconciles); can't be sold, stocked or returned.
        assertEquals(0, qty(id))
        assertEquals(Code.ARCHIVED, repo.sell(newOpId(), id).code)
        assertEquals(Code.ARCHIVED, repo.restock(newOpId(), id, 1).code)
        assertEquals(Code.ARCHIVED, repo.returnItem(newOpId(), id, sale, 1, false).code)
        assertEquals(Code.ARCHIVED, repo.undoSale(newOpId(), sale).code)
        // Not found by its barcode or its printed app label; the sale record is kept.
        assertEquals(Resolution.Unknown, repo.resolve(tag))
        assertEquals(Resolution.Unknown, repo.resolve(ScannedCode(label, "CODE_128")))
        assertNotNull(dao.sale(sale))

        // Barcode and identity are free again: the same item can be added with the same tag.
        val again = create(5, code = tag)
        assertEquals(again, (repo.resolve(tag) as Resolution.One).variant.id)
    }

    @Test fun deletingOneOfTwoItemsSharingALabelLeavesItToTheOther() = runBlocking {
        val code = ScannedCode("SHARED-TAG", "CODE_128")
        val a = create(1, code = code)
        val b = create(1, size = "XL")
        assertEquals(LinkResult.SHARED, repo.link(b, code, share = true).result)
        assertEquals(Code.OK, repo.deleteItem(newOpId(), a, 1).code)
        // No "choose the item" step any more: the label is simply B's.
        assertEquals(b, (repo.resolve(code) as Resolution.One).variant.id)
        assertEquals(false, dao.alias(code.key)!!.shared)
    }

    @Test fun itemsHiddenByOldArchiveNeverScanAndAreCleanedUp() = runBlocking {
        val tag = ScannedCode("OLD-ARCHIVED", "CODE_128")
        val id = create(4, code = tag)
        // What the removed Archive button did: hide the item, keep its barcode.
        db.useWriterConnection { c -> c.usePrepared("UPDATE variants SET archived = 1 WHERE id = $id") { it.step() } }
        // Before the clean-up runs: the label no longer finds it, and another item can take the label.
        assertEquals(Resolution.Unknown, repo.resolve(tag))
        val other = create(1, size = "XL")
        assertEquals(LinkResult.LINKED, repo.link(other, tag, share = false).result)
        assertEquals(other, (repo.resolve(tag) as Resolution.One).variant.id)

        repo.finishOldArchives()
        assertEquals(0, qty(id)) // written off as "Deleted"; tearDown checks the ledger still reconciles
        assertTrue(dao.variant(id)!!.identityKey.startsWith("deleted:"))
        assertEquals(other, (repo.resolve(tag) as Resolution.One).variant.id) // the other item's label is untouched
        repo.finishOldArchives() // nothing left to do the second time
    }

    @Test fun oneBarcodeCanOnlyBeOnOneItem() = runBlocking {
        val code = ScannedCode("ONE-TAG", "CODE_128")
        val a = create(1, code = code)
        val b = create(1, size = "XL")
        assertEquals(LinkResult.TAKEN, repo.link(b, code, share = false).result)
        assertEquals(Code.LABEL_TAKEN, repo.createVariant(newOpId(), nv(size = "M"), 1, code, false, "").code)
        // Even a label already shared (from before this rule) takes no further items.
        val c = create(1, size = "S")
        assertEquals(LinkResult.SHARED, repo.link(b, code, share = true).result)
        assertEquals(LinkResult.TAKEN, repo.link(c, code, share = false).result)
        assertEquals(setOf(a, b), (repo.resolve(code) as Resolution.Many).variants.map { it.id }.toSet())
    }

    @Test fun oldMixedCaseTextBecomesCapitalsOnce() = runBlocking {
        val id = create(1, notes = "x")
        db.useWriterConnection { c -> c.usePrepared("UPDATE variants SET name = 'Ramraj', model = 'Rr Image', colour = 'White', notes = 'rack 4' WHERE id = $id") { it.step() } }
        repo.upperCaseOnce()
        val v = dao.variant(id)!!
        assertEquals(listOf("RAMRAJ", "RR IMAGE", "WHITE", "RACK 4"), listOf(v.name, v.model, v.colour, v.notes))
        // Runs only once: later text is left alone.
        db.useWriterConnection { c -> c.usePrepared("UPDATE variants SET notes = 'later' WHERE id = $id") { it.step() } }
        repo.upperCaseOnce()
        assertEquals("later", dao.variant(id)!!.notes)
    }

    @Test fun oldDhotiSizesGetWaistKeysOnceWithoutDuplicates() = runBlocking {
        val typed = create(5, size = "Free")
        val clash = create(2, size = "Plain")
        // As saved before waist sizes existed: free text, and a range read as an age.
        suspend fun old(id: Long, label: String, key: String) = db.useWriterConnection { c ->
            c.usePrepared("UPDATE variants SET sizeLabel = '$label', sizeKey = '$key', identityKey = groupKey || char(31) || '$key' || char(31) || 'FULL' WHERE id = $id") { it.step() }
        }
        old(typed, "M/24-38", "C:m/24-38")
        old(clash, "24-38", "A:24-38")
        repo.resizeOnce()
        val v = dao.variant(typed)!!
        assertEquals(listOf("W:24-38", "M/24–38", nv(size = "M/24-38").identityKey), listOf(v.sizeKey, v.sizeLabel, v.identityKey))
        assertEquals(5, v.qty)
        // The second item would now be the same size: it is left alone rather than merged.
        assertEquals("A:24-38", dao.variant(clash)!!.sizeKey)
        // Picking the size again finds the existing item instead of creating a duplicate.
        assertEquals(Code.DUPLICATE, repo.createVariant(newOpId(), nv(size = "24-38"), 1, null, false, "").code)
    }

    @Test fun restockOneAtATimeAndUndoLastKeepsLedger() = runBlocking {
        val id = create(2)
        repeat(3) { assertEquals(Code.OK, repo.restock(newOpId(), id, 1).code) }
        assertEquals(5, qty(id))
        assertEquals(4, repo.correct(newOpId(), id, 5, 4, "Undo add stock").remaining)
    }

    @Test fun deleteRefusedWhenStockMovedSinceConfirming() = runBlocking {
        val id = create(3)
        repo.sell(newOpId(), id)
        assertEquals(Code.STALE, repo.deleteItem(newOpId(), id, 3).code)
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
        // Same printed digits in another symbology (e.g. a Code 128 tag for a typed number) still finds it...
        assertEquals(id, (repo.resolve(ScannedCode("890123456789", "CODE_128")) as Resolution.One).variant.id)
        // ...but an exact match always wins over that fallback.
        val exact = create(1, size = "XL", code = ScannedCode("890123456789", "CODE_128"))
        assertEquals(exact, (repo.resolve(ScannedCode("890123456789", "CODE_128")) as Resolution.One).variant.id)
    }

    @Test fun typedNumberFoundByCode128AndUpcEScans() = runBlocking {
        val a = create(1, code = ScannedCode("8905550001014", Codes.TYPED))
        assertEquals(a, (repo.resolve(ScannedCode("8905550001014", "CODE_128")) as Resolution.One).variant.id)
        val b = create(1, size = "XL", code = ScannedCode("01234565", Codes.TYPED))
        assertEquals(b, (repo.resolve(ScannedCode("01234565", "UPC_E")) as Resolution.One).variant.id)
    }

    @Test fun generatedBarcodesAreValidAndNeverReused() = runBlocking {
        val seen = HashSet<String>()
        repeat(50) {
            val c = repo.newStoreBarcode()!!
            assertTrue(c.raw, Codes.isEan13(c.raw) && StoreBarcode.isStore(c.raw))
            assertTrue("repeated ${c.raw}", seen.add(c.raw))
        }
        val linked = repo.newStoreBarcode()!!
        val id = create(1, code = linked)
        // The printed EAN-13 label scans back to the item.
        assertEquals(id, (repo.resolve(ScannedCode(linked.raw, "EAN_13")) as Resolution.One).variant.id)

        // A code someone already typed in is skipped.
        val shop = dao.setting(InventoryRepository.KEY_STORE_SHOP)!!.toInt()
        val next = StoreBarcode.make(shop, dao.setting(InventoryRepository.KEY_STORE_SERIAL)!!.toLong() + 1)
        create(1, size = "XL", code = ScannedCode(next, Codes.TYPED))
        assertTrue(repo.newStoreBarcode()!!.raw != next)

        // An older backup restored (saved serial goes back): still never below codes already in use.
        repo.setSetting(InventoryRepository.KEY_STORE_SERIAL, "0")
        repeat(5) { val c = repo.newStoreBarcode()!!.raw; assertTrue("reissued $c", c !in seen && c != linked.raw && c != next) }

        // All serials used up: no code (and no crash) rather than wrapping round to an old one.
        repo.setSetting(InventoryRepository.KEY_STORE_SERIAL, "${StoreBarcode.MAX_SERIAL}")
        assertEquals(null, repo.newStoreBarcode())
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
        assertEquals(Code.OK, repo.editItem(newOpId(), id, nv(), "Cotton", 2, 2, emptyList(), emptyList()).code)
        val e = dao.variant(id)!!
        assertEquals("Cotton", e.notes); assertEquals(v.identityKey, e.identityKey); assertEquals(2, e.qty)
        assertTrue(!e.search.contains("linen") && e.search.contains("cotton"))
    }

    @Test fun editIntoAnExistingIdentityReportsClash() = runBlocking {
        val a = create(1)
        val b = create(1, size = "XL")
        val r = repo.editItem(newOpId(), b, nv(size = "L"), "", 1, 1, emptyList(), emptyList())
        assertEquals(Code.DUPLICATE, r.code); assertEquals(a, r.variantId)
        assertEquals("XL", dao.variant(b)!!.sizeLabel)
    }

    @Test fun editChangesEveryFieldQuantityAndBarcodesTogether() = runBlocking {
        val old = ScannedCode("OLD-TAG-1", "CODE_128")
        val id = create(5, code = old)
        val new = ScannedCode("8901000000019", "EAN_13")
        val half = Validate.variant(VariantInput("Siyaram", "Kurta", "Festive", "Cream", "42", Sleeve.HALF)).first!!
        assertEquals(Code.OK, repo.editItem(newOpId(), id, half, Validate.notes("Silk")!!, 5, 3, listOf(new), listOf(old.key)).code)
        val v = dao.variant(id)!!
        assertEquals(listOf("SIYARAM", "KURTA", "FESTIVE", "CREAM", "42", "Half", "SILK", 3), listOf(v.company, v.name, v.model, v.colour, v.sizeLabel, v.sleeve, v.notes, v.qty))
        assertEquals(Resolution.Unknown, repo.resolve(old))
        assertEquals(id, (repo.resolve(new) as Resolution.One).variant.id)
        // The quantity change is in History as a correction (tearDown also checks the ledger reconciles).
        val delta = db.useWriterConnection { c ->
            c.usePrepared("SELECT delta FROM movements WHERE variantId = $id AND type = '${MoveType.ADJUST}'") { st -> st.step(); st.getLong(0) }
        }
        assertEquals(-2L, delta)
    }

    @Test fun editIsAllOrNothing() = runBlocking {
        val a = create(1, code = ScannedCode("A-TAG", "CODE_128"))
        val b = create(4, size = "XL", code = ScannedCode("B-TAG", "CODE_128"))
        val changed = nv(name = "Renamed", size = "XL")
        // Adding a code that belongs to another item rejects the whole edit.
        assertEquals(Code.LABEL_TAKEN, repo.editItem(newOpId(), b, changed, "", 4, 9, listOf(ScannedCode("A-TAG", "CODE_128")), emptyList()).code)
        // Stock moved since the form loaded (a sale): rejected, nothing applied.
        repo.sell(newOpId(), b)
        assertEquals(Code.STALE, repo.editItem(newOpId(), b, changed, "", 4, 9, emptyList(), listOf(ScannedCode("B-TAG", "CODE_128").key)).code)
        val v = dao.variant(b)!!
        assertEquals("SHIRT", v.name); assertEquals(3, v.qty)
        assertEquals(b, (repo.resolve(ScannedCode("B-TAG", "CODE_128")) as Resolution.One).variant.id)
        assertEquals(a, (repo.resolve(ScannedCode("A-TAG", "CODE_128")) as Resolution.One).variant.id)
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
