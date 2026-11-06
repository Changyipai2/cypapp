package com.dsh.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 純 JVM 邏輯的守門測試（不依賴 Android framework）。 */
class CsvTest {

    @Test
    fun splitLine_keepsQuotedSeparator() {
        assertEquals(listOf("a", "b,c", "d"), Csv.splitLine("a,\"b,c\",d"))
    }

    @Test
    fun splitLine_handlesEscapedQuote() {
        assertEquals(listOf("he said \"hi\"", "x"), Csv.splitLine("\"he said \"\"hi\"\"\",x"))
    }

    @Test
    fun parseDate_acceptsCommonFormats() {
        assertEquals(LocalDate.of(2024, 1, 5), Csv.parseDate("2024-01-05"))
        assertEquals(LocalDate.of(2024, 1, 5), Csv.parseDate("2024/01/05"))
        assertEquals(LocalDate.of(2024, 1, 5), Csv.parseDate("2024年1月5日"))
        assertEquals(LocalDate.of(2024, 1, 5), Csv.parseDate("20240105"))
    }

    @Test
    fun parseDate_rejectsGarbage() {
        assertNull(Csv.parseDate(""))
        assertNull(Csv.parseDate("not a date"))
    }

    @Test
    fun parseAmount_normalizesCurrencyAndCommas() {
        assertEquals(123456L, Csv.parseAmount("1,234.56"))
        assertEquals(123456L, Csv.parseAmount("NT$1,234.56"))
        assertEquals(-123456L, Csv.parseAmount("-1,234.56"))
        assertEquals(100L, Csv.parseAmount("1.00"))
        assertEquals(101L, Csv.parseAmount("1.005"))
    }

    @Test
    fun parseAmount_rejectsGarbage() {
        assertNull(Csv.parseAmount(""))
        assertNull(Csv.parseAmount("abc"))
    }

    @Test
    fun toYuan_formatsCents() {
        assertEquals("12.34", 1234L.toYuan())
        assertEquals("-0.05", (-5L).toYuan())
        assertEquals("0.00", 0L.toYuan())
    }

    @Test
    fun toCentsOrNull_parsesUserInput() {
        assertEquals(1234L, "12.34".toCentsOrNull())
        assertEquals(123450L, "1,234.50".toCentsOrNull())
        assertNull("".toCentsOrNull())
        assertNull("abc".toCentsOrNull())
    }
}
