package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns market data into a feeling: how stressed the tape is, what drives it,
 * and how the trader's own P&L feels right now. The agent brain uses this so it
 * can talk like a human who watches the market, not like a status endpoint.
 */
data class MarketMood(
    /** 0 = serene, 100 = panic. */
    val stress: Int,
    /** CALM, WATCH, STRESSED, PANIC. */
    val label: String,
    /** Human-readable drivers, most important first. */
    val drivers: List<String>,
    /** How the trader's own book feels. */
    val bookNote: String,
) {
    fun summary(): String =
        "$label (stress $stress/100)" + if (drivers.isEmpty()) "" else " — " + drivers.joinToString("; ")
}

object MarketMoodCalc {

    /**
     * @param candles recent candles (oldest first) of the watched symbol
     * @param funding current funding rate (0.001 = 0.1% per interval), null if unknown
     * @param pnlPct unrealized P&L as a fraction of the margin used (e.g. -0.08)
     */
    fun assess(
        candles: List<Candle>,
        funding: Double? = null,
        pnlPct: Double? = null,
    ): MarketMood {
        val drivers = mutableListOf<String>()
        var stress = 0

        // Volatility: mean absolute candle range over the recent window.
        val window = candles.takeLast(20)
        if (window.size >= 3) {
            val ranges = window.map { (it.high - it.low) / max(it.close, 1e-9) }
            val meanRange = ranges.average()
            val volPct = meanRange * 100
            when {
                volPct >= 4.0 -> {
                    stress += 55
                    drivers += "volatility ${"%.1f".format(volPct)}% per candle — violent tape"
                }
                volPct >= 2.0 -> {
                    stress += 32
                    drivers += "volatility ${"%.1f".format(volPct)}% per candle — choppy"
                }
                volPct >= 1.0 -> stress += 14
            }
        }

        // Trend damage: how far price is from its recent high.
        if (window.isNotEmpty()) {
            val last = window.last().close
            val high = window.maxOf { it.high }
            val dropPct = (high - last) / max(high, 1e-9) * 100
            if (dropPct >= 5.0) {
                stress += 30
                drivers += "price ${"%.1f".format(dropPct)}% off the recent high"
            } else if (dropPct >= 2.5) {
                stress += 16
                drivers += "price ${"%.1f".format(dropPct)}% below the recent high"
            }
        }

        // Funding crowding: extreme funding means one side is paying to hold.
        if (funding != null) {
            val fPct = funding * 100
            when {
                abs(fPct) >= 0.05 -> {
                    stress += 22
                    val who = if (fPct > 0) "longs pay shorts — crowded long" else "shorts pay longs — crowded short"
                    drivers += "funding ${"%.3f".format(fPct)}% ($who)"
                }
                abs(fPct) >= 0.02 -> stress += 10
            }
        }

        // The trader's own book.
        var bookNote = "no open exposure"
        if (pnlPct != null) {
            val p = pnlPct * 100
            bookNote = when {
                p <= -15 -> "your book is deep underwater (${"%.1f".format(p)}%) — protect it"
                p <= -5 -> "your book is down ${"%.1f".format(p)}%"
                p >= 15 -> "your book is up ${"%.1f".format(p)}% — protect the gains"
                p >= 5 -> "your book is up ${"%.1f".format(p)}%"
                else -> "your book is roughly flat (${"%.1f".format(p)}%)"
            }
            if (p <= -8) stress += 25
            else if (p >= 8) stress += 8
        }

        stress = stress.coerceIn(0, 100)
        val label = when {
            stress >= 70 -> "PANIC"
            stress >= 45 -> "STRESSED"
            stress >= 22 -> "WATCH"
            else -> "CALM"
        }
        return MarketMood(stress, label, drivers.take(3), bookNote)
    }
}
