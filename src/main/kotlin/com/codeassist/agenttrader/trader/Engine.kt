package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixApiException
import com.codeassist.agenttrader.bitunix.BitunixRest
import com.codeassist.agenttrader.bitunix.BitunixWs
import com.codeassist.agenttrader.bitunix.Candle
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** An open position, paper or live. dir is 1 for long and -1 for short. */
data class Position(
    val symbol: String,
    val dir: Int,
    val entry: Double,
    var qty: Double,
    val originalQty: Double,
    val leverage: Int,
    val plan: TpSlPlan,
    var stop: Double,
    var best: Double,
    var nextRung: Int = 0,
    var breakeven: Boolean = false,
)

/**
 * Paper broker: simulated market fills and PnL. Default mode; no exchange calls.
 * Balance is in USDT. Each trade risks [MARGIN_FRACTION] of the balance as margin.
 */
class PaperBroker(var balance: Double = 1000.0) {
    val positions = LinkedHashMap<String, Position>()
    var realizedPnl = 0.0
    val history = mutableListOf<String>()

    fun open(symbol: String, dir: Int, price: Double, leverage: Int): Position? {
        if (positions.containsKey(symbol)) return null
        val margin = balance * MARGIN_FRACTION
        val qty = margin * leverage / price
        val plan = TpSlPlanner.plan(price, dir, leverage)
        val pos = Position(symbol, dir, price, qty, qty, leverage, plan, plan.stop, price)
        positions[symbol] = pos
        history.add("OPEN ${if (dir > 0) "LONG" else "SHORT"} $symbol @ ${fmt(price)} qty ${fmt(qty)}")
        return pos
    }

    /** Walks the ladder and trailing/breakeven rules with a new price. Returns closes done. */
    fun manage(symbol: String, price: Double): List<String> {
        val p = positions[symbol] ?: return emptyList()
        val events = mutableListOf<String>()
        if (p.dir > 0) p.best = maxOf(p.best, price) else p.best = minOf(p.best, price)
        // Partial take-profit ladder
        while (p.nextRung < p.plan.ladder.size) {
            val (target, frac) = p.plan.ladder[p.nextRung]
            val hit = if (p.dir > 0) price >= target else price <= target
            if (!hit) break
            realize(p, target, frac)
            p.nextRung++
            events.add("TP${p.nextRung} $symbol @ ${fmt(target)}")
        }
        if (positions[symbol] == null) return events
        // Breakeven once ROI reaches 10%
        if (!p.breakeven && TpSlPlanner.roi(p.entry, p.best, p.dir, p.leverage) >= TpSlPlanner.BREAKEVEN_ROI) {
            p.breakeven = true
            p.stop = p.entry
        }
        // Trailing stop once in profit
        if (p.breakeven) {
            val trail = TpSlPlanner.trailStop(p.best, p.dir, p.leverage)
            if (p.dir > 0) p.stop = maxOf(p.stop, trail) else p.stop = minOf(p.stop, trail)
        }
        val stopHit = if (p.dir > 0) price <= p.stop else price >= p.stop
        if (stopHit) {
            events.add(close(symbol, p.stop) ?: "")
        }
        return events.filter { it.isNotEmpty() }
    }

    fun close(symbol: String, price: Double): String? {
        val p = positions[symbol] ?: return null
        realize(p, price, 1.0)
        return "CLOSE $symbol @ ${fmt(price)}"
    }

    private fun realize(p: Position, price: Double, fraction: Double) {
        val closeQty = p.originalQty * fraction
        val pnl = p.dir * (price - p.entry) * closeQty
        realizedPnl += pnl
        balance += pnl
        p.qty -= closeQty
        history.add("PARTIAL ${p.symbol} ${fmt(pnl)} USDT")
        if (p.qty <= 1e-12 || fraction >= 1.0) positions.remove(p.symbol)
    }

