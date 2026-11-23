package com.codeassist.agenttrader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeassist.agenttrader.bitunix.Candle
import com.codeassist.agenttrader.trader.TradeBoard
import com.codeassist.agenttrader.trader.TraderEngine
import com.codeassist.agenttrader.util.fmt
import com.codeassist.agenttrader.util.fmt2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val CANDLE_UP = Color(0xFF26A69A)
private val CANDLE_DOWN = Color(0xFFEF5350)
private val LINE_BLUE = Color(0xFF42A5F5)

/** Position levels to draw on top of the candles while a position is open. */
data class ChartOverlay(
    val side: String,
    val qty: Double,
    val pnlPct: Double,
    val entry: Double,
    val stop: Double?,
    val liq: Double?,
    val tps: List<Pair<String, Double>>,
    val otherOrders: List<Pair<String, Double>>,
)

private fun distancePct(entry: Double, level: Double): Double =
    if (entry > 0.0) kotlin.math.abs((level - entry) / entry * 100.0) else 0.0

private const val MAX_DRAWN_CANDLES = 80

/** Last [count] candles, oldest first — the window the canvas actually draws. */
internal fun chartWindow(candles: List<Candle>, count: Int = MAX_DRAWN_CANDLES): List<Candle> =
    if (candles.size <= count) candles else candles.takeLast(count)

/** Price window with a small margin so wicks never touch the edge. */
internal fun chartBounds(candles: List<Candle>): Pair<Double, Double> {
    if (candles.isEmpty()) return 0.0 to 1.0
    val lo = candles.minOf { it.low }
    val hi = candles.maxOf { it.high }
    val span = (hi - lo).takeIf { it > 0.0 } ?: hi.coerceAtLeast(1.0) * 0.01
    return (lo - span * 0.06) to (hi + span * 0.06)
}

private fun timePattern(interval: String): String = when {
    interval.equals("1M", ignoreCase = true) -> "yyyy-MM"
    interval.endsWith("m", ignoreCase = true) || interval.endsWith("h", ignoreCase = true) -> "HH:mm"
    else -> "MM-dd"
}

