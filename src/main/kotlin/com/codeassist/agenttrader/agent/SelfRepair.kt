package com.codeassist.agenttrader.agent

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

    data class Diagnosis(val findings: List<Finding>) {
        val worst: String get() = findings.firstOrNull()?.severity ?: "OK"

        fun summary(): String = if (findings.isEmpty()) {
            "No problems found. Everything looks healthy."
        } else {
            findings.joinToString("\n") { f ->
                "[${f.severity}] ${f.issue}\n    fix: ${f.fix}${if (f.autoFixable) "  (auto)" else ""}"
            }
        }
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
    )

    /**
     * Inspects both layers and returns ranked findings. Pure — the caller
     * decides which repairs to apply.
     */
    fun diagnose(provider: ProviderState, engine: EngineState): Diagnosis {
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
        if (engine.trading && !engine.engineOn) {
            f += Finding("WARN", "Trading is on but the engine is off.", "Turn the engine on", true)
        }
        if (engine.trading && !engine.loopRunning) {
            f += Finding("WARN", "Trading is on but the trading loop is not running.", "Restart the loop", true)
        }
        if (engine.live && !engine.feedConnected && engine.bitunixKeysPresent) {
            f += Finding("WARN", "Live mode without the WebSocket feed.", "Reconnect the private feed (balance/position/order/tpsl)", true)
        }
        if (engine.leverage > 125) {
            f += Finding("WARN", "Leverage ${engine.leverage}x is above the exchange maximum.", "Clamp leverage to 125x", true)
        }
        if (engine.live && engine.openPositions > 0 && !engine.feedConnected) {
            f += Finding("WARN", "Open live positions but no feed — changes may be missed.", "Reconnect the feed before managing positions", true)
        }

        val order = mapOf("CRITICAL" to 0, "WARN" to 1, "INFO" to 2)
        return Diagnosis(f.sortedBy { order[it.severity] ?: 3 })
    }

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
