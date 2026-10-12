package com.cnanjappa.inventory.domain

import java.util.Locale

/**
 * Barcode identity. The lookup key is symbology class + exact payload. The only equivalence applied
 * is the standard GTIN one: UPC-A, UPC-E and EAN-13 for the same item map to one 13-digit key.
 * Everything else stays exact (no trimming, case folding, prefix or numeric conversion), so leading
 * zeroes survive and unrelated codes never collapse.
 */
data class ScannedCode(val raw: String, val format: String) {
    val key: String get() = Codes.key(raw, format)
}

object Codes {
    const val TYPED = "TYPED"
    private val digits = Regex("""^\d+$""")

    fun key(raw: String, format: String): String = when (format) {
        "EAN_13" -> if (isDigits(raw, 13)) "G:$raw" else "T:$raw"
        "UPC_A" -> if (isDigits(raw, 12)) "G:0$raw" else "T:$raw"
        "UPC_E" -> upcEtoA(raw)?.let { "G:0$it" } ?: "T:$raw"
        "EAN_8" -> if (isDigits(raw, 8)) "E8:$raw" else "T:$raw"
        TYPED -> when {
            isDigits(raw, 13) -> "G:$raw"
            isDigits(raw, 12) -> "G:0$raw"
            isDigits(raw, 8) -> "E8:$raw"
            else -> "T:$raw"
        }
        else -> "T:$raw"
    }

    /**
     * Keys to try when looking a scan up, exact key first. The same printed number can reach the app
     * as a typed code (stored as a GTIN key) and later as a Code 128/39/QR scan (a text key), or the
     * other way round, so a miss on the exact key falls back to the other readings of the same payload.
     */
    fun lookupKeys(raw: String, format: String): List<String> =
        listOf(key(raw, format), key(raw, TYPED), "T:$raw").distinct()

    private fun isDigits(s: String, len: Int) = s.length == len && digits.matches(s)

    /** GS1 check digit for the first 12 digits of an EAN-13. */
    fun eanCheck(first12: String): Char {
        val sum = first12.reversed().withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 3 else 1 }
        return '0' + (10 - sum % 10) % 10
    }

    fun isEan13(s: String) = isDigits(s, 13) && s[12] == eanCheck(s.substring(0, 12))

    /** Expands an 8-digit UPC-E (number system 0/1 + 6 digits + check) to its 12-digit UPC-A. */
    fun upcEtoA(e: String): String? {
        if (!isDigits(e, 8) || (e[0] != '0' && e[0] != '1')) return null
        val d = e.substring(1, 7)
        val body = when (d[5]) {
            '0', '1', '2' -> d.substring(0, 2) + d[5] + "0000" + d.substring(2, 5)
            '3' -> d.substring(0, 3) + "00000" + d.substring(3, 5)
            '4' -> d.substring(0, 4) + "00000" + d[4]
            else -> d.substring(0, 5) + "0000" + d[5]
        }
        return e[0] + body + e[7]
    }

    /** Readable name of the barcode format, for screens and exports. */
    fun formatLabel(format: String) = when (format) {
        TYPED -> "Typed"
        "QR_CODE" -> "QR"
        "DATA_MATRIX" -> "Data Matrix"
        "CODE_128" -> "Code 128"
        "CODE_39" -> "Code 39"
        else -> format.replace('_', '-')
    }
}

/**
 * Barcodes the app generates for tags that have none: EAN-13 in GS1's in-store range (first digit 2,
 * never printed on manufacturer goods), so they scan like any retail barcode and can't clash with a
 * real product's code. Layout: "2" + 3-digit shop number + 8-digit serial + check digit.
 */
object StoreBarcode {
    const val MAX_SERIAL = 99_999_999L

    fun prefix(shop: Int) = String.format(Locale.ROOT, "2%03d", shop)

    fun make(shop: Int, serial: Long): String {
        require(shop in 0..999 && serial in 1..MAX_SERIAL)
        val body = prefix(shop) + String.format(Locale.ROOT, "%08d", serial)
        return body + Codes.eanCheck(body)
    }

    /** Serial of [raw] when it is one of this shop's generated barcodes, else null. */
    fun serialOf(raw: String, shop: Int): Long? =
        raw.takeIf { Codes.isEan13(it) && it.startsWith(prefix(shop)) }?.substring(4, 12)?.toLong()

    /** Any in-store EAN-13 (ours or another in-store system's). */
    fun isStore(raw: String) = Codes.isEan13(raw) && raw[0] == '2'
}

/**
 * App-generated label codes: "CN" + 5-char catalog id + "-" + base-36 variant id. The catalog id is
 * random per installation (kept across backup/restore), so another shop's labels never match.
 */
object InternalCode {
    private val pattern = Regex("""^CN([0-9A-Z]{5})-([0-9A-Z]{1,10})$""")

    fun make(catalog: String, variantId: Long) = "CN$catalog-${variantId.toString(36).uppercase()}"

    /** Returns the catalog id when [raw] looks like an app label, else null. */
    fun catalogOf(raw: String): String? = pattern.matchEntire(raw)?.groupValues?.get(1)
}
