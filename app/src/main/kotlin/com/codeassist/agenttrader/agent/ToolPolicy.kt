package com.codeassist.agenttrader.agent

/**
 * The authority model for everything the model is allowed to ask the engine to do.
 *
 * The system prompt is advice; this file is the rule. A tool call reaches
 * [com.codeassist.agenttrader.trader.TraderEngine.handle] only when
 * [ToolPolicy.authorize] returns a decision that the caller is allowed to act on,
 * so a model — or a prompt injection inside a tool result — cannot close a
 * position by describing one.
 *
 * Everything here is pure, so the policy can be unit-tested without a device.
 */
enum class ToolCapability {
    /** Reads state. Never changes anything, so it never needs consent. */
    READ,

    /** Scanner/agent configuration. Allowed only when the user's own turn asked for it. */
    SETTINGS,

    /** Changes how much money is at risk. A one-tap confirmation every time. */
    RISK,

    /** Moves money: opens or closes a position. A one-tap confirmation every time. */
    MONEY,

    /** Starts or stops the engine/trading loop. A one-tap confirmation every time. */
    LIFECYCLE,
}

data class ToolSpec(
    /** Canonical command prefix, lowercase (the engine matches case-insensitively). */
    val command: String,
    val capability: ToolCapability,
    /** Words in the user's own message that make a SETTINGS change legitimate. */
    val keywords: List<String> = emptyList(),
)

sealed class ToolDecision {
    object Allow : ToolDecision()
    data class Confirm(val reason: String) : ToolDecision()
    data class Deny(val reason: String) : ToolDecision()
}

object ToolPolicy {

    private const val SETTINGS_REASON =
        "This changes the app's configuration, and the user did not ask for it in this message."
    private const val RISK_REASON = "This changes how much money is at risk."
    private const val MONEY_REASON = "This moves money: it opens or closes a position."
    private const val LIFECYCLE_REASON = "This starts or stops the engine or the trading loop."

    /**
     * Every command the model may use, with the authority it carries.
     *
     * Read-only first: those are what the model needs to answer with real
     * numbers. Anything that changes state is listed after it.
     */
    val specs: List<ToolSpec> = listOf(
        // ---- READ: state only ----
        ToolSpec("status", ToolCapability.READ),
        ToolSpec("balance", ToolCapability.READ),
        ToolSpec("positions history", ToolCapability.READ),
        ToolSpec("positions", ToolCapability.READ),
        ToolSpec("orders", ToolCapability.READ),
        ToolSpec("history orders", ToolCapability.READ),
        ToolSpec("pnl", ToolCapability.READ),
        ToolSpec("trades_history", ToolCapability.READ),
        ToolSpec("tpsl_history", ToolCapability.READ),
        ToolSpec("tpsl", ToolCapability.READ),
        ToolSpec("funding", ToolCapability.READ),
        ToolSpec("tiers", ToolCapability.READ),
        ToolSpec("signals", ToolCapability.READ),
        ToolSpec("settings trade", ToolCapability.READ),
        ToolSpec("scan once", ToolCapability.READ),
        ToolSpec("mid management", ToolCapability.READ),

        // ---- SETTINGS: scanner, universe, reports ----
        ToolSpec("set universe", ToolCapability.SETTINGS, listOf("universe", "symbol", "symbols")),
        ToolSpec("set symbols", ToolCapability.SETTINGS, listOf("universe", "symbol", "symbols")),
        ToolSpec("set timeframes", ToolCapability.SETTINGS, listOf("timeframe", "timeframes")),
        ToolSpec("set scan top", ToolCapability.SETTINGS, listOf("scan top", "top ")),
        ToolSpec("set min agree", ToolCapability.SETTINGS, listOf("min agree", "agree")),
        ToolSpec("set min tf", ToolCapability.SETTINGS, listOf("min tf", "confidence")),
        ToolSpec("set min conf", ToolCapability.SETTINGS, listOf("min conf", "confidence")),
        ToolSpec("set tf agree", ToolCapability.SETTINGS, listOf("tf agree", "agree")),
        ToolSpec("set auto universe", ToolCapability.SETTINGS, listOf("auto universe", "auto")),
        ToolSpec("set min volume", ToolCapability.SETTINGS, listOf("min volume", "volume")),
        ToolSpec("set auto rank", ToolCapability.SETTINGS, listOf("auto rank", "rank")),
        ToolSpec("refresh auto", ToolCapability.SETTINGS, listOf("refresh auto", "refresh")),
        ToolSpec("scan on", ToolCapability.SETTINGS, listOf("scan on", "scanner on", "scan")),
        ToolSpec("scan off", ToolCapability.SETTINGS, listOf("scan off", "scanner off", "scan")),
        ToolSpec("report on", ToolCapability.SETTINGS, listOf("report on", "report")),
        ToolSpec("report off", ToolCapability.SETTINGS, listOf("report off", "report")),
        ToolSpec("report interval", ToolCapability.SETTINGS, listOf("report interval", "report")),

        // ---- RISK: sizing, stops, guards ----
        ToolSpec("set leverage", ToolCapability.RISK),
        ToolSpec("set risk", ToolCapability.RISK),
        ToolSpec("set guard", ToolCapability.RISK),
        ToolSpec("set max positions", ToolCapability.RISK),
        ToolSpec("set breakeven", ToolCapability.RISK),
        ToolSpec("set ladder", ToolCapability.RISK),
        ToolSpec("set trail trigger", ToolCapability.RISK),
        ToolSpec("set trail distance", ToolCapability.RISK),
        ToolSpec("set sl", ToolCapability.RISK),
        ToolSpec("set tp r", ToolCapability.RISK),
        // "set tp" is prefix-matched with a following space, so "set tp 2.5"
        // lands here while "set tp r 3" still hits the longer spec first.
        ToolSpec("set tp", ToolCapability.RISK),
        // Manual stop moves: they change the live stop without the ROI trigger,
        // so they carry the same risk authority as the automatic rules.
        ToolSpec("fire breakeven", ToolCapability.RISK),
        ToolSpec("fire trailing", ToolCapability.RISK),

        // ---- MONEY ----
        ToolSpec("close position", ToolCapability.MONEY),
        ToolSpec("close all", ToolCapability.MONEY),
        ToolSpec("manual entry", ToolCapability.MONEY),
        ToolSpec("cancel orders", ToolCapability.MONEY),

        // ---- LIFECYCLE ----
        ToolSpec("engine on", ToolCapability.LIFECYCLE),
        ToolSpec("engine off", ToolCapability.LIFECYCLE),
        ToolSpec("start trading", ToolCapability.LIFECYCLE),
        ToolSpec("stop trading", ToolCapability.LIFECYCLE),
        ToolSpec("set live", ToolCapability.LIFECYCLE),
        ToolSpec("live mode", ToolCapability.LIFECYCLE),
        ToolSpec("paper mode", ToolCapability.LIFECYCLE),
    )

