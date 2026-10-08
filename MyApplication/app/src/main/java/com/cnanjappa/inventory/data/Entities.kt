package com.cnanjappa.inventory.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stock-keeping variant: company + name + model + colour + size + sleeve. Owns the quantity. */
@Entity(
    tableName = "variants",
    indices = [
        Index(value = ["identityKey"], unique = true),
        Index(value = ["code"], unique = true),
        Index(value = ["groupKey"]),
    ],
)
data class Variant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable internal label code; assigned right after insert inside the same transaction. */
    val code: String?,
    val company: String,
    val name: String,
    val model: String,
    val colour: String,
    val sizeLabel: String,
    val sizeKey: String,
    val sizeSort: Int,
    val sleeve: String,
    val identityKey: String,
    val groupKey: String,
    val search: String,
    val qty: Int,
    val archived: Boolean = false,
    val createdAt: Long,
    /** Free-text notes from the shop (fabric, supplier, rack...). Not part of the identity. */
    @ColumnInfo(defaultValue = "") val notes: String = "",
) {
    val title get() = listOf(company, name, model).filter { it.isNotEmpty() }.joinToString(" · ")
    val detail get() = listOf(colour, "Size $sizeLabel", sleeve).filter { it.isNotEmpty() }.joinToString(" · ")
    val description get() = "$title — $detail"
}

/**
 * Manufacturer/QR code alias. A direct alias has [variantId]; a code seen on several variants is
 * [shared] with a null variantId and its choices live in [AliasCandidate]. Linking never adds stock.
 */
@Entity(tableName = "aliases", indices = [Index(value = ["variantId"])])
data class BarcodeAlias(
    @PrimaryKey val key: String,
    val raw: String,
    val format: String,
    val variantId: Long?,
    val shared: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "alias_candidates", primaryKeys = ["key", "variantId"], indices = [Index(value = ["variantId"])])
data class AliasCandidate(val key: String, val variantId: Long)

@Entity(tableName = "sales", indices = [Index(value = ["variantId", "ts"]), Index(value = ["opId"], unique = true)])
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opId: String,
    val variantId: Long,
    val qty: Int,
    val returnedQty: Int = 0,
    val reversedQty: Int = 0,
    val ts: Long,
    val description: String,
) {
    val remaining get() = qty - returnedQty - reversedQty
}

@Entity(tableName = "returns", indices = [Index(value = ["saleId"]), Index(value = ["opId"], unique = true)])
data class ReturnRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opId: String,
    val saleId: Long,
    val variantId: Long,
    val qty: Int,
    val damaged: Boolean,
    val ts: Long,
    val description: String,
)

object MoveType {
    const val OPENING = "OPENING"
    const val SALE = "SALE"
    const val RETURN = "RETURN"
    const val RETURN_DAMAGED = "RETURN_DAMAGED"
    const val REVERSAL = "REVERSAL"
    const val RESTOCK = "RESTOCK"
    const val ADJUST = "ADJUST"

    fun label(t: String) = when (t) {
        OPENING -> "Opening stock"
        SALE -> "Sold"
        RETURN -> "Returned"
        RETURN_DAMAGED -> "Damaged return"
        REVERSAL -> "Sale undone"
        RESTOCK -> "Stock added"
        ADJUST -> "Stock corrected"
        else -> t
    }
}

/** Ledger row. Stock of a variant always equals the sum of its deltas. */
@Entity(
    tableName = "movements",
    indices = [Index(value = ["variantId"]), Index(value = ["ts"]), Index(value = ["opId"])],
)
data class Movement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opId: String,
    val variantId: Long,
    val type: String,
    val delta: Int,
    /** Pieces involved; differs from delta for damaged returns (delta 0). */
    val qty: Int,
    val ts: Long,
    val saleId: Long? = null,
    val returnId: Long? = null,
    val note: String? = null,
    val description: String,
)

/**
 * Durable record of an intentional mutation. Written as PENDING before execution and switched to
 * DONE inside the mutation's own transaction, so a PENDING row after a crash means "not committed".
 */
@Entity(tableName = "operations")
data class Operation(
    @PrimaryKey val opId: String,
    val kind: String,
    val request: String,
    val status: String,
    val resultCode: String? = null,
    val variantId: Long? = null,
    val remaining: Int? = null,
    val refId: Long? = null,
    val qty: Int? = null,
    val createdAt: Long,
)

@Entity(tableName = "settings")
data class Setting(@PrimaryKey val key: String, val value: String)
