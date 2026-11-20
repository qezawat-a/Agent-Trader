package com.codeassist.agenttrader

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.codeassist.agenttrader.agent.AgentBrain
import com.codeassist.agenttrader.agent.AgentConfig
import com.codeassist.agenttrader.agent.AgentSession
import com.codeassist.agenttrader.agent.AppIdentity
import com.codeassist.agenttrader.agent.ChatReply
import com.codeassist.agenttrader.agent.ChatTransport
import com.codeassist.agenttrader.agent.CommandRouter
import com.codeassist.agenttrader.agent.ConfigBackup
import com.codeassist.agenttrader.agent.ConsentGate
import com.codeassist.agenttrader.agent.ModelClient
import com.codeassist.agenttrader.agent.SelfCheck
import com.codeassist.agenttrader.agent.SelfRepair
import com.codeassist.agenttrader.bitunix.BitunixRest
import com.codeassist.agenttrader.notify.Notifier
import com.codeassist.agenttrader.notify.notifiable
import com.codeassist.agenttrader.notify.notificationTitle
import com.codeassist.agenttrader.trader.AgentRuntimeError
import com.codeassist.agenttrader.trader.EngineRuntimeError
import com.codeassist.agenttrader.trader.MarketMood
import com.codeassist.agenttrader.trader.NetworkErrors
import com.codeassist.agenttrader.trader.SignalRow
import com.codeassist.agenttrader.trader.TraderEngine
import com.codeassist.agenttrader.trader.parseSymbolsCsv
import com.codeassist.agenttrader.trader.parseTimeframesCsv
import com.codeassist.agenttrader.ui.AppScreen
import com.codeassist.agenttrader.ui.ControlScreen
import com.codeassist.agenttrader.ui.MarkdownText
import com.codeassist.agenttrader.ui.SignalsScreen
import com.codeassist.agenttrader.ui.StatusPill
import com.codeassist.agenttrader.ui.TradeScreen
import com.codeassist.agenttrader.ui.TraderTheme
import com.codeassist.agenttrader.util.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Request code for the Android 13+ POST_NOTIFICATIONS permission dialog. */
private const val NOTIFICATION_REQUEST_CODE = 1001

class MainActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TraderTheme {
                AgentScreen(config = AgentConfig(LocalContext.current), post = { ui.post(it) })
            }
        }
    }
}

private data class ChatMsg(val text: String, val isUser: Boolean, val time: String)

/** What the app can do — injected into the brain so it can guide the user accurately. */
private val CAPABILITIES = """
Screens: Chat (this one), Trade (one-tap entries), Control (switches + risk sliders), Settings (provider, model, soul, Bitunix keys).
Chat commands: /help /models /set_model /connect_provider /check_ai /price /clear /soul /add_soul /think /memory /add_skill /skill /mcp /add_mcp /check_system /diagnose <what is wrong> /repair /autopsy /verify /code_review (/review) /bug_fix /simplify /team_spawn /ws on|off
Self-diagnosis: /verify runs real probes; /diagnose <complaint> echoes your report and shows exactly which checks ran and which failed; a free-text bug report is routed to the same evidence collector instead of a generic chat answer.
Trader commands: the exact list is in your tool set above — act with <tool>...</tool> calls. The user can also type them directly in chat.
The universe is automatic: every scan refreshes it to the top symbols by 24h gainers or volume (min 24h volume filter). "set universe ..." switches to a fixed list.
The Control screen turns engine/trading/scanner/report on and off and sets leverage, margin per trade and report interval. The Trade screen opens or closes a position in one tap.
""".trim()

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Composable
fun AgentScreen(config: AgentConfig, post: (Runnable) -> Unit) {
    var engineVersion by remember { mutableStateOf(0) }
    val engine = remember(engineVersion) {
        TraderEngine(BitunixRest(config.bitunixApiKey, config.bitunixSecret)).apply {
            leverage = config.leverage
            marginMode = config.marginMode
            positionMode = config.positionMode
            live = config.live
            riskFraction = config.riskPct / 100.0
            engineOn = config.engineOn
            scanOn = config.scanOn
            reportOn = config.reportOn
            reportIntervalMin = config.reportIntervalMin
            scanTop = config.scanTop
            minAgreeing = config.minAgreeing
            minTfConfidence = config.minTfConfidence.toDouble()
            minConfidence = config.minConfidence.toDouble()
            parseTimeframesCsv(config.timeframes)?.let { timeframes = it }
            minSideTfs = config.minSideTfs.takeIf { it in 1..timeframes.size } ?: Int.MAX_VALUE
            parseSymbolsCsv(config.universe)?.let { csv ->
                universe.clear(); universe.addAll(csv)
            }
            // Auto-universe persisted since v.2.4.3.
            this.autoUniverse = config.autoUniverse
            this.autoMinVolumeUsd = config.autoMinVolumeUsd
            this.autoRankBy = config.autoRankBy
            // Risk settings persisted since v2.6 with a "chosen" flag, because
            // 0 is a real setting ("off") and must not be read as "unset". An
            // old backup carries no flag: a non-zero value was chosen by the
            // user, a zero one falls back to the engine default.
            if (config.breakevenSet || config.breakevenRoiPct > 0) {
                risk = risk.copy(breakevenRoiPct = config.breakevenRoiPct.toDouble())
            }
            if (config.trailTriggerSet || config.trailTriggerRoiPct > 0) {
                risk = risk.copy(trailingTriggerRoiPct = config.trailTriggerRoiPct.toDouble())
            }
            if (config.trailDistanceSet || config.trailDistanceRoiPct > 0) {
                risk = risk.copy(trailDistanceRoiPct = config.trailDistanceRoiPct.toDouble())
            }
            if (config.stopFracSet || config.stopFracPct > 0) {
                risk = risk.copy(stopFractionOfLiqPct = config.stopFracPct.toDouble())
            }
            risk = risk.copy(stopAtrMultiple = config.stopAtrMultiple.toDouble())
            risk = risk.copy(liqGuardRoiPct = config.liqGuardRoiPct.toDouble())
            liqBufferPct = config.liqBufferPct.toDouble()
            if (config.tpR > 0f) tpR = config.tpR.toDouble()
            trading = false // the trading loop never auto-resumes on launch (safety)
        }
    }
    AgentApp(
        config = config,
        post = post,
        engine = engine,
        engineVersion = engineVersion,
        onEngineRecreate = { engineVersion++ },
    )
}

