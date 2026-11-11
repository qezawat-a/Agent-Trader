package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import kotlin.math.abs

/** Aggregated view of one timeframe. dir is 1, -1, or 0 when nothing qualifies. */
data class TfSignal(val dir: Int, val confidence: Double, val agreeing: Int)

/** One strategy's named vote, kept so the signal board can explain itself. */
data class StrategyVote(val name: String, val group: String, val dir: Int, val confidence: Int)

/** A timeframe's full evaluation: the decision plus every strategy's vote. */
data class TfEvaluation(
    val timeframe: String,
    val dir: Int,
    val confidence: Double,
    val agreeing: Int,
    val votes: List<StrategyVote>,
)

/**
 * One symbol on the signal board. [dir] is 1/‑1 for a trade decision and 0 when
 * the symbol does not qualify; [reasons] always says why, in plain words.
 */
data class SignalRow(
    val symbol: String,
    val price: Double,
    val timeframes: List<TfEvaluation>,
    val dir: Int,
    val confidence: Double,
    val status: String,
    val reasons: List<String>,
)

/** Final trade decision after checking every timeframe. */
data class Signal(val dir: Int, val confidence: Double, val timeframes: List<String>)

object SignalAggregator {
    const val MIN_AGREEING = 3
    const val MIN_CONFIDENCE = 70.0
    const val TIMEFRAME_CONFIDENCE = 60.0

    /** Timeframes that must agree. Intervals match the Bitunix kline parameter. */
    val TIMEFRAMES = listOf("15m", "1h")

    /** Every strategy's named vote for one timeframe, including abstentions. */
    fun votes(candles: List<Candle>): List<StrategyVote> =
        StrategyRegistry.all.map { st ->
            val v = st.vote(candles)
            StrategyVote(st.name, st.group, v.dir, v.confidence)
        }

    /** Evaluates one timeframe and keeps the per-strategy detail for the board. */
    fun evaluateTimeframe(timeframe: String, candles: List<Candle>): TfEvaluation {
        val all = votes(candles)
        var best = TfEvaluation(timeframe, 0, 0.0, 0, all)
        for (dir in listOf(1, -1)) {
            val mine = all.filter { it.dir == dir }
            if (mine.size < MIN_AGREEING) continue
            val conf = mine.map { it.confidence.toDouble() }.average()
            if (conf > best.confidence) best = TfEvaluation(timeframe, dir, conf, mine.size, all)
        }
        return best
    }

    fun timeframe(candles: List<Candle>): TfSignal {
        val e = evaluateTimeframe("", candles)
        return TfSignal(e.dir, e.confidence, e.agreeing)
    }

    /** Short status text: "LONG 78%", "SHORT 74%" or "no trade". */
    fun statusText(dir: Int, confidence: Double): String = when {
        dir > 0 -> "LONG ${"%.0f".format(confidence)}%"
        dir < 0 -> "SHORT ${"%.0f".format(confidence)}%"
        else -> "no trade"
    }

    /**
     * Applies the same rules as [combine] but returns a [SignalRow] that explains
     * the outcome step by step, so "no trade" is never a silent mystery.
     */
    fun evaluate(symbol: String, price: Double, perTf: Map<String, TfEvaluation>): SignalRow {
        val rows = TIMEFRAMES.mapNotNull { perTf[it] }
        val detail = mutableListOf<String>()
        var verdict: String? = null
        var dir = 0
        var confidence = 0.0

        if (rows.size < TIMEFRAMES.size) {
            verdict = "missing kline data for " + TIMEFRAMES.filter { perTf[it] == null }.joinToString(", ")
        } else {
            rows.forEach { e ->
                val sideVar = when (e.dir) {
                    1 -> "LONG"
                    -1 -> "SHORT"
                    else -> "no side"
                }
                detail += "${e.timeframe}: $sideVar, ${e.agreeing} strategies at ${"%.0f".format(e.confidence)}%" +
                    " (need $MIN_AGREEING at >=${TIMEFRAME_CONFIDENCE.toInt()}%)"
            }
            val withSide = rows.filter { it.dir != 0 }
            verdict = when {
                withSide.isEmpty() -> "no timeframe produced a side"
                withSide.map { it.dir }.distinct().size > 1 -> "timeframes disagree"
                else -> {
                    val weak = withSide.filter { it.confidence < TIMEFRAME_CONFIDENCE }
                    if (weak.isNotEmpty()) {
                        "${weak.joinToString { it.timeframe }} below the ${TIMEFRAME_CONFIDENCE.toInt()}% timeframe gate"
                    } else {
                        val avg = rows.map { it.confidence }.average()
                        if (avg < MIN_CONFIDENCE) {
                            "combined confidence ${"%.0f".format(avg)}% is below ${MIN_CONFIDENCE.toInt()}%"
                        } else {
                            dir = rows.first().dir
                            confidence = avg
                            null
                        }
                    }
                }
            }
        }
        // The blocking verdict comes first so it is never cut off in the UI.
        val reasons = listOfNotNull(verdict) + detail
        return SignalRow(symbol, price, rows, dir, confidence, statusText(dir, confidence), reasons)
    }

