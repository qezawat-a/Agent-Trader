package com.codeassist.agenttrader.agent

import com.codeassist.agenttrader.bitunix.BitunixRest
import com.codeassist.agenttrader.trader.TraderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The brain must work with a zero-credit key: free-tier models are auto-picked
 * first, any model id is accepted (nothing hard-capped), and credit/not-found
 * errors come back as an actionable hint instead of a raw JSON dump.
 */
class ModelPickTest {

    @Test
    fun free_tier_models_are_picked_first() {
        val picked = ModelClient.pickModel(
            listOf("openai/gpt-5", "anthropic/claude-sonnet-4", "deepseek/deepseek-chat-v3:free"),
        )
        assertEquals("deepseek/deepseek-chat-v3:free", picked)
    }

    @Test
    fun known_chat_models_still_rank_when_no_free_one_exists() {
        val picked = ModelClient.pickModel(
            listOf("gemini/gemini-2.0-flash", "openai/gpt-4o-mini", "openai/gpt-5"),
        )
        assertEquals("openai/gpt-4o-mini", picked)
    }

    @Test
    fun embeddings_and_non_chat_ids_are_never_picked() {
        val picked = ModelClient.pickModel(
            listOf("text-embedding-3-large", "whisper-1", "gpt-4o-mini"),
        )
        assertEquals("gpt-4o-mini", picked)
    }

    @Test
    fun any_model_id_the_user_types_is_accepted() {
        // Nothing is hard-capped: pickModel only ranks, it never validates or blocks.
        assertEquals("kc/whatever-weird-id", ModelClient.pickModel(listOf("kc/whatever-weird-id")))
    }

    @Test
    fun credit_errors_come_back_as_an_actionable_hint() {
        val hint = ModelClient.friendlyHttpHint(402, """{"error":"credits required"}""")
        assertTrue(hint.contains("no credits"), hint)
        assertTrue(hint.contains("free"), hint)
    }

    @Test
    fun unknown_model_errors_point_to_the_model_list() {
        val hint = ModelClient.friendlyHttpHint(404, """{"error_type":"model_not_found"}""")
        assertTrue(hint.contains("List models"), hint)
    }

    @Test
    fun other_errors_stay_unhinted() {
        assertEquals("", ModelClient.friendlyHttpHint(401, "invalid key"))
        assertEquals("", ModelClient.friendlyHttpHint(500, "boom"))
    }

    @Test
    fun set_symbols_and_set_symbol_are_aliases_for_set_universe() {
        val engine = TraderEngine(BitunixRest("k", "s"))
        val reply = engine.handle("set symbols ADAUSDT")
        assertTrue(reply.contains("Universe set"), reply)
        assertEquals(listOf("ADAUSDT"), engine.universe.toList())

        engine.handle("set symbol INITUSDT")
        assertEquals(listOf("INITUSDT"), engine.universe.toList())
    }
}
