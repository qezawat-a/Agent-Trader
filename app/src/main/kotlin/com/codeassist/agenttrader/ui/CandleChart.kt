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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeassist.agenttrader.bitunix.Candle
import com.codeassist.agenttrader.trader.BoardPosition
import com.codeassist.agenttrader.trader.TraderEngine
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

private const val MAX_DRAWN_CANDLES = 80

/** Last [count] candles, oldest first — the window the canvas actually draws. */
internal fun chartWindow(candles: List<Candle>, count: Int = MAX_DRAWN_CANDLES): List<Candle> =
    if (candles.size <= count) candles else candles.takeLast(count)

/** A price line that belongs to the open position shown on the chart. */
internal enum class ChartLevelKind { MARK, ENTRY, STOP, LIQUIDATION, TAKE_PROFIT }

internal data class ChartLevel(
    val kind: ChartLevelKind,
    val label: String,
    val price: Double,
)

/**
 * The engine position levels that must be visible on the selected symbol's
 * chart. Values that the exchange has not supplied are omitted rather than
 * invented.
 */
internal fun chartPositionLevels(position: BoardPosition?): List<ChartLevel> {
    if (position == null) return emptyList()
    val levels = ArrayList<ChartLevel>()
    if (position.mark > 0.0) levels += ChartLevel(ChartLevelKind.MARK, "MARK", position.mark)
    if (position.entry > 0.0) levels += ChartLevel(ChartLevelKind.ENTRY, "ENTRY", position.entry)
    if (position.stop != null && position.stop > 0.0) {
        levels += ChartLevel(ChartLevelKind.STOP, "SL", position.stop)
    }
    if (position.liq != null && position.liq > 0.0) {
        levels += ChartLevel(ChartLevelKind.LIQUIDATION, "LIQ", position.liq)
    }
    position.tps.forEachIndexed { index, tp ->
        if (tp.price > 0.0) {
            levels += ChartLevel(ChartLevelKind.TAKE_PROFIT, "TP${index + 1}", tp.price)
        }
    }
    return levels
}

/**
 * Price window with a small margin so wicks and open-position levels never
 * touch the edge. Position levels are included so an entry or liquidation
 * price outside the candle range is still visible.
 */
