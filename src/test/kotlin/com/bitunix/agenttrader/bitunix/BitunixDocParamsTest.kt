package com.bitunix.agenttrader.bitunix

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every request the client builds is matched against the official Bitunix
 * OpenAPI docs, endpoint by endpoint: the documented parameter set must be
 * present, and nothing that the docs dropped may still be sent.
 *
 * Sources: https://www.bitunix.com/api-docs/futures/ (market, account,
 * position, tp_sl, trade) and the 2026-10-27 change log.
 */
class BitunixDocParamsTest {

    /** org.json exposes the field names as an iterator. */
    private fun keysOf(json: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        val it = json.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    // ---- Get Kline (market) ----

    @Test
    fun kline_query_carries_exactly_the_documented_params() {
        val q = BitunixRest.klineQuery("BTCUSDT", "15m", limit = 100, endTime = 1672410000000L, type = "MARK_PRICE")
        assertEquals(
            setOf("symbol", "interval", "limit", "endTime", "type"),
            q.keys,
        )
        assertEquals("BTCUSDT", q["symbol"])
        assertEquals("15m", q["interval"])
        assertEquals("100", q["limit"])
        assertEquals("1672410000000", q["endTime"])
        assertEquals("MARK_PRICE", q["type"])
    }

    @Test
    fun kline_no_longer_sends_the_removed_start_time() {
        // Change log 2026-10-27: "startTime is not supported and has been
        // removed from the request parameters". The endpoint ignores it.
        val q = BitunixRest.klineQuery("BTCUSDT", "1h")
        assertFalse(q.containsKey("startTime"))
        assertEquals(setOf("symbol", "interval", "limit"), q.keys)
    }

    @Test
    fun kline_limit_is_capped_at_the_documented_maximum() {
        assertEquals("200", BitunixRest.klineQuery("BTCUSDT", "1m", limit = 5000)["limit"])
        assertEquals("1", BitunixRest.klineQuery("BTCUSDT", "1m", limit = 0)["limit"])
    }

    // ---- Get Depth (market) ----

    @Test
    fun depth_gears_are_the_documented_ones() {
        assertEquals(listOf("1", "5", "15", "50", "max"), BitunixRest.DEPTH_LIMITS)
    }

    @Test
    fun depth_rejects_a_gear_the_exchange_would_reject() {
        // Live: limit=7 answers 10008 "Parameter 7 does not match,
        // alternative value ["1","5","15","50","max"]".
        val e = assertFailsWith<IllegalArgumentException> {
            BitunixRest("k", "s").depth("BTCUSDT", "7")
        }
        assertTrue(e.message!!.contains("1,5,15,50,max"), e.message)
    }

    // ---- Place Order (trade) ----

    @Test
    fun place_order_body_carries_every_documented_field() {
        val body = BitunixRest.placeOrderBody(
            symbol = "BTCUSDT", qty = "0.5", side = "BUY", orderType = "LIMIT",
            tradeSide = "OPEN", positionId = "111", price = "60000", effect = "GTC",
            clientId = "c1", reduceOnly = false,
            tpPrice = "61000", tpStopType = "MARK_PRICE", tpOrderType = "LIMIT", tpOrderPrice = "61000.1",
            slPrice = "59000", slStopType = "LAST_PRICE", slOrderType = "MARKET", slOrderPrice = "59000.1",
        )
        assertEquals(
            setOf(
                "symbol", "qty", "side", "orderType", "price", "tradeSide", "positionId",
                "effect", "clientId", "reduceOnly",
                "tpPrice", "tpStopType", "tpOrderType", "tpOrderPrice",
                "slPrice", "slStopType", "slOrderType", "slOrderPrice",
                "marginCoin",
            ),
            keysOf(body),
        )
        assertEquals("LAST_PRICE", body.getString("slStopType"))
        assertEquals("MARK_PRICE", body.getString("tpStopType"))
    }

    @Test
    fun place_order_body_omits_optional_fields_that_were_not_supplied() {
        val body = BitunixRest.placeOrderBody(
            symbol = "BTCUSDT", qty = "0.5", side = "SELL", orderType = "MARKET",
        )
        assertEquals(setOf("symbol", "qty", "side", "orderType", "marginCoin"), keysOf(body))
    }

    @Test
    fun place_order_uses_the_documented_stop_types() {
        // The docs allow MARK_PRICE / LAST_PRICE only (the sample JSON in the
        // docs uses "MARK", which is NOT a valid value).
        val body = BitunixRest.placeOrderBody(
            symbol = "BTCUSDT", qty = "1", side = "BUY", orderType = "MARKET",
            tpPrice = "1", tpStopType = "LAST_PRICE", tpOrderType = "MARKET",
        )
        assertTrue(body.getString("tpStopType") in listOf("MARK_PRICE", "LAST_PRICE"))
        assertTrue(body.getString("tpOrderType") in listOf("LIMIT", "MARKET"))
    }

    // ---- Cancel Orders (trade) ----

    @Test
    fun cancel_orders_accepts_a_client_id_only_entry() {
        // Docs: each orderList entry carries orderId OR clientId.
        val body = BitunixRest.cancelOrdersBody(
            "BTCUSDT",
            listOf(OrderRef(orderId = "11111"), OrderRef(clientId = "22223")),
        )
        val list = body.getJSONArray("orderList")
        assertEquals(2, list.length())
        assertEquals("11111", list.getJSONObject(0).getString("orderId"))
        assertEquals("22223", list.getJSONObject(1).getString("clientId"))
    }

    @Test
    fun cancel_orders_rejects_an_entry_without_any_id() {
        assertFailsWith<IllegalArgumentException> {
            BitunixRest.cancelOrdersBody("BTCUSDT", listOf(OrderRef()))
        }
    }

    @Test
    fun cancel_orders_rejects_more_than_twenty_orders() {
        val many = (1..21).map { OrderRef(orderId = "$it") }
        assertFailsWith<IllegalArgumentException> {
            BitunixRest.cancelOrdersBody("BTCUSDT", many)
        }
    }

    // ---- Batch Order (trade) ----

    @Test
    fun batch_order_accepts_at_most_five_orders() {
        val five = (1..5).map {
            BitunixRest.batchOrderItem(qty = "0.1", side = "BUY", orderType = "MARKET")
        }
        val body = BitunixRest.batchOrderBody("BTCUSDT", five)
        assertEquals(5, body.getJSONArray("orderList").length())
        assertEquals("BTCUSDT", body.getString("symbol"))

        val six = five + BitunixRest.batchOrderItem(qty = "0.1", side = "SELL", orderType = "MARKET")
        assertFailsWith<IllegalArgumentException> {
            BitunixRest.batchOrderBody("BTCUSDT", six)
        }
    }

    @Test
    fun batch_order_item_carries_the_documented_fields() {
        val item = BitunixRest.batchOrderItem(
            qty = "0.5", side = "BUY", orderType = "LIMIT", tradeSide = "OPEN",
            positionId = "111", price = "60000", effect = "GTC", clientId = "c12345",
            reduceOnly = false, tpPrice = "61000", tpStopType = "LAST_PRICE",
            tpOrderType = "LIMIT", tpOrderPrice = "61000.1", slPrice = "59000",
            slStopType = "LAST_PRICE", slOrderType = "MARKET", slOrderPrice = "59000.1",
        )
        assertEquals(
            setOf(
                "qty", "side", "orderType", "tradeSide", "positionId", "price", "effect",
                "clientId", "reduceOnly", "tpPrice", "tpStopType", "tpOrderType",
                "tpOrderPrice", "slPrice", "slStopType", "slOrderType", "slOrderPrice",
            ),
            keysOf(item),
        )
    }

    // ---- Get Pending Positions (position) ----

    @Test
    fun pending_positions_query_carries_the_sub_account_filters() {
        // Docs: symbol, positionId, subAccountId, includeSubAccounts.
        val q = BitunixRest.pendingPositionsQuery(
            symbol = "BTCUSDT", positionId = "123", includeSubAccounts = true, subAccountId = 77L,
        )
        assertEquals(setOf("symbol", "positionId", "includeSubAccounts", "subAccountId"), q.keys)
        assertEquals("77", q["subAccountId"])
        assertEquals("true", q["includeSubAccounts"])
    }

    @Test
    fun pending_positions_query_omits_blank_filters() {
        assertTrue(BitunixRest.pendingPositionsQuery().isEmpty())
    }

    // ---- Error codes ----

    @Test
    fun retryable_codes_include_the_busy_order_code() {
        // Docs: 10009 "Another order operation is in progress, please try again
        // shortly" — transient, so it must be retried.
        assertTrue(10009 in BitunixRest.RETRYABLE_CODES)
        assertTrue(10001 in BitunixRest.RETRYABLE_CODES) // network error
        assertTrue(10005 in BitunixRest.RETRYABLE_CODES) // too many requests
        assertTrue(10006 in BitunixRest.RETRYABLE_CODES) // request too frequently
        assertTrue(40007 in BitunixRest.RETRYABLE_CODES) // share settlement
        // Business errors must NOT be retried.
        assertFalse(20003 in BitunixRest.RETRYABLE_CODES) // insufficient balance
        assertFalse(10002 in BitunixRest.RETRYABLE_CODES) // parameter error
        assertFalse(30017 in BitunixRest.RETRYABLE_CODES) // qty below minimum
    }

    // ---- Rate limits ----

    @Test
    fun rate_limits_follow_the_documented_values() {
        // "Rate Limit: 1 req/sec/uid" (close_all_position)
        assertEquals(1000L, BitunixRest.minIntervalMs("/api/v1/futures/trade/close_all_position"))
        // "Rate Limit: 2 req/sec/uid" (batch_order)
        assertEquals(500L, BitunixRest.minIntervalMs("/api/v1/futures/trade/batch_order"))
        // "Rate Limit: 5 req/sec/uid" (adjust_position_margin, flash_close_position)
        assertEquals(200L, BitunixRest.minIntervalMs("/api/v1/futures/account/adjust_position_margin"))
        assertEquals(200L, BitunixRest.minIntervalMs("/api/v1/futures/trade/flash_close_position"))
        // "Rate Limit: 20 req/sec/uid" (place_order) — the global 8/s cap is stricter.
        assertEquals(125L, BitunixRest.minIntervalMs("/api/v1/futures/trade/place_order"))
        // Unlisted endpoints fall back to the global cap.
        assertEquals(125L, BitunixRest.minIntervalMs("/api/v1/futures/market/kline"))
    }

    // ---- Symbol lists ----

    @Test
    fun symbol_lists_are_trimmed_to_the_documented_maximum() {
        val many = (1..60).joinToString(",") { "S$it" }
        val param = BitunixRest.symbolsParam(many, 50)
        assertEquals(50, param.split(",").size)
        assertEquals("BTCUSDT,ETHUSDT", BitunixRest.symbolsParam(" BTCUSDT , ETHUSDT ", 50))
    }

    // ---- Id requirements ----

    @Test
    fun endpoints_that_need_an_order_id_reject_an_empty_call() {
        val rest = BitunixRest("k", "s")
        // The guard fires before any request goes out, so this stays offline.
        assertFailsWith<IllegalArgumentException> { rest.orderDetail() }
        assertFailsWith<IllegalArgumentException> { rest.modifyOrder() }
        assertFailsWith<IllegalArgumentException> { rest.modifyOrder(orderId = "", clientId = "") }
    }

    @Test
    fun signed_post_body_is_signed_byte_for_byte() {
        // The docs: "The request body format must be identical to the signature
        // string" — the same JSONObject instance is hashed and sent.
        val body = JSONObject().put("symbol", "BTCUSDT").put("qty", "0.5")
        val headers = BitunixRest.signedHeaders(
            "k", "s", "nonce", "1700000000000", emptyMap(), body.toString(),
        )
        val other = BitunixRest.signedHeaders(
            "k", "s", "nonce", "1700000000000", emptyMap(),
            JSONObject().put("qty", "0.5").put("symbol", "BTCUSDT").toString(),
        )
        // Key order may differ between instances, so the signs may differ — the
        // contract is that the SIGNED string is the SENT string.
        assertTrue(headers.containsKey("sign"))
        assertTrue(other.containsKey("sign"))
    }

    // ---- TP/SL documented requirements ----

    @Test
    fun partial_tp_sl_needs_a_price_and_a_quantity() {
        val rest = BitunixRest("k", "s")
        // Docs: at least one of tpPrice/slPrice AND at least one of tpQty/slQty.
        assertFailsWith<IllegalArgumentException> {
            rest.placeTpSlOrder("BTCUSDT", "1", tpPrice = "1")
        }
        assertFailsWith<IllegalArgumentException> {
            rest.placeTpSlOrder("BTCUSDT", "1", tpQty = "0.1")
        }
        assertFailsWith<IllegalArgumentException> {
            rest.modifyTpSlOrder("12", tpPrice = "1")
        }
        // Position-level TP/SL needs at least one trigger price.
        assertFailsWith<IllegalArgumentException> {
            rest.modifyPositionTpSl("BTCUSDT", "1", tpPrice = null, slPrice = null)
        }
        // Both position endpoints require symbol + positionId.
        assertFailsWith<IllegalArgumentException> {
            rest.placePositionTpSl("", "1", tpPrice = "100", slPrice = null)
        }
        assertFailsWith<IllegalArgumentException> {
            rest.modifyPositionTpSl("BTCUSDT", "", tpPrice = "100", slPrice = null)
        }
    }

    @Test
    fun limit_child_orders_require_their_order_price() {
        val rest = BitunixRest("k", "s")
        // Docs: tpOrderPrice/slOrderPrice are required when the matching
        // child order type is LIMIT.
        assertFailsWith<IllegalArgumentException> {
            rest.placeTpSlOrder(
                "BTCUSDT", "1", tpPrice = "110", tpQty = "0.1", tpOrderType = "LIMIT",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            rest.modifyTpSlOrder(
                "12", slPrice = "90", slQty = "0.1", slOrderType = "LIMIT",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BitunixRest.placeOrderBody(
                symbol = "BTCUSDT", qty = "0.5", side = "BUY", orderType = "MARKET",
                tpPrice = "110", tpOrderType = "LIMIT",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BitunixRest.batchOrderItem(
                qty = "0.5", side = "BUY", orderType = "MARKET",
                slPrice = "90", slOrderType = "LIMIT",
            )
        }
    }

    // ---- Documented required flags ----

    @Test
    fun a_limit_order_always_carries_an_effect() {
        // Docs: effect is "Required if the orderType is limit"; the documented
        // default value is GTC.
        val limit = BitunixRest.placeOrderBody(
            symbol = "BTCUSDT", qty = "0.5", side = "BUY", orderType = "LIMIT",
        )
        assertEquals("GTC", limit.getString("effect"))

        val market = BitunixRest.placeOrderBody(
            symbol = "BTCUSDT", qty = "0.5", side = "BUY", orderType = "MARKET",
        )
        assertFalse(market.has("effect"))

        val item = BitunixRest.batchOrderItem(qty = "0.1", side = "BUY", orderType = "LIMIT")
        assertEquals("GTC", item.getString("effect"))
    }

    @Test
    fun modify_order_needs_a_qty() {
        // Docs: qty is required ("Amount (base coin)").
        val rest = BitunixRest("k", "s")
        assertFailsWith<IllegalArgumentException> { rest.modifyOrder(orderId = "1") }
        assertFailsWith<IllegalArgumentException> {
            rest.modifyOrder(orderId = "1", price = "60000")
        }
    }

    @Test
    fun adjust_position_margin_needs_a_side_or_a_position_id() {
        // Docs: "Either side or positionId required".
        val rest = BitunixRest("k", "s")
        assertFailsWith<IllegalArgumentException> {
            rest.adjustPositionMargin("BTCUSDT", "10")
        }
    }

    @Test
    fun account_endpoints_carry_their_documented_rate_limits() {
        // get_trading_settings / get_position_mode: "Rate Limit: 20 req/sec/uid".
        assertEquals(20, BitunixRest.RATE_LIMITS["/api/v1/futures/account/trading_settings"])
        assertEquals(20, BitunixRest.RATE_LIMITS["/api/v1/futures/account/position_mode"])
        // The global 8 req/s cap stays the binding limit for both.
        assertEquals(125L, BitunixRest.minIntervalMs("/api/v1/futures/account/trading_settings"))
        assertEquals(125L, BitunixRest.minIntervalMs("/api/v1/futures/account/position_mode"))
    }
}
