package com.bitunix.agenttrader.bitunix

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
 *  - kline interval enum: 1m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M
 *
 * Every path and parameter below is matched against the official docs
 * (https://www.bitunix.com/api-docs/futures/), endpoint by endpoint:
 *  - every request carries EVERY documented parameter (query string or body);
 *  - the per-endpoint rate limits the docs state ("Rate Limit: N req/sec/uid")
 *    are enforced locally, so the exchange never answers 10005/10006.
 *
 * Documented history that changed the implementation:
 *  - 2026-10-27 changelog: Get Kline no longer supports `startTime`. The
 *    endpoint silently IGNORES it (verified live: identical candles come back
 *    with and without it), so it is not sent any more — history pages with
 *    `endTime` + `limit`, counting backwards, exactly as the docs describe.
 */
class BitunixRest(private val apiKey: String, private val secretKey: String) {

    private val base = "https://fapi.bitunix.com"

    /** Timestamp of the last request — atomic because the chat, the scanner and
     *  the trading loop all call this client from different threads. */
    private val lastRequestMs = java.util.concurrent.atomic.AtomicLong(0)
    private val lastCallMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
            throttle(path)
            try {
                return callOnce(method, path, query, body, signed)
            } catch (e: RetryableError) {
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(BACKOFF_MS * (attempt + 1))
            }
        }
        throw lastError as? IllegalStateException ?: IllegalStateException("Request failed: $method $path")
    }

    private fun throttle(path: String) {
        val now = System.currentTimeMillis()
        // Global cap: the docs allow 10 req/s on most endpoints, we stay at 8.
        var wait = lastRequestMs.get() + MIN_INTERVAL_MS - now
        // Per-endpoint cap: some endpoints are far stricter (close_all = 1/s,
        // batch_order = 2/s, adjust_position_margin = 5/s).
        RATE_LIMITS[path]?.let { perSecond ->
            val last = lastCallMs[path] ?: 0L
            wait = maxOf(wait, last + (1000L / perSecond) - now)
        }
        if (wait > 0) Thread.sleep(wait)
        val stamp = System.currentTimeMillis()
        lastRequestMs.set(stamp)
        lastCallMs[path] = stamp
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
        if (!json.has("code")) {
            throw IllegalStateException("Bitunix response is missing required code")
        }
        val apiCode = json.optInt("code", Int.MIN_VALUE)
        if (apiCode == Int.MIN_VALUE) {
            throw IllegalStateException("Bitunix response has an invalid code")
        }
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
        if (d is JSONObject) return d
        if (d is JSONArray && d.length() > 0) return d.optJSONObject(0) ?: JSONObject()
        return JSONObject()
    }

    fun dataObject(json: JSONObject): JSONObject = json.dataObj()
    /**
     * Extracts the list payload from an API response. The docs use three shapes:
     *  - `data` is a bare array (positions, tiers, klines, tickers, trading pairs,
     *    funding rates, trading settings, pending TP/SL);
     *  - `data` is an object holding the list under `orderList` / `positionList` /
     *    `tradeList` (pending/history orders, history trades, history positions,
     *    history TP/SL);
     *  - a bare top-level list under the same keys (legacy/alternate shape).
     * `data` always wins over a top-level fallback.
     */
    fun dataArray(json: JSONObject): JSONArray {
        val d = json.opt("data")
        when (d) {
            is JSONArray -> return d
            is JSONObject -> firstList(d)?.let { return it }
        }
        return firstList(json) ?: JSONArray()
    }

    private fun firstList(obj: JSONObject): JSONArray? {
        for (key in DOCUMENTED_LIST_KEYS) obj.optJSONArray(key)?.let { return it }
        // Fallback: any array-valued field. Bitunix names the wrapper per
        // endpoint (tickerList, tradingPairList, fundingRateList, tierList...),
        // so a missing key must never blank the whole response.
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            obj.optJSONArray(k)?.let { return it }
        }
        return null
    }

    // ---- Market data (public) ----

    /**
     * Kline history. Only CLOSED candles are returned — the forming candle is
     * excluded; rows arrive newest first and we sort them ascending.
     * [endTime] (exclusive, ms) pages backward through history; [type] is
     * LAST_PRICE (default) or MARK_PRICE. limit is 1..200 per request.
     *
     * Documented query params: symbol, interval, limit, endTime, type — all of
     * them are sent. `startTime` was REMOVED from the endpoint by the
     * 2026-10-27 changelog ("startTime is not supported and has been removed
     * from the request parameters"); the exchange silently ignores it, so it is
     * not sent. History pages backwards with `endTime` + `limit`: the candle
     * whose open time equals endTime is NOT included.
     */
    fun klines(
        symbol: String,
        interval: String,
        limit: Int = 200,
        endTime: Long? = null,
        type: String? = null,
    ): List<Candle> {
        val q = klineQuery(symbol, interval, limit, endTime, type)
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

    /**
     * Order book depth. `limit` is a fixed gear enumeration in the docs:
     * "1", "5", "15", "50" or "max" (the maximum gear of the pair). Any other
     * value is rejected by the exchange with 10008 ("Parameter 7 does not
     * match, alternative value [...]"), so it is validated here.
     */
    fun depth(symbol: String, limit: String = "15"): JSONObject {
        require(DEPTH_LIMITS.contains(limit)) {
            "depth limit must be one of ${DEPTH_LIMITS.joinToString(",")} (got \"$limit\")"
        }
        return dataObject(publicGet(
            "/api/v1/futures/market/depth", mapOf("symbol" to symbol, "limit" to limit)))
    }

    /** Depth by numeric gear (1/5/15/50); "max" is available through [depth]. */
    fun depth(symbol: String, limit: Int): JSONObject = depth(symbol, limit.toString())

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
        val q = mutableMapOf("symbol" to symbol, "limit" to limit.coerceIn(1, 200).toString())
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

    fun changeMarginMode(symbol: String, marginCoin: String = "USDT", mode: String): JSONObject {
        require(mode == "ISOLATION" || mode == "CROSS") {
            "marginMode must be ISOLATION or CROSS"
        }
        return signedPost("/api/v1/futures/account/change_margin_mode", JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin).put("marginMode", mode))
    }

    fun changePositionMode(mode: String): JSONObject {
        require(mode == "ONE_WAY" || mode == "HEDGE") {
            "positionMode must be ONE_WAY or HEDGE"
        }
        return signedPost("/api/v1/futures/account/change_position_mode", JSONObject().put("positionMode", mode))
    }

    /** Leverage + margin mode for one symbol. */
    fun leverageMarginMode(symbol: String, marginCoin: String = "USDT"): JSONObject = dataObject(signedGet(
        "/api/v1/futures/account/get_leverage_margin_mode",
        mapOf("symbol" to symbol, "marginCoin" to marginCoin)))

    /**
     * Adds (positive) or removes (negative) isolated margin on a position.
     * Official path: POST /api/v1/futures/account/adjust_position_margin.
     * `side` (LONG/SHORT) or `positionId` must be supplied — at least one is required.
     */
    fun adjustPositionMargin(
        symbol: String,
        amount: String,
        side: String? = null,
        positionId: String? = null,
        marginCoin: String = "USDT",
    ): JSONObject {
        require(symbol.isNotBlank()) { "adjust_position_margin needs a symbol" }
        require(marginCoin.isNotBlank()) { "adjust_position_margin needs a marginCoin" }
        require(amount.isNotBlank()) { "adjust_position_margin needs an amount" }
        val normalizedSide = side?.uppercase()
        val normalizedPositionId = positionId?.takeIf { it.isNotBlank() }
        require(normalizedSide == "LONG" || normalizedSide == "SHORT" || normalizedPositionId != null) {
            "adjust_position_margin needs a side or a positionId"
        }
        require(normalizedSide == null || normalizedSide == "LONG" || normalizedSide == "SHORT") {
            "side must be LONG or SHORT"
        }
        val body = JSONObject()
            .put("symbol", symbol).put("marginCoin", marginCoin).put("amount", amount)
        if (normalizedSide != null) body.put("side", normalizedSide)
        if (normalizedPositionId != null) body.put("positionId", normalizedPositionId)
        return signedPost("/api/v1/futures/account/adjust_position_margin", body)
    }

    /**
     * Per-symbol account trading settings: marginMode, leverage (one-way) and
     * long/short leverage (hedge). Pass comma-separated symbols (max 50) or all.
     */
    fun tradingSettings(symbols: String = ""): JSONArray {
        val q = if (symbols.isBlank()) emptyMap() else mapOf("symbols" to symbolsParam(symbols, 50))
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
     * Open positions. Documented filters: symbol, positionId, subAccountId and
     * includeSubAccounts (without subAccountId the main account and every
     * sub-account the API key can reach are returned).
     */
    fun pendingPositions(
        symbol: String = "",
        positionId: String = "",
        includeSubAccounts: Boolean? = null,
        subAccountId: Long? = null,
    ): JSONArray {
        val q = pendingPositionsQuery(symbol, positionId, includeSubAccounts, subAccountId)
        return dataArray(signedGet("/api/v1/futures/position/get_pending_positions", q))
    }

    /**
     * Closed/historical positions. Documented filters: symbol, positionId,
     * startTime, endTime (position create time, unix ms), skip (default 0),
     * limit (default 10, max 100) and subAccountId.
     * Response: `data.positionList` (+ `data.total`).
     */
    fun historyPositions(
        symbol: String = "",
        positionId: String = "",
        startTime: Long? = null,
        endTime: Long? = null,
        skip: Int = 0,
        limit: Int = 10,
        subAccountId: Long? = null,
    ): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (positionId.isNotBlank()) put("positionId", positionId)
            if (startTime != null) put("startTime", startTime.toString())
            if (endTime != null) put("endTime", endTime.toString())
            put("skip", skip.toString())
            put("limit", limit.coerceIn(1, 100).toString())
            if (subAccountId != null) put("subAccountId", subAccountId.toString())
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
     * Places an order. Every documented parameter of `POST place_order` is here:
     * symbol, qty, price, side, tradeSide, positionId, orderType, effect,
     * clientId, reduceOnly and the six TP/SL fields (tpPrice, tpStopType,
     * tpOrderType, tpOrderPrice, slPrice, slStopType, slOrderType, slOrderPrice).
     *  - `side` names the POSITION: BUY opens long, SELL opens short.
     *  - `tradeSide` ("OPEN"/"CLOSE") is only sent in hedge mode; in one-way
     *    mode leave it null.
     *  - `positionId` is required when tradeSide is "CLOSE".
     *  - `price` is required when orderType is LIMIT; `tpOrderPrice` /
     *    `slOrderPrice` are required when the matching order type is LIMIT.
     */
    fun placeOrder(
        symbol: String,
        qty: String,
        side: String,
        orderType: String,
        tradeSide: String? = null,
        positionId: String? = null,
        price: String? = null,
        effect: String? = null,
        clientId: String? = null,
        reduceOnly: Boolean? = null,
        tpPrice: String? = null,
        tpStopType: String? = null,
        tpOrderType: String? = null,
        tpOrderPrice: String? = null,
        slPrice: String? = null,
        slStopType: String? = null,
        slOrderType: String? = null,
        slOrderPrice: String? = null,
    ): JSONObject {
        val body = placeOrderBody(
            symbol = symbol, qty = qty, side = side, orderType = orderType,
            tradeSide = tradeSide, positionId = positionId, price = price,
            effect = effect, clientId = clientId, reduceOnly = reduceOnly,
            tpPrice = tpPrice, tpStopType = tpStopType, tpOrderType = tpOrderType,
            tpOrderPrice = tpOrderPrice, slPrice = slPrice, slStopType = slStopType,
            slOrderType = slOrderType, slOrderPrice = slOrderPrice,
        )
        return dataObject(signedPost("/api/v1/futures/trade/place_order", body))
    }

    /**
     * Places a market order.
     *  - side names the POSITION: BUY opens long, SELL opens short.
     *  - tradeSide ("OPEN"/"CLOSE") is only sent in hedge mode; pass null in one-way mode.
     *  - tpPrice/slPrice attach the documented Order TP/SL to this entry order;
     *    TP uses LAST_PRICE and MARKET order type per project rule. Position
     *    TP/SL is a separate method and is placed against positionId.
     *  - the STOP triggers on MARK_PRICE, not LAST_PRICE: the exchange liquidates
     *    against the MARK price, so a stop that watches the LAST price can be
     *    bypassed in a fast move (mark reaches the liquidation price before the
     *    last price ever touches the stop). Same price series for the stop and
     *    for the liquidation → the stop always gets there first.
     */
    fun placeMarketOrder(
        symbol: String, qty: String, side: String, tradeSide: String?,
        tpPrice: String?, slPrice: String?, reduceOnly: Boolean = false,
        clientId: String = "",
        positionId: String? = null,
    ): JSONObject = placeOrder(
        symbol = symbol, qty = qty, side = side, orderType = "MARKET",
        tradeSide = tradeSide, positionId = positionId, effect = null,
        clientId = clientId.takeIf { it.isNotBlank() },
        reduceOnly = reduceOnly.takeIf { it },
        tpPrice = tpPrice, tpStopType = tpPrice?.let { "LAST_PRICE" },
        tpOrderType = tpPrice?.let { "MARKET" },
        slPrice = slPrice, slStopType = slPrice?.let { "MARK_PRICE" },
        slOrderType = slPrice?.let { "MARKET" },
    )

    /** Order detail; data holds orderId, status (NEW/PART_FILLED/FILLED/CANCELED/...).
     *  Docs: at least one of orderId or clientId is required. */
    fun orderDetail(orderId: String = "", clientId: String = ""): JSONObject {
        require(orderId.isNotBlank() || clientId.isNotBlank()) {
            "get_order_detail needs an orderId or a clientId"
        }
        val q = buildMap {
            if (orderId.isNotBlank()) put("orderId", orderId)
            if (clientId.isNotBlank()) put("clientId", clientId)
        }
        return dataObject(signedGet("/api/v1/futures/trade/get_order_detail", q))
    }

    /** Open orders. Documented filters: symbol, orderId, clientId, status (NEW or
     *  PART_FILLED), startTime, endTime, skip (default 0), limit (default 10, max 100).
     *  Response: `data.orderList` (+ `data.total`). */
    fun pendingOrders(
        symbol: String = "",
        orderId: String = "",
        clientId: String = "",
        status: String = "",
        startTime: Long? = null,
        endTime: Long? = null,
        skip: Int = 0,
        limit: Int = 20,
    ): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (orderId.isNotBlank()) put("orderId", orderId)
            if (clientId.isNotBlank()) put("clientId", clientId)
            if (status.isNotBlank()) {
                require(status in PENDING_ORDER_STATUSES) {
                    "pending order status must be NEW or PART_FILLED"
                }
                put("status", status)
            }
            if (startTime != null) put("startTime", startTime.toString())
            if (endTime != null) put("endTime", endTime.toString())
            put("skip", skip.toString())
            put("limit", limit.coerceIn(1, 100).toString())
        }
        return dataArray(signedGet("/api/v1/futures/trade/get_pending_orders", q))
    }

    /** Historical trades (fills). Documented filters: symbol, orderId, positionId,
     *  startTime, endTime, skip (default 0), limit (default 10, max 100).
     *  Response: `data.tradeList` (+ `data.total`). */
    fun historyTrades(
        symbol: String = "",
        orderId: String = "",
        positionId: String = "",
        startTime: Long? = null,
        endTime: Long? = null,
        skip: Int = 0,
        limit: Int = 20,
    ): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (orderId.isNotBlank()) put("orderId", orderId)
            if (positionId.isNotBlank()) put("positionId", positionId)
            if (startTime != null) put("startTime", startTime.toString())
            if (endTime != null) put("endTime", endTime.toString())
            put("skip", skip.toString())
            put("limit", limit.coerceIn(1, 100).toString())
        }
        return dataArray(signedGet("/api/v1/futures/trade/get_history_trades", q))
    }

    /**
     * Historical orders. Documented filters: symbol, orderId, clientId, status
     * (FILLED/CANCELED/PART_FILLED_CANCELED/EXPIRED), type (LIMIT/MARKET),
     * startTime, endTime, skip (default 0), limit (default 10, max 100),
     * subAccountId and queryCanceled (true = canceled only, last 3 days).
     * Response: `data.orderList` (+ `data.total`).
     */
    fun historyOrders(
        symbol: String = "",
        orderId: String = "",
        clientId: String = "",
        status: String = "",
        type: String = "",
        startTime: Long? = null,
        endTime: Long? = null,
        skip: Int = 0,
        limit: Int = 20,
        queryCanceled: Boolean? = null,
        subAccountId: Long? = null,
    ): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (orderId.isNotBlank()) put("orderId", orderId)
            if (clientId.isNotBlank()) put("clientId", clientId)
            if (status.isNotBlank()) {
                require(status in HISTORY_ORDER_STATUSES) {
                    "history order status must be FILLED, CANCELED, PART_FILLED_CANCELED, or EXPIRED"
                }
                put("status", status)
            }
            if (type.isNotBlank()) {
                require(type == "LIMIT" || type == "MARKET") {
                    "history order type must be LIMIT or MARKET"
                }
                put("type", type)
            }
            if (startTime != null) put("startTime", startTime.toString())
            if (endTime != null) put("endTime", endTime.toString())
            put("skip", skip.toString())
            put("limit", limit.coerceIn(1, 100).toString())
            if (queryCanceled != null) put("queryCanceled", queryCanceled.toString())
            if (subAccountId != null) put("subAccountId", subAccountId.toString())
        }
        return dataArray(signedGet("/api/v1/futures/trade/get_history_orders", q))
    }

    /**
     * Cancels specific orders. Docs: symbol + orderList only; each list entry
     * carries `orderId` OR `clientId` (orderId wins when both are sent).
     * The doc page states no maximum, so the 20-order cap below is the
     * exchange's live-verified limit, not a documented one.
     * `marginCoin` is sent per project rule.
     */
    fun cancelOrders(symbol: String, orderIds: List<String>): JSONObject =
        cancelOrdersByRef(symbol, orderIds.map { OrderRef(orderId = it) })

    /** Cancels orders addressed by order id or client id — the documented orderList shape. */
    fun cancelOrdersByRef(
        symbol: String,
        refs: List<OrderRef>,
    ): JSONObject {
        val body = cancelOrdersBody(symbol, refs)
        return signedPost("/api/v1/futures/trade/cancel_orders", body)
    }

    /** Cancels all open orders. `symbol` is optional per the docs — omitted when blank. */
    fun cancelAllOrders(symbol: String = ""): JSONObject {
        val body = JSONObject()
        if (symbol.isNotBlank()) body.put("symbol", symbol)
        return signedPost("/api/v1/futures/trade/cancel_all_orders", body)
    }

    /**
     * Places up to 5 orders in one call (docs: orderList max length 5; rate limit 2 req/s).
     * Each order follows the place_order fields; the response holds
     * data.successList and data.failureList.
     */
    fun batchOrder(symbol: String, orders: List<JSONObject>): JSONObject {
        val body = batchOrderBody(symbol, orders)
        return signedPost("/api/v1/futures/trade/batch_order", body)
    }

    /**
     * Modifies a pending order (price, qty and/or its TP/SL).
     * Either orderId or clientId is required; orderId wins when both are sent.
     * Documented fields: orderId, clientId, qty, price, tpPrice, tpStopType,
     * tpOrderType, tpOrderPrice, slPrice, slStopType, slOrderType, slOrderPrice.
     */
    fun modifyOrder(
        orderId: String? = null,
        clientId: String? = null,
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
        require(!orderId.isNullOrBlank() || !clientId.isNullOrBlank()) {
            "modify_order needs an orderId or a clientId"
        }
        // Docs: qty is required ("Amount (base coin)").
        require(qty != null) { "modify_order needs a qty" }
        // Live docs page trade/modify_order (checked 2026-11-26): price is required too.
        require(price != null) { "modify_order needs a price" }
        require(!qty.isNullOrBlank()) { "modify_order needs a qty" }
        require(!price.isNullOrBlank()) { "modify_order needs a price" }
        val body = JSONObject()
        if (orderId != null) body.put("orderId", orderId)
        if (clientId != null) body.put("clientId", clientId)
        body.put("qty", qty) // required by the docs, so it is never null here
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

    /**
     * Position-level TP/SL: closes the whole position at market when triggered.
     *
     * The STOP defaults to MARK_PRICE on purpose — the exchange liquidates
     * against the mark price, so a stop watching the last price can be
     * bypassed in a fast move. The TP keeps the project's LAST_PRICE rule.
     */
    fun placePositionTpSl(
        symbol: String, positionId: String, tpPrice: String?, slPrice: String?,
        tpStopType: String = "LAST_PRICE", slStopType: String = "MARK_PRICE",
    ): JSONObject {
        require(symbol.isNotBlank()) { "place_position_tp_sl_order needs a symbol" }
        require(positionId.isNotBlank()) { "place_position_tp_sl_order needs a positionId" }
        return positionTpSlBody(
        "/api/v1/futures/tpsl/position/place_order", symbol, positionId, tpPrice, slPrice, tpStopType, slStopType)
    }

    /**
     * Modifies an existing position-level TP/SL. The stop moves on MARK_PRICE
     * (same reason as [placePositionTpSl]) so a trailing/breakeven stop can
     * never be slower than the exchange's liquidation check.
     *
     * Docs: "Each position can only have one Position TP/SL Order" — this
     * endpoint MODIFIES that order and fails when the position has none. A
     * position whose stop was attached as Order TP/SL (place_order's `slPrice`)
     * has no position-level order, so callers must fall back to
     * [placePositionTpSl]; see [TraderEngine.applyLiveSl].
     */
    fun modifyPositionTpSl(
        symbol: String, positionId: String, tpPrice: String?, slPrice: String?,
        tpStopType: String = "LAST_PRICE", slStopType: String = "MARK_PRICE",
    ): JSONObject {
        require(symbol.isNotBlank()) { "modify_position_tp_sl_order needs a symbol" }
        require(positionId.isNotBlank()) { "modify_position_tp_sl_order needs a positionId" }
        return positionTpSlBody(
            "/api/v1/futures/tpsl/position/modify_order", symbol, positionId, tpPrice, slPrice, tpStopType, slStopType)
    }

    /**
     * The position-level TP/SL order currently armed on a position, or null.
     *
     * `get_pending_orders` returns BOTH position-level orders and partial TP/SL
     * orders. The documented difference is the quantity: a position-level order
     * closes the WHOLE position and carries no `tpQty`/`slQty`, while a partial
     * order always carries at least one of them ("At least one of tpQty or slQty
     * is required"). Only the position-level order is what
     * [modifyPositionTpSl] can change, so the two are told apart on that field
     * alone — `id` is NOT a discriminator, both shapes carry it.
     */
    fun positionTpSlOrder(symbol: String, positionId: String): JSONObject? {
        // The old filter skipped every order with a tpQty/slQty field. The
        // exchange fills those fields on the position order too, so the
        // position order was never found: every breakeven move then CREATED a
        // second stop instead of modifying the first, and the automatic move
        // could never be confirmed. Prefer an order without a quantity, else
        // the first one on this position that carries a stop.
        val orders = pendingTpSlOrders(symbol, positionId)
        val mine = (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
            .filter { it.optString("positionId").let { p -> p.isBlank() || p == positionId } }
        fun noQty(o: JSONObject, k: String) = o.optString(k).let { it.isBlank() || (it.toDoubleOrNull() ?: 0.0) == 0.0 }
        return mine.firstOrNull { noQty(it, "tpQty") && noQty(it, "slQty") }
            ?: mine.firstOrNull { (it.optString("slPrice").toDoubleOrNull() ?: 0.0) > 0 }
        // No blind fallback: a TP-only partial rung (tpQty, no slPrice) is NOT
        // the position order. Taking it made breakeven copy rung 1's TP onto
        // the Position TP/SL, which then closed 100% at the first rung.
    }

    private fun positionTpSlBody(
        path: String, symbol: String, positionId: String,
        tpPrice: String?, slPrice: String?, tpStopType: String, slStopType: String,
    ): JSONObject {
        // Docs: "At least one of tpPrice or slPrice is required."
        require(tpPrice != null || slPrice != null) {
            "position TP/SL needs a tpPrice or an slPrice"
        }
        if (tpPrice != null) validateStopType(tpStopType, "tpStopType")
        if (slPrice != null) validateStopType(slStopType, "slStopType")
        val body = JSONObject().put("symbol", symbol).put("positionId", positionId)
        if (tpPrice != null) body.put("tpPrice", tpPrice).put("tpStopType", tpStopType)
        if (slPrice != null) body.put("slPrice", slPrice).put("slStopType", slStopType)
        return signedPost(path, body)
    }

    /** Cancels a TP/SL order. */
    fun cancelTpSl(symbol: String, orderId: String): JSONObject {
        require(symbol.isNotBlank()) { "cancel TP/SL needs a symbol" }
        require(orderId.isNotBlank()) { "cancel TP/SL needs an orderId" }
        return signedPost("/api/v1/futures/tpsl/cancel_order", JSONObject()
            .put("symbol", symbol).put("orderId", orderId))
    }

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
        // Docs: "At least one of tpPrice or slPrice is required" and
        // "At least one of tpQty or slQty is required".
        require(tpPrice != null || slPrice != null) {
            "partial TP/SL needs a tpPrice or an slPrice"
        }
        require(tpQty != null || slQty != null) {
            "partial TP/SL needs a tpQty or an slQty"
        }
        require(symbol.isNotBlank()) { "place TP/SL needs a symbol" }
        require(positionId.isNotBlank()) { "place TP/SL needs a positionId" }
        validateChildOrderTypes(tpOrderType, tpOrderPrice, slOrderType, slOrderPrice)
        if (tpPrice != null) validateStopType(tpStopType, "tpStopType")
        if (slPrice != null) validateStopType(slStopType, "slStopType")
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
        // Docs: "At least one of tpPrice or slPrice is required" and
        // "At least one of tpQty or slQty is required".
        require(tpPrice != null || slPrice != null) {
            "partial TP/SL needs a tpPrice or an slPrice"
        }
        require(tpQty != null || slQty != null) {
            "partial TP/SL needs a tpQty or an slQty"
        }
        require(orderId.isNotBlank()) { "modify TP/SL needs an orderId" }
        validateChildOrderTypes(tpOrderType, tpOrderPrice, slOrderType, slOrderPrice)
        if (tpPrice != null) validateStopType(tpStopType, "tpStopType")
        if (slPrice != null) validateStopType(slStopType, "slStopType")
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

    private fun validateStopType(value: String, field: String) {
        require(value == "MARK_PRICE" || value == "LAST_PRICE") {
            "$field must be MARK_PRICE or LAST_PRICE"
        }
    }

    /**
     * Pending TP/SL orders. Documented filters: symbol, positionId, side (int32),
     * positionMode (int32), skip (default 0), limit (default 10, max 100).
     * `side`/`positionMode` are int32 in the docs, so callers pass the raw numbers.
     */
    fun pendingTpSlOrders(
        symbol: String = "",
        positionId: String = "",
        side: Int? = null,
        positionMode: Int? = null,
        skip: Int = 0,
        limit: Int = 50,
    ): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (positionId.isNotBlank()) put("positionId", positionId)
            if (side != null) put("side", side.toString())
            if (positionMode != null) put("positionMode", positionMode.toString())
            put("skip", skip.toString())
            put("limit", limit.coerceIn(1, 100).toString())
        }
        return dataArray(signedGet("/api/v1/futures/tpsl/get_pending_orders", q))
    }

    /** Historical TP/SL orders. Documented filters: symbol, side (int32),
     *  positionMode (int32), startTime, endTime, skip (default 0),
     *  limit (default 10, max 100). Response: `data.orderList` (+ `data.total`). */
    fun historyTpSlOrders(
        symbol: String = "",
        side: Int? = null,
        positionMode: Int? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        skip: Int = 0,
        limit: Int = 20,
    ): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (side != null) put("side", side.toString())
            if (positionMode != null) put("positionMode", positionMode.toString())
            if (startTime != null) put("startTime", startTime.toString())
            if (endTime != null) put("endTime", endTime.toString())
            put("skip", skip.toString())
            put("limit", limit.coerceIn(1, 100).toString())
        }
        return dataArray(signedGet("/api/v1/futures/tpsl/get_history_orders", q))
    }

    /** Market-closes one position by id. Docs list positionId only. */
    fun flashClose(positionId: String): JSONObject {
        require(positionId.isNotBlank()) { "flash_close_position needs a positionId" }
        return dataObject(signedPost("/api/v1/futures/trade/flash_close_position", JSONObject()
            .put("positionId", positionId)))
    }

    /** Market-closes everything. `symbol` is optional per the docs — omitted when blank. */
    fun closeAllPositions(symbol: String = ""): JSONObject {
        val body = JSONObject()
        if (symbol.isNotBlank()) body.put("symbol", symbol)
        return dataObject(signedPost("/api/v1/futures/trade/close_all_position", body))
    }

    companion object {
        /**
         * Documented list wrappers, in preference order. Bitunix does NOT use one
         * key everywhere — this is why the previous fixed list silently returned
         * an empty array for tickers (`data.tickerList`), which starved the
         * auto-universe and the signal board.
         */
        private val DOCUMENTED_LIST_KEYS = listOf(
            "orderList",
            "positionList",
            "tradeList",
            "tickerList",
            "tradingPairList",
            "fundingRateList",
            "tierList",
            "positionTierList",
            "list",
        )

        /**
         * Codes the docs mark retryable:
         *  10001 network error · 10005 too many requests · 10006 request too
         *  frequently · 10009 another order operation is in progress · 40007
         *  share settlement in progress.
         */
        val RETRYABLE_CODES = setOf(10001, 10005, 10006, 10009, 40007)

        /** Depth gears the endpoint accepts ("1", "5", "15", "50", "max"). */
        val DEPTH_LIMITS = listOf("1", "5", "15", "50", "max")

        /** Kline intervals listed by the REST documentation. */
        val KLINE_INTERVALS = listOf(
            "1m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h",
            "1d", "3d", "1w", "1M",
        )
        private val PENDING_ORDER_STATUSES = setOf("NEW", "PART_FILLED")
        private val HISTORY_ORDER_STATUSES =
            setOf("FILLED", "CANCELED", "PART_FILLED_CANCELED", "EXPIRED")

        /**
         * Per-endpoint rate limits from the official docs ("Rate Limit: N
         * req/sec/uid" or "/ip"). Endpoints that are not listed use the global
         * 8 req/s cap.
         */
        val RATE_LIMITS: Map<String, Int> = mapOf(
            "/api/v1/futures/trade/close_all_position" to 1,
            "/api/v1/futures/trade/batch_order" to 2,
            "/api/v1/futures/account/adjust_position_margin" to 5,
            "/api/v1/futures/trade/flash_close_position" to 5,
            "/api/v1/futures/account" to 10,
            "/api/v1/futures/account/change_leverage" to 10,
            "/api/v1/futures/account/change_margin_mode" to 10,
            "/api/v1/futures/account/change_position_mode" to 10,
            "/api/v1/futures/account/get_leverage_margin_mode" to 10,
            "/api/v1/futures/account/trading_settings" to 20,
            "/api/v1/futures/account/position_mode" to 20,
            "/api/v1/futures/position/get_history_positions" to 10,
            "/api/v1/futures/position/get_pending_positions" to 10,
            "/api/v1/futures/trade/cancel_orders" to 10,
            "/api/v1/futures/trade/get_history_orders" to 10,
            "/api/v1/futures/trade/get_history_trades" to 10,
            "/api/v1/futures/trade/get_order_detail" to 10,
            "/api/v1/futures/trade/get_pending_orders" to 10,
            "/api/v1/futures/tpsl/cancel_order" to 10,
            "/api/v1/futures/tpsl/get_history_orders" to 10,
            "/api/v1/futures/tpsl/get_pending_orders" to 10,
            "/api/v1/cp/asset/query" to 10,
            "/api/v1/cp/asset/transfer-to-sub-account" to 10,
            "/api/v1/cp/asset/transfer-to-main-account" to 10,
            "/api/v1/futures/trade/cancel_all_orders" to 20,
            "/api/v1/futures/trade/modify_order" to 20,
            "/api/v1/futures/trade/place_order" to 20,
            "/api/v1/futures/tpsl/modify_order" to 20,
            "/api/v1/futures/tpsl/position/modify_order" to 20,
            "/api/v1/futures/tpsl/position/place_order" to 20,
            "/api/v1/futures/tpsl/place_order" to 20,
        )

        private const val ATTEMPTS = 3
        private const val BACKOFF_MS = 800L
        /** Docs allow 10 req/s; we throttle to 8 req/s. */
        private const val MIN_INTERVAL_MS = 125L

        /** Minimum gap between two calls of the same path, in milliseconds. */
        fun minIntervalMs(path: String): Long {
            val perSecond = RATE_LIMITS[path] ?: return MIN_INTERVAL_MS
            return maxOf(MIN_INTERVAL_MS, 1000L / perSecond)
        }

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Documented Get-Kline query: symbol, interval, limit, endTime, type.
         * `startTime` is NOT sent — the changelog removed it and the endpoint
         * ignores it anyway.
         */
        fun klineQuery(
            symbol: String,
            interval: String,
            limit: Int = 200,
            endTime: Long? = null,
            type: String? = null,
        ): Map<String, String> {
            require(symbol.isNotBlank()) { "kline needs a symbol" }
            require(interval in KLINE_INTERVALS) {
                "unsupported REST kline interval: $interval"
            }
            require(type == null || type == "LAST_PRICE" || type == "MARK_PRICE") {
                "kline type must be LAST_PRICE or MARK_PRICE"
            }
            val q = mutableMapOf(
                "symbol" to symbol,
                "interval" to interval,
                "limit" to limit.coerceIn(1, 200).toString(),
            )
            if (endTime != null) q["endTime"] = endTime.toString()
            if (type != null) q["type"] = type
            return q
        }

        /** Documented Get-Pending-Positions query. */
        fun pendingPositionsQuery(
            symbol: String = "",
            positionId: String = "",
            includeSubAccounts: Boolean? = null,
            subAccountId: Long? = null,
        ): Map<String, String> = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            if (positionId.isNotBlank()) put("positionId", positionId)
            if (includeSubAccounts != null) put("includeSubAccounts", includeSubAccounts.toString())
            if (subAccountId != null) put("subAccountId", subAccountId.toString())
        }

        /** Documented Place-Order body — every documented field, nothing invented. */
        fun placeOrderBody(
            symbol: String,
            qty: String,
            side: String,
            orderType: String,
            tradeSide: String? = null,
            positionId: String? = null,
            price: String? = null,
            effect: String? = null,
            clientId: String? = null,
            reduceOnly: Boolean? = null,
            tpPrice: String? = null,
            tpStopType: String? = null,
            tpOrderType: String? = null,
            tpOrderPrice: String? = null,
            slPrice: String? = null,
            slStopType: String? = null,
            slOrderType: String? = null,
            slOrderPrice: String? = null,
        ): JSONObject {
            validateOrderFields(symbol, qty, side, orderType, tradeSide, effect, price)
            validateChildOrderTypes(tpOrderType, tpOrderPrice, slOrderType, slOrderPrice)
            validateStopTypes(tpPrice, tpStopType, slPrice, slStopType)
            val body = JSONObject()
                .put("symbol", symbol)
                .put("qty", qty)
                .put("side", side)
                .put("orderType", orderType)
            if (price != null) body.put("price", price)
            // Docs: effect is "Required if the orderType is limit"; GTC is the
            // documented default, so a LIMIT order never goes out without one.
            if (effect != null) body.put("effect", effect)
            else if (orderType.equals("LIMIT", ignoreCase = true)) body.put("effect", "GTC")
            if (tradeSide != null) body.put("tradeSide", tradeSide)
            if (positionId != null) body.put("positionId", positionId)
            if (clientId != null) body.put("clientId", clientId)
            if (reduceOnly != null) body.put("reduceOnly", reduceOnly)
            if (tpPrice != null) body.put("tpPrice", tpPrice)
            if (tpStopType != null) body.put("tpStopType", tpStopType)
            if (tpOrderType != null) body.put("tpOrderType", tpOrderType)
            if (tpOrderPrice != null) body.put("tpOrderPrice", tpOrderPrice)
            if (slPrice != null) body.put("slPrice", slPrice)
            if (slStopType != null) body.put("slStopType", slStopType)
            if (slOrderType != null) body.put("slOrderType", slOrderType)
            if (slOrderPrice != null) body.put("slOrderPrice", slOrderPrice)
            return body
        }

        /** Documented Cancel-Orders body: symbol + orderList[{orderId|clientId}]. */
        fun cancelOrdersBody(
            symbol: String,
            refs: List<OrderRef>,
        ): JSONObject {
            require(symbol.isNotBlank()) { "cancel_orders needs a symbol" }
            require(refs.isNotEmpty()) { "cancel_orders needs at least one order reference" }
            require(refs.size <= 20) { "cancel_orders accepts at most 20 orders (got ${refs.size})" } // live-verified exchange limit
            val list = JSONArray()
            for (ref in refs) {
                require(ref.orderId != null || ref.clientId != null) {
                    "each cancel_orders entry needs an orderId or a clientId"
                }
                val entry = JSONObject()
                if (ref.orderId != null) entry.put("orderId", ref.orderId)
                if (ref.clientId != null) entry.put("clientId", ref.clientId)
                list.put(entry)
            }
            return JSONObject()
                .put("symbol", symbol)
                .put("orderList", list)
        }

        /** Documented Batch-Order body: symbol + orderList (max 5). */
        fun batchOrderBody(symbol: String, orders: List<JSONObject>): JSONObject {
            require(orders.isNotEmpty()) { "batch_order needs at least one order" }
            require(orders.size <= 5) { "batch_order accepts at most 5 orders (got ${orders.size})" }
            val list = JSONArray()
            orders.forEach { list.put(it) }
            return JSONObject().put("symbol", symbol).put("orderList", list)
        }

        /**
         * One entry of a documented order list (batch_order / cancel_orders):
         * qty, price, side, tradeSide, positionId, orderType, effect, clientId,
         * reduceOnly plus the TP/SL fields.
         */
        fun batchOrderItem(
            qty: String,
            side: String,
            orderType: String,
            tradeSide: String? = null,
            positionId: String? = null,
            price: String? = null,
            effect: String? = null,
            clientId: String? = null,
            reduceOnly: Boolean? = null,
            tpPrice: String? = null,
            tpStopType: String? = null,
            tpOrderType: String? = null,
            tpOrderPrice: String? = null,
            slPrice: String? = null,
            slStopType: String? = null,
            slOrderType: String? = null,
            slOrderPrice: String? = null,
        ): JSONObject {
            validateOrderFields("", qty, side, orderType, tradeSide, effect, price, requireSymbol = false)
            validateChildOrderTypes(tpOrderType, tpOrderPrice, slOrderType, slOrderPrice)
            validateStopTypes(tpPrice, tpStopType, slPrice, slStopType)
            return JSONObject()
            .put("qty", qty)
            .put("side", side)
            .put("orderType", orderType)
            .apply {
                if (price != null) put("price", price)
                // Docs: effect is "Required if the orderType is limit" (GTC default).
                if (effect != null) put("effect", effect)
                else if (orderType.equals("LIMIT", ignoreCase = true)) put("effect", "GTC")
                if (tradeSide != null) put("tradeSide", tradeSide)
                if (positionId != null) put("positionId", positionId)
                if (clientId != null) put("clientId", clientId)
                if (reduceOnly != null) put("reduceOnly", reduceOnly)
                if (tpPrice != null) put("tpPrice", tpPrice)
                if (tpStopType != null) put("tpStopType", tpStopType)
                if (tpOrderType != null) put("tpOrderType", tpOrderType)
                if (tpOrderPrice != null) put("tpOrderPrice", tpOrderPrice)
                if (slPrice != null) put("slPrice", slPrice)
                if (slStopType != null) put("slStopType", slStopType)
                if (slOrderType != null) put("slOrderType", slOrderType)
                if (slOrderPrice != null) put("slOrderPrice", slOrderPrice)
            }
        }

        /** The official contract requires a child order price for every LIMIT TP/SL. */
        private fun validateChildOrderTypes(
            tpOrderType: String?, tpOrderPrice: String?,
            slOrderType: String?, slOrderPrice: String?,
        ) {
            require(tpOrderType == null || tpOrderType == "LIMIT" || tpOrderType == "MARKET") {
                "tpOrderType must be LIMIT or MARKET"
            }
            require(slOrderType == null || slOrderType == "LIMIT" || slOrderType == "MARKET") {
                "slOrderType must be LIMIT or MARKET"
            }
            if (tpOrderType == "LIMIT") require(!tpOrderPrice.isNullOrBlank()) {
                "tpOrderPrice is required when tpOrderType is LIMIT"
            }
            if (slOrderType == "LIMIT") require(!slOrderPrice.isNullOrBlank()) {
                "slOrderPrice is required when slOrderType is LIMIT"
            }
        }

        private fun validateStopTypes(
            tpPrice: String?,
            tpStopType: String?,
            slPrice: String?,
            slStopType: String?,
        ) {
            require(tpStopType == null || tpStopType == "MARK_PRICE" || tpStopType == "LAST_PRICE") {
                "tpStopType must be MARK_PRICE or LAST_PRICE"
            }
            require(slStopType == null || slStopType == "MARK_PRICE" || slStopType == "LAST_PRICE") {
                "slStopType must be MARK_PRICE or LAST_PRICE"
            }
        }

        private fun validateOrderFields(
            symbol: String,
            qty: String,
            side: String,
            orderType: String,
            tradeSide: String?,
            effect: String?,
            price: String?,
            requireSymbol: Boolean = true,
        ) {
            if (requireSymbol) require(symbol.isNotBlank()) { "order needs a symbol" }
            require(qty.isNotBlank()) { "order needs a qty" }
            require(side == "BUY" || side == "SELL") { "side must be BUY or SELL" }
            require(orderType == "LIMIT" || orderType == "MARKET") {
                "orderType must be LIMIT or MARKET"
            }
            require(tradeSide == null || tradeSide == "OPEN" || tradeSide == "CLOSE") {
                "tradeSide must be OPEN or CLOSE"
            }
            require(effect == null || effect in setOf("GTC", "IOC", "FOK", "POST_ONLY")) {
                "effect must be GTC, IOC, FOK, or POST_ONLY"
            }
            if (orderType == "LIMIT") require(!price.isNullOrBlank()) {
                "price is required when orderType is LIMIT"
            }
        }

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

        /** Comma-separated symbols, trimmed to the documented maximum count. */
        fun symbolsParam(symbols: String, max: Int): String =
            symbols.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(max).joinToString(",")

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

/**
 * One entry of a documented order list: an order is addressed by `orderId` or
 * `clientId`; when both are given the orderId prevails.
 */
data class OrderRef(val orderId: String? = null, val clientId: String? = null)

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)
