package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.Candle
import com.bitunix.agenttrader.util.fmt
import com.bitunix.agenttrader.util.fmt0
import org.json.JSONObject

/**
 * Automatic TP/SL chosen by the agent (the LLM), not by a fixed number.
 *
 * When auto TP/SL is on and the user set no stop (`set sl 0`), every signal
 * entry asks the model for its stop-loss and take-profit prices. The model
 * sees the raw market data — candles per timeframe, each timeframe's signal
 * and the combined strength, leverage and the liquidation price — and decides
 * the levels itself. There is no fixed percentage and no fixed formula
 * behind it, and no fixed fallback either: when the model gives no valid
 * answer, the entry is SKIPPED rather than opened with a made-up stop.
 *
 * The app only checks the answer against facts the exchange enforces: the
 * stop and the TP on the correct side of the entry (Bitunix 30007–30010) and
 * the stop before the liquidation price (30020–30025).
 */
data class TpSlAsk(
    val symbol: String,
    val dir: Int,
    val price: Double,
    val leverage: Int,
    /** Combined signal strength, 0..100. */
    val confidence: Double,
    val liquidation: Double,
    /** Candles per timeframe, oldest first. */
    val candles: Map<String, List<Candle>>,
    /** Each timeframe's own signal. */
    val perTimeframe: Map<String, TfSignal>,
)

data class TpSlAdvice(val sl: Double, val tp: Double, val reason: String)

object AgentTpSl {
    /** How many recent candles per timeframe go into the prompt. */
    const val CANDLES_IN_PROMPT = 40

    fun prompt(a: TpSlAsk): List<Pair<String, String>> {
        val side = if (a.dir > 0) "LONG" else "SHORT"
        val data = buildString {
            appendLine("symbol ${a.symbol} · side $side · entry ~${fmt(a.price)} · leverage ${a.leverage}x")
            appendLine("liquidation price ${fmt(a.liquidation)}")
            appendLine("combined signal strength ${fmt0(a.confidence)}%")
            for ((tf, s) in a.perTimeframe) {
                val d = when { s.dir > 0 -> "LONG"; s.dir < 0 -> "SHORT"; else -> "none" }
                appendLine("timeframe $tf signal $d ${fmt0(s.confidence)}% (${s.agreeing} strategies agree)")
            }
            for ((tf, series) in a.candles) {
                appendLine("candles $tf (time,open,high,low,close,volume), oldest first:")
                series.takeLast(CANDLES_IN_PROMPT).forEach {
                    appendLine("${it.time},${fmt(it.open)},${fmt(it.high)},${fmt(it.low)},${fmt(it.close)},${fmt(it.volume)}")
                }
            }
        }
        val system = "You set the stop-loss and take-profit for one futures entry. " +
            "Decide both prices yourself from the market data: structure, volatility, " +
            "the signal strength on each timeframe. A stronger, better-agreeing signal " +
            "may justify a farther target; a weak one a closer target or a tighter stop. " +
            "The stop must sit between the entry and the liquidation price. " +
            "Answer with ONE JSON object and nothing else: " +
            "{\"sl\": <price>, \"tp\": <price>, \"reason\": \"<one short sentence>\"}"
        return listOf("system" to system, "user" to data)
    }

    /** Pulls the first JSON object out of the model's text. Null when there is none. */
    fun parse(text: String?): TpSlAdvice? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = JSONObject(text.substring(start, end + 1))
            val sl = o.optString("sl").toDoubleOrNull() ?: o.optDouble("sl")
            val tp = o.optString("tp").toDoubleOrNull() ?: o.optDouble("tp")
            if (sl.isNaN() || tp.isNaN()) null
            else TpSlAdvice(sl, tp, o.optString("reason").take(200))
        }.getOrNull()
    }

    /**
     * Null when the advice is valid for an entry at [entry]; otherwise why not.
     * Only exchange facts are checked — never a preferred distance.
     */
    fun invalidReason(a: TpSlAdvice, dir: Int, entry: Double, liquidation: Double): String? = when {
        a.sl <= 0.0 || a.tp <= 0.0 -> "non-positive price"
        dir > 0 && a.sl >= entry -> "LONG stop not below entry"
        dir < 0 && a.sl <= entry -> "SHORT stop not above entry"
        dir > 0 && a.tp <= entry -> "LONG target not above entry"
        dir < 0 && a.tp >= entry -> "SHORT target not below entry"
        liquidation > 0.0 && dir > 0 && a.sl <= liquidation -> "stop at/after liquidation ${fmt(liquidation)}"
        liquidation > 0.0 && dir < 0 && a.sl >= liquidation -> "stop at/after liquidation ${fmt(liquidation)}"
        else -> null
    }
}
