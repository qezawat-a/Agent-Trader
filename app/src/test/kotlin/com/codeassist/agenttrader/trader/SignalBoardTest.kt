package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalBoardTest {

    private fun tf(name: String, dir: Int, conf: Double, agreeing: Int = 4): TfEvaluation =
        TfEvaluation(name, dir, conf, agreeing, emptyList())

    private fun rising(n: Int = 260): List<Candle> = (0 until n).map { i ->
        val p = 100.0 + i * 0.5
        Candle(time = i.toLong() * 60_000, open = p - 0.2, high = p + 0.3, low = p - 0.4, close = p, volume = 100.0)
    }

    // ---- timeframe evaluation ----

    @Test
    fun a_clean_uptrend_produces_a_long_timeframe_vote() {
        val e = SignalAggregator.evaluateTimeframe("15m", rising())
        assertEquals(1, e.dir)
        assertTrue(e.agreeing >= SignalAggregator.MIN_AGREEING, "agreeing=${e.agreeing}")
        assertTrue(e.confidence >= SignalAggregator.TIMEFRAME_CONFIDENCE, "conf=${e.confidence}")
        assertTrue(e.votes.size == StrategyRegistry.all.size, "every strategy must be listed")
    }

    @Test
    fun timeframe_conversion_keeps_the_same_numbers() {
        val c = rising()
        val e = SignalAggregator.evaluateTimeframe("1h", c)
        val legacy = SignalAggregator.timeframe(c)
        assertEquals(legacy.dir, e.dir)
        assertEquals(legacy.agreeing, e.agreeing)
        assertEquals(legacy.confidence, e.confidence)
    }

    // ---- decision + reasons ----

    @Test
    fun agreeing_timeframes_with_high_confidence_make_a_signal() {
        val row = SignalAggregator.evaluate(
            "BTCUSDT", 104_000.0,
            mapOf("15m" to tf("15m", 1, 75.0), "1h" to tf("1h", 1, 80.0)),
        )
        assertEquals(1, row.dir)
        assertEquals(77.5, row.confidence)
        assertEquals("LONG 78%", row.status)
        assertTrue(row.reasons.any { it.startsWith("15m:") })
    }

    @Test
    fun short_signal_works_the_same_way() {
        val row = SignalAggregator.evaluate(
            "ETHUSDT", 3_000.0,
            mapOf("15m" to tf("15m", -1, 72.0), "1h" to tf("1h", -1, 76.0)),
        )
        assertEquals(-1, row.dir)
        assertEquals("SHORT 74%", row.status)
    }

    @Test
    fun disagreeing_timeframes_block_with_a_reason() {
        val row = SignalAggregator.evaluate(
            "SOLUSDT", 200.0,
            mapOf("15m" to tf("15m", 1, 75.0), "1h" to tf("1h", -1, 75.0)),
        )
        assertEquals(0, row.dir)
        assertEquals("no trade", row.status)
        assertEquals("timeframes disagree", row.reasons.first())
    }

    @Test
    fun a_weak_timeframe_blocks_with_a_reason() {
        val row = SignalAggregator.evaluate(
            "XRPUSDT", 2.0,
            mapOf("15m" to tf("15m", 1, 55.0), "1h" to tf("1h", 1, 80.0)),
        )
        assertEquals(0, row.dir)
        assertTrue(row.reasons.any { it.contains("15m below the 60% timeframe gate") }, row.reasons.toString())
    }

    @Test
    fun confidence_under_seventy_blocks_with_a_reason() {
        val row = SignalAggregator.evaluate(
            "DOGEUSDT", 0.4,
            mapOf("15m" to tf("15m", 1, 65.0), "1h" to tf("1h", 1, 66.0)),
        )
        assertEquals(0, row.dir)
        assertEquals("combined confidence 66% is below 70%", row.reasons.first())
    }

    @Test
    fun a_side_that_never_formed_blocks_with_a_reason() {
        val row = SignalAggregator.evaluate(
            "ADAUSDT", 1.0,
            mapOf("15m" to tf("15m", 0, 0.0, 0), "1h" to tf("1h", 0, 0.0, 0)),
        )
        assertEquals(0, row.dir)
        assertTrue(row.reasons.contains("no timeframe produced a side"))
    }

    @Test
    fun missing_timeframes_are_named() {
        val row = SignalAggregator.evaluate("BNBUSDT", 600.0, mapOf("15m" to tf("15m", 1, 80.0)))
        assertEquals(0, row.dir)
        assertTrue(row.reasons.any { it.contains("missing kline data") && it.contains("1h") }, row.reasons.toString())
    }

    // ---- report formatting ----

    @Test
    fun board_header_states_every_gate() {
        val text = formatSignalBoard(listOf())
        assertTrue(text.contains("15m+1h must agree"))
        assertTrue(text.contains("3 strategies"), text)
        assertTrue(text.contains(">=60%"), text)
        assertTrue(text.contains(">=70%"), text)
    }

    @Test
    fun board_rows_show_confidence_and_the_blocking_reason() {
        val rows = listOf(
            SignalAggregator.evaluate("BTCUSDT", 104_000.0, mapOf("15m" to tf("15m", 1, 75.0), "1h" to tf("1h", 1, 80.0))),
            SignalAggregator.evaluate("SOLUSDT", 200.0, mapOf("15m" to tf("15m", 1, 75.0), "1h" to tf("1h", -1, 75.0))),
        )
        val text = formatSignalBoard(rows)
        assertTrue(text.contains("BTCUSDT"), text)
        assertTrue(text.contains("LONG 78%"), text)
        assertTrue(text.contains("no trade"), text)
        assertTrue(text.contains("why: timeframes disagree"), text)
        assertTrue(text.contains("Best: BTCUSDT LONG 78%"), text)
    }

    @Test
    fun every_strategy_name_is_exposed_for_the_votes_list() {
        val votes = SignalAggregator.votes(rising())
        assertEquals(StrategyRegistry.all.size, votes.size)
        assertTrue(votes.any { it.name == "EMA 9/21 cross" })
        assertTrue(votes.all { it.group.isNotBlank() })
        assertTrue(votes.all { abs(it.confidence) in 0..100 })
    }
}
