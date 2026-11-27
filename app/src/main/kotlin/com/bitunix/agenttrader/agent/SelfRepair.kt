package com.bitunix.agenttrader.agent

import com.bitunix.agenttrader.trader.NetworkErrors

/**
 * Self-diagnosis and self-repair for the running app.
 *
 * The agent can inspect the live state, decide what is broken and apply only the
 * repairs that are safe (restarting a socket, restarting the loop, re-picking a
 * working model). Anything that touches money or credentials is reported, never
 * silently applied.
 */
object SelfRepair {

    data class Finding(
        val severity: String, // CRITICAL, WARN, INFO
        val issue: String,
        val fix: String,
        val autoFixable: Boolean,
    )

    data class Diagnosis(
        val findings: List<Finding>,
        /**
         * Real probes that ran ([SelfCheck]). Empty when the caller only ran the
         * rule list; the summary then says so instead of claiming health.
         */
        val checks: List<SelfCheck.Check> = emptyList(),
        /** The user's own words, echoed back so a complaint can never be ignored. */
        val complaint: String = "",
    ) {
        val worst: String
            get() = findings.firstOrNull()?.severity
                ?: if (checks.any { it.status == SelfCheck.Status.FAIL }) "WARN" else "OK"

        /** Findings only — used as the rule section of the model evidence block. */
        fun findingsText(): String = if (findings.isEmpty()) "none" else {
            findings.joinToString("\n") { f ->
                "[${f.severity}] ${f.issue}\n    fix: ${f.fix}${if (f.autoFixable) "  (auto)" else ""}"
            }
        }

        fun summary(): String = buildString {
            if (complaint.isNotBlank()) appendLine("Your report: \"${complaint.trim()}\"")
            if (findings.isEmpty()) {
                // Never "No problems found": an empty rule list only means no
                // known rule fired. Say exactly that and what was checked.
                if (checks.isEmpty()) {
                    appendLine("No rule fired — but no live check ran either, so this is not a clean bill of health.")
                } else {
                    appendLine("No rule fired. Ran ${checks.size} live checks; details below.")
                }
                if (complaint.isNotBlank()) {
                    appendLine("That does not mean your problem is gone: the checks only cover what is listed below.")
                }
            } else {
                appendLine(findingsText())
            }
            if (checks.isNotEmpty()) {
                appendLine("Live checks: ${SelfCheck.counts(checks)}")
                appendLine(SelfCheck.render(checks))
            }
        }.trim()
    }

    /** Snapshot of the provider layer. */
    data class ProviderState(
        val baseUrl: String,
        val apiKeyPresent: Boolean,
        val model: String,
        val modelsAvailable: Int,
        val lastError: String,
    )

    /** Snapshot of the trading engine. */
    data class EngineState(
        val live: Boolean,
        val engineOn: Boolean,
        val trading: Boolean,
        val loopRunning: Boolean,
        val feedConnected: Boolean,
        val bitunixKeysPresent: Boolean,
        val openPositions: Int,
        val leverage: Int,
        /** Highest leverage the exchange lists for any contract (200 for BTCUSDT/ETHUSDT). */
        val leverageCeiling: Int = 200,
        /**
         * Last exchange/engine failure ([com.bitunix.agenttrader.trader.EngineRuntimeError]).
         * Kept separate from the provider error so a DNS outage is not blamed on the model.
         */
        val lastError: String = "",
    )

