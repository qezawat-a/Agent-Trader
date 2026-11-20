package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import com.codeassist.agenttrader.trader.Indicators as I
import kotlin.math.abs

/** One strategy's view of the market: direction (1 long, -1 short, 0 no view) and confidence 0..100. */
data class Vote(val dir: Int, val confidence: Int)

interface Strategy {
    val name: String
    val group: String

    /**
     * Group of origin: the market signal this strategy actually reads.
     *
     * Several strategies in the registry are the SAME signal under a different
     * name (a hammer and a "hammer oversold bounce", an EMA cross and a
     * "Vegas tunnel" cross, ...). When such duplicates all vote, the
     * "N strategies agree" gate counted ONE market view three times.
     *
     * The gate therefore counts DISTINCT origins, so every strategy that
     * shares an origin shares one vote toward the gate. The default is the
     * strategy's own name: a strategy that reads a signal nobody else reads
     * is its own origin.
     */
    val origin: String get() = name

    fun vote(c: List<Candle>): Vote
}

private val NONE = Vote(0, 0)

private fun closes(candles: List<Candle>): List<Double> = candles.map { it.close }

/**
 * Relative tolerance for a "decisive" comparison.
 *
 * Two indicators that are mathematically equal on a clean trend (price and its
 * TEMA, a MACD histogram and its previous value) differ only by floating-point
 * noise, so a plain ">" decides the side by rounding error. A difference below
 * this share of the price (0.0001%) is a TIE, not a signal: the strategy
 * abstains instead of picking a side.
 */
private const val DECISIVE_EPS = 1e-6

/** True when [diff] is a real difference on the price scale, not rounding noise. */
private fun decisive(diff: Double, price: Double): Boolean = abs(diff) > DECISIVE_EPS * abs(price)

private class Lambda(
    override val name: String,
    override val group: String,
    override val origin: String,
    private val voteOf: (List<Candle>) -> Vote,
) : Strategy {
    override fun vote(c: List<Candle>): Vote = voteOf(c)
}

private fun s(name: String, group: String, origin: String = name, voteOf: (List<Candle>) -> Vote): Strategy =
    Lambda(name, group, origin, voteOf)

// Groups of origin: every strategy listed under one constant reads the SAME
// market signal, so the gate counts it once no matter how many names it has.
private const val ORIGIN_EMA_9_21 = "ema9_21"
private const val ORIGIN_HAMMER = "hammer"
private const val ORIGIN_SHOOTING_STAR = "shooting_star"
private const val ORIGIN_MORNING_STAR = "morning_star"
private const val ORIGIN_DARK_CLOUD = "dark_cloud"
private const val ORIGIN_VWAP = "vwap_deviation"
private const val ORIGIN_SMA_50_200 = "sma50_200"

/** Registry of all strategies, grouped as: Classic, Risk, Reversal, Breakout, Trend, Additional. */
object StrategyRegistry {

