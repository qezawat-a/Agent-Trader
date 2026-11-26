package com.bitunix.agenttrader.bitunix

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebSocket push shapes, checked against the official docs and a live capture
 * of wss://fapi.bitunix.com/public/ (2026-11):
 *  - "tickers" pushes an ARRAY whose elements carry the symbol in `s`;
 *  - "trade" pushes an ARRAY whose `s` is the trade SIDE ("buy"/"sell") while
 *    the symbol sits in the frame header;
 *  - "price", "ticker", the depth channels and the kline channels push an
 *    OBJECT.
 */
class WsPushShapeTest {

    @Test
    fun tickers_elements_carry_the_symbol() {
        val element = JSONObject().put("s", "BTCUSDT").put("la", "65894.9")
        assertEquals("BTCUSDT", BitunixWs.symbolOfPush("tickers", element, ""))
    }

    @Test
    fun trade_elements_carry_the_side_not_the_symbol() {
        // Live frame:
        // {"ch":"trade","symbol":"BTCUSDT","ts":...,"data":[{"t":"...","p":"65888.9","v":"0.0315","s":"sell"}]}
        val element = JSONObject().put("t", "2026-11-14T14:51:31Z").put("p", "65888.9")
            .put("v", "0.0315").put("s", "sell")
        assertEquals("BTCUSDT", BitunixWs.symbolOfPush("trade", element, "BTCUSDT"))
    }

    @Test
    fun object_pushes_use_the_frame_header_symbol() {
        val element = JSONObject().put("ip", "65925.8").put("mp", "65900")
        assertEquals("BTCUSDT", BitunixWs.symbolOfPush("price", element, "BTCUSDT"))
        assertEquals("BTCUSDT", BitunixWs.symbolOfPush("market_kline_1min", element, "BTCUSDT"))
        assertEquals("BTCUSDT", BitunixWs.symbolOfPush("depth_book5", element, "BTCUSDT"))
    }

    @Test
    fun only_tickers_uses_the_element_symbol_field() {
        assertEquals(setOf("tickers"), BitunixWs.ELEMENT_SYMBOL_CHANNELS)
    }

    @Test
    fun every_documented_channel_name_is_known() {
        assertEquals(
            listOf("price", "ticker", "tickers", "trade", "depth_books", "depth_book1", "depth_book5", "depth_book15"),
            BitunixWs.PUBLIC_CHANNELS,
        )
        assertEquals(listOf("balance", "order", "position", "tpsl"), BitunixWs.PRIVATE_CHANNELS)
        assertEquals(
            listOf("balance", "order", "position"),
            BitunixWs.PRIVATE_SUBSCRIPTION_CHANNELS,
            "tpsl is pushed automatically after private login",
        )
    }

    @Test
    fun documented_limits_are_enforced_locally() {
        assertEquals(300, BitunixWs.MAX_SUBSCRIPTIONS)
        // 5 messages per second ceiling -> at least 200ms between frames.
        assertTrue(BitunixWs.MIN_SEND_INTERVAL_MS >= 200L)
    }
}
