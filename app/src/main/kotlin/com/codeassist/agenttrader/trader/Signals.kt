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
    fun evaluate(symbol: String, price: Double, perTf: Map<String, TfEvaluation>, gates: Gates = Gates()): SignalRow {
        val rows = gates.timeframes.mapNotNull { perTf[it] }
        val detail = mutableListOf<String>()
        var verdict: String? = null
        var dir = 0
        var confidence = 0.0

        if (rows.size < gates.timeframes.size) {
            verdict = "missing kline data for " + gates.timeframes.filter { perTf[it] == null }.joinToString(", ")
        } else {
            rows.forEach { e ->
                val sideVar = when (e.dir) {
                    1 -> "LONG"
                    -1 -> "SHORT"
                    else -> "no side"
                }
                detail += "${e.timeframe}: $sideVar, ${e.agreeing} strategies at ${"%.0f".format(e.confidence)}%" +
                    " (need ${gates.minAgreeing} at >=${gates.minTfConfidence.toInt()}%)"
            }
            val withSide = rows.filter { it.dir != 0 }
            verdict = when {
                withSide.isEmpty() -> "no timeframe produced a side"
                else -> {
                    // Mirrors [combine]: how many timeframes must share one side.
                    val need = minOf(gates.minSideTfs, rows.size)
                    val counts = withSide.groupingBy { it.dir }.eachCount()
                    val qualifying = counts.entries.filter { it.value >= need }
                    when {
                        qualifying.size > 1 -> "timeframes split evenly between LONG and SHORT"
                        qualifying.isEmpty() -> {
                            val biggest = counts.maxByOrNull { it.value }?.value ?: 0
                            "only $biggest of $need timeframes share a side" +
                                if (need >= rows.size) " (all must agree — try: set tf agree 2)" else ""
                        }
                        else -> {
                            val side = qualifying[0].key
                            val team = withSide.filter { it.dir == side }
                            val weak = team.filter { it.confidence < gates.minTfConfidence }
                            val strong = team.filter { it.confidence >= gates.minTfConfidence }
                            when {
                                strong.size < need ->
                                    "${weak.joinToString { it.timeframe }} below the ${gates.minTfConfidence.toInt()}% timeframe gate"
                                else -> {
                                    val avg = strong.map { it.confidence }.average()
                                    if (avg < gates.minConfidence) {
                                        "combined confidence ${"%.0f".format(avg)}% is below ${gates.minConfidence.toInt()}%"
                                    } else {
                                        dir = side
                                        confidence = avg
                                        null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The blocking verdict comes first so it is never cut off in the UI.
        val reasons = listOfNotNull(verdict) + detail
        return SignalRow(symbol, price, rows, dir, confidence, statusText(dir, confidence), reasons)
    }

    /**
     * The signal gates as the user configured them. The constants are defaults;
     * nothing is hard-capped — timeframes, vote counts and confidences all move.
     *
     * [minSideTfs]: how many timeframes must share ONE side before a trade is
     * allowed. Default Int.MAX_VALUE = every timeframe must agree (the old strict
     * rule). `set tf agree 3` / `set tf agree 2` relaxes it; minority timeframes
     * are then ignored instead of vetoing the trade.
     */
    data class Gates(
        val timeframes: List<String> = TIMEFRAMES,
        val minAgreeing: Int = MIN_AGREEING,
        val minTfConfidence: Double = TIMEFRAME_CONFIDENCE,
        val minConfidence: Double = MIN_CONFIDENCE,
        val minSideTfs: Int = Int.MAX_VALUE,
    )

    /**
     * Combines the configured timeframes into a trade decision. Default behavior
     * (minSideTfs = all): every timeframe must pass its gate and agree in direction.
     */
    fun combine(perTf: Map<String, TfSignal>, gates: Gates = Gates()): Signal? {
        if (perTf.size < gates.timeframes.size) return null
        val sigs = gates.timeframes.map { perTf[it] ?: return null }
        val need = minOf(gates.minSideTfs, sigs.size)
        val counts = sigs.filter { it.dir != 0 }.groupingBy { it.dir }.eachCount()
        val qualifying = counts.entries.filter { it.value >= need }
        // Both sides reaching the threshold = genuinely torn market: no trade.
        val dir = if (qualifying.size == 1) qualifying[0].key else return null
        val strong = sigs.withIndex().filter { (_, s) -> s.dir == dir && s.confidence >= gates.minTfConfidence }
        if (strong.size < need) return null
        val conf = strong.map { it.value.confidence }.average()
        if (conf < gates.minConfidence) return null
        return Signal(dir, conf, strong.map { gates.timeframes[it.index] })
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

    fun plan(entry: Double, dir: Int, leverage: Int, stopFraction: Double = STOP_FRACTION_OF_LIQ): TpSlPlan {
        val liqDist = entry / leverage
        val liq = if (dir > 0) entry - liqDist else entry + liqDist
        val stop = if (dir > 0) entry - stopFraction * (entry - liq)
        else entry + stopFraction * (liq - entry)
        val r = abs(entry - stop)
        val ladder = LADDER_R.map { k -> (if (dir > 0) entry + k * r else entry - k * r) to LADDER_FRACTION }
        return TpSlPlan(entry, stop, liq, ladder)
    }

    /** ROI of a position at a price, including leverage. */
    fun roi(entry: Double, price: Double, dir: Int, leverage: Int): Double =
        dir * (price - entry) / entry * leverage

    /** Trailing stop price for the best price seen so far, [trailRoi] distance behind it. */
    fun trailStop(best: Double, dir: Int, leverage: Int, trailRoi: Double = TRAIL_ROI): Double {
        val dist = trailRoi / leverage
        return if (dir > 0) best * (1 - dist) else best * (1 + dist)
    }
}

/**
 * Human-readable signal board. [rows] is what the engine evaluated; the header
 * spells out the gates so "confidence" and "other settings" are never hidden.
 */
internal fun formatSignalBoard(
    rows: List<SignalRow>,
    gates: SignalAggregator.Gates = SignalAggregator.Gates(),
): String = buildString {
    appendLine(
        "Signal board — ${gates.timeframes.joinToString("+")} must agree; each timeframe needs " +
            "${gates.minAgreeing} strategies at >=" +
            "${gates.minTfConfidence.toInt()}%; combined >=" +
            "${gates.minConfidence.toInt()}%",
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

/**
 * Kline intervals for GET /api/v1/futures/market/kline. The official docs list
 * 1m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M — plus "3m", which is NOT in the
 * docs but the endpoint accepts it (verified live, 2026-11, code:0) and the user
 * asked for it. Case matters: "1m" is one MINUTE, "1M" is one MONTH.
 */
val KLINE_INTERVALS = listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M")

/** Parses "BTCUSDT, ethusdt ;SOLUSDT" into clean uppercase symbols; null when none are valid. */
internal fun parseSymbolsCsv(raw: String): List<String>? {
    val out = raw.split(",", " ", ";")
        .map { it.trim().uppercase() }
        .filter { it.length in 4..16 && it.all { ch -> ch.isLetterOrDigit() } }
        .distinct()
    return out.ifEmpty { null }
}

/** Parses "15m,1h,1M" into valid kline intervals (max 5); null when anything is invalid.
 *  Case-sensitive: only "1M" (uppercase M) is the monthly interval per the docs. */
internal fun parseTimeframesCsv(raw: String): List<String>? {
    val out = raw.split(",", " ", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    if (out.isEmpty() || out.size > 5) return null
    return out.takeIf { out.all { tf -> tf in KLINE_INTERVALS } }
}