    val all: List<Strategy> = listOf(
        // Classic
        s("EMA 9/21 cross", "Classic", ORIGIN_EMA_9_21) { candles ->
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
        s("Golden Cross 50/200 Enhanced Long", "Classic", ORIGIN_SMA_50_200) { candles ->
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
        s("Death Cross 50/200 Enhanced Short", "Classic", ORIGIN_SMA_50_200) { candles ->
            val sma50 = I.sma(closes(candles), 50)
            val sma200 = I.sma(closes(candles), 200)
            if (sma50 == null || sma200 == null) NONE
            else {
                val avg = if (candles.size < 21) 0.0 else candles.dropLast(1).takeLast(20).map { it.volume }.average()
                if (avg > 0 && candles.last().volume < 1.5 * avg) NONE
                else if (sma50 < sma200) Vote(-1, 75) else Vote(1, 75)
            }
        },
        s("Vegas Tunnel Trend-Confirmed Bearish", "Classic", ORIGIN_EMA_9_21) { candles ->
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
        s("VWAP deviation", "Risk", ORIGIN_VWAP) { candles ->
            val vwap = I.vwap(candles) ?: return@s NONE
            val lastClose = candles.last().close
            when {
                lastClose < vwap * 0.995 -> Vote(1, 60)
                lastClose > vwap * 1.005 -> Vote(-1, 60)
                else -> NONE
            }
        },
        // Risk — exact spec
        s("VWAP Breakdown Alert", "Risk", ORIGIN_VWAP) { candles ->
            val vwap = I.vwap(candles) ?: return@s NONE
            val lastClose = candles.last().close
            if (lastClose < vwap * 0.995) Vote(1, 60) else NONE
        },
        // Reversal
        s("Hammer", "Reversal", ORIGIN_HAMMER) { candles -> if (CandlePatterns.hammer(candles)) Vote(1, 70) else NONE },
        s("Shooting star", "Reversal", ORIGIN_SHOOTING_STAR) { candles ->
            if (CandlePatterns.shootingStar(candles)) Vote(-1, 70) else NONE
        },
        s("Morning star", "Reversal", ORIGIN_MORNING_STAR) { candles ->
            if (CandlePatterns.morningStar(candles)) Vote(1, 80) else NONE
        },
        s("Dark cloud cover", "Reversal", ORIGIN_DARK_CLOUD) { candles ->
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
        s("Hammer Oversold Bounce", "Reversal", ORIGIN_HAMMER) { candles ->
            if (CandlePatterns.hammer(candles)) Vote(1, 70) else NONE
        },
        s("Morning Star Bullish Divergence", "Reversal", ORIGIN_MORNING_STAR) { candles ->
            if (CandlePatterns.morningStar(candles)) Vote(1, 80) else NONE
        },
        s("Shooting Star Overbought Alert", "Reversal", ORIGIN_SHOOTING_STAR) { candles ->
            if (CandlePatterns.shootingStar(candles)) Vote(-1, 70) else NONE
        },
        s("Dark Cloud Cover Death Cross", "Reversal", ORIGIN_DARK_CLOUD) { candles ->
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
            val closePrices = closes(candles)
            val macd = I.macd(closePrices) ?: return@s NONE
            val bands = I.bollinger(closePrices) ?: return@s NONE
            val lastClose = closePrices.last()
            // OLD: "macd.line > macd.signal && macd.line > bands.mid" compared an
            // oscillator (a price DIFFERENCE) with a price LEVEL. The MACD line
            // is orders of magnitude smaller than the price, so the condition
            // was practically never true and the strategy voted SHORT on EVERY
            // market - a free bearish vote in the gate.
            // NEW: compare like with like - the MACD line with its signal, and
            // the close with the Bollinger mid-band. Both must point the same
            // way, and both must be decisive: at an exact crossover the sign is
            // floating-point noise, so the strategy abstains instead of guessing.
            val macdUp = macd.line - macd.signal
            val priceUp = lastClose - bands.mid
            // Both signals must point the SAME way: the MACD above its signal
            // line AND the price above the mid-band is a long; both below is a
            // short. When they disagree the strategy abstains - a long vote
            // with the price under the mid-band is the wrong side again.
            when {
                macdUp > 0 && priceUp > 0 &&
                    decisive(macdUp, lastClose) && decisive(priceUp, lastClose) -> Vote(1, 70)
                macdUp < 0 && priceUp < 0 &&
                    decisive(macdUp, lastClose) && decisive(priceUp, lastClose) -> Vote(-1, 70)
                else -> NONE
            }
        },
        // Trend
        s("TEMA 20 trend", "Trend") { candles ->
            val closePrices = closes(candles)
            val tema = I.tema(closePrices, 20) ?: return@s NONE
            val lastClose = closePrices.last()
            // OLD: "close > tema" decided the side. On a clean trend the price
            // and the (lagging) TEMA sit exactly on the crossover, so the sign
            // was decided by floating-point noise - the same series could vote
            // LONG or SHORT depending on the last bit of the rounding.
            // NEW: a difference below 0.0001% of the price is a TIE, not a
            // signal, so the strategy abstains near the crossover.
            val diff = lastClose - tema
            when {
                decisive(diff, lastClose) && diff > 0 -> Vote(1, 65)
                decisive(diff, lastClose) && diff < 0 -> Vote(-1, 65)
                else -> NONE
            }
        },
        s("DEMA 20 trend", "Trend") { candles ->
            val closePrices = closes(candles)
            val dema = I.dema(closePrices, 20) ?: return@s NONE
            if (closePrices.last() > dema) Vote(1, 65) else Vote(-1, 65)
        },
        s("MACD histogram trend", "Trend") { candles ->
            val closePrices = closes(candles)
            val macd = I.macd(closePrices) ?: return@s NONE
            val lastClose = closePrices.last()
            // OLD: "hist > prevHist" decided the side. On a clean trend the
            // histogram barely moves, so the comparison fell through to the
            // rounding error and the strategy voted the wrong side.
            // NEW: a change below 0.0001% of the price is a TIE, not a signal,
            // so the strategy abstains near the crossover.
            val diff = macd.hist - macd.prevHist
            when {
                decisive(diff, lastClose) && diff > 0 -> Vote(1, 60)
                decisive(diff, lastClose) && diff < 0 -> Vote(-1, 60)
                else -> NONE
            }
        },
        // Trend — exact spec
        s("MA200 Cross MACD Confirm", "Trend") { candles ->
            val closePrices = closes(candles)
            val sma200 = I.sma(closePrices, 200) ?: return@s NONE
            val macd = I.macd(closePrices) ?: return@s NONE
            val sma50 = I.sma(closePrices, 50) ?: return@s NONE
            if (macd.line > macd.signal && sma50 > sma200) Vote(1, 75) else Vote(-1, 75)
        },
        s("Vegas Tunnel Trend-Confirmed Bullish", "Trend", ORIGIN_EMA_9_21) { candles ->
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
            val closePrices = closes(candles)
            val dema = I.dema(closePrices, 55) ?: return@s NONE
            val macd = I.macd(closePrices) ?: return@s NONE
            val lastClose = closePrices.last()
            // OLD: "dema > lastClose && macd.line > macd.signal" asked the LAGGING
            // DEMA to be ABOVE the price - which only happens in a downtrend.
            // So in a normal uptrend the "bullish" strategy voted SHORT: it was
            // long-only by name and short-only by behaviour.
            // NEW: the trend condition is the correct one - the price is above
            // the lagging DEMA (uptrend) and the MACD confirms. The strategy is
            // long-only by name, so it abstains (NONE) instead of voting the
            // wrong side when the condition is not met.
            val priceAboveDema = lastClose - dema
            if (decisive(priceAboveDema, lastClose) && priceAboveDema > 0 && macd.line > macd.signal) Vote(1, 70)
            else NONE
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
