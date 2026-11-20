package com.codeassist.agenttrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeassist.agenttrader.trader.MarketMood
import com.codeassist.agenttrader.trader.SignalRow
import com.codeassist.agenttrader.trader.TraderEngine
import com.codeassist.agenttrader.util.fmt
import com.codeassist.agenttrader.util.fmt0
import com.codeassist.agenttrader.util.fmt2

/** Where the app is right now — the top-level navigation. */
enum class AppScreen { CHAT, TRADE, SIGNALS, CONTROL, SETTINGS }

/** Compact status pill used in the top bar. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = color, modifier = modifier) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            color = Color(0xFF06121F),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        )
    }
}

/** A labelled switch row used across the control panel. */
@Composable
fun ToggleRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String = "",
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (checked) tint else MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * The control panel: everything that turns on or off, with sliders for the
 * numbers that matter. One tap to reach, no typing.
 */
@Composable
fun ControlScreen(
    engine: TraderEngine,
    mood: MarketMood?,
    onToggleEngine: (Boolean) -> Unit,
    onToggleTrading: (Boolean) -> Unit,
    onToggleScan: (Boolean) -> Unit,
    onToggleReport: (Boolean) -> Unit,
    notificationsOn: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on configRev: when the universe/timeframes/gates change from the chat or
    // the brain, these fields refresh instead of showing stale values.
    var leverage by remember(engine.configRev) { mutableStateOf(engine.leverage.toFloat()) }
    var risk by remember(engine.configRev) { mutableStateOf((engine.riskFraction * 100).toFloat()) }
    var reportMin by remember(engine.configRev) { mutableStateOf(engine.reportIntervalMin.toFloat()) }
    val scheme = MaterialTheme.colorScheme
    // Poll the engine's own summary: in LIVE it reads the exchange balance, in
    // PAPER the simulated one. Reading broker.balance here was the bug that made
    // LIVE show the paper account.
    val accountLine by produceState(initialValue = engine.accountLine(), engine, engine.live) {
        while (true) {
            value = engine.accountLine()
            kotlinx.coroutines.delay(2_000)
        }
    }

    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (engine.live) "LIVE — real orders" else "PAPER — simulated",
                            Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            color = if (engine.live) scheme.error else scheme.tertiary,
                        )
                        StatusPill(
                            if (engine.trading) "TRADING" else "IDLE",
                            if (engine.trading) scheme.primary else scheme.outline,
                        )
                    }
                    Text(
                        accountLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    if (mood != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Market: ${mood.summary()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (mood.label) {
                                "PANIC" -> scheme.error
                                "STRESSED" -> scheme.tertiary
                                else -> scheme.onSurfaceVariant
                            },
                        )
                        if (mood.bookNote.isNotBlank()) {
                            Text(
                                mood.bookNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Switches", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    ToggleRow(
                        "Engine", Icons.Default.Build, engine.engineOn, onToggleEngine,
                        "scan + manage positions every 30s",
                    )
                    ToggleRow(
                        "Trading", Icons.Default.PlayArrow, engine.trading, onToggleTrading,
                        "place orders (paper or live)",
                        enabled = engine.engineOn,
                        tint = scheme.primary,
                    )
                    ToggleRow(
                        "Scanner", Icons.Default.Search, engine.scanOn, onToggleScan,
                        "rank the universe by volume instead of the fixed list",
                    )
                    ToggleRow(
                        "Periodic report", Icons.Default.DateRange, engine.reportOn, onToggleReport,
                        "post a summary every ${engine.reportIntervalMin} min",
                    )
                    ToggleRow(
                        "Notifications", Icons.Default.Notifications, notificationsOn, onToggleNotifications,
                        "trade events, SL/TP moves and reports in the status bar",
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Risk sliders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    val ceiling = engine.leverageCeiling()
                    Text(
                        "Leverage — ${leverage.toInt()}x  (max ${ceiling}x, each symbol clamps to its own limit)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = leverage.coerceIn(1f, ceiling.toFloat()),
                        onValueChange = { leverage = it },
                        onValueChangeFinished = { onAction("set leverage ${leverage.toInt()}") },
                        valueRange = 1f..ceiling.toFloat(),
                        steps = 39,
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.primary,
                            activeTrackColor = scheme.primary,
                        ),
                    )

                    Text("Margin per trade — ${risk.toInt()}% of balance", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = risk,
                        onValueChange = { risk = it },
                        onValueChangeFinished = { onAction("set risk ${risk.toInt()}") },
                        valueRange = 1f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.tertiary,
                            activeTrackColor = scheme.tertiary,
                        ),
                    )

                    Text("Report interval — ${reportMin.toInt()} min", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = reportMin,
                        onValueChange = { reportMin = it },
                        onValueChangeFinished = { onAction("report interval ${reportMin.toInt()}") },
                        valueRange = 1f..120f,
                        steps = 23,
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.secondary,
                            activeTrackColor = scheme.secondary,
                        ),
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Scanner & signals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Gates: ≥${engine.minAgreeing} strategies · ≥${engine.minTfConfidence.toInt()}% per timeframe · " +
                            "≥${engine.minConfidence.toInt()}% combined (${engine.timeframes.joinToString("+")})" +
                            if (engine.marketFeedOn()) " · WS live" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    var uniText by remember(engine.configRev) {
                        mutableStateOf(if (engine.autoUniverse) "AUTO" else engine.universe.joinToString(","))
                    }
                    OutlinedTextField(
                        uniText, { uniText = it.uppercase() },
                        label = { Text("Symbols (comma-separated, or AUTO)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAction("set universe $uniText") }) { Text("Apply symbols") }
                        OutlinedButton(onClick = { uniText = engine.universe.joinToString(",") }) { Text("Reset") }
                    }
                    Text(
                        if (engine.autoUniverse) {
                            "AUTO is on — the app picks the symbols itself: top ${engine.scanTop} by " +
                                "${engine.autoRankBy}, min ${engine.autoMinVolumeUsd.toLong()} USDT 24h volume. " +
                                "Current: ${engine.universe.joinToString(", ")}"
                        } else {
                            "Fixed list (${engine.universe.size} symbols). Type AUTO and apply to hand the picking " +
                                "back to the app."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("gainers", "volume", "losers", "movers").forEach { mode ->
                            OutlinedButton(
                                onClick = { onAction("auto $mode") },
                                modifier = Modifier.weight(1f),
                            ) { Text(mode, fontSize = 11.sp, maxLines = 1) }
                        }
                    }
                    var volText by remember(engine.configRev) { mutableStateOf(engine.autoMinVolumeUsd.toLong().toString()) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            volText, { volText = it.filter { ch -> ch.isDigit() }.take(12) },
                            label = { Text("Min 24h volume (USDT)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onAction("set min volume $volText") }) { Text("Apply") }
                    }
                    var tfText by remember(engine.configRev) { mutableStateOf(engine.timeframes.joinToString(",")) }
                    OutlinedTextField(
                        tfText, { tfText = it },
                        label = { Text("Timeframes (1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAction("set timeframes $tfText") }) { Text("Apply timeframes") }
                        OutlinedButton(onClick = { tfText = engine.timeframes.joinToString(",") }) { Text("Reset") }
                    }
                    var topText by remember(engine.configRev) { mutableStateOf(engine.scanTop.toString()) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            topText, { topText = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("Scan top") }, singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                        OutlinedButton(onClick = { onAction("set scan top $topText") }) { Text("Apply") }
                    }
                    Text(
                        "More via chat: set min agree 4 · set min tf 60 · set min conf 70 — or ask the agent for settings trade",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Risk manager & exits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Breakeven ${num(engine.risk.breakevenRoiPct)}% · trail trigger ${num(engine.risk.trailingTriggerRoiPct)}% · " +
                            "SL ${num(engine.risk.stopFractionOfLiqPct)}% toward liq · liq guard ${num(engine.risk.liqGuardRoiPct)}% ROI · " +
                            "max ${engine.maxOpenPositions} positions · " +
                            "guard ${if (engine.guardIntervalSec > 0) "${engine.guardIntervalSec}s" else "off"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Keyed on configRev: chat/agent changes refresh these fields too.
                    var beText by remember(engine.configRev) { mutableStateOf(num(engine.risk.breakevenRoiPct)) }
                    var ttText by remember(engine.configRev) { mutableStateOf(num(engine.risk.trailingTriggerRoiPct)) }
                    var mpText by remember(engine.configRev) { mutableStateOf(engine.maxOpenPositions.toString()) }
                    var guardText by remember(engine.configRev) { mutableStateOf(engine.guardIntervalSec.toString()) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            beText, { beText = it },
                            label = { Text("Breakeven % (0 off)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onAction("set breakeven $beText") }) { Text("Apply") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            ttText, { ttText = it },
                            label = { Text("Trail trigger % (0 off)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onAction("set trail trigger $ttText") }) { Text("Apply") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            mpText, { mpText = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("Max positions") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onAction("set max positions $mpText") }) { Text("Apply") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            guardText, { guardText = it.filter { ch -> ch.isDigit() }.take(5) },
                            label = { Text("Guard sec (0 off)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onAction("set guard $guardText") }) { Text("Apply") }
                    }
                    var lgText by remember(engine.configRev) { mutableStateOf(num(engine.risk.liqGuardRoiPct)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            lgText, { lgText = it },
                            label = { Text("Liq guard % ROI (0 off)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onAction("set liq guard $lgText") }) { Text("Apply") }
                    }
                    Text(
                        "Liq guard closes a position before the exchange can liquidate it (warn at ${num(-(engine.risk.liqGuardRoiPct + 15.0))}% ROI). " +
                            "Liq buffer ${num(engine.liqBufferPct)}% caps new entries' leverage the same way — off by default, because any value silently lowers the leverage you asked for.",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        "Also via chat: set trail distance 15 · set stop frac 50 · set liq buffer 2 — or ask: settings trade",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("One-tap actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ActionButton("Scan now", Icons.Default.Search, Modifier.weight(1f)) { onAction("scan once") }
                        ActionButton("Funding", Icons.Default.DateRange, Modifier.weight(1f)) { onAction("funding") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ActionButton("Close all", Icons.Default.Close, Modifier.weight(1f), scheme.error) { onAction("close all") }
                        ActionButton("Cancel orders", Icons.Default.Clear, Modifier.weight(1f), scheme.tertiary) { onAction("cancel orders") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ActionButton("Diagnose", Icons.Default.Warning, Modifier.weight(1f)) { onAction("diagnose") }
                        ActionButton("Repair", Icons.Default.Build, Modifier.weight(1f)) { onAction("repair") }
                    }
                }
            }
        }
    }
}

/** Number display without a trailing ".0" (7.0 → "7"). */
private fun num(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp)
    }
}

/**
 * The signal board: what the 21 strategies say right now, how confident they are,
 * and — when a symbol does not qualify — exactly which gate blocked it.
 */
@Composable
fun SignalsScreen(
    rows: List<SignalRow>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onTrade: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Signals", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = onRefresh, enabled = !loading) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (loading) "Scanning…" else "Refresh", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "15m and 1h must agree · each timeframe needs ≥3 strategies at ≥60% · " +
                            "combined confidence must be ≥70%",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                    Text(
                        if (loading) "Evaluating the universe…" else "No evaluation yet — tap Refresh.",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(rows, key = { it.symbol }) { r ->
            val color = when {
                r.dir > 0 -> scheme.tertiary
                r.dir < 0 -> scheme.error
                else -> scheme.outline
            }
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.symbol, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        StatusPill(r.status, color)
                    }
                    Text(
                        "price ${fmt(r.price)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    r.timeframes.forEach { e ->
                        val side = when (e.dir) {
                            1 -> "LONG"
                            -1 -> "SHORT"
                            else -> "—"
                        }
                        Text(
                            "${e.timeframe}: $side · ${e.agreeing} votes · ${fmt0(e.confidence)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.dir == 0) scheme.onSurfaceVariant else color,
                        )
                    }
                    if (r.dir == 0) {
                        r.reasons.take(2).forEach { why ->
                            Text("why: $why", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = { expanded = if (expanded == r.symbol) null else r.symbol },
                            label = { Text(if (expanded == r.symbol) "Hide votes" else "Strategy votes", fontSize = 11.sp) },
                        )
                        if (r.dir != 0) {
                            AssistChip(
                                onClick = { onTrade(r.symbol, r.dir) },
                                label = { Text(if (r.dir > 0) "Go LONG" else "Go SHORT", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                leadingIcon = {
                                    Icon(
                                        if (r.dir > 0) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        null,
                                        Modifier.size(14.dp),
                                    )
                                },
                            )
                        }
                    }
                    if (expanded == r.symbol) {
                        Spacer(Modifier.height(4.dp))
                        val top = r.timeframes.flatMap { it.votes }.filter { it.dir != 0 }
                            .sortedByDescending { it.confidence }.take(8)
                        if (top.isEmpty()) {
                            Text("No strategy has a side right now.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        } else {
                            top.forEach { v ->
                                Text(
                                    "· ${v.name} (${v.group}): ${if (v.dir > 0) "long" else "short"} ${v.confidence}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fast trading surface: pick a symbol, pick a side, pick a size, go.
 */
@Composable
fun TradeScreen(
    engine: TraderEngine,
    onExecute: (String, Int, Double) -> Unit,
    onClose: (String) -> Unit,
    post: (Runnable) -> Unit,
    modifier: Modifier = Modifier,
) {
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var sizePct by remember { mutableStateOf((engine.riskFraction * 100).toFloat()) }
    var price by remember { mutableStateOf(0.0) }
    val scheme = MaterialTheme.colorScheme
    // LIVE must list exchange positions, not the paper book.
    val livePositions by produceState(initialValue = engine.feedPositions(), engine, engine.live) {
        while (engine.live) {
            value = engine.feedPositions()
            kotlinx.coroutines.delay(2_000)
        }
    }
    val symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT")

    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Quick trade", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = symbol,
                            onValueChange = { symbol = it.uppercase() },
                            label = { Text("Symbol") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            // symbolPrice hits the network — it must never run on the UI thread
                            // (NetworkOnMainThreadException crashed the app here).
                            val sym = symbol.trim().uppercase()
                            Thread {
                                val p = engine.symbolPrice(sym)
                                post(object : Runnable {
                                    override fun run() { price = p }
                                })
                            }.start()
                        }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (price > 0) fmt2(price) else "Price")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        symbols.take(4).forEach { s ->
                            FilterChip(
                                selected = symbol == s,
                                onClick = { symbol = s },
                                label = { Text(s.removeSuffix("USDT"), fontSize = 12.sp) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Size — ${sizePct.toInt()}% of balance", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = sizePct,
                        onValueChange = { sizePct = it },
                        valueRange = 1f..100f,
                        colors = SliderDefaults.colors(thumbColor = scheme.primary, activeTrackColor = scheme.primary),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onExecute(symbol, 1, sizePct / 100.0) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("LONG", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onExecute(symbol, -1, sizePct / 100.0) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.error),
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SHORT", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        if (engine.live) "LIVE orders — real money. PAPER mode is simulated." else "PAPER mode — simulated fills.",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (engine.live) scheme.error else scheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Open positions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (engine.live) {
                        if (livePositions.isEmpty()) {
                            Text(
                                "No live positions on the private feed " +
                                    "(${if (engine.liveFeedOn) "feed connected" else "feed off"}). " +
                                    "Use Positions for the exchange snapshot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        } else {
                            livePositions.forEach { sym ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            sym,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "live on the exchange — use Positions for qty/entry/stop",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    AssistChip(
                                        onClick = { onClose(sym) },
                                        label = { Text("Close") },
                                        leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                                    )
                                }
                            }
                        }
                    } else if (engine.broker.positions.isEmpty()) {
                        Text(
                            "No open positions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        engine.broker.snapshot().forEach { e ->
                            val p = e.value
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${p.symbol} ${if (p.dir > 0) "LONG" else "SHORT"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "qty ${fmt(p.qty)} · entry ${fmt(p.entry)} · stop ${fmt(p.stop)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                AssistChip(
                                    onClick = { onClose(p.symbol) },
                                    label = { Text("Close") },
                                    leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
