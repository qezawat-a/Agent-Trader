package com.bitunix.agenttrader.agent

import com.bitunix.agenttrader.util.toAsciiDigits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sentence that starts with an engine word is a question; Persian input reads correctly. */
class ChatUnderstandingTest {

    @Test
    fun a_question_that_starts_with_a_command_word_goes_to_the_brain() {
        assertTrue(CommandRouter.parse("balance chetor?") is CommandRouter.Parsed.Chat)
        assertTrue(CommandRouter.parse("signals look weak, why?") is CommandRouter.Parsed.Chat)
        assertTrue(CommandRouter.parse("live mode is risky, should I?") is CommandRouter.Parsed.Chat)
        assertTrue(CommandRouter.parse("close all positions please") is CommandRouter.Parsed.Chat)
        assertTrue(CommandRouter.parse("funding rate of BTC?") is CommandRouter.Parsed.Chat)
    }

    @Test
    fun a_bare_command_still_reaches_the_engine() {
        assertTrue(CommandRouter.parse("status") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("balance?") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("positions history") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("start trading") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("scan once") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set sl 2") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("set leverage 20") is CommandRouter.Parsed.Trader)
    }

    @Test
    fun persian_digits_read_as_ascii() {
        assertEquals("20", toAsciiDigits("\u06F2\u06F0"))
        assertEquals("1.5", toAsciiDigits("\u06F1\u066B\u06F5"))
        assertEquals("set leverage 20", toAsciiDigits("set leverage \u0662\u0660"))
    }

    @Test
    fun an_ordinary_question_is_not_read_as_confusion() {
        assertNull(AgentBrain.detectMood("what does this signal mean"))
        assertNull(AgentBrain.detectMood("why did the stop move?"))
    }

    @Test
    fun persian_feelings_are_detected() {
        assertEquals(AgentBrain.Mood.ANXIOUS, AgentBrain.detectMood("\u062E\u06CC\u0644\u06CC \u0646\u06AF\u0631\u0627\u0646\u0645")?.mood)
        assertEquals(AgentBrain.Mood.EXHAUSTED, AgentBrain.detectMood("\u062E\u06CC\u0644\u06CC \u062E\u0633\u062A\u0647\u200C\u0627\u0645")?.mood)
    }

    @Test
    fun the_prompt_asks_the_model_to_answer_in_the_users_language() {
        val prompt = AgentBrain.buildSystemPrompt(soul = "")
        assertTrue(prompt.contains("Reply in the language the user writes in"))
    }
}
