package com.cnanjappa.inventory.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LikePatternTest {
    @Test fun wordsBecomeOrderedWildcards() {
        assertEquals("%%", InventoryRepository.likePattern("  "))
        assertEquals("%white%shirt%", InventoryRepository.likePattern(" White   SHIRT "))
    }

    @Test fun userWildcardsAreLiteral() {
        assertEquals("%50\\%%", InventoryRepository.likePattern("50%"))
        assertEquals("%a\\_b%", InventoryRepository.likePattern("a_b"))
        assertEquals("%c:\\\\x%", InventoryRepository.likePattern("c:\\x"))
    }
}
