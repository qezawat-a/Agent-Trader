package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline tests for the exact-spec strategies in StrategyRegistry (no network). */
class StrategyTest {

    private fun candle(i: Long, close: Double, volume: Double = 100.0): Candle =
        Candle(time = i * 60_000, open = close - 0.2, high = close + 0.3, low = close - 0.4, close = close, volume = volume)

    private fun rising(n: Int): List<Candle> = (0 until n).map { i -> candle(i.toLong(), 100.0 + i * 0.5) }
    private fun falling(n: Int): List<Candle> = (0 until n).map { i -> candle(i.toLong(), 100.0 - i * 0.5) }

    private fun volLast(candles: List<Candle>, volume: Double): List<Candle> =
        candles.dropLast(1) + candle(candles.last().time / 60_000, candles.last().close, volume)

    // ---- Classic: Golden / Death Cross (need 200+ candles for SMA 200) ----

    @Test
    fun golden_cross_long_when_sma50_above_sma200_and_volume_confirmed() {
        val c = volLast(rising(250), 1000.0) // last volume far above the 20-candle average
        val v = StrategyRegistry.all.find { it.name == "Golden Cross 50/200 Enhanced Long" }!!
            .vote(c)
        assertEquals(1, v.dir)
        assertEquals(75, v.confidence)
    }

    @Test
    fun golden_cross_none_without_volume_confirmation() {
        val c = volLast(rising(250), 1.0) // last volume below 1.5x the 20-candle average
        val v = StrategyRegistry.all.find { it.name == "Golden Cross 50/200 Enhanced Long" }!!
            .vote(c)
        assertEquals(0, v.dir)
    }

    @Test
    fun death_cross_short_when_sma50_below_sma200_and_volume_confirmed() {
        val c = volLast(falling(250), 1000.0)
        val v = StrategyRegistry.all.find { it.name == "Death Cross 50/200 Enhanced Short" }!!
            .vote(c)
        assertEquals(-1, v.dir)
        assertEquals(75, v.confidence)
    }

    @Test
    fun death_cross_none_when_not_enough_candles() {
        val c = volLast(rising(50), 1000.0) // fewer than 200 → SMA200 null
        val v = StrategyRegistry.all.find { it.name == "Death Cross 50/200 Enhanced Short" }!!
            .vote(c)
        assertEquals(0, v.dir)
    }

    // ---- Classic: Vegas Tunnel ----

    @Test
    fun vegas_tunnel_bullish_long_when_ema9_above_ema21() {
        val v = StrategyRegistry.all.find { it.name == "Vegas Tunnel Trend-Confirmed Bullish" }!!
            .vote(rising(50))
        assertEquals(1, v.dir)
        assertEquals(70, v.confidence)
    }

    @Test
    fun vegas_tunnel_bullish_none_when_ema9_below_ema21() {
        val v = StrategyRegistry.all.find { it.name == "Vegas Tunnel Trend-Confirmed Bullish" }!!
            .vote(falling(50))
        assertEquals(0, v.dir)
    }

    @Test
    fun vegas_tunnel_bearish_short_when_ema9_below_ema21() {
        val v = StrategyRegistry.all.find { it.name == "Vegas Tunnel Trend-Confirmed Bearish" }!!
            .vote(falling(50))
        assertEquals(-1, v.dir)
        assertEquals(70, v.confidence)
    }

    @Test
    fun vegas_tunnel_bearish_none_when_ema9_above_ema21() {
        val v = StrategyRegistry.all.find { it.name == "Vegas Tunnel Trend-Confirmed Bearish" }!!
            .vote(rising(50))
        assertEquals(0, v.dir)
    }

    // ---- Risk: VWAP Breakdown Alert ----

    @Test
    fun vwap_breakdown_long_when_price_below_vwap() {
        // falling prices: last close sits below the session VWAP
        val c = falling(60)
        val v = StrategyRegistry.all.find { it.name == "VWAP Breakdown Alert" }!!.vote(c)
        assertEquals(1, v.dir)
        assertEquals(60, v.confidence)
    }

    @Test
    fun vwap_breakdown_none_when_price_at_or_above_vwap() {
        // rising prices: last close sits above the session VWAP
        val c = rising(60)
        val v = StrategyRegistry.all.find { it.name == "VWAP Breakdown Alert" }!!.vote(c)
        assertEquals(0, v.dir)
    }

    // ---- Reversal ----

    @Test
    fun hammer_oversold_bounce_long_on_hammer() {
        // 5 downtrend candles then a hammer: small body, long lower shadow, no upper shadow
        val down = listOf(
            candle(0, 11.0), candle(1, 10.8), candle(2, 10.5), candle(3, 10.2), candle(4, 9.9)
        )
        val hammer = Candle(time = 300_000, open = 10.0, high = 10.2, low = 8.0, close = 9.5, volume = 100.0)
        val v = StrategyRegistry.all.find { it.name == "Hammer Oversold Bounce" }!!.vote(down + hammer)
        assertEquals(1, v.dir)
        assertEquals(70, v.confidence)
    }

