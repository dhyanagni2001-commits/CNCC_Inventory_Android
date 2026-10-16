package com.cnanjappa.inventory.domain

import java.util.Locale

const val SHOP_NAME = "C Nanjappa Cloth Center"

const val NAME_MAX = 100
const val SIZE_MAX = 30
const val COLOUR_MAX = 40
const val NOTE_MAX = 200
const val NOTES_MAX = 500
const val MAX_QTY = 1_000_000

enum class Sleeve(val label: String) { FULL("Full"), HALF("Half") }

/** Size type order also drives on-screen sort: letters, numbers, ages, waist, then custom. */
enum class SizeType(val tab: String, val caption: String) {
    LETTER("Letter", "Letter sizes"),
    NUMBER("Number", "Number sizes"),
    AGE("Age", "Age sizes (years) — for kids"),
    WAIST("Waist", "Waist sizes (inches) — dhoti, velcro dhoti, pants"),
    CUSTOM("Other", "Other saved sizes"),
}

data class CanonicalSize(val type: SizeType, val label: String, val key: String, val sort: Int)

object Sizes {
    val LETTERS = listOf("S", "M", "L", "XL", "XXL", "XXXL")
    val LETTER_HINTS = mapOf("L" to "Large", "XXL" to "Double XL", "XXXL" to "Triple XL")
    val NUMBERS = (36..50).map { it.toString() }
    val AGES = (0..17).map { "$it–${it + 1}" } + "18–20"
    /** Waist ranges as printed on readymade/velcro dhoti tags (e.g. Ramraj M/24-38, L/40-46). */
    val WAISTS = listOf("M/24–38", "L/40–46")

    /** The one-tap choices shown under each size tab; CUSTOM has only the shop's saved sizes. */
    val STANDARD = mapOf(
        SizeType.LETTER to LETTERS, SizeType.NUMBER to NUMBERS, SizeType.AGE to AGES,
        SizeType.WAIST to WAISTS, SizeType.CUSTOM to emptyList(),
    )

    private val letterAliases = mapOf(
        "s" to "S", "small" to "S",
        "m" to "M", "medium" to "M",
        "l" to "L", "large" to "L",
        "xl" to "XL", "extra large" to "XL",
        "xxl" to "XXL", "2xl" to "XXL", "double xl" to "XXL",
        "xxxl" to "XXXL", "3xl" to "XXXL", "triple xl" to "XXXL",
    )
    private val ageRegex = Regex("""^(\d{1,2})\s*[-–—]\s*(\d{1,2})\s*(y|yr|yrs|year|years)?$""")
    private val waistRegex = Regex("""^(?:(s|m|l|xl|xxl|xxxl|2xl|3xl)\s*[/(]?\s*)?(\d{2})\s*[-–—]\s*(\d{2})\s*\)?\s*(?:in|inch|inches|")?$""")
    private val numberRegex = Regex("""^\d{1,3}$""")

    /**
     * Canonicalises a typed or tapped size. Aliases (L/Large, 2XL/XXL...) collapse, but sizes of
     * different types never convert into each other: numeric 40 is never L, and 2–3 years is never 2.
     * A range is an age up to 18–20; a range from 20 inches up is a waist, keyed by the range alone so
     * "M/24-38" and "24-38" are the same size, but never the letter M.
     */
    fun canonical(input: String): CanonicalSize? {
        val clean = Text.clean(input)
        if (clean.isEmpty()) return null
        val lower = clean.lowercase(Locale.ROOT)
        letterAliases[lower]?.let { return CanonicalSize(SizeType.LETTER, it, "L:$it", LETTERS.indexOf(it)) }
        ageRegex.matchEntire(lower)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a < b && b <= 20) return CanonicalSize(SizeType.AGE, "$a–$b years", "A:$a-$b", 20_000 + a * 100 + b)
        }
        waistRegex.matchEntire(lower)?.let { m ->
            val a = m.groupValues[2].toInt()
            val b = m.groupValues[3].toInt()
            if (a in 20..60 && a < b) {
                val letter = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { letterAliases[it] }
                val label = (letter?.let { "$it/" } ?: "") + "$a–$b"
                return CanonicalSize(SizeType.WAIST, label, "W:$a-$b", 25_000 + (a - 20) * 100 + b)
            }
        }
        if (numberRegex.matches(lower)) {
            val n = lower.toInt()
            return CanonicalSize(SizeType.NUMBER, n.toString(), "N:$n", 10_000 + n)
        }
        return CanonicalSize(SizeType.CUSTOM, clean, "C:$lower", 30_000)
    }

    fun searchTerms(size: CanonicalSize): String = when (size.type) {
        SizeType.WAIST -> size.label + " waist"
        else -> size.label + " " + (LETTER_HINTS[size.label] ?: "")
    }
}

