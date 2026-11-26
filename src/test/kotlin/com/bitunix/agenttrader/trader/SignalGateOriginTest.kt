package com.bitunix.agenttrader.trader

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "N strategies agree" gate must count INDEPENDENT signals.
 *
 * REVIEW.md, open finding #1: the registry carries the same market view under
 * several names (a hammer and a "hammer oversold bounce", an EMA 9/21 cross
 * and the Vegas tunnel that reads the same two EMAs). Every name used to count
 * separately, so three copies of ONE view were enough to open the gate and
 * the engine traded on a single observation.
 */
class SignalGateOriginTest {

    private fun vote(name: String, origin: String, dir: Int = 1, conf: Int = 70) =
        StrategyVote(name, "Test", dir, conf, origin)

    @Test
    fun duplicate_votes_of_one_signal_count_once() {
        val votes = listOf(
            vote("EMA 9/21 cross", "ema9_21"),
            vote("Vegas Tunnel Trend-Confirmed Bullish", "ema9_21"),
            vote("Hammer", "hammer"),
        )
        val e = SignalAggregator.evaluateVotes("15m", votes)
        assertEquals(0, e.dir, "two independent signals must not open the gate")
        assertEquals(0, e.agreeing)
    }

    @Test
    fun three_independent_signals_open_the_gate() {
        val votes = listOf(vote("a", "a"), vote("b", "b"), vote("c", "c"))
        val e = SignalAggregator.evaluateVotes("15m", votes)
        assertEquals(1, e.dir)
        assertEquals(3, e.agreeing)
    }

    @Test
    fun duplicate_views_do_not_inflate_confidence() {
        val votes = listOf(
            vote("a", "a", conf = 70),
            vote("a-copy", "a", conf = 95),
            vote("b", "b", conf = 70),
            vote("c", "c", conf = 70),
        )
        val e = SignalAggregator.evaluateVotes("15m", votes)
        assertEquals(1, e.dir)
        assertEquals(3, e.agreeing)
        // The duplicate contributes one origin value (its strongest 95%), but
        // it must not count as a fourth vote or earn an agreement bonus.
        assertEquals((95.0 + 70.0 + 70.0) / 3.0, e.confidence, 0.001)
    }

    @Test
    fun abstentions_never_count_toward_the_gate() {
        val votes = listOf(vote("a", "a"), vote("b", "b"), vote("c", "c", dir = 0))
        val e = SignalAggregator.evaluateVotes("15m", votes)
        assertEquals(0, e.dir)
        assertEquals(0, e.agreeing)
    }

    @Test
    fun the_registry_keeps_the_duplicate_pairs_on_one_origin() {
        fun origin(name: String): String = StrategyRegistry.all.find { it.name == name }!!.origin

        assertEquals(origin("EMA 9/21 cross"), origin("Vegas Tunnel Trend-Confirmed Bullish"))
        assertEquals(origin("EMA 9/21 cross"), origin("Vegas Tunnel Trend-Confirmed Bearish"))
        assertEquals(origin("Hammer"), origin("Hammer Oversold Bounce"))
        assertEquals(origin("Shooting star"), origin("Shooting Star Overbought Alert"))
        assertEquals(origin("Morning star"), origin("Morning Star Bullish Divergence"))
        assertEquals(origin("Dark cloud cover"), origin("Dark Cloud Cover Death Cross"))
        assertEquals(origin("VWAP deviation"), origin("VWAP Breakdown Alert"))
        assertEquals(origin("Golden Cross 50/200 Enhanced Long"), origin("Death Cross 50/200 Enhanced Short"))
    }

    @Test
    fun a_real_evaluation_counts_distinct_origins() {
        val candles = (0 until 250).map { i ->
            com.bitunix.agenttrader.bitunix.Candle(
                time = i * 60_000L,
                open = 100.0 * 1.004.pow(i.toDouble()) - 0.2,
                high = 100.0 * 1.004.pow(i.toDouble()) + 0.3,
                low = 100.0 * 1.004.pow(i.toDouble()) - 0.4,
                close = 100.0 * 1.004.pow(i.toDouble()),
                volume = 100.0,
            )
        }
        val e = SignalAggregator.evaluateTimeframe("15m", candles)
        if (e.dir != 0) {
            val origins = e.votes.filter { it.dir == e.dir }.map { it.origin }.distinct().size
            assertEquals(origins, e.agreeing)
            assertTrue(e.agreeing >= SignalAggregator.MIN_AGREEING)
        }
    }
}
