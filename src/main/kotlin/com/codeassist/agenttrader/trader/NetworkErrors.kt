package com.codeassist.agenttrader.trader

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Classifies failures from the exchange/network layer.
 *
 * Android reports a dead DNS lookup as `Unable to resolve host "fapi.bitunix.com":
 * No address associated with hostname`. That is a connectivity problem on this
 * device — not the LLM provider, and not a reason to switch models. Keeping the
 * classifier here lets the loop, the self-diagnosis and the UI agree on one
 * explanation.
 */
object NetworkErrors {

    private val DNS_MARKERS = listOf(
        "unable to resolve host",
        "no address associated with hostname",
        "unknownhost",
        "nodename nor servname",
        "name or service not known",
    )

    private val NETWORK_MARKERS = DNS_MARKERS + listOf(
        "network is unreachable",
        "network unreachable",
        "unreachable",
        "dns",
        "internet connection",
        "connect timed out",
        "connection timed out",
        "connection refused",
        "connection reset",
        "broken pipe",
        "unable to connect",
        "socket",
        "timeout",
        "timed out",
        "ssl",
        "handshake",
    )

    /** True when the failure is a DNS lookup failure. */
    fun isDns(raw: String?): Boolean {
        val m = raw?.lowercase().orEmpty()
        return m.isNotBlank() && DNS_MARKERS.any { m.contains(it) }
    }

    /** True when the failure is any kind of network/connectivity failure. */
    fun isNetwork(raw: String?): Boolean {
        val m = raw?.lowercase().orEmpty()
        return m.isNotBlank() && NETWORK_MARKERS.any { m.contains(it) }
    }

    /** Walks the cause chain so a wrapped DNS failure is still recognised. */
    fun isNetwork(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { e ->
            e is UnknownHostException || e is ConnectException ||
                e is SocketTimeoutException || e is IOException || isNetwork(e.message)
        }

    /**
     * True when [error] came from Bitunix rather than the LLM provider.
     * Provider hosts (api.openai.com, ...) must keep their own error path.
     */
    fun isExchange(error: Throwable?): Boolean {
        val text = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return text.contains("bitunix") || text.contains("fapi.")
    }

    /** The most specific message in the cause chain, with the class as a fallback. */
    fun rawMessage(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        return chain.firstOrNull { isNetwork(it.message) }?.message
            ?.takeIf { it.isNotBlank() }
            ?: chain.firstOrNull { !it.message.isNullOrBlank() }?.message
            ?: error.javaClass.simpleName
    }

    /**
     * A short, actionable line for the log/UI.
     * DNS outages get their own wording because the fix is "check the internet",
     * never "switch models".
     */
    fun describe(raw: String?, retrySec: Long = 30): String {
        val m = raw?.trim().orEmpty()
        val retry = if (retrySec > 0) "retrying in ${retrySec}s" else "will retry"
        return when {
            isDns(m) ->
                "Bitunix unreachable (DNS) — check this device's internet connection; $retry"
            isNetwork(m) ->
                "Bitunix unreachable (network) — check this device's internet connection; $retry"
            m.isBlank() -> "unknown error; $retry"
            else -> m
        }
    }
}

/**
 * Holds the most recent exchange/engine failure, separately from
 * [AgentRuntimeError] (which is reserved for LLM-provider failures).
 *
 * A DNS outage while the loop polls Bitunix used to be written into
 * [AgentRuntimeError], so `/diagnose` blamed the model and suggested switching
 * it. It also re-printed the same "Loop error: ..." line every 30s. This object
 * keeps those errors out of the provider diagnostic and rate-limits identical
 * repeats to one report every [REPEAT_WINDOW_MS].
 */
object EngineRuntimeError {

    private const val REPEAT_WINDOW_MS = 5 * 60_000L

    /** Friendly text of the most recent engine failure; blank when healthy. */
    @Volatile
    var last: String = ""
        private set

    /** When the current message was last reported (repeated errors do not move it until they are shown again). */
    @Volatile
    var lastAt: Long = 0L
        private set

    /** How many times the current message has repeated since [lastAt]. */
    @Volatile
    var repeats: Int = 0
        private set

    /** True when the current failure was classified as network/DNS. */
    @Volatile
    var network: Boolean = false
        private set

    /**
     * Records [message] and reports whether it should be shown.
     * Returns true for a new message, or for the same message once the repeat
     * window has elapsed; suppressed repeats only bump [repeats].
     */
    fun record(message: String, now: Long = System.currentTimeMillis()): Boolean {
        val msg = message.trim()
        if (msg.isBlank()) return false
        val same = msg == last
        if (same) {
            repeats += 1
            if (now - lastAt < REPEAT_WINDOW_MS) return false
        } else {
            last = msg
            lastAt = now
            repeats = 0
            network = NetworkErrors.isNetwork(msg)
        }
        lastAt = now
        return true
    }

    fun clear() {
        last = ""
        lastAt = 0L
        repeats = 0
        network = false
    }
}
