package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelPickTest {

    @Test
    fun prefers_a_cheap_fast_model_for_a_phone_app() {
        val models = listOf(
            "ft:chat-x-large", "gpt-4o", "gpt-4o-mini", "gpt-5", "claude-sonnet-4",
        )
        assertEquals("gpt-4o-mini", ModelClient.pickModel(models))
    }

    @Test
    fun falls_back_to_other_families_in_order() {
        assertEquals("claude-haiku-3", ModelClient.pickModel(listOf("claude-haiku-3", "claude-sonnet-6")))
        assertEquals("gemini-2.0-flash", ModelClient.pickModel(listOf("gemini-2.0-flash", "qwen-max")))
        assertEquals("deepseek-chat", ModelClient.pickModel(listOf("deepseek-chat", "llama-3")))
    }

    @Test
    fun unknown_providers_just_use_the_first_model() {
        assertEquals("some-model-a", ModelClient.pickModel(listOf("some-model-a", "some-model-b")))
    }

    @Test
    fun an_empty_list_yields_an_empty_name() {
        assertEquals("", ModelClient.pickModel(emptyList()))
        assertTrue(ModelClient.pickModel(listOf("x")).isNotBlank())
    }
}