    /**
     * Inspects both layers and returns ranked findings. Pure — the caller
     * decides which repairs to apply.
     *
     * [complaint] is the user's own description of what is wrong and is echoed
     * in the summary; [checks] are the live probes that actually ran. Without
     * them the diagnosis can only say which rules fired, never "all healthy".
     */
    fun diagnose(
        provider: ProviderState,
        engine: EngineState,
        complaint: String = "",
        checks: List<SelfCheck.Check> = emptyList(),
    ): Diagnosis {
        val f = mutableListOf<Finding>()

        if (provider.baseUrl.isBlank()) {
            f += Finding("CRITICAL", "No LLM provider configured.", "Set base URL + API key in Settings or /connect_provider", false)
        } else if (!provider.apiKeyPresent) {
            f += Finding("CRITICAL", "Provider URL set but no API key.", "Add the API key in Settings", false)
        } else if (provider.model.isBlank()) {
            f += Finding(
                "WARN", "No model selected.",
                "Probe the provider and pick a working model",
                provider.baseUrl.isNotBlank() && provider.apiKeyPresent,
            )
        } else if (provider.lastError.isNotBlank()) {
            f += Finding(
                "WARN", "Last provider call failed: ${provider.lastError.take(120)}",
                "Probe the provider again and switch to another model if it keeps failing",
                true,
            )
        }

        if (!engine.bitunixKeysPresent && engine.live) {
            f += Finding("CRITICAL", "LIVE mode without Bitunix keys.", "Add API key + secret in Settings, or switch to PAPER", false)
        }
        if (engine.lastError.isNotBlank()) {
            if (NetworkErrors.isNetwork(engine.lastError)) {
                // A dead connection is not a provider problem. Say what to check
                // and never suggest probing the model.
                f += Finding(
                    "WARN",
                    "Exchange unreachable: ${engine.lastError.take(160)}",
                    "Check this device's internet connection (DNS); the app retries automatically — no model change needed",
                    false,
                )
            } else {
                f += Finding(
                    "INFO",
                    "Last engine error: ${engine.lastError.take(160)}",
                    "Review the log; restart the trading loop if it repeats",
                    false,
                )
            }
        }
        if (engine.trading && !engine.engineOn) {
            f += Finding("WARN", "Trading is on but the engine is off.", "Turn the engine on", true)
        }
        if (engine.trading && !engine.loopRunning) {
            f += Finding("WARN", "Trading is on but the trading loop is not running.", "Restart the loop", true)
        }
        if (engine.live && !engine.feedConnected && engine.bitunixKeysPresent) {
            f += Finding("WARN", "Live mode without the WebSocket feed.", "Reconnect the private feed (balance/position/order/tpsl)", true)
        }
        if (engine.leverage > engine.leverageCeiling) {
            f += Finding(
                "WARN",
                "Leverage ${engine.leverage}x is above the exchange maximum (${engine.leverageCeiling}x).",
                "Clamp leverage to ${engine.leverageCeiling}x",
                true,
            )
        }
        if (engine.live && engine.openPositions > 0 && !engine.feedConnected) {
            f += Finding("WARN", "Open live positions but no feed — changes may be missed.", "Reconnect the feed before managing positions", true)
        }

        val order = mapOf("CRITICAL" to 0, "WARN" to 1, "INFO" to 2)
        return Diagnosis(f.sortedBy { order[it.severity] ?: 3 }, checks, complaint)
    }

    /**
     * True when a free-text message is asking the agent to verify, review,
     * simplify or debug something. Those messages must reach the real
     * diagnostic path; previously they fell through to plain chat (or to
     * "No model set") and the complaint was never investigated.
     */
    fun asksForDiagnosis(text: String): Boolean {
        val t = text.lowercase()
        // "what's wrong with BTC?" is a market question, not a bug report. Without
        // this guard every such message was hijacked into the self-debugger and
        // answered with an app health report instead of market analysis.
        val subject = WRONG_WITH.find(t)?.groupValues?.get(1)
        if (subject != null && subject !in APP_SUBJECTS) return false
        if (FEATURE_REQUEST.any { t.contains(it) }) return false
        if (DIAGNOSIS_ALWAYS.any { t.contains(it) }) return true
        return DIAGNOSIS_COMPLAINT.any { t.contains(it) }
    }

    /** "what's wrong with X" — [APP_SUBJECTS] keeps the app-side questions working. */
    private val WRONG_WITH = Regex("""what'?s wrong with\s+([a-z0-9]{2,12})""")
    private val APP_SUBJECTS = setOf("app", "agent", "engine", "bot", "you", "it", "the", "this", "my")

    /** Explicit "check this" words — always run the probes. */
    private val DIAGNOSIS_ALWAYS = listOf(
        "verify", "code review", "review code", "review the code", "simplify", "diagnose",
        "self-heal", "self heal", "debug", "audit", "no issues", "no problem", "nothing happens",
        "is everything ok", "everything ok", "all good", "is it working", "check everything",
        "any problems", "what's wrong", "whats wrong",
    )

    /** "It is broken" words — run the probes unless this is a feature request. */
    private val DIAGNOSIS_COMPLAINT = listOf(
        "bug", "broken", "not working", "doesn't work", "does not work", "not work",
        "problem", "issue", "error", "crash", "stuck", "wrong", "failed", "fails",
        "weird", "not showing",
        // the user writes Finglish/Persian
        "kharab", "خراب", "مشکل", "کار نمیکنه", "کار نمی‌کند", "باگ", "ارور",
    )

    /** Asking for new behaviour is a normal chat turn, not a bug report. */
    private val FEATURE_REQUEST = listOf(
        "i want", "i need you to add", "add a feature", "add feature", "implement",
        "build me", "make a plan", "new feature", "can you add", "please add",
    )

    /**
     * Applies the safe repairs and returns a report of what was done.
     * [actions] receives the repair ids that can be applied automatically.
     */
    fun applyAutoFixes(diagnosis: Diagnosis, actions: (String) -> String): String {
        val applied = mutableListOf<String>()
        for (finding in diagnosis.findings) {
            if (!finding.autoFixable) continue
            when {
                finding.issue.startsWith("No model selected") ->
                    applied += actions("pick_model")
                finding.issue.startsWith("Last provider call failed") ->
                    applied += actions("probe_provider")
                finding.issue.contains("trading loop is not running") ->
                    applied += actions("restart_loop")
                finding.issue.contains("WebSocket feed") ->
                    applied += actions("reconnect_feed")
                finding.issue.startsWith("Leverage") ->
                    applied += actions("clamp_leverage")
            }
        }
        return if (applied.isEmpty()) "Nothing needed automatic repair."
        else applied.joinToString("\n")
    }
}
