package com.codeassist.agenttrader.bitunix

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bitunix futures WebSocket client.
 *
 * Official WS endpoints (docs "WebSocket" page, verified live):
 *   public  market channels : wss://fapi.bitunix.com/public/
 *   private account channels: wss://fapi.bitunix.com/private/
 * The old single fstream.bitunix.com/ws host no longer resolves.
 *
 * Limits from the docs: at most 5 messages per second per connection (ping and subscribe
 * count; exceeding it disconnects and can block the IP), at most 300 channel
 * subscriptions per connection. All outgoing frames go through a 250ms pump (4 msg/s);
 * an application-level ping with a Unix-seconds timestamp goes out every 3s.
 */
class BitunixWs(
    private val apiKey: String,
    private val secretKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen(privateChannel: Boolean)
        fun onChannel(ch: String, symbol: String?, data: JSONObject)
        fun onError(message: String)
        fun onClosed(privateChannel: Boolean, reason: String)
    }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    private val pump = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bitunix-ws-pump").apply { isDaemon = true }
    }
    private var pumpsStarted = false

    private fun ensurePumps() {
        synchronized(this) {
            if (!pumpsStarted) {
                pumpsStarted = true
                startPumps()
            }
        }
    }

    private class SocketState(val privateChannel: Boolean) {
        val socket = AtomicReference<WebSocket?>(null)
        val channels = Collections.synchronizedSet(LinkedHashSet<Pair<String, String?>>())
        val queue = ConcurrentLinkedQueue<String>()
        val ready = AtomicBoolean(false)
        val manualClose = AtomicBoolean(false)
        val attempt = AtomicLong(0)
    }

    private val pubState = SocketState(privateChannel = false)
    private val privState = SocketState(privateChannel = true)

    fun connectPublic() = connect(pubState)

    /** Opens the private socket. Subscriptions are sent only after the login reply succeeds. */
    fun connectPrivate() = connect(privState)

    private fun connect(state: SocketState) {
        ensurePumps()
        state.manualClose.set(false)
        state.ready.set(false)
        state.socket.set(
            client.newWebSocket(
                Request.Builder().url(if (state.privateChannel) WS_PRIVATE_URL else WS_PUBLIC_URL).build(),
                socketListener(state),
            )
        )
    }

    /** Reconnects with exponential backoff (1s → 30s cap) on the pump thread. */
    private fun scheduleReconnect(state: SocketState) {
        val n = state.attempt.incrementAndGet()
        val delay = (1L shl n.toInt().coerceAtMost(5)).coerceAtMost(30L)
        pump.schedule({ if (!state.manualClose.get()) connect(state) }, delay, TimeUnit.SECONDS)
    }
    private fun startPumps() {
        // 250ms send pump keeps outgoing traffic under the 5 msg/s limit.
        pump.scheduleWithFixedDelay({
            for (state in listOf(pubState, privState)) {
                val ws = state.socket.get()
                if (ws != null && state.ready.get()) {
                    val frame = state.queue.poll()
                    if (frame != null) ws.send(frame)
                }
            }
        }, 250L, 250L, TimeUnit.MILLISECONDS)
        // Application-level ping every 3s (official client heartbeat): the server
        // drops silent sessions.
        pump.scheduleWithFixedDelay({
            for (state in listOf(pubState, privState)) {
                val ws = state.socket.get()
                if (ws != null && state.ready.get()) {
                    ws.send(pingFrame(System.currentTimeMillis()))
                }
            }
        }, 3L, 3L, TimeUnit.SECONDS)
    }

    /** Subscribe a public market channel, e.g. ("market_kline_15min", "BTCUSDT"). */
    fun subscribePublic(ch: String, symbol: String) {
        pubState.channels.add(ch to symbol)
        send(pubState, "subscribe", ch, symbol)
    }

    /** Subscribes several public channels in ONE frame (the docs allow an args list). */
    fun subscribePublicBatch(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        items.forEach { pubState.channels.add(it) }
        val args = JSONArray()
        items.forEach { (ch, sym) -> args.put(JSONObject().put("ch", ch).put("symbol", sym)) }
        pubState.queue.add(JSONObject().put("op", "subscribe").put("args", args).toString())
    }

    fun unsubscribePublic(ch: String, symbol: String) {
        pubState.channels.remove(ch to symbol)
        send(pubState, "unsubscribe", ch, symbol)
    }

    /** Private channels: "order", "position", "tpsl", "balance" (no symbol). */
    fun subscribePrivate(ch: String) {
        privState.channels.add(ch to null)
        send(privState, "subscribe", ch, null)
    }

    /**
     * Subscribes to several private channels in ONE frame, the way the official
     * client does ("balance", "position", "order", "tpsl").
     */
    fun subscribePrivateAll(channels: List<String>) {
        if (channels.isEmpty()) return
        channels.forEach { privState.channels.add(it to null) }
        val args = JSONArray()
        channels.forEach { args.put(JSONObject().put("ch", it)) }
        privState.queue.add(JSONObject().put("op", "subscribe").put("args", args).toString())
    }

    fun close() {
        pubState.manualClose.set(true)
        privState.manualClose.set(true)
        pubState.socket.get()?.close(1000, "bye")
        privState.socket.get()?.close(1000, "bye")
        pump.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    private fun socketListener(state: SocketState) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (state.privateChannel) {
                // attempt resets only after a SUCCESSFUL login (see "login" branch) —
                // otherwise a failing-login retry loop would retry at a fixed ~2s
                // interval instead of backing off.
                login(webSocket)
            } else {
                state.attempt.set(0)
                state.ready.set(true)
                state.socket.set(webSocket)
                listener.onOpen(false)
                resubscribe(state)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (e: Exception) {
                listener.onError("Bad frame: ${text.take(120)}")
                return
            }
            when (msg.optString("op")) {
                "pong", "" -> Unit
                "ping" -> webSocket.send(JSONObject().put("op", "pong").put("pong", msg.optLong("ping")).toString())
                "login" -> {
                    if (msg.optInt("code", -1) == 0) {
                        state.attempt.set(0)
                        state.ready.set(true)
                        state.socket.set(webSocket)
                        listener.onOpen(true)
                        resubscribe(state)
                    } else {
                        val reason = msg.optString("msg").ifBlank { msg.optString("message") }
                        listener.onError("Login failed (code ${msg.optInt("code", -1)}): ${reason.ifBlank { "no reason from server" }}")
                        // Without this the socket sits connected but dead: the pump never
                        // sends on it (ready=false) and nothing ever retries. Close and
                        // reconnect with backoff — after an abnormal drop the server can
                        // keep the previous session's slot briefly, so the next attempt
                        // may also fail; the 1s→30s backoff rides that out.
                        state.ready.set(false)
                        webSocket.close(1000, "login failed")
                        if (!state.manualClose.get()) scheduleReconnect(state)
                    }
                }
                else -> {
                    val ch = msg.optString("ch")
                    if (ch.isNotEmpty() && msg.has("data")) {
                        val data = msg.opt("data")
                        when (data) {
                            is JSONObject -> listener.onChannel(ch, msg.optString("symbol").ifEmpty { null }, data)
                            is JSONArray -> {
                                // "tickers" and "trade" push data as an ARRAY of objects; each
                                // element carries its own symbol ("s"). Kline docs say the data is
                                // a list but the examples show an object — deliver every element.
                                for (i in 0 until data.length()) {
                                    val obj = data.optJSONObject(i) ?: continue
                                    val sym = obj.optString("s").ifBlank { msg.optString("symbol").ifEmpty { "" } }
                                    listener.onChannel(ch, sym.ifBlank { null }, obj)
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            state.ready.set(false)
            listener.onError(wsErrorText(t.message ?: t.javaClass.simpleName))
            // onClosed is NOT emitted here: the error line already tells the user what
            // happened, and a second "closed: failure" line only doubles the noise.
            if (!state.manualClose.get()) scheduleReconnect(state)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            state.ready.set(false)
            listener.onClosed(state.privateChannel, "closed $code $reason")
        }
    }

    /** Re-subscribes every channel booked on this socket after a (re)connect. */
    private fun resubscribe(state: SocketState) {
        val snapshot = synchronized(state.channels) { state.channels.toList() }
        snapshot.forEach { (ch, sym) -> send(state, "subscribe", ch, sym) }
    }

    private fun login(ws: WebSocket) {
        val nonce = UUID.randomUUID().toString().replace("-", "").take(32)
        val timestamp = System.currentTimeMillis() / 1000
        // Private WS login: digest = SHA256(nonce + timestamp + apiKey); sign = SHA256(digest + secretKey)
        val digest = sha256Hex(nonce + timestamp + apiKey)
        val sign = sha256Hex(digest + secretKey)
        val args = JSONArray().put(
            JSONObject()
                .put("apiKey", apiKey)
                .put("timestamp", timestamp)
                .put("nonce", nonce)
                .put("sign", sign),
        )
        ws.send(JSONObject().put("op", "login").put("args", args).toString())
    }

    /** Queues a request; the 250ms pump sends it once the socket is ready. */
    private fun send(state: SocketState, op: String, ch: String, symbol: String?) {
        val arg = JSONObject().put("ch", ch)
        if (symbol != null) arg.put("symbol", symbol)
        val args = JSONArray().put(arg)
        state.queue.add(JSONObject().put("op", op).put("args", args).toString())
    }

    companion object {

        /**
         * WS kline channel name for a REST interval, per the docs:
         * 1m->market_kline_1min, 1h->market_kline_60min, 1d->market_kline_1day,
         * 1w->market_kline_1week, 1M->market_kline_1month; mark_kline_* for MARK_PRICE.
         */
        fun klineChannel(interval: String, markPrice: Boolean = false): String {
            val kind = if (markPrice) "mark_kline" else "market_kline"
            val suffix = when (interval) {
                "1m" -> "1min"; "3m" -> "3min"; "5m" -> "5min"; "15m" -> "15min"; "30m" -> "30min"
                "1h" -> "60min"; "2h" -> "2h"; "4h" -> "4h"; "6h" -> "6h"; "8h" -> "8h"; "12h" -> "12h"
                "1d" -> "1day"; "3d" -> "3day"; "1w" -> "1week"; "1M" -> "1month"
                else -> interval
            }
            return "${kind}_$suffix"
        }
        /** Docs: "Main Domain, Public channel". */
        const val WS_PUBLIC_URL = "wss://fapi.bitunix.com/public/"

        /** Docs: "Main Domain, Private channel" (login required). */
        const val WS_PRIVATE_URL = "wss://fapi.bitunix.com/private/"

        /** Docs: ping carries a Unix timestamp in SECONDS (not milliseconds). */
        fun pingFrame(nowMs: Long): String =
            JSONObject().put("op", "ping").put("ping", nowMs / 1000).toString()

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Turns a low-level socket error into a short line the user can act on.
 * "Unable to resolve host ..." is what Android reports when the WS domain cannot
 * be reached at all — historically also the symptom of a wrong host.
 */
internal fun wsErrorText(raw: String?): String {
    val m = raw?.trim().orEmpty()
    return when {
        m.contains("Unable to resolve host", ignoreCase = true) ||
            m.contains("No address associated with hostname", ignoreCase = true) ->
            "WS unreachable (DNS) — check this device's internet connection"
        m.contains("timeout", ignoreCase = true) || m.contains("timed out", ignoreCase = true) ->
            "WS timeout — reconnecting"
        // Server/network closed the TCP stream mid-session (EOFException, reset,
        // broken pipe...). Routine: the client reconnects automatically with backoff.
        m.contains("EOF", ignoreCase = true) ||
            m.contains("Connection reset", ignoreCase = true) ||
            m.contains("broken pipe", ignoreCase = true) ||
            m.contains("Socket closed", ignoreCase = true) ||
            m.contains("Software caused connection abort", ignoreCase = true) ->
            "WS closed by server — reconnecting"
        m.isEmpty() -> "WS connection failed"
        else -> m
    }
}
