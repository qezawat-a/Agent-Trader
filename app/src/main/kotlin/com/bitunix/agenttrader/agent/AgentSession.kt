package com.bitunix.agenttrader.agent

import com.bitunix.agenttrader.trader.AgentRuntimeError

/** One model answer. Tool markup is still inside [content]; the session parses it. */
data class ChatReply(val content: String)

/** The only thing the session needs from a provider. Tests supply a fake. */
fun interface ChatTransport {
    fun chat(model: String, messages: List<Pair<String, String>>, maxTokens: Int): ChatReply
}

/** Asks the person to approve one action. Blocks until answered; false = deny. */
fun interface ConsentGate {
    fun ask(command: String, reason: String): Boolean
}

enum class ReceiptStatus { OK, FAILED, BLOCKED }

/**
 * What actually happened for one tool call. This is rendered in the chat, so the
 * model's prose is never the only record of an action.
 */
data class Receipt(val command: String, val output: String, val status: ReceiptStatus) {

    private val icon: String
        get() = when (status) {
            ReceiptStatus.OK -> "⚙️"
            ReceiptStatus.FAILED -> "⚠️"
            ReceiptStatus.BLOCKED -> "🔒"
        }

    private val headline: String
        get() = command + when (status) {
            ReceiptStatus.OK -> ""
            ReceiptStatus.FAILED -> " — failed"
            ReceiptStatus.BLOCKED -> " — not run"
        }

    /** The line shown to the user. Short answers stay on one line, long dumps are indented. */
    fun render(): String = if (output.isBlank()) {
        "$icon $headline"
    } else if (output.lineSequence().count() <= 1 && output.length <= 120) {
        "$icon $headline → $output"
    } else {
        "$icon $headline\n" + output.lineSequence().joinToString("\n") { "   $it" }
    }

    /** The same facts for the model's second pass. */
    fun renderForModel(): String =
        "» $command [" + status.name.lowercase() + "]\n" + output.ifBlank { "(no output)" }
}

data class TurnOutcome(val answer: String, val receipts: List<Receipt>)

/**
 * One chat session: memory, the tool loop and the receipts.
 *
 * This used to live inside the Compose screen, which is why the loop's bugs
 * (ungated money tools, tool calls that never reached the engine, history that
 * crossed the PAPER/LIVE boundary) were invisible to the test suite. Keeping the
 * orchestration here makes it a plain class with no Android dependency.
 */
