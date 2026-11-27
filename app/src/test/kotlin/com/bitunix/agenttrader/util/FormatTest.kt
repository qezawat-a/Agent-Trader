package com.bitunix.agenttrader.util

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Number formatting must not depend on the device locale: a comma decimal
 * separator would print prices as "1,2345" and break anything that reads them
 * back.
 */
class FormatTest {

    @Test
    fun every_precision_uses_a_dot_decimal_separator() {
        assertEquals("1.2346", fmt(1.23456))
        assertEquals("1.2", fmt1(1.23456))
        assertEquals("1.23", fmt2(1.23456))
        assertEquals("1.235", fmt3(1.23456))
        assertEquals("1.234568", fmt6(1.2345678))
        assertEquals("1", fmt0(1.23456))
    }

    @Test
    fun formatting_ignores_the_device_locale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1234.57", fmt2(1234.567))
            assertEquals("12", fmt0(12.4))
        } finally {
            Locale.setDefault(original)
        }
    }
}