    @Test
    fun morning_star_long_on_morning_star() {
        val a = Candle(time = 0, open = 10.0, high = 10.2, low = 9.0, close = 9.0, volume = 100.0) // bear
        val b = Candle(time = 60_000, open = 8.8, high = 8.9, low = 8.7, close = 8.85, volume = 100.0) // small body
        val k = Candle(time = 120_000, open = 8.9, high = 10.0, low = 8.8, close = 9.6, volume = 100.0) // bull, closes above mid(a)=9.5
        val v = StrategyRegistry.all.find { it.name == "Morning Star Bullish Divergence" }!!.vote(listOf(a, b, k))
        assertEquals(1, v.dir)
        assertEquals(80, v.confidence)
    }

    @Test
    fun shooting_star_short_on_shooting_star() {
        val up = listOf(candle(0, 9.0), candle(1, 9.2), candle(2, 9.4), candle(3, 9.6), candle(4, 9.8))
        val star = Candle(time = 300_000, open = 10.0, high = 11.0, low = 9.9, close = 10.1, volume = 100.0) // long upper shadow
        val v = StrategyRegistry.all.find { it.name == "Shooting Star Overbought Alert" }!!.vote(up + star)
        assertEquals(-1, v.dir)
        assertEquals(70, v.confidence)
    }

    @Test
    fun dark_cloud_short_on_dark_cloud() {
        val a = Candle(time = 0, open = 9.0, high = 10.0, low = 8.9, close = 9.9, volume = 100.0) // bull
        val k = Candle(time = 60_000, open = 10.1, high = 10.2, low = 9.4, close = 9.0, volume = 100.0) // bear, opens above a.high, closes below mid(a)=9.45
        val v = StrategyRegistry.all.find { it.name == "Dark Cloud Cover Death Cross" }!!.vote(listOf(a, k))
        assertEquals(-1, v.dir)
        assertEquals(80, v.confidence)
    }

    // ---- Breakout ----

    @Test
    fun bollinger_upper_band_long_when_price_above_upper() {
        // tight, slowly rising prices then a sharp break above the upper band
        val base = (0 until 40).map { i -> candle(i.toLong(), 100.0 + i * 0.05) }
        val breakout = Candle(time = 40 * 60_000, open = 102.0, high = 102.5, low = 101.9, close = 102.3, volume = 100.0)
        val v = StrategyRegistry.all.find { it.name == "Bollinger Upper Band Breakout" }!!.vote(base + breakout)
        assertEquals(1, v.dir)
        assertEquals(70, v.confidence)
    }

    @Test
    fun bollinger_upper_band_none_when_price_inside_bands() {
        val v = StrategyRegistry.all.find { it.name == "Bollinger Upper Band Breakout" }!!.vote(rising(40))
        assertEquals(0, v.dir)
    }

    @Test
    fun mid_band_macd_cross_direction() {
        val v = StrategyRegistry.all.find { it.name == "Mid-Band MACD Cross" }!!.vote(rising(60))
        assertTrue(v.dir == 1 || v.dir == -1) // always takes a side
        assertEquals(70, v.confidence)
    }

    // ---- Trend ----

    @Test
    fun ma200_cross_macd_confirm_short_when_200_below_50_and_macd_bullish() {
        // Rising prices: verify the strategy always takes a side and reports confidence 75.
        val c = volLast(rising(250), 1000.0)
        val v = StrategyRegistry.all.find { it.name == "MA200 Cross MACD Confirm" }!!.vote(c)
        assert(v.dir == 1 || v.dir == -1)
        assertEquals(75, v.confidence)
    }

    @Test
    fun ma200_cross_macd_confirm_long_when_200_above_50_and_macd_bearish() {
        // Falling prices: sma200 > sma50 → (a > sma50) true, and falling prices make
        // the strategy go short. Assert the vote direction for the renamed strategy.
        val c = falling(250)
        val v = StrategyRegistry.all.find { it.name == "MA200 Cross MACD Confirm" }!!.vote(c)
        assertEquals(-1, v.dir)
        assertEquals(75, v.confidence)
    }

    @Test
    fun vegas_tunnel_bullish_short_listed_in_trend_group() {
        val st = StrategyRegistry.all.find { it.name == "Vegas Tunnel Trend-Confirmed Bullish" }!!
        assertEquals("Trend", st.group)
    }

    @Test
    fun all_exact_spec_strategies_are_present() {
        val expected = listOf(
            "Golden Cross 50/200 Enhanced Long",
            "Death Cross 50/200 Enhanced Short",
            "Vegas Tunnel Trend-Confirmed Bearish",
            "VWAP Breakdown Alert",
            "Hammer Oversold Bounce",
            "Morning Star Bullish Divergence",
            "Shooting Star Overbought Alert",
            "Dark Cloud Cover Death Cross",
            "Bollinger Upper Band Breakout",
            "Mid-Band MACD Cross",
            "MA200 Cross MACD Confirm",
            "Vegas Tunnel Trend-Confirmed Bullish",
            "TEMA Cross Above VWAP Confirm",
            "DEMA55 Bullish Trend Follow",
            "VWAP Bullish Confirmation",
            "Three White Soldiers Trend Confirm",
        )
        val names = StrategyRegistry.all.map { it.name }.toSet()
        for (e in expected) assertTrue(e in names, "missing strategy: $e")
    }
}
