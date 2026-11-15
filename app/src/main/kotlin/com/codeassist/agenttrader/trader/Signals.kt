package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import com.codeassist.agenttrader.util.fmt0
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
        dir > 0 -> "LONG ${fmt0(confidence)}%"
        dir < 0 -> "SHORT ${fmt0(confidence)}%"
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
                detail += "${e.timeframe}: $sideVar, ${e.agreeing} strategies at ${fmt0(e.confidence)}%" +
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
                                        "combined confidence ${fmt0(avg)}% is below ${gates.minConfidence.toInt()}%"
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
 *
 * Liquidation-aware: the stop is NEVER allowed to reach or pass the liquidation price.
 * A safety margin keeps the stop inside liquidation even when the user pushes
 * `set stop frac` high. If a stop cannot fit at the configured leverage, the
 * trade is refused rather than opened with a stop that liquidation would hit first.
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

    /** The stop must stay at least this share of the liquidation distance away
     *  from the liquidation price, so liquidation never beats the stop. */
    const val LIQ_SAFETY_FRACTION = 0.05

    /**
     * The fraction of the liquidation distance at which the liquidation guard
     * closes.
     *
     * The stop sits at 30% of that distance by default, so the guard stays a
     * BACKSTOP: it fires late, but before the exchange can liquidate.
     */
    const val LIQ_GUARD_ROOM_FRACTION = 0.85

    /**
     * How many ATRs away the stop must sit from the entry.
     *
     * The stop has to be outside the symbol's own noise, or the market gaps
     * through it and the loss is several times the intended one.
     */
    const val STOP_ATR_MULTIPLE = 1.5

    /**
     * The room left between the stop and the liquidation price, as a fraction
     * of the price. The leverage is capped so this room always exists.
     */
    const val LIQ_STOP_SAFETY_PCT = 0.0015

    /** The user-visible stop fraction may not exceed this — beyond it the stop
     *  is inside the safety margin and the trade is refused. */
    const val MAX_STOP_FRACTION = 0.90

    /**
     * Maintenance margin rate used when the symbol's tier table is not
     * available (paper mode, or the tier query failed).
     *
     * Bitunix's own liquidation formula is
     *   Liq = Entry × (1 − 1/Leverage + MMR)   (long; mirrored for a short),
     * so the MMR term eats INTO the liquidation distance. Ignoring it made
     * every distance in the app look safer than it was: at 100x with MMR 0.5%
     * the app saw 1.0% of room where the exchange had 0.5%. The default errs
     * on the safe side — 0.5% is the tier-1 rate of the majors, and the live
     * path reads the real value from the tier table.
     */
    const val DEFAULT_MMR = 0.005

    /**
     * The liquidation distance from the entry, in price units:
     *   Entry × (1/leverage − MMR)
     *
     * Zero when the leverage is too low for the MMR to leave any room — such
     * an entry is refused rather than opened.
     */
    fun liqDistance(entry: Double, leverage: Int, mmr: Double = DEFAULT_MMR): Double {
        if (entry <= 0.0 || leverage <= 0) return 0.0
        return maxOf(0.0, entry * (1.0 / leverage - mmr.coerceIn(0.0, 0.5)))
    }

    /** The liquidation price — the official Bitunix formula. */
    fun liqPrice(entry: Double, dir: Int, leverage: Int, mmr: Double = DEFAULT_MMR): Double {
        val d = liqDistance(entry, leverage, mmr)
        return if (dir > 0) entry - d else entry + d
    }

    /**
     * Builds a TP/SL plan where [stop] is guaranteed inside [liquidation] with
     * the safety margin. [stopFraction] is clamped to [MAX_STOP_FRACTION].
     *
     * The stop distance has TWO floors:
     *  - [stopFraction] of the liquidation distance (the historical rule);
     *  - [atrMultiple] × the symbol's ATR — the VOLATILITY floor.
     *
     * The volatility floor is what keeps the stop outside the noise. A stop
     * at 30% of the liquidation distance sits 0.21% from the entry at 75x,
     * while a 15m bar on that symbol routinely moves 1.7% — the market gaps
     * through it and the realised loss is three to five times the intended
     * one. The ATR floor says: never place a stop closer than the symbol's
     * own noise.
     */
    fun plan(
        entry: Double, dir: Int, leverage: Int,
        stopFraction: Double = STOP_FRACTION_OF_LIQ,
        mmr: Double = DEFAULT_MMR,
        atr: Double? = null,
        atrMultiple: Double = STOP_ATR_MULTIPLE,
    ): TpSlPlan {
        val liqDist = liqDistance(entry, leverage, mmr)
        val liq = if (dir > 0) entry - liqDist else entry + liqDist
        val frac = stopFraction.coerceIn(0.01, MAX_STOP_FRACTION)
        val volDist = if (atr != null && atr > 0) atrMultiple * atr else 0.0
        val dist = maxOf(frac * liqDist, volDist)
        val stop = if (dir > 0) {
            val s = entry - dist
            // Keep the stop inside liquidation with the safety margin.
            val floor = liq + LIQ_SAFETY_FRACTION * liqDist
            maxOf(s, floor)
        } else {
            val s = entry + dist
            val ceil = liq - LIQ_SAFETY_FRACTION * liqDist
            minOf(s, ceil)
        }
        val r = abs(entry - stop)
        val ladder = LADDER_R.map { k -> (if (dir > 0) entry + k * r else entry - k * r) to LADDER_FRACTION }
        return TpSlPlan(entry, stop, liq, ladder)
    }

    /**
     * The largest leverage at which a stop of [stopDistPct] (a fraction of the
     * price) still fits inside the liquidation distance, with [safetyPct] of
     * the price to spare.
     *
     *   1/L − MMR ≥ stopDist + safety   →   L ≤ 1/(stopDist + MMR + safety)
     *
     * This is the rule that makes the leverage DERIVED rather than assumed: a
     * symbol whose volatility needs a 2.6% stop cannot be traded at 75x,
     * because 75x leaves only 0.68% of room to the liquidation price.
     */
    fun maxLeverageForStop(stopDistPct: Double, mmr: Double, safetyPct: Double = LIQ_STOP_SAFETY_PCT): Int {
        if (stopDistPct <= 0.0) return Int.MAX_VALUE
        val lev = 1.0 / (stopDistPct + mmr + safetyPct)
        if (!lev.isFinite() || lev >= Int.MAX_VALUE) return Int.MAX_VALUE
        return lev.toInt().coerceAtLeast(1)
    }

    /** True when a stop at [stopFraction] of the liquidation distance still fits
     *  inside liquidation with the safety margin at [leverage]. No coercion — the
     *  raw user value is checked, so a setting of 100%+ correctly returns false. */
    fun stopFits(
        entry: Double, dir: Int, leverage: Int, stopFraction: Double,
        mmr: Double = DEFAULT_MMR,
        atr: Double? = null,
        atrMultiple: Double = STOP_ATR_MULTIPLE,
    ): Boolean {
        if (leverage <= 0 || entry <= 0) return false
        val liqDist = liqDistance(entry, leverage, mmr)
        if (liqDist <= 0.0) return false // no room at all: refuse the entry
        val liq = if (dir > 0) entry - liqDist else entry + liqDist
        val volDist = if (atr != null && atr > 0) atrMultiple * atr else 0.0
        val dist = maxOf(stopFraction * liqDist, volDist)
        val stop = if (dir > 0) entry - dist else entry + dist
        return if (dir > 0) stop > liq + LIQ_SAFETY_FRACTION * liqDist else stop < liq - LIQ_SAFETY_FRACTION * liqDist
    }

    /** ROI of a position at a price, including leverage. */
    fun roi(entry: Double, price: Double, dir: Int, leverage: Int): Double =
        dir * (price - entry) / entry * leverage

    /**
     * The ROI (negative, percent) at which the liquidation guard must close.
     *
     * The user sets a flat ROI — "close at −60% ROI" — but a flat ROI is only
     * safe at LOW leverage. The liquidation ROI is −(1 − L·MMR): at 75x with
     * MMR 0.65% the exchange liquidates at −51.25%, so a −60% guard would only
     * fire AFTERWARDS and the backstop would never run.
     *
     * So the guard has a distance floor as well: it closes at
     * [LIQ_GUARD_ROOM_FRACTION] of the distance from the entry to the
     * liquidation price, whichever trigger comes first. That is before the
     * liquidation at EVERY leverage, and it always stays behind the stop
     * (30% of that distance by default).
     *
     * [liqPrice] is the exchange's own liquidation price when it publishes
     * one, else the official formula.
     */
    fun guardThresholdRoiPct(
        userRoiPct: Double,
        entry: Double,
        liqPrice: Double,
        leverage: Int,
    ): Double {
        if (entry <= 0.0 || leverage <= 0) return -abs(userRoiPct)
        val liqDist = abs(entry - liqPrice)
        // The distance the user's ROI asks for, in price units.
        val userDistance = entry * (abs(userRoiPct) / 100.0) / leverage
        // The safety floor: a fixed share of the way to liquidation.
        val floorDistance = liqDist * LIQ_GUARD_ROOM_FRACTION
        // The closer to the entry wins — that is the earlier trigger.
        val distance = minOf(userDistance, floorDistance)
        return -distance / entry * leverage * 100.0
    }
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
    emptyReason: String = "",
): String = buildString {
    appendLine(
        "Signal board — ${gates.timeframes.joinToString("+")} must agree; each timeframe needs " +
            "${gates.minAgreeing} strategies at >=" +
            "${gates.minTfConfidence.toInt()}%; combined >=" +
            "${gates.minConfidence.toInt()}%",
    )
    if (rows.isEmpty()) {
        // "no symbols evaluated" alone looks like a broken app — say what to fix.
        appendLine("  no symbols evaluated" + if (emptyReason.isBlank()) "" else " — $emptyReason")
    }
    rows.forEach { r ->
        val side = r.status
        append("  ${r.symbol.padEnd(11)} $side")
        if (r.timeframes.isNotEmpty()) {
            append("  " + r.timeframes.joinToString(" · ") { e ->
                "${e.timeframe} ${fmt0(e.confidence)}%/${e.agreeing}v"
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

/**
 * Numbers typed by a human, not by a parser.
 *
 * The user writes how they talk: "set min volume 24h usdt 10000", "min volume
 * 24h usd 10000", "set scan top 10", "leverage 20x". A strict number parse
 * answers "Usage:" to all of them, which looks like the app ignoring the
 * command. These helpers read the number out of the sentence instead.
 */
private data class TypedNumber(val value: Double, val suffix: String)

private val NUMBER_TOKEN = Regex("^([0-9]+(?:[.,][0-9]+)?)([a-zA-Z%]*)$")

/** Every number-like token in the text, with the unit that followed it. */
private fun typedNumbers(raw: String): List<TypedNumber> =
    raw.split(Regex("[\\s,;:/]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { tok ->
            val m = NUMBER_TOKEN.find(tok) ?: return@mapNotNull null
            val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            TypedNumber(v, m.groupValues[2].lowercase())
        }

/** Units that describe a duration, not an amount ("24h" in "24h usdt 10000"). */
private val TIME_UNITS = setOf("h", "hr", "hrs", "d", "day", "days", "s", "sec", "secs", "w", "week", "weeks")

/** Multiplier for a money unit ("5m" = 5 million). */
private fun amountMultiplier(suffix: String): Double? = when (suffix) {
    "", "usdt", "usd", "t", "u", "%", "x" -> 1.0
    "k", "thousand" -> 1_000.0
    "m", "mm", "million" -> 1_000_000.0
    "b", "bn", "billion" -> 1_000_000_000.0
    else -> null
}

/**
 * The number the user meant: the last one, ignoring duration words.
 * "24h usdt 10000" -> 10000, "24h" -> 24, "10" -> 10.
 */
internal fun extractNumber(raw: String): Double? {
    val nums = typedNumbers(raw)
    if (nums.isEmpty()) return null
    val withoutTime = nums.filterNot { it.suffix in TIME_UNITS }
    return (withoutTime.lastOrNull() ?: nums.last()).value
}

/**
 * The amount the user meant, honouring k/m/b suffixes: "5m usdt" -> 5000000.
 * "24h usdt 10000" -> 10000 (the "24h" is a duration, not the amount).
 */
internal fun extractAmount(raw: String): Double? {
    val nums = typedNumbers(raw)
    if (nums.isEmpty()) return null
    val withUnits = nums.mapNotNull { n ->
        val mult = amountMultiplier(n.suffix) ?: return@mapNotNull null
        n to mult
    }
    if (withUnits.isEmpty()) return nums.last().value
    val withoutTime = withUnits.filterNot { it.first.suffix in TIME_UNITS }
    val (num, mult) = withoutTime.lastOrNull() ?: withUnits.last()
    return num.value * mult
}

/**
 * One contract in the candidate pool: what the exchange reports about it.
 *
 * @param quoteVol 24h quote volume in USDT — the liquidity floor works on this.
 * @param changePct 24h change % against the ticker's own open.
 * @param rangePct 24h high-low range in % — the tie-breaker in VOLUME mode.
 */
internal data class UniverseCandidate(
    val symbol: String,
    val quoteVol: Double,
    val changePct: Double,
    val rangePct: Double,
    val price: Double,
)

/**
 * Turns the exchange's ticker list into a candidate pool.
 *
 * Only contracts the exchange actually trades are kept ([tradable], when the
 * trading_pairs list is available) — the ticker list also carries pairs that
 * are closed or not API-traded, and scanning those burns requests for nothing.
 */
internal fun buildCandidates(
    tickers: org.json.JSONArray,
    tradable: Set<String>? = null,
): List<UniverseCandidate> {
    val out = mutableListOf<UniverseCandidate>()
    for (i in 0 until tickers.length()) {
        val o = tickers.optJSONObject(i) ?: continue
        val symbol = o.optString("symbol", "").trim().uppercase()
        if (symbol.isBlank()) continue
        if (tradable != null && symbol !in tradable) continue
        val vol = o.optString("quoteVol", "0").toDoubleOrNull() ?: 0.0
        val open = o.optString("open", "0").toDoubleOrNull() ?: 0.0
        val last = o.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
        val high = o.optString("high", "0").toDoubleOrNull() ?: 0.0
        val low = o.optString("low", "0").toDoubleOrNull() ?: 0.0
        if (vol <= 0.0 || last <= 0.0) continue
        val change = if (open > 0) (last - open) / open * 100.0 else 0.0
        val range = if (low > 0 && high > 0) (high - low) / low * 100.0 else 0.0
        out.add(UniverseCandidate(symbol, vol, change, range, last))
    }
    return out
}

/**
 * Ranks a candidate pool into the pick order for the given universe mode.
 *
 * Ported from the Telegram trader so both apps pick the same coins:
 *   VOLUME   deepest books first; 24h movement is only a tie-breaker. The score
 *            is liquidity-dominated (0.75), so a thin coin with a huge range
 *            can never outrank BTC — that is how you end up long a microcap
 *            with 10x leverage.
 *   GAINERS  only coins that are actually UP on the day (change > 0), biggest
 *            gain first. A coin that is flat or red is not a gainer and is
 *            dropped, even when the pool is smaller than the requested size:
 *            the size is a ceiling, not a quota.
 *   LOSERS   the mirror: only change < 0, biggest fall first.
 *   MOVERS   biggest move in EITHER direction.
 */
internal fun rankUniverse(pool: List<UniverseCandidate>, mode: String): List<UniverseCandidate> {
    return when (mode.lowercase()) {
        "gainers" -> pool.filter { it.changePct > 0.0 }.sortedByDescending { it.changePct }
        "losers" -> pool.filter { it.changePct < 0.0 }.sortedBy { it.changePct }
        "movers" -> pool.sortedByDescending { kotlin.math.abs(it.changePct) }
        else -> {
            val maxVol = pool.maxOfOrNull { it.quoteVol } ?: 0.0
            pool.sortedByDescending { c ->
                val liquidity = if (maxVol > 0) c.quoteVol / maxVol else 0.0
                val movement = kotlin.math.min(c.rangePct / 25.0, 1.0)
                liquidity * 0.75 + movement * 0.25
            }
        }
    }
}

/** Result of checking a user-typed universe against the exchange's symbol list. */internal data class UniverseCheck(
    /** Symbols that exist on the exchange (bare names are mapped to the USDT pair). */
    val accepted: List<String>,
    /** Symbols the exchange does not list — stock tickers like RANK or MOVERS. */
    val unknown: List<String>,
)

/**
 * Matches a typed universe against the live symbol list.
 *
 * "BTC" becomes "BTCUSDT" when that contract exists; anything the exchange
 * does not list (RANK, MOVERS, AAPL…) is reported instead of being silently
 * scanned — the scanner would fetch nothing for those and the board would say
 * "no symbols evaluated" without saying why.
 */
internal fun matchUniverse(symbols: List<String>, known: Set<String>): UniverseCheck {
    val accepted = mutableListOf<String>()
    val unknown = mutableListOf<String>()
    for (s in symbols) {
        val upper = s.uppercase()
        when {
            upper in known -> accepted.add(upper)
            "${upper}USDT" in known -> accepted.add("${upper}USDT")
            else -> unknown.add(upper)
        }
    }
    return UniverseCheck(accepted.distinct(), unknown)
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
