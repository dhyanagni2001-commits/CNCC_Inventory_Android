package com.cnanjappa.inventory.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizeTest {
    private fun input(name: String = "Shirt", model: String = "General", size: String = "L", sleeve: Sleeve? = Sleeve.FULL, company: String = "", colour: String = "") =
        VariantInput(company, name, model, colour, size, sleeve)

    @Test fun letterAliasesCollapse() {
        listOf("L", "l", " large ", "LARGE").forEach { assertEquals("L:L", Sizes.canonical(it)!!.key) }
        listOf("XXL", "2xl", "Double XL").forEach { assertEquals("L:XXL", Sizes.canonical(it)!!.key) }
        assertEquals("L:XXXL", Sizes.canonical("3XL")!!.key)
    }

    @Test fun sizeTypesNeverConvert() {
        assertEquals(SizeType.NUMBER, Sizes.canonical("40")!!.type)
        assertNotEquals(Sizes.canonical("40")!!.key, Sizes.canonical("L")!!.key)
        assertNotEquals(Sizes.canonical("2-3")!!.key, Sizes.canonical("2")!!.key)
    }

    @Test fun ageSizesAcceptCommonSpellings() {
        listOf("2-3", "2–3", "2 - 3 yrs", "2-3 years").forEach {
            val s = Sizes.canonical(it)!!
            assertEquals(SizeType.AGE, s.type); assertEquals("A:2-3", s.key); assertEquals("2–3 years", s.label)
        }
    }

    @Test fun customSizeKeepsTextButIsCaseInsensitive() {
        val a = Sizes.canonical("Free  Size")!!
        assertEquals(SizeType.CUSTOM, a.type); assertEquals("Free Size", a.label)
        assertEquals(a.key, Sizes.canonical("free size")!!.key)
        assertNull(Sizes.canonical("   "))
    }

    @Test fun sortOrderIsLettersNumbersAgesCustom() {
        val sorts = listOf("XXXL", "36", "0-1", "Free").map { Sizes.canonical(it)!!.sort }
        assertEquals(sorts.sorted(), sorts)
        assertTrue(Sizes.canonical("S")!!.sort < Sizes.canonical("M")!!.sort)
    }

    @Test fun validVariantNormalises() {
        val (v, errors) = Validate.variant(input(name = "  White   Shirt ", company = "Ramraj", colour = "white"))
        assertTrue(errors.isEmpty())
        assertEquals("White Shirt", v!!.name)
        assertTrue(v.search.contains("large"))
    }

    @Test fun identityIgnoresCaseAndSpacingButNotSleeveOrSize() {
        val a = Validate.variant(input(name = "white shirt")).first!!
        val b = Validate.variant(input(name = " WHITE  Shirt ")).first!!
        val c = Validate.variant(input(sleeve = Sleeve.HALF)).first!!
        val d = Validate.variant(input(size = "XL")).first!!
        assertEquals(a.identityKey, b.identityKey)
        assertEquals(a.groupKey, Validate.variant(input(name = "White Shirt", size = "XL")).first!!.groupKey)
        assertNotEquals(Validate.variant(input()).first!!.identityKey, c.identityKey)
        assertNotEquals(Validate.variant(input()).first!!.identityKey, d.identityKey)
    }

    @Test fun missingRequiredFieldsReportedInOrder() {
        val (v, errors) = Validate.variant(input(name = "", model = " ", size = "", sleeve = null))
        assertNull(v)
        assertEquals(listOf(Field.NAME, Field.MODEL, Field.SIZE, Field.SLEEVE), errors.map { it.field })
    }

    @Test fun controlCharactersAndLengthRejected() {
        assertEquals(Field.NAME, Validate.variant(input(name = "Shirt\u0007")).second.single().field)
        assertEquals(Field.COMPANY, Validate.variant(input(company = "x".repeat(NAME_MAX + 1))).second.single().field)
        assertTrue(Validate.variant(input(name = "x".repeat(NAME_MAX))).second.isEmpty())
    }

    @Test fun quantityParsing() {
        assertEquals(0, Validate.quantity("0"))
        assertEquals(12, Validate.quantity(" 12 "))
        assertEquals(MAX_QTY, Validate.quantity(MAX_QTY.toString()))
        listOf("", "-1", "1.5", "abc", "99999999", (MAX_QTY + 1).toString()).forEach { assertNull(it, Validate.quantity(it)) }
        assertNull(Validate.quantity("0", min = 1))
    }

    @Test fun notesAreCleanedAndLimited() {
        assertEquals("", Validate.notes("   "))
        assertEquals("Cotton, rack 4", Validate.notes("  Cotton,\track   4 "))
        assertEquals("Line one\n\nLine two", Validate.notes("Line one\r\n\n\n\n  Line two  "))
        assertEquals("a b", Validate.notes("a\u0000b"))
        assertEquals(NOTES_MAX, Validate.notes("x".repeat(NOTES_MAX))!!.length)
        assertNull(Validate.notes("x".repeat(NOTES_MAX + 1)))
    }

    @Test fun coloursReuseExistingSpelling() {
        assertEquals("White", Colours.resolve(" white ", emptyList()))
        assertEquals("Sky Blue", Colours.resolve("sky  blue", listOf("Sky Blue")))
        assertEquals("Mint Green", Colours.resolve("Mint  Green", listOf("Red")))
        assertEquals(listOf("White", "Cream"), Colours.FIRST)
    }
}
