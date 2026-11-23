package com.codeassist.agenttrader.trader

import org.json.JSONArray
import org.json.JSONObject

/** One take-profit rung, ready to render next to an open position. */
data class BoardTp(
    val label: String,
    val price: Double,
    val detail: String,
    val hit: Boolean,
)

/** One row of the order / TP-SL list. */
data class BoardOrder(
    val symbol: String,
    val kind: String,
    val side: String,
    val price: Double,
    val qty: Double,
    val status: String,
)

/** One open position, paper or live, with only the fields the app actually knows. */
data class BoardPosition(
    val symbol: String,
    val side: String,
    val qty: Double,
    val entry: Double,
    val mark: Double,
    val leverage: Int,
    val margin: Double,
    val pnl: Double,
    val pnlPct: Double,
    val liq: Double?,
    val stop: Double?,
    val tps: List<BoardTp>,
    val paper: Boolean,
)

/** Everything the Trade screen shows while a position is open. */
data class TradeBoard(
    val positions: List<BoardPosition>,
    val orders: List<BoardOrder>,
    val note: String = "",
)

private fun sideOf(raw: String): String = when (raw.uppercase()) {
    "BUY", "LONG" -> "LONG"
    "SELL", "SHORT" -> "SHORT"
    else -> raw.ifBlank { "?" }
}

private fun doubleOf(p: JSONObject, key: String, fallback: String = ""): Double =
    (p.optString(key).ifBlank { fallback }).toDoubleOrNull() ?: 0.0

/**
 * A live position from `get_pending_positions`. Missing exchange fields stay
 * missing (0 / null) instead of being guessed — the UI prints "—" for them.
 */
internal fun parseBoardPosition(p: JSONObject, markPrice: Double = 0.0): BoardPosition {
    val qty = doubleOf(p, "qty", p.optString("holdQty"))
    val entry = doubleOf(p, "avgOpenPrice", p.optString("entryPrice"))
    val margin = doubleOf(p, "margin")
    val pnl = doubleOf(p, "unrealizedPNL")
    val liq = doubleOf(p, "liqPrice").takeIf { it > 0.0 }
    return BoardPosition(
        symbol = p.optString("symbol"),
        side = sideOf(p.optString("side")),
        qty = qty,
        entry = entry,
        mark = markPrice,
        leverage = p.optInt("leverage", 0),
        margin = margin,
        pnl = pnl,
        pnlPct = if (margin > 0.0) pnl / margin * 100.0 else 0.0,
        liq = liq,
        stop = null,
        tps = emptyList(),
        paper = false,
    )
}

/** Ordinary open orders plus the TP and SL legs of every pending TP/SL order. */
internal fun parseBoardOrders(orders: JSONArray, tpsls: JSONArray): List<BoardOrder> {
    val out = ArrayList<BoardOrder>()
    for (i in 0 until orders.length()) {
        val o = orders.optJSONObject(i) ?: continue
        out += BoardOrder(
            symbol = o.optString("symbol"),
            kind = o.optString("orderType").ifBlank { "ORDER" },
            side = o.optString("side").ifBlank { "—" },
            price = doubleOf(o, "price"),
            qty = doubleOf(o, "qty", o.optString("holdQty")),
            status = o.optString("status"),
        )
    }
    for (i in 0 until tpsls.length()) {
        val o = tpsls.optJSONObject(i) ?: continue
        val symbol = o.optString("symbol")
        val side = o.optString("side").ifBlank { "—" }
        val status = o.optString("status")
        val qty = doubleOf(o, "qty")
        val tp = doubleOf(o, "tpPrice")
        val sl = doubleOf(o, "slPrice")
        if (tp > 0.0) out += BoardOrder(symbol, "TP", side, tp, qty, status)
        if (sl > 0.0) out += BoardOrder(symbol, "SL", side, sl, qty, status)
    }
    return out
}

/** Live TP and SL levels for one symbol, derived from the TP/SL order list. */
internal fun tpslFor(tpsls: JSONArray, symbol: String): Pair<Double?, List<BoardTp>> {
    val prices = ArrayList<BoardTp>()
    var stop: Double? = null
    for (i in 0 until tpsls.length()) {
        val o = tpsls.optJSONObject(i) ?: continue
        if (o.optString("symbol") != symbol) continue
        val tp = doubleOf(o, "tpPrice")
        if (tp > 0.0) {
            val qty = doubleOf(o, "tpQty").takeIf { it > 0.0 } ?: doubleOf(o, "qty")
            prices += BoardTp("TP", tp, if (qty > 0.0) "qty $qty" else "", false)
        }
        val sl = doubleOf(o, "slPrice")
        if (sl > 0.0 && stop == null) stop = sl
    }
    return stop to prices.sortedBy { it.price }
}
