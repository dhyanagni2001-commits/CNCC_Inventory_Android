package com.cnanjappa.inventory.domain

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

    private fun isDigits(s: String, len: Int) = s.length == len && digits.matches(s)

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
 * App-generated label codes: "CN" + 5-char catalog id + "-" + base-36 variant id. The catalog id is
 * random per installation (kept across backup/restore), so another shop's labels never match.
 */
object InternalCode {
    private val pattern = Regex("""^CN([0-9A-Z]{5})-([0-9A-Z]{1,10})$""")

    fun make(catalog: String, variantId: Long) = "CN$catalog-${variantId.toString(36).uppercase()}"

    /** Returns the catalog id when [raw] looks like an app label, else null. */
    fun catalogOf(raw: String): String? = pattern.matchEntire(raw)?.groupValues?.get(1)
}
