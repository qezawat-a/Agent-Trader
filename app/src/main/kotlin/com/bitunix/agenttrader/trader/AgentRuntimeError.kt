package com.bitunix.agenttrader.trader

/**
 * Holds the most recent LLM-provider failure so /autopsy and /diagnose can
 * inspect it.
 *
 * Two rules keep the diagnostic honest:
 *  - A failure expires. One provider hiccup must not be reported as "the last
 *    provider call failed" days later, long after the provider recovered.
 *  - A failure is attributed. Exchange/engine failures go to
 *    [EngineRuntimeError]; local app exceptions are recorded as [LOCAL] so the
 *    repair flow cannot blame the model for a bug in a command handler.
 */
object AgentRuntimeError {

    const val PROVIDER = "provider"
    const val LOCAL = "local"

    /** How long a recorded failure stays relevant. */
    const val DEFAULT_MAX_AGE_MS = 15 * 60_000L

    /** The most recent failure text; blank when healthy. */
    @Volatile
    var last: String = ""
        private set

    /** [PROVIDER] or [LOCAL]. */
    @Volatile
    var source: String = ""
        private set

    /** When the failure was recorded (epoch millis, 0 when healthy). */
    @Volatile
    var at: Long = 0L
        private set

    fun record(message: String, source: String = PROVIDER, now: Long = System.currentTimeMillis()) {
        val text = message.trim()
        if (text.isBlank()) return
        last = text
        this.source = source
        at = now
    }

    /** A successful provider call (or a clean re-check) clears the old failure. */
    fun clear() {
        last = ""
        source = ""
        at = 0L
    }

    /**
     * The provider failure worth reporting, or "" when the last error came from
     * local code or is older than [maxAgeMs].
     */
    fun providerError(
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        now: Long = System.currentTimeMillis(),
    ): String = if (source == PROVIDER && last.isNotBlank() && now - at <= maxAgeMs) last else ""

    /** Age of the recorded failure in seconds, or -1 when nothing is recorded. */
    fun ageSeconds(now: Long = System.currentTimeMillis()): Long =
        if (at == 0L) -1 else (now - at) / 1000

    /** One line for /autopsy and the diagnosis summary, labelled with its origin. */
    fun describe(now: Long = System.currentTimeMillis()): String = when {
        last.isBlank() -> ""
        source == PROVIDER -> "Provider error (${ageSeconds(now)}s ago): $last"
        else -> "App error (${ageSeconds(now)}s ago): $last"
    }
}