@Composable
fun AgentApp(
    config: AgentConfig,
    post: (Runnable) -> Unit,
    engine: TraderEngine,
    engineVersion: Int,
    onEngineRecreate: () -> Unit,
) {
    val appContext = LocalContext.current
    val messages = remember { mutableStateListOf(ChatMsg("Agent Trader ready. Tap a quick action or type /help.", false, now())) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(AppScreen.CHAT) }
    var mood by remember { mutableStateOf<MarketMood?>(null) }
    var settingsMsg by remember { mutableStateOf("") }
    var signalRows by remember { mutableStateOf<List<SignalRow>>(emptyList()) }
    var signalsLoading by remember { mutableStateOf(false) }
    var lastKeys by remember { mutableStateOf(config.bitunixApiKey to config.bitunixSecret) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    /**
     * One chat session: memory, the tool loop and the receipts.
     *
     * The engine can be recreated when the Bitunix keys change, so the session
     * reaches it through a holder instead of a capture — a stale engine would
     * answer with the old account's state.
     */
    val engineBox = remember { AtomicReference(engine) }
    SideEffect { engineBox.set(engine) }

    // A model-requested action waiting for the user's one-tap confirmation.
    var consentRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    var consentResolve by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val session = remember {
        AgentSession(
            transport = ChatTransport { model, msgs, maxTokens ->
                ChatReply(ModelClient(config.baseUrl, config.apiKey).chat(model, msgs, maxTokens))
            },
            execute = { command -> engineBox.get().handle(command) },
            consent = ConsentGate { command, reason ->
                // Blocks the worker thread (never the UI thread) until the user
                // answers, denies on timeout so the chat can never hang forever.
                val latch = CountDownLatch(1)
                val granted = AtomicBoolean(false)
                post(object : Runnable {
                    override fun run() {
                        consentRequest = command to reason
                        consentResolve = { ok ->
                            granted.set(ok)
                            latch.countDown()
                        }
                    }
                })
                val answered = latch.await(120, TimeUnit.SECONDS)
                answered && granted.get()
            },
        )
    }

    /** Identity of the conversation: memory resets when any of these change. */
    fun sessionKey(): String = listOf(
        if (engine.live) "LIVE" else "PAPER",
        config.baseUrl,
        config.model,
        config.apiKey.hashCode(),
    ).joinToString("|")

    /** Copies the engine's current settings into [AgentConfig] so they survive restarts. */
    fun persistEngine() {
        config.leverage = engine.leverage
        config.riskPct = (engine.riskFraction * 100).toInt().coerceIn(1, 100)
        config.engineOn = engine.engineOn
        config.scanOn = engine.scanOn
        config.reportOn = engine.reportOn
        config.reportIntervalMin = engine.reportIntervalMin
        config.marginMode = engine.marginMode
        config.positionMode = engine.positionMode
        config.live = engine.live
        config.universe = engine.universe.joinToString(",")
        config.timeframes = engine.timeframes.joinToString(",")
        config.scanTop = engine.scanTop
        config.minAgreeing = engine.minAgreeing
        config.minTfConfidence = engine.minTfConfidence.toInt()
        config.minConfidence = engine.minConfidence.toInt()
        config.minSideTfs = if (engine.minSideTfs == Int.MAX_VALUE) 0 else engine.minSideTfs
        config.breakevenRoiPct = engine.risk.breakevenRoiPct.toInt()
        config.trailTriggerRoiPct = engine.risk.trailingTriggerRoiPct.toInt()
        config.trailDistanceRoiPct = engine.risk.trailDistanceRoiPct.toInt()
        config.stopFracPct = engine.risk.stopFractionOfLiqPct.toInt()
        config.stopAtrMultiple = engine.risk.stopAtrMultiple.toFloat()
        config.liqGuardRoiPct = engine.risk.liqGuardRoiPct.toInt().coerceIn(0, 100)
        config.liqBufferPct = engine.liqBufferPct.toFloat()
        config.tpR = engine.tpR.toFloat()
        config.autoUniverse = engine.autoUniverse
        config.autoMinVolumeUsd = engine.autoMinVolumeUsd
        config.autoRankBy = engine.autoRankBy
    }

    fun say(text: String) {
        post(object : Runnable {
            override fun run() {
                runCatching { persistEngine() }
                messages.add(ChatMsg(text, false, now()))
                // Every bot line — engine event OR command response (manual open,
                // close, etc.) — gets one chance to notify; notifiable() filters
                // the chatter. Previously manual entries never notified because
                // only the event sink posted.
                if (config.notifications && notifiable(text)) {
                    Notifier.post(appContext, notificationTitle(text), text)
                }
            }
        })
    }

    // Android back returns to the chat instead of closing the app.
    BackHandler(enabled = screen != AppScreen.CHAT) { screen = AppScreen.CHAT }

    /** Engine toggles — one tap, no typing. */
    fun toggleEngine(on: Boolean) {
        // The engine owns its own lifecycle (loop stop/open-position warning),
        // so the UI routes through handle() instead of writing the fields.
        say(engine.handle(if (on) "engine on" else "engine off"))
    }

    fun toggleTrading(on: Boolean) {
        if (on && !engine.engineOn) engine.engineOn = true
        // startTrading()/stopTrading() own the loop: the reply describes the
        // real state ("loop running"), not a promise.
        say(if (on) engine.startTrading() else engine.stopTrading())
    }

    fun toggleScan(on: Boolean) {
        engine.scanOn = on
        say(if (on) "Scanner ON — ranking the universe by volume." else "Scanner OFF — fixed universe.")
    }

    fun toggleReport(on: Boolean) {
        engine.reportOn = on
        say(if (on) "Periodic reports ON (every ${engine.reportIntervalMin} min)." else "Periodic reports OFF.")
    }

    fun toggleNotifications(on: Boolean) {
        config.notifications = on
        if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                appContext as Activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE,
            )
            say("Notifications ON — accept the Android permission dialog so events can reach the status bar.")
            return
        }
        say(
            if (on) "Notifications ON — trade events, SL/TP moves and reports will show in the status bar."
            else "Notifications OFF.",
        )
    }

    fun toggleLive(on: Boolean) {
        if (on && (config.bitunixApiKey.isBlank() || config.bitunixSecret.isBlank())) {
            say("LIVE needs real Bitunix keys — add them in Settings first. Staying in PAPER.")
            return
        }
        config.live = on
        engine.live = on
        // The feed follows the mode immediately (config changes do not recompose).
        if (on && config.bitunixApiKey.isNotBlank() && config.bitunixSecret.isNotBlank()) {
            engine.startLiveFeed(config.bitunixApiKey, config.bitunixSecret)
            // Prime the exchange balance right away: until it arrives the UI
            // shows "unavailable (exchange)", never the paper account.
            Thread { runCatching { engine.refreshLiveBalance() } }.apply { isDaemon = true }.start()
        } else {
            engine.stopLiveFeed()
        }
        say(
            if (on) "LIVE mode ON — orders hit the exchange. The private feed is connecting."
            else "PAPER mode — orders are simulated.",
        )
    }

    /** Provider snapshot for self-diagnosis. */
    fun providerState() = SelfRepair.ProviderState(
        baseUrl = config.baseUrl,
        apiKeyPresent = config.apiKey.isNotBlank(),
        model = config.model,
        modelsAvailable = 0,
        // Only a recent provider failure is worth reporting; a stale one or an
        // app-side exception would send the repair flow after the wrong suspect.
        lastError = AgentRuntimeError.providerError(),
    )

    /** Engine snapshot for self-diagnosis. */
    fun engineState() = SelfRepair.EngineState(
        live = engine.live,
        engineOn = engine.engineOn,
        trading = engine.trading,
        loopRunning = engine.isLoopRunning,
        feedConnected = engine.liveFeedOn,
        bitunixKeysPresent = config.bitunixApiKey.isNotBlank() && config.bitunixSecret.isNotBlank(),
        openPositions = if (engine.live) engine.feedPositions().size else engine.broker.positions.size,
        leverage = engine.leverage,
        lastError = EngineRuntimeError.last,
    )

    /**
     * Runs the probes the diagnosis is allowed to talk about.
     *
     * [deep] adds the two network calls to the LLM provider. Without them the
     * report still says which checks ran and which were skipped — it never
     * claims a subsystem is healthy because it was not looked at.
     */
    fun selfChecks(deep: Boolean): List<SelfCheck.Check> {
        val checks = mutableListOf<SelfCheck.Check>()
        val providerReady = config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()

        checks += if (providerReady) {
            SelfCheck.pass(
                "provider config",
                "${config.baseUrl.trim()} · model ${config.model.ifBlank { "(none yet — auto-picked on probe)" }}",
            )
        } else {
            SelfCheck.fail("provider config", "no base URL or API key saved in Settings")
        }

        if (deep && providerReady) {
            val client = ModelClient(config.baseUrl, config.apiKey)
            checks += SelfCheck.guarded("provider HTTP") {
                val models = client.listModels()
                if (models.isNotEmpty()) SelfCheck.pass("provider HTTP", "GET /models → ${models.size} models")
                else SelfCheck.fail("provider HTTP", "GET /models returned nothing (${client.url("models")})")
            }
            checks += SelfCheck.guarded("model reply") {
                var model = config.model
                if (model.isBlank()) {
                    model = ModelClient.pickModel(client.listModels())
                    if (model.isNotBlank()) config.model = model
                }
                if (model.isBlank()) {
                    SelfCheck.fail("model reply", "no model id available from the provider")
                } else {
                    val reply = client.chat(model, listOf("user" to "Reply with the single word: OK"), maxTokens = 12)
                    if (reply.isBlank()) SelfCheck.fail("model reply", "'$model' returned empty content")
                    else SelfCheck.pass("model reply", "'$model' answered \"${reply.take(30)}\"")
                }
            }
        } else {
            val why = if (!providerReady) "needs a configured provider" else "quick check — deep probe skipped"
            checks += SelfCheck.skip("provider HTTP", why)
            checks += SelfCheck.skip("model reply", why)
        }

        checks += SelfCheck.guarded("market price") {
            val symbol = engine.universe.firstOrNull() ?: "BTCUSDT"
            val px = engine.symbolPrice(symbol)
            if (px > 0) {
                SelfCheck.pass("market price", "$symbol ${fmt(px)} (Bitunix reachable)")
            } else {
                // Distinguish "this symbol is bad" from "the exchange is down".
                val btc = engine.symbolPrice("BTCUSDT")
                if (btc > 0) {
                    SelfCheck.fail("market price", "no price for $symbol, but BTCUSDT ${fmt(btc)} — check the symbol in the universe")
                } else {
                    SelfCheck.fail("market price", "no price for $symbol or BTCUSDT — Bitunix unreachable (DNS?)")
                }
            }
        }

        val keysReady = config.bitunixApiKey.isNotBlank() && config.bitunixSecret.isNotBlank()
        checks += when {
            !engine.live -> SelfCheck.skip("private feed", "PAPER mode — feed not needed")
            !keysReady -> SelfCheck.fail("private feed", "LIVE without Bitunix API key + secret")
            engine.liveFeedOn -> SelfCheck.pass("private feed", "connected (balance/positions/orders/tpsl)")
            else -> SelfCheck.fail("private feed", "LIVE but the private WebSocket is not connected")
        }

        checks += when {
            !engine.live -> SelfCheck.pass("balance source", "${engine.balanceText()} — simulated, never mixed with the real one")
            engine.liveBalanceSnapshot() == null ->
                SelfCheck.fail("balance source", "LIVE but the exchange balance has not arrived yet")
            else -> SelfCheck.pass(
                "balance source",
                "${engine.liveBalanceText()}, refreshed ${engine.liveBalanceAgeMs() / 1000}s ago",
            )
        }

        checks += when {
            !engine.engineOn -> SelfCheck.pass("engine", "off — idle")
            engine.trading && !engine.isLoopRunning -> SelfCheck.fail("engine", "trading ON but the loop is not running")
            engine.trading -> SelfCheck.pass("engine", "on, trading on, loop running")
            else -> SelfCheck.pass("engine", "on, trading off (scan only)")
        }

        checks += if (engine.universe.isEmpty()) {
            SelfCheck.fail("universe", "no symbols — the scanner cannot find anything")
        } else {
            SelfCheck.pass("universe", engine.universe.joinToString(",").take(80))
        }

        checks += if (engine.leverage > engine.leverageCeiling()) {
            SelfCheck.fail("risk config", "leverage ${engine.leverage}x above the exchange max ${engine.leverageCeiling()}x")
        } else {
            SelfCheck.pass(
                "risk config",
                "leverage ${engine.leverage}x (max ${engine.leverageCeiling()}), stop ${engine.risk.stopFractionOfLiqPct.toInt()}% of liq, TP ${engine.tpR}R",
            )
        }

        checks += if (AgentRuntimeError.providerError().isBlank()) {
            SelfCheck.pass("last provider error", "none recent")
        } else {
            SelfCheck.fail(
                "last provider error",
                "${AgentRuntimeError.providerError().take(180)} (${AgentRuntimeError.ageSeconds()}s ago)",
            )
        }
        checks += if (EngineRuntimeError.last.isBlank()) {
            SelfCheck.pass("last engine error", "none recorded")
        } else {
            SelfCheck.fail("last engine error", EngineRuntimeError.last.take(180))
        }

        val canNotify = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        checks += if (canNotify) {
            SelfCheck.pass("notifications", "permission granted")
        } else {
            SelfCheck.fail("notifications", "POST_NOTIFICATIONS denied — alerts stay inside the app")
        }

        return checks
    }

    /** Diagnosis from the rule list plus the live probes that actually ran. */
    fun diagnoseReport(complaint: String = "", deep: Boolean = true): String {
        val checks = selfChecks(deep)
        val d = SelfRepair.diagnose(providerState(), engineState(), complaint, checks)
        return "Diagnosis [${d.worst}]:\n" + d.summary()
    }

    /**
     * The real agent loop for "something is wrong": collect runtime evidence,
     * then make the model reason over that evidence. With no provider it says
     * exactly that and shows the checks instead of guessing or reassuring.
     */
    fun analyse(complaint: String, deep: Boolean = true): String {
        val checks = selfChecks(deep)
        val d = SelfRepair.diagnose(providerState(), engineState(), complaint, checks)
        val runtime = buildString {
            appendLine("Engine status: ${engine.status().replace("\n", " | ")}")
            appendLine("Config: ${engine.configLine()}")
            appendLine("Loop running: ${engine.isLoopRunning}, feed: ${engine.liveFeedOn}, live: ${engine.live}")
            val providerErr = AgentRuntimeError.describe()
            if (providerErr.isNotBlank()) appendLine("Last provider/app error: $providerErr")
            if (EngineRuntimeError.last.isNotBlank()) appendLine("Last engine error: ${EngineRuntimeError.last}")
        }.trim()

        val providerReady = config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()
        val fixable = d.findings.count { it.autoFixable }
        val repairHint = if (fixable > 0) {
            "\n\n$fixable finding(s) can be repaired automatically — send /repair; it re-runs these checks afterwards."
        } else ""
        if (!providerReady) {
            return d.summary() + repairHint + "\n\n" +
                "No LLM provider is configured, so I cannot reason past these checks. " +
                "Settings → provider URL + key (or /connect_provider <url> <key>), then ask again. " +
                "The failed checks above are real and already tell you where to look."
        }

        val client = ModelClient(config.baseUrl, config.apiKey)
        return try {
            var model = config.model
            if (model.isBlank()) {
                model = ModelClient.pickModel(client.listModels())
                if (model.isNotBlank()) config.model = model
            }
            if (model.isBlank()) return d.summary() + repairHint + "\n\nProvider is set but lists no usable model — /connect_provider or /models."

            val reply = client.chat(
                model,
                listOf(
                    "system" to SelfCheck.analystSystemPrompt(CAPABILITIES),
                    "user" to "The user reports: \"${complaint.trim()}\"\n\n" +
                        "Evidence collected by the app itself:\n" +
                        SelfCheck.evidenceBlock(checks, d.findingsText(), runtime),
                ),
                maxTokens = 900,
            )
            d.summary() + repairHint + "\n\n— Agent analysis (" + model + ") —\n" + AgentBrain.stripToolCalls(reply).trim()
        } catch (e: Exception) {
            // The model failed, not the app: attribute the failure to the
            // provider so /autopsy and the repair flow look in the right place.
            AgentRuntimeError.record("Error: ${e.message ?: e.javaClass.simpleName}", AgentRuntimeError.PROVIDER)
            d.summary() + "\n\nThe analysis model failed: ${e.message}\n" +
                "That failure is now the top suspect — /autopsy shows it, /repair retries the provider."
        }
    }

    /** One safe repair action, shared by /repair and the automatic heal path. */
    fun repairAction(action: String): String = when (action) {
        "pick_model" -> runCatching {
            val models = ModelClient(config.baseUrl, config.apiKey).listModels()
            if (models.isEmpty()) "could not list models" else {
                val chosen = ModelClient.pickModel(models)
                config.model = chosen
                "model set to $chosen"
            }
        }.getOrElse { "model probe failed: ${it.message}" }
        "probe_provider" -> runCatching {
            ModelClient(config.baseUrl, config.apiKey).listModels().size
        }.map { "provider reachable ($it models)" }
            .getOrElse { "provider probe failed: ${it.message}" }
        "restart_loop" -> {
            engine.startLoop(30) { msg -> say(msg) }
            "trading loop restarted"
        }
        "reconnect_feed" -> {
            engine.startLiveFeed(config.bitunixApiKey, config.bitunixSecret)
            "private feed reconnecting"
        }
        "clamp_leverage" -> {
            engine.leverage = engine.leverage.coerceAtMost(engine.leverageCeiling())
            "leverage clamped to ${engine.leverage}x"
        }
        else -> "nothing to do for $action"
    }

    /** Diagnoses, applies only the safe repairs, then re-runs the checks to prove it worked. */
    fun repairNow(): String {
        val d = SelfRepair.diagnose(providerState(), engineState())
        val applied = SelfRepair.applyAutoFixes(d) { action -> repairAction(action) }
        val after = selfChecks(deep = false)
        val failed = after.filter { it.status == SelfCheck.Status.FAIL }
        return buildString {
            appendLine("Repair report:")
            appendLine(applied)
            appendLine()
            appendLine("Re-check after repair — ${SelfCheck.counts(after)}")
            appendLine(SelfCheck.render(after))
            if (failed.isNotEmpty()) {
                appendLine("Still failing: " + failed.joinToString(", ") { it.name } + " — /autopsy has the last error.")
            }
        }.trim()
    }

    /** Runs a trader command (or the self-repair flow) from a button. */
    fun runAction(cmd: String) {
        when {
            cmd.startsWith("diagnose") -> runAsync(post, ::say) { diagnoseReport() }
            cmd.startsWith("repair") -> runAsync(post, ::say) { repairNow() }
            else -> runAsync(post, ::say) { engine.handle(cmd) }
        }
    }

    /** Live context handed to the LLM so it can reason with real numbers. */
    fun liveContext(moodSnapshot: MarketMood? = mood): String =
        AgentBrain.buildContext(
        mode = if (engine.live) "LIVE" else "PAPER",
        // LIVE must never hand the model the simulated balance — it would
        // confidently quote a paper number as the real account.
        balance = engine.balanceText(),
        positionsSummary = if (engine.live) {
            // REST, not the WS cache: the feed pushes only CHANGES, so a position
            // closed during a reconnect used to linger in the prompt and the model
            // filled the missing leverage/SL/PnL with invented numbers.
            engine.livePositionsSummary()
        } else {
            engine.broker.snapshot().joinToString(", ") {
                "${it.key} ${if (it.value.dir > 0) "LONG" else "SHORT"} qty ${fmt(it.value.qty)} @ ${fmt(it.value.entry)}"
            }.ifEmpty { "none" }
        },
        moodLine = moodSnapshot?.summary() ?: "",
        // Funding needs a REST round-trip, so it is left out here instead of
        // faked — the agent asks for it with <tool>funding</tool> when it matters.
        extra = "Config: ${engine.configLine()} · Signals: " + engine.signalsBrief().ifBlank { "not evaluated yet" },
    )

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    /** Loads the signal board on a background thread and publishes it to the UI. */
    fun loadSignals(force: Boolean = false) {
        signalsLoading = true
        Thread {
            val age = if (force) 0L else 60_000L
            val rows = runCatching { engine.signalBoard(maxAgeMs = age) }.getOrDefault(emptyList())
            post(object : Runnable {
                override fun run() {
                    signalRows = rows
                    signalsLoading = false
                }
            })
        }.start()
    }

    // While the Signals screen is open, refresh it every 30s (engine caches for 60s).
    LaunchedEffect(screen) {
        if (screen == AppScreen.SIGNALS) {
            loadSignals()
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (screen == AppScreen.SIGNALS) loadSignals()
            }
        }
    }

    // Refresh the market feeling every 30s (off the UI thread).
    LaunchedEffect(engineVersion) {
        while (true) {
            val m = Thread {
                val res = runCatching { engine.marketMood() }.getOrNull()
                post(object : Runnable {
                    override fun run() { mood = res }
                })
            }
            m.start()
            kotlinx.coroutines.delay(30_000)
        }
    }

    // LIVE account balance: refresh from the exchange every 30s (off the UI thread).
    // The Control/Trade screens poll accountLine(), so the value shows up within 2s.
    LaunchedEffect(engineVersion, engine.live) {
        while (engine.live) {
            val t = Thread { runCatching { engine.refreshLiveBalanceIfStale(30_000) } }
            t.isDaemon = true
            t.start()
            kotlinx.coroutines.delay(30_000)
        }
    }

    // When the engine is recreated (e.g. the Bitunix keys changed) the OLD
    // engine's loop must die with it: a background engine trading on stale
    // credentials is exactly the leak this effect prevents.
    DisposableEffect(engine) {
        onDispose { engine.stopLoop() }
    }

    // One event sink for the engine (loop, reports, live WebSocket feed).
    LaunchedEffect(engineVersion) {
        engine.setEventSink { msg -> say(msg) }
    }

    // Startup notice: the trading loop never auto-resumes (safety) — but the user
    // must KNOW that, otherwise open positions look "unannounced/unmanaged".
    LaunchedEffect(Unit) {
        if (engine.engineOn && !engine.trading) {
            say("Restarted: Engine is ON but the trading loop is OFF (safety). Tap Trade to resume managing positions.")
        }
    }

    // Ask for the status-bar notification permission once (Android 13+).
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                appContext as Activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE,
            )
        }
    }

    // Public WebSocket market feed (tickers + price): no keys needed, streams silently.
    LaunchedEffect(engineVersion, engine.universe.size) {
        engine.startMarketFeed()
    }
    DisposableEffect(engine) {
        onDispose { engine.stopMarketFeed() }
    }

    // Private WebSocket feed: live in LIVE mode with keys set, closed otherwise.
    LaunchedEffect(engineVersion, config.live, config.bitunixApiKey, config.bitunixSecret) {
        if (config.live && config.bitunixApiKey.isNotBlank() && config.bitunixSecret.isNotBlank()) {
            engine.startLiveFeed(config.bitunixApiKey, config.bitunixSecret)
        } else {
            engine.stopLiveFeed()
        }
    }

    DisposableEffect(engine) {
        onDispose { engine.stopLiveFeed() }
    }

    fun handle(line: String) {
        post(object : Runnable {
            override fun run() {
                messages.add(ChatMsg(line, true, now()))
            }
        })
        keyboard?.hide()
        when (val parsed = CommandRouter.parse(line)) {
            is CommandRouter.Parsed.Agent -> when (parsed.command) {
                "/help" -> say(CommandRouter.helpText())
                "/clear" -> {
                    session.clear()
                    // The plan belongs to the conversation: a cleared chat has no plan.
                    config.lastPlan = ""
                    post(object : Runnable {
                        override fun run() {
                            messages.clear()
                            messages.add(ChatMsg("Chat cleared. Memory reset.", false, now()))
                        }
                    })
                }
                "/models" -> runAsync(post, ::say) {
                    val client = ModelClient(config.baseUrl, config.apiKey)
                    if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
                        "No provider configured. Open Settings and save your base URL + API key."
                    } else {
                        val all = client.listModels()
                        if (all.isEmpty()) "No models returned."
                        else {
                            // Probe a handful of candidates and stop at the first
                            // that answers. Probing every id the provider listed
                            // meant 300 billable calls and a chat frozen until
                            // they all finished.
                            val probes = client.probeWorking(all)
                            val working = probes.filter { it.ok }.map { it.model }
                            if (working.isEmpty()) {
                                "No working model among the ${probes.size} candidates tried:\n" +
                                    probes.joinToString("\n") { (m, ok, d) -> "$m — ${if (ok) "ok" else d}" }
                            } else {
                                config.model = working.first()
                                "Model set to ${working.first()} (verified with a live call)."
                            }
                        }
                    }
                }
                "/set_model" -> {
                    config.model = parsed.args
                    say("Model set to ${parsed.args}")
                }
                "/price" -> runAsync(post, ::say) {
                    val sym = parsed.args.trim().uppercase().ifBlank { "BTCUSDT" }
                    val px = BitunixRest(config.bitunixApiKey, config.bitunixSecret).lastPrice(sym)
                    if (px > 0) "$sym last price: $px USDT" else "No price for $sym."
                }
                "/soul" -> say("Soul:\n${config.soul}")
                "/add_soul" -> {
                    config.soul = parsed.args
                    say("Soul updated.")
                }
                "/think" -> {
                    val plan = config.lastPlan
                    say(if (plan.isBlank()) "No plan recorded yet. Give me a task." else "Plan:\n$plan")
                }
                "/memory" -> say(
                    "Saved notes: agent config in private SharedPreferences (base URL, model, soul, engine settings). " +
                    "Bitunix keys stored separately and never written to chat."
                )
                "/add_skill" -> {
                    val parts = parsed.args.split(" ", limit = 2)
                    if (parts.size < 2 || parts[1].isBlank()) say("Usage: /add_skill <name> <text>")
                    else {
                        config.skillPut(parts[0], parts[1])
                        say("Skill '${parts[0]}' saved (${parts[1].length} chars).")
                    }
                }
                "/skill" -> {
                    val s = config.skillGet(parsed.args.trim())
                    say(if (s == null) "No skill named '${parsed.args}'. Saved: ${config.skillNames()}" else s)
                }
                "/check_ai" -> runAsync(post, ::say) {
                    val client = ModelClient(config.baseUrl, config.apiKey)
                    if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
                        "No provider configured. Use /connect_provider <url> <key> or Settings."
                    } else {
                        val models = client.listModels()
                        if (models.isEmpty()) {
                            "Provider reachable but /models returned nothing. Endpoint tried: ${client.url("models")}"
                        } else if (config.model.isBlank()) {
                            val chosen = ModelClient.pickModel(models)
                            config.model = chosen
                            "Provider OK: ${models.size} models. Auto-picked model '$chosen' — try a chat now."
                        } else {
                            try {
                                val reply = client.chat(config.model, listOf("user" to "Say OK."), maxTokens = 16)
                                "Provider OK: ${models.size} models. Model '${config.model}' replied: ${reply.take(60).ifBlank { "(empty content)" }}"
                            } catch (e: Exception) {
                                "Provider OK (${models.size} models) but model '${config.model}' failed: ${e.message}"
                            }
                        }
                    }
                }
                "/connect_provider" -> {
                    val parts = parsed.args.split(" ", limit = 2)
                    if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                        say("Usage: /connect_provider <baseUrl> <apiKey>")
                    } else {
                        config.baseUrl = parts[0]
                        config.apiKey = parts[1]
                        say("Provider connected: ${config.baseUrl}")
                    }
                }
                "/check_system" -> say(
                    "Engine: ${if (engine.engineOn) "on" else "off"}, trading: ${if (engine.trading) "on" else "off"}, scan: ${if (engine.scanOn) "on" else "off"}\n" +
                    "Mode: ${if (engine.live) "LIVE" else "PAPER"}, leverage ${engine.leverage}x, margin ${engine.marginMode}, position ${engine.positionMode}\n" +
                    "Loop: ${if (engine.isLoopRunning) "running" else "stopped"}, ${engine.accountLine()}"
                )
                "/cert" -> say(AppIdentity.describe(appContext))
                "/backup" -> runAsync(post, ::say) {
                    val path = ConfigBackup.write(appContext, config.exportSettings())
                    "Backup written (provider, model, Bitunix keys, engine settings):\n$path\n\n" +
                        "Copy that file off the phone (cable/USB or a file manager) and keep it. " +
                        "If an update ever asks you to uninstall, run /restore after the reinstall."
                }
                "/restore" -> runAsync(post, ::say) {
                    val map = ConfigBackup.read(appContext)
                    if (map == null) {
                        "No backup found at ${ConfigBackup.file(appContext).absolutePath}.\n" +
                            "Put the backup file there (or paste its JSON) and run /restore again."
                    } else {
                        config.importSettings(map)
                        "Restored ${map.size} settings from ${ConfigBackup.file(appContext).absolutePath}.\n" +
                            "Provider: ${if (config.baseUrl.isBlank()) "(none)" else config.baseUrl} · " +
                            "model: ${if (config.model.isBlank()) "(none)" else config.model} · " +
                            "Bitunix key: ${if (config.bitunixApiKey.isBlank()) "(none)" else "set"}\n" +
                            "Reload the app so the engine picks the restored values up."
                    }
                }
                "/add_mcp" -> {
                    val parts = parsed.args.split(" ", limit = 2)
                    if (parts.size < 2 || !parts[1].contains("://")) {
                        say("Usage: /add_mcp <name> <url>")
                    } else {
                        config.mcpPut(parts[0], parts[1])
                        say("MCP server saved: ${parts[0]} → ${parts[1]}")
                    }
                }
                "/mcp" -> {
                    val names = config.mcpNames()
                    if (names.isEmpty()) say("No MCP servers registered.")
                    else say(names.joinToString("\n") { "$it → ${config.mcpGet(it)}" })
                }
                "/team_spawn" -> {
                    val task = parsed.args.ifBlank { "the current feature" }
                    config.lastPlan = "team_spawn: $task"
                    say(
                        "Sub-agent plan for: $task\n" +
                        "1. Router agent — split the task into subtasks\n" +
                        "2. Analyst agent — gather data / decide strategy\n" +
                        "3. Builder agent — implement code changes\n" +
                        "4. Verifier agent — run build + tests\n" +
                        "Spawning is simulated: assign each subtask to a sub-agent and merge results."
                    )
                }
                "/code_review", "/review" -> {
                    val target = parsed.args.ifBlank { "the whole app (start with the trader engine and the chat loop)" }
                    if (config.model.isBlank()) {
                        // Never run a network probe on the UI thread: the price
                        // check would fail for the wrong reason.
                        runAsync(post, ::say) {
                            val checks = selfChecks(deep = false)
                            "Code review needs a model, and none is set. What I can verify without one:\n" +
                                SelfCheck.counts(checks) + "\n" + SelfCheck.render(checks)
                        }
                    } else {
                        runAsync(post, ::say) {
                            val checks = selfChecks(deep = false)
                            ModelClient(config.baseUrl, config.apiKey).chat(
                                config.model,
                                listOf(
                                    "system" to config.soul,
                                    "user" to "Act as a senior code reviewer for Agent Trader, the app running this chat: " +
                                        "Kotlin/Compose, modules: agent (config, command router, self-repair, model client), " +
                                        "bitunix (signed REST + WebSocket), trader (indicators, strategies, signal aggregator, " +
                                        "paper/live engine, TP/SL).\n" +
                                        "Review: $target\n" +
                                        "Runtime facts from the app: ${engine.configLine()}; checks: ${SelfCheck.counts(checks)}.\n" +
                                        "Rules: if you were not given the actual source of a file, say which file you need — " +
                                        "never claim the code is clean or bug-free without seeing it. " +
                                        "List concrete risks with file/function names and the top 3 fixes.",
                                ),
                                maxTokens = 900,
                            )
                        }
                    }
                }
                "/bug_fix" -> {
                    val symptom = parsed.args.ifBlank { "unspecified symptom" }
                    if (config.model.isBlank()) {
                        runAsync(post, ::say) { analyse(symptom, deep = false) }
                    } else {
                        runAsync(post, ::say) {
                            val checks = selfChecks(deep = false)
                            val suggestion = ModelClient(config.baseUrl, config.apiKey).chat(
                                config.model,
                                listOf(
                                    "system" to SelfCheck.analystSystemPrompt(CAPABILITIES),
                                    "user" to "Diagnose this bug: '$symptom'.\n" +
                                        "Evidence collected by the app:\n" +
                                        SelfCheck.evidenceBlock(checks, "none", "Config: ${engine.configLine()}") +
                                        "\nGive the most likely root cause and a minimal code fix.",
                                ),
                                maxTokens = 900,
                            )
                            suggestion + "\n\n(No file was changed. Reply with /verify to re-run the checks.)"
                        }
                    }
                }
                "/simplify" -> {
                    val code = parsed.args
                    when {
                        code.isNotBlank() && config.model.isBlank() ->
                            say("No model set — /simplify needs one to rewrite code. Use /diagnose <problem> for the checks that need no model.")
                        code.isNotBlank() -> runAsync(post, ::say) {
                            ModelClient(config.baseUrl, config.apiKey).chat(
                                config.model,
                                listOf(
                                    "system" to config.soul,
                                    "user" to "Simplify this Kotlin snippet without changing behavior, and explain each change:\n$code",
                                ),
                                maxTokens = 900,
                            )
                        }
                        // No snippet given: do not pretend there is nothing to fix.
                        else -> runAsync(post, ::say) {
                            "Simplification needs a snippet: /simplify <code>.\n\n" +
                                "Meanwhile, the checks that need no model:\n" +
                                diagnoseReport(complaint = "simplify request without a snippet", deep = false)
                        }
                    }
                }
                "/verify" -> runAsync(post, ::say) {
                    config.lastPlan = "verify: runtime checks (compile happens in CI)"
                    val checks = selfChecks(deep = true)
                    val failed = checks.filter { it.status == SelfCheck.Status.FAIL }
                    buildString {
                        appendLine("Verification — ${SelfCheck.counts(checks)}")
                        appendLine(SelfCheck.render(checks))
                        appendLine()
                        appendLine("Compile: not possible inside the app. CI compiles and signs the APK on every tag,")
                        appendLine("and the unit tests run in that pipeline. Anything else would be a guess, so it is not claimed.")
                        if (failed.isEmpty()) {
                            appendLine("Verdict: every probe above passed. That covers the probe list only — say what you")
                            appendLine("actually see broken and I will trace it: /diagnose <what happens>.")
                        } else {
                            appendLine("Verdict: ${failed.size} probe(s) failed — " + failed.joinToString(", ") { it.name } + ".")
                            appendLine("Top fix: /repair (applies the safe ones), then /verify again.")
                        }
                    }.trim()
                }
                "/autopsy" -> {
                    val providerErr = AgentRuntimeError.providerError()
                    val appErr = if (AgentRuntimeError.source == AgentRuntimeError.LOCAL) AgentRuntimeError.last else ""
                    val engineErr = EngineRuntimeError.last
                    val entries = mutableListOf<String>()
                    if (providerErr.isNotBlank()) {
                        entries += "Captured error (provider, ${AgentRuntimeError.ageSeconds()}s ago): $providerErr"
                    }
                    if (appErr.isNotBlank()) entries += "Captured error (app code): $appErr"
                    if (engineErr.isNotBlank()) entries += "Captured error (engine): $engineErr"
                    if (entries.isEmpty()) {
                        say("No recorded failure to analyse. Errors during async work are stored automatically (see /diagnose).")
                    } else {
                        val engineOnly = providerErr.isBlank() && appErr.isBlank() && engineErr.isNotBlank()
                        val stage = when {
                            engineOnly && NetworkErrors.isNetwork(engineErr) -> "exchange/network (Bitunix unreachable)"
                            entries.any { it.contains("HTTP") } -> "network/API call"
                            entries.any { it.contains("Exception") } -> "logic/exception"
                            else -> "unknown"
                        }
                        val action = when {
                            stage.startsWith("exchange/network") ->
                                "This is a connectivity problem, not a model problem. Check Wi-Fi/mobile data; the loop retries automatically."
                            appErr.isNotBlank() ->
                                "The failure came from the app itself, not the provider — retry once, then report it with /diagnose <what you did>."
                            else ->
                                "Check provider/keys, retry the operation, or review the trace above."
                        }
                        say(
                            "Autopsy of last failure:\n" +
                            entries.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n") +
                            "\n${entries.size + 1}. Likely stage: $stage\n" +
                            "${entries.size + 2}. Suggested action: $action"
                        )
                    }
                }
                "/ws" -> {
                    when (parsed.args.trim().lowercase()) {
                        "on" -> say(
                            if (config.bitunixApiKey.isBlank() || config.bitunixSecret.isBlank())
                                "WS needs Bitunix API keys — add them in Settings first."
                            else engine.startLiveFeed(config.bitunixApiKey, config.bitunixSecret)
                        )
                        "off" -> {
                            engine.stopLiveFeed()
                            say("WS feed disconnected.")
                        }
                        else -> say(
                            "WS feed: ${if (engine.liveFeedOn) "connected" else "off"}. " +
                                "Usage: /ws on | /ws off"
                        )
                    }
                }
                "/diagnose" -> runAsync(post, ::say) {
                    val complaint = parsed.args.trim()
                    diagnoseReport(complaint = complaint, deep = true)
                }
                "/repair" -> runAsync(post, ::say) { repairNow() }
                else -> say("${parsed.command} is not wired yet.")
            }
            is CommandRouter.Parsed.Trader -> runAsync(post, ::say) {
                // The engine's reply already states the real loop state
                // ("Loop running: scan + manage"), so there is no UI-side
                // trading flag to keep in sync any more.
                engine.handle(parsed.text)
            }
            is CommandRouter.Parsed.Chat -> {
                val text = parsed.text.trim()
                if (SelfRepair.asksForDiagnosis(text)) {
                    // A complaint is not small talk: run the real probes, then let the
                    // model reason over the evidence. Before this, the message either
                    // reached a model with no runtime facts or died on "No model set".
                    busy = true
                    runAsync(post, { s ->
                        say(s)
                        busy = false
                    }) { analyse(text, deep = true) }
                } else if (config.model.isBlank()) {
                    say(
                        "No model set. Open Settings, save provider + key, then /models.\n" +
                            "If something is broken, type /diagnose <what you see> — that runs the real checks without a model.",
                    )
                } else {
                    busy = true
                    val moodSignal = AgentBrain.detectMood(parsed.text)
                    val moodSnapshot = mood
                    runAsync(post, { s ->
                        say(s)
                        busy = false
                    }) {
                        session.beginSession(sessionKey())
                        val sys = AgentBrain.buildSystemPrompt(
                            soul = config.soul,
                            context = liveContext(moodSnapshot),
                            mood = moodSignal,
                            appCapabilities = CAPABILITIES,
                        )
                        // The session owns the tool loop: authority checks, consent,
                        // the 3-call cap and the receipts all live there now.
                        try {
                            session.turn(
                                model = config.model,
                                systemPrompt = sys,
                                userText = parsed.text,
                                onReceipt = { receipt -> say(receipt.render()) },
                            ).answer
                        } catch (e: Exception) {
                            // The provider failed (HTTP, empty content…): it is
                            // a provider failure even though the session is
                            // app code, so /autopsy must not blame the app.
                            val raw = NetworkErrors.rawMessage(e)
                            AgentRuntimeError.record("Error: $raw", AgentRuntimeError.PROVIDER)
                            "The model call failed: $raw\n" +
                                "/autopsy shows the failure; /models re-picks a working model."
                        }
                    }
                }
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
                .imePadding(),
        ) {
            // ---- Top bar: always shows where you are and how to get back ----
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (screen != AppScreen.CHAT) {
                    IconButton(onClick = { screen = AppScreen.CHAT }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = scheme.onSurface)
                    }
                } else {
                    IconButton(onClick = { screen = AppScreen.CONTROL }) {
                        Icon(Icons.Default.Build, "Control panel", tint = scheme.onSurface)
                    }
                }
                Text(
                    when (screen) {
                        AppScreen.CHAT -> "Agent Trader"
                        AppScreen.CONTROL -> "Control"
                        AppScreen.TRADE -> "Trade"
                        AppScreen.SIGNALS -> "Signals"
                        AppScreen.SETTINGS -> "Settings"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                StatusPill(
                    if (engine.live) "LIVE" else "PAPER",
                    if (engine.live) scheme.error else scheme.tertiary,
                )
                // The pill must show a real, running loop — "trading" alone can
                // be a stale flag after an engine re-creation.
                if (engine.trading && engine.isLoopRunning) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill("TRADING", scheme.primary)
                }
            }

            Box(Modifier.weight(1f)) {
                when (screen) {
                    AppScreen.CHAT -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                            // Fast toggles: everything on/off in one tap.
                            LazyRow(
                                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                item {
                                    QuickToggle("Engine", engine.engineOn) { toggleEngine(!engine.engineOn) }
                                }
                                item {
                                    QuickToggle("Trade", engine.trading) { toggleTrading(!engine.trading) }
                                }
                                item {
                                    QuickToggle("Scan", engine.scanOn) { toggleScan(!engine.scanOn) }
                                }
                                item {
                                    QuickToggle("Report", engine.reportOn) { toggleReport(!engine.reportOn) }
                                }
                                item {
                                    QuickToggle("Live", engine.live, danger = true) { toggleLive(!engine.live) }
                                }
                            }
                            // Quick actions
                            LazyRow(
                                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val actions = listOf(
                                    "Signals" to "signals",
                                    "Scan" to "scan once",
                                    "Status" to "status",
                                    "Balance" to "balance",
                                    "Positions" to "positions",
                                    "PnL" to "pnl",
                                    "Funding" to "funding",
                                    "Orders" to "orders",
                                    "Repair" to "repair",
                                    "Help" to "/help",
                                )
                                items(actions) { (label, cmd) ->
                                    AssistChip(
                                        onClick = { if (!busy) runAction(cmd) },
                                        label = { Text(label, fontSize = 12.sp) },
                                        enabled = !busy,
                                    )
                                }
                            }

                            // Messages (scrollable)
                            LazyColumn(
                                Modifier.weight(1f).fillMaxWidth(),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(bottom = 6.dp),
                            ) {
                                items(messages) { msg -> Bubble(msg) }
                            }

                            // Input row
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("Ask anything, or /command") },
                                )
                                Button(
                                    onClick = {
                                        val line = input.trim()
                                        input = ""
                                        if (line.isNotEmpty()) handle(line)
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.padding(start = 6.dp),
                                ) { Text("Send") }
                            }
                        }
                    }

                    AppScreen.SIGNALS -> SignalsScreen(
                        rows = signalRows,
                        loading = signalsLoading,
                        onRefresh = { loadSignals(force = true) },
                        onTrade = { sym, dir ->
                            runAction("manual entry $sym ${if (dir > 0) "long" else "short"} ${(engine.riskFraction * 100).toInt()}")
                        },
                    )
                    AppScreen.CONTROL -> ControlScreen(
                        engine = engine,
                        mood = mood,
                        onToggleEngine = { v -> toggleEngine(v) },
                        onToggleTrading = { v -> toggleTrading(v) },
                        onToggleScan = { v -> toggleScan(v) },
                        onToggleReport = { v -> toggleReport(v) },
                        notificationsOn = config.notifications,
                        onToggleNotifications = { v -> toggleNotifications(v) },
                        onAction = { runAction(it) },
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )

                    AppScreen.TRADE -> TradeScreen(
                        engine = engine,
                        onExecute = { sym, dir, frac ->
                            runAction("manual entry $sym ${if (dir > 0) "long" else "short"} ${(frac * 100).toInt()}")
                        },
                        onClose = { sym -> runAction("close position $sym") },
                        post = post,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )

                    AppScreen.SETTINGS -> SettingsScreen(config, engine, settingsMsg, post) { msg, back ->
                        settingsMsg = msg
                        if (back) {
                            screen = AppScreen.CHAT
                            // Recreate the engine only when the Bitunix credentials changed.
                            val newKeys = config.bitunixApiKey to config.bitunixSecret
                            if (newKeys != lastKeys) {
                                lastKeys = newKeys
                                onEngineRecreate()
                            }
                        }
                    }
                }
            }

            // ---- Bottom navigation: the start menu is always one tap away ----
            NavigationBar {
                NavigationBarItem(
                    selected = screen == AppScreen.CHAT,
                    onClick = { screen = AppScreen.CHAT },
                    icon = { Icon(Icons.Default.Person, null) },
                    label = { Text("Chat") },
                )
                NavigationBarItem(
                    selected = screen == AppScreen.TRADE,
                    onClick = { screen = AppScreen.TRADE },
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    label = { Text("Trade") },
                )
                NavigationBarItem(
                    selected = screen == AppScreen.SIGNALS,
                    onClick = { screen = AppScreen.SIGNALS },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                    label = { Text("Signals") },
                )
                NavigationBarItem(
                    selected = screen == AppScreen.CONTROL,
                    onClick = { screen = AppScreen.CONTROL },
                    icon = { Icon(Icons.Default.Build, null) },
                    label = { Text("Control") },
                )
                NavigationBarItem(
                    selected = screen == AppScreen.SETTINGS,
                    onClick = { screen = AppScreen.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") },
                )
            }
        }
    }

    // One-tap confirmation for everything the MODEL asked the engine to do that
    // moves money, changes risk or starts/stops the loop. The request comes from
    // the worker thread; answering releases the blocked gate there.
    consentRequest?.let { (command, reason) ->
        fun resolve(granted: Boolean) {
            consentResolve?.invoke(granted)
            consentResolve = null
            consentRequest = null
        }
        AlertDialog(
            onDismissRequest = { resolve(false) },
            title = { Text("Confirm before this runs") },
            text = {
                Text("$reason\n\nThe agent asked to run:\n$command")
            },
            confirmButton = {
                TextButton(onClick = { resolve(true) }) { Text("Run it") }
            },
            dismissButton = {
                TextButton(onClick = { resolve(false) }) { Text("No") }
            },
        )
    }
}