object Text {
    private val ws = Regex("""\s+""")
    private val control = Regex("""\p{Cc}""")

    fun clean(s: String): String = ws.replace(s.trim(), " ")
    fun key(s: String): String = clean(s).lowercase(Locale.ROOT)
    fun hasControl(s: String) = control.containsMatchIn(s)
}

data class VariantInput(
    val company: String,
    val name: String,
    val model: String,
    val colour: String,
    val size: String,
    val sleeve: Sleeve?,
)

enum class Field { NAME, MODEL, SIZE, SLEEVE, COMPANY, COLOUR, QTY, CODE, NOTES }

data class FieldError(val field: Field, val message: String)

data class NormalizedVariant(
    val company: String,
    val name: String,
    val model: String,
    val colour: String,
    val size: CanonicalSize,
    val sleeve: Sleeve,
) {
    val groupKey get() = listOf(company, name, model, colour).joinToString(SEP) { Text.key(it) }
    val identityKey get() = listOf(groupKey, size.key, sleeve.name).joinToString(SEP)
    val search: String
        get() = listOf(company, name, model, colour, Sizes.searchTerms(size), sleeve.label)
            .joinToString(" ").lowercase(Locale.ROOT)

    companion object {
        private const val SEP = "\u001F"
    }
}

object Validate {
    /** Returns field errors in on-screen order; empty means the input is valid. */
    fun variant(input: VariantInput): Pair<NormalizedVariant?, List<FieldError>> {
        val errors = mutableListOf<FieldError>()
        fun text(v: String, field: Field, max: Int, required: String?): String {
            val c = Text.clean(v)
            when {
                Text.hasControl(v) -> errors += FieldError(field, "Remove unusual characters")
                c.isEmpty() && required != null -> errors += FieldError(field, required)
                c.length > max -> errors += FieldError(field, "Use $max letters or fewer")
            }
            return c
        }
        val name = text(input.name, Field.NAME, NAME_MAX, "Enter company name")
        val model = text(input.model, Field.MODEL, NAME_MAX, "Enter product name")
        val sizeText = text(input.size, Field.SIZE, SIZE_MAX, "Choose a size")
        val size = Sizes.canonical(sizeText)
        if (input.sleeve == null) errors += FieldError(Field.SLEEVE, "Choose Full or Half")
        val company = text(input.company, Field.COMPANY, NAME_MAX, null)
        val colour = text(input.colour, Field.COLOUR, COLOUR_MAX, "Choose a colour")
        if (errors.isNotEmpty() || size == null) return null to errors
        // Saved in capitals however it was entered; identity keys are lower-case, so "Ramraj" = "RAMRAJ".
        fun up(t: String) = t.uppercase(Locale.ROOT)
        return NormalizedVariant(up(company), up(name), up(model), up(colour), size.copy(label = up(size.label)), input.sleeve!!) to emptyList()
    }

    /**
     * Cleans free-text product notes: keeps line breaks (max 2 in a row), drops other control
     * characters, trims each line. Returns null when longer than [NOTES_MAX].
     */
    fun notes(s: String): String? {
        val lines = s.replace("\r\n", "\n").split('\n')
            .map { line -> Text.clean(line.map { c -> if (c.isISOControl()) ' ' else c }.joinToString("")) }
        val out = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return out.uppercase(Locale.ROOT).takeIf { it.length <= NOTES_MAX }
    }

    /** Parses a typed quantity; rejects blank, negative, fractional and overflowing values. */
    fun quantity(s: String, min: Int = 0): Int? =
        s.trim().takeIf { it.matches(Regex("""\d{1,7}""")) }?.toInt()?.takeIf { it in min..MAX_QTY }
}

object Colours {
    val FIRST = listOf("White", "Cream")

    /** Matches an existing colour case-insensitively so "white " reuses White. */
    fun resolve(typed: String, saved: List<String>): String {
        val key = Text.key(typed)
        return (FIRST + saved).firstOrNull { Text.key(it) == key } ?: Text.clean(typed)
    }
}
