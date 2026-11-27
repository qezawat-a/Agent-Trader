package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.Candle
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the SIDE of the strategies that REVIEW.md, open finding #2, flagged.
 *
 * "Mid-Band MACD Cross" compared the MACD line (a price DIFFERENCE) with the
 * Bollinger mid-band (a price LEVEL), so it voted SHORT on every market.
 * "DEMA55 Bullish Trend Follow" asked a LAGGING average to be above the price,
 * so the "bullish" strategy voted SHORT. "TEMA 20 trend" and "MACD histogram
 * trend" compared two nearly equal numbers and let the rounding decide.
 *
 * The fingerprint test pins the registry on a perfectly LINEAR ramp. On such a
 * ramp the TEMA coincides with the price and the MACD is flat, so there is no
 * signal to give - which is why the fixed strategies abstain there. These
 * tests use a GEOMETRIC trend instead, where the indicators really do
 * separate and the side is meaningful.
 */
class StrategySideCorrectnessTest {

    private fun candle(index: Long, close: Double, volume: Double = 100.0): Candle =
        Candle(time = index * 60_000, open = close - 0.2, high = close + 0.3, low = close - 0.4, close = close, volume = volume)

    /** A market that keeps accelerating: +0.4% per bar. */
    private fun accelerating(count: Int): List<Candle> =
        (0 until count).map { i -> candle(i.toLong(), 100.0 * 1.004.pow(i.toDouble())) }

    /** The same move read backwards: the decline slows down towards the end. */
    private fun decelerating(count: Int): List<Candle> =
        (0 until count).map { i -> candle(i.toLong(), 100.0 * 1.004.pow((count - 1 - i).toDouble())) }

    private fun flat(count: Int): List<Candle> =
        (0 until count).map { i -> candle(i.toLong(), 100.0) }

    private fun vote(name: String, candles: List<Candle>): Vote =
        StrategyRegistry.all.find { it.name == name }!!.vote(candles)

    @Test
    fun the_tema_trend_votes_the_side_the_trend_is_on() {
        assertEquals(1, vote("TEMA 20 trend", accelerating(250)).dir)
        assertEquals(-1, vote("TEMA 20 trend", decelerating(250)).dir)
    }

    @Test
    fun the_macd_histogram_trend_votes_the_side_the_histogram_moves_to() {
        assertEquals(1, vote("MACD histogram trend", accelerating(250)).dir)
        assertEquals(-1, vote("MACD histogram trend", decelerating(250)).dir)
    }

    @Test
    fun the_mid_band_macd_cross_votes_long_only_when_macd_and_price_agree() {
        assertEquals(1, vote("Mid-Band MACD Cross", accelerating(250)).dir)
        // Mirrored market: the price sits under the mid-band while the MACD
        // still points up. The two disagree, so the strategy abstains instead
        // of guessing a side.
        assertEquals(0, vote("Mid-Band MACD Cross", decelerating(250)).dir)
    }

    @Test
    fun the_bullish_dema_strategy_never_votes_short() {
        assertEquals(1, vote("DEMA55 Bullish Trend Follow", accelerating(250)).dir)
        for (candles in listOf(accelerating(250), decelerating(250), flat(250))) {
            assertTrue(
                vote("DEMA55 Bullish Trend Follow", candles).dir != -1,
                "a bullish strategy voted SHORT",
            )
        }
    }

    @Test
    fun a_flat_market_produces_no_side_from_the_indicator_strategies() {
        val c = flat(250)
        assertEquals(0, vote("TEMA 20 trend", c).dir)
        assertEquals(0, vote("MACD histogram trend", c).dir)
        assertEquals(0, vote("Mid-Band MACD Cross", c).dir)
        assertEquals(0, vote("DEMA55 Bullish Trend Follow", c).dir)
    }

    @Test
    fun a_crossing_that_is_only_rounding_noise_is_not_a_signal() {
        // A perfectly linear ramp: the TEMA lands exactly on the price and the
        // MACD histogram does not move, so there is nothing to vote on.
        val linear = (0 until 250).map { i -> candle(i.toLong(), 100.0 + i * 0.5) }
        assertEquals(0, vote("TEMA 20 trend", linear).dir)
        assertEquals(0, vote("MACD histogram trend", linear).dir)
        assertEquals(0, vote("Mid-Band MACD Cross", linear).dir)
    }
}
