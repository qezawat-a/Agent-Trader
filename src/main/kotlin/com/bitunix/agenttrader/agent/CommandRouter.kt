package com.bitunix.agenttrader.agent

/**
 * Parses slash commands typed in the chat. Agent commands are handled here; trader-engine
 * commands are forwarded to the trader engine by the caller.
 */
object CommandRouter {

    val AGENT_COMMANDS: List<Pair<String, String>> = listOf(
        "/help" to "Show this command list",
        "/clear" to "Clear the chat and session memory",
        "/models" to "List models found for your API key",
        "/set_model <name>" to "Choose the model to use",
        "/connect_provider <url> <key>" to "Set the LLM base URL and API key",
        "/check_ai" to "Check the model connection and limits",
        "/price <symbol>" to "Fetch the live Bitunix last price",
        "/add_mcp <name> <url>" to "Register an MCP server",
        "/mcp" to "List MCP servers",
        "/add_skill <name> <text>" to "Add a skill",
        "/skill <name>" to "Run or show a skill",
        "/add_soul <text>" to "Set the agent persona (soul)",
        "/soul" to "Show the current soul",
        "/think" to "Show the agent's plan for the last task",
        "/memory" to "Show saved project notes",
        "/code_review" to "Review code with runtime evidence (/review alias)",
        "/bug_fix" to "Find and fix a bug (asks before each edit)",
        "/verify" to "Run the live checks (no fake 'compile ok')",
        "/simplify" to "Simplify a pasted snippet — or report what is broken",
        "/team_spawn" to "Start sub-agents for a larger task",
        "/check_system" to "Check the trader engine, scanner and agent code",
        "/autopsy" to "Analyse the last failure step by step",
        "/diagnose" to "Run every check; add your complaint: /diagnose <what is wrong>",
        "/repair" to "Apply the safe automatic repairs",
        "/ws on|off|status" to "Connect or disconnect the Bitunix private WebSocket feed",
        "/cert" to "Show the signing key of this build (which APK updates it)",
        "/backup" to "Write every setting to a file on the phone (survives a reinstall)",
        "/restore" to "Read the settings back from that file",
    )

    sealed class Parsed {
        data class Agent(val command: String, val args: String) : Parsed()
        data class Trader(val text: String) : Parsed()
        data class Chat(val text: String) : Parsed()
    }

    /**
     * Engine commands that take no argument. They count only when the whole message is the
     * command, ignoring trailing punctuation. Before this, any sentence that merely began with
     * one of these words ("balance chetor?", "signals look weak, why?") was run as the engine
     * command, so the question was never answered.
     */
    private val exactTraderCommands = setOf(
        "engine on", "engine off", "start trading", "stop trading",
        "status", "balance", "positions", "positions history", "orders", "history orders",
        "cancel orders", "pnl", "trades_history", "tpsl_history", "tpsl", "funding",
        "tiers", "signals", "settings trade", "mid management",
        "scan on", "scan off", "scan once", "report on", "report off",
        "close all", "live mode", "paper mode", "refresh auto", "auto on", "auto off",
        "fire breakeven", "fire trailing",
    )

    /**
     * Engine commands that take an argument. They count when the message starts with the
     * command followed by a space: "set leverage 20", "set universe BTCUSDT,ETHUSDT".
     */
    private val argTraderCommands = listOf(
        "set universe", "set symbols", "set symbol", "set timeframes", "set scan top",
        "set min agree", "set min tf", "set min conf", "set tf agree",
        "set auto universe", "set auto tpsl", "set partial", "set account tp", "set account sl", "set auto rank", "set min volume",
        "set breakeven", "set trail trigger", "set trail distance", "set sl",
        "set max positions", "set maxpos", "set guard",
        "set tp r", "set tp", "set leverage", "set risk", "set live",
        "min volume", "auto universe", "auto rank", "auto gainers", "auto volume",
        "auto losers", "auto movers", "scan top",
        "close position", "manual entry", "report interval",
        "fire breakeven", "fire trailing",
    )

    fun parse(input: String): Parsed {
        val text = input.trim()
        if (text.startsWith("/")) {
            val parts = text.split(Regex("\\s+"), limit = 2)
            return Parsed.Agent(parts[0].lowercase(), parts.getOrElse(1) { "" })
        }
        if (isTraderCommand(text.lowercase())) return Parsed.Trader(text)
        return Parsed.Chat(text)
    }

    /** True when [lower] is an engine command, not a sentence that happens to start with one. */
    internal fun isTraderCommand(lower: String): Boolean {
        if (lower.trimEnd(' ', '?', '!', '.', '\u061F') in exactTraderCommands) return true
        return argTraderCommands.any { lower.startsWith("$it ") }
    }

    fun helpText(): String = AGENT_COMMANDS.joinToString("\n") { (cmd, desc) -> "$cmd — $desc" }
}