    fun unrealized(prices: Map<String, Double>): Double = positions.values.sumOf { p ->
        val px = prices[p.symbol] ?: p.entry
        p.dir * (px - p.entry) * p.qty
    }

    companion object {
        const val MARGIN_FRACTION = 0.10
    }
}

/** Contract specs needed to place valid live orders. */
data class PairInfo(
    val basePrecision: Int,
    val quotePrecision: Int,
    val minTradeVolume: Double,
    val maxLeverage: Int,
)

fun fmt(v: Double): String = "%.4f".format(v)

/**
 * Trader engine: owns the broker, the scanner universe, and the on/off switches.
 * Live trading needs explicit enabling and API keys. Until then every order is paper.
 */
class TraderEngine(private val rest: BitunixRest) {
    val broker = PaperBroker()
    var engineOn = false
    var trading = false
    var scanOn = false
    var live = false
    var leverage = 5
    var marginMode = "ISOLATION"
    var positionMode = "ONE_WAY"
    val universe = mutableListOf("BTCUSDT", "ETHUSDT", "SOLUSDT")

    /** Periodic report settings. */
    var reportOn = false
    var reportIntervalMin = 15L

    private val pairs = HashMap<String, PairInfo>()

    // Autonomous loop (runs while `trading` is true; posts events through [onEvent]).
    private val loopExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "trader-loop").apply { isDaemon = true }
    }
    private var loopTask: ScheduledFuture<*>? = null
    private var reportTask: ScheduledFuture<*>? = null
    private var onEvent: ((String) -> Unit)? = null

    // ---- Live WebSocket feed (private channels) ----
    private var ws: BitunixWs? = null
    private val wsBalance = java.util.concurrent.atomic.AtomicReference<Double?>(null)
    private val wsPositions = java.util.Collections.synchronizedMap(HashMap<String, org.json.JSONObject>())

    /**
     * Opens the private WebSocket feed (balance, position, order, tpsl) and pushes
     * updates through the same event sink the trading loop uses.
     */
    fun startLiveFeed(apiKey: String, secretKey: String): String {
        if (apiKey.isBlank() || secretKey.isBlank()) return "Live feed needs Bitunix API keys."
        stopLiveFeed()
        lateinit var client: BitunixWs
        client = BitunixWs(apiKey, secretKey, object : BitunixWs.Listener {
            override fun onOpen(privateChannel: Boolean) {
                if (privateChannel) client.subscribePrivateAll(listOf("balance", "position", "order", "tpsl"))
            }

            override fun onChannel(ch: String, symbol: String?, data: JSONObject) {
                when (ch) {
                    "balance" -> wsBalance.set(data.optString("available").toDoubleOrNull())
                    "position" -> {
                        val sym = data.optString("symbol").ifBlank { symbol ?: "" }
                        val qty = data.optString("qty").toDoubleOrNull() ?: 0.0
                        if (sym.isNotBlank()) {
                            if (qty == 0.0) wsPositions.remove(sym) else wsPositions[sym] = data
                        }
                    }
                }
                val line = feedEventLine(ch, data, symbol)
                if (line.isNotEmpty()) onEvent?.invoke(line)
            }

            override fun onError(message: String) {
                onEvent?.invoke("WS error: $message")
            }

            override fun onClosed(privateChannel: Boolean, reason: String) {
                onEvent?.invoke("WS closed: $reason")
            }
        })
        ws = client
        client.connectPrivate()
        return "Live feed connecting (balance/position/order/tpsl)."
    }

    fun stopLiveFeed() {
        ws?.close()
        ws = null
        wsBalance.set(null)
        wsPositions.clear()
    }

    /** True when the private WebSocket feed is connected. */
    val liveFeedOn: Boolean get() = ws != null

    /** Balance from the WebSocket feed when available, otherwise a REST call. */
    fun liveBalance(): Double = wsBalance.get() ?: rest.availableUsdt()

    /** Symbols the WebSocket feed currently reports as open. */
    fun feedPositions(): List<String> = synchronized(wsPositions) { wsPositions.keys.toList() }

    private fun candles(symbol: String, tf: String): List<Candle> = rest.klines(symbol, tf, 200)

    /** Loads and caches contract specs for a symbol (best effort; returns null offline). */
    fun pairInfo(symbol: String): PairInfo? {
        pairs[symbol]?.let { return it }
        try {
            val arr = rest.tradingPairs(symbol)
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                pairs[symbol] = PairInfo(
                    basePrecision = o.optInt("basePrecision", 4),
                    quotePrecision = o.optInt("quotePrecision", 1),
                    minTradeVolume = o.optString("minTradeVolume").toDoubleOrNull() ?: 0.0,
                    maxLeverage = o.optInt("maxLeverage", 125),
                )
                return pairs[symbol]
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    /** Test hook: seeds the pair cache without network. */
    internal fun seedPairInfo(symbol: String, info: PairInfo) {
        pairs[symbol] = info
    }

    /** Formats qty to the symbol's base precision, floored, never below minTradeVolume. */
    fun formatQty(symbol: String, raw: Double): String? = formatQty(symbol, raw, pairInfo(symbol))

    internal fun formatQty(symbol: String, raw: Double, info: PairInfo?): String? {
        if (info == null) return "%.6f".format(raw)
        // valueOf() uses the canonical decimal string, avoiding the BigDecimal(double)
        // binary artifact that made 0.123 floor to 0.122 at 3 decimals.
        val qty = java.math.BigDecimal.valueOf(raw)
            .setScale(info.basePrecision, java.math.RoundingMode.FLOOR)
            .toDouble()
        // Skip instead of clamping up: an order floored to zero or below the min
        // volume must never be silently upsized beyond the intended risk.
        if (qty <= 0) return null
        if (info.minTradeVolume > 0 && qty < info.minTradeVolume) return null
        return BigDecimalFormat.format(qty, info.basePrecision)
    }

    /** Formats a price to the symbol's quote precision. */
    fun formatPrice(symbol: String, price: Double): String =
        formatPrice(symbol, price, pairInfo(symbol)?.quotePrecision)

    internal fun formatPrice(symbol: String, price: Double, quotePrecision: Int?): String =
        BigDecimalFormat.format(price, quotePrecision ?: 1)

    /** Effective leverage, clamped to the symbol's max. */
    fun effectiveLeverage(symbol: String): Int = effectiveLeverage(leverage, pairInfo(symbol)?.maxLeverage)

    internal fun effectiveLeverage(base: Int, max: Int?): Int = minOf(base, max ?: base)

    /** Ranks the scanner universe by quote volume and returns the top symbols. */
    fun rankUniverse(top: Int = 10): List<String> {
        val arr = rest.tickers()
        val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("symbol").endsWith("USDT") }
            .sortedByDescending { it.optString("quoteVol").toDoubleOrNull() ?: 0.0 }
        return rows.take(top).map { it.getString("symbol") }
    }

    /** One scan cycle: analyse each symbol and open a paper trade when the signal passes. */
    fun scanOnce(): List<String> {
        val out = mutableListOf<String>()
        val symbols = if (scanOn) rankUniverse() else universe.toList()
        for (sym in symbols) {
            val perTf = SignalAggregator.TIMEFRAMES.associateWith { SignalAggregator.timeframe(candles(sym, it)) }
            val sig = SignalAggregator.combine(perTf) ?: continue
            val price = candles(sym, SignalAggregator.TIMEFRAMES.first()).last().close
            if (live) {
                out.add(openLive(sym, sig.dir, price, sig.confidence) ?: continue)
            } else {
                if (broker.positions.containsKey(sym)) continue
                broker.open(sym, sig.dir, price, leverage)?.let {
                    out.add("OPEN ${if (sig.dir > 0) "LONG" else "SHORT"} $sym @ ${fmt(price)} conf ${"%.0f".format(sig.confidence)}")
                }
            }
        }
        return out
    }

    /** Places a real market order with TP/SL attached. Returns a status line, or null to skip. */
    private fun openLive(symbol: String, dir: Int, price: Double, confidence: Double): String? {
        val livePositions = rest.pendingPositions(symbol)
        for (i in 0 until livePositions.length()) {
            if (livePositions.getJSONObject(i).optString("symbol") == symbol) {
                return "SKIP $symbol: live position already open."
            }
        }
        val lev = effectiveLeverage(symbol)
        val available = rest.availableUsdt()
        val margin = available * PaperBroker.MARGIN_FRACTION
        // TP/SL triggers are MARK_PRICE, so plan the levels from the mark price
        // when the exchange publishes one; fall back to the signal price.
        val mark = runCatching {
            rest.markPrice(symbol).optString("markPrice").toDoubleOrNull()
        }.getOrNull()
        val triggerBasis = mark ?: price
        val plan = TpSlPlanner.plan(triggerBasis, dir, lev)
        val qtyRaw = margin * lev / price
        val qty = formatQty(symbol, qtyRaw) ?: return "SKIP $symbol: qty below min volume."
        val side = if (dir > 0) "BUY" else "SELL"
        val tradeSide = if (positionMode == "HEDGE") "OPEN" else null
        // Sync account leverage/margin mode with this order's needs — only when they
        // differ, using the account's ACTUAL settings (trading_settings endpoint).
        runCatching {
            val s = rest.tradingSettings(symbol)
            if (s.length() > 0) {
                val o = s.getJSONObject(0)
                val curMode = o.optString("marginMode")
                val curLev = if (positionMode == "HEDGE")
                    o.optInt("longLeverage", o.optInt("leverage", 0))
                else o.optInt("leverage", 0)
                if (curMode.isNotBlank() && curMode != marginMode) {
                    rest.changeMarginMode(symbol, mode = marginMode)
                }
                if (curLev > 0 && curLev != lev) rest.changeLeverage(symbol, leverage = lev)
            }
        }
        try {
            val order = rest.placeMarketOrder(
                symbol = symbol, qty = qty, side = side, tradeSide = tradeSide,
                tpPrice = formatPrice(symbol, plan.ladder.first().first),
                slPrice = formatPrice(symbol, plan.stop),
                reduceOnly = false,
            )
            val orderId = order.optString("orderId")
            val status = waitForFill(orderId)
            return "LIVE OPEN ${if (dir > 0) "LONG" else "SHORT"} $symbol qty $qty @ ~${fmt(price)} " +
                "conf ${"%.0f".format(confidence)} | order $orderId $status"
        } catch (e: BitunixApiException) {
            return "LIVE ERROR $symbol: ${e.message}"
        }
    }

    /** Polls an order until terminal status (FILLED / CANCELED / PART_FILLED_CANCELED / EXPIRED). */
    private fun waitForFill(orderId: String, tries: Int = 5): String {
        if (orderId.isBlank()) return "(no orderId)"
        repeat(tries) {
            try {
                val d = rest.orderDetail(orderId)
                val status = d.optString("status", d.optString("orderStatus", "?"))
                if (status in listOf("FILLED", "CANCELED", "PART_FILLED_CANCELED", "EXPIRED")) return status
                Thread.sleep(500)
            } catch (_: Exception) {
                return "?"
            }
        }
        return "PENDING"
    }

    /** Live account snapshot: balance, open positions, per-symbol settings, pending orders. */
    fun liveStatus(): String = try {
        val bal = rest.account()
        val positions = rest.pendingPositions()
        val orders = rest.pendingOrders(limit = 5)
        val syms = (0 until positions.length())
            .map { positions.getJSONObject(it).optString("symbol") }
            .filter { it.isNotBlank() }
            .distinct()
        val bySymbol = HashMap<String, org.json.JSONObject>()
        for (i in 0 until positions.length()) {
            val p = positions.getJSONObject(i)
            val sym = p.optString("symbol")
            if (sym.isNotBlank()) bySymbol[sym] = p
        }
        buildString {
            appendLine("Account: available ${bal.optString("available")} USDT, margin ${bal.optString("margin")}")
            val feed = feedPositions()
            if (feed.isNotEmpty()) appendLine("Feed: live feed tracking ${feed.joinToString(", ")}")
            appendLine("Open positions: ${positions.length()}")
            for (i in 0 until positions.length()) {
                val p = positions.getJSONObject(i)
                appendLine("  ${p.optString("symbol")} ${p.optString("side")} qty ${p.optString("holdQty")} " +
                    "entry ${p.optString("entryPrice")} liq ${p.optString("liqPrice")}")
            }
            if (syms.isNotEmpty()) {
                runCatching { rest.tradingSettings(syms.joinToString(",")) }.onSuccess { settings ->
                    for (j in 0 until settings.length()) {
                        val s = settings.getJSONObject(j)
                        appendLine("  settings ${s.optString("symbol")}: ${s.optString("marginMode")} " +
                            "lev ${s.optInt("leverage")} (long ${s.optInt("longLeverage")}/short ${s.optInt("shortLeverage")})")
                    }
                }
                // Risk tier per open position (bounded to keep the call count low).
                for (sym in syms.take(3)) {
                    val p = bySymbol[sym] ?: continue
                    val qty = p.optString("holdQty").toDoubleOrNull() ?: continue
                    val entry = p.optString("entryPrice").toDoubleOrNull() ?: continue
                    val notional = qty * entry
                    val tier = runCatching { tierFor(rest.positionTiers(sym), notional) }.getOrNull()
                    if (tier != null) {
                        appendLine("  tier $sym: notional ${fmt(notional)} lev ${tier.optInt("leverage")} " +
                            "mmr ${tier.optString("maintenanceMarginRate")}")
                    }
                }
            }
            appendLine("Pending orders: ${orders.length()}")
        }
    } catch (e: Exception) {
        "Live status failed: ${e.message ?: e.javaClass.simpleName} (check Bitunix keys)"
    }

    /** Manages open positions against current prices. */
    fun tick(): List<String> {
        val events = mutableListOf<String>()
        for (sym in broker.positions.keys.toList()) {
            val price = candles(sym, "15m").lastOrNull()?.close ?: continue
            events.addAll(broker.manage(sym, price))
        }
        return events
    }

    fun status(): String = buildString {
        appendLine("Engine: ${if (engineOn) "on" else "off"}, trading: ${if (trading) "on" else "off"}, scan: ${if (scanOn) "on" else "off"}")
        appendLine("Mode: ${if (live) "LIVE" else "PAPER"}, leverage $leverage${if (live) "x (clamped per symbol)" else "x"}, margin $marginMode, position $positionMode")
        if (live) {
            appendLine("Balance (exchange): see 'balance'. Open positions (exchange): see 'positions'.")
        } else {
            appendLine("Balance: ${fmt(broker.balance)} USDT, open positions: ${broker.positions.size}")
        }
    }

    fun pnl(): String =
        "Realized PnL: ${fmt(broker.realizedPnl)} USDT. Balance: ${fmt(broker.balance)} USDT."

    fun closePosition(symbol: String): String {
        if (live) {
            return try {
                val positions = rest.pendingPositions(symbol)
                if (positions.length() == 0) return "No live position on $symbol."
                val p = positions.getJSONObject(0)
                val id = p.optString("positionId")
                rest.flashClose(id)
                "Flash-close sent for $symbol (position $id)."
            } catch (e: Exception) {
                "Close failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val price = candles(symbol, "15m").lastOrNull()?.close ?: return "No price for $symbol."
        return broker.close(symbol, price) ?: "No open position on $symbol."
    }

    fun tradesHistory(): String = if (broker.history.isEmpty()) "No trades yet."
    else broker.history.takeLast(20).joinToString("\n")

    /**
     * Funding-rate snapshot, most extreme rates first (crowding signal).
     * Positive rate = longs pay shorts (crowded longs); negative = shorts pay longs.
     * Pure so it can be tested against the documented response shape.
     */
    internal fun fundingSummary(arr: org.json.JSONArray, top: Int = 8): String {
        val rows = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val rate = o.optString("fundingRate").toDoubleOrNull() ?: return@mapNotNull null
            Triple(o.optString("symbol"), rate, o.optLong("nextFundingTime"))
        }
        if (rows.isEmpty()) return "No funding data."
        val fmtTime = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        val body = rows.sortedByDescending { kotlin.math.abs(it.second) }.take(top)
            .joinToString("\n") { (sym, rate, next) ->
                "%-10s %s %.4f%%  %s  next %s".format(
                    sym,
                    if (rate >= 0) "▲" else "▼",
                    rate * 100,
                    if (rate >= 0) "longs pay shorts" else "shorts pay longs",
                    if (next > 0) fmtTime.format(java.util.Date(next)) else "?",
                )
            }
        return body + "\nExtreme positive funding = crowded longs; extreme negative = crowded shorts."
    }

    /** Starts the autonomous loop: scan + manage every [intervalSec] while trading is on. */
    fun startLoop(intervalSec: Long, onEvent: (String) -> Unit) {
        this.onEvent = onEvent
        if (loopTask != null) return
        loopTask = loopExecutor.scheduleWithFixedDelay({
            try {
                if (!trading) return@scheduleWithFixedDelay
                val events = tick() + scanOnce()
                events.forEach(onEvent)
            } catch (e: Exception) {
                AgentRuntimeError.last = "Loop: ${e.message ?: e.javaClass.simpleName}"
                onEvent("Loop error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS)
        startReport()
    }

    private fun startReport() {
        if (reportTask != null) return
        reportTask = loopExecutor.scheduleWithFixedDelay({
            try {
                if (!reportOn || !engineOn) return@scheduleWithFixedDelay
                onEvent?.invoke(
                    "Periodic report: balance ${fmt(broker.balance)} USDT, positions ${broker.positions.size}, " +
                        "realized ${fmt(broker.realizedPnl)} USDT"
                )
            } catch (_: Exception) {
            }
        }, reportIntervalMin * 60, reportIntervalMin * 60, TimeUnit.SECONDS)
    }

    fun stopLoop() {
        loopTask?.cancel(false)
        reportTask?.cancel(false)
        loopTask = null
        reportTask = null
    }

    /**
     * Event sink shared by the trading loop, the periodic report and the live
     * WebSocket feed. Kept independent of [stopLoop] so feed updates still flow
     * while trading is paused.
     */
    fun setEventSink(sink: ((String) -> Unit)?) {
        onEvent = sink
    }

    val isLoopRunning: Boolean get() = loopTask != null && !loopTask!!.isDone

    /** Handles one trader command typed in chat. Returns the reply text. */
    fun handle(text: String): String {
        val t = text.trim().lowercase()
        return when {
            t.startsWith("engine on") -> { engineOn = true; "Engine on (paper mode)." }
            t.startsWith("engine off") -> { engineOn = false; trading = false; stopLoop(); "Engine off." }
            t.startsWith("start trading") -> when {
                !engineOn -> "Turn the engine on first: engine on"
                else -> {
                    trading = true
                    "Trading started (${if (live) "LIVE — real orders" else "paper"}). Loop running: scan + manage."
                }
            }
            t.startsWith("stop trading") -> { trading = false; stopLoop(); "Trading stopped." }
            t.startsWith("scan on") -> { scanOn = true; "Scanner on: ranking the top symbols by volume." }
            t.startsWith("scan off") -> { scanOn = false; "Scanner off: using the fixed universe." }
            t.startsWith("status") -> status()
            t.startsWith("balance") -> if (live) liveStatus().lines().firstOrNull() ?: "Account unavailable." else pnl()
            t.startsWith("positions history") -> if (live) {
                try {
                    val arr = rest.historyPositions(limit = 10)
                    if (arr.length() == 0) "No closed positions yet."
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val p = arr.getJSONObject(i)
                        val realized = p.optString("realizedPNL").toDoubleOrNull() ?: 0.0
                        val fee = p.optString("fee").toDoubleOrNull() ?: 0.0
                        val funding = p.optString("funding").toDoubleOrNull() ?: 0.0
                        "${p.optString("symbol")} ${p.optString("side")} net ${fmt(netPositionPnl(realized, fee, funding))} " +
                            "(realized ${fmt(realized)} fee ${fmt(fee)} funding ${fmt(funding)})"
                    }
                } catch (e: Exception) {
                    "History failed: ${e.message}"
                }
            } else "Paper mode: realized ${fmt(broker.realizedPnl)} USDT total. See trades_history."
            t.startsWith("positions") -> if (live) liveStatus() else
                (if (broker.positions.isEmpty()) "No open paper positions."
                else broker.positions.values.joinToString("\n") { p ->
                    "${p.symbol} ${if (p.dir > 0) "LONG" else "SHORT"} qty ${fmt(p.qty)} entry ${fmt(p.entry)} stop ${fmt(p.stop)}"
                })
            t.startsWith("trades_history") -> tradesHistory()
            t.startsWith("orders") -> if (live) {
                try {
                    val arr = rest.pendingOrders(limit = 10)
                    if (arr.length() == 0) "No pending orders."
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val o = arr.getJSONObject(i)
                        "${o.optString("symbol")} ${o.optString("side")} ${o.optString("orderType")} qty ${o.optString("qty")} ${o.optString("status")}"
                    }
                } catch (e: Exception) {
                    "Orders failed: ${e.message}"
                }
            } else "Paper mode: orders are simulated instantly."
            t.startsWith("tpsl_history") -> "TP/SL plan: stop at ${"%.0f".format(TpSlPlanner.STOP_FRACTION_OF_LIQ * 100)}% toward liquidation, " +
                "ladder 1R..4R each 25%, breakeven at 10% ROI, trail at 15% ROI."
            t.startsWith("tiers") -> {
                val sym = t.removePrefix("tiers").trim().uppercase().ifEmpty { "BTCUSDT" }
                try {
                    val arr = rest.positionTiers(sym)
                    if (arr.length() == 0) "No risk tiers for $sym."
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val o = arr.getJSONObject(i)
                        "level ${o.optInt("level")} ${o.optString("startValue")}-${o.optString("endValue")} " +
                            "lev ${o.optInt("leverage")} mmr ${o.optString("maintenanceMarginRate")}"
                    }
                } catch (e: Exception) {
                    "Tiers failed: ${e.message}"
                }
            }
            t.startsWith("tpsl") -> if (live) {
                try {
                    val arr = rest.pendingTpSlOrders(limit = 10)
                    if (arr.length() == 0) "No pending TP/SL orders."
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val o = arr.getJSONObject(i)
                        "${o.optString("symbol")} tp ${o.optString("tpPrice")} sl ${o.optString("slPrice")} " +
                            "qty ${o.optString("qty")} ${o.optString("status")}"
                    }
                } catch (e: Exception) {
                    "TP/SL failed: ${e.message}"
                }
            } else "Paper mode: TP/SL orders are simulated."
            t.startsWith("history orders") -> if (live) {
                try {
                    val arr = rest.historyOrders(limit = 10)
                    if (arr.length() == 0) "No order history."
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val o = arr.getJSONObject(i)
                        "${o.optString("symbol")} ${o.optString("side")} ${o.optString("orderType")} " +
                            "qty ${o.optString("qty")} ${o.optString("status")}"
                    }
                } catch (e: Exception) {
                    "Order history failed: ${e.message}"
                }
            } else "Paper mode: order history is local."
            t.startsWith("cancel orders") -> if (live) {
                val sym = t.removePrefix("cancel orders").trim().uppercase()
                try {
                    rest.cancelAllOrders(sym)
                    "Cancel-all-orders sent${if (sym.isNotBlank()) " for $sym" else ""}."
                } catch (e: Exception) {
                    "Cancel failed: ${e.message}"
                }
            } else "Paper mode: nothing to cancel."
            t.startsWith("pnl") -> pnl()
            t.startsWith("funding") -> try {
                fundingSummary(rest.fundingRateBatch())
            } catch (e: Exception) {
                "Funding failed: ${e.message}"
            }
            t.startsWith("report on") -> { reportOn = true; "Periodic reports on (every $reportIntervalMin min)." }
            t.startsWith("report off") -> { reportOn = false; "Periodic reports off." }
            t.startsWith("report interval") -> {
                val m = t.removePrefix("report interval").trim().toLongOrNull()
                if (m != null && m in 1..1440) {
                    reportIntervalMin = m
                    reportTask?.cancel(false)
                    reportTask = null
                    startReport()
                    "Report interval set to $m min."
                } else "Usage: report interval <minutes>"
            }
            t.startsWith("mid management") -> {
                val events = tick()
                if (events.isEmpty()) "No management events (no positions moved)."
                else events.joinToString("\n")
            }
            t.startsWith("close position") -> closePosition(t.removePrefix("close position").trim().uppercase().ifEmpty { "BTCUSDT" })
            t.startsWith("scan once") -> {
                val out = scanOnce()
                if (out.isEmpty()) "Scan done: no qualifying signals."
                else out.joinToString("\n")
            }
            else -> "Not supported yet: $text"
        }
    }
}

