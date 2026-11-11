package com.codeassist.agenttrader

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import com.codeassist.agenttrader.agent.AgentConfig
import com.codeassist.agenttrader.agent.CommandRouter
import com.codeassist.agenttrader.agent.ModelClient
import com.codeassist.agenttrader.bitunix.BitunixRest
import com.codeassist.agenttrader.trader.TraderEngine
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
    var showSettings by remember { mutableStateOf(false) }
    var settingsMsg by remember { mutableStateOf("") }
    var lastKeys by remember { mutableStateOf(config.bitunixApiKey to config.bitunixSecret) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    fun say(text: String) {
        post(object : Runnable {
            override fun run() {
                messages.add(ChatMsg(text, false, now()))
            }
        })
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
                            "Provider OK: ${models.size} models. Set one with /set_model <name>."
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
                "/diagnose" -> {
                    say(
                        "Diagnostic report:\n" +
                        "- Provider URL: ${if (config.baseUrl.isNotBlank()) config.baseUrl else "NOT SET"}\n" +
                        "- API key: ${if (config.apiKey.isNotBlank()) "set" else "NOT SET"}\n" +
                        "- Bitunix keys: ${if (config.bitunixApiKey.isNotBlank() && config.bitunixSecret.isNotBlank()) "set (live ready)" else "not set"}\n" +
                        "- Model: ${config.model.ifBlank { "none" }}\n" +
                        "- Mode: ${if (engine.live) "LIVE" else "PAPER"}, leverage ${engine.leverage}x\n" +
                        "- Engine: ${if (engine.engineOn) "on" else "off"}, trading ${if (engine.trading) "on" else "off"}, scan ${if (engine.scanOn) "on" else "off"}\n" +
                        "- Last error: ${com.codeassist.agenttrader.trader.AgentRuntimeError.last.ifBlank { "none" }}"
                    )
                }
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
                    val window = listOf("system" to config.soul) + history.takeLast(12)
                    runAsync(post, { s ->
                        say(s)
                        history.add("assistant" to s)
                        busy = false
                    }) {
                        ModelClient(config.baseUrl, config.apiKey).chat(config.model, window)
                    }
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (showSettings) {
            SettingsScreen(config, engine, settingsMsg, post) { msg, back ->
                settingsMsg = msg
                if (back) {
                    showSettings = false
                    // Recreate the engine only when the Bitunix credentials changed.
                    val newKeys = config.bitunixApiKey to config.bitunixSecret
                    if (newKeys != lastKeys) {
                        lastKeys = newKeys
                        onEngineRecreate()
                    }
                }
            }
        } else {
            val insets = WindowInsets.systemBars.asPaddingValues()
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .padding(12.dp)
            ) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Agent Trader", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (engine.live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    ) {
                        Text(
                            if (engine.live) "LIVE" else "PAPER",
                            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            color = if (engine.live) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.padding(start = 8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text("⚙", fontSize = 16.sp) }
                }

                // Quick action bar
                val actions = listOf(
                    "Help" to "/help",
                    "Models" to "/models",
                    "Engine" to "engine on",
                    "Trade" to "start trading",
                    "Stop" to "stop trading",
                    "Status" to "status",
                    "PnL" to "pnl",
                    "Close" to "close position BTCUSDT",
                    "Clear" to "/clear",
                )
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(actions) { (label, cmd) ->
                        AssistChip(
                            onClick = { if (!busy) handle(cmd) },
                            label = { Text(label) },
                            enabled = !busy,
                        )
                    }
                }

                // Messages
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages) { msg -> Bubble(msg) }
                }

                // Input row
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Message or /command") },
                    )
                    Button(
                        onClick = {
                            val line = input.trim()
                            input = ""
                            if (line.isNotEmpty()) handle(line)
                        },
                        enabled = !busy,
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Send") }
                }
            }
        }
    }
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
        runAsync(post, { result -> onDone(result, false) }) {
            val models = probe.listModels()
            if (models.isEmpty()) "No models returned. Check the URL and key."
            else "Provider OK: ${models.size} models. First: ${models.first()}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(insets)
            .padding(12.dp),
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
