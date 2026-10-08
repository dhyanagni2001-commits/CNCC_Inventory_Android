package com.cnanjappa.inventory.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodesTest {
    @Test fun upcAAndEan13OfSameItemShareKey() {
        assertEquals(Codes.key("0890123456789", "EAN_13"), Codes.key("890123456789", "UPC_A"))
        assertEquals("G:0890123456789", Codes.key("890123456789", "UPC_A"))
    }

    @Test fun upcEExpandsToUpcA() {
        // Standard examples: 0425261-4 -> 042100005264, 01234565 -> 012345000065
        assertEquals("042100005264", Codes.upcEtoA("04252614"))
        assertEquals("012345000065", Codes.upcEtoA("01234565"))
        assertEquals(Codes.key("042100005264", "UPC_A"), Codes.key("04252614", "UPC_E"))
        assertNull(Codes.upcEtoA("24252614"))
        assertNull(Codes.upcEtoA("123"))
    }

    @Test fun leadingZeroesAndCaseArePreserved() {
        assertEquals("T:00123", Codes.key("00123", "CODE_128"))
        assertNotEquals(Codes.key("abc", "QR_CODE"), Codes.key("ABC", "QR_CODE"))
        assertNotEquals(Codes.key("123", "CODE_128"), Codes.key("0123", "CODE_128"))
    }

    @Test fun malformedGtinFallsBackToText() {
        assertEquals("T:12345", Codes.key("12345", "EAN_13"))
        assertEquals("T:1234567", Codes.key("1234567", "EAN_8"))
        assertEquals("E8:12345670", Codes.key("12345670", "EAN_8"))
    }

    @Test fun typedCodesClassifiedByLength() {
        assertEquals(Codes.key("0890123456789", "EAN_13"), Codes.key("0890123456789", Codes.TYPED))
        assertEquals(Codes.key("890123456789", "UPC_A"), Codes.key("890123456789", Codes.TYPED))
        assertEquals("E8:12345670", Codes.key("12345670", Codes.TYPED))
        assertEquals("T:SHIRT-1", Codes.key("SHIRT-1", Codes.TYPED))
    }

    @Test fun internalCodeRoundTrip() {
        val code = InternalCode.make("AB2CD", 123456)
        assertEquals("CNAB2CD-2N9C", code)
        assertEquals("AB2CD", InternalCode.catalogOf(code))
        listOf("CNAB2C-1", "cnAB2CD-1", "CNAB2CD-", "XAB2CD-1", "CNAB2CD-1 ").forEach { assertNull(it, InternalCode.catalogOf(it)) }
    }

    @Test fun formatLabels() {
        assertEquals("QR", Codes.formatLabel("QR_CODE"))
        assertEquals("EAN-13", Codes.formatLabel("EAN_13"))
        assertEquals("Typed", Codes.formatLabel(Codes.TYPED))
    }
}
