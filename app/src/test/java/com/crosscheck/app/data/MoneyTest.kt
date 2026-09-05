package com.crosscheck.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun formats_indian_grouping() {
        assertEquals("₹0", formatRupees(0))
        assertEquals("₹500", formatRupees(50_000))
        assertEquals("₹500.50", formatRupees(50_050))
        assertEquals("₹0.05", formatRupees(5))
        assertEquals("₹1,000", formatRupees(100_000))
        assertEquals("₹1,50,000", formatRupees(15_000_000))
        assertEquals("₹12,34,56,789.10", formatRupees(123_456_789_10L))
        assertEquals("-₹250", formatRupees(-25_000))
    }

    @Test
    fun parses_amount_text() {
        assertEquals(50_000L, parseRupeesToPaise("500"))
        assertEquals(50_000L, parseRupeesToPaise("500.00"))
        assertEquals(50_050L, parseRupeesToPaise("500.5"))
        assertEquals(15_000_000L, parseRupeesToPaise("1,50,000.00"))
        assertNull(parseRupeesToPaise("500.123"))
        assertNull(parseRupeesToPaise("abc"))
        assertNull(parseRupeesToPaise(""))
        assertNull(parseRupeesToPaise("1.2.3"))
    }
}
