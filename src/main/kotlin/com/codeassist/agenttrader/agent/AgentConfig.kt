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

    /**
     * Risk manager: SL→entry trigger ROI % (0 = off). Persisted since v.2.2.6.
     *
     * Zero is a real setting ("off"), not "unset". [breakevenSet] tells the two
     * apart, otherwise turning it off reverted to the engine default on restart.
     */
    var breakevenRoiPct: Int
        get() = prefs.getInt("breakeven_roi", 0)
        set(value) = prefs.edit().putInt("breakeven_roi", value).putBoolean("breakeven_roi_set", true).apply()

    val breakevenSet: Boolean
        get() = prefs.getBoolean("breakeven_roi_set", false)

    /** Trailing trigger ROI % (0 = off). */
    var trailTriggerRoiPct: Int
        get() = prefs.getInt("trail_trigger", 0)
        set(value) = prefs.edit().putInt("trail_trigger", value).putBoolean("trail_trigger_set", true).apply()

    val trailTriggerSet: Boolean
        get() = prefs.getBoolean("trail_trigger_set", false)

    /** Trailing distance behind best ROI %. */
    var trailDistanceRoiPct: Int
        get() = prefs.getInt("trail_distance", 0)
        set(value) = prefs.edit().putInt("trail_distance", value).putBoolean("trail_distance_set", true).apply()

    val trailDistanceSet: Boolean
        get() = prefs.getBoolean("trail_distance_set", false)

    /** Initial stop as % of the way to liquidation (0 = use engine default). */
    var stopFracPct: Int
        get() = prefs.getInt("stop_frac", 0)
        set(value) = prefs.edit().putInt("stop_frac", value).putBoolean("stop_frac_set", true).apply()

    val stopFracSet: Boolean
        get() = prefs.getBoolean("stop_frac_set", false)

    /**
     * How many ATRs away the stop must sit from the entry (0 = off).
     *
     * The stop has to be outside the symbol's own noise, or the market gaps
     * through it. The default is 1.5 ATRs.
     */
    var stopAtrMultiple: Float
        get() = prefs.getFloat("stop_atr", com.codeassist.agenttrader.trader.TpSlPlanner.STOP_ATR_MULTIPLE.toFloat())
        set(value) = prefs.edit().putFloat("stop_atr", value.coerceIn(0f, 10f)).apply()

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

    /** Liquidation guard: close a position when its loss reaches this % of margin (stored as positive, e.g. 60 = -60% ROI). 0 = off. */
    var liqGuardRoiPct: Int
        get() = prefs.getInt("liq_guard_roi", 60)
        set(value) = prefs.edit().putInt("liq_guard_roi", value.coerceIn(0, 100)).apply()

    /**
     * Liquidation buffer: keep estimated liquidation at least this % from
     * entry (caps leverage). 0 = off (the default).
     *
     * The stored VALUE only counts once the user has chosen it themselves
     * ([liqBufferPctSet]) — or once it differs from the 5% that the versions
     * before v2.5.0 shipped as a default and wrote into the settings on every
     * chat message. An install that never chose a buffer still carried that
     * inherited 5, which silently capped the leverage (75x became 17x);
     * reading the raw value would have kept the cap alive after the default
     * went to 0.
     */
    var liqBufferPct: Float
        get() = effectiveLiqBuffer(
            if (prefs.contains(KEY_LIQ_BUFFER_PCT)) prefs.getFloat(KEY_LIQ_BUFFER_PCT, 0f) else null,
            liqBufferPctSet,
            LEGACY_LIQ_BUFFER_DEFAULT,
        )
        set(value) {
            prefs.edit()
                .putFloat(KEY_LIQ_BUFFER_PCT, value.coerceIn(0f, 100f))
                .putBoolean(KEY_LIQ_BUFFER_PCT_SET, true)
                .apply()
        }

    /** True once the user has chosen a liquidation buffer themselves. */
    var liqBufferPctSet: Boolean
        get() = prefs.getBoolean(KEY_LIQ_BUFFER_PCT_SET, false)
        set(value) { prefs.edit().putBoolean(KEY_LIQ_BUFFER_PCT_SET, value).apply() }

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
        "stop_frac" to stopFracPct,
        "stop_frac_set" to stopFracSet,
        "stop_atr" to stopAtrMultiple,
        "tp_r" to tpR,
        "auto_universe" to autoUniverse,
        "auto_min_volume_usd" to autoMinVolumeUsd,
        "auto_rank_by" to autoRankBy,
        "liq_guard_roi" to liqGuardRoiPct,
        "liq_buffer_pct" to liqBufferPct,
        "liq_buffer_pct_set" to liqBufferPctSet,
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
        restoreChosen(int("breakeven_roi"), bool("breakeven_roi_set")) { breakevenRoiPct = it }
        restoreChosen(int("trail_trigger"), bool("trail_trigger_set")) { trailTriggerRoiPct = it }
        restoreChosen(int("trail_distance"), bool("trail_distance_set")) { trailDistanceRoiPct = it }
        restoreChosen(int("stop_frac"), bool("stop_frac_set")) { stopFracPct = it }
        num("stop_atr")?.let { stopAtrMultiple = it.toFloat() }
        num("tp_r")?.let { tpR = it.toFloat() }
        bool("auto_universe")?.let { autoUniverse = it }
        num("auto_min_volume_usd")?.let { autoMinVolumeUsd = it }
        str("auto_rank_by")?.let { autoRankBy = it }
        int("liq_guard_roi")?.let { liqGuardRoiPct = it }
        num("liq_buffer_pct")?.let { v ->
            val f = v.toFloat().coerceIn(0f, 100f)
            // An old backup carries no "chosen" flag: the legacy 5% default
            // inside it was never the user's choice, anything else was.
            val chosen = bool(KEY_LIQ_BUFFER_PCT_SET) ?: (f != LEGACY_LIQ_BUFFER_DEFAULT)
            prefs.edit()
                .putFloat(KEY_LIQ_BUFFER_PCT, f)
                .putBoolean(KEY_LIQ_BUFFER_PCT_SET, chosen)
                .apply()
        }
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

        /** The buffer default the versions before v2.5.0 shipped and persisted. */
        const val LEGACY_LIQ_BUFFER_DEFAULT = 5f

        const val KEY_LIQ_BUFFER_PCT = "liq_buffer_pct"
        const val KEY_LIQ_BUFFER_PCT_SET = "liq_buffer_pct_set"
    }
}

/**
 * The effective liquidation buffer.
 *
 * A stored value only counts when the user chose it ([chosen]) or when it is
 * not the legacy default that the versions before v2.5.0 shipped and wrote
 * into the settings on every chat message. That inherited 5% was never the
 * user's choice, and it silently capped the leverage — 75x became 17x.
 */
internal fun effectiveLiqBuffer(stored: Float?, chosen: Boolean, legacyDefault: Float): Float = when {
    chosen -> stored ?: 0f
    stored == null -> 0f
    stored == legacyDefault -> 0f
    else -> stored
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