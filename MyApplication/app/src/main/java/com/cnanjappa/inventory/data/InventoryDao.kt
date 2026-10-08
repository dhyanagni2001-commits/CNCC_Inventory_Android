package com.cnanjappa.inventory.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class StockGroup(
    val groupKey: String,
    val company: String,
    val name: String,
    val model: String,
    val colour: String,
    val fullQty: Int,
    val halfQty: Int,
    val total: Int,
    val anyArchived: Boolean,
)

data class SizeRow(val sizeLabel: String, val fullQty: Int, val halfQty: Int, val total: Int, val archived: Boolean)

data class HistoryRow(
    val id: Long,
    val type: String,
    val delta: Int,
    val qty: Int,
    val ts: Long,
    val description: String,
    val note: String?,
    val saleId: Long?,
    val variantId: Long,
    val saleRemaining: Int?,
)

data class TypeTotal(val type: String, val pieces: Int)

@Dao
interface InventoryDao {
    // ---- Variants ----
    @Insert
    suspend fun insertVariant(v: Variant): Long

    @Query("UPDATE variants SET code = :code WHERE id = :id")
    suspend fun setCode(id: Long, code: String)

    @Query("SELECT * FROM variants WHERE id = :id")
    suspend fun variant(id: Long): Variant?

    @Query("SELECT * FROM variants WHERE id = :id")
    fun observeVariant(id: Long): Flow<Variant?>

    @Query("SELECT * FROM variants WHERE code = :code")
    suspend fun variantByCode(code: String): Variant?

    @Query("SELECT * FROM variants WHERE identityKey = :key")
    suspend fun variantByIdentity(key: String): Variant?

    /** Conditional sale: only succeeds when enough stock exists and the item is active. */
    @Query("UPDATE variants SET qty = qty - :n WHERE id = :id AND qty >= :n AND archived = 0")
    suspend fun decrement(id: Long, n: Int): Int

    @Query("UPDATE variants SET qty = qty + :n WHERE id = :id AND qty <= :max - :n")
    suspend fun increment(id: Long, n: Int, max: Int): Int

    @Query("UPDATE variants SET qty = :newQty WHERE id = :id AND qty = :expected")
    suspend fun setQty(id: Long, expected: Int, newQty: Int): Int

    @Query(
        """UPDATE variants SET company = :company, name = :name, model = :model, colour = :colour,
        sizeLabel = :sizeLabel, sizeKey = :sizeKey, sizeSort = :sizeSort, sleeve = :sleeve,
        identityKey = :identityKey, groupKey = :groupKey, search = :search, notes = :notes WHERE id = :id""",
    )
    suspend fun updateDetails(
        id: Long, company: String, name: String, model: String, colour: String, sizeLabel: String,
        sizeKey: String, sizeSort: Int, sleeve: String, identityKey: String, groupKey: String, search: String, notes: String,
    )

    @Query("UPDATE variants SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query(
        """SELECT * FROM variants WHERE search LIKE :q ESCAPE '\'
        ORDER BY name COLLATE NOCASE, company COLLATE NOCASE, model COLLATE NOCASE, colour COLLATE NOCASE, sizeSort, sizeLabel, sleeve""",
    )
    fun variantsPaged(q: String): PagingSource<Int, Variant>

    @Query(
        """SELECT groupKey, MIN(company) AS company, MIN(name) AS name, MIN(model) AS model, MIN(colour) AS colour,
        SUM(CASE WHEN sleeve = 'Full' THEN qty ELSE 0 END) AS fullQty,
        SUM(CASE WHEN sleeve = 'Half' THEN qty ELSE 0 END) AS halfQty,
        SUM(qty) AS total, MAX(archived) AS anyArchived
        FROM variants WHERE (archived = 0 OR qty > 0) AND search LIKE :q ESCAPE '\'
        GROUP BY groupKey ORDER BY name COLLATE NOCASE, company COLLATE NOCASE, model COLLATE NOCASE, colour COLLATE NOCASE""",
    )
    fun groupsPaged(q: String): PagingSource<Int, StockGroup>

    @Query(
        """SELECT MIN(sizeLabel) AS sizeLabel,
        SUM(CASE WHEN sleeve = 'Full' THEN qty ELSE 0 END) AS fullQty,
        SUM(CASE WHEN sleeve = 'Half' THEN qty ELSE 0 END) AS halfQty,
        SUM(qty) AS total, MAX(archived) AS archived
        FROM variants WHERE groupKey = :groupKey AND (archived = 0 OR qty > 0)
        GROUP BY sizeKey ORDER BY MIN(sizeSort), MIN(sizeLabel)""",
    )
    fun sizeBreakdown(groupKey: String): Flow<List<SizeRow>>

    @Query("SELECT * FROM variants WHERE groupKey = :groupKey AND (archived = 0 OR qty > 0) ORDER BY sizeSort, sizeLabel, sleeve")
    fun groupVariants(groupKey: String): Flow<List<Variant>>

    @Query("SELECT IFNULL(SUM(qty), 0) FROM variants")
    fun totalPieces(): Flow<Int>

    @Query("SELECT company FROM variants WHERE company != '' AND company LIKE :q ESCAPE '\\' GROUP BY company COLLATE NOCASE ORDER BY MAX(id) DESC LIMIT :limit")
    fun companies(q: String, limit: Int): Flow<List<String>>

