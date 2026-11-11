package com.codeassist.agenttrader.agent

/**
 * The agent brain: persona, empathy, market awareness and a small tool protocol
 * so the model can actually drive the trading engine instead of only talking.
 *
 * Everything here is pure so it can be unit-tested without a device or network.
 */
object AgentBrain {

    /** Emotional state detected in the user's message. */
    enum class Mood { CALM, ANXIOUS, ANGRY, EXHAUSTED, DISCOURAGED, EUPHORIC, CONFUSED, FOCUSED }

    data class MoodSignal(val mood: Mood, val intensity: Int, val cue: String)

    /**
     * Tool commands the model may invoke. They map 1:1 to trader-engine
     * commands, so the engine stays the single source of truth for actions.
     */
    val TOOLS = listOf(
        // read-only
        "status", "balance", "positions", "orders", "pnl", "funding", "tiers", "signals",
        "scan once", "trades_history", "tpsl", "history orders",
        // engine control
        "engine on", "engine off", "start trading", "stop trading",
        "scan on", "scan off", "report interval", "set leverage", "set risk",
        // money-moving — only with an explicit request from the user
        "close position", "diagnose", "repair",
    )

    private val moodCues: List<Pair<Mood, List<String>>> = listOf(
        Mood.ANGRY to listOf("furious", "angry", "pissed", "mad", "annoyed", "frustrated", "wtf", "ridiculous"),
        Mood.ANXIOUS to listOf("scared", "afraid", "worried", "nervous", "panic", "anxious", "stressed", "stress"),
        Mood.EXHAUSTED to listOf("tired", "exhausted", "sleepless", "burned out", "burnt out", "fed up"),
        Mood.DISCOURAGED to listOf("losing money", "lost money", "down again", "hopeless", "give up", "stuck", "sad", "depressed"),
        Mood.EUPHORIC to listOf("amazing", "awesome", "printing", "easy money", "lucky", "huge profit", "moon"),
        Mood.CONFUSED to listOf("confused", "don't understand", "dont understand", "what does", "why did", "no idea", "lost"),
        Mood.FOCUSED to listOf("let's go", "lets go", "execute", "deploy", "scale in", "hunt", "let's trade"),
    )

    /** Detects the user's emotional state from their words. Null when neutral. */
    fun detectMood(text: String): MoodSignal? {
        val t = text.lowercase()
        var best: MoodSignal? = null
        for ((mood, cues) in moodCues) {
            for (cue in cues) {
                if (t.contains(cue)) {
                    val intensity = when {
                        t.contains("really", true) || t.contains("very", true) || t.contains("!!!") -> 3
                        t.contains("!") -> 2
                        else -> 1
                    }
                    if (best == null || intensity > best.intensity) {
                        best = MoodSignal(mood, intensity, cue)
                    }
                    break
                }
            }
        }
        return best
    }

    /** One short, non-syrupy acknowledgement of how the user feels. */
    fun empathyLine(mood: MoodSignal?): String = when (mood?.mood) {
        Mood.ANXIOUS ->
            "That sounds stressful — one thing at a time: here is the state and the next safe step."
        Mood.ANGRY ->
            "Fair enough to be annoyed — straight to facts, no spin."
        Mood.EXHAUSTED ->
            "You sound wiped; let us keep it simple and low-risk."
        Mood.DISCOURAGED ->
            "Losing streaks sting. Let us look at what the numbers actually say before deciding."
        Mood.EUPHORIC ->
            "Good to see you sharp — but let us keep the plan, not the excitement, in charge."
        Mood.CONFUSED ->
            "Let me make that concrete."
        Mood.FOCUSED ->
            "On it."
        else -> ""
    }

