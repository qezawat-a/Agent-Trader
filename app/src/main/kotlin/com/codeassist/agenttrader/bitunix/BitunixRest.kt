package com.codeassist.agenttrader.bitunix

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Bitunix futures REST client.
 *
 * Signature (official docs):
 *   digest = SHA256(nonce + timestamp + apiKey + sortedQueryParams + body)
 *   sign   = SHA256(digest + secretKey)
 * queryParams are sorted by key and concatenated as key+value with no separators;
 * body is the raw compact JSON string ("" when absent).
 *
 * Verified live (2026-11):
 *  - kline fields: time/open/high/low/close/baseVol/quoteVol, newest first (we sort ascending)
 *  - tickers fields: symbol/lastPrice/markPrice/open/high/low/quoteVol/baseVol
 *  - trading_pairs fields: basePrecision, quotePrecision, minTradeVolume, maxLeverage
 *  - kline interval enum: 1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M
 */
class BitunixRest(private val apiKey: String, private val secretKey: String) {

    private val base = "https://fapi.bitunix.com"
    private var lastRequestMs = 0L

    /** Public GET, no signature. */
    fun publicGet(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        call("GET", path, query, null, signed = false)

    /** Signed GET. */
    fun signedGet(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        call("GET", path, query, null, signed = true)

    /** Signed POST with a JSON body. */
    fun signedPost(path: String, body: JSONObject): JSONObject =
        call("POST", path, emptyMap(), body.toString(), signed = true)

    private fun call(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        signed: Boolean,
    ): JSONObject {
        var lastError: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            throttle()
            try {
                return callOnce(method, path, query, body, signed)
            } catch (e: RetryableError) {
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(BACKOFF_MS * (attempt + 1))
            }
        }
        throw lastError as? IllegalStateException ?: IllegalStateException("Request failed: $method $path")
    }

    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = lastRequestMs + MIN_INTERVAL_MS - now
        if (wait > 0) Thread.sleep(wait)
        lastRequestMs = System.currentTimeMillis()
    }

