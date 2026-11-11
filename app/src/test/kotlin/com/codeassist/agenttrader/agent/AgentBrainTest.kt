package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The brain: mood detection, prompt content and the tool protocol. */
class AgentBrainTest {

    @Test
    fun detects_anxiety_and_anger() {
        assertEquals(AgentBrain.Mood.ANXIOUS, AgentBrain.detectMood("I'm scared this position will blow up")?.mood)
        assertEquals(AgentBrain.Mood.ANGRY, AgentBrain.detectMood("this is ridiculous, I'm furious")?.mood)
        assertEquals(AgentBrain.Mood.EXHAUSTED, AgentBrain.detectMood("I'm so tired of this")?.mood)
        assertEquals(AgentBrain.Mood.DISCOURAGED, AgentBrain.detectMood("I keep losing money")?.mood)
        assertEquals(AgentBrain.Mood.EUPHORIC, AgentBrain.detectMood("we are printing, amazing")?.mood)
    }

    @Test
    fun neutral_message_has_no_mood() {
        assertNull(AgentBrain.detectMood("what is the price of BTCUSDT"))
        assertNull(AgentBrain.detectMood(""))
    }

    @Test
    fun intensity_rises_with_emphasis() {
        val soft = AgentBrain.detectMood("I am a bit worried")
        val loud = AgentBrain.detectMood("I am really worried!!!")
        assertNotNull(soft)
        assertNotNull(loud)
        assertTrue(loud.intensity > soft.intensity)
    }

    @Test
    fun empathy_line_is_short_and_only_for_emotional_moods() {
        assertTrue(AgentBrain.empathyLine(AgentBrain.detectMood("I'm scared")).isNotBlank())
        assertEquals("", AgentBrain.empathyLine(null))
        assertEquals("", AgentBrain.empathyLine(AgentBrain.detectMood("price please")))
    }

    @Test
    fun prompt_carries_soul_context_and_mood() {
        val mood = AgentBrain.detectMood("I'm worried about the market")!!
        val prompt = AgentBrain.buildSystemPrompt(
            soul = "Sharp and protective.",
            context = "Mode: LIVE\nBalance: 1000 USDT",
            mood = mood,
            appCapabilities = "Chat commands: /help",
        )
        assertTrue(prompt.contains("Sharp and protective."))
        assertTrue(prompt.contains("Balance: 1000 USDT"))
        assertTrue(prompt.contains("worried"), prompt.take(200))
        assertTrue(prompt.contains("emotion", ignoreCase = true))
        assertTrue(prompt.contains("<tool>"))
    }

    @Test
    fun tool_calls_are_extracted_and_filtered() {
        val reply = "Let me check.\n<tool>status</tool>\n<tool>positions</tool>\n<tool>rm -rf /</tool>"
        val tools = AgentBrain.extractToolCalls(reply)
        assertEquals(listOf("status", "positions"), tools)
    }

    @Test
    fun tool_calls_keep_symbols_and_are_deduplicated() {
        val tools = AgentBrain.extractToolCalls("<tool>close position BTCUSDT</tool><tool>status</tool><tool>status</tool>")
        assertEquals(listOf("close position btcusdt", "status"), tools)
    }

    @Test
    fun tool_markup_is_stripped_from_the_user_reply() {
        val reply = "Checking now.\n<tool>balance</tool>\nDone."
        assertEquals("Checking now.\n\nDone.", AgentBrain.stripToolCalls(reply))
        assertFalse(AgentBrain.stripToolCalls(reply).contains("<tool>"))
    }

    @Test
    fun context_is_compact_and_complete() {
        val ctx = AgentBrain.buildContext(
            mode = "PAPER",
            balance = "1000.00 USDT",
            positionsSummary = "none",
            moodLine = "CALM (stress 5/100)",
            fundingLine = "",
        )
        assertTrue(ctx.contains("Mode: PAPER"))
        assertTrue(ctx.contains("Balance: 1000.00 USDT"))
        assertTrue(ctx.contains("Positions: none"))
        assertTrue(ctx.contains("Market mood: CALM"))
    }
}
