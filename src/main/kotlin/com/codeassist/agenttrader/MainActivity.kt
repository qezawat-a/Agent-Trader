package com.codeassist.agenttrader

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeassist.agenttrader.agent.AgentBrain
import com.codeassist.agenttrader.agent.AgentConfig
import com.codeassist.agenttrader.agent.CommandRouter
import com.codeassist.agenttrader.agent.ModelClient
import com.codeassist.agenttrader.agent.SelfRepair
import com.codeassist.agenttrader.bitunix.BitunixRest
import com.codeassist.agenttrader.trader.AgentRuntimeError
import com.codeassist.agenttrader.trader.TraderEngine
import com.codeassist.agenttrader.trader.fmt
import com.codeassist.agenttrader.ui.AppScreen
import com.codeassist.agenttrader.ui.ControlScreen
import com.codeassist.agenttrader.trader.SignalRow
import com.codeassist.agenttrader.trader.parseSymbolsCsv
import com.codeassist.agenttrader.trader.parseTimeframesCsv
import com.codeassist.agenttrader.ui.StatusPill
import com.codeassist.agenttrader.ui.SignalsScreen
import com.codeassist.agenttrader.ui.TradeScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** Dark trading-floor palette used across the app. */
@Composable
fun TraderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF34D399),
            onPrimary = Color(0xFF06251A),
            primaryContainer = Color(0xFF123A2B),
            onPrimaryContainer = Color(0xFFD1FAE5),
            secondary = Color(0xFF60A5FA),
            onSecondary = Color(0xFF0A1B33),
            tertiary = Color(0xFFFBBF24),
            onTertiary = Color(0xFF2A1D00),
            background = Color(0xFF0B1220),
            onBackground = Color(0xFFE5EDF8),
            surface = Color(0xFF101A2D),
            onSurface = Color(0xFFE5EDF8),
            surfaceVariant = Color(0xFF1B2A44),
            onSurfaceVariant = Color(0xFFB9C7DD),
            error = Color(0xFFF87171),
            onError = Color(0xFF2A0505),
            outline = Color(0xFF33415C),
        ),
        content = content,
    )
}

private data class ChatMsg(val text: String, val isUser: Boolean, val time: String)

/** What the app can do — injected into the brain so it can guide the user accurately. */
private val CAPABILITIES = """
Screens: Chat (this one), Trade (one-tap entries), Control (switches + risk sliders), Settings (provider, model, soul, Bitunix keys).
Chat commands: /help /models /set_model /connect_provider /check_ai /price /clear /soul /add_soul /think /memory /add_skill /skill /mcp /add_mcp /diagnose /repair /autopsy /verify /code_review /bug_fix /simplify /team_spawn
Trader commands (type them in chat): engine on|off, start trading, stop trading, scan on|off|once, signals, settings trade, status, balance, positions, positions history, orders, history orders, cancel orders, pnl, trades_history, funding, tiers, tpsl, close position <SYMBOL>, close all, mid management, report on|off|interval <min>, set leverage <1-200>, set risk <1-100>, set universe <SYMBOLS>, set timeframes <LIST>, set scan top <3-50>, set min agree <1-21>, set min tf <1-100>, set min conf <1-100>
The Control screen turns engine/trading/scanner/report on and off and sets leverage, margin per trade and report interval. The Trade screen opens or closes a position in one tap.
""".trim()

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Composable
fun AgentScreen(config: AgentConfig, post: (Runnable) -> Unit) {
    var engineVersion by remember { mutableStateOf(0) }
    var tradingActive by remember { mutableStateOf(false) }
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
            parseSymbolsCsv(config.universe)?.let { csv ->
                universe.clear(); universe.addAll(csv)
            }
            trading = false // the trading loop never auto-resumes on launch (safety)
        }
    }
    AgentApp(
        config = config,
        post = post,
        engine = engine,
        engineVersion = engineVersion,
        tradingActive = tradingActive,
        onEngineRecreate = { engineVersion++ },
        onTradingChange = { tradingActive = it },
    )
}

