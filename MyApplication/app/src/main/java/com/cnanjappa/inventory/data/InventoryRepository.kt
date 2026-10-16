package com.cnanjappa.inventory.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.cnanjappa.inventory.domain.Codes
import com.cnanjappa.inventory.domain.InternalCode
import com.cnanjappa.inventory.domain.MAX_QTY
import com.cnanjappa.inventory.domain.NormalizedVariant
import com.cnanjappa.inventory.domain.ScannedCode
import com.cnanjappa.inventory.domain.Sizes
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.StoreBarcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

enum class Code { OK, NO_STOCK, ARCHIVED, NOT_FOUND, NO_SALE, LIMIT, STALE, DUPLICATE, LABEL_TAKEN, INVALID, MISMATCH, FAILED }

/** Outcome of a stock operation. [remaining] is the committed stock after the operation. */
data class OpResult(
    val code: Code,
    val variantId: Long? = null,
    val remaining: Int? = null,
    val refId: Long? = null,
    val qty: Int? = null,
) {
    val ok get() = code == Code.OK
}

sealed interface Resolution {
    data class One(val variant: Variant) : Resolution
    data class Many(val key: String, val variants: List<Variant>) : Resolution
    data object Unknown : Resolution
}

enum class LinkResult { LINKED, ALREADY, TAKEN, SHARED }

data class LinkOutcome(val result: LinkResult, val otherVariantId: Long? = null)

private class Reject(val code: Code, val variantId: Long? = null) : Exception(null, null, false, false)

fun newOpId(): String = UUID.randomUUID().toString()

/**
 * The single path for every inventory mutation, shared by camera and manual flows. Each mutation:
 * 1. durably records its operation id as PENDING (recoverable intent),
 * 2. in one transaction re-checks the id, applies conditional updates, writes ledger rows and marks DONE.
 * A replayed id returns the stored outcome. Rejections roll back and leave the id PENDING, which is
 * safe to retry because nothing was changed. Work runs NonCancellable so leaving a screen mid-commit
 * cannot leave the outcome unknown.
 */
class InventoryRepository(val db: AppDatabase) {
    val dao = db.dao()
    @Volatile private var catalogId: String? = null

    /** Write transaction on Room's writer connection (works with the bundled SQLite driver). */
    private suspend fun <R> tx(block: suspend () -> R): R =
        db.useWriterConnection { it.immediateTransaction { block() } }

    suspend fun catalog(): String = catalogId ?: withContext(Dispatchers.IO) {
        tx {
            dao.setting(KEY_CATALOG) ?: randomCatalog().also { dao.putSetting(Setting(KEY_CATALOG, it)) }
        }.also { catalogId = it }
    }