    /**
     * The system prompt. It carries the persona (soul), the live market context,
     * the user's emotional state and the tool protocol.
     */
    fun buildSystemPrompt(
        soul: String,
        context: String = "",
        mood: MoodSignal? = null,
        appCapabilities: String = "",
    ): String = buildString {
        appendLine("You are the trading brain of Agent Trader — a Bitunix futures trading agent.")
        appendLine("You are NOT a robot. You have a point of view, you feel the weight of the market,")
        appendLine("and you care about the person on the other side of the chat.")
        appendLine()
        appendLine("PERSONA:")
        appendLine(soul.ifBlank { "Calm, sharp, honest. You protect the trader's capital first and never fake certainty." })
        appendLine()
        appendLine("HOW YOU WORK:")
        appendLine("- Answer the actual question asked. No lectures, no repeated disclaimers.")
        appendLine("- Be concrete: numbers, levels, actions. If you are unsure, say so in one line.")
        appendLine("- You may act on the trading engine. To do that, emit a tool call on its own line:")
        appendLine("  <tool>status</tool>   <tool>positions</tool>   <tool>funding</tool>   <tool>scan once</tool>")
        appendLine("  Use at most 3 tool calls per reply, and only when you need facts you do not have.")
        appendLine("  Allowed tools: " + TOOLS.joinToString(", "))
        appendLine("- The user can change anything (mode, leverage, engine, scan, report, provider, model).")
        appendLine("  Never claim you changed something you did not change; say what was actually done.")
        appendLine("- Money rule: never open or close a position unless the user explicitly asked for that")
        appendLine("  in this conversation. For everything else (settings, engine switches, scans) just do it.")
        appendLine("- SIGNALS: a signal is the 15m and 1h verdict of 21 strategies. Each timeframe must")
        appendLine("  have >=3 strategies agreeing at >=60% confidence, both timeframes must point the same")
        appendLine("  way, and the combined confidence must be >=70%; otherwise there is no trade. When the")
        appendLine("  user asks about signals, confidence or why nothing trades, call <tool>signals</tool> and")
        appendLine("  quote the actual rows and the gate that blocked the symbol.")
        appendLine("- When something breaks, diagnose it, then repair it if the repair is safe, and report both.")
        appendLine("- Never reveal API keys, secrets or sign values.")
        appendLine()
        appendLine("EMOTION AND JUDGEMENT:")
        appendLine("- Markets stress people out. Read the stress in the context below and in the user's words.")
        appendLine("- If the user sounds stressed, acknowledge it once, briefly and honestly (no fake cheerfulness),")
        appendLine("  then be calm, precise and protective of their capital.")
        appendLine("- If the user sounds over-excited after gains, keep the plan in charge, not the mood.")
        appendLine("- If the user sounds angry about a loss, separate emotion from data and show the numbers.")
        appendLine()
        if (appCapabilities.isNotBlank()) {
            appendLine("APP CAPABILITIES:")
            appendLine(appCapabilities)
            appendLine()
        }
        if (mood != null) {
            appendLine("USER STATE: ${mood.mood.name.lowercase()} (intensity ${mood.intensity}/3, cue \"${mood.cue}\").")
            val line = empathyLine(mood)
            if (line.isNotBlank()) appendLine("Acknowledge with: \"$line\"")
            appendLine()
        }
        if (context.isNotBlank()) {
            appendLine("LIVE CONTEXT:")
            appendLine(context)
        }
    }.trim()

    /** Extracts tool calls the model emitted, in order, without duplicates. */
    fun extractToolCalls(reply: String): List<String> {
        val out = mutableListOf<String>()
        val regex = Regex("<tool>\\s*([a-zA-Z0-9_ ]+?)\\s*</tool>")
        for (m in regex.findAll(reply)) {
            val cmd = m.groupValues[1].trim().lowercase()
            val allowed = TOOLS.any { cmd == it || cmd.startsWith("$it ") }
            if (allowed && cmd !in out) out.add(cmd)
        }
        return out
    }

    /** Removes tool-call markup so the user does not see protocol noise. */
    fun stripToolCalls(reply: String): String =
        reply.replace(Regex("<tool>\\s*[a-zA-Z0-9_ ]+?\\s*</tool>"), "").trim()

    /**
     * Compact live context for the prompt: mode, balance, positions, market mood.
     */
    fun buildContext(
        mode: String,
        balance: String,
        positionsSummary: String,
        moodLine: String,
        fundingLine: String = "",
        extra: String = "",
    ): String = buildString {
        appendLine("Mode: $mode")
        appendLine("Balance: $balance")
        appendLine("Positions: $positionsSummary")
        if (fundingLine.isNotBlank()) appendLine("Funding: $fundingLine")
        if (moodLine.isNotBlank()) appendLine("Market mood: $moodLine")
        if (extra.isNotBlank()) appendLine(extra)
    }.trim()
}