    /** Longest command first, so "set tp r" wins over "set tp " and "positions history" over "positions". */
    private val byLength: List<ToolSpec> = specs.sortedByDescending { it.command.length }

    /** Canonical command names, for the system prompt. */
    val commands: List<String> = specs.map { it.command }

    /** The spec a call belongs to, or null when the engine has no such command. */
    fun specFor(command: String): ToolSpec? {
        val c = command.trim().lowercase()
        return byLength.firstOrNull { c == it.command || c.startsWith(it.command + " ") }
    }

    /** True when the engine can execute this call at all. */
    fun isKnown(command: String): Boolean = specFor(command) != null

    /** The class of authority a call carries; unknown commands are treated as money-level. */
    fun capabilityOf(command: String): ToolCapability =
        specFor(command)?.capability ?: ToolCapability.MONEY

    /**
     * Decides what may happen with [command] in a turn where the user wrote [userTurn].
     *
     * - READ is always allowed.
     * - SETTINGS is allowed when the user's own message mentions the subject,
     *   otherwise it needs a confirmation.
     * - RISK, MONEY and LIFECYCLE always need a confirmation, whatever the model
     *   claims the user said.
     * - Anything the engine does not implement is denied outright, so the model
     *   cannot report success for a command that never ran.
     */
    fun authorize(command: String, userTurn: String): ToolDecision {
        val spec = specFor(command)
            ?: return ToolDecision.Deny("The engine has no command for this — nothing was executed.")
        return when (spec.capability) {
            ToolCapability.READ -> ToolDecision.Allow
            ToolCapability.SETTINGS ->
                if (mentions(userTurn, spec.keywords)) ToolDecision.Allow
                else ToolDecision.Confirm(SETTINGS_REASON)
            ToolCapability.RISK -> ToolDecision.Confirm(RISK_REASON)
            ToolCapability.MONEY -> ToolDecision.Confirm(MONEY_REASON)
            ToolCapability.LIFECYCLE -> ToolDecision.Confirm(LIFECYCLE_REASON)
        }
    }

    private fun mentions(userTurn: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val t = userTurn.lowercase()
        return keywords.any { t.contains(it) }
    }
}