    @Query("SELECT name FROM variants WHERE name LIKE :q ESCAPE '\\' GROUP BY name COLLATE NOCASE ORDER BY MAX(id) DESC LIMIT :limit")
    fun names(q: String, limit: Int): Flow<List<String>>

    @Query(
        """SELECT model FROM variants WHERE (:name = '' OR name = :name COLLATE NOCASE) AND model LIKE :q ESCAPE '\'
        GROUP BY model COLLATE NOCASE ORDER BY MAX(id) DESC LIMIT :limit""",
    )
    fun models(name: String, q: String, limit: Int): Flow<List<String>>

    @Query("SELECT colour FROM variants WHERE colour != '' GROUP BY colour COLLATE NOCASE ORDER BY MAX(id) DESC LIMIT :limit")
    fun colours(limit: Int): Flow<List<String>>

    @Query("SELECT MIN(sizeLabel) FROM variants WHERE sizeKey NOT IN (:standard) GROUP BY sizeKey ORDER BY MIN(sizeSort), MIN(sizeLabel) LIMIT 60")
    fun extraSizes(standard: List<String>): Flow<List<String>>

    // ---- Barcode aliases ----
    @Query("SELECT * FROM aliases WHERE `key` = :key")
    suspend fun alias(key: String): BarcodeAlias?

    @Insert
    suspend fun insertAlias(a: BarcodeAlias)

    @Query("UPDATE aliases SET variantId = NULL, shared = 1 WHERE `key` = :key")
    suspend fun markShared(key: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCandidate(c: AliasCandidate)

    @Query("SELECT v.* FROM alias_candidates c JOIN variants v ON v.id = c.variantId WHERE c.`key` = :key ORDER BY v.company, v.sizeSort, v.sleeve")
    suspend fun candidates(key: String): List<Variant>

    @Query(
        """SELECT * FROM aliases WHERE variantId = :variantId
        OR `key` IN (SELECT `key` FROM alias_candidates WHERE variantId = :variantId) ORDER BY createdAt""",
    )
    fun aliasesFor(variantId: Long): Flow<List<BarcodeAlias>>

    // ---- Sales and returns ----
    @Insert
    suspend fun insertSale(s: Sale): Long

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun sale(id: Long): Sale?

    /** Newest eligible sale; id breaks ties deterministically. */
    @Query("SELECT * FROM sales WHERE variantId = :variantId AND qty - returnedQty - reversedQty > 0 ORDER BY ts DESC, id DESC LIMIT 1")
    suspend fun newestEligibleSale(variantId: Long): Sale?

    @Query("SELECT * FROM sales WHERE variantId = :variantId AND qty - returnedQty - reversedQty > 0 ORDER BY ts DESC, id DESC LIMIT 50")
    fun eligibleSales(variantId: Long): Flow<List<Sale>>

    @Query("UPDATE sales SET returnedQty = returnedQty + :n WHERE id = :id AND variantId = :variantId AND qty - returnedQty - reversedQty >= :n")
    suspend fun addReturned(id: Long, variantId: Long, n: Int): Int

    @Query("UPDATE sales SET reversedQty = reversedQty + :n WHERE id = :id AND qty - returnedQty - reversedQty >= :n")
    suspend fun addReversed(id: Long, n: Int): Int

    @Insert
    suspend fun insertReturn(r: ReturnRecord): Long

    // ---- Movements ----
    @Insert
    suspend fun insertMovement(m: Movement): Long

    @Query(
        """SELECT m.id, m.type, m.delta, m.qty, m.ts, m.description, m.note, m.saleId, m.variantId,
        CASE WHEN m.type = 'SALE' THEN s.qty - s.returnedQty - s.reversedQty END AS saleRemaining
        FROM movements m LEFT JOIN sales s ON s.id = m.saleId ORDER BY m.id DESC""",
    )
    fun history(): PagingSource<Int, HistoryRow>

    @Query("SELECT type, SUM(qty) AS pieces FROM movements WHERE ts >= :from AND ts < :to GROUP BY type")
    fun totalsBetween(from: Long, to: Long): Flow<List<TypeTotal>>

    /** Variants whose stored quantity disagrees with the ledger (should always be empty). */
    @Query(
        """SELECT v.id FROM variants v LEFT JOIN (SELECT variantId, SUM(delta) AS s FROM movements GROUP BY variantId) m
        ON m.variantId = v.id WHERE v.qty != IFNULL(m.s, 0) OR v.qty < 0 LIMIT 20""",
    )
    suspend fun unreconciled(): List<Long>

    // ---- Operations ----
    @Query("SELECT * FROM operations WHERE opId = :opId")
    suspend fun operation(opId: String): Operation?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(op: Operation)

    @Query(
        """UPDATE operations SET status = 'DONE', resultCode = :code, variantId = :variantId, remaining = :remaining,
        refId = :refId, qty = :qty WHERE opId = :opId AND status = 'PENDING'""",
    )
    suspend fun completeOperation(opId: String, code: String, variantId: Long?, remaining: Int?, refId: Long?, qty: Int?): Int

    // ---- Settings ----
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun setting(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observeSetting(key: String): Flow<String?>

    @Upsert
    suspend fun putSetting(s: Setting)

    @Query("SELECT COUNT(*) FROM variants")
    suspend fun variantCount(): Int

    @Query("SELECT MAX(ts) FROM movements")
    fun lastChange(): Flow<Long?>
}
