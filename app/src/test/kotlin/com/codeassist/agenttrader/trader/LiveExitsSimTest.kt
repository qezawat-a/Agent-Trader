package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A small in-memory Bitunix that follows the documented TP/SL rules:
 *  - Position TP/SL (tpsl/position/...): no qty, closes the WHOLE position, one per position.
 *  - TP/SL order (tpsl/place_order): closes only its tpQty / slQty.
 *  - A TP/SL attached to the entry order is a TP/SL order for the entry qty (it was
 *    seen next to a Position TP/SL on the same position, so it is not one).
 * Price moves are fed with [move]; triggered orders close quantity like the exchange.
 */
class FakeBitunix(var mark: Double) : BitunixRest("k", "s") {
    var qty = 0.0
    var entry = 0.0
    var side = ""
    val orders = mutableListOf<JSONObject>()
    val log = mutableListOf<String>()
    private var nextId = 1

    private fun ok(data: Any?) = JSONObject().put("code", 0).put("data", data ?: JSONObject())

    override fun publicGet(path: String, query: Map<String, String>): JSONObject = when {
        path.endsWith("/tickers") -> ok(JSONArray().put(JSONObject().put("symbol", "SSVUSDT").put("lastPrice", mark.toString())))
        path.endsWith("/trading_pairs") -> ok(JSONArray().put(JSONObject().put("symbol", "SSVUSDT")
            .put("basePrecision", 2).put("quotePrecision", 3).put("minTradeVolume", "0.01").put("maxLeverage", 100)))
        path.endsWith("/funding_rate") -> ok(JSONObject().put("symbol", "SSVUSDT").put("markPrice", mark.toString()).put("fundingRate", "0"))
        else -> ok(JSONArray())
    }

    override fun signedGet(path: String, query: Map<String, String>): JSONObject = when {
        path.endsWith("/futures/account") -> ok(JSONObject().put("available", "1000"))
        path.endsWith("/get_position_tiers") -> ok(JSONArray().put(JSONObject().put("level", 1)
            .put("startValue", "0").put("endValue", "1000000").put("leverage", 100).put("maintenanceMarginRate", "0.005")))
        path.endsWith("/get_order_detail") -> ok(JSONObject().put("status", "FILLED"))
        path.endsWith("/get_pending_positions") -> ok(if (qty <= 0) JSONArray() else JSONArray().put(
            JSONObject().put("symbol", "SSVUSDT").put("positionId", "P1").put("side", side)
                .put("qty", fmtQ(qty)).put("avgOpenPrice", entry.toString()).put("leverage", "50")))
        path.endsWith("/tpsl/get_pending_orders") -> ok(JSONArray(orders.map { JSONObject(it.toString()) }))
        else -> ok(JSONArray())
    }

    override fun signedPost(path: String, body: JSONObject): JSONObject {
        log += "$path $body"
        when {
            path.endsWith("/trade/place_order") -> {
                qty = body.getString("qty").toDouble(); entry = mark
                side = if (body.getString("side") == "BUY") "LONG" else "SHORT"
                if (body.has("tpPrice") || body.has("slPrice")) {
                    // Attached TP/SL: closes the whole position.
                    orders += JSONObject().put("id", "O${nextId++}").put("positionId", "P1")
                        .put("tpPrice", body.optString("tpPrice")).put("slPrice", body.optString("slPrice"))
                        .put("tpQty", if (body.has("tpPrice")) body.getString("qty") else "")
                        .put("slQty", if (body.has("slPrice")) body.getString("qty") else "")
                }
                return ok(JSONObject().put("orderId", "E1"))
            }
            path.endsWith("/tpsl/position/place_order") -> {
                check(orders.none { it.optString("tpQty").isBlank() && it.optString("slQty").isBlank() && it.optString("kind") == "position" }) {
                    "Each position can only have one Position TP/SL Order"
                }
                orders += JSONObject().put("id", "O${nextId++}").put("positionId", "P1").put("kind", "position")
                    .put("tpPrice", body.optString("tpPrice")).put("slPrice", body.optString("slPrice"))
            }
            path.endsWith("/tpsl/position/modify_order") -> {
                val o = orders.first { it.optString("kind") == "position" }
                if (body.has("slPrice")) o.put("slPrice", body.getString("slPrice"))
                if (body.has("tpPrice")) o.put("tpPrice", body.getString("tpPrice"))
            }
            path.endsWith("/tpsl/place_order") -> orders += JSONObject().put("id", "O${nextId++}").put("positionId", "P1")
                .put("tpPrice", body.optString("tpPrice")).put("tpQty", body.optString("tpQty"))
                .put("slPrice", body.optString("slPrice")).put("slQty", body.optString("slQty"))
            path.endsWith("/tpsl/modify_order") -> {
                val o = orders.first { it.getString("id") == body.getString("orderId") }
                for (k in listOf("tpPrice", "tpQty", "slPrice", "slQty")) if (body.has(k)) o.put(k, body.getString(k))
            }
        }
        return ok(JSONObject())
    }

