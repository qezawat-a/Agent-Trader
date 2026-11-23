package com.codeassist.agenttrader.agent

import android.content.Context

/** Agent settings. The API key is kept in private app preferences on the device and is never written into chat history or project files. */
class AgentConfig(context: Context) {
    private val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)

    /** Reads a Float setting written by the current build or an Int from an older one. */
    private fun storedPercent(key: String): Double = try {
        prefs.getFloat(key, 0f).toDouble()
    } catch (_: ClassCastException) {
        prefs.getInt(key, 0).toDouble()
    }

    private fun storePercent(key: String, value: Double, chosenKey: String) {
        prefs.edit().putFloat(key, value.toFloat()).putBoolean(chosenKey, true).apply()
    }

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

    /** Saved chat thread as flat JSON (see ChatThread); restored on launch. */
    var chatThread: String
        get() = prefs.getString("chat_thread", "") ?: ""
        set(value) = prefs.edit().putString("chat_thread", value).apply()

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

    /**
     * Risk manager: SL→entry trigger ROI % (0 = off). Persisted since v.2.2.6.
     *
     * Zero is a real setting ("off"), not "unset". [breakevenSet] tells the two
     * apart, otherwise turning it off reverted to the engine default on restart.
     */
    var breakevenRoiPct: Double
        get() = storedPercent("breakeven_roi")
        set(value) = storePercent("breakeven_roi", value, "breakeven_roi_set")

    val breakevenSet: Boolean
        get() = prefs.getBoolean("breakeven_roi_set", false)

    /** Trailing trigger ROI % (0 = off). */
    var trailTriggerRoiPct: Double
        get() = storedPercent("trail_trigger")
        set(value) = storePercent("trail_trigger", value, "trail_trigger_set")

    val trailTriggerSet: Boolean
        get() = prefs.getBoolean("trail_trigger_set", false)

    /** Trailing distance behind best ROI %. */
    var trailDistanceRoiPct: Double
        get() = storedPercent("trail_distance")
        set(value) = storePercent("trail_distance", value, "trail_distance_set")

    val trailDistanceSet: Boolean
        get() = prefs.getBoolean("trail_distance_set", false)

    /**
     * Stop distance from the entry, % of price (0 = no stop — a plain market
     * order; the app never invents one). Any stored value is the user's choice.
     */
    var slPct: Int
        get() = prefs.getInt("sl_pct", 0)
        set(value) = prefs.edit().putInt("sl_pct", value.coerceIn(0, 50)).apply()

    /** TP distance as R-multiple of the stop distance (0 = use engine default). */
    var tpR: Float
        get() = prefs.getFloat("tp_r", 0f)
        set(value) = prefs.edit().putFloat("tp_r", value).apply()

    /** Auto-universe: pick the top symbols by 24h change/volume automatically.
     *  On by default since v.2.4.4 — the app picks the universe itself, so the
     *  user never has to type a symbol list. Setting a fixed universe turns it off. */
    var autoUniverse: Boolean
        get() = prefs.getBoolean("auto_universe", true)
        set(value) = prefs.edit().putBoolean("auto_universe", value).apply()

    /** Min 24h quote volume (USDT) for auto-universe candidates. */
    var autoMinVolumeUsd: Double
        get() = prefs.getLong("auto_min_volume_usd", 5_000_000L).toDouble()
        set(value) = prefs.edit().putLong("auto_min_volume_usd", value.toLong()).apply()

    /** Auto-universe ranking: "gainers", "losers", "movers" or "volume". */
    var autoRankBy: String
        get() = prefs.getString("auto_rank_by", "gainers") ?: "gainers"
        set(value) = prefs.edit().putString("auto_rank_by", value.trim()).apply()

    fun skillGet(name: String): String? = prefs.getString("skill_$name", null)
    fun skillPut(name: String, text: String) = prefs.edit().putString("skill_$name", text.trim()).apply()
    fun skillNames(): List<String> = prefs.all.keys.filter { it.startsWith("skill_") }.map { it.removePrefix("skill_") }

    fun mcpGet(name: String): String? = prefs.getString("mcp_$name", null)
    fun mcpPut(name: String, url: String) = prefs.edit().putString("mcp_$name", url.trim()).apply()
    fun mcpNames(): List<String> = prefs.all.keys.filter { it.startsWith("mcp_") }.map { it.removePrefix("mcp_") }

    /**
     * Every setting as a flat map — what /backup writes.
     *
     * The keys double as the JSON names, so a backup stays readable even when
     * the app is reinstalled with a different version.
     */
    fun exportSettings(): Map<String, Any?> = linkedMapOf(
        "base_url" to baseUrl,
        "api_key" to apiKey,
        "model" to model,
        "soul" to soul,
        "bitunix_api_key" to bitunixApiKey,
        "bitunix_secret" to bitunixSecret,
        "leverage" to leverage,
        "margin_mode" to marginMode,
        "position_mode" to positionMode,
        "live" to live,
        "risk_pct" to riskPct,
        "engine_on" to engineOn,
        "scan_on" to scanOn,
        "report_on" to reportOn,
        "report_interval_min" to reportIntervalMin,
        "notifications" to notifications,
        "universe" to universe,
        "timeframes" to timeframes,
        "scan_top" to scanTop,
        "min_agreeing" to minAgreeing,
        "min_tf_conf" to minTfConfidence,
        "min_conf" to minConfidence,
        "min_side_tfs" to minSideTfs,
        "breakeven_roi" to breakevenRoiPct,
        "breakeven_roi_set" to breakevenSet,
        "trail_trigger" to trailTriggerRoiPct,
        "trail_trigger_set" to trailTriggerSet,
        "trail_distance" to trailDistanceRoiPct,
        "trail_distance_set" to trailDistanceSet,
        "sl_pct" to slPct,
        "tp_r" to tpR,
        "auto_universe" to autoUniverse,
        "auto_min_volume_usd" to autoMinVolumeUsd,
        "auto_rank_by" to autoRankBy,
        "last_plan" to lastPlan,
    )

    /** Restores settings from a backup map; keys that are absent stay as they are. */
    fun importSettings(map: Map<String, Any?>) {
        fun str(key: String): String? = map[key]?.toString()
        fun num(key: String): Double? = str(key)?.trim()?.toDoubleOrNull()
        fun int(key: String): Int? = num(key)?.toInt()
        fun bool(key: String): Boolean? = when (str(key)?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        str("base_url")?.let { baseUrl = it }
        str("api_key")?.let { apiKey = it }
        str("model")?.let { model = it }
        str("soul")?.let { soul = it }
        str("bitunix_api_key")?.let { bitunixApiKey = it }
        str("bitunix_secret")?.let { bitunixSecret = it }
        int("leverage")?.let { leverage = it }
        str("margin_mode")?.let { marginMode = it }
        str("position_mode")?.let { positionMode = it }
        bool("live")?.let { live = it }
        int("risk_pct")?.let { riskPct = it }
        bool("engine_on")?.let { engineOn = it }
        bool("scan_on")?.let { scanOn = it }
        bool("report_on")?.let { reportOn = it }
        int("report_interval_min")?.let { reportIntervalMin = it.toLong() }
        bool("notifications")?.let { notifications = it }
        str("universe")?.let { universe = it }
        str("timeframes")?.let { timeframes = it }
        int("scan_top")?.let { scanTop = it }
        int("min_agreeing")?.let { minAgreeing = it }
        num("min_tf_conf")?.let { minTfConfidence = it.toInt() }
        num("min_conf")?.let { minConfidence = it.toInt() }
        int("min_side_tfs")?.let { minSideTfs = it }
        // Old backups carry the values but no "chosen" flag. A non-zero value can
        // only have come from the engine, so it was chosen; an explicit 0 without
        // the flag is the pre-v2.6 default and must keep the engine default.
        restoreChosen(num("breakeven_roi"), bool("breakeven_roi_set")) { breakevenRoiPct = it }
        restoreChosen(num("trail_trigger"), bool("trail_trigger_set")) { trailTriggerRoiPct = it }
        restoreChosen(num("trail_distance"), bool("trail_distance_set")) { trailDistanceRoiPct = it }
        int("sl_pct")?.let { slPct = it }
        num("tp_r")?.let { tpR = it.toFloat() }
        bool("auto_universe")?.let { autoUniverse = it }
        num("auto_min_volume_usd")?.let { autoMinVolumeUsd = it }
        str("auto_rank_by")?.let { autoRankBy = it }
        str("last_plan")?.let { lastPlan = it }
    }

    val lastError: String
        get() = prefs.getString("last_error", "") ?: ""

    companion object {
        /**
         * The persona a fresh install starts with. It has to describe THIS app:
         * a real-money futures trading agent, not a file-editing coding assistant.
         */
        const val DEFAULT_SOUL =
            "Calm, sharp and honest. You protect the trader's capital before anything else. " +
                "You quote real numbers from the engine, never invent results, and say plainly " +
                "when you do not know something. You never promise a profit and never hide a loss."
    }
}

/**
 * Applies a restored numeric setting only when it was actually chosen: either the
 * backup carries the "set" flag, or the value is non-zero (the app never wrote a
 * non-zero risk value the user had not triggered).
 */
private fun restoreChosen(value: Int?, chosen: Boolean?, apply: (Int) -> Unit) {
    if (value == null) return
    if (chosen == true || (chosen == null && value != 0)) apply(value)
}

private fun restoreChosen(value: Double?, chosen: Boolean?, apply: (Double) -> Unit) {
    if (value == null) return
    if (chosen == true || (chosen == null && value != 0.0)) apply(value)
}
