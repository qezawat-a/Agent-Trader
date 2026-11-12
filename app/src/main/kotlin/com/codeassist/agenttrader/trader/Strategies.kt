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

private fun closes(c: List<Candle>) = c.map { it.close }

private class Lambda(
    override val name: String,
    override val group: String,
    private val f: (List<Candle>) -> Vote,
) : Strategy {
    override fun vote(c: List<Candle>): Vote = f(c)
}

private fun s(name: String, group: String, f: (List<Candle>) -> Vote): Strategy = Lambda(name, group, f)

/** Registry of all strategies, grouped as: Classic, Risk, Reversal, Breakout, Trend, Additional. */
object StrategyRegistry {

    val all: List<Strategy> = listOf(
        // Classic
        s("EMA 9/21 cross", "Classic") { c ->
            val cl = closes(c)
            val f = I.emaSeries(cl, 9); val sl = I.emaSeries(cl, 21)
            if (cl.size < 22) NONE
            else if (f.last() > sl.last()) Vote(1, 70) else Vote(-1, 70)
        },
        s("SMA 50/200 trend", "Classic") { c ->
            val cl = closes(c)
            val a = I.sma(cl, 50); val b = I.sma(cl, 200)
            if (a == null || b == null) NONE else if (a > b) Vote(1, 65) else Vote(-1, 65)
        },
        s("MACD cross", "Classic") { c ->
            val m = I.macd(closes(c)) ?: return@s NONE
            if (m.line > m.signal) Vote(1, 70) else Vote(-1, 70)
        },
        s("RSI reversion", "Classic") { c ->
            val r = I.rsi(closes(c)) ?: return@s NONE
            when {
                r <= 30 -> Vote(1, 75)
                r >= 70 -> Vote(-1, 75)
                else -> NONE
            }
        },
        s("Bollinger reversion", "Classic") { c ->
            val cl = closes(c); val b = I.bollinger(cl) ?: return@s NONE
            val p = cl.last()
            when {
                p <= b.lower -> Vote(1, 70)
                p >= b.upper -> Vote(-1, 70)
                else -> NONE
            }
        },
        // Classic — exact spec: Golden Cross / Death Cross with volume confirmation,
        // Vegas Tunnel trend confirmation
        s("Golden Cross 50/200 Enhanced Long", "Classic") { c ->
            val a = I.sma(closes(c), 50); val b = I.sma(closes(c), 200)
            if (a == null || b == null) NONE
            else {
                // volume confirmation: last candle volume must be above the 20-candle avg
                val avg = if (c.size < 21) 0.0 else c.dropLast(1).takeLast(20).map { it.volume }.average()
                if (avg > 0 && c.last().volume < 1.5 * avg) NONE
                else if (a > b) Vote(1, 75) else Vote(-1, 75)
            }
        },
        s("Death Cross 50/200 Enhanced Short", "Classic") { c ->
            val a = I.sma(closes(c), 50); val b = I.sma(closes(c), 200)
            if (a == null || b == null) NONE
            else {
                val avg = if (c.size < 21) 0.0 else c.dropLast(1).takeLast(20).map { it.volume }.average()
                if (avg > 0 && c.last().volume < 1.5 * avg) NONE
                else if (a < b) Vote(-1, 75) else Vote(1, 75)
            }
        },
        s("Vegas Tunnel Trend-Confirmed Bearish", "Classic") { c ->
            val f = I.emaSeries(closes(c), 9); val sl = I.emaSeries(closes(c), 21)
            if (c.size < 22) NONE
            else if (f.last() < sl.last()) Vote(-1, 70) else NONE
        },
        // Risk
        s("ATR volatility filter", "Risk") { c ->
            val atr = I.atr(c) ?: return@s NONE
            val p = c.last().close
            // Calm market: lean with the trend; very volatile market: no view.
            if (atr / p > 0.05) NONE else Vote(if (c.last().close >= c[c.size - 2].close) 1 else -1, 60)
        },
        s("Volume confirmation", "Risk") { c ->
            if (c.size < 21) return@s NONE
            val avg = c.dropLast(1).takeLast(20).map { it.volume }.average()
            val last = c.last()
            if (avg == 0.0 || last.volume < 1.5 * avg) NONE
            else Vote(if (last.close >= last.open) 1 else -1, 65)
        },
        s("VWAP deviation", "Risk") { c ->
            val v = I.vwap(c) ?: return@s NONE
            val p = c.last().close
            when {
                p < v * 0.995 -> Vote(1, 60)
                p > v * 1.005 -> Vote(-1, 60)
                else -> NONE
            }
        },
        // Risk — exact spec
        s("VWAP Breakdown Alert", "Risk") { c ->
            val v = I.vwap(c) ?: return@s NONE
            val p = c.last().close
            if (p < v * 0.995) Vote(1, 60) else NONE
        },
        // Reversal
        s("Hammer", "Reversal") { c -> if (CandlePatterns.hammer(c)) Vote(1, 70) else NONE },
        s("Shooting star", "Reversal") { c -> if (CandlePatterns.shootingStar(c)) Vote(-1, 70) else NONE },
        s("Morning star", "Reversal") { c -> if (CandlePatterns.morningStar(c)) Vote(1, 80) else NONE },
        s("Dark cloud cover", "Reversal") { c -> if (CandlePatterns.darkCloud(c)) Vote(-1, 80) else NONE },
        s("Three soldiers / crows", "Reversal") { c ->
            when {
                CandlePatterns.threeWhite(c) -> Vote(1, 75)
                CandlePatterns.threeBlack(c) -> Vote(-1, 75)
                else -> NONE
            }
        },
        // Reversal — exact spec
        s("Hammer Oversold Bounce", "Reversal") { c -> if (CandlePatterns.hammer(c)) Vote(1, 70) else NONE },
        s("Morning Star Bullish Divergence", "Reversal") { c -> if (CandlePatterns.morningStar(c)) Vote(1, 80) else NONE },
        s("Shooting Star Overbought Alert", "Reversal") { c -> if (CandlePatterns.shootingStar(c)) Vote(-1, 70) else NONE },
        s("Dark Cloud Cover Death Cross", "Reversal") { c -> if (CandlePatterns.darkCloud(c)) Vote(-1, 80) else NONE },
        // Breakout
        s("Donchian 20 breakout", "Breakout") { c ->
            if (c.size < 21) return@s NONE
            val prev = c.dropLast(1).takeLast(20)
            val hi = prev.maxOf { it.high }; val lo = prev.minOf { it.low }
            val p = c.last().close
            when {
                p > hi -> Vote(1, 75)
                p < lo -> Vote(-1, 75)
                else -> NONE
            }
        },
        s("Bollinger squeeze breakout", "Breakout") { c ->
            val cl = closes(c)
            val b = I.bollinger(cl) ?: return@s NONE
            val p = cl.last()
            // Narrow bands (width below 4%) followed by a close outside the band.
            if (b.width > 0.04) NONE else if (p > b.upper) Vote(1, 70) else if (p < b.lower) Vote(-1, 70) else NONE
        },
        s("Volume breakout", "Breakout") { c ->
            if (c.size < 21) return@s NONE
            val avg = c.dropLast(1).takeLast(20).map { it.volume }.average()
            val last = c.last()
            val prevHigh = c.dropLast(1).takeLast(10).maxOf { it.high }
            val prevLow = c.dropLast(1).takeLast(10).minOf { it.low }
            when {
                avg > 0 && last.volume > 2 * avg && last.close > prevHigh -> Vote(1, 70)
                avg > 0 && last.volume > 2 * avg && last.close < prevLow -> Vote(-1, 70)
                else -> NONE
            }
        },
        // Breakout — exact spec
        s("Bollinger Upper Band Breakout", "Breakout") { c ->
            val cl = closes(c); val b = I.bollinger(cl) ?: return@s NONE
            val p = cl.last()
            if (p > b.upper) Vote(1, 70) else NONE
        },
        s("Mid-Band MACD Cross", "Breakout") { c ->
            val m = I.macd(closes(c)) ?: return@s NONE
            val b = I.bollinger(closes(c)) ?: return@s NONE
            if (m.line > m.signal && m.line > b?.mid ?: 0.0) Vote(1, 70) else Vote(-1, 70)
        },
        // Trend
        s("TEMA 20 trend", "Trend") { c ->
            val cl = closes(c); val t = I.tema(cl, 20) ?: return@s NONE
            if (cl.last() > t) Vote(1, 65) else Vote(-1, 65)
        },
        s("DEMA 20 trend", "Trend") { c ->
            val cl = closes(c); val d = I.dema(cl, 20) ?: return@s NONE
            if (cl.last() > d) Vote(1, 65) else Vote(-1, 65)
        },
        s("MACD histogram trend", "Trend") { c ->
            val m = I.macd(closes(c)) ?: return@s NONE
            if (m.hist > m.prevHist) Vote(1, 60) else Vote(-1, 60)
        },
        // Trend — exact spec
        s("MA200 Cross MACD Confirm", "Trend") { c ->
            val cl = closes(c)
            val a = I.sma(cl, 200) ?: return@s NONE
            val m = I.macd(cl) ?: return@s NONE
            val s50 = I.sma(cl, 50) ?: return@s NONE
            if (m.line > m.signal && s50 > a) Vote(1, 75) else Vote(-1, 75)
        },
        s("Vegas Tunnel Trend-Confirmed Bullish", "Trend") { c ->
            val f = I.emaSeries(closes(c), 9); val sl = I.emaSeries(closes(c), 21)
            if (c.size < 22) NONE
            else if (f.last() > sl.last()) Vote(1, 70) else NONE
        },
        s("TEMA Cross Above VWAP Confirm", "Trend") { c ->
            val t = I.tema(closes(c), 20) ?: return@s NONE
            val v = I.vwap(c) ?: return@s NONE
            val p = c.last().close
            if (t > p && p > v) Vote(1, 70) else Vote(-1, 70)
        },
        s("DEMA55 Bullish Trend Follow", "Trend") { c ->
            val d = I.dema(closes(c), 55) ?: return@s NONE
            val m = I.macd(closes(c)) ?: return@s NONE
            if (d > c.last().close && m.line > m.signal) Vote(1, 70) else Vote(-1, 70)
        },
        s("VWAP Bullish Confirmation", "Trend") { c ->
            val v = I.vwap(c) ?: return@s NONE
            val m = I.macd(closes(c)) ?: return@s NONE
            val p = c.last().close
            if (p > v && m.line > m.signal) Vote(1, 70) else Vote(-1, 70)
        },
        s("Three White Soldiers Trend Confirm", "Trend") { c ->
            if (!CandlePatterns.threeWhite(c)) NONE
            else {
                val r = I.rsi(closes(c)) ?: return@s NONE
                val e = I.emaSeries(closes(c), 20)
                if (r > 50 && e.last() > c[c.size - 2].close) Vote(1, 75) else Vote(1, 75)
            }
        },
        // Additional
        s("RSI momentum 50", "Additional") { c ->
            val r = I.rsi(closes(c)) ?: return@s NONE
            when {
                r > 55 -> Vote(1, 60)
                r < 45 -> Vote(-1, 60)
                else -> NONE
            }
        },
        s("EMA 50 slope", "Additional") { c ->
            val cl = closes(c)
            if (cl.size < 56) return@s NONE
            val e = I.emaSeries(cl, 50)
            if (e.last() > e[e.size - 6]) Vote(1, 60) else Vote(-1, 60)
        },
    )

    val groups: List<String> = all.map { it.group }.distinct()
}
