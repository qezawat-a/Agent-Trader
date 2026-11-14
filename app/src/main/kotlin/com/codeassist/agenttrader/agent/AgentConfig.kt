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

    // ---- Engine settings that survive restarts ----

    /** Margin per trade as a percent of balance (1..100). */
    var riskPct: Int
        get() = prefs.getInt("risk_pct", 10)
        set(value) = prefs.edit().putInt("risk_pct", value).apply()

    var engineOn: Boolean
        get() = prefs.getBoolean("engine_on", false)
        set(value) = prefs.edit().putBoolean("engine_on", value).apply()

    var scanOn: Boolean
        get() = prefs.getBoolean("scan_on", false)
        set(value) = prefs.edit().putBoolean("scan_on", value).apply()

    var reportOn: Boolean
        get() = prefs.getBoolean("report_on", false)
        set(value) = prefs.edit().putBoolean("report_on", value).apply()

    var reportIntervalMin: Long
        get() = prefs.getLong("report_interval_min", 15L)
        set(value) = prefs.edit().putLong("report_interval_min", value).apply()

    /** Status-bar notifications for trade events and reports (Control screen toggle). */
    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(value) = prefs.edit().putBoolean("notifications", value).apply()

    /** Scanner universe as comma-separated symbols, e.g. "BTCUSDT,ETHUSDT". */
    var universe: String
        get() = prefs.getString("universe", "") ?: ""
        set(value) = prefs.edit().putString("universe", value).apply()

    /** Signal timeframes as comma-separated kline intervals, e.g. "15m,1h". */
    var timeframes: String
        get() = prefs.getString("timeframes", "") ?: ""
        set(value) = prefs.edit().putString("timeframes", value).apply()

    var scanTop: Int
        get() = prefs.getInt("scan_top", 10)
        set(value) = prefs.edit().putInt("scan_top", value).apply()

    var minAgreeing: Int
        get() = prefs.getInt("min_agreeing", 3)
        set(value) = prefs.edit().putInt("min_agreeing", value).apply()

    var minTfConfidence: Int
        get() = prefs.getInt("min_tf_conf", 60)
        set(value) = prefs.edit().putInt("min_tf_conf", value).apply()

    var minConfidence: Int
        get() = prefs.getInt("min_conf", 70)
        set(value) = prefs.edit().putInt("min_conf", value).apply()

    /** How many timeframes must share one side. 0 = all must agree (strict default). */
    var minSideTfs: Int
        get() = prefs.getInt("min_side_tfs", 0)
        set(value) = prefs.edit().putInt("min_side_tfs", value).apply()

    /** Risk manager: SL→entry trigger ROI % (0 = off). Persisted since v.2.2.6. */
    var breakevenRoiPct: Int
        get() = prefs.getInt("breakeven_roi", 0)
        set(value) = prefs.edit().putInt("breakeven_roi", value).apply()

    /** Trailing trigger ROI % (0 = off). */
    var trailTriggerRoiPct: Int
        get() = prefs.getInt("trail_trigger", 0)
        set(value) = prefs.edit().putInt("trail_trigger", value).apply()

    /** Trailing distance behind best ROI %. */
    var trailDistanceRoiPct: Int
        get() = prefs.getInt("trail_distance", 0)
        set(value) = prefs.edit().putInt("trail_distance", value).apply()

    /** Initial stop as % of the way to liquidation (0 = use engine default). */
    var stopFracPct: Int
        get() = prefs.getInt("stop_frac", 0)
        set(value) = prefs.edit().putInt("stop_frac", value).apply()

    /** TP distance as R-multiple of the stop distance (0 = use engine default). */
    var tpR: Float
        get() = prefs.getFloat("tp_r", 0f)
        set(value) = prefs.edit().putFloat("tp_r", value).apply()

    /** Live-entry minimum futures balance in USDT (0 = off). */
    var minLiveBalance: Float
        get() = prefs.getFloat("min_live_balance", 5f)
        set(value) = prefs.edit().putFloat("min_live_balance", value).apply()

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