internal fun chartBounds(
    candles: List<Candle>,
    extraPrices: List<Double> = emptyList(),
): Pair<Double, Double> {
    if (candles.isEmpty()) return 0.0 to 1.0
    val validExtra = extraPrices.filter { it.isFinite() && it > 0.0 }
    val lo = minOf(candles.minOf { it.low }, validExtra.minOrNull() ?: Double.MAX_VALUE)
    val hi = maxOf(candles.maxOf { it.high }, validExtra.maxOrNull() ?: -Double.MAX_VALUE)
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
    position: BoardPosition? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val drawn = remember(candles) { chartWindow(candles) }
    val levels = remember(position) { chartPositionLevels(position) }
    val (minPrice, maxPrice) = remember(candles, levels) {
        chartBounds(candles, levels.map { it.price })
    }

    // Reveal animation, driven by the frame clock instead of
    // androidx.compose.animation.core. That module is NOT on the on-device
    // CodeAssist build's dependency list (module.toml), so importing Animatable
    // or tween from it compiles under Gradle — which pulls it in transitively —
    // but fails to resolve in the on-device build. withFrameNanos lives in
    // compose-runtime, which both builds already have.
    //
    // The value must RESET to 0 for every new data set (new symbol, new
    // interval, refresh) so the chart draws itself in each time instead of
    // snapping. Keyed on `drawn`, so a recomposition with the same candles does
    // not replay it.
    var reveal by remember { mutableStateOf(0f) }
    LaunchedEffect(drawn) {
        reveal = 0f
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 550_000_000f).coerceIn(0f, 1f)
            // Smoothstep ease-in-out: slow at both ends, quick in the middle.
            reveal = t * t * (3f - 2f * t)
            if (t >= 1f) break
        }
    }

    val chartDescription = remember(position, drawn) {
        if (position == null) {
            "Price chart, ${drawn.size} candles"
        } else {
            buildString {
                append("${position.side} ${position.symbol} position chart. ")
                append("Entry ${fmt2(position.entry)}, mark ${fmt2(position.mark)}. ")
                append(levels.joinToString(", ") { "${it.label} ${fmt2(it.price)}" })
            }
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(260.dp)
            .semantics { contentDescription = chartDescription },
    ) {
        // Read INSIDE the draw scope on purpose: a State read here invalidates
        // only the draw phase, so the 550 ms reveal repaints the canvas without
        // recomposing the whole card on every frame.
        val progress = reveal
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

        // How many candles the reveal has uncovered so far. At least one, so the
        // very first frame is not an empty plot.
        val visible = (n * progress).toInt().coerceIn(1, n)

        if (lineMode) {
            val points = drawn.mapIndexed { i, c -> Offset(slot * (i + 0.5f), yOf(c.close)) }
            val shown = points.take(visible)
            if (shown.size >= 2) {
                val path = Path().apply {
                    moveTo(shown.first().x, shown.first().y)
                    for (i in 1 until shown.size) {
                        val prev = shown[i - 1]
                        val cur = shown[i]
                        quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
                    }
                    lineTo(shown.last().x, shown.last().y)
                }
                drawPath(path, LINE_BLUE, style = Stroke(width = 2.dp.toPx()))
            }
        } else {
            drawn.forEachIndexed { i, c ->
                if (i >= visible) return@forEachIndexed
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

        // Position levels are drawn from the same engine snapshot as the
        // position card. They must be visible even when the current price has
        // moved outside the historical candle range.
        levels.forEach { level ->
            val color = when (level.kind) {
                ChartLevelKind.MARK -> scheme.primary
                ChartLevelKind.ENTRY -> Color(0xFFFFB300)
                ChartLevelKind.STOP -> scheme.error
                ChartLevelKind.LIQUIDATION -> Color(0xFFD84315)
                ChartLevelKind.TAKE_PROFIT -> scheme.tertiary
            }
            val y = yOf(level.price)
            drawLine(
                color = color.copy(alpha = 0.85f),
                start = Offset(0f, y),
                end = Offset(plotW, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = if (level.kind == ChartLevelKind.LIQUIDATION) {
                    PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                } else {
                    null
                },
            )
            drawText(
                measurer,
                "${level.label} ${fmt2(level.price)}",
                topLeft = Offset(4.dp.toPx(), (y - 12.dp.toPx()).coerceAtLeast(0f)),
                style = TextStyle(color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold),
            )
        }

        val maxVol = drawn.maxOf { it.volume }.takeIf { it > 0.0 } ?: 1.0
        drawn.forEachIndexed { i, c ->
            if (i >= visible) return@forEachIndexed
            val cx = slot * (i + 0.5f)
            val barH = (c.volume / maxVol * volH).toFloat()
            val color = (if (c.close >= c.open) CANDLE_UP else CANDLE_DOWN).copy(alpha = 0.55f)
            drawRect(color, topLeft = Offset(cx - bodyW / 2f, volTop + volH - barH), size = Size(bodyW, barH))
        }
        drawLine(grid, Offset(0f, volTop + volH), Offset(plotW, volTop + volH), strokeWidth = 1.dp.toPx())

        // The last-price marker fades in with the reveal, so it does not sit on
        // an empty plot while the candles are still drawing.
        val lastY = yOf(drawn.last().close)
        val markerAlpha = progress.coerceIn(0f, 1f)
        drawLine(
            scheme.primary.copy(alpha = 0.75f * markerAlpha),
            Offset(0f, lastY), Offset(plotW, lastY), strokeWidth = 1.dp.toPx(),
        )
        drawRect(
            scheme.primary.copy(alpha = markerAlpha),
            topLeft = Offset(plotW, lastY - 9.dp.toPx()), size = Size(axisW, 18.dp.toPx()),
        )
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

/** Chart section in the Trade screen. Owns the symbol picker the chart follows. */
@Composable
fun CandleChartCard(
    engine: TraderEngine,
    symbol: String,
    position: BoardPosition? = null,
    onSymbolChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val intervals = listOf("5m", "15m", "1h", "4h", "1d")
    val symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT")
    var interval by remember { mutableStateOf("15m") }
    var lineMode by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    val sym = symbol.trim().uppercase(Locale.ROOT)

    // Debounced: typing a symbol one letter at a time must not spam the API.
    LaunchedEffect(sym, interval, refreshKey) {
        if (sym.isBlank()) {
            candles = emptyList()
            error = "Enter a symbol above."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(300)
        val result = withContext(Dispatchers.IO) {
            runCatching { engine.chartCandles(sym, interval, 120) }
        }
        result.onSuccess { rows ->
            candles = rows
            error = if (rows.isEmpty()) "No candles for $sym ($interval)." else ""
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
                "$sym  ·  $interval  ·  closed candles",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )

            // Symbol picker. It used to live in the Quick trade card; that card
            // is gone, so the chart carries the picker it depends on.
            Spacer(Modifier.height(6.dp))
            LazyRow {
                items(symbols) { s ->
                    FilterChip(
                        selected = sym == s,
                        onClick = { onSymbolChange(s) },
                        label = { Text(s.removeSuffix("USDT"), fontSize = 11.sp) },
                    )
                    Spacer(Modifier.width(6.dp))
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
                Text(
                    "O ${fmt2(first.open)}   H ${fmt2(candles.maxOf { it.high })}   " +
                        "L ${fmt2(candles.minOf { it.low })}   C ${fmt2(last.close)}   " +
                        "${if (change >= 0) "+" else ""}${fmt2(change)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (change >= 0) CANDLE_UP else CANDLE_DOWN,
                )
                CandleChart(
                    candles = candles,
                    interval = interval,
                    lineMode = lineMode,
                    position = position,
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
                    "Loading $sym $interval candles…",
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