    /** Moves the price and fires every TP/SL it crosses, as the exchange would. */
    fun move(price: Double) {
        mark = price
        val long = side == "LONG"
        for (o in orders.toList()) {
            if (qty <= 0) break
            val tp = o.optString("tpPrice").toDoubleOrNull()
            val sl = o.optString("slPrice").toDoubleOrNull()
            val tpHit = tp != null && tp > 0 && (if (long) price >= tp else price <= tp)
            val slHit = sl != null && sl > 0 && (if (long) price <= sl else price >= sl)
            if (!tpHit && !slHit) continue
            val q = (if (tpHit) o.optString("tpQty") else o.optString("slQty")).toDoubleOrNull()
            qty = if (q == null) 0.0 else maxOf(0.0, qty - q)
            if (q != null && tpHit) orders.remove(o)
        }
        if (qty <= 1e-9) { qty = 0.0; orders.clear() }
    }

    fun stops() = orders.filter { (it.optString("slPrice").toDoubleOrNull() ?: 0.0) > 0 }
    private fun fmtQ(v: Double) = java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}

class LiveExitsSimTest {

    private fun liveEngine(fake: FakeBitunix): TraderEngine = TraderEngine(fake).apply {
        live = true
        leverage = 50
        guardIntervalSec = 0
        risk = risk.copy(slPct = 1.0, breakevenRoiPct = 25.0, trailingTriggerRoiPct = 0.0)
    }

    @Test
    fun live_ladder_closes_a_quarter_per_rung_not_the_whole_position() {
        val fake = FakeBitunix(2.622)
        val e = liveEngine(fake).apply { ladderOn = true; ladderRungs = 4; risk = risk.copy(breakevenRoiPct = 0.0) }
        val out = e.manualEntry("SSVUSDT", 1, 0.10)
        assertTrue("LIVE OPEN" in out, out)
        assertTrue("ladder 4/4" in out, out)
        val start = fake.qty
        // No order that would close everything at a TP.
        assertTrue(fake.orders.none { (it.optString("tpPrice").toDoubleOrNull() ?: 0.0) > 0 && it.optString("tpQty").isBlank() }, fake.orders.toString())
        assertEquals(1, fake.stops().size, "exactly one stop: ${fake.orders}")
        // R = the stop distance the engine really used (read from the exchange).
        val r = 2.622 - fake.stops().single().getString("slPrice").toDouble()
        assertTrue(r > 0, "stop below entry")
        fake.move(2.622 + 1.05 * r)
        assertEquals(start * 0.75, fake.qty, 0.011, "1R closes 25%")
        fake.move(2.622 + 2.05 * r)
        assertEquals(start * 0.50, fake.qty, 0.011, "2R closes 25%")
        fake.move(2.622 + 3.05 * r)
        assertEquals(start * 0.25, fake.qty, 0.011, "3R closes 25%")
        fake.move(2.622 + 4.05 * r)
        assertEquals(0.0, fake.qty, 1e-9, "4R closes the rest")
    }

    @Test
    fun live_breakeven_fires_by_itself_and_moves_the_one_existing_stop() {
        val fake = FakeBitunix(2.622)
        val e = liveEngine(fake)
        val out = e.manualEntry("SSVUSDT", 1, 0.10)
        assertTrue("LIVE OPEN" in out, out)
        assertEquals(1, fake.stops().size, "one stop after entry: ${fake.orders}")
        e.tick() // first look at the position
        // 50x: +0.6% price = +30% ROI, past the 25% breakeven level.
        fake.move(2.622 * 1.006)
        val events = e.tick()
        assertTrue(events.any { "Breakeven" in it && "SL moved" in it }, events.toString())
        assertEquals(1, fake.stops().size, "still one stop: ${fake.orders}")
        assertEquals(2.622, fake.stops().single().getString("slPrice").toDouble(), 1e-9)
        // Next tick: no second move, no second stop.
        e.tick()
        assertEquals(1, fake.stops().size)
    }

    @Test
    fun live_breakeven_with_ladder_on_keeps_the_rungs() {
        val fake = FakeBitunix(2.622)
        val e = liveEngine(fake).apply { ladderOn = true; ladderRungs = 4 }
        e.manualEntry("SSVUSDT", 1, 0.10)
        e.tick()
        fake.move(2.622 * 1.006)
        val events = e.tick()
        assertTrue(events.any { "Breakeven" in it && "SL moved" in it }, events.toString())
        assertEquals(4, fake.orders.count { it.optString("tpQty").isNotBlank() }, fake.orders.toString())
        assertEquals(1, fake.stops().size)
    }

    @Test
    fun live_without_ladder_has_one_tp_sl_only() {
        val fake = FakeBitunix(2.622)
        val e = liveEngine(fake)
        e.manualEntry("SSVUSDT", 1, 0.10)
        assertEquals(1, fake.orders.size, "one TP/SL order, not two: ${fake.orders}")
    }

    @Test
    fun paper_breakeven_fires_by_itself() {
        val e = TraderEngine(BitunixRest("k", "s"))
        val risk = e.risk.copy(slPct = 1.0, breakevenRoiPct = 25.0, trailingTriggerRoiPct = 0.0)
        val pos = e.broker.open("SSVUSDT", 1, 2.622, 50, risk)!!
        e.broker.manage("SSVUSDT", 2.622 * 1.006, risk)
        assertEquals(2.622, pos.stop!!, 1e-9)
    }
}
