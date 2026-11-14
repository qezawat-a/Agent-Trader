package com.codeassist.agenttrader.agent

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
        "/code_review" to "Review the open file and propose fixes",
        "/bug_fix" to "Find and fix a bug (asks before each edit)",
        "/verify" to "Build and run to verify the last change",
        "/simplify" to "Simplify the selected code",
        "/team_spawn" to "Start sub-agents for a larger task",
        "/check_system" to "Check the trader engine, scanner and agent code",
        "/autopsy" to "Analyse the last failure step by step",
        "/diagnose" to "Collect logs and diagnostics for an issue",
        "/ws on|off|status" to "Connect or disconnect the Bitunix private WebSocket feed",
    )

    sealed class Parsed {
        data class Agent(val command: String, val args: String) : Parsed()
        data class Trader(val text: String) : Parsed()
        data class Chat(val text: String) : Parsed()
    }

    private val traderPrefixes = listOf(
        "engine on", "engine off", "start trading", "stop trading",
        "status", "balance", "positions history", "positions", "orders",
        "pnl", "trades_history", "tpsl_history", "tpsl", "funding",
        "tiers", "signals", "history orders", "cancel orders",
        "report on", "report off", "report interval",
        "settings trade", "scan on", "scan off", "scan once",
        "close position", "close all", "manual entry", "set leverage", "set risk",
        "mid management",
        // v1.1.1 scanner & gate commands — without these they fell through to the LLM,
        // which happily answered "Set." while nothing ran on the engine.
        "set universe", "set symbols", "set symbol", "set timeframes", "set scan top",
        "set min agree", "set min tf", "set min conf",
        // v1.1.7 risk manager & exits — same lesson: route to the engine, never the LLM
        "set breakeven", "set trail trigger", "set trail distance",
        "set stop frac", "set stop fraction",
        "set max positions", "set maxpos", "set daily loss", "set guard",
    )

    fun parse(input: String): Parsed {
        val text = input.trim()
        if (text.startsWith("/")) {
            val parts = text.split(Regex("\\s+"), limit = 2)
            return Parsed.Agent(parts[0].lowercase(), parts.getOrElse(1) { "" })
        }
        val lower = text.lowercase()
        if (traderPrefixes.any { lower.startsWith(it) }) return Parsed.Trader(text)
        return Parsed.Chat(text)
    }

    fun helpText(): String = AGENT_COMMANDS.joinToString("\n") { (cmd, desc) -> "$cmd — $desc" }
}
