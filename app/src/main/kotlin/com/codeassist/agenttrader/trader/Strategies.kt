package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import com.codeassist.agenttrader.trader.Indicators as I

/** One strategy's view of the market: direction (1 long, -1 short, 0 no view) and confidence 0..100. */
data class Vote(val dir: Int, val confidence: Int)

interface Strategy {
    val name: String
    val group: String
    fun vote(c: List<Candle>): Vote
}

private val NONE = Vote(0, 0)

private fun closes(candles: List<Candle>): List<Double> = candles.map { it.close }

private class Lambda(
    override val name: String,
    override val group: String,
    private val voteOf: (List<Candle>) -> Vote,
) : Strategy {
    override fun vote(c: List<Candle>): Vote = voteOf(c)
}

private fun s(name: String, group: String, voteOf: (List<Candle>) -> Vote): Strategy =
    Lambda(name, group, voteOf)

/** Registry of all strategies, grouped as: Classic, Risk, Reversal, Breakout, Trend, Additional. */
object StrategyRegistry {

    val all: List<Strategy> = listOf(
        // Classic
        s("EMA 9/21 cross", "Classic") { candles ->
            val closePrices = closes(candles)
            val fastEma = I.emaSeries(closePrices, 9)
            val slowEma = I.emaSeries(closePrices, 21)
            if (closePrices.size < 22) NONE
            else if (fastEma.last() > slowEma.last()) Vote(1, 70) else Vote(-1, 70)
        },
        s("SMA 50/200 trend", "Classic") { candles ->
            val closePrices = closes(candles)
            val sma50 = I.sma(closePrices, 50)
            val sma200 = I.sma(closePrices, 200)
            if (sma50 == null || sma200 == null) NONE else if (sma50 > sma200) Vote(1, 65) else Vote(-1, 65)
        },
        s("MACD cross", "Classic") { candles ->
            val macd = I.macd(closes(candles)) ?: return@s NONE
            if (macd.line > macd.signal) Vote(1, 70) else Vote(-1, 70)
        },
        s("RSI reversion", "Classic") { candles ->
            val rsi = I.rsi(closes(candles)) ?: return@s NONE
            when {
                rsi <= 30 -> Vote(1, 75)
                rsi >= 70 -> Vote(-1, 75)
                else -> NONE
            }
        },
        s("Bollinger reversion", "Classic") { candles ->
            val closePrices = closes(candles)
            val bands = I.bollinger(closePrices) ?: return@s NONE
            val lastClose = closePrices.last()
            when {
                lastClose <= bands.lower -> Vote(1, 70)
                lastClose >= bands.upper -> Vote(-1, 70)
                else -> NONE
            }
        },
        // Classic — exact spec: Golden Cross / Death Cross with volume confirmation,
        // Vegas Tunnel trend confirmation
        s("Golden Cross 50/200 Enhanced Long", "Classic") { candles ->
            val sma50 = I.sma(closes(candles), 50)
            val sma200 = I.sma(closes(candles), 200)
            if (sma50 == null || sma200 == null) NONE
            else {
                // volume confirmation: last candle volume must be above the 20-candle avg
                val avg = if (candles.size < 21) 0.0 else candles.dropLast(1).takeLast(20).map { it.volume }.average()
                if (avg > 0 && candles.last().volume < 1.5 * avg) NONE
                else if (sma50 > sma200) Vote(1, 75) else Vote(-1, 75)
            }
        },
        s("Death Cross 50/200 Enhanced Short", "Classic") { candles ->
            val sma50 = I.sma(closes(candles), 50)
            val sma200 = I.sma(closes(candles), 200)
            if (sma50 == null || sma200 == null) NONE
            else {
                val avg = if (candles.size < 21) 0.0 else candles.dropLast(1).takeLast(20).map { it.volume }.average()
                if (avg > 0 && candles.last().volume < 1.5 * avg) NONE
                else if (sma50 < sma200) Vote(-1, 75) else Vote(1, 75)
            }
        },
        s("Vegas Tunnel Trend-Confirmed Bearish", "Classic") { candles ->
            val fastEma = I.emaSeries(closes(candles), 9)
            val slowEma = I.emaSeries(closes(candles), 21)
            if (candles.size < 22) NONE
            else if (fastEma.last() < slowEma.last()) Vote(-1, 70) else NONE
        },
        // Risk
        s("ATR volatility filter", "Risk") { candles ->
            val atr = I.atr(candles) ?: return@s NONE
            val lastClose = candles.last().close
            // Calm market: lean with the trend; very volatile market: no view.
            if (atr / lastClose > 0.05) NONE
            else Vote(if (candles.last().close >= candles[candles.size - 2].close) 1 else -1, 60)
        },
        s("Volume confirmation", "Risk") { candles ->
            if (candles.size < 21) return@s NONE
            val avg = candles.dropLast(1).takeLast(20).map { it.volume }.average()
            val last = candles.last()
            if (avg == 0.0 || last.volume < 1.5 * avg) NONE
            else Vote(if (last.close >= last.open) 1 else -1, 65)
        },
        s("VWAP deviation", "Risk") { candles ->
            val vwap = I.vwap(candles) ?: return@s NONE
            val lastClose = candles.last().close
            when {
                lastClose < vwap * 0.995 -> Vote(1, 60)
                lastClose > vwap * 1.005 -> Vote(-1, 60)
                else -> NONE
            }
        },
        // Risk — exact spec
        s("VWAP Breakdown Alert", "Risk") { candles ->
            val vwap = I.vwap(candles) ?: return@s NONE
            val lastClose = candles.last().close
            if (lastClose < vwap * 0.995) Vote(1, 60) else NONE
        },
        // Reversal
        s("Hammer", "Reversal") { candles -> if (CandlePatterns.hammer(candles)) Vote(1, 70) else NONE },
        s("Shooting star", "Reversal") { candles ->
            if (CandlePatterns.shootingStar(candles)) Vote(-1, 70) else NONE
        },
        s("Morning star", "Reversal") { candles ->
            if (CandlePatterns.morningStar(candles)) Vote(1, 80) else NONE
        },
        s("Dark cloud cover", "Reversal") { candles ->
            if (CandlePatterns.darkCloud(candles)) Vote(-1, 80) else NONE
        },
        s("Three soldiers / crows", "Reversal") { candles ->
            when {
                CandlePatterns.threeWhite(candles) -> Vote(1, 75)
                CandlePatterns.threeBlack(candles) -> Vote(-1, 75)
                else -> NONE
            }
        },
        // Reversal — exact spec
        s("Hammer Oversold Bounce", "Reversal") { candles ->
            if (CandlePatterns.hammer(candles)) Vote(1, 70) else NONE
        },
        s("Morning Star Bullish Divergence", "Reversal") { candles ->
            if (CandlePatterns.morningStar(candles)) Vote(1, 80) else NONE
        },
        s("Shooting Star Overbought Alert", "Reversal") { candles ->
            if (CandlePatterns.shootingStar(candles)) Vote(-1, 70) else NONE
        },
        s("Dark Cloud Cover Death Cross", "Reversal") { candles ->
            if (CandlePatterns.darkCloud(candles)) Vote(-1, 80) else NONE
        },
        // Breakout
        s("Donchian 20 breakout", "Breakout") { candles ->
            if (candles.size < 21) return@s NONE
            val previous = candles.dropLast(1).takeLast(20)
            val highest = previous.maxOf { it.high }
            val lowest = previous.minOf { it.low }
            val lastClose = candles.last().close
            when {
                lastClose > highest -> Vote(1, 75)
                lastClose < lowest -> Vote(-1, 75)
                else -> NONE
            }
        },
        s("Bollinger squeeze breakout", "Breakout") { candles ->
            val closePrices = closes(candles)
            val bands = I.bollinger(closePrices) ?: return@s NONE
            val lastClose = closePrices.last()
            // Narrow bands (width below 4%) followed by a close outside the band.
            if (bands.width > 0.04) NONE
            else if (lastClose > bands.upper) Vote(1, 70)
            else if (lastClose < bands.lower) Vote(-1, 70)
            else NONE
        },
        s("Volume breakout", "Breakout") { candles ->
            if (candles.size < 21) return@s NONE
            val avg = candles.dropLast(1).takeLast(20).map { it.volume }.average()
            val last = candles.last()
            val prevHigh = candles.dropLast(1).takeLast(10).maxOf { it.high }
            val prevLow = candles.dropLast(1).takeLast(10).minOf { it.low }
            when {
                avg > 0 && last.volume > 2 * avg && last.close > prevHigh -> Vote(1, 70)
                avg > 0 && last.volume > 2 * avg && last.close < prevLow -> Vote(-1, 70)
                else -> NONE
            }
        },
        // Breakout — exact spec
        s("Bollinger Upper Band Breakout", "Breakout") { candles ->
            val closePrices = closes(candles)
            val bands = I.bollinger(closePrices) ?: return@s NONE
            val lastClose = closePrices.last()
            if (lastClose > bands.upper) Vote(1, 70) else NONE
        },
        s("Mid-Band MACD Cross", "Breakout") { candles ->
            val macd = I.macd(closes(candles)) ?: return@s NONE
            val bands = I.bollinger(closes(candles)) ?: return@s NONE
            if (macd.line > macd.signal && macd.line > bands.mid) Vote(1, 70) else Vote(-1, 70)
        },
        // Trend
        s("TEMA 20 trend", "Trend") { candles ->
            val closePrices = closes(candles)
            val tema = I.tema(closePrices, 20) ?: return@s NONE
            if (closePrices.last() > tema) Vote(1, 65) else Vote(-1, 65)
        },
        s("DEMA 20 trend", "Trend") { candles ->
            val closePrices = closes(candles)
            val dema = I.dema(closePrices, 20) ?: return@s NONE
            if (closePrices.last() > dema) Vote(1, 65) else Vote(-1, 65)
        },
        s("MACD histogram trend", "Trend") { candles ->
            val macd = I.macd(closes(candles)) ?: return@s NONE
            if (macd.hist > macd.prevHist) Vote(1, 60) else Vote(-1, 60)
        },
        // Trend — exact spec
        s("MA200 Cross MACD Confirm", "Trend") { candles ->
            val closePrices = closes(candles)
            val sma200 = I.sma(closePrices, 200) ?: return@s NONE
            val macd = I.macd(closePrices) ?: return@s NONE
            val sma50 = I.sma(closePrices, 50) ?: return@s NONE
            if (macd.line > macd.signal && sma50 > sma200) Vote(1, 75) else Vote(-1, 75)
        },
        s("Vegas Tunnel Trend-Confirmed Bullish", "Trend") { candles ->
            val fastEma = I.emaSeries(closes(candles), 9)
            val slowEma = I.emaSeries(closes(candles), 21)
            if (candles.size < 22) NONE
            else if (fastEma.last() > slowEma.last()) Vote(1, 70) else NONE
        },
        s("TEMA Cross Above VWAP Confirm", "Trend") { candles ->
            val tema = I.tema(closes(candles), 20) ?: return@s NONE
            val vwap = I.vwap(candles) ?: return@s NONE
            val lastClose = candles.last().close
            if (tema > lastClose && lastClose > vwap) Vote(1, 70) else Vote(-1, 70)
        },
        s("DEMA55 Bullish Trend Follow", "Trend") { candles ->
            val dema = I.dema(closes(candles), 55) ?: return@s NONE
            val macd = I.macd(closes(candles)) ?: return@s NONE
            if (dema > candles.last().close && macd.line > macd.signal) Vote(1, 70) else Vote(-1, 70)
        },
        s("VWAP Bullish Confirmation", "Trend") { candles ->
            val vwap = I.vwap(candles) ?: return@s NONE
            val macd = I.macd(closes(candles)) ?: return@s NONE
            val lastClose = candles.last().close
            if (lastClose > vwap && macd.line > macd.signal) Vote(1, 70) else Vote(-1, 70)
        },
        s("Three White Soldiers Trend Confirm", "Trend") { candles ->
            // The RSI/EMA confirmation is not binding yet: both branches vote the
            // same way, so the pattern alone decides. The RSI guard is kept —
            // without enough history the strategy abstains. See REVIEW.md
            // (open finding: the confirmation should gate the vote).
            if (!CandlePatterns.threeWhite(candles)) NONE
            else if (I.rsi(closes(candles)) == null) NONE
            else Vote(1, 75)
        },
        // Additional
        s("RSI momentum 50", "Additional") { candles ->
            val rsi = I.rsi(closes(candles)) ?: return@s NONE
            when {
                rsi > 55 -> Vote(1, 60)
                rsi < 45 -> Vote(-1, 60)
                else -> NONE
            }
        },
        s("EMA 50 slope", "Additional") { candles ->
            val closePrices = closes(candles)
            if (closePrices.size < 56) return@s NONE
            val ema = I.emaSeries(closePrices, 50)
            if (ema.last() > ema[ema.size - 6]) Vote(1, 60) else Vote(-1, 60)
        },
    )

    val groups: List<String> = all.map { it.group }.distinct()
}
