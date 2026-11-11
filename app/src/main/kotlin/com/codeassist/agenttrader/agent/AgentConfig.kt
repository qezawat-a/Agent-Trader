package com.codeassist.agenttrader.agent

import android.content.Context

/** Agent settings. The API key is kept in private app preferences on the device and is never written into chat history or project files. */
class AgentConfig(context: Context) {
    private val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(value) = prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    /** Last plan/task the agent committed to, shown by /think. */
    var lastPlan: String
        get() = prefs.getString("last_plan", "") ?: ""
        set(value) = prefs.edit().putString("last_plan", value.trim()).apply()

    var soul: String
        get() = prefs.getString("soul", DEFAULT_SOUL) ?: DEFAULT_SOUL
        set(value) = prefs.edit().putString("soul", value).apply()

    /** Bitunix secret key, used only for signing live requests. Stored like the API key. */
    var bitunixApiKey: String
        get() = prefs.getString("bitunix_api_key", "") ?: ""
        set(value) = prefs.edit().putString("bitunix_api_key", value.trim()).apply()

    var bitunixSecret: String
        get() = prefs.getString("bitunix_secret", "") ?: ""
        set(value) = prefs.edit().putString("bitunix_secret", value.trim()).apply()

    var leverage: Int
        get() = prefs.getInt("leverage", 5)
        set(value) = prefs.edit().putInt("leverage", value).apply()

    var marginMode: String
        get() = prefs.getString("margin_mode", "ISOLATION") ?: "ISOLATION"
        set(value) = prefs.edit().putString("margin_mode", value).apply()

    var positionMode: String
        get() = prefs.getString("position_mode", "ONE_WAY") ?: "ONE_WAY"
        set(value) = prefs.edit().putString("position_mode", value).apply()

    var live: Boolean
        get() = prefs.getBoolean("live", false)
        set(value) = prefs.edit().putBoolean("live", value).apply()

    fun skillGet(name: String): String? = prefs.getString("skill_$name", null)
    fun skillPut(name: String, text: String) = prefs.edit().putString("skill_$name", text.trim()).apply()
    fun skillNames(): List<String> = prefs.all.keys.filter { it.startsWith("skill_") }.map { it.removePrefix("skill_") }

    fun mcpGet(name: String): String? = prefs.getString("mcp_$name", null)
    fun mcpPut(name: String, url: String) = prefs.edit().putString("mcp_$name", url.trim()).apply()
    fun mcpNames(): List<String> = prefs.all.keys.filter { it.startsWith("mcp_") }.map { it.removePrefix("mcp_") }

    var lastError: String
        get() = prefs.getString("last_error", "") ?: ""
        set(value) = prefs.edit().putString("last_error", value.trim()).apply()

    val maxSteps: Int = 128
    val thinking: String = "MAX"
    val autonomousSec: Int = 15
    val rateLimitWindow: Int = 128

    companion object {
        const val DEFAULT_SOUL =
            "You are CodeAssist's agent. Be precise, admit uncertainty, never invent results, " +
                "and ask before changing files unless auto-accept is on."
    }
}