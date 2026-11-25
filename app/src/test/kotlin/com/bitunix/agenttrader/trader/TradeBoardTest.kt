package com.bitunix.agenttrader.trader

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Trade screen's position/order board must show exchange facts, not guesses. */
class TradeBoardTest {

    private val livePosition = JSONObject(
        """{"positionId":"1","symbol":"BTCUSDT","qty":"0.5","avgOpenPrice":"60000",
           "side":"LONG","leverage":20,"margin":"1500","unrealizedPNL":"30",
           "liqPrice":"51000"}""",
    )

    private val tpSl = JSONArray(
        """[{"symbol":"BTCUSDT","orderId":"t1","tpPrice":"62000","tpQty":"0.25","status":"NEW"},
            {"symbol":"BTCUSDT","orderId":"s1","slPrice":"58000","slQty":"0.5","status":"NEW"},
            {"symbol":"ETHUSDT","orderId":"t2","tpPrice":"4000","tpQty":"1","status":"NEW"}]""",
    )

    @Test
    fun live_position_uses_exchange_field_names() {
        val p = parseBoardPosition(livePosition, markPrice = 61000.0)
        assertEquals("BTCUSDT", p.symbol)
        assertEquals("LONG", p.side)
        assertEquals(0.5, p.qty, 1e-9)
        assertEquals(60_000.0, p.entry, 1e-9)
        assertEquals(61_000.0, p.mark, 1e-9)
        assertEquals(20, p.leverage)
        assertEquals(1_500.0, p.margin, 1e-9)
        assertEquals(30.0, p.pnl, 1e-9)
        assertEquals(2.0, p.pnlPct, 1e-9)
        assertEquals(51_000.0, p.liq!!, 1e-9)
    }

    @Test
    fun low_risk_position_has_no_liquidation_price() {
        val p = parseBoardPosition(JSONObject("""{"symbol":"X","side":"SHORT","liqPrice":"0"}"""))
        assertNull(p.liq)
        assertEquals("SHORT", p.side)
        assertEquals(0.0, p.pnlPct, 1e-9)
    }

    @Test
    fun legacy_entry_field_is_accepted() {
        val p = parseBoardPosition(JSONObject("""{"symbol":"X","qty":"2","entryPrice":"10","side":"BUY"}"""))
        assertEquals(10.0, p.entry, 1e-9)
        assertEquals("LONG", p.side)
    }

    @Test
    fun pending_orders_and_tpsl_legs_are_flattened() {
        val orders = JSONArray(
            """[{"symbol":"BTCUSDT","orderType":"LIMIT","side":"BUY","price":"59000",
                 "qty":"0.1","status":"NEW"}]""",
        )
        val rows = parseBoardOrders(orders, tpSl)
        assertEquals(4, rows.size)
        assertEquals("LIMIT", rows[0].kind)
        assertEquals(59_000.0, rows[0].price, 1e-9)
        assertTrue(rows.any { it.kind == "TP" && it.price == 62_000.0 })
        assertTrue(rows.any { it.kind == "SL" && it.price == 58_000.0 })
    }

    @Test
    fun tpsl_for_symbol_does_not_leak_other_symbols() {
        val (stop, tps) = tpslFor(tpSl, "BTCUSDT")
        assertEquals(58_000.0, stop!!, 1e-9)
        assertEquals(1, tps.size)
        assertEquals("TP", tps[0].label)
        assertEquals(62_000.0, tps[0].price, 1e-9)
    }

    @Test
    fun tp_rows_say_partial_or_position() {
        assertEquals("partial · qty 0.5", tpKind(0.5))
        assertEquals("position · all", tpKind(0.0))
    }
}