/** Fixed-point formatting without scientific notation. */
object BigDecimalFormat {
    fun format(value: Double, decimals: Int): String = java.math.BigDecimal.valueOf(value)
        .setScale(decimals, java.math.RoundingMode.FLOOR)
        .stripTrailingZeros()
        .toPlainString()
        .ifEmpty { "0" }
}

/**
 * Net result of a closed position. Per the Bitunix docs, realizedPNL EXCLUDES
 * both the transaction fee and the funding fee, and the fee is a cost:
 *   net = realizedPNL - fee + funding
 */
internal fun netPositionPnl(realized: Double, fee: Double, funding: Double): Double =
    realized - fee + funding

/**
 * Formats a private WebSocket update into a chat line. Field names follow the
 * official Bitunix WS examples (balance, position, order and tpsl channels).
 * Returns an empty string for unknown channels.
 */
internal fun feedEventLine(ch: String, data: JSONObject, symbol: String?): String = when (ch) {
    "balance" -> "WS balance ${data.optString("coin")} available ${data.optString("available")} " +
        "margin ${data.optString("margin")}"
    "position" -> {
        val sym = data.optString("symbol").ifBlank { symbol ?: "" }
        "WS position $sym ${data.optString("side")} qty ${data.optString("qty")} " +
            "pnl ${data.optString("unrealizedPNL")}"
    }
    "order" -> "WS order ${data.optString("symbol")} ${data.optString("type")} " +
        "qty ${data.optString("qty")} id ${data.optString("orderId")}"
    "tpsl" -> "WS tpsl ${data.optString("symbol")} sl ${data.optString("slPrice")} " +
        "id ${data.optString("orderId")}"
    else -> ""
}

/**
 * Risk tier covering a notional value, or null when the value falls outside the
 * tier table. Tiers: level, startValue, endValue, leverage, maintenanceMarginRate.
 */
internal fun tierFor(tiers: org.json.JSONArray, notional: Double): org.json.JSONObject? {
    for (i in 0 until tiers.length()) {
        val t = tiers.optJSONObject(i) ?: continue
        val start = t.optString("startValue").toDoubleOrNull() ?: continue
        val end = t.optString("endValue").toDoubleOrNull() ?: Double.MAX_VALUE
        if (notional >= start && notional < end) return t
    }
    return null
}

