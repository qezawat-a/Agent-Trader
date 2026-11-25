package com.bitunix.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The chat session: authority gating, consent, the 3-call cap, receipts and
 * memory. These are the behaviours that used to live inline in the Compose
 * screen where nothing could test them.
 */
class AgentSessionTest {

    /** Records every prompt and hands out queued replies. */
    private class FakeTransport {
        val prompts = mutableListOf<List<Pair<String, String>>>()
        val replies = ArrayDeque<String>()

        val transport: ChatTransport = ChatTransport { _, messages, _ ->
            prompts += messages
            ChatReply(replies.removeFirstOrNull() ?: "")
        }
    }

    private fun session(
        t: FakeTransport,
        execute: (String) -> String = { "ok" },
        consent: (String, String) -> Boolean = { _, _ -> false },
    ): AgentSession = AgentSession(t.transport, execute, consent)

    @Test
    fun read_tools_run_without_bothering_the_user() {
        val t = FakeTransport()
        t.replies += "<tool>status</tool>"
        t.replies += "All good."
        val executed = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val s = session(t, { executed += it; "engine says ok" }, { cmd, _ -> asked += cmd; false })

        s.beginSession("test")
        val out = s.turn("model", "sys", "how is it going")

        assertEquals(listOf("status"), executed)
        assertTrue(asked.isEmpty(), "a read must not open the consent dialog")
        assertEquals(ReceiptStatus.OK, out.receipts.single().status)
    }

    @Test
    fun a_denied_money_tool_is_never_executed() {
        val t = FakeTransport()
        t.replies += "<tool>close position BTCUSDT</tool>"
        var executed = 0
        val asked = mutableListOf<String>()
        val s = session(t, { executed++; "closed" }, { cmd, _ -> asked += cmd; false })

        s.beginSession("test")
        val out = s.turn("model", "sys", "close my position")

        assertEquals(0, executed, "denied consent must not reach the engine")
        assertEquals(listOf("close position BTCUSDT"), asked)
        assertEquals(ReceiptStatus.BLOCKED, out.receipts.single().status)
        // No tool actually ran, so the model is not asked to comment on results.
        assertEquals(1, t.prompts.size)
    }

    @Test
    fun a_confirmed_money_tool_runs_and_reports() {
        val t = FakeTransport()
        t.replies += "<tool>close position BTCUSDT</tool>"
        t.replies += "Done, it is closed."
        val executed = mutableListOf<String>()
        val s = session(t, { executed += it; "position closed" }, { _, _ -> true })

        s.beginSession("test")
        val out = s.turn("model", "sys", "close it")

        assertEquals(listOf("close position BTCUSDT"), executed)
        assertEquals(ReceiptStatus.OK, out.receipts.single().status)
        assertEquals("Done, it is closed.", out.answer)
    }

    @Test
    fun at_most_three_tool_calls_run_per_turn() {
        val t = FakeTransport()
        t.replies += "<tool>status</tool><tool>balance</tool><tool>pnl</tool><tool>positions</tool>"
        t.replies += "Here is everything."
        val executed = mutableListOf<String>()
        val s = session(t, { executed += it; "ok" }, { _, _ -> true })

        s.beginSession("test")
        val out = s.turn("model", "sys", "give me the full picture")

        assertEquals(3, executed.size, "the cap is enforced in code, not in the prompt")
        assertEquals(3, out.receipts.size)
    }

    @Test
    fun an_empty_model_answer_is_an_error_not_a_silent_bubble() {
        val t = FakeTransport()
        t.replies += "   "
        val s = session(t)
        s.beginSession("test")

        assertFailsWith<IllegalStateException> { s.turn("model", "sys", "hello") }
    }

    @Test
    fun tool_results_reach_the_second_model_pass() {
        val t = FakeTransport()
        t.replies += "<tool>balance</tool>"
        t.replies += "Your balance is 1000 USDT."
        val s = session(t, { "Balance: 1000.00 USDT" }, { _, _ -> true })

        s.beginSession("test")
        val out = s.turn("model", "sys", "what is my balance")

        assertEquals(2, t.prompts.size)
        val followUp = t.prompts.last()
        assertEquals(4, followUp.size) // system, user, assistant, tool results
        val results = followUp.last().second
        assertTrue(results.contains("balance"), results)
        assertTrue(results.contains("1000"), results)
        assertTrue(out.answer.contains("1000"))
    }

    @Test
    fun a_turn_where_everything_was_blocked_has_no_second_pass() {
        val t = FakeTransport()
        t.replies += "<tool>close all</tool>"
        val s = session(t, { "closed" }, { _, _ -> false })

        s.beginSession("test")
        val out = s.turn("model", "sys", "close everything")

        assertEquals(1, t.prompts.size)
        // The blank answer falls back to the receipt: the user still sees what
        // was (not) done instead of an empty bubble.
        assertTrue(out.answer.contains("close all"))
        assertTrue(out.answer.contains("not run"))
    }

    @Test
    fun memory_resets_when_the_session_identity_changes() {
        val t = FakeTransport()
        val s = session(t)

        t.replies += "hi there"
        s.beginSession("PAPER")
        s.turn("model", "sys", "hello")

        t.replies += "again answered"
        s.turn("model", "sys", "second turn")
        assertTrue(t.prompts.last().any { it.second == "hello" }, "memory kept within a session")

        t.replies += "fresh answer"
        s.beginSession("LIVE") // mode changed → memory must drop
        val out = s.turn("model", "sys", "new life")
        assertTrue(t.prompts.last().none { it.second == "hello" }, "memory crossed the PAPER/LIVE boundary")
        assertEquals("fresh answer", out.answer)
    }

    @Test
    fun failed_engine_output_gets_a_failed_receipt_but_reads_do_not() {
        val t = FakeTransport()
        t.replies += "<tool>positions</tool><tool>close all</tool>"
        t.replies += "explained."
        val s = session(
            t,
            { cmd -> if (cmd.startsWith("close")) "Usage: close all — nothing to close" else "No open position" },
            { _, _ -> true },
        )

        s.beginSession("test")
        val out = s.turn("model", "sys", "what is open")

        assertEquals(ReceiptStatus.OK, out.receipts[0].status, "no open positions is a valid answer")
        assertEquals(ReceiptStatus.FAILED, out.receipts[1].status)
    }

    @Test
    fun on_receipt_fires_for_every_resolved_tool_in_order() {
        val t = FakeTransport()
        t.replies += "<tool>status</tool><tool>close all</tool>"
        t.replies += "explained."
        val seen = mutableListOf<Receipt>()
        val s = session(t, { "ok" }, { _, _ -> true })

        s.beginSession("test")
        s.turn("model", "sys", "status then close", onReceipt = { seen += it })

        assertEquals(listOf("status", "close all"), seen.map { it.command })
    }
}
