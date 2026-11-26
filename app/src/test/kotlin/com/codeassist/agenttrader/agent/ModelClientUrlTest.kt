package com.codeassist.agenttrader.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The /v1 normalization rules for pasted provider base URLs. */
class ModelClientUrlTest {

    private fun client(base: String) = ModelClient(base, "sk-test")

    @Test
    fun plain_host_appends_v1() {
        assertEquals("https://api.openai.com/v1/models", client("https://api.openai.com").url("models"))
    }

    @Test
    fun trailing_slash_is_trimmed_before_appending() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            client("https://api.openai.com/").url("chat/completions"),
        )
    }

    @Test
    fun existing_v1_is_left_alone() {
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            client("https://openrouter.ai/api/v1").url("models"),
        )
    }

    @Test
    fun versioned_paths_like_v1beta_are_left_alone() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            client("https://generativelanguage.googleapis.com/v1beta/openai").url("models"),
        )
    }

    @Test
    fun path_ending_in_api_is_left_alone() {
        assertEquals("https://example.com/api/models", client("https://example.com/api").url("models"))
    }

    @Test
    fun leading_slash_on_path_does_not_double() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", client("https://api.deepseek.com").url("/chat/completions"))
    }

    @Test
    fun blank_base_url_is_rejected() {
        val e = assertFailsWith<IllegalArgumentException> { client("").url("models") }
        assertEquals(true, e.message!!.contains("Base URL"))
    }

    @Test
    fun blank_api_key_is_rejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            ModelClient("https://api.openai.com", "").url("models")
        }
        assertEquals(true, e.message!!.contains("API key"))
    }
}
