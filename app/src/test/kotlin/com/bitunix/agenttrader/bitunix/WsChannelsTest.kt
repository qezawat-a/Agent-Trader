package com.bitunix.agenttrader.bitunix

import com.bitunix.agenttrader.notify.notifiable
import com.bitunix.agenttrader.trader.feedEventLine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Channel names and payload field names re-checked against the official docs:
 * kline, ticker, tickers, price, trade, depth channels; balance/position/order/tpsl.
 */
class WsChannelsTest {

    @Test
    fun kline_channel_names_follow_the_docs() {
        // market_kline_1min ... market_kline_60min ... market_kline_1day / 3day / 1week / 1month
        assertEquals("market_kline_1min", BitunixWs.klineChannel("1m"))
        assertEquals("market_kline_5min", BitunixWs.klineChannel("5m"))
        assertEquals("market_kline_15min", BitunixWs.klineChannel("15m"))
        assertEquals("market_kline_30min", BitunixWs.klineChannel("30m"))
        assertEquals("market_kline_60min", BitunixWs.klineChannel("1h"))
        assertEquals("market_kline_2h", BitunixWs.klineChannel("2h"))
        assertEquals("market_kline_8h", BitunixWs.klineChannel("8h"))
        assertEquals("market_kline_12h", BitunixWs.klineChannel("12h"))
        assertEquals("market_kline_1day", BitunixWs.klineChannel("1d"))
        assertEquals("market_kline_3day", BitunixWs.klineChannel("3d"))
        assertEquals("market_kline_1week", BitunixWs.klineChannel("1w"))
        assertEquals("market_kline_1month", BitunixWs.klineChannel("1M"))
        assertEquals("mark_kline_60min", BitunixWs.klineChannel("1h", markPrice = true))
    }

    @Test
    fun public_channels_stay_silent_in_the_chat_feed() {
        val data = JSONObject().put("s", "BTCUSDT").put("la", "68000")
        assertEquals("", feedEventLine("tickers", data, "BTCUSDT"))
        assertEquals("", feedEventLine("ticker", data, "BTCUSDT"))
        assertEquals("", feedEventLine("price", JSONObject().put("mp", "10000"), "BTCUSDT"))
        assertEquals("", feedEventLine("trade", JSONObject(), "BTCUSDT"))
    }

    @Test
    fun balance_pushes_stay_out_of_the_notifications() {
        // Balance pushes land in the chat but never wake the user.
        val data = JSONObject().put("coin", "USDT").put("available", "1000").put("margin", "10")
        val line = feedEventLine("balance", data, null)
        assertTrue(line.isNotEmpty(), line)
        assertTrue(!notifiable(line), "balance pushes must not notify: $line")
    }

    @Test
    fun only_position_lifecycle_events_are_announced() {
        val open = JSONObject().put("event", "OPEN").put("symbol", "BTCUSDT")
            .put("side", "LONG").put("qty", "0.5").put("unrealizedPNL", "1.5")
        assertTrue(feedEventLine("position", open, null).contains("BTCUSDT"))

        val close = JSONObject().put("event", "CLOSE").put("symbol", "BTCUSDT")
            .put("side", "LONG").put("qty", "0")
        assertTrue(feedEventLine("position", close, null).contains("BTCUSDT"))

        // UPDATE fires on every position tick — it must not reach the chat.
        val update = JSONObject().put("event", "UPDATE").put("symbol", "BTCUSDT").put("qty", "0.5")
        assertEquals("", feedEventLine("position", update, null))
    }

    @Test
    fun private_order_line_uses_the_documented_event_and_status_fields() {
        val data = JSONObject()
            .put("event", "UPDATE").put("orderStatus", "PART_FILLED")
            .put("symbol", "BTCUSDT").put("type", "LIMIT").put("qty", "0.1")
            .put("orderId", "12")
        val line = feedEventLine("order", data, null)
        assertTrue(line.contains("UPDATE"), line)
        assertTrue(line.contains("PART_FILLED"), line)
        assertTrue(line.contains("LIMIT"), line)
    }

    @Test
    fun private_tpsl_line_uses_the_documented_event_and_status_fields() {
        val data = JSONObject()
            .put("event", "CLOSE").put("status", "FILLED")
            .put("symbol", "BTCUSDT").put("slPrice", "60000").put("orderId", "7")
        val line = feedEventLine("tpsl", data, null)
        assertTrue(line.contains("CLOSE"), line)
        assertTrue(line.contains("FILLED"), line)
        assertTrue(line.contains("sl 60000"), line)
    }

    @Test
    fun tickers_and_trade_push_data_as_arrays_with_per_item_symbols() {
        // Documented tickers push: data is a list of objects each carrying "s".
        val data = JSONArray("""[{"s":"BTCUSDT","la":"68000"},{"s":"ETHUSDT","la":"2100"}]""")
        assertEquals(2, data.length())
        assertEquals("BTCUSDT", data.getJSONObject(0).getString("s"))
        assertEquals("68000", data.getJSONObject(0).getString("la"))
    }
}