class AgentSession(
    private val transport: ChatTransport,
    private val execute: (String) -> String,
    private val consent: ConsentGate,
    private val maxToolCalls: Int = MAX_TOOL_CALLS,
    private val historyWindow: Int = HISTORY_WINDOW,
) {

    private val history = mutableListOf<Pair<String, String>>()
    private var sessionKey: String = ""

    /** Clears the memory whenever the mode, account, provider or model changes. */
    fun beginSession(key: String) {
        if (key != sessionKey) {
            history.clear()
            sessionKey = key
        }
    }

    /** Appends restored [pairs] so a restarted process resumes the saved thread. */
    fun appendHistory(pairs: List<Pair<String, String>>) {
        history.addAll(pairs)
    }

    fun clear() {
        history.clear()
    }

    fun historySize(): Int = history.size

    /**
     * Runs one turn: model → authorized tools → receipts → final answer.
     *
     * [onReceipt] is called for every tool call as soon as it is resolved, so the
     * UI can show the action record before the second model pass finishes.
     *
     * @throws IllegalStateException when the provider returns no text at all.
     */
    fun turn(
        model: String,
        systemPrompt: String,
        userText: String,
        onReceipt: (Receipt) -> Unit = {},
    ): TurnOutcome {
        history.add("user" to userText)
        val window = listOf("system" to systemPrompt) + history.takeLast(historyWindow)

        var convo = window
        var reply = ask(model, convo)
        val receipts = mutableListOf<Receipt>()
        val ran = mutableSetOf<String>()

        // Up to MAX_ROUNDS rounds: look → act → verify. Each round runs at most
        // [maxToolCalls] calls; a call already made this turn is not repeated.
        for (round in 1..MAX_ROUNDS) {
            val wanted = AgentBrain.extractToolCalls(reply)
                .filter { ran.add(it.lowercase()) }
                .take(maxToolCalls)
            if (wanted.isEmpty()) break
            val thisRound = wanted.map { command ->
                resolve(command, userText).also { receipts += it; onReceipt(it) }
            }
            // A further pass is only useful when something actually ran; otherwise
            // the model would be asked to comment on actions it never performed.
            if (thisRound.none { it.status != ReceiptStatus.BLOCKED }) break
            val more = if (round < MAX_ROUNDS) "If the results show a cause you can fix with a tool, " +
                "call it now; otherwise answer. " else ""
            convo = convo + listOf(
                "assistant" to reply,
                "user" to "Tool results:\n" +
                    thisRound.joinToString("\n") { it.renderForModel() } +
                    "\n" + more + "Answer the user using these results. Never claim an action " +
                    "marked blocked or failed was done.",
            )
            reply = ask(model, convo)
        }

        val answer = AgentBrain.stripToolCalls(reply).ifBlank {
            // The model answered only with tool markup. Say what really ran instead
            // of showing an empty bubble.
            receipts.lastOrNull()?.render() ?: "The model returned no text."
        }
        history.add("assistant" to answer)
        return TurnOutcome(answer, receipts)
    }

    private fun ask(model: String, messages: List<Pair<String, String>>): String {
        val reply = transport.chat(model, messages, MAX_TOKENS).content.trim()
        if (reply.isBlank()) {
            throw IllegalStateException(
                "The model returned an empty answer (no content). " +
                    "Private reasoning is not used as the answer — pick another model or retry.",
            )
        }
        // A successful provider call means the last provider failure is history.
        AgentRuntimeError.clear()
        return reply
    }

    private fun resolve(command: String, userText: String): Receipt =
        when (val decision = ToolPolicy.authorize(command, userText)) {
            is ToolDecision.Deny -> Receipt(command, decision.reason, ReceiptStatus.BLOCKED)

            is ToolDecision.Confirm ->
                if (consent.ask(command, decision.reason)) {
                    run(command)
                } else {
                    Receipt(command, "the user did not confirm — nothing was executed", ReceiptStatus.BLOCKED)
                }

            is ToolDecision.Allow -> run(command)
        }

    private fun run(command: String): Receipt = try {
        val output = execute(command).trim()
        // "No open positions" is a normal answer to a read-only query, not a failure.
        val failed = ToolPolicy.capabilityOf(command) != ToolCapability.READ && looksFailed(output)
        Receipt(command, output, if (failed) ReceiptStatus.FAILED else ReceiptStatus.OK)
    } catch (e: Throwable) {
        Receipt(command, e.message ?: e.javaClass.simpleName, ReceiptStatus.FAILED)
    }

    companion object {
        /** Hard cap enforced in code, not in the prompt. */
        const val MAX_TOOL_CALLS = 3

        /** Tool rounds per turn (look, act, verify). */
        const val MAX_ROUNDS = 3

        /** How many messages of memory the model sees. */
        const val HISTORY_WINDOW = 12

        // Reasoning models spend tokens thinking before the answer; 1024 left
        // some of them with empty content.
        private const val MAX_TOKENS = 4096

        /** Engine replies that mean the command was understood but did not take effect. */
        private val FAILURE_MARKERS = listOf(
            "usage:", "not supported", "failed", "error:", "cannot", "no open position",
            "no live position", "skipped", "skip ",
        )

        internal fun looksFailed(output: String): Boolean {
            val t = output.trim().lowercase()
            return FAILURE_MARKERS.any { t.startsWith(it) || t.contains("\n$it") }
        }
    }
}
