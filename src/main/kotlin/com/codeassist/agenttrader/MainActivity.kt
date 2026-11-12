package com.codeassist.agenttrader

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import com.codeassist.agenttrader.notify.Notifier
import com.codeassist.agenttrader.notify.notifiable
import com.codeassist.agenttrader.notify.notificationTitle
import com.codeassist.agenttrader.trader.AgentRuntimeError
import com.codeassist.agenttrader.trader.TraderEngine
import com.codeassist.agenttrader.trader.fmt
import com.codeassist.agenttrader.ui.AppScreen
import com.codeassist.agenttrader.ui.ControlScreen
import com.codeassist.agenttrader.ui.TraderTheme
import com.codeassist.agenttrader.ui.StatusPill
import com.codeassist.agenttrader.ui.SignalsScreen
import com.codeassist.agenttrader.ui.TradeScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

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
Trader commands (type them in chat): engine on|off, start trading, stop trading, scan on|off|once, signals, settings trade, status, balance, positions, positions history, orders, history orders, cancel orders, pnl, trades_history, funding, tiers, tpsl, close position <SYMBOL>, close all, mid management, report on|off|interval <min>, set leverage <1-200>, set risk <1-100>, set universe <SYMBOLS>, set timeframes <LIST>, set scan top <3-50>, set min agree <1-21>, set min tf <1-100>, set min conf <1-100>, set breakeven <pct>, set trail trigger <pct>, set trail distance <pct>, set stop frac <10-90>, set max positions <1-20>, set daily loss <pct>, set guard <sec>
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