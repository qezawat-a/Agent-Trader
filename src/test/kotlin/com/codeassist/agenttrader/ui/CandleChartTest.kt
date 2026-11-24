package com.codeassist.agenttrader.ui

import com.codeassist.agenttrader.bitunix.Candle
import com.codeassist.agenttrader.trader.BoardPosition
import com.codeassist.agenttrader.trader.BoardTp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandleChartTest {
    private fun candle(t: Long, low: Double, high: Double) = Candle(
        time = t,
        open = low + 1.0,
        high = high,
        low = low,
        close = high - 1.0,
        volume = 10.0,
    )

    @Test
    fun chart_window_keeps_the_newest_candles() {
        val all = (1..120).map { candle(it.toLong(), 10.0, 12.0) }
        val window = chartWindow(all, 80)
        assertEquals(80, window.size)
        assertEquals(41L, window.first().time)
        assertEquals(120L, window.last().time)
    }

    @Test
    fun chart_bounds_padding_keeps_wicks_inside() {
        val series = listOf(candle(1, 90.0, 110.0), candle(2, 95.0, 105.0))
        val (lo, hi) = chartBounds(series)
        assertTrue(lo < 90.0)
        assertTrue(hi > 110.0)
    }

    @Test
    fun flat_series_still_has_a_usable_range() {
        val (lo, hi) = chartBounds(listOf(candle(1, 100.0, 100.0)))
        assertTrue(hi > lo, "flat candles must not produce a zero-height chart")
    }

    @Test
    fun position_levels_are_included_in_chart_bounds() {
        val series = listOf(candle(1, 100.0, 110.0))
        val (lo, hi) = chartBounds(series, listOf(80.0, 130.0))
        assertTrue(lo < 80.0, "liquidation/entry levels below the candles must stay visible")
        assertTrue(hi > 130.0, "take-profit levels above the candles must stay visible")
    }

    @Test
    fun engine_position_becomes_chart_levels() {
        val position = BoardPosition(
            symbol = "BTCUSDT",
            side = "LONG",
            qty = 0.5,
            entry = 100.0,
            mark = 105.0,
            leverage = 5,
            margin = 10.0,
            pnl = 2.5,
            pnlPct = 25.0,
            liq = 80.0,
            stop = 95.0,
            tps = listOf(BoardTp("TP1", 120.0, "25%", false)),
            paper = false,
        )

        val levels = chartPositionLevels(position)
        assertEquals(
            listOf(
                ChartLevelKind.MARK,
                ChartLevelKind.ENTRY,
                ChartLevelKind.STOP,
                ChartLevelKind.LIQUIDATION,
                ChartLevelKind.TAKE_PROFIT,
            ),
            levels.map { it.kind },
        )
        assertEquals(listOf(105.0, 100.0, 95.0, 80.0, 120.0), levels.map { it.price })
    }

    @Test
    fun missing_position_levels_are_not_invented() {
        val position = BoardPosition(
            symbol = "ETHUSDT",
            side = "SHORT",
            qty = 1.0,
            entry = 200.0,
            mark = 0.0,
            leverage = 3,
            margin = 60.0,
            pnl = 0.0,
            pnlPct = 0.0,
            liq = null,
            stop = null,
            tps = emptyList(),
            paper = false,
        )

        assertEquals(listOf(ChartLevelKind.ENTRY), chartPositionLevels(position).map { it.kind })
    }
}