/**
 * Bitunix-style K-line chart drawn on Compose Canvas: green up / red down
 * candles, volume bars, right price scale, last-price marker, optional smooth
 * close line. No chart library, so the APK gains no dependency.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun CandleChart(
    candles: List<Candle>,
    interval: String,
    lineMode: Boolean = false,
    overlay: ChartOverlay? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val drawn = remember(candles) { chartWindow(candles) }
    val (minPrice, maxPrice) = remember(candles) { chartBounds(candles) }

    Canvas(modifier.fillMaxWidth().height(260.dp)) {
        val w = size.width
        val h = size.height
        val axisW = 56.dp.toPx()
        val plotW = w - axisW
        val labelH = 16.dp.toPx()
        val top = 4.dp.toPx()
        val grid = scheme.outline.copy(alpha = 0.20f)
        val labelStyle = TextStyle(color = scheme.onSurfaceVariant, fontSize = 9.sp)
        val volH = (h - labelH) * 0.18f
        val priceH = (h - labelH) * 0.76f
        val volTop = top + priceH + 6.dp.toPx()

        if (plotW <= 0f || drawn.isEmpty()) {
            drawText(
                measurer, "No candle data",
                topLeft = Offset(12.dp.toPx(), 12.dp.toPx()),
                style = TextStyle(color = scheme.onSurfaceVariant, fontSize = 12.sp),
            )
            return@Canvas
        }

        val range = maxPrice - minPrice
        fun yOf(price: Double): Float = top + ((maxPrice - price) / range * priceH).toFloat()

        for (i in 0..4) {
            val gy = top + priceH * i / 4f
            drawLine(grid, Offset(0f, gy), Offset(plotW, gy), strokeWidth = 1.dp.toPx())
            drawText(
                measurer, fmt2(maxPrice - range * i / 4.0),
                topLeft = Offset(plotW + 4.dp.toPx(), gy - 6.dp.toPx()),
                style = labelStyle,
            )
        }
        for (i in 1..3) {
            val gx = plotW * i / 4f
            drawLine(grid, Offset(gx, top), Offset(gx, volTop + volH), strokeWidth = 1.dp.toPx())
        }

        val n = drawn.size
        val slot = plotW / n
        val bodyW = (slot * 0.64f).coerceAtLeast(1f)
        val wickW = 1.2.dp.toPx()

        if (lineMode) {
            val points = drawn.mapIndexed { i, c -> Offset(slot * (i + 0.5f), yOf(c.close)) }
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val cur = points[i]
                    quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
                }
                lineTo(points.last().x, points.last().y)
            }
            drawPath(path, LINE_BLUE, style = Stroke(width = 2.dp.toPx()))
        } else {
            drawn.forEachIndexed { i, c ->
                val cx = slot * (i + 0.5f)
                val color = if (c.close >= c.open) CANDLE_UP else CANDLE_DOWN
                drawLine(color, Offset(cx, yOf(c.high)), Offset(cx, yOf(c.low)), strokeWidth = wickW)
                val yo = yOf(c.open)
                val yc = yOf(c.close)
                drawRect(
                    color,
                    topLeft = Offset(cx - bodyW / 2f, minOf(yo, yc)),
                    size = Size(bodyW, maxOf(abs(yc - yo), 1.5.dp.toPx())),
                )
            }
        }

        val maxVol = drawn.maxOf { it.volume }.takeIf { it > 0.0 } ?: 1.0
        drawn.forEachIndexed { i, c ->
            val cx = slot * (i + 0.5f)
            val barH = (c.volume / maxVol * volH).toFloat()
            val color = (if (c.close >= c.open) CANDLE_UP else CANDLE_DOWN).copy(alpha = 0.55f)
            drawRect(color, topLeft = Offset(cx - bodyW / 2f, volTop + volH - barH), size = Size(bodyW, barH))
        }
        drawLine(grid, Offset(0f, volTop + volH), Offset(plotW, volTop + volH), strokeWidth = 1.dp.toPx())

        // Position levels are drawn over the candles only while a position is
        // open, the way the Bitunix client marks entry / SL / TP / liquidation.
        if (overlay != null) {
            val dashed = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()), 0f)
            val levels = ArrayList<Triple<String, Pair<Double, Color>>>()
            val marker = if (overlay.side == "LONG") "B" else "S"
            levels += Triple(
                "$marker ${fmt(overlay.qty)}  ${if (overlay.pnlPct >= 0) "+" else ""}${fmt2(overlay.pnlPct)}%",
                overlay.entry to Color(0xFFFFC107),
            )
            overlay.stop?.let { price ->
                if (price > 0.0) levels += Triple(
                    "SL +${fmt2(distancePct(overlay.entry, price))}%",
                    price to CANDLE_DOWN,
                )
            }
            overlay.tps.forEach { (tag, price) ->
                if (price > 0.0) levels += Triple(
                    "$tag +${fmt2(distancePct(overlay.entry, price))}%",
                    price to CANDLE_UP,
                )
            }
            overlay.liq?.let { price ->
                if (price > 0.0) levels += Triple("LQ ${fmt2(price)}", price to Color(0xFFFF7043))
            }
            overlay.otherOrders.forEach { (tag, price) ->
                if (price > 0.0) levels += Triple("$tag ${fmt2(price)}", price to LINE_BLUE)
            }
            levels.forEach { (text, level) ->
                val (price, color) = level
                if (price in minPrice..maxPrice) {
                    val y = yOf(price)
                    drawLine(
                        color.copy(alpha = 0.9f),
                        Offset(0f, y), Offset(plotW, y),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = dashed,
                    )
                    val labelStyle = TextStyle(
                        color = Color(0xFF06121F), fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    )
                    val pillW = measurer.measure(text, labelStyle).size.width + 10.dp.toPx()
                    drawRect(color, topLeft = Offset(0f, y - 8.dp.toPx()), size = Size(pillW, 16.dp.toPx()))
                    drawText(measurer, text, topLeft = Offset(4.dp.toPx(), y - 6.dp.toPx()), style = labelStyle)
                }
            }
        }

        val lastY = yOf(drawn.last().close)
        drawLine(scheme.primary.copy(alpha = 0.75f), Offset(0f, lastY), Offset(plotW, lastY), strokeWidth = 1.dp.toPx())
        drawRect(scheme.primary, topLeft = Offset(plotW, lastY - 9.dp.toPx()), size = Size(axisW, 18.dp.toPx()))
        drawText(
            measurer, fmt2(drawn.last().close),
            topLeft = Offset(plotW + 4.dp.toPx(), lastY - 6.dp.toPx()),
            style = TextStyle(color = Color(0xFF06121F), fontSize = 9.sp, fontWeight = FontWeight.Bold),
        )

        val formatter = SimpleDateFormat(timePattern(interval), Locale.ROOT)
        for (i in 0..3) {
            val idx = (n - 1) * i / 3
            val x = slot * (idx + 0.5f) - 20.dp.toPx()
            drawText(
                measurer, formatter.format(Date(drawn[idx].time)),
                topLeft = Offset(x.coerceAtLeast(0f), h - labelH + 1.dp.toPx()),
                style = labelStyle,
            )
        }
    }
}

/** Chart section in the Trade screen. Follows the symbol typed in Quick trade. */
@Composable
fun CandleChartCard(
    engine: TraderEngine,
    symbol: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val intervals = listOf("1m", "3m", "5m", "15m")
    var interval by remember { mutableStateOf("15m") }
    var lineMode by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var board by remember { mutableStateOf(TradeBoard(emptyList(), emptyList())) }
    var activeSymbol by remember { mutableStateOf(symbol.trim().uppercase(Locale.ROOT)) }
    var lastPositionSymbols by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    val typedSymbol = symbol.trim().uppercase(Locale.ROOT)
    val activePosition = board.positions.firstOrNull { it.symbol.equals(activeSymbol, ignoreCase = true) }

    // One authoritative read from the engine. Whenever a position is open the
    // chart follows that symbol automatically; otherwise it follows Quick trade.
    LaunchedEffect(engine, typedSymbol) {
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching { engine.tradeBoard() } }
            result.onSuccess { fresh ->
                board = fresh
                val symbols = fresh.positions.map { it.symbol.uppercase(Locale.ROOT) }.toSet()
                if (symbols != lastPositionSymbols) {
                    lastPositionSymbols = symbols
                    activeSymbol = symbols.firstOrNull() ?: typedSymbol
                } else if (symbols.isEmpty()) {
                    activeSymbol = typedSymbol
                }
            }
            delay(4_000)
        }
    }

    // Debounced: typing a symbol one letter at a time must not spam the API.
    LaunchedEffect(activeSymbol, interval, refreshKey) {
        if (activeSymbol.isBlank()) {
            candles = emptyList()
            error = "Enter a symbol above."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(300)
        val result = withContext(Dispatchers.IO) {
            runCatching { engine.chartCandles(activeSymbol, interval, 120) }
        }
        result.onSuccess { rows ->
            candles = rows
            error = if (rows.isEmpty()) "No candles for $activeSymbol ($interval)." else ""
        }.onFailure { e ->
            candles = emptyList()
            error = e.message?.take(140) ?: e.javaClass.simpleName
        }
        loading = false
    }

    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "K-line chart",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                AssistChip(
                    onClick = { refreshKey++ },
                    enabled = !loading,
                    label = { Text(if (loading) "Loading…" else "Refresh", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.width(14.dp).height(14.dp)) },
                )
            }
            Text(
                "$activeSymbol  ·  $interval  ·  closed candles",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )

            // Every symbol with an open position gets a chip. The first one is
            // selected automatically by the engine poll above.
            if (board.positions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                LazyRow {
                    items(board.positions) { p ->
                        val ps = p.symbol.uppercase(Locale.ROOT)
                        FilterChip(
                            selected = activeSymbol == ps,
                            onClick = { activeSymbol = ps },
                            label = { Text(ps, fontSize = 11.sp) },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            LazyRow {
                items(intervals) { tf ->
                    FilterChip(
                        selected = interval == tf,
                        onClick = { interval = tf },
                        label = { Text(tf, fontSize = 11.sp) },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !lineMode, onClick = { lineMode = false }, label = { Text("Candles", fontSize = 11.sp) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = lineMode, onClick = { lineMode = true }, label = { Text("Line", fontSize = 11.sp) })
            }

            Spacer(Modifier.height(6.dp))
            if (candles.isNotEmpty()) {
                val first = candles.first()
                val last = candles.last()
                val change = if (first.close > 0) (last.close - first.close) / first.close * 100.0 else 0.0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fmt2(last.close),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (change >= 0) CANDLE_UP else CANDLE_DOWN,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${if (change >= 0) "+" else ""}${fmt2(change)}%",
                        fontSize = 12.sp,
                        color = if (change >= 0) CANDLE_UP else CANDLE_DOWN,
                    )
                }
                Text(
                    "O ${fmt2(first.open)}   H ${fmt2(candles.maxOf { it.high })}   " +
                        "L ${fmt2(candles.minOf { it.low })}   C ${fmt2(last.close)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                val openPosition = activePosition
                if (openPosition != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(openPosition.side).append("  qty ").append(fmt(openPosition.qty))
                            append("  entry ").append(fmt2(openPosition.entry))
                            openPosition.stop?.let { append("  SL ").append(fmt2(it)) }
                            append("  liq ").append(openPosition.liq?.let { fmt2(it) } ?: "—")
                            append("  PnL ").append(fmt2(openPosition.pnl))
                            append("  ").append(fmt2(openPosition.pnlPct)).append("%")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (openPosition.pnl >= 0.0) CANDLE_UP else CANDLE_DOWN,
                    )
                }
                CandleChart(
                    candles = candles,
                    interval = interval,
                    lineMode = lineMode,
                    overlay = openPosition?.let { p ->
                        ChartOverlay(
                            side = p.side,
                            qty = p.qty,
                            pnlPct = p.pnlPct,
                            entry = p.entry,
                            stop = p.stop,
                            liq = p.liq,
                            tps = p.tps.map { it.label to it.price },
                            otherOrders = board.orders
                                .filter { it.symbol.equals(p.symbol, ignoreCase = true) }
                                .filter { it.kind.uppercase(Locale.ROOT) !in setOf("TP", "SL") }
                                .map { it.kind to it.price },
                        )
                    },
                )
            } else if (!loading) {
                Text(
                    error.ifBlank { "No chart data." },
                    Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            } else {
                Text(
                    "Loading $activeSymbol $interval candles…",
                    Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            if (candles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Recent candles", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                val df = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
                candles.takeLast(6).asReversed().forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(df.format(Date(c.time)), Modifier.weight(1.35f), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                        Text(fmt2(c.open), Modifier.weight(1f), fontSize = 10.sp)
                        Text(fmt2(c.high), Modifier.weight(1f), fontSize = 10.sp)
                        Text(fmt2(c.low), Modifier.weight(1f), fontSize = 10.sp)
                        Text(
                            fmt2(c.close), Modifier.weight(1f), fontSize = 10.sp,
                            color = if (c.close >= c.open) CANDLE_UP else CANDLE_DOWN,
                        )
                    }
                }
            }
        }
    }
}
