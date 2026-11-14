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
 * Limits from the docs: at most 5 messages per second per connection (ping and
 * subscribe count; exceeding it disconnects and can block the IP), at most 300
 * channel subscriptions per connection. All outgoing frames go through
 * [sendFrame] (min 210ms apart, ~4.7 msg/s) plus a 250ms pump; an
 * application-level ping with a Unix-seconds timestamp goes out every 3s.
 *
 * Channel names are the documented ones (see the CH_* constants). Push shapes
 * differ per channel and were verified live: "tickers" pushes an ARRAY whose
 * elements carry the symbol in `s`, "trade" pushes an ARRAY whose `s` is the
 * trade side while the symbol sits in the frame header, and "price", "ticker",
 * the depth channels and the kline channels push an OBJECT.
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
        val lastSendMs = AtomicLong(0)

        /**
         * Channels booked on the PREVIOUS connection. Taken when the socket is
         * opened, so the re-subscribe never duplicates what the listener
         * subscribes on open.
         */
        @Volatile
        var pendingResubscribe: List<Pair<String, String?>> = emptyList()
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
        // Snapshot the channels booked on the previous connection: only those
        // need to be re-subscribed, everything else is booked by the listener
        // when the socket opens.
        state.pendingResubscribe = synchronized(state.channels) { state.channels.toList() }
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
                    if (frame != null) sendFrame(state, ws, frame)
                }
            }
        }, 250L, 250L, TimeUnit.MILLISECONDS)
        // Application-level ping every 3s (official client heartbeat): the server
        // drops silent sessions. The ping carries a Unix-seconds timestamp.
        pump.scheduleWithFixedDelay({
            for (state in listOf(pubState, privState)) {
                val ws = state.socket.get()
                if (ws != null && state.ready.get()) {
                    sendFrame(state, ws, pingFrame(System.currentTimeMillis()))
                }
            }
        }, 3L, 3L, TimeUnit.SECONDS)
    }

    /**
     * Sends one frame, respecting the documented 5 messages/second ceiling.
     * Every outgoing frame — subscribe, unsubscribe, login and ping — goes
     * through here, so the cap can never be broken by a burst of subscribes.
     */
    private fun sendFrame(state: SocketState, ws: WebSocket, text: String) {
        val wait = state.lastSendMs.get() + MIN_SEND_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        state.lastSendMs.set(System.currentTimeMillis())
        ws.send(text)
    }

    /** Subscribe a public market channel, e.g. ("market_kline_15min", "BTCUSDT"). */
    fun subscribePublic(ch: String, symbol: String) {
        if (book(pubState, ch to symbol) != Book.NEW) return
        send(pubState, "subscribe", ch, symbol)
    }

    /** Subscribes several public channels in ONE frame (the docs allow an args list). */
    fun subscribePublicBatch(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val accepted = items.filter { book(pubState, it) == Book.NEW }
        if (accepted.isEmpty()) return
        val args = JSONArray()
        accepted.forEach { (ch, sym) -> args.put(JSONObject().put("ch", ch).put("symbol", sym)) }
        pubState.queue.add(JSONObject().put("op", "subscribe").put("args", args).toString())
    }

    fun unsubscribePublic(ch: String, symbol: String) {
        pubState.channels.remove(ch to symbol)
        send(pubState, "unsubscribe", ch, symbol)
    }

    /** Private channels: "order", "position", "tpsl", "balance" (no symbol). */
    fun subscribePrivate(ch: String) {
        if (book(privState, ch to null) != Book.NEW) return
        send(privState, "subscribe", ch, null)
    }

    /**
     * Subscribes to several private channels in ONE frame, the way the official
     * client does ("balance", "position", "order", "tpsl").
     */
    fun subscribePrivateAll(channels: List<String>) {
        if (channels.isEmpty()) return
        val accepted = channels.filter { book(privState, it to null) == Book.NEW }
        if (accepted.isEmpty()) return
        val args = JSONArray()
        accepted.forEach { args.put(JSONObject().put("ch", it)) }
        privState.queue.add(JSONObject().put("op", "subscribe").put("args", args).toString())
    }

    /**
     * Books a channel subscription. The docs cap a connection at 300 channel
     * subscriptions, so anything beyond that is refused instead of silently
     * getting the socket disconnected.
     */
    private fun book(state: SocketState, entry: Pair<String, String?>): Book {
        synchronized(state.channels) {
            if (state.channels.contains(entry)) return Book.EXISTING
            if (state.channels.size >= MAX_SUBSCRIPTIONS) {
                listener.onError(
                    "WS subscription limit reached (${MAX_SUBSCRIPTIONS} channels per connection)",
                )
                return Book.REFUSED
            }
            state.channels.add(entry)
            return Book.NEW
        }
    }

    /** Outcome of booking a channel subscription. */
    private enum class Book { NEW, EXISTING, REFUSED }

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
            val op = msg.optString("op")
            when {
                // The docs' ping response comes back as op "ping" carrying `pong`
                // ({"op":"ping","pong":...,"ping":...}) — it is the server's reply to
                // our own heartbeat, not a request. Replying again would double the
                // outgoing rate for nothing, so both "ping" and "pong" are acks.
                // A server-initiated ping (op "ping" with a `ping` field and no
                // `pong`) is answered with the documented response shape.
                op == "ping" && msg.has("ping") && !msg.has("pong") -> {
                    // Answer through the send queue so the reader thread never
                    // blocks and the 5 msg/s ceiling still holds.
                    state.queue.add(
                        JSONObject().put("op", "ping").put("pong", msg.optLong("ping")).toString(),
                    )
                }
                isHeartbeat(op) -> Unit
                op == "connect" -> {
                    // The server acks the handshake with {"op":"connect","data":{"result":true}}.
                    val data = msg.optJSONObject("data")
                    if (data != null && !data.optBoolean("result", true)) {
                        listener.onError("WS connect rejected: ${loginReason(msg) ?: "server said no"}")
                        webSocket.cancel()
                    }
                }
                op == "login" -> {
                    // The login reply carries no top-level `code`; the verdict is in
                    // `data.result` (live failure frame:
                    // {"op":"login","data":{"result":false,"msg":"invalid params..."}}).
                    // The official demo treats any op "login" frame as success, so only
                    // an explicit failure flag rejects it. Checking `code == 0` here
                    // rejected EVERY successful login and looped the reconnect forever.
                    if (isLoginSuccess(msg)) {
                        state.attempt.set(0)
                        state.ready.set(true)
                        state.socket.set(webSocket)
                        listener.onOpen(true)
                        resubscribe(state)
                    } else {
                        listener.onError("Login failed: ${loginReason(msg) ?: "rejected by server"}")
                        // Cancel (not close): an abrupt teardown fires onFailure, which is
                        // the single reconnect path — a graceful close would wait for the
                        // server's ack (can hang) and onClosed would need its own reconnect.
                        webSocket.cancel()
                    }
                }
                else -> {
                    val ch = msg.optString("ch")
                    if (ch.isNotEmpty() && msg.has("data")) {
                        val data = msg.opt("data")
                        when (data) {
                            is JSONObject -> listener.onChannel(ch, msg.optString("symbol").ifEmpty { null }, data)
                            is JSONArray -> {
                                // "tickers" and "trade" push data as an ARRAY of objects.
                                // Their element shapes differ: on "tickers" the element
                                // field `s` IS the symbol, on "trade" it is the trade
                                // side ("buy"/"sell") and the symbol sits in the frame
                                // header — verified live 2026-11.
                                val frameSymbol = msg.optString("symbol").ifEmpty { "" }
                                for (i in 0 until data.length()) {
                                    val obj = data.optJSONObject(i) ?: continue
                                    val sym = symbolOfPush(ch, obj, frameSymbol)
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
            // A graceful close was previously a dead end: only onFailure reconnected,
            // so a polite server close (maintenance, load-balancing) killed the feed
            // forever. Reconnect here too — manual stops are filtered by manualClose.
            if (!state.manualClose.get()) scheduleReconnect(state)
        }
    }

    /**
     * Re-subscribes the channels booked on the PREVIOUS connection. The
     * subscriptions go out in a few batched frames (the docs allow an args
     * list) instead of one frame per channel — 300 channels would otherwise
     * take over a minute at the documented message rate.
     */
    private fun resubscribe(state: SocketState) {
        val snapshot = state.pendingResubscribe
        state.pendingResubscribe = emptyList()
        if (snapshot.isEmpty()) return
        snapshot.chunked(RESUBSCRIBE_CHUNK).forEach { chunk ->
            val args = JSONArray()
            chunk.forEach { (ch, sym) ->
                val arg = JSONObject().put("ch", ch)
                if (sym != null) arg.put("symbol", sym)
                args.put(arg)
            }
            state.queue.add(JSONObject().put("op", "subscribe").put("args", args).toString())
        }
    }

    private fun login(ws: WebSocket) {
        val nonce = UUID.randomUUID().toString().replace("-", "").take(32)
        // Seconds — same unit the official WS docs expect (timestamp: Int, seconds format of Unix timestamp);
        // milliseconds made every private login fail with "code -1: no reason".
        val timestamp = System.currentTimeMillis() / 1000
        // Private WS login: digest = SHA256(nonce + timestamp + apiKey); sign = SHA256(digest + secretKey)
        // (official demo: open_api_ws_sign.go — same formula, 32-char random nonce, seconds).
        val digest = sha256Hex(nonce + timestamp + apiKey)
        val sign = sha256Hex(digest + secretKey)
        val args = JSONArray().put(
            JSONObject()
                .put("apiKey", apiKey)
                .put("timestamp", timestamp)
                .put("nonce", nonce)
                .put("sign", sign),
        )
        sendFrame(privState, ws, JSONObject().put("op", "login").put("args", args).toString())
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
         * Every public channel the docs list. The private ones are on the
         * private socket (login required).
         */
        const val CH_PRICE = "price"
        const val CH_TICKER = "ticker"
        const val CH_TICKERS = "tickers"
        const val CH_TRADE = "trade"
        const val CH_DEPTH_BOOKS = "depth_books"
        const val CH_DEPTH_BOOK1 = "depth_book1"
        const val CH_DEPTH_BOOK5 = "depth_book5"
        const val CH_DEPTH_BOOK15 = "depth_book15"

        /** Private (account) channels — pushed after a successful login. */
        const val CH_BALANCE = "balance"
        const val CH_ORDER = "order"
        const val CH_POSITION = "position"
        const val CH_TPSL = "tpsl"

        /** All documented public channels. */
        val PUBLIC_CHANNELS = listOf(
            CH_PRICE, CH_TICKER, CH_TICKERS, CH_TRADE,
            CH_DEPTH_BOOKS, CH_DEPTH_BOOK1, CH_DEPTH_BOOK5, CH_DEPTH_BOOK15,
        )

        /** All documented private channels. */
        val PRIVATE_CHANNELS = listOf(CH_BALANCE, CH_ORDER, CH_POSITION, CH_TPSL)

        /**
         * Channels whose ARRAY elements carry the symbol in the `s` field.
         * On "trade" the `s` field is the trade side, not a symbol.
         */
        val ELEMENT_SYMBOL_CHANNELS = setOf(CH_TICKERS)

        /** Docs: "Subscription limit : Max 300 channel subscription/connection". */
        const val MAX_SUBSCRIPTIONS = 300

        /** Channels per re-subscribe frame (the docs accept an args list). */
        const val RESUBSCRIBE_CHUNK = 50

        /** Docs: the server accepts at most 5 messages per second per connection. */
        const val MIN_SEND_INTERVAL_MS = 210L

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

        /**
         * The symbol a pushed element belongs to. Array channels differ: on
         * "tickers" the element field `s` is the symbol, on "trade" it is the
         * trade side — there the frame header carries the symbol.
         */
        fun symbolOfPush(ch: String, element: JSONObject, frameSymbol: String): String =
            if (ch in ELEMENT_SYMBOL_CHANNELS) element.optString("s").ifBlank { frameSymbol }
            else frameSymbol

        /** Docs: ping carries a Unix timestamp in SECONDS (not milliseconds). */
        fun pingFrame(nowMs: Long): String =
            JSONObject().put("op", "ping").put("ping", nowMs / 1000).toString()

        /**
         * True for heartbeat frames that must NOT be answered. The docs' ping
         * response is `{"op":"ping","pong":...}` — the server's own reply to our
         * heartbeat — so "ping" and "pong" are acks.
         *
         * A frame with NO "op" is NOT a heartbeat: every data push (tickers,
         * price, depth, balance, order, position, tpsl, …) carries only
         * "ch"/"ts"/"data" and has no "op" at all. Treating an empty op as a
         * heartbeat silently drops the whole market and account feed.
         */
        fun isHeartbeat(op: String): Boolean = op == "ping" || op == "pong"

        /**
         * Private-login verdict. The login reply has no top-level `code`; the result
         * lives in `data.result` (live failure frame:
         * `{"op":"login","data":{"result":false,"msg":"invalid params..."}}`).
         * The official demo treats any op "login" frame as success, so only an
         * explicit failure flag rejects it.
         */
        fun isLoginSuccess(msg: JSONObject): Boolean {
            val data = msg.optJSONObject("data")
            val codeOk = msg.optInt("code", 0) == 0
            val resultOk = data?.optBoolean("result", true) ?: true
            return codeOk && resultOk
        }

        /** Human-readable login failure reason from `data.msg` / `msg` / `message`. */
        fun loginReason(msg: JSONObject): String? {
            val data = msg.optJSONObject("data")
            return (data?.optString("msg")?.takeIf { it.isNotBlank() }
                ?: msg.optString("msg").takeIf { it.isNotBlank() }
                ?: msg.optString("message").takeIf { it.isNotBlank() })
        }

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
        // broken pipe, cancelled websocket...). Routine: the client reconnects
        // automatically with backoff.
        m.contains("cancel", ignoreCase = true) ||
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