/** One-tap toggle chip used in the chat quick bar. */
@Composable
private fun QuickToggle(label: String, active: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        danger && active -> scheme.error
        active -> scheme.primary
        else -> scheme.outline
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, color = if (active) color else scheme.onSurfaceVariant) },
        leadingIcon = {
            Box(
                Modifier.size(8.dp),
            ) {
                Surface(color = color, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxSize()) {}
            }
        },
    )
}

@Composable
private fun Bubble(msg: ChatMsg) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (msg.isUser) scheme.primaryContainer else scheme.surfaceVariant,
            ),
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (msg.isUser) 12.dp else 2.dp,
                bottomEnd = if (msg.isUser) 2.dp else 12.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                // Bot answers are model Markdown: render it instead of showing
                // literal `**` and `#` noise. User text stays plain.
                if (msg.isUser) {
                    Text(
                        msg.text,
                        color = scheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    MarkdownText(
                        msg.text,
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    msg.time,
                    Modifier
                        .padding(top = 2.dp)
                        .align(Alignment.End),
                    color = if (msg.isUser) scheme.onPrimaryContainer.copy(alpha = 0.55f)
                    else scheme.onSurfaceVariant.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    config: AgentConfig,
    engine: TraderEngine,
    msg: String,
    post: (Runnable) -> Unit,
    onDone: (String, Boolean) -> Unit,
) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val scheme = MaterialTheme.colorScheme
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var soul by remember { mutableStateOf(config.soul) }
    var bKey by remember { mutableStateOf(config.bitunixApiKey) }
    var bSecret by remember { mutableStateOf(config.bitunixSecret) }
    var lever by remember { mutableStateOf(config.leverage.toString()) }
    var margin by remember { mutableStateOf(config.marginMode) }
    var position by remember { mutableStateOf(config.positionMode) }
    var live by remember { mutableStateOf(config.live) }

    fun save() {
        config.baseUrl = baseUrl
        config.apiKey = apiKey
        config.model = model
        config.soul = soul
        config.bitunixApiKey = bKey
        config.bitunixSecret = bSecret
        config.leverage = lever.toIntOrNull() ?: 5
        config.marginMode = margin
        config.positionMode = position
        config.live = live
        engine.leverage = config.leverage
        engine.marginMode = margin
        engine.positionMode = position
        engine.live = live
        onDone("Settings saved (mode: ${if (live) "LIVE" else "PAPER"}).", true)
    }

    fun saveProvider() {
        config.baseUrl = baseUrl
        config.apiKey = apiKey
        config.model = model
        onDone("Saved provider.", false)
    }

    fun testProvider() {
        // Probe the values currently in the fields without saving them.
        val probe = ModelClient(baseUrl, apiKey)
        onDone("Testing ${probe.url("models")} ...", false)
        runAsync(post, { result -> onDone(result, false); model = config.model }) {
            val models = probe.listModels()
            if (models.isEmpty()) {
                "No models returned. Check the URL and key."
            } else if (model.isBlank()) {
                // AutoSetModelByKey: no model chosen yet — pick a sensible default now.
                val chosen = ModelClient.pickModel(models)
                config.model = chosen
                "Provider OK: ${models.size} models. Auto-picked model: $chosen"
            } else {
                "Provider OK: ${models.size} models. Using ${config.model}."
            }
        }
    }

    /** AutoSetModelByKey: fetch what this key can see and choose a default model. */
    fun autoPickModel() {
        val client = ModelClient(baseUrl, apiKey)
        runAsync(post, { result -> onDone(result, false); model = config.model }) {
            val models = runCatching { client.listModels() }.getOrElse { emptyList() }
            if (models.isEmpty()) {
                "No models returned. Check the URL and key, then retry."
            } else {
                val chosen = ModelClient.pickModel(models)
                config.model = chosen
                "Model auto-picked: $chosen (from ${models.size} available)."
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(insets)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(
            if (msg.isBlank()) "Configure provider, model and Bitunix keys." else msg,
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("AI API Key") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { testProvider() }) { Text("Test provider") }
            OutlinedButton(onClick = { saveProvider() }) { Text("Save provider only") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { autoPickModel() }) { Text("Auto pick model") }
            Text("fetches the models for this key and picks a default", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(model, { model = it }, label = { Text("Model (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(soul, { soul = it }, label = { Text("Soul / Persona") }, minLines = 2, modifier = Modifier.fillMaxWidth())

        Text("Bitunix Futures", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(bKey, { bKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bSecret, { bSecret = it }, label = { Text("Secret Key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lever, { lever = it }, label = { Text("Leverage (e.g. 5)") }, modifier = Modifier.fillMaxWidth())

        Text("Margin mode", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { margin = "ISOLATION" }, enabled = margin != "ISOLATION") { Text("Isolation") }
            OutlinedButton(onClick = { margin = "CROSS" }, enabled = margin != "CROSS") { Text("Cross") }
        }

        Text("Position mode", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { position = "ONE_WAY" }, enabled = position != "ONE_WAY") { Text("One-way") }
            OutlinedButton(onClick = { position = "HEDGE" }, enabled = position != "HEDGE") { Text("Hedge") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = live, onCheckedChange = { live = it })
            Text(if (live) "LIVE mode — real orders (risky!)" else "PAPER mode — simulated (safe)")
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
            OutlinedButton(onClick = { onDone("", true) }, modifier = Modifier.weight(1f)) { Text("Back") }
        }

        Text(
            "To go live: save real Bitunix keys, keep mode PAPER, test with engine on → start trading. " +
                "When confident, switch to LIVE here.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
        )
    }
}

/** Runs [work] on a background thread and posts the result back to the UI thread. */
private fun runAsync(post: (Runnable) -> Unit, done: (String) -> Unit, work: () -> String) {
    Thread {
        val result = try {
            work()
        } catch (e: Throwable) {
            // A Throwable that escapes a background thread kills the WHOLE app
            // on Android: the chat stops replying and the app "crashes" with
            // no explanation. Catch everything here and report it instead.
            val raw = NetworkErrors.rawMessage(e)
            if (NetworkErrors.isExchange(e)) {
                // Bitunix failures belong to the engine diagnostic, even when
                // they happen inside a command handler.
                val friendly = NetworkErrors.describe(raw)
                EngineRuntimeError.record(friendly)
                friendly
            } else {
                val msg = "Error: $raw"
                // LOCAL, not provider: this thread also runs engine commands and
                // repairs, and blaming the model for an app bug sent /autopsy
                // and the repair flow after the wrong suspect.
                AgentRuntimeError.record(msg, AgentRuntimeError.LOCAL)
                msg
            }
        }
        post(object : Runnable {
            override fun run() {
                done(result)
            }
        })
    }.start()
}
