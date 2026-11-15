package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins every strategy's vote on a rising and on a falling series.
 *
 * The registry is the signal path: a refactor that silently flips a vote
 * changes when the engine opens a position. This test makes such a change
 * explicit — fixing a strategy (see REVIEW.md, open findings) means updating
 * the expected value on purpose.
 */
class StrategyRegistryFingerprintTest {

    private fun candle(index: Long, close: Double, volume: Double = 100.0): Candle =
        Candle(time = index * 60_000, open = close - 0.2, high = close + 0.3, low = close - 0.4, close = close, volume = volume)

    private fun rising(count: Int): List<Candle> =
        (0 until count).map { i -> candle(i.toLong(), 100.0 + i * 0.5) }

    private fun falling(count: Int): List<Candle> =
        (0 until count).map { i -> candle(i.toLong(), 100.0 - i * 0.5) }

    /** name, vote on the rising series, vote on the falling one. */
    private val expected: List<Triple<String, Vote, Vote>> = listOf(
        Triple("EMA 9/21 cross", Vote(1, 70), Vote(-1, 70)),
        Triple("SMA 50/200 trend", Vote(1, 65), Vote(-1, 65)),
        Triple("MACD cross", Vote(1, 70), Vote(-1, 70)),
        Triple("RSI reversion", Vote(-1, 75), Vote(1, 75)),
        Triple("Bollinger reversion", Vote(0, 0), Vote(0, 0)),
        Triple("Golden Cross 50/200 Enhanced Long", Vote(0, 0), Vote(0, 0)),
        Triple("Death Cross 50/200 Enhanced Short", Vote(0, 0), Vote(0, 0)),
        Triple("Vegas Tunnel Trend-Confirmed Bearish", Vote(0, 0), Vote(-1, 70)),
        Triple("ATR volatility filter", Vote(1, 60), Vote(-1, 60)),
        Triple("Volume confirmation", Vote(0, 0), Vote(0, 0)),
        Triple("VWAP deviation", Vote(-1, 60), Vote(1, 60)),
        Triple("VWAP Breakdown Alert", Vote(0, 0), Vote(1, 60)),
        Triple("Hammer", Vote(0, 0), Vote(0, 0)),
        Triple("Shooting star", Vote(0, 0), Vote(0, 0)),
        Triple("Morning star", Vote(0, 0), Vote(0, 0)),
        Triple("Dark cloud cover", Vote(0, 0), Vote(0, 0)),
        Triple("Three soldiers / crows", Vote(1, 75), Vote(0, 0)),
        Triple("Hammer Oversold Bounce", Vote(0, 0), Vote(0, 0)),
        Triple("Morning Star Bullish Divergence", Vote(0, 0), Vote(0, 0)),
        Triple("Shooting Star Overbought Alert", Vote(0, 0), Vote(0, 0)),
        Triple("Dark Cloud Cover Death Cross", Vote(0, 0), Vote(0, 0)),
        Triple("Donchian 20 breakout", Vote(1, 75), Vote(-1, 75)),
        Triple("Bollinger squeeze breakout", Vote(0, 0), Vote(0, 0)),
        Triple("Volume breakout", Vote(0, 0), Vote(0, 0)),
        Triple("Bollinger Upper Band Breakout", Vote(0, 0), Vote(0, 0)),
        Triple("Mid-Band MACD Cross", Vote(-1, 70), Vote(-1, 70)),
        Triple("TEMA 20 trend", Vote(-1, 65), Vote(1, 65)),
        Triple("DEMA 20 trend", Vote(1, 65), Vote(-1, 65)),
        Triple("MACD histogram trend", Vote(-1, 60), Vote(1, 60)),
        Triple("MA200 Cross MACD Confirm", Vote(1, 75), Vote(-1, 75)),
        Triple("Vegas Tunnel Trend-Confirmed Bullish", Vote(1, 70), Vote(0, 0)),
        Triple("TEMA Cross Above VWAP Confirm", Vote(1, 70), Vote(-1, 70)),
        Triple("DEMA55 Bullish Trend Follow", Vote(-1, 70), Vote(-1, 70)),
        Triple("VWAP Bullish Confirmation", Vote(1, 70), Vote(-1, 70)),
        Triple("Three White Soldiers Trend Confirm", Vote(1, 75), Vote(0, 0)),
        Triple("RSI momentum 50", Vote(1, 60), Vote(-1, 60)),
        Triple("EMA 50 slope", Vote(1, 60), Vote(-1, 60)),
    )

    @Test
    fun every_strategy_keeps_its_vote() {
        val up = rising(250)
        val down = falling(250)
        assertEquals(expected.size, StrategyRegistry.all.size)
        for ((name, upVote, downVote) in expected) {
            val strategy = StrategyRegistry.all.find { it.name == name }
            assertNotNull(strategy, "strategy '$name' is missing from the registry")
            assertEquals(upVote, strategy.vote(up), "uptrend vote of '$name' changed")
            assertEquals(downVote, strategy.vote(down), "downtrend vote of '$name' changed")
        }
    }
}
