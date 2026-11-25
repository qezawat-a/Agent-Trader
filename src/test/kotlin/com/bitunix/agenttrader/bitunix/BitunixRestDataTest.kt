package com.bitunix.agenttrader.bitunix

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Response-shape tests matched against the official docs:
 *  - pending/history orders, history trades and history positions return the list
 *    nested under `data` (`orderList` / `tradeList` / `positionList`);
 *  - positions, tiers, klines, tickers, funding rates and trading settings return
 *    `data` as a bare array.
 */
class BitunixRestDataTest {

    private val rest = BitunixRest("test-api-key", "test-secret")

    @Test
    fun data_order_list_is_unwrapped() {
        val json = JSONObject(
            """{"code":0,"data":{"orderList":[{"orderId":"11111","status":"NEW"}],"total":1},"msg":"Success"}""",
        )
        val arr = rest.dataArray(json)
        assertEquals(1, arr.length())
        assertEquals("11111", arr.getJSONObject(0).optString("orderId"))
    }

    @Test
    fun data_position_list_is_unwrapped() {
        val json = JSONObject(
            """{"code":0,"data":{"positionList":[{"positionId":"99","symbol":"BTCUSDT"}],"total":1},"msg":"Success"}""",
        )
        val arr = rest.dataArray(json)
        assertEquals(1, arr.length())
        assertEquals("99", arr.getJSONObject(0).optString("positionId"))
    }

    @Test
    fun data_trade_list_is_unwrapped() {
        val json = JSONObject(
            """{"code":0,"data":{"tradeList":[{"tradeId":"7","orderId":"11111"}],"total":1},"msg":"Success"}""",
        )
        val arr = rest.dataArray(json)
        assertEquals(1, arr.length())
        assertEquals("7", arr.getJSONObject(0).optString("tradeId"))
    }

    @Test
    fun bare_data_array_is_returned_as_is() {
        val json = JSONObject(
            """{"code":0,"data":[{"symbol":"BTCUSDT","lastPrice":"60000"}],"msg":"Success"}""",
        )
        val arr = rest.dataArray(json)
        assertEquals(1, arr.length())
        assertEquals("BTCUSDT", arr.getJSONObject(0).optString("symbol"))
    }

    @Test
    fun data_object_without_a_list_yields_empty() {
        val json = JSONObject("""{"code":0,"data":{"positionMode":"HEDGE"},"msg":"Success"}""")
        assertEquals(0, rest.dataArray(json).length())
    }

    @Test
    fun missing_data_yields_empty() {
        assertEquals(0, rest.dataArray(JSONObject("""{"code":0,"msg":"Success"}""")).length())
    }

    @Test
    fun generic_list_key_is_unwrapped() {
        val json = JSONObject("""{"code":0,"data":{"list":[{"symbol":"ETHUSDT"}]},"msg":"Success"}""")
        val arr = rest.dataArray(json)
        assertEquals(1, arr.length())
        assertEquals("ETHUSDT", arr.getJSONObject(0).optString("symbol"))
    }

    @Test
    fun data_array_helper_handles_empty_array() {
        val json = JSONObject("""{"code":0,"data":[],"msg":"Success"}""")
        assertEquals(0, rest.dataArray(json).length())
    }

    @Test
    fun data_object_still_takes_the_first_array_element() {
        // account / funding_rate return `data` as an array; dataObject picks [0].
        val json = JSONObject(
            """{"code":0,"data":[{"marginCoin":"USDT","available":"1000"}],"msg":"Success"}""",
        )
        assertEquals("1000", rest.dataObject(json).optString("available"))
    }

    @Test
    fun tickers_filter_still_returns_a_json_array() {
        val all = JSONArray("""[{"symbol":"BTCUSDT","lastPrice":"1"}]""")
        assertEquals(1, BitunixRest.filterTickers(all, "BTCUSDT").length())
    }
}
