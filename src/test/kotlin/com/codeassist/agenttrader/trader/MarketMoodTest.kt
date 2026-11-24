package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the market feeling is computed from candles, funding and the book. */
class MarketMoodTest {

    private fun candles(closes: List<Double>, range: Double = 0.005): List<Candle> =
        closes.mapIndexed { i, c ->
            Candle(
                time = 1_700_000_000_000L + i * 900_000L,
                open = c, high = c * (1 + range), low = c * (1 - range), close = c,
                volume = 1.0,
            )
        }

    @Test
    fun flat_market_is_calm() {
        val mood = MarketMoodCalc.assess(candles(List(20) { 100.0 + it * 0.01 }))
        assertEquals("CALM", mood.label)
        assertTrue(mood.stress < 22, "stress ${mood.stress}")
    }

    @Test
    fun wild_candles_are_stressed() {
        val mood = MarketMoodCalc.assess(candles(List(20) { 100.0 + it }, range = 0.06))
        assertTrue(mood.stress >= 45, "stress ${mood.stress}")
        assertTrue(mood.label == "STRESSED" || mood.label == "PANIC")
        assertTrue(mood.drivers.any { it.contains("volatility") }, mood.drivers.toString())
    }

    @Test
    fun crash_from_the_high_adds_stress() {
        val up = List(10) { 100.0 + it }
        val down = List(10) { 109.0 - it * 1.2 } // falls ~13% from the high
        val mood = MarketMoodCalc.assess(candles(up + down))
        assertTrue(mood.stress >= 22, "stress ${mood.stress}")
        assertTrue(mood.drivers.any { it.contains("off the recent high") || it.contains("below the recent high") }, mood.drivers.toString())
    }

    @Test
    fun extreme_funding_marks_crowding() {
        val mood = MarketMoodCalc.assess(candles(List(20) { 100.0 }), funding = 0.002)
        assertTrue(mood.drivers.any { it.contains("crowded long") }, mood.drivers.toString())
        val short = MarketMoodCalc.assess(candles(List(20) { 100.0 }), funding = -0.002)
        assertTrue(short.drivers.any { it.contains("crowded short") }, short.drivers.toString())
    }

    @Test
    fun underwater_book_is_called_out() {
        val mood = MarketMoodCalc.assess(candles(List(20) { 100.0 }), pnlPct = -0.2)
        assertTrue(mood.bookNote.contains("underwater"), mood.bookNote)
        assertTrue(mood.stress >= 25)
    }

    @Test
    fun winning_book_is_noticed_but_not_panicked() {
        val mood = MarketMoodCalc.assess(candles(List(20) { 100.0 }), pnlPct = 0.12)
        assertTrue(mood.bookNote.contains("up"), mood.bookNote)
    }

    @Test
    fun stress_is_bounded() {
        val mood = MarketMoodCalc.assess(candles(List(20) { 100.0 - it }, range = 0.2), funding = 0.5, pnlPct = -5.0)
        assertTrue(mood.stress in 0..100, "stress ${mood.stress}")
        assertEquals("PANIC", mood.label)
    }

    @Test
    fun summary_is_human_readable() {
        val mood = MarketMoodCalc.assess(candles(List(20) { 100.0 }))
        assertTrue(mood.summary().startsWith("CALM"), mood.summary())
    }
}