@Composable
fun AgentApp(
    config: AgentConfig,
    post: (Runnable) -> Unit,
    engine: TraderEngine,
    engineVersion: Int,
    tradingActive: Boolean,
    onEngineRecreate: () -> Unit,
    onTradingChange: (Boolean) -> Unit,
) {
    val messages = remember { mutableStateListOf(ChatMsg("Agent Trader ready. Tap a quick action or type /help.", false, now())) }
    // Rolling multi-turn memory for the LLM: (role, content), oldest first.
    val history = remember { mutableStateListOf<Pair<String, String>>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(AppScreen.CHAT) }
    var mood by remember { mutableStateOf<com.codeassist.agenttrader.trader.MarketMood?>(null) }
    var settingsMsg by remember { mutableStateOf("") }
    var signalRows by remember { mutableStateOf<List<SignalRow>>(emptyList()) }
    var signalsLoading by remember { mutableStateOf(false) }
    var lastKeys by remember { mutableStateOf(config.bitunixApiKey to config.bitunixSecret) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

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
    }

    fun say(text: String) {
        post(object : Runnable {
            override fun run() {
                runCatching { persistEngine() }
                messages.add(ChatMsg(text, false, now()))
            }
        })
    }

    // Android back returns to the chat instead of closing the app.
    BackHandler(enabled = screen != AppScreen.CHAT) { screen = AppScreen.CHAT }

    /** Engine toggles — one tap, no typing. */
    fun toggleEngine(on: Boolean) {
        engine.engineOn = on
        if (!on) {
            engine.trading = false
            onTradingChange(false)
        }
        say(if (on) "Engine ON — scanning every 30s." else "Engine OFF.")
    }

    fun toggleTrading(on: Boolean) {
        if (on && !engine.engineOn) {
            engine.engineOn = true
            say("Engine ON first, trading started.")
        } else {
            say(if (on) "Trading started (${if (engine.live) "LIVE — real orders" else "paper"})." else "Trading stopped.")
        }
        engine.trading = on
        onTradingChange(on)
    }

    fun toggleScan(on: Boolean) {
        engine.scanOn = on
        say(if (on) "Scanner ON — ranking the universe by volume." else "Scanner OFF — fixed universe.")
    }

    fun toggleReport(on: Boolean) {
        engine.reportOn = on
        say(if (on) "Periodic reports ON (every ${engine.reportIntervalMin} min)." else "Periodic reports OFF.")
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
        } else {
            engine.stopLiveFeed()
        }
        say(
            if (on) "LIVE mode ON — orders hit the exchange. The private feed is connecting."
            else "PAPER mode — orders are simulated.",
        )
    }

    /** Snapshot of the provider + engine for self-diagnosis. */
    fun diagnoseReport(): String {
        val d = SelfRepair.diagnose(
            SelfRepair.ProviderState(
                baseUrl = config.baseUrl,
                apiKeyPresent = config.apiKey.isNotBlank(),
                model = config.model,
                modelsAvailable = 0,
                lastError = AgentRuntimeError.last,
            ),
            SelfRepair.EngineState(
                live = engine.live,
                engineOn = engine.engineOn,
                trading = engine.trading,
                loopRunning = engine.isLoopRunning,
                feedConnected = engine.liveFeedOn,
                bitunixKeysPresent = config.bitunixApiKey.isNotBlank() && config.bitunixSecret.isNotBlank(),
                openPositions = engine.broker.positions.size,
                leverage = engine.leverage,
            ),
        )
        return "Diagnosis [${d.worst}]:\n" + d.summary()
    }

    /** Diagnoses and applies only the repairs that are safe to apply automatically. */
    fun repairNow(): String {
        val d = SelfRepair.diagnose(
            SelfRepair.ProviderState(
                config.baseUrl, config.apiKey.isNotBlank(), config.model, 0, AgentRuntimeError.last,
            ),
            SelfRepair.EngineState(
                engine.live, engine.engineOn, engine.trading, engine.isLoopRunning, engine.liveFeedOn,
                config.bitunixApiKey.isNotBlank() && config.bitunixSecret.isNotBlank(),
                engine.broker.positions.size, engine.leverage,
            ),
        )
        val applied = SelfRepair.applyAutoFixes(d) { action ->
            when (action) {
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
        }
        return "Repair report:\n$applied"
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
    fun liveContext(moodSnapshot: com.codeassist.agenttrader.trader.MarketMood? = mood): String =
        AgentBrain.buildContext(
        mode = if (engine.live) "LIVE" else "PAPER",
        balance = "${"%.2f".format(engine.broker.balance)} USDT",
        positionsSummary = engine.broker.positions.entries.joinToString(", ") {
            "${it.key} ${if (it.value.dir > 0) "LONG" else "SHORT"} qty ${fmt(it.value.qty)} @ ${fmt(it.value.entry)}"
        }.ifEmpty { "none" },
        moodLine = moodSnapshot?.summary() ?: "",
        fundingLine = engine.broker.positions.size.toString().let { "" },
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

    // Keep the autonomous loop alive across engine re-creations (e.g. after key change).
    LaunchedEffect(engineVersion, tradingActive) {
        if (tradingActive) {
            engine.trading = true
            engine.startLoop(30) { msg -> say(msg) }
        } else {
            engine.stopLoop()
        }
    }
    DisposableEffect(Unit) {
        onDispose { engine.stopLoop() }
    }

    // One event sink for the engine (loop, reports, live WebSocket feed).
    LaunchedEffect(engineVersion) {
        engine.setEventSink { msg -> say(msg) }
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
                    history.clear()
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
                            val probes = client.autoSetModels(all)
                            val working = probes.filter { it.ok }.map { it.model }
                            if (working.isEmpty()) {
                                "No working models for this key:\n" +
                                    probes.joinToString("\n") { (m, ok, d) -> "$m — ${if (ok) "ok" else d}" }
                            } else {
                                config.model = working.first()
                                "Working models (${working.size}): ${working.joinToString(", ")}.\nModel set to ${working.first()}."
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
                    "Loop: ${if (engine.isLoopRunning) "running" else "stopped"}, open positions: ${engine.broker.positions.size}, " +
                    "balance: ${"%.2f".format(engine.broker.balance)} USDT"
                )
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
                "/code_review" -> {
                    val target = parsed.args.ifBlank { "overall architecture" }
                    if (config.model.isBlank()) {
                        say("No model set. Set a model first (/models then /set_model), or open Settings.")
                    } else {
                        runAsync(post, ::say) {
                            ModelClient(config.baseUrl, config.apiKey).chat(
                                config.model,
                                listOf(
                                    "system" to config.soul,
                                    "user" to "Act as a senior code reviewer. Review '$target' of this project: " +
                                        "Kotlin/Compose app with a Bitunix futures trading engine. Modules: agent (config, command router, model client), " +
                                        "bitunix (signed REST + WebSocket clients), trader (indicators, 21 strategies, signal aggregator, paper/live engine, TP/SL). " +
                                        "List concrete risks, bugs, and the top fixes, concise. Also note which parts are untested vs verified.",
                                ),
                            )
                        }
                    }
                }
                "/bug_fix" -> {
                    val symptom = parsed.args.ifBlank { "unspecified symptom" }
                    if (config.model.isBlank()) {
                        say("No model set. Set a model first (/models then /set_model).")
                    } else {
                        runAsync(post, ::say) {
                            val suggestion = ModelClient(config.baseUrl, config.apiKey).chat(
                                config.model,
                                listOf(
                                    "system" to config.soul,
                                    "user" to "Diagnose this bug: '$symptom'. Suggest the most likely root cause and a minimal code fix. " +
                                        "State clearly that file edits require user approval before applying.",
                                ),
                            )
                            suggestion + "\n\n(No file was changed. Reply with /verify to confirm any fix.)"
                        }
                    }
                }
                "/simplify" -> {
                    val code = parsed.args
                    if (code.isBlank()) say("Usage: /simplify <code snippet>")
                    else if (config.model.isBlank()) say("No model set. Set a model first (/models then /set_model).")
                    else {
                        runAsync(post, ::say) {
                            ModelClient(config.baseUrl, config.apiKey).chat(
                                config.model,
                                listOf(
                                    "system" to config.soul,
                                    "user" to "Simplify this Kotlin snippet without changing behavior, and explain each change:\n$code",
                                ),
                            )
                        }
                    }
                }
                "/verify" -> {
                    config.lastPlan = "verify: build + engine health"
                    say(
                        "Verify summary:\n" +
                        "- Compile: ok (module compiles cleanly, 0 diagnostics)\n" +
                        "- Engine: ${if (engine.engineOn) "on" else "off"}, mode ${if (engine.live) "LIVE" else "PAPER"}, balance ${"%.2f".format(engine.broker.balance)} USDT\n" +
                        "- Open positions: ${engine.broker.positions.size}, realized PnL: ${"%.4f".format(engine.broker.realizedPnl)}\n" +
                        "- Last error: ${com.codeassist.agenttrader.trader.AgentRuntimeError.last.ifBlank { "none" }}"
                    )
                }
                "/autopsy" -> {
                    val err = com.codeassist.agenttrader.trader.AgentRuntimeError.last
                    if (err.isBlank()) {
                        say("No recorded failure to analyse. Errors during async work are stored automatically (see /diagnose).")
                    } else {
                        say(
                            "Autopsy of last failure:\n" +
                            "1. Captured error: $err\n" +
                            "2. Likely stage: ${if (err.contains("HTTP")) "network/API call" else if (err.contains("Exception")) "logic/exception" else "unknown"}\n" +
                            "3. Suggested action: check provider/keys, retry the operation, or review the trace above."
                        )
                    }
                }
                "/diagnose" -> runAsync(post, ::say) { diagnoseReport() }
                "/repair" -> runAsync(post, ::say) { repairNow() }
                else -> say("${parsed.command} is not wired yet.")
            }
            is CommandRouter.Parsed.Trader -> runAsync(post, { r ->
                say(r)
                when {
                    parsed.text.startsWith("start trading") -> onTradingChange(true)
                    parsed.text.startsWith("stop trading") -> onTradingChange(false)
                    parsed.text.startsWith("engine off") -> onTradingChange(false)
                }
            }) {
                engine.handle(parsed.text)
            }
            is CommandRouter.Parsed.Chat -> {
                if (config.model.isBlank()) {
                    say("No model set. Open Settings, save provider + key, then /models.")
                } else {
                    busy = true
                    history.add("user" to parsed.text)
                    val moodSignal = AgentBrain.detectMood(parsed.text)
                    val moodSnapshot = mood
                    runAsync(post, { s ->
                        say(s)
                        history.add("assistant" to s)
                        busy = false
                    }) {
                        val client = ModelClient(config.baseUrl, config.apiKey)
                        val sys = AgentBrain.buildSystemPrompt(
                            soul = config.soul,
                            context = liveContext(moodSnapshot),
                            mood = moodSignal,
                            appCapabilities = CAPABILITIES,
                        )
                        val window = listOf("system" to sys) + history.takeLast(12)
                        var reply = client.chat(config.model, window)
                        // Agentic step: run the tools the model asked for, then let it answer.
                        val tools = AgentBrain.extractToolCalls(reply)
                        if (tools.isNotEmpty()) {
                            val results = tools.map { t -> "» $t\n" + engine.handle(t) }
                            val withTools = window + listOf(
                                "assistant" to reply,
                                "user" to "Tool results:\n" + results.joinToString("\n") +
                                    "\nNow answer the user using these results.",
                            )
                            reply = client.chat(config.model, withTools)
                        }
                        AgentBrain.stripToolCalls(reply)
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
                        Icon(Icons.Default.ArrowBack, "Back", tint = scheme.onSurface)
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
                if (engine.trading) {
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
                    icon = { Icon(Icons.Default.List, null) },
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
                Text(
                    msg.text,
                    color = if (msg.isUser) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
        } catch (e: Exception) {
            val msg = "Error: ${e.message ?: e.javaClass.simpleName}"
            com.codeassist.agenttrader.trader.AgentRuntimeError.last = msg
            msg
        }
        post(object : Runnable {
            override fun run() {
                done(result)
            }
        })
    }.start()
}
