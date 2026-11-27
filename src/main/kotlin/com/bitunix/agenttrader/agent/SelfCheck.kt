package com.bitunix.agenttrader.agent

/**
 * Real, runnable self-tests for the app itself.
 *
 * [SelfRepair] answers "did one of the known rules fire?" — it cannot answer
 * "is the thing the user complained about actually broken?". These checks touch
 * the real subsystems (provider HTTP, model reply, exchange price, private
 * feed, balance freshness, engine flags, notification permission) and always
 * return evidence, so the agent can show what it ran instead of answering
 * "No issues found."
 *
 * Every check is a plain value so the rendering and the prompts stay pure and
 * unit-testable; the caller supplies the network/engine probes.
 */
object SelfCheck {

    enum class Status { PASS, FAIL, SKIP }

    data class Check(val name: String, val status: Status, val detail: String) {
        fun line(): String = when (status) {
            Status.PASS -> "✔ $name — $detail"
            Status.FAIL -> "✖ $name — $detail"
            Status.SKIP -> "– $name — $detail"
        }
    }

    fun pass(name: String, detail: String) = Check(name, Status.PASS, detail)
    fun fail(name: String, detail: String) = Check(name, Status.FAIL, detail)
    fun skip(name: String, detail: String) = Check(name, Status.SKIP, detail)

    /**
     * Runs [block]; a thrown exception becomes a FAIL carrying the exception
     * text. A probe that blew up must never look like a pass.
     */
    fun guarded(name: String, block: () -> Check): Check = try {
        block()
    } catch (e: Exception) {
        fail(name, "threw ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
    }

    fun counts(checks: List<Check>): String =
        "${checks.count { it.status == Status.PASS }} passed, " +
            "${checks.count { it.status == Status.FAIL }} failed, " +
            "${checks.count { it.status == Status.SKIP }} skipped"

    fun render(checks: List<Check>): String = checks.joinToString("\n") { it.line() }

    /**
     * Compact evidence block handed to the model. It contains everything the
     * app can observe, so the model never has to guess about the runtime.
     */
    fun evidenceBlock(checks: List<Check>, ruleFindings: String, runtime: String): String = buildString {
        appendLine("Live checks (${counts(checks)}):")
        appendLine(render(checks))
        appendLine()
        appendLine("Rule findings:")
        appendLine(ruleFindings.ifBlank { "none" })
        if (runtime.isNotBlank()) {
            appendLine()
            appendLine("Runtime snapshot:")
            appendLine(runtime)
        }
    }.trim()

    /**
     * System prompt for self-debugging. The explicit bans matter: the old
     * behaviour ("No problems found. Everything looks healthy.") is exactly
     * what the user complained about.
     */
    fun analystSystemPrompt(appCapabilities: String = ""): String = buildString {
        appendLine("You are the self-debugger of Agent Trader — the app you are running inside.")
        appendLine("You receive the user's report plus checks the app actually ran. Reason from evidence.")
        appendLine()
        appendLine("HARD RULES:")
        appendLine("- Never answer \"no issues found\", \"everything looks fine\", \"no problems\" or any reassurance")
        appendLine("  when the user says something is wrong. A passing check proves only that the probe passed.")
        appendLine("- First line: what the evidence says about the user's exact complaint.")
        appendLine("- Then the 2-3 most likely causes, ranked, each with the one command or screenshot that")
        appendLine("  confirms or kills it. Use the check names and their values.")
        appendLine("- If the evidence cannot explain the report, say that in one line and list what you still need.")
        appendLine("- You cannot edit app source from here. If the root cause is in code, name the file/function")
        appendLine("  that must change and what the fix is.")
        appendLine("- Be concrete and short: max 12 lines, no generic advice, no disclaimers.")
        if (appCapabilities.isNotBlank()) {
            appendLine()
            appendLine("WHAT THIS APP CAN RUN (answer with these commands only):")
            appendLine(appCapabilities)
        }
    }.trim()
}
