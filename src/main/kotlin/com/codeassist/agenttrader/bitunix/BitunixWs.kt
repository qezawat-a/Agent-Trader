package com.codeassist.agenttrader.bitunix

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bitunix futures WebSocket client.
 *
 * Official WS URL for futures: wss://fstream.bitunix.com/ws (one endpoint serves both the
 * public market channels and the private ones — the private connection just logs in first).
 *
 * Limits from the docs: at most 5 messages per second per connection (ping and subscribe
 * count), and at most 300 channel subscriptions per connection. The server drops silent
 * sessions (~26s), so we send an application-level ping every 5s and pump all outgoing
 * frames through a 250ms queue (4 msg/s).
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
        val socket = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        val channels = java.util.Collections.synchronizedSet(LinkedHashSet<Pair<String, String?>>())
        val queue = ConcurrentLinkedQueue<String>()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val manualClose = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempt = java.util.concurrent.atomic.AtomicLong(0)
    }

    private val public = SocketState(privateChannel = false)
    private val private = SocketState(privateChannel = true)

    fun connectPublic() = connect(public)

    /** Opens the private socket. Subscriptions are sent only after the login reply succeeds. */
    fun connectPrivate() = connect(private)

    private fun connect(state: SocketState) {
        ensurePumps()
        state.manualClose.set(false)
        state.ready.set(false)
        state.socket.set(
            client.newWebSocket(
                Request.Builder().url(WS_URL).build(),
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
            for (state in listOf(public, private)) {
                val ws = state.socket.get()
                if (ws != null && state.ready.get()) {
                    val frame = state.queue.poll()
                    if (frame != null) ws.send(frame)
                }
            }
        }, 250L, 250L, TimeUnit.MILLISECONDS)
        // Application-level ping every 5s: the server drops silent sessions (~26s).
        pump.scheduleWithFixedDelay({
            for (state in listOf(public, private)) {
                val ws = state.socket.get()
                if (ws != null && state.ready.get()) {
                    ws.send(JSONObject().put("op", "ping").put("ping", System.currentTimeMillis()).toString())
                }
            }
        }, 5L, 5L, TimeUnit.SECONDS)
    }

    /** Subscribe a public market channel, e.g. ("market_kline_15min", "BTCUSDT"). */
    fun subscribePublic(ch: String, symbol: String) {
        public.channels.add(ch to symbol)
        send(public, "subscribe", ch, symbol)
    }

    fun unsubscribePublic(ch: String, symbol: String) {
        public.channels.remove(ch to symbol)
        send(public, "unsubscribe", ch, symbol)
    }

    /** Private channels: "order", "position", "tpsl", "balance" (no symbol). */
    fun subscribePrivate(ch: String) {
        private.channels.add(ch to null)
        send(private, "subscribe", ch, null)
    }

    fun close() {
        public.manualClose.set(true)
        private.manualClose.set(true)
        public.socket.get()?.close(1000, "bye")
        private.socket.get()?.close(1000, "bye")
        pump.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    private fun socketListener(state: SocketState) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            state.attempt.set(0)
            if (state.privateChannel) {
                login(webSocket)
            } else {
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
                        state.ready.set(true)
                        state.socket.set(webSocket)
                        listener.onOpen(true)
                        resubscribe(state)
                    } else {
                        listener.onError("Login failed: ${msg.optString("msg")}")
                    }
                }
                else -> {
                    val ch = msg.optString("ch")
                    if (ch.isNotEmpty() && msg.has("data")) {
                        val data = msg.opt("data")
                        // Kline docs say data is a list, but examples show an object. Accept both.
                        val obj = when (data) {
                            is JSONObject -> data
                            is JSONArray -> if (data.length() > 0) data.optJSONObject(0) ?: JSONObject() else JSONObject()
                            else -> JSONObject()
                        }
                        listener.onChannel(ch, msg.optString("symbol").ifEmpty { null }, obj)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            state.ready.set(false)
            listener.onError(t.message ?: t.javaClass.simpleName)
            listener.onClosed(state.privateChannel, "failure")
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
        const val WS_URL = "wss://fstream.bitunix.com/ws"

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
