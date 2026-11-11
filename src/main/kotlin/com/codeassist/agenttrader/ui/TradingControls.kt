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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeassist.agenttrader.trader.MarketMood
import com.codeassist.agenttrader.trader.TraderEngine
import com.codeassist.agenttrader.trader.fmt

/** Where the app is right now — the top-level navigation. */
enum class AppScreen { CHAT, CONTROL, TRADE, SETTINGS }

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
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var leverage by remember { mutableStateOf(engine.leverage.toFloat()) }
    var risk by remember { mutableStateOf((engine.riskFraction * 100).toFloat()) }
    var reportMin by remember { mutableStateOf(engine.reportIntervalMin.toFloat()) }
    val scheme = MaterialTheme.colorScheme

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
                        "Balance ${"%.2f".format(engine.broker.balance)} USDT · realized ${fmt(engine.broker.realizedPnl)} · " +
                            "open ${engine.broker.positions.size}",
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
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Risk sliders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    Text("Leverage — ${leverage.toInt()}x", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = leverage,
                        onValueChange = { leverage = it },
                        onValueChangeFinished = { onAction("set leverage ${leverage.toInt()}") },
                        valueRange = 1f..125f,
                        steps = 23,
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
 * Fast trading surface: pick a symbol, pick a side, pick a size, go.
 */
@Composable
fun TradeScreen(
    engine: TraderEngine,
    onExecute: (String, Int, Double) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var sizePct by remember { mutableStateOf((engine.riskFraction * 100).toFloat()) }
    var price by remember { mutableStateOf(0.0) }
    val scheme = MaterialTheme.colorScheme
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
                        OutlinedButton(onClick = { price = engine.symbolPrice(symbol.trim().uppercase()) }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (price > 0) "%.2f".format(price) else "Price")
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
                    if (engine.broker.positions.isEmpty()) {
                        Text(
                            "No open positions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        engine.broker.positions.values.forEach { p ->
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
