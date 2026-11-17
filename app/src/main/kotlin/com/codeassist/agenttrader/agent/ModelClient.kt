package com.codeassist.agenttrader.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenAI-compatible chat client.
 *
 * Robustness rules learned the hard way:
 *  - Base URLs are often pasted without /v1 (e.g. https://api.openai.com). We append /v1
 *    when the path has no version segment; /v1beta or /openai paths are left untouched.
 *  - Reasoning models may return "content": null and put a chain of thought in
 *    "reasoning_content". That trace is NEVER used as the answer: it is private
 *    model output, it can contain tool-shaped text, and showing it to the user
 *    would be a lie about what the model said. An empty content is an error the
 *    caller surfaces explicitly.
 *  - OpenAI o-series rejects "max_tokens" and asks for "max_completion_tokens"; we retry
 *    once with the renamed field when the error says so.
 *  - 429/5xx get two retries with backoff. API keys are redacted from every error message.
 */
class ModelClient(private val baseUrl: String, private val apiKey: String) {

    /** Redacts the API key from error messages so it never leaks to logs/UI. */
    private fun String.redact(): String = if (apiKey.isBlank()) this else replace(apiKey, "***")

    data class Probe(val model: String, val ok: Boolean, val detail: String)

    fun listModels(): List<String> {
        val json = JSONObject(request("GET", url("models"), null))
        val data: JSONArray = json.optJSONArray("data")
            ?: json.optJSONArray("models")
            ?: return emptyList()
        val ids = (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "").ifBlank { o.optString("name", "") }
            id.takeIf { it.isNotBlank() }
        }
        return ids.distinct()
    }

    /**
     * AutoSetModelByKey: pick a usable default model from whatever the key can see.
     * Cheap fast models first — this app chats a lot — then bigger names, then
     * just the first entry the provider lists.
     */
    companion object {
        private val PREFERRED = listOf(
            Regex("gpt-4o-mini"), Regex("gpt-4\\.1-(mini|nano)"), Regex("gpt-5-mini"),
            Regex("gpt-5"), Regex("o4-mini"), Regex("gpt-4o"),
            Regex("claude-[0-9.]*haiku"), Regex("claude-[0-9.]*sonnet"), Regex("claude"),
            Regex("gemini-[0-9.]*flash"), Regex("gemini"),
            Regex("deepseek"), Regex("qwen"), Regex("glm-[45]"), Regex("llama"),
        )

        /** Ids that are not chat models — never auto-picked. */
        private val NOT_CHAT = Regex("embed|whisper|tts|dall-e|dalle|image|audio|moderation", RegexOption.IGNORE_CASE)

        /**
         * Picks a usable default model. Nothing is hard-capped — any id the provider
         * lists is allowed. Preference: 1) a free-tier id (":free" suffix, e.g.
         * OpenRouter — works with a zero-credit key), 2) known cheap/fast chat models,
         * 3) the first non-embedding entry.
         */
        fun pickModel(models: List<String>): String {
            models.firstOrNull { it.endsWith(":free") }?.let { return it }
            val chat = models.filter { !NOT_CHAT.containsMatchIn(it) }
            val pool = chat.ifEmpty { models }
            for (pattern in PREFERRED) {
                pool.firstOrNull { pattern.containsMatchIn(it) }?.let { return it }
            }
            return pool.firstOrNull() ?: models.firstOrNull() ?: ""
        }

        /** A short human hint appended to provider errors the user can act on. */
        internal fun friendlyHttpHint(code: Int, detail: String): String = when {
            code == 402 || detail.contains("usage_limit") || detail.contains("credits") ->
                " → your key has no credits. Pick a FREE model: Settings → Auto pick, " +
                    "or type any id ending in ':free' (e.g. deepseek/deepseek-chat:free)."
            code == 404 && detail.contains("model") ->
                " → model id not found. Settings → 'List models' shows the ids your key can use."
            else -> ""
        }
    }

    /**
     * Probes at most [limit] candidates and stops at the first one that answers.
     *
     * `/models` used to probe every id the provider listed: 300 models meant 300
     * billable calls, 429 retries and a chat that could not answer until they all
     * finished. Preference order is the caller's, so a free/cheap model is tried
     * first instead of whatever the provider happened to list first.
     */
    fun probeWorking(candidates: List<String>, limit: Int = 5): List<Probe> {
        val out = mutableListOf<Probe>()
        for (model in candidates.filter { it.isNotBlank() }.distinct().take(limit.coerceAtLeast(1))) {
            val probe = try {
                val reply = chat(model, listOf("user" to "ping"), maxTokens = 16)
                Probe(model, true, if (reply.isBlank()) "ok (empty content)" else "ok")
            } catch (e: Exception) {
                Probe(model, false, e.message?.redact() ?: e.javaClass.simpleName)
            }
            out += probe
            if (probe.ok) break
        }
        return out
    }

    /** One-shot chat completion. [messages] are (role, content) pairs, oldest first. */
    fun chat(model: String, messages: List<Pair<String, String>>, maxTokens: Int = 1024): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().also { arr ->
                    messages.forEach { (role, content) ->
                        arr.put(JSONObject().put("role", role).put("content", content))
                    }
                },
            )
        val json = try {
            request("POST", url("chat/completions"), body.toString())
        } catch (e: RenamedMaxTokens) {
            // o-series style: retry once with max_completion_tokens.
            body.remove("max_tokens")
            body.put("max_completion_tokens", maxTokens)
            request("POST", url("chat/completions"), body.toString())
        }
        val choices = JSONObject(json).optJSONArray("choices")
            ?: throw IllegalStateException("Malformed reply: no choices. ${json.take(200).redact()}")
        if (choices.length() == 0) throw IllegalStateException("Empty choices array.")
        val msg = choices.getJSONObject(0).optJSONObject("message") ?: JSONObject()
        // Content only. A blank answer stays blank so the caller can report an
        // empty response instead of leaking the private reasoning trace.
        return msg.optString("content", "").trim()
    }

    /** Appends /v1 when the URL has no version segment (v1, v1beta, v2, openai...). */
    internal fun url(path: String): String {
        require(baseUrl.isNotBlank()) { "Base URL is not set. Use /connect_provider or Settings." }
        require(apiKey.isNotBlank()) { "API key is not set. Use /connect_provider or Settings." }
        val clean = baseUrl.trim().trimEnd('/')
        val hasVersion = Regex("(/v[0-9]|/openai|/api)$").containsMatchIn(clean.lowercase())
        return (if (hasVersion) clean else "$clean/v1") + "/" + path.trimStart('/')
    }

    /** Thrown when the provider asks for max_completion_tokens instead of max_tokens. */
    private class RenamedMaxTokens(val body: String) : RuntimeException()

    private fun request(method: String, url: String, body: String?): String {
        var lastError: String? = null
        repeat(3) { attempt ->
            try {
                return requestOnce(method, url, body)
            } catch (e: RetryableHttp) {
                lastError = e.message
                if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw IllegalStateException((lastError ?: "Request failed").redact())
    }

    private fun requestOnce(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            // Redact BEFORE the body becomes an exception message: the provider
            // body can echo the Authorization header back.
            val detail = text.take(300).redact()
            if (detail.contains("max_completion_tokens")) throw RenamedMaxTokens(detail)
            if (code == 429 || code >= 500) throw RetryableHttp("HTTP $code: $detail")
            throw IllegalStateException("HTTP $code: $detail${friendlyHttpHint(code, detail)}")
        }
        return text
    }

    private class RetryableHttp(message: String) : RuntimeException(message)

}