    private fun randomCatalog(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return (1..5).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    // ---------------- Lookup ----------------

    suspend fun resolve(code: ScannedCode): Resolution = withContext(Dispatchers.IO) {
        // Deleted items never match, whatever is still recorded against them.
        for (key in Codes.lookupKeys(code.raw, code.format)) {
            val a = dao.alias(key) ?: continue
            if (a.variantId != null) {
                dao.variant(a.variantId)?.takeIf { !it.archived }?.let { return@withContext Resolution.One(it) }
            } else {
                val live = dao.candidates(a.key)
                if (live.size == 1) return@withContext Resolution.One(live.single())
                if (live.isNotEmpty()) return@withContext Resolution.Many(a.key, live)
            }
        }
        val catalog = InternalCode.catalogOf(code.raw)
        if (catalog != null && catalog == catalog()) {
            // A deleted item's printed app labels no longer find it.
            dao.variantByCode(code.raw)?.takeIf { !it.archived }?.let { return@withContext Resolution.One(it) }
        }
        Resolution.Unknown
    }

    /** Stored outcome for [opId]; FAILED when it never committed (safe to retry with the same id). */
    suspend fun outcome(opId: String): OpResult? = withContext(Dispatchers.IO) {
        val op = dao.operation(opId) ?: return@withContext null
        if (op.status != DONE) OpResult(Code.FAILED) else op.toResult()
    }

    private fun Operation.toResult() =
        OpResult(Code.valueOf(resultCode ?: "OK"), variantId, remaining, refId, qty)

    // ---------------- Operation framework ----------------

    private suspend fun runOp(opId: String, kind: String, request: String, block: suspend () -> OpResult): OpResult =
        withContext(NonCancellable + Dispatchers.IO) {
            val req = "$kind|$request"
            try {
                tx {
                    val existing = dao.operation(opId)
                    if (existing == null) {
                        dao.insertOperation(Operation(opId, kind, req, PENDING, createdAt = now()))
                    }
                }
                tx {
                    val op = dao.operation(opId)!!
                    if (op.status == DONE) {
                        return@tx if (op.request == req) op.toResult() else OpResult(Code.MISMATCH)
                    }
                    val result = block()
                    check(dao.completeOperation(opId, result.code.name, result.variantId, result.remaining, result.refId, result.qty) == 1)
                    result
                }
            } catch (r: Reject) {
                OpResult(r.code, r.variantId)
            } catch (e: Exception) {
                OpResult(Code.FAILED)
            }
        }

    private suspend fun stockOf(id: Long) = dao.variant(id)!!.qty

    // ---------------- Mutations ----------------

    suspend fun createVariant(
        opId: String,
        v: NormalizedVariant,
        openingQty: Int,
        code: ScannedCode?,
        shareCode: Boolean,
        notes: String = "",
    ): OpResult {
        val catalog = catalog()
        return createInTx(opId, v, openingQty, code, shareCode, notes, catalog)
    }

    private suspend fun createInTx(opId: String, v: NormalizedVariant, openingQty: Int, code: ScannedCode?, shareCode: Boolean, notes: String, catalog: String): OpResult =
        runOp(opId, "CREATE", "${v.identityKey}|$openingQty|${code?.key}|$shareCode|${notes.hashCode()}") {
        if (openingQty !in 0..MAX_QTY) throw Reject(Code.INVALID)
        dao.variantByIdentity(v.identityKey)?.let { throw Reject(Code.DUPLICATE, it.id) }
        val ts = now()
        val id = dao.insertVariant(
            Variant(
                code = null, company = v.company, name = v.name, model = v.model, colour = v.colour,
                sizeLabel = v.size.label, sizeKey = v.size.key, sizeSort = v.size.sort, sleeve = v.sleeve.label,
                identityKey = v.identityKey, groupKey = v.groupKey, search = searchText(v, notes), qty = openingQty, createdAt = ts,
                notes = notes,
            ),
        )
        dao.setCode(id, InternalCode.make(catalog, id))
        val variant = dao.variant(id)!!
        if (code != null) {
            val link = linkInTx(id, code, shareCode)
            if (link.result == LinkResult.TAKEN) throw Reject(Code.LABEL_TAKEN, link.otherVariantId)
        }
        dao.insertMovement(
            Movement(opId = opId, variantId = id, type = MoveType.OPENING, delta = openingQty, qty = openingQty, ts = ts, description = variant.description),
        )
        OpResult(Code.OK, id, openingQty, qty = openingQty)
    }

    suspend fun sell(opId: String, variantId: Long): OpResult = runOp(opId, "SELL", "$variantId") {
        val v = dao.variant(variantId) ?: throw Reject(Code.NOT_FOUND)
        if (v.archived) throw Reject(Code.ARCHIVED, variantId)
        if (dao.decrement(variantId, 1) != 1) throw Reject(Code.NO_STOCK, variantId)
        val ts = now()
        val saleId = dao.insertSale(Sale(opId = opId, variantId = variantId, qty = 1, ts = ts, description = v.description))
        dao.insertMovement(Movement(opId = opId, variantId = variantId, type = MoveType.SALE, delta = -1, qty = 1, ts = ts, saleId = saleId, description = v.description))
        OpResult(Code.OK, variantId, stockOf(variantId), saleId, 1)
    }

    /** Reverses whatever part of the sale has not been returned or reversed yet. Allowed once. */
    suspend fun undoSale(opId: String, saleId: Long): OpResult = runOp(opId, "UNDO", "$saleId") {
        val sale = dao.sale(saleId) ?: throw Reject(Code.NOT_FOUND)
        val n = sale.remaining
        if (dao.variant(sale.variantId)?.archived == true) throw Reject(Code.ARCHIVED, sale.variantId)
        if (n <= 0 || dao.addReversed(saleId, n) != 1) throw Reject(Code.LIMIT, sale.variantId)
        if (dao.increment(sale.variantId, n, MAX_QTY) != 1) throw Reject(Code.INVALID, sale.variantId)
        val v = dao.variant(sale.variantId)!!
        dao.insertMovement(Movement(opId = opId, variantId = v.id, type = MoveType.REVERSAL, delta = n, qty = n, ts = now(), saleId = saleId, description = v.description))
        OpResult(Code.OK, v.id, v.qty, saleId, n)
    }

    /**
     * Records a return against [saleId], or the newest eligible sale of the variant when null.
     * Damaged returns consume eligibility with a zero stock delta.
     */
    suspend fun returnItem(opId: String, variantId: Long, saleId: Long?, qty: Int, damaged: Boolean): OpResult =
        runOp(opId, "RETURN", "$variantId|$saleId|$qty|$damaged") {
            if (qty !in 1..MAX_QTY) throw Reject(Code.INVALID)
            val v = dao.variant(variantId) ?: throw Reject(Code.NOT_FOUND)
            if (v.archived) throw Reject(Code.ARCHIVED, variantId)
            val sale = (if (saleId != null) dao.sale(saleId) else dao.newestEligibleSale(variantId))
                ?: throw Reject(Code.NO_SALE, variantId)
            if (sale.variantId != variantId) throw Reject(Code.INVALID, variantId)
            if (dao.addReturned(sale.id, variantId, qty) != 1) throw Reject(Code.LIMIT, variantId)
            val ts = now()
            val returnId = dao.insertReturn(ReturnRecord(opId = opId, saleId = sale.id, variantId = variantId, qty = qty, damaged = damaged, ts = ts, description = v.description))
            if (!damaged && dao.increment(variantId, qty, MAX_QTY) != 1) throw Reject(Code.INVALID, variantId)
            dao.insertMovement(
                Movement(
                    opId = opId, variantId = variantId, type = if (damaged) MoveType.RETURN_DAMAGED else MoveType.RETURN,
                    delta = if (damaged) 0 else qty, qty = qty, ts = ts, saleId = sale.id, returnId = returnId, description = v.description,
                ),
            )
            OpResult(Code.OK, variantId, stockOf(variantId), returnId, qty)
        }

    suspend fun restock(opId: String, variantId: Long, qty: Int): OpResult = runOp(opId, "RESTOCK", "$variantId|$qty") {
        if (qty !in 1..MAX_QTY) throw Reject(Code.INVALID)
        val v = dao.variant(variantId) ?: throw Reject(Code.NOT_FOUND)
        if (v.archived) throw Reject(Code.ARCHIVED, variantId)
        if (dao.increment(variantId, qty, MAX_QTY) != 1) throw Reject(Code.INVALID, variantId)
        dao.insertMovement(Movement(opId = opId, variantId = variantId, type = MoveType.RESTOCK, delta = qty, qty = qty, ts = now(), description = v.description))
        OpResult(Code.OK, variantId, stockOf(variantId), qty = qty)
    }

    /** Sets the counted quantity. [expected] is the stock the user saw; a concurrent change returns STALE. */
    suspend fun correct(opId: String, variantId: Long, expected: Int, newQty: Int, reason: String): OpResult =
        runOp(opId, "CORRECT", "$variantId|$expected|$newQty|$reason") {
            if (newQty !in 0..MAX_QTY || reason.isBlank()) throw Reject(Code.INVALID)
            val v = dao.variant(variantId) ?: throw Reject(Code.NOT_FOUND)
            if (v.archived) throw Reject(Code.ARCHIVED, variantId)
            if (dao.setQty(variantId, expected, newQty) != 1) throw Reject(Code.STALE, variantId)
            val delta = newQty - expected
            dao.insertMovement(Movement(opId = opId, variantId = variantId, type = MoveType.ADJUST, delta = delta, qty = kotlin.math.abs(delta), ts = now(), note = reason, description = v.description))
            OpResult(Code.OK, variantId, newQty, qty = delta)
        }

    // ---------------- Identity changes (no stock effect) ----------------

    /**
     * A new in-store barcode (see [StoreBarcode]) that was never handed out before: the serial is
     * saved as soon as it is used, so a discarded form still uses it up; it never drops below the
     * highest code already saved (e.g. after restoring an older backup); linked codes are skipped.
     * Null only when all serials are used up.
     */
    suspend fun newStoreBarcode(): ScannedCode? = withContext(NonCancellable + Dispatchers.IO) {
        tx {
            val shop = dao.setting(KEY_STORE_SHOP)?.toIntOrNull()
                ?: SecureRandom().nextInt(1000).also { dao.putSetting(Setting(KEY_STORE_SHOP, "$it")) }
            val highest = dao.maxAliasRaw(StoreBarcode.prefix(shop))?.let { StoreBarcode.serialOf(it, shop) } ?: 0L
            var serial = maxOf(dao.setting(KEY_STORE_SERIAL)?.toLongOrNull() ?: 0L, highest)
            var raw: String
            do {
                if (++serial > StoreBarcode.MAX_SERIAL) return@tx null
                raw = StoreBarcode.make(shop, serial)
            } while (Codes.lookupKeys(raw, EAN_13).any { dao.alias(it) != null })
            dao.putSetting(Setting(KEY_STORE_SERIAL, "$serial"))
            ScannedCode(raw, EAN_13)
        }
    }

    suspend fun link(variantId: Long, code: ScannedCode, share: Boolean): LinkOutcome =
        withContext(NonCancellable + Dispatchers.IO) { tx { linkInTx(variantId, code, share) } }

    /**
     * Adds an alias without touching stock. A code already linked to another variant is never
     * overwritten: it is reported as TAKEN, or, when the user confirms it is printed on several
     * items, turned into a shared code that always needs an explicit choice.
     */
    private suspend fun linkInTx(variantId: Long, code: ScannedCode, share: Boolean): LinkOutcome {
        dao.variantByCode(code.raw)?.let { owner ->
            return if (owner.id == variantId) LinkOutcome(LinkResult.ALREADY) else LinkOutcome(LinkResult.TAKEN, owner.id)
        }
        var a = dao.alias(code.key)
        if (a != null && !a.shared && dao.variant(a.variantId!!)?.archived != false) { dao.deleteAlias(a.key); a = null }
        return when {
            a == null -> {
                dao.insertAlias(BarcodeAlias(code.key, code.raw, code.format, variantId, false, now()))
                LinkOutcome(LinkResult.LINKED)
            }
            a.variantId == variantId -> LinkOutcome(LinkResult.ALREADY)
            a.shared -> {
                val on = dao.candidates(a.key)
                when {
                    on.any { it.id == variantId } -> LinkOutcome(LinkResult.ALREADY)
                    !share -> LinkOutcome(LinkResult.TAKEN, on.firstOrNull()?.id)
                    else -> { dao.insertCandidate(AliasCandidate(a.key, variantId)); LinkOutcome(LinkResult.SHARED) }
                }
            }
            !share -> LinkOutcome(LinkResult.TAKEN, a.variantId)
            else -> {
                dao.markShared(a.key)
                dao.insertCandidate(AliasCandidate(a.key, a.variantId!!))
                dao.insertCandidate(AliasCandidate(a.key, variantId))
                LinkOutcome(LinkResult.SHARED)
            }
        }
    }

    /** Takes [variantId] off a shared code; a code left on one item becomes its own, on none it is removed. */
    private suspend fun dropFromShared(key: String, variantId: Long) {
        dao.deleteCandidate(key, variantId)
        val left = dao.candidateIds(key)
        when (left.size) {
            0 -> dao.deleteAlias(key)
            1 -> { dao.unshare(key, left.single()); dao.deleteCandidates(key) }
        }
    }

    /**
     * Saves a whole Edit in one transaction, or nothing: details, barcodes removed ([removeKeys]) and
     * added ([add]), and the quantity. A quantity change is recorded as a correction in History and is
     * refused (STALE) if stock moved since the form loaded [expectedQty]. Rejects DUPLICATE when the
     * new details match another item, LABEL_TAKEN when an added code belongs to another item.
     */
    suspend fun editItem(
        opId: String, variantId: Long, v: NormalizedVariant, notes: String,
        expectedQty: Int, newQty: Int, add: List<ScannedCode>, removeKeys: List<String>,
    ): OpResult = runOp(
        opId, "EDIT",
        "$variantId|${v.identityKey}|${notes.hashCode()}|$expectedQty|$newQty|${add.joinToString(",") { it.key }}|${removeKeys.joinToString(",")}",
    ) {
        if (newQty !in 0..MAX_QTY) throw Reject(Code.INVALID)
        val current = dao.variant(variantId) ?: throw Reject(Code.NOT_FOUND)
        if (current.archived) throw Reject(Code.ARCHIVED, variantId)
        dao.variantByIdentity(v.identityKey)?.takeIf { it.id != variantId }?.let { throw Reject(Code.DUPLICATE, it.id) }
        dao.updateDetails(
            variantId, v.company, v.name, v.model, v.colour, v.size.label, v.size.key, v.size.sort,
            v.sleeve.label, v.identityKey, v.groupKey, searchText(v, notes), notes,
        )
        for (key in removeKeys) {
            val a = dao.alias(key) ?: continue
            if (!a.shared) { if (a.variantId == variantId) dao.deleteAlias(key) }
            else dropFromShared(key, variantId)
        }
        for (code in add) {
            val r = linkInTx(variantId, code, share = false)
            if (r.result == LinkResult.TAKEN) throw Reject(Code.LABEL_TAKEN, r.otherVariantId)
        }
        if (newQty != expectedQty) {
            if (dao.setQty(variantId, expectedQty, newQty) != 1) throw Reject(Code.STALE, variantId)
            val delta = newQty - expectedQty
            dao.insertMovement(Movement(opId = opId, variantId = variantId, type = MoveType.ADJUST, delta = delta, qty = kotlin.math.abs(delta), ts = now(), note = "Edited", description = dao.variant(variantId)!!.description))
        }
        OpResult(Code.OK, variantId, newQty, qty = newQty - expectedQty)
    }

    /**
     * Deletes an item for good: it leaves stock, search and scanning, and its barcodes and identity
     * are freed for reuse. Pieces left are written off to 0 as a "Deleted" correction so totals stay
     * right; refused (STALE) if stock moved since the user saw [expectedQty]. The row itself stays so
     * past sales keep their place in History and Excel.
     */
    suspend fun deleteItem(opId: String, variantId: Long, expectedQty: Int): OpResult =
        runOp(opId, "DELETE", "$variantId|$expectedQty") {
            val v = dao.variant(variantId) ?: throw Reject(Code.NOT_FOUND)
            if (v.archived) return@runOp OpResult(Code.OK, variantId, 0)
            if (expectedQty > 0) {
                if (dao.setQty(variantId, expectedQty, 0) != 1) throw Reject(Code.STALE, variantId)
                dao.insertMovement(Movement(opId = opId, variantId = variantId, type = MoveType.ADJUST, delta = -expectedQty, qty = expectedQty, ts = now(), note = "Deleted", description = v.description))
            } else if (v.qty != 0) throw Reject(Code.STALE, variantId)
            purge(variantId)
            OpResult(Code.OK, variantId, 0, qty = -expectedQty)
        }

    /** Removes a deleted item's barcodes and frees its identity; the row stays for History. */
    private suspend fun purge(variantId: Long) {
        dao.deleteAliasesOf(variantId)
        for (key in dao.candidateKeysOf(variantId)) dropFromShared(key, variantId)
        dao.markDeleted(variantId)
    }

    /** Once: products saved before text was kept in capitals are converted, so lists never mix cases. */
    suspend fun upperCaseOnce() = withContext(NonCancellable + Dispatchers.IO) {
        tx {
            if (dao.setting(KEY_CAPS) == null) { dao.upperCaseText(); dao.putSetting(Setting(KEY_CAPS, "1")) }
        }
    }

    /**
     * Once: sizes saved before waist sizes existed (e.g. "M/24-38" kept as free text, or "24-38" read
     * as an age) get the key the size picker now uses, so picking them again finds the same item
     * instead of creating a duplicate. An item whose new identity is already taken is left as it was.
     * Stock and history are untouched.
     */
    suspend fun resizeOnce() = withContext(NonCancellable + Dispatchers.IO) {
        tx {
            if (dao.setting(KEY_SIZES) != null) return@tx
            for (v in dao.rangeOrCustomSizes()) {
                val size = Sizes.canonical(v.sizeLabel) ?: continue
                if (size.key == v.sizeKey) continue
                val sleeve = Sleeve.entries.firstOrNull { it.label == v.sleeve } ?: continue
                val nv = NormalizedVariant(v.company, v.name, v.model, v.colour, size.copy(label = size.label.uppercase(java.util.Locale.ROOT)), sleeve)
                if (dao.variantByIdentity(nv.identityKey) != null) continue
                dao.updateDetails(
                    v.id, v.company, v.name, v.model, v.colour, nv.size.label, nv.size.key, nv.size.sort,
                    v.sleeve, nv.identityKey, nv.groupKey, searchText(nv, v.notes), v.notes,
                )
            }
            dao.putSetting(Setting(KEY_SIZES, "1"))
        }
    }

    /**
     * Items hidden by the old Archive button now count as deleted. Run once at start: frees their
     * barcodes and identity, and writes off pieces left as a "Deleted" correction (ledger stays right).
     */
    suspend fun finishOldArchives() = withContext(NonCancellable + Dispatchers.IO) {
        for (id in dao.unfinishedDeletes()) tx {
            val v = dao.variant(id) ?: return@tx
            if (v.qty > 0) {
                dao.setQty(id, v.qty, 0)
                dao.insertMovement(Movement(opId = newOpId(), variantId = id, type = MoveType.ADJUST, delta = -v.qty, qty = v.qty, ts = now(), note = "Deleted", description = v.description))
            }
            purge(id)
        }
    }

    suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) { dao.putSetting(Setting(key, value)) }

    companion object {
        const val PENDING = "PENDING"
        const val DONE = "DONE"
        const val KEY_CATALOG = "catalog"
        const val KEY_LAST_BACKUP = "lastBackup"
        const val KEY_LABEL_PRESET = "labelPreset"
        const val KEY_STORE_SHOP = "storeBarcodeShop"
        const val KEY_CAPS = "textInCapitals"
        const val KEY_SIZES = "sizeKeysV2"
        const val KEY_STORE_SERIAL = "storeBarcodeSerial"
        private const val EAN_13 = "EAN_13"
        fun now() = System.currentTimeMillis()

        /** Notes are searchable too, so "linen" or "rack 4" finds the item. */
        fun searchText(v: NormalizedVariant, notes: String) =
            if (notes.isBlank()) v.search else v.search + " " + notes.lowercase(java.util.Locale.ROOT).replace('\n', ' ')

        /** LIKE pattern for a search box, escaping wildcards so user text is literal. */
        fun likePattern(q: String): String {
            val esc = q.trim().lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return "%" + esc.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("%") + "%"
        }
    }
}