    private fun callOnce(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        signed: Boolean,
    ): JSONObject {
        val qs = query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = URL(base + path + if (qs.isNotEmpty()) "?$qs" else "")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("language", "en-US")
        conn.setRequestProperty("Content-Type", "application/json")
        if (signed) {
            val nonce = UUID.randomUUID().toString().replace("-", "")
            val timestamp = System.currentTimeMillis().toString()
            signedHeaders(apiKey, secretKey, nonce, timestamp, query, body)
                .forEach { (k, v) -> conn.setRequestProperty(k, v) }
        }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            if (code == 429 || code >= 500) throw RetryableError("Bitunix HTTP $code: ${text.take(200)}")
            throw IllegalStateException("Bitunix HTTP $code: ${text.take(200)}")
        }
        val json = JSONObject(text)
        val apiCode = json.optInt("code", 0)
        if (apiCode != 0) {
            val msg = "Bitunix error $apiCode: ${json.optString("msg")}"
            if (apiCode in RETRYABLE_CODES) throw RetryableError(msg)
            throw BitunixApiException(apiCode, msg)
        }
        return json
    }

    /** Extracts "data" as an object, or the first element when the API returns an array. */
    private fun JSONObject.dataObj(): JSONObject {
        val d = opt("data")
        return when (d) {
            is JSONObject -> d
            is JSONArray -> if (d.length() > 0) d.optJSONObject(0) ?: JSONObject() else JSONObject()
            else -> JSONObject()
        }
    }

    fun dataObject(json: JSONObject): JSONObject = json.dataObj()
    fun dataArray(json: JSONObject): JSONArray =
        json.optJSONArray("data")
            ?: json.optJSONArray("positionList")
            ?: json.optJSONArray("list")
            ?: JSONArray()

    // ---- Market data (public) ----

    /**
     * Kline history. Only CLOSED candles are returned — the forming candle is
     * excluded; rows arrive newest first and we sort them ascending.
     * [endTime] (exclusive, ms) pages backward through history; [type] is
     * LAST_PRICE (default) or MARK_PRICE. limit is 100..200 per request.
     *
     * The official docs list only symbol/interval/limit/endTime/type as query
     * params. [startTime] is NOT documented, but the endpoint accepts it
     * (verified live, 2026-11, code:0) — kept for backward-paging use.
     */
    fun klines(
        symbol: String,
        interval: String,
        limit: Int = 200,
        endTime: Long? = null,
        type: String? = null,
        startTime: Long? = null,
    ): List<Candle> {
        val q = mutableMapOf(
            "symbol" to symbol, "interval" to interval, "limit" to limit.toString(),
        )
        if (endTime != null) q["endTime"] = endTime.toString()
        if (startTime != null) q["startTime"] = startTime.toString()
        if (type != null) q["type"] = type
        val json = publicGet("/api/v1/futures/market/kline", q)
        val arr = dataArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Candle(
                time = o.getLong("time"),
                open = o.getString("open").toDouble(),
                high = o.getString("high").toDouble(),
                low = o.getString("low").toDouble(),
                close = o.getString("close").toDouble(),
                volume = o.getString("baseVol").toDouble(),
            )
        }.sortedBy { it.time }
    }

    /**
     * 24h tickers (lastPrice, quoteVol, open, high, low).
     * NOTE: the endpoint ignores the `symbols` filter — it always returns every
     * symbol — so results are filtered client-side.
     */
    fun tickers(symbols: String = ""): JSONArray {
        val q = if (symbols.isBlank()) emptyMap() else mapOf("symbols" to symbols)
        return filterTickers(dataArray(publicGet("/api/v1/futures/market/tickers", q)), symbols)
    }

    fun lastPrice(symbol: String): Double {
        val t = tickers(symbol)
        return if (t.length() > 0) t.getJSONObject(0).optString("lastPrice").toDoubleOrNull() ?: 0.0 else 0.0
    }

    /** Contract specs: basePrecision, quotePrecision, minTradeVolume, maxLeverage. */
    fun tradingPairs(symbols: String = ""): JSONArray {
        val q = if (symbols.isBlank()) emptyMap() else mapOf("symbols" to symbols)
        return dataArray(publicGet("/api/v1/futures/market/trading_pairs", q))
    }

    fun fundingRate(symbol: String): JSONObject = dataObject(publicGet(
        "/api/v1/futures/market/funding_rate", mapOf("symbol" to symbol)))

    /** Funding rates for ALL symbols: fundingRate, markPrice, nextFundingTime, fundingInterval. */
    fun fundingRateBatch(): JSONArray =
        dataArray(publicGet("/api/v1/futures/market/funding_rate/batch"))

    /** Order book depth. limit: 1, 5, 15, 50 or max (default 15). */
    fun depth(symbol: String, limit: Int = 15): JSONObject = dataObject(publicGet(
        "/api/v1/futures/market/depth", mapOf("symbol" to symbol, "limit" to limit.toString())))

    /**
     * Mark price for a symbol. Bitunix does not serve a dedicated mark_price
     * endpoint (404), but the single-symbol funding endpoint publishes markPrice,
     * indexPrice and lastPrice.
     */
    fun markPrice(symbol: String): JSONObject = fundingRate(symbol)

    /** Index price for a symbol (same source as [markPrice]). */
    fun indexPrice(symbol: String): JSONObject = fundingRate(symbol)

    /** Funding settlement history (canonical path: get_funding_rate_history). */
    fun fundingRateHistory(
        symbol: String,
        limit: Int = 100,
        startTime: Long? = null,
        endTime: Long? = null,
    ): JSONArray {
        val q = mutableMapOf("symbol" to symbol, "limit" to limit.toString())
        if (startTime != null) q["startTime"] = startTime.toString()
        if (endTime != null) q["endTime"] = endTime.toString()
        return dataArray(publicGet("/api/v1/futures/market/get_funding_rate_history", q))
    }

    // ---- Account (signed) ----

    /** Futures balance for a margin coin. Fields include available/freeze/margin. */
    fun account(marginCoin: String = "USDT"): JSONObject = dataObject(signedGet(
        "/api/v1/futures/account", mapOf("marginCoin" to marginCoin)))

    fun availableUsdt(): Double {
        val a = account()
        return a.optString("available").toDoubleOrNull()
            ?: a.optString("balance").toDoubleOrNull()
            ?: a.optString("allBalance").toDoubleOrNull()
            ?: 0.0
    }

    fun changeLeverage(symbol: String, marginCoin: String = "USDT", leverage: Int): JSONObject =
        signedPost("/api/v1/futures/account/change_leverage", JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin).put("leverage", leverage))

    fun changeMarginMode(symbol: String, marginCoin: String = "USDT", mode: String): JSONObject =
        signedPost("/api/v1/futures/account/change_margin_mode", JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin).put("marginMode", mode))

    fun changePositionMode(mode: String): JSONObject =
        signedPost("/api/v1/futures/account/change_position_mode", JSONObject().put("positionMode", mode))

    /** Leverage + margin mode for one symbol. */
    fun leverageMarginMode(symbol: String, marginCoin: String = "USDT"): JSONObject = dataObject(signedGet(
        "/api/v1/futures/account/get_leverage_margin_mode",
        mapOf("symbol" to symbol, "marginCoin" to marginCoin)))

    /** Adds (positive) or removes (negative) isolated margin on a position. */
    fun adjustPositionMargin(
        symbol: String,
        amount: String,
        side: String? = null,
        positionId: String? = null,
        marginCoin: String = "USDT",
    ): JSONObject {
        val body = JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin).put("amount", amount)
        if (side != null) body.put("side", side)
        if (positionId != null) body.put("positionId", positionId)
        return try {
            signedPost("/api/v1/futures/account/adjust_position_margin", body)
        } catch (_: BitunixApiException) {
            // The official examples use the position-scoped path; try it as fallback.
            signedPost("/api/v1/futures/position/adjust_margin", body)
        }
    }

    /**
     * Per-symbol account trading settings: marginMode, leverage (one-way) and
     * long/short leverage (hedge). Pass comma-separated symbols (max 50) or all.
     */
    fun tradingSettings(symbols: String = ""): JSONArray {
        val q = if (symbols.isBlank()) emptyMap() else mapOf("symbols" to symbols)
        return dataArray(signedGet("/api/v1/futures/account/trading_settings", q))
    }

    // ---- Copy trading / sub-account assets (signed) ---

    /** Queries whether futures/spot assets are available and the maximum transferable amount. */
    fun cpAssetQuery(): JSONObject = dataObject(signedGet("/api/v1/cp/asset/query"))

    /** Transfers asset from the main account to a cp/asset sub-account (amount, assetType FUTURES/SPOT). */
    fun cpAssetTransferToSubAccount(amount: String, assetType: String): JSONObject =
        signedPost("/api/v1/cp/asset/transfer-to-sub-account", JSONObject()
            .put("amount", amount).put("assetType", assetType))

    /** Transfers asset from a cp/asset sub-account back to the main account (amount, assetType). */
    fun cpAssetTransferToMainAccount(amount: String, assetType: String): JSONObject =
        signedPost("/api/v1/cp/asset/transfer-to-main-account", JSONObject()
            .put("amount", amount).put("assetType", assetType))
    // ---- Positions (signed) ----

    /**
     * Open positions. All filters are optional: `symbol`, `positionId`, and
     * sub-account scope (docs: without subAccountId, main + accessible subs are
     * returned; includeSubAccounts toggles the sub-account query).
     */
    fun pendingPositions(
        symbol: String = "",
        positionId: String = "",
        includeSubAccounts: Boolean? = null,
    ): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (positionId.isNotBlank()) put("positionId", positionId)
            if (includeSubAccounts != null) put("includeSubAccounts", includeSubAccounts.toString())
        }
        return dataArray(signedGet("/api/v1/futures/position/get_pending_positions", q))
    }

    fun historyPositions(symbol: String = "", limit: Int = 10): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            put("limit", limit.toString())
        }
        return dataArray(signedGet("/api/v1/futures/position/get_history_positions", q))
    }

    /**
     * Risk tiers for a symbol: level, startValue, endValue, leverage,
     * maintenanceMarginRate. Used to size positions against liquidation risk.
     */
    fun positionTiers(symbol: String): JSONArray =
        dataArray(signedGet("/api/v1/futures/position/get_position_tiers", mapOf("symbol" to symbol)))

    // ---- Trade (signed) ----

    /**
     * Places a market order.
     *  - marginCoin is REQUIRED by the exchange even though docs list it as optional.
     *  - side names the POSITION: BUY opens long, SELL opens short.
     *  - tradeSide ("OPEN"/"CLOSE") is only sent in hedge mode; pass null in one-way mode.
     *  - tpPrice/slPrice attach a position-level TP/SL on fill; all TPSL orders use
     *    LAST_PRICE stop type and MARKET order type per project rule.
     */
    fun placeMarketOrder(
        symbol: String, qty: String, side: String, tradeSide: String?,
        tpPrice: String?, slPrice: String?, reduceOnly: Boolean = false,
        marginCoin: String = "USDT", clientId: String = "",
    ): JSONObject {
        val body = JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin).put("qty", qty)
            .put("side", side).put("orderType", "MARKET").put("effect", "GTC")
        if (tradeSide != null) body.put("tradeSide", tradeSide)
        if (reduceOnly) body.put("reduceOnly", true)
        if (clientId.isNotBlank()) body.put("clientId", clientId)
        if (tpPrice != null) {
            body.put("tpPrice", tpPrice).put("tpStopType", "LAST_PRICE").put("tpOrderType", "MARKET")
        }
        if (slPrice != null) {
            body.put("slPrice", slPrice).put("slStopType", "LAST_PRICE").put("slOrderType", "MARKET")
        }
        val resp = signedPost("/api/v1/futures/trade/place_order", body)
        return dataObject(resp)
    }

    /** Order detail; data holds orderId, status (NEW/PART_FILLED/FILLED/CANCELED/...). */
    fun orderDetail(orderId: String): JSONObject = dataObject(signedGet(
        "/api/v1/futures/trade/get_order_detail", mapOf("orderId" to orderId)))

    fun pendingOrders(symbol: String = "", limit: Int = 20): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            put("marginCoin", "USDT") // required in practice
            put("limit", limit.toString())
        }
        return dataArray(signedGet("/api/v1/futures/trade/get_pending_orders", q))
    }

    fun historyTrades(symbol: String = "", limit: Int = 20): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            put("marginCoin", "USDT")
            put("limit", limit.toString())
        }
        return dataArray(signedGet("/api/v1/futures/trade/get_history_trades", q))
    }

    /** Historical orders (marginCoin must be sent in practice). */
    fun historyOrders(symbol: String = "", limit: Int = 20, marginCoin: String = "USDT"): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            put("marginCoin", marginCoin)
            put("limit", limit.toString())
        }
        return dataArray(signedGet("/api/v1/futures/trade/get_history_orders", q))
    }

    /** Cancels specific orders (max 20 per call). */
    fun cancelOrders(symbol: String, orderIds: List<String>, marginCoin: String = "USDT"): JSONObject {
        val list = JSONArray()
        orderIds.forEach { list.put(JSONObject().put("orderId", it)) }
        return signedPost("/api/v1/futures/trade/cancel_orders", JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin).put("orderList", list))
    }

    /** Cancels all open orders (optionally limited to one symbol). */
    fun cancelAllOrders(symbol: String = "", marginCoin: String = "USDT"): JSONObject =
        signedPost("/api/v1/futures/trade/cancel_all_orders", JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin))

    /**
     * Places up to 5 orders in one call (rate limit 2 req/s).
     * Each order follows the place_order fields; the response holds
     * data.successList and data.failureList.
     */
    fun batchOrder(symbol: String, orders: List<JSONObject>): JSONObject {
        val list = JSONArray()
        orders.forEach { list.put(it) }
        return signedPost("/api/v1/futures/trade/batch_order", JSONObject()
            .put("symbol", symbol).put("orderList", list))
    }

    /**
     * Modifies a pending order (price, qty and/or its TP/SL).
     * Either orderId or clientId is required; orderId wins when both are sent.
     */
    fun modifyOrder(
        orderId: String? = null,
        clientId: String? = null,
        symbol: String? = null,
        qty: String? = null,
        price: String? = null,
        tpPrice: String? = null,
        tpStopType: String? = null,
        tpOrderType: String? = null,
        tpOrderPrice: String? = null,
        slPrice: String? = null,
        slStopType: String? = null,
        slOrderType: String? = null,
        slOrderPrice: String? = null,
    ): JSONObject {
        val body = JSONObject()
        if (orderId != null) body.put("orderId", orderId)
        if (clientId != null) body.put("clientId", clientId)
        if (symbol != null) body.put("symbol", symbol)
        if (qty != null) body.put("qty", qty)
        if (price != null) body.put("price", price)
        if (tpPrice != null) body.put("tpPrice", tpPrice)
        if (tpStopType != null) body.put("tpStopType", tpStopType)
        if (tpOrderType != null) body.put("tpOrderType", tpOrderType)
        if (tpOrderPrice != null) body.put("tpOrderPrice", tpOrderPrice)
        if (slPrice != null) body.put("slPrice", slPrice)
        if (slStopType != null) body.put("slStopType", slStopType)
        if (slOrderType != null) body.put("slOrderType", slOrderType)
        if (slOrderPrice != null) body.put("slOrderPrice", slOrderPrice)
        return signedPost("/api/v1/futures/trade/modify_order", body)
    }

    // ---- TP/SL ----

    /** Position-level TP/SL: closes the whole position at market when triggered. */
    fun placePositionTpSl(
        symbol: String, positionId: String, tpPrice: String?, slPrice: String?,
        tpStopType: String = "LAST_PRICE", slStopType: String = "LAST_PRICE",
    ): JSONObject = positionTpSlBody(
        "/api/v1/futures/tpsl/position/place_order", symbol, positionId, tpPrice, slPrice, tpStopType, slStopType)

    /** Modifies an existing position-level TP/SL. */
    fun modifyPositionTpSl(
        symbol: String, positionId: String, tpPrice: String?, slPrice: String?,
        tpStopType: String = "LAST_PRICE", slStopType: String = "LAST_PRICE",
    ): JSONObject = positionTpSlBody(
        "/api/v1/futures/tpsl/position/modify_order", symbol, positionId, tpPrice, slPrice, tpStopType, slStopType)

    private fun positionTpSlBody(
        path: String, symbol: String, positionId: String,
        tpPrice: String?, slPrice: String?, tpStopType: String, slStopType: String,
    ): JSONObject {
        val body = JSONObject().put("symbol", symbol).put("positionId", positionId)
        if (tpPrice != null) body.put("tpPrice", tpPrice).put("tpStopType", tpStopType)
        if (slPrice != null) body.put("slPrice", slPrice).put("slStopType", slStopType)
        return signedPost(path, body)
    }

    /** Cancels a TP/SL order. */
    fun cancelTpSl(symbol: String, orderId: String): JSONObject =
        signedPost("/api/v1/futures/tpsl/cancel_order", JSONObject()
            .put("symbol", symbol).put("orderId", orderId))

    /**
     * Partial TP/SL order on an open position: at least one of tpPrice/slPrice
     * and at least one of tpQty/slQty is required.
     */
    fun placeTpSlOrder(
        symbol: String,
        positionId: String,
        tpPrice: String? = null,
        slPrice: String? = null,
        tpQty: String? = null,
        slQty: String? = null,
        tpStopType: String = "LAST_PRICE",
        slStopType: String = "LAST_PRICE",
        tpOrderType: String? = "MARKET",
        tpOrderPrice: String? = null,
        slOrderType: String? = "MARKET",
        slOrderPrice: String? = null,
    ): JSONObject {
        val body = JSONObject().put("symbol", symbol).put("positionId", positionId)
        if (tpPrice != null) {
            body.put("tpPrice", tpPrice).put("tpStopType", tpStopType)
            if (tpOrderType != null) body.put("tpOrderType", tpOrderType)
        }
        if (slPrice != null) {
            body.put("slPrice", slPrice).put("slStopType", slStopType)
            if (slOrderType != null) body.put("slOrderType", slOrderType)
        }
        if (tpQty != null) body.put("tpQty", tpQty)
        if (slQty != null) body.put("slQty", slQty)
        if (tpOrderPrice != null) body.put("tpOrderPrice", tpOrderPrice)
        if (slOrderPrice != null) body.put("slOrderPrice", slOrderPrice)
        return signedPost("/api/v1/futures/tpsl/place_order", body)
    }

    /** Modifies a partial TP/SL order (orderId required). */
    fun modifyTpSlOrder(
        orderId: String,
        tpPrice: String? = null,
        slPrice: String? = null,
        tpQty: String? = null,
        slQty: String? = null,
        tpStopType: String = "LAST_PRICE",
        slStopType: String = "LAST_PRICE",
        tpOrderType: String? = "MARKET",
        tpOrderPrice: String? = null,
        slOrderType: String? = "MARKET",
        slOrderPrice: String? = null,
    ): JSONObject {
        val body = JSONObject().put("orderId", orderId)
        if (tpPrice != null) {
            body.put("tpPrice", tpPrice).put("tpStopType", tpStopType)
            if (tpOrderType != null) body.put("tpOrderType", tpOrderType)
        }
        if (slPrice != null) {
            body.put("slPrice", slPrice).put("slStopType", slStopType)
            if (slOrderType != null) body.put("slOrderType", slOrderType)
        }
        if (tpQty != null) body.put("tpQty", tpQty)
        if (slQty != null) body.put("slQty", slQty)
        if (tpOrderPrice != null) body.put("tpOrderPrice", tpOrderPrice)
        if (slOrderPrice != null) body.put("slOrderPrice", slOrderPrice)
        return signedPost("/api/v1/futures/tpsl/modify_order", body)
    }

    /** Current position mode of the account: ONE_WAY or HEDGE. */
    fun positionMode(): JSONObject =
        dataObject(signedGet("/api/v1/futures/account/position_mode"))

    /**
     * Pending TP/SL orders. The API wants int32 for side/positionMode, so those
     * filters are left out and applied client-side.
     */
    fun pendingTpSlOrders(symbol: String = "", positionId: String = "", limit: Int = 50): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (positionId.isNotBlank()) put("positionId", positionId)
            put("limit", limit.toString())
        }
        return dataArray(signedGet("/api/v1/futures/tpsl/get_pending_orders", q))
    }

    /** Historical TP/SL orders. */
    fun historyTpSlOrders(symbol: String = "", limit: Int = 20): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            put("limit", limit.toString())
        }
        return dataArray(signedGet("/api/v1/futures/tpsl/get_history_orders", q))
    }

    /** Market-closes one position by id. */
    fun flashClose(positionId: String, marginCoin: String = "USDT"): JSONObject =
        dataObject(signedPost("/api/v1/futures/trade/flash_close_position", JSONObject()
            .put("positionId", positionId).put("marginCoin", marginCoin)))

    /** Market-closes everything (optionally one symbol). */
    fun closeAllPositions(symbol: String = ""): JSONObject =
        dataObject(signedPost("/api/v1/futures/trade/close_all_position",
            JSONObject().put("symbol", symbol)))

    companion object {
        /** Codes the docs mark retryable: network error, rate limit, share settlement. */
        val RETRYABLE_CODES = setOf(10001, 10005, 10006, 40007)
        private const val ATTEMPTS = 3
        private const val BACKOFF_MS = 800L
        /** Docs allow 10 req/s; we throttle to 8 req/s. */
        private const val MIN_INTERVAL_MS = 125L

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * The tickers endpoint ignores the `symbols` filter and returns every
         * symbol, so the filter is applied here. Blank input passes through.
         */
        fun filterTickers(all: JSONArray, symbols: String): JSONArray {
            if (symbols.isBlank()) return all
            val wanted = symbols.split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val out = JSONArray()
            for (i in 0 until all.length()) {
                val o = all.optJSONObject(i) ?: continue
                if (o.optString("symbol").uppercase() in wanted) out.put(o)
            }
            return out
        }

        /**
         * Builds the four signed headers per the official docs:
         *   digest = SHA256(nonce + timestamp + apiKey + sortedQueryParams + body)
         *   sign   = SHA256(digest + secretKey)
         * queryParams are concatenated key+value, sorted by key, no separators.
         */
        fun signedHeaders(
            apiKey: String,
            secretKey: String,
            nonce: String,
            timestamp: String,
            query: Map<String, String>,
            body: String?,
        ): Map<String, String> {
            val queryParams = query.toSortedMap().entries.joinToString("") { it.key + it.value }
            val digest = sha256Hex(nonce + timestamp + apiKey + queryParams + (body ?: ""))
            val sign = sha256Hex(digest + secretKey)
            return mapOf(
                "api-key" to apiKey,
                "nonce" to nonce,
                "timestamp" to timestamp,
                "sign" to sign,
            )
        }
    }
}

/** A Bitunix business error (code != 0) that is not retryable. */
class BitunixApiException(val code: Int, message: String) : IllegalStateException(message)

/** Transport/rate-limit failure worth retrying. */
internal class RetryableError(message: String) : IllegalStateException(message)

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)
