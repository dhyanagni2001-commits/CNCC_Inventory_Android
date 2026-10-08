package com.cnanjappa.inventory.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.cnanjappa.inventory.domain.InternalCode
import com.cnanjappa.inventory.domain.MAX_QTY
import com.cnanjappa.inventory.domain.NormalizedVariant
import com.cnanjappa.inventory.domain.ScannedCode
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
        dao.alias(code.key)?.let { a ->
            if (a.variantId != null) dao.variant(a.variantId)?.let { return@withContext Resolution.One(it) }
            return@withContext Resolution.Many(a.key, dao.candidates(a.key))
        }
        val catalog = InternalCode.catalogOf(code.raw)
        if (catalog != null && catalog == catalog()) {
            dao.variantByCode(code.raw)?.let { return@withContext Resolution.One(it) }
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
            if (dao.setQty(variantId, expected, newQty) != 1) throw Reject(Code.STALE, variantId)
            val delta = newQty - expected
            dao.insertMovement(Movement(opId = opId, variantId = variantId, type = MoveType.ADJUST, delta = delta, qty = kotlin.math.abs(delta), ts = now(), note = reason, description = v.description))
            OpResult(Code.OK, variantId, newQty, qty = delta)
        }

    // ---------------- Identity changes (no stock effect) ----------------

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
        val a = dao.alias(code.key)
        return when {
            a == null -> {
                dao.insertAlias(BarcodeAlias(code.key, code.raw, code.format, variantId, false, now()))
                LinkOutcome(LinkResult.LINKED)
            }
            a.variantId == variantId -> LinkOutcome(LinkResult.ALREADY)
            a.shared -> {
                if (dao.candidates(a.key).any { it.id == variantId }) LinkOutcome(LinkResult.ALREADY)
                else { dao.insertCandidate(AliasCandidate(a.key, variantId)); LinkOutcome(LinkResult.SHARED) }
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

    /** Edits descriptive fields; the internal code, stock and history stay. Returns the clashing id, if any. */
    suspend fun edit(variantId: Long, v: NormalizedVariant, notes: String): Long? = withContext(NonCancellable + Dispatchers.IO) {
        tx {
            dao.variantByIdentity(v.identityKey)?.takeIf { it.id != variantId }?.let { return@tx it.id }
            dao.updateDetails(
                variantId, v.company, v.name, v.model, v.colour, v.size.label, v.size.key, v.size.sort,
                v.sleeve.label, v.identityKey, v.groupKey, searchText(v, notes), notes,
            )
            null
        }
    }

    suspend fun setArchived(variantId: Long, archived: Boolean) =
        withContext(NonCancellable + Dispatchers.IO) { dao.setArchived(variantId, archived) }

    suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) { dao.putSetting(Setting(key, value)) }

    companion object {
        const val PENDING = "PENDING"
        const val DONE = "DONE"
        const val KEY_CATALOG = "catalog"
        const val KEY_LAST_BACKUP = "lastBackup"
        const val KEY_LABEL_PRESET = "labelPreset"
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
