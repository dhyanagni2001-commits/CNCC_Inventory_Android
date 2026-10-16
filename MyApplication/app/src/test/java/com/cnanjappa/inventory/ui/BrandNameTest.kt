package com.cnanjappa.inventory.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrandNameTest {
    @Test fun splitsLongNamesOverTwoLines() {
        assertEquals("C Nanjappa" to "Cloth Center", splitName("C Nanjappa Cloth Center"))
        assertEquals("Ramu Textiles" to "", splitName(" Ramu  Textiles "))
        assertEquals("Inventory" to "", splitName(null))
    }

    @Test fun initialsForTheBadge() {
        assertEquals("CN", initials("C Nanjappa Cloth Center"))
        assertEquals("S", initials("sarees"))
        assertEquals("IN", initials(null))
    }
}