    /** Combines the timeframes. Every timeframe must pass the timeframe gate and point the same way. */
    fun combine(perTf: Map<String, TfSignal>): Signal? {
        if (perTf.size < TIMEFRAMES.size) return null
        val sigs = TIMEFRAMES.map { perTf[it] ?: return null }
        if (sigs.any { it.dir == 0 || it.confidence < TIMEFRAME_CONFIDENCE }) return null
        if (sigs.map { it.dir }.distinct().size != 1) return null
        val conf = sigs.map { it.confidence }.average()
        if (conf < MIN_CONFIDENCE) return null
        return Signal(sigs[0].dir, conf, TIMEFRAMES)
    }
}

/**
 * TP/SL rules from the user's spec, expressed as prices.
 *  - Stop-loss sits halfway (50%) between entry and the liquidation price, so the loss stays
 *    well inside the liquidation distance. Liquidation is approximated as entry * (1 -/+ 1/leverage).
 *  - Partial take-profit ladder: 1R, 2R, 3R, 4R, each closing 25%. R is the entry-to-stop distance.
 *  - Breakeven: once ROI reaches 10%, the stop moves to entry.
 *  - Trailing stop: once in profit, the stop trails the best price at a 15% ROI distance.
 */
data class TpSlPlan(
    val entry: Double,
    val stop: Double,
    val liquidation: Double,
    val ladder: List<Pair<Double, Double>>, // (price, fraction of position)
)

object TpSlPlanner {
    val LADDER_R = listOf(1.0, 2.0, 3.0, 4.0)
    const val LADDER_FRACTION = 0.25
    const val BREAKEVEN_ROI = 0.10
    const val TRAIL_ROI = 0.15
    const val STOP_FRACTION_OF_LIQ = 0.5

    fun plan(entry: Double, dir: Int, leverage: Int): TpSlPlan {
        val liqDist = entry / leverage
        val liq = if (dir > 0) entry - liqDist else entry + liqDist
        val stop = if (dir > 0) entry - STOP_FRACTION_OF_LIQ * (entry - liq)
        else entry + STOP_FRACTION_OF_LIQ * (liq - entry)
        val r = abs(entry - stop)
        val ladder = LADDER_R.map { k -> (if (dir > 0) entry + k * r else entry - k * r) to LADDER_FRACTION }
        return TpSlPlan(entry, stop, liq, ladder)
    }

    /** ROI of a position at a price, including leverage. */
    fun roi(entry: Double, price: Double, dir: Int, leverage: Int): Double =
        dir * (price - entry) / entry * leverage

    /** Trailing stop price for the best price seen so far. */
    fun trailStop(best: Double, dir: Int, leverage: Int): Double {
        val dist = TRAIL_ROI / leverage
        return if (dir > 0) best * (1 - dist) else best * (1 + dist)
    }
}

/**
 * Human-readable signal board. [rows] is what the engine evaluated; the header
 * spells out the gates so "confidence" and "other settings" are never hidden.
 */
internal fun formatSignalBoard(rows: List<SignalRow>): String = buildString {
    appendLine(
        "Signal board — 15m+1h must agree; each timeframe needs " +
            "${SignalAggregator.MIN_AGREEING} strategies at >=" +
            "${SignalAggregator.TIMEFRAME_CONFIDENCE.toInt()}%; combined >=" +
            "${SignalAggregator.MIN_CONFIDENCE.toInt()}%",
    )
    if (rows.isEmpty()) {
        appendLine("  no symbols evaluated")
    }
    rows.forEach { r ->
        val side = r.status
        append("  ${r.symbol.padEnd(11)} $side")
        if (r.timeframes.isNotEmpty()) {
            append("  " + r.timeframes.joinToString(" · ") { e ->
                "${e.timeframe} ${"%.0f".format(e.confidence)}%/${e.agreeing}v"
            })
        }
        appendLine()
        if (r.dir == 0) r.reasons.take(2).forEach { appendLine("    why: $it") }
    }
    rows.firstOrNull { it.dir != 0 }?.let { best ->
        appendLine("Best: ${best.symbol} ${best.status} — say \"open ${best.symbol.removeSuffix("USDT")} ${if (best.dir > 0) "long" else "short"}\" or tap it in Trade.")
    }
}.trim()
