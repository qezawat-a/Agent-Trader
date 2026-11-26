package com.codeassist.agenttrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeassist.agenttrader.trader.BoardOrder
import com.codeassist.agenttrader.trader.BoardPosition
import com.codeassist.agenttrader.trader.TradeBoard
import com.codeassist.agenttrader.util.fmt
import com.codeassist.agenttrader.util.fmt2

/**
 * Trade-screen panel shown only while a position is open: position numbers,
 * its TP/SL levels, liquidation price and the pending order list.
 *
 * The engine snapshot is blocking (REST), so it is always read on IO by the
 * Trade screen. LIVE gets exchange fields; PAPER gets the local simulation.
 */
@Composable
fun PositionBoardCard(
    board: TradeBoard,
    failed: String,
    onRefresh: () -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing to show when flat — that is the whole point of this panel.
    if (board.positions.isEmpty()) return

    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Open position · ${board.positions.size}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                AssistChip(
                    onClick = onRefresh,
                    label = { Text("Refresh", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.width(14.dp).height(14.dp)) },
                )
            }
            if (board.note.isNotBlank()) {
                Text(board.note, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (failed.isNotBlank()) {
                Text("Board error: $failed", style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }

            board.positions.forEach { p ->
                Spacer(Modifier.height(8.dp))
                PositionDetail(p, onClose)
            }

            if (board.orders.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Orders & TP/SL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                board.orders.forEach { o -> OrderLine(o) }
            }
        }
    }
}

@Composable
private fun PositionDetail(p: BoardPosition, onClose: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sideColor = if (p.side == "LONG") scheme.tertiary else scheme.error
    val pnlColor = if (p.pnl >= 0.0) scheme.tertiary else scheme.error

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${p.symbol}  ${p.side}",
                Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                color = sideColor,
            )
            if (p.leverage > 0) {
                Text("${p.leverage}x", fontSize = 11.sp, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            AssistChip(
                onClick = { onClose(p.symbol) },
                label = { Text("Close", fontSize = 11.sp) },
            )
        }

        KeyValue(
            "Entry", if (p.entry > 0.0) fmt2(p.entry) else "—",
            "Mark", if (p.mark > 0.0) fmt2(p.mark) else "—",
        )
        KeyValue("Qty", fmt(p.qty), "Margin", fmt2(p.margin))
        KeyValue(
            "uPnL", fmt2(p.pnl),
            "ROE", "${if (p.pnlPct >= 0.0) "+" else ""}${fmt2(p.pnlPct)}%",
            firstColor = pnlColor,
            secondColor = pnlColor,
        )
        KeyValue(
            "Liq", p.liq?.let { fmt2(it) } ?: "— (low risk)",
            "SL", p.stop?.let { fmt2(it) } ?: "none",
        )

        if (p.tps.isNotEmpty()) {
            Text(
                "Take profit",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            p.tps.forEach { tp ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(tp.label, Modifier.weight(0.6f), fontSize = 11.sp, color = scheme.tertiary)
                    Text(fmt2(tp.price), Modifier.weight(1.1f), fontSize = 11.sp)
                    Text(tp.detail, Modifier.weight(1f), fontSize = 11.sp, color = scheme.onSurfaceVariant)
                    Text(
                        if (tp.hit) "hit" else "open",
                        Modifier.weight(0.6f), fontSize = 10.sp,
                        color = if (tp.hit) scheme.tertiary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyValue(
    key1: String,
    value1: String,
    key2: String,
    value2: String,
    firstColor: Color = Color.Unspecified,
    secondColor: Color = Color.Unspecified,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(key1, Modifier.weight(0.65f), fontSize = 11.sp, color = muted)
        Text(value1, Modifier.weight(1.35f), fontSize = 11.sp, color = firstColor)
        Text(key2, Modifier.weight(0.65f), fontSize = 11.sp, color = muted)
        Text(value2, Modifier.weight(1.35f), fontSize = 11.sp, color = secondColor)
    }
}

@Composable
private fun OrderLine(o: BoardOrder) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (o.kind.uppercase()) {
        "SL" -> scheme.error
        "TP" -> scheme.tertiary
        else -> scheme.primary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            o.kind.uppercase(), Modifier.weight(0.7f), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, color = tint,
        )
        Text(o.symbol, Modifier.weight(1.2f), fontSize = 10.sp, color = scheme.onSurfaceVariant)
        Text(
            if (o.price > 0.0) fmt2(o.price) else "—",
            Modifier.weight(1.1f), fontSize = 10.sp,
        )
        Text(
            if (o.qty > 0.0) fmt(o.qty) else "—",
            Modifier.weight(0.9f), fontSize = 10.sp,
        )
        Text(
            o.status.ifBlank { "—" }, Modifier.weight(0.9f), fontSize = 10.sp,
            color = scheme.onSurfaceVariant,
        )
    }
}
