package com.codeassist.agenttrader.trader

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chat lines for private WebSocket updates, using the official field names. */
class FeedEventTest {

    @Test
    fun balance_update_uses_coin_available_margin() {
        val data = JSONObject()
            .put("coin", "USDT")
            .put("available", "1234.56")
            .put("frozen", "10")
            .put("margin", "200")
        val line = feedEventLine("balance", data, null)
        assertTrue(line.contains("USDT"), line)
        assertTrue(line.contains("1234.56"), line)
        assertTrue(line.contains("margin 200"), line)
    }

    @Test
    fun position_update_uses_symbol_side_qty_and_unrealized_pnl() {
        val data = JSONObject()
            .put("symbol", "BTCUSDT")
            .put("side", "LONG")
            .put("qty", "0.05")
            .put("unrealizedPNL", "12.5")
        val line = feedEventLine("position", data, null)
        assertTrue(line.contains("BTCUSDT"), line)
        assertTrue(line.contains("LONG"), line)
        assertTrue(line.contains("0.05"), line)
        assertTrue(line.contains("12.5"), line)
    }

    @Test
    fun position_update_falls_back_to_the_frame_symbol() {
        val data = JSONObject().put("side", "SHORT").put("qty", "1")
        val line = feedEventLine("position", data, "ETHUSDT")
        assertTrue(line.contains("ETHUSDT"), line)
    }

    @Test
    fun order_update_shows_type_qty_and_id() {
        val data = JSONObject()
            .put("symbol", "BTCUSDT")
            .put("type", "MARKET")
            .put("qty", "0.01")
            .put("orderId", "12345")
        val line = feedEventLine("order", data, null)
        assertTrue(line.contains("MARKET"), line)
        assertTrue(line.contains("12345"), line)
    }

    @Test
    fun tpsl_update_shows_stop_price_and_id() {
        val data = JSONObject()
            .put("symbol", "BTCUSDT")
            .put("slPrice", "61000")
            .put("orderId", "777")
        val line = feedEventLine("tpsl", data, null)
        assertTrue(line.contains("61000"), line)
        assertTrue(line.contains("777"), line)
    }

    @Test
    fun unknown_channels_produce_no_line() {
        assertEquals("", feedEventLine("market_kline_15min", JSONObject(), "BTCUSDT"))
        assertEquals("", feedEventLine("", JSONObject(), null))
    }
}
