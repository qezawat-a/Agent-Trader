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
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            throttle()
            try {
                return callOnce(method, path, query, body, signed)
            } catch (e: RetryableError) {
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(BACKOFF_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Request failed: $method $path")
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
            val queryParams = query.toSortedMap().entries.joinToString("") { it.key + it.value }
            val digest = sha256Hex(nonce + timestamp + apiKey + queryParams + (body ?: ""))
            val sign = sha256Hex(digest + secretKey)
            conn.setRequestProperty("api-key", apiKey)
            conn.setRequestProperty("nonce", nonce)
            conn.setRequestProperty("timestamp", timestamp)
            conn.setRequestProperty("sign", sign)
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
    fun dataArray(json: JSONObject): JSONArray = json.optJSONArray("data") ?: JSONArray()

    // ---- Market data (public) ----

    /** Kline history. Returns candles oldest first; the forming candle is the last row. */
    fun klines(symbol: String, interval: String, limit: Int = 200): List<Candle> {
        val json = publicGet("/api/v1/futures/market/kline", mapOf(
            "symbol" to symbol, "interval" to interval, "limit" to limit.toString(),
        ))
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

    /** 24h tickers (lastPrice, quoteVol, open, high, low). */
    fun tickers(symbols: String = ""): JSONArray {
        val q = if (symbols.isBlank()) emptyMap() else mapOf("symbols" to symbols)
        return dataArray(publicGet("/api/v1/futures/market/tickers", q))
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

    // ---- Positions (signed) ----

    fun pendingPositions(symbol: String = ""): JSONArray {
        val q = if (symbol.isBlank()) emptyMap() else mapOf("symbol" to symbol)
        return dataArray(signedGet("/api/v1/futures/position/get_pending_positions", q))
    }

    fun historyPositions(symbol: String = "", limit: Int = 10): JSONArray {
        val q = buildMap {
            if (symbol.isNotBlank()) put("symbol", symbol)
            put("limit", limit.toString())
        }
        return dataArray(signedGet("/api/v1/futures/position/get_history_positions", q))
    }

    // ---- Trade (signed) ----

    /**
     * Places a market order.
     *  - marginCoin is REQUIRED by the exchange even though docs list it as optional.
     *  - side names the POSITION: BUY opens long, SELL opens short.
     *  - tradeSide ("OPEN"/"CLOSE") is only sent in hedge mode; pass null in one-way mode.
     *  - tpPrice/slPrice attach a position-level TP/SL on fill; stop types may be
     *    MARK_PRICE or LAST_PRICE (default MARK_PRICE).
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
        if (tpPrice != null) body.put("tpPrice", tpPrice).put("tpStopType", "MARK_PRICE")
        if (slPrice != null) body.put("slPrice", slPrice).put("slStopType", "MARK_PRICE")
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
