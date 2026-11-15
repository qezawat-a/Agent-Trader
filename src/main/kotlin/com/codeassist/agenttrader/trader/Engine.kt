package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixApiException
import com.codeassist.agenttrader.bitunix.BitunixRest
import com.codeassist.agenttrader.bitunix.BitunixWs
import com.codeassist.agenttrader.bitunix.Candle
import com.codeassist.agenttrader.util.fmt
import com.codeassist.agenttrader.util.fmt0
import com.codeassist.agenttrader.util.fmt1
import com.codeassist.agenttrader.util.fmt2
import com.codeassist.agenttrader.util.fmt6
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
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
 * Risk manager & exit rules — every value is user-changeable (chat or Control
 * screen); the defaults keep the engine's long-standing behavior.
 */
data class RiskSettings(
    /** Stop moves to entry once ROI reaches this % (0 = breakeven off). */
    val breakevenRoiPct: Double = 25.0,
    /** Trailing starts once ROI reaches this % — fires after breakeven (0 = off). */
    val trailingTriggerRoiPct: Double = 40.0,
    /** Trailing stop keeps this much ROI distance behind the best price. */
    val trailDistanceRoiPct: Double = 25.0,
    /** Initial stop sits this % of the way toward liquidation. */
    val stopFractionOfLiqPct: Double = 30.0,
    /**
     * Liquidation backstop: close the position once the loss reaches this % of
     * its margin (0 = off). The exchange SL normally fires first (it sits at
     * stopFractionOfLiqPct of the liq distance); this guard closes the position
     * at market when the SL was rejected, missing or gapped through — before
     * the exchange's liquidation engine can. Default -60% ROI leaves ~40% of
     * the margin and a warn at -45%.
     */
    val liqGuardRoiPct: Double = 60.0,
)

/**
 * Paper broker: simulated market fills and PnL. Default mode; no exchange calls.
 * Balance is in USDT. Each trade risks [MARGIN_FRACTION] of the balance as margin.
 */
class PaperBroker(var balance: Double = 1000.0) {
    val positions = LinkedHashMap<String, Position>()
    var realizedPnl = 0.0
    val history = mutableListOf<String>()

    /** Symbols the liquidation guard already warned about (warn once per position). */
    private val liqGuardWarned = mutableSetOf<String>()

    fun open(
        symbol: String, dir: Int, price: Double, leverage: Int,
        risk: RiskSettings = RiskSettings(),
    ): Position? = openSized(symbol, dir, price, leverage, MARGIN_FRACTION, risk)

    /** Opens a position committing [marginFraction] of the balance as margin. */
    fun openSized(
        symbol: String, dir: Int, price: Double, leverage: Int,
        marginFraction: Double, risk: RiskSettings = RiskSettings(),
        maxPositions: Int = 5,
    ): Position? {
        if (positions.containsKey(symbol)) return null
        // Same sizing as the live path: configured share, capped by the free
        // slots, minus the fee buffer.
        val margin = MarginSizing.compute(balance, marginFraction * 100.0, positions.size, maxPositions)
        val qty = margin * leverage / price
        val plan = TpSlPlanner.plan(price, dir, leverage, risk.stopFractionOfLiqPct / 100.0)
        val pos = Position(symbol, dir, price, qty, qty, leverage, plan, plan.stop, price)
        positions[symbol] = pos
        history.add("OPEN ${if (dir > 0) "LONG" else "SHORT"} $symbol @ ${fmt(price)} qty ${fmt(qty)}")
        return pos
    }

    /** Walks the ladder and the configured breakeven/trailing rules with a new price. */
    fun manage(symbol: String, price: Double, risk: RiskSettings = RiskSettings()): List<String> {
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
        // Breakeven: the stop moves to entry once ROI reaches the configured level.
        if (!p.breakeven && risk.breakevenRoiPct > 0 &&
            TpSlPlanner.roi(p.entry, p.best, p.dir, p.leverage) >= risk.breakevenRoiPct / 100.0
        ) {
            p.breakeven = true
            p.stop = p.entry
            events.add("Breakeven $symbol: SL moved to entry ${fmt(p.entry)}")
        }
        // Trailing: fires at the configured trigger ROI (after breakeven) and only tightens.
        if (risk.trailingTriggerRoiPct > 0 &&
            TpSlPlanner.roi(p.entry, p.best, p.dir, p.leverage) >= risk.trailingTriggerRoiPct / 100.0
        ) {
            val trail = TpSlPlanner.trailStop(p.best, p.dir, p.leverage, risk.trailDistanceRoiPct / 100.0)
            val improves = if (p.dir > 0) trail > p.stop else trail < p.stop
            if (improves) {
                p.stop = trail
                events.add("Trailing $symbol: SL moved to ${fmt(trail)}")
            }
        }
        // Liquidation guard — the backstop when the SL is missing, rejected or
        // gapped through: close at the market price BEFORE the exchange's
        // liquidation engine can. Runs before the stop check so a configured
        // floor below the stop's ROI still wins; the normal stop (default 30%
        // of the liq distance = -30% ROI) fires first in the healthy case.
        if (risk.liqGuardRoiPct > 0) {
            val roiPct = TpSlPlanner.roi(p.entry, price, p.dir, p.leverage) * 100.0
            val closeThreshold = -risk.liqGuardRoiPct
            val warnThreshold = -(risk.liqGuardRoiPct - 15.0)
            if (roiPct <= closeThreshold) {
                liqGuardWarned.remove(symbol)
                val liqDist = p.entry / p.leverage
                val liq = if (p.dir > 0) p.entry - liqDist else p.entry + liqDist
                val dist = if (p.entry != 0.0) (liq - price) / p.entry * 100.0 else 0.0
                close(symbol, price)
                events.add(
                    "LIQ GUARD closed $symbol @ ${fmt(price)} — ROI ${fmt1(roiPct)}%, " +
                        "~${fmt1(dist)}% from the estimated liquidation; closed before the exchange could.",
                )
            } else if (roiPct <= warnThreshold && !liqGuardWarned.contains(symbol)) {
                liqGuardWarned.add(symbol)
                val liqDist = p.entry / p.leverage
                val liq = if (p.dir > 0) p.entry - liqDist else p.entry + liqDist
                val dist = if (p.entry != 0.0) (liq - price) / p.entry * 100.0 else 0.0
                events.add(
                    "LIQ GUARD WARNING $symbol: ROI ${fmt1(roiPct)}% — the exchange can " +
                        "liquidate ~${fmt1(dist)}% from here. Close it or add margin.",
                )
            }
            if (positions[symbol] == null) return events.filter { it.isNotEmpty() }
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
        liqGuardWarned.remove(symbol)
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

/** Percent display that shows "off" when the value is 0 (breakeven/trailing/caps). */
internal fun offable(v: Double): String = if (v > 0) "${fmt0(v)}%" else "off"

/**
 * A stop only "improves" when it moves toward the price — the engine never
 * widens a stop it (or the user) already placed.
 */
internal fun improvesStop(dir: Int, candidate: Double, current: Double): Boolean =
    if (dir > 0) candidate > current else candidate < current

/**
 * The breakeven stop for an open position, or null when the rule does not
 * apply yet / the stop in place is already tighter than entry.
 *
 * Breakeven fires on the BEST ROI the position reached: at
 * [RiskSettings.breakevenRoiPct] the position has earned enough that the loss
 * can be taken out at the entry — even if the price pulled back since.
 */
internal fun breakevenStopFor(
    dir: Int, entry: Double, best: Double, lev: Int, curStop: Double, risk: RiskSettings,
): Double? {
    if (risk.breakevenRoiPct <= 0) return null
    val roi = TpSlPlanner.roi(entry, best, dir, lev)
    if (roi < risk.breakevenRoiPct / 100.0) return null
    return if (improvesStop(dir, entry, curStop)) entry else null
}

/**
 * The trailing stop for an open position, or null when the rule does not
 * apply yet.
 *
 * Two things the naive version got wrong, and the reason trailing "did
 * nothing" on the exchange:
 *  - the trigger is measured on the BEST price reached, not the current price,
 *    so a pullback does not freeze the stop where it was;
 *  - the candidate must be tighter than the stop already in place, otherwise
 *    the engine would widen a stop (its own or the user's).
 */
internal fun trailingStopFor(
    dir: Int, entry: Double, best: Double, lev: Int, curStop: Double, risk: RiskSettings,
): Double? {
    if (risk.trailingTriggerRoiPct <= 0) return null
    val bestRoi = TpSlPlanner.roi(entry, best, dir, lev)
    if (bestRoi < risk.trailingTriggerRoiPct / 100.0) return null
    val trail = TpSlPlanner.trailStop(best, dir, lev, risk.trailDistanceRoiPct / 100.0)
    return if (improvesStop(dir, trail, curStop)) trail else null
}

/**
 * Trader engine: owns the broker, the scanner universe, and the on/off switches.
 * Live trading needs explicit enabling and API keys. Until then every order is paper.
 */
class TraderEngine(private val rest: BitunixRest) {
    val broker = PaperBroker()
    var engineOn = false
    var trading = false
    var scanOn = false

    /**
     * LIVE trades real money; every balance/PnL display must then come from the
     * exchange, never from [broker]. Volatile because the WS/loop threads read it.
     */
    @Volatile
    var live = false
        set(value) {
            val leavingLive = field && !value
            field = value
            // Do not carry an old exchange balance into the next LIVE session or
            // into PAPER displays: it would look current when it is not.
            if (leavingLive) {
                exchangeBalance.set(null)
                exchangeBalanceAt.set(0)
            }
        }

    var leverage = 5
    var marginMode = "ISOLATION"
    var positionMode = "ONE_WAY"
    val universe = mutableListOf("BTCUSDT", "ETHUSDT", "SOLUSDT")

    /**
     * Bumped on every universe/timeframe/gate/leverage/risk change so UI fields
     * bound to these values can reset themselves (Compose remember key).
     */
    var configRev: Int = 0
        private set

    // Scanner & signal gates — all user-changeable (set universe / set timeframes / set min ...).
    var scanTop = 10
    var timeframes: List<String> = SignalAggregator.TIMEFRAMES

    /** Auto-universe: when on, each scan refreshes the universe to the top [scanTop]
     *  symbols by 24h change (gainers) or 24h quote volume, filtered by [autoMinVolumeUsd].
     *  On by default since v.2.4.4 — the app picks the symbols itself. A fixed
     *  universe typed with `set universe` turns this off again. */
    var autoUniverse: Boolean = true
        set(value) { field = value; configRev++ }

    /** Minimum 24h quote volume (USDT) for an auto-universe candidate. */
    var autoMinVolumeUsd: Double = 5_000_000.0
        set(value) { field = value; configRev++ }

    /** "gainers" (24h change %) or "volume" (24h quote volume). */
    var autoRankBy: String = "gainers"
        set(value) { field = value; configRev++ }

    private var autoCacheAt: Long = 0

    var minAgreeing: Int = SignalAggregator.MIN_AGREEING
    var minTfConfidence: Double = SignalAggregator.TIMEFRAME_CONFIDENCE
    var minConfidence: Double = SignalAggregator.MIN_CONFIDENCE

    /** How many timeframes must share one side before a trade is allowed.
     *  Int.MAX_VALUE = all of them must agree (the old strict rule). */
    var minSideTfs: Int = Int.MAX_VALUE

    private fun gates() =
        SignalAggregator.Gates(timeframes, minAgreeing, minTfConfidence, minConfidence, minSideTfs)

    // ---- Risk manager & exits (v1.1.7) — every value user-changeable. ----

    /** Breakeven / trailing / initial-stop configuration (RiskSettings defaults keep old behavior). */
    var risk = RiskSettings()

    /** Maximum simultaneously open paper positions (0 = unlimited). */
    var maxOpenPositions = 3

    /** Minimum seconds between two entries (0 = off). */
    var guardIntervalSec = 300L

    /** Take-profit distance as an R-multiple of the stop distance (live orders attach
     *  this rung for the full qty; paper keeps its 4-rung ladder). 1.0 = at the SL distance. */
    var tpR: Double = 1.0

    /**
     * Liquidation buffer for NEW entries, % from the entry (0 = off): the
     * estimated liquidation price must stay at least this far away, which caps
     * the leverage an entry may use ("set liq buffer <pct>").
     *
     * OFF by default. This is a POLICY, not a safety rule: it silently
     * downgrades the leverage the user chose (5% buffer + 0.65% MMR → 17x
     * instead of the 75x the account and the exchange both allow). The
     * safety rules — the stop inside the real liquidation price, and the
     * refusal to open a position with no room — do not depend on it.
     */
    var liqBufferPct: Double = 0.0
        set(value) { field = value; configRev++ }

    // Live-position mid-management state, keyed by symbol.
    private val liveBest = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val liveLastStop = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val liveBreakevenDone = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Symbols the liquidation guard already warned about (warn once per position). */
    private val liqGuardWarned = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * The exchange's tiered risk limit table per symbol
     * (symbol → (tiers, fetchedAt)). The TABLE changes rarely, so it is cached
     * for [MMR_CACHE_MS] — the rate is NOT cached, because it depends on the
     * position's notional and the notional changes with every entry.
     */
    private val tierCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<RiskTier>, Long>>()

    /** Last seen live positions (symbol → "side|qty") — the diff announces opens/closes. */
    private val seenLive = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal var lastEntryAt = 0L

    /** Periodic report settings. */
    var reportOn = false
    var reportIntervalMin = 15L

    /** Share of the account balance committed as margin per trade (0.01 - 1.0). */
    var riskFraction: Double = PaperBroker.MARGIN_FRACTION
        set(value) {
            field = value.coerceIn(0.01, 1.0)
        }

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

    /**
     * Last known exchange balance (available USDT). The WS feed pushes it, and
     * [refreshLiveBalance] fills it over REST when the feed is silent — without
     * it the UI (and the risk manager) would reason about the PAPER balance
     * while the engine trades real money.
     */
    private val exchangeBalance = java.util.concurrent.atomic.AtomicReference<Double?>(null)
    private val exchangeBalanceAt = java.util.concurrent.atomic.AtomicLong(0)
    private var lastWsError: String? = null

    // ---- Signal board (shared by chat, report and the Signals screen) ----
    /**
     * Guards the signal cache ONLY. It is never held across a network call:
     * the evaluation costs ~30 REST calls, and holding a lock for that long
     * blocked every other caller of the engine — the chat went silent and the
     * balance stopped refreshing because both waited on the same monitor.
     */
    private val signalLock = Any()

    @Volatile
    private var signalCache: List<SignalRow> = emptyList()

    @Volatile
    private var signalCacheAt = 0L

    /**
     * Serializes balance refreshes with each other — and nothing else. The
     * HTTP call must not hold the engine monitor: that blocked every other
     * caller for as long as the exchange took to answer.
     */
    private val balanceLock = Any()
    private val wsPositions = java.util.Collections.synchronizedMap(HashMap<String, org.json.JSONObject>())
    // Latest private "tpsl" event per orderId, so the tpsl command can show live TP/SL state.
    private val wsTpSl = java.util.Collections.synchronizedMap(LinkedHashMap<String, org.json.JSONObject>())

    // Public WS market data (no keys needed): "tickers" last prices, "price" market/index/funding.
    private var marketFeed: BitunixWs? = null
    private val wsPrices = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Double>>() // symbol -> (ts, last)
    private val wsMarket = java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject>() // symbol -> mp/ip/fr

    /**
     * Opens the private WebSocket feed (balance, position, order, tpsl) and pushes
     * updates through the same event sink the trading loop uses.
     */
    /**
     * Public WebSocket market feed for the universe: no API keys needed. Subscribes the
     * documented "tickers" channel (24h stats incl. last price per symbol) and the "price"
     * channel (market price, index price, funding rate). Updates are cached silently —
     * public channels never spam the chat.
     */
    fun startMarketFeed(): String {
        if (universe.isEmpty()) return "No universe to stream."
        stopMarketFeed()
        lateinit var client: BitunixWs
        client = BitunixWs("", "", object : BitunixWs.Listener {
            override fun onOpen(privateChannel: Boolean) {
                if (privateChannel) return
                val args = universe.map { "tickers" to it } + universe.map { "price" to it }
                client.subscribePublicBatch(args)
            }

            override fun onChannel(ch: String, symbol: String?, data: JSONObject) {
                when (ch) {
                    "tickers", "ticker" -> {
                        val sym = (data.optString("s").ifBlank { symbol ?: "" }).uppercase()
                        val last = data.optString("la").toDoubleOrNull()
                        if (sym.isNotBlank() && last != null) wsPrices[sym] = System.currentTimeMillis() to last
                    }
                    "price" -> {
                        val sym = (symbol ?: "").uppercase()
                        if (sym.isNotBlank()) wsMarket[sym] = data
                    }
                }
            }

            override fun onError(message: String) { /* public feed failures stay silent */ }

            override fun onClosed(privateChannel: Boolean, reason: String) { }
        })
        marketFeed = client
        client.connectPublic()
        return "Market feed streaming ${universe.size} symbols (tickers + price)."
    }

    fun stopMarketFeed() {
        marketFeed?.close()
        marketFeed = null
    }

    fun marketFeedOn(): Boolean = marketFeed != null

    /** Last price seen on the public WS feed, or null when stale (>15s). */
    fun wsLivePrice(symbol: String): Double? {
        val (ts, price) = wsPrices[symbol.uppercase()] ?: return null
        return if (System.currentTimeMillis() - ts <= 15_000) price else null
    }

    /** Market/index price + funding rate from the "price" channel, or null offline. */
    fun wsMarketData(symbol: String): org.json.JSONObject? = wsMarket[symbol.uppercase()]

    fun startLiveFeed(apiKey: String, secretKey: String): String {
        if (apiKey.isBlank() || secretKey.isBlank()) return "Live feed needs Bitunix API keys."
        stopLiveFeed()
        lateinit var client: BitunixWs
        client = BitunixWs(apiKey, secretKey, object : BitunixWs.Listener {
            override fun onOpen(privateChannel: Boolean) {
                lastWsError = null
                if (privateChannel) client.subscribePrivateAll(listOf("balance", "position", "order", "tpsl"))
            }

            override fun onChannel(ch: String, symbol: String?, data: JSONObject) {
                // A Throwable that escapes an OkHttp callback thread kills the
                // app: the feed dies AND the whole process goes with it.
                runCatching {
                when (ch) {
                    "balance" -> parseBalance(data)?.let { available ->
                        exchangeBalance.set(available)
                        exchangeBalanceAt.set(System.currentTimeMillis())
                    }
                    "position" -> {
                        val sym = data.optString("symbol").ifBlank { symbol ?: "" }
                        val qty = data.optString("qty").toDoubleOrNull() ?: 0.0
                        if (sym.isNotBlank()) {
                            if (qty == 0.0) wsPositions.remove(sym) else wsPositions[sym] = data
                        }
                    }
                    "tpsl" -> {
                        val id = data.optString("orderId")
                        if (id.isNotBlank()) {
                            wsTpSl.remove(id)
                            wsTpSl[id] = data
                            while (wsTpSl.size > 50) wsTpSl.remove(wsTpSl.keys.first())
                        }
                    }
                }
                }
                val line = feedEventLine(ch, data, symbol)
                if (line.isNotEmpty()) onEvent?.invoke(line)
            }

            override fun onError(message: String) {
                // Reconnects repeat the same failure; surface it once until it changes.
                if (message == lastWsError) return
                lastWsError = message
                onEvent?.invoke("WS: $message")
            }

            override fun onClosed(privateChannel: Boolean, reason: String) {
                // 1000 = normal closure; every 1000 here is benign (manual stop, or a
                // graceful server close that now auto-reconnects). It reads like an
                // error, so stay silent for it.
                if (!reason.startsWith("closed 1000")) onEvent?.invoke("WS closed: $reason")
            }
        })
        ws = client
        client.connectPrivate()
        return "Live feed connecting (balance/position/order/tpsl)."
    }

    fun stopLiveFeed() {
        ws?.close()
        ws = null
        // Keep the last exchange balance cached: LIVE mode still needs it for
        // sizing and the liquidation guard while the feed reconnects.
        wsPositions.clear()
        wsTpSl.clear()
    }

    /** TP/SL events received on the private WS feed, newest last. */
    fun feedTpSl(): List<org.json.JSONObject> = synchronized(wsTpSl) { wsTpSl.values.toList() }

    /** True when the private WebSocket feed is connected. */
    val liveFeedOn: Boolean get() = ws != null

    /**
     * Extracts the USDT balance from an account/WS payload. Bitunix names the
     * field differently across endpoints ("available" on the account snapshot,
     * "balance"/"allBalance" on older payloads), so accept all three.
     */
    private fun parseBalance(data: JSONObject): Double? =
        listOf("available", "balance", "allBalance")
            .firstNotNullOfOrNull { data.optString(it).toDoubleOrNull() }

    /**
     * The exchange balance (available USDT) as last seen — from the WS feed or
     * from a REST refresh. Null until the exchange answered once.
     */
    fun liveBalanceSnapshot(): Double? = exchangeBalance.get()

    /**
     * Refreshes the exchange balance over REST and caches it.
     * Blocking (network) — call it from a background thread, never from the UI.
     * Returns the new value, or the last known one when the exchange is unreachable.
     *
     * The HTTP call holds [balanceLock], NOT the engine monitor. Holding the
     * engine monitor across a network call blocked every other caller of the
     * engine for as long as the exchange took to answer — the chat went silent
     * and the balance stopped refreshing, because both wait on the same lock.
     */
    fun refreshLiveBalance(): Double? {
        if (!live) return null
        val fetched = synchronized(balanceLock) {
            runCatching { rest.availableUsdt() }.getOrNull()
        }
        // The mode can switch while the REST call is in flight — do not repopulate
        // an exchange cache that the PAPER session is not allowed to show.
        if (fetched != null && live) {
            exchangeBalance.set(fetched)
            exchangeBalanceAt.set(System.currentTimeMillis())
        }
        return if (live) fetched ?: exchangeBalance.get() else null
    }

    /** Refreshes only when the cache is missing or older than [maxAgeMs]. */
    fun refreshLiveBalanceIfStale(maxAgeMs: Long = 30_000): Double? {
        if (!live) return null
        val cached = exchangeBalance.get()
        if (cached != null && liveBalanceAgeMs() <= maxAgeMs) return cached
        return refreshLiveBalance()
    }

    /** When the exchange balance was last refreshed (ms), 0 when never. */
    fun liveBalanceAgeMs(): Long {
        val at = exchangeBalanceAt.get()
        return if (at == 0L) 0L else System.currentTimeMillis() - at
    }

    /** Test hook: seeds the cached exchange balance without network. */
    internal fun seedLiveBalance(value: Double) {
        exchangeBalance.set(value)
        exchangeBalanceAt.set(System.currentTimeMillis())
    }

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
                    maxLeverage = o.optInt("maxLeverage", MAX_LEVERAGE),
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

    /** Test hook: seeds the tier table without network. */
    internal fun seedRiskTiers(symbol: String, tiers: List<RiskTier>) {
        tierCache[symbol] = tiers to System.currentTimeMillis()
    }

    /**
     * Leverage a NEW entry may use.
     *
     * Two caps, both taken from the exchange's own tiered risk limit table
     * (`get_position_tiers`), which carries per tier the nominal value range,
     * the MAXIMUM LEVERAGE the tier allows, and the maintenance margin rate:
     *
     *   BTCUSDT (read live):    0 – 100 000        → 200x, MMR 0.30%
     *                          100 000 – 400 000  → 150x, MMR 0.40%
     *                          400 000 – 1 000 000 → 100x, MMR 0.50%
     *                        1 000 000 – 4 000 000 →  75x, MMR 0.65%
     *
     *  - the tier the position occupies allows at most that tier's maximum
     *    leverage — the exchange rejects an order above it, so the app must
     *    not ask for one;
     *  - the optional liquidation buffer ("set liq buffer <pct>", 0 = off)
     *    demands that the estimated liquidation price stays at least that %
     *    away from the entry. With the official formula
     *        Liq = Entry × (1 − 1/Leverage + MMR)
     *    that means 1/leverage ≥ buffer/100 + MMR.
     *
     * The buffer is OFF by default: it is a policy, not a safety rule, and any
     * non-zero value silently downgrades the leverage the user asked for
     * (5% buffer + 0.65% MMR → 17x instead of 75x).
     *
     * The tier depends on the notional, the notional on the leverage, and the
     * leverage on the tier's MMR — so this settles on a fixed point rather
     * than reading the tier of the PRE-CAP notional, which is a tier too high
     * and therefore too pessimistic.
     */
    internal fun entryLeverage(symbol: String): Pair<Int, String?> {
        val wanted = effectiveLeverage(symbol)
        val tiers = riskTiers(symbol)
        val margin = marginForEntry()
        if (tiers.isEmpty() || margin <= 0.0) {
            // No tier table (paper mode, or the query failed): the buffer is the
            // only cap, and it uses the simulated default rate.
            val mmr = TpSlPlanner.DEFAULT_MMR
            val cap = if (liqBufferPct > 0.0) {
                (1.0 / (liqBufferPct / 100.0 + mmr)).toInt().coerceAtLeast(1)
            } else MAX_LEVERAGE
            val lev = minOf(wanted, cap).coerceAtLeast(1)
            if (lev >= wanted) return wanted to null
            return lev to "leverage clamped ${wanted}x → ${lev}x · liquidation buffer ${fmt0(liqBufferPct)}% " +
                "needs 1/lev ≥ ${fmt1(liqBufferPct / 100.0 + mmr)} (MMR ${fmt1(mmr * 100)}%) " +
                "(set liq buffer <pct> to change)"
        }
        // The largest leverage whose position would land in a tier that ALLOWS
        // it — the exchange rejects an order above its tier's maximum, so the
        // app must not ask for one. Then the optional buffer on top.
        var lev = maxTierLeverage(tiers, margin, wanted)
        var tier: RiskTier? = null
        var mmr = TpSlPlanner.DEFAULT_MMR
        repeat(2) {
            val t = tierFor(tiers, margin * lev)
            tier = t
            mmr = t?.mmr ?: TpSlPlanner.DEFAULT_MMR
            if (liqBufferPct > 0.0) {
                lev = minOf(lev, (1.0 / (liqBufferPct / 100.0 + mmr)).toInt().coerceAtLeast(1))
            }
        }
        if (lev >= wanted) return wanted to null
        val why = buildString {
            val t = tier
            if (t != null && t.maxLeverage < wanted) {
                append("tier ${t.level} (${fmt(t.startValue)}–${fmt(t.endValue)} notional) allows ${t.maxLeverage}x")
                if (liqBufferPct > 0.0) append(" · ")
            }
            if (liqBufferPct > 0.0) {
                append("liquidation buffer ${fmt0(liqBufferPct)}% needs 1/lev ≥ " +
                    "${fmt1(liqBufferPct / 100.0 + mmr)} (MMR ${fmt1(mmr * 100)}%)")
            }
        }
        return lev to "leverage clamped ${wanted}x → ${lev}x · $why (set liq buffer <pct> to change)"
    }

    /** The margin one entry commits: the configured share of the available balance. */
    private fun marginForEntry(): Double {
        val available = runCatching { rest.availableUsdt() }.getOrNull()
            ?: liveBalanceSnapshot() ?: broker.balance
        return available * riskFraction
    }

    /**
     * The exchange's tier table for a symbol, cached for [MMR_CACHE_MS].
     *
     * The TABLE is cached, not the rate: the rate depends on the position's
     * notional, so caching the rate froze the first position's tier onto every
     * later one — a position ten times the size kept the small position's
     * (lower) maintenance margin rate and the app believed it had more room
     * between entry and liquidation than the exchange would allow.
     */
    internal fun riskTiers(symbol: String): List<RiskTier> {
        tierCache[symbol]?.let { (tiers, at) ->
            if (System.currentTimeMillis() - at < MMR_CACHE_MS) return tiers
        }
        // The paper book has no exchange tiers: the simulated default applies.
        if (!live) return emptyList()
        val tiers = runCatching { parseRiskTiers(rest.positionTiers(symbol)) }.getOrDefault(emptyList())
        tierCache[symbol] = tiers to System.currentTimeMillis()
        return tiers
    }

    /**
     * The maintenance margin rate (MMR) for a symbol at a given notional — the
     * official tier table (`get_position_tiers`) is the source. Falls back to
     * [TpSlPlanner.DEFAULT_MMR] when the table is unavailable: assuming a rate
     * that is too low would place the stop outside the real liquidation price.
     */
    internal fun mmrFor(symbol: String, notional: Double): Double {
        tierFor(riskTiers(symbol), notional)?.let { return it.mmr.coerceIn(0.0, 0.5) }
        return TpSlPlanner.DEFAULT_MMR
    }

    /** Formats qty to the symbol's base precision, floored, never below minTradeVolume. */
    fun formatQty(symbol: String, raw: Double): String? = formatQty(symbol, raw, pairInfo(symbol))

    internal fun formatQty(symbol: String, raw: Double, info: PairInfo?): String? {
        if (info == null) return fmt6(raw)
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

    /**
     * Highest leverage the slider may offer. Bitunix does not publish a single
     * account-wide maximum in the docs — the authority is the per-symbol
     * maxLeverage from trading_pairs (verified live: BTCUSDT/ETHUSDT = 200x,
     * most other contracts 20-125x). Each order is clamped to its own symbol.
     */
    fun leverageCeiling(): Int = MAX_LEVERAGE

    /** Effective leverage, clamped to the symbol's max. */
    fun effectiveLeverage(symbol: String): Int = effectiveLeverage(leverage, pairInfo(symbol)?.maxLeverage)

    internal fun effectiveLeverage(base: Int, max: Int?): Int = minOf(base, max ?: base)

    /** Ranks the scanner universe by quote volume and returns the top symbols. */
    fun rankUniverse(top: Int = 10, tickersJson: org.json.JSONArray? = null): List<String> {
        if (universe.isEmpty()) return emptyList()
        val wanted = universe.map { it.uppercase() }.toSet()
        return try {
            // The tickers endpoint lists EVERY symbol on the exchange — keep only the
            // universe's symbols, ranked by 24h quote volume. Symbols without ticker
            // data keep their universe order behind the ranked ones.
            val arr = tickersJson ?: rest.tickers()
            val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("symbol").uppercase() in wanted }
                .sortedByDescending { it.optString("quoteVol").toDoubleOrNull() ?: 0.0 }
            val ranked = rows.map { it.getString("symbol") }
            (ranked + universe).distinct().take(top)
        } catch (_: Exception) {
            universe.take(top)
        }
    }

    /**
     * Pure: build the trading universe from the exchange's ticker list.
     *
     * The liquidity floor (min 24h quote volume) comes first, then the ranking
     * mode (see [rankUniverse]). When the floor leaves fewer than ten pairs the
     * most liquid ones are used instead — an empty universe stops the scanner
     * completely, and a thin book is still better than nothing.
     */
    internal fun autoUniverseSymbols(
        tickers: org.json.JSONArray,
        enabled: Boolean,
        minVolumeUsd: Double,
        rankBy: String,
        top: Int,
        tradable: Set<String>? = null,
    ): List<String> {
        if (!enabled || tickers.length() == 0 || top <= 0) return emptyList()
        val candidates = buildCandidates(tickers, tradable)
        if (candidates.isEmpty()) return emptyList()
        var pool = candidates.filter { it.quoteVol >= minVolumeUsd }
        if (pool.size < 10) {
            // The floor is too high for today's market — fall back to the most
            // liquid names instead of scanning nothing.
            pool = candidates.sortedByDescending { it.quoteVol }.take(maxOf(10, top))
        }
        var ranked = rankUniverse(pool, rankBy)
        if (ranked.isEmpty() && (rankBy.equals("gainers", true) || rankBy.equals("losers", true))) {
            // Nothing is up (or down) today — keep the scanner working.
            ranked = rankUniverse(pool, "volume")
        }
        return ranked.map { it.symbol }.distinct().take(top)
    }

    /** Symbols the exchange lists as OPEN and API-traded (empty when unknown). */
    private fun tradableSymbols(): Set<String> = try {
        val arr = rest.tradingPairs()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val symbol = o.optString("symbol", "").trim().uppercase()
            if (symbol.isBlank()) return@mapNotNull null
            val status = o.optString("symbolStatus", o.optString("status", "OPEN"))
            val apiOk = if (o.has("isApiSupported")) o.optBoolean("isApiSupported", true) else true
            val quote = o.optString("quote", "USDT")
            if (status.equals("OPEN", true) && apiOk && quote.equals("USDT", true)) symbol else null
        }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** Refreshes the universe from the live ticker list when auto-universe is on. */
    internal fun refreshAutoUniverseIfStale(maxAgeMs: Long = 60_000): String {
        if (!autoUniverse) return ""
        val now = System.currentTimeMillis()
        if (now - autoCacheAt <= maxAgeMs && universe.size >= scanTop) return ""
        val picked = try {
            val tradable = tradableSymbols()
            autoUniverseSymbols(
                rest.tickers(), true, autoMinVolumeUsd, autoRankBy, scanTop,
                tradable = tradable.ifEmpty { null },
            )
        } catch (e: Exception) {
            "Auto-universe refresh failed: ${e.message ?: e.javaClass.simpleName}".let { return it }
        }
        if (picked.isEmpty()) {
            autoCacheAt = now
            return "Auto-universe: no symbols passed the min ${autoMinVolumeUsd.toLong()} USDT volume filter."
        }
        universe.clear()
        universe.addAll(picked)
        signalCacheAt = 0
        autoCacheAt = now
        configRev++
        return "Auto-universe: ${picked.size} symbols → ${picked.joinToString(", ")}"
    }

    /** One-off manual refresh of the auto universe. */
    fun refreshAutoUniverse(): String = refreshAutoUniverseIfStale(0)

    /**
     * Evaluates the universe and returns one explainable row per symbol.
     * Cached for [maxAgeMs] because each row costs two kline requests.
     */
    fun signalBoard(maxAgeMs: Long = 60_000): List<SignalRow> {
        val now = System.currentTimeMillis()
        synchronized(signalLock) {
            if (signalCache.isNotEmpty() && now - signalCacheAt <= maxAgeMs) return signalCache
        }
        val symbols = runCatching {
            // Auto-universe: refresh the symbol list from the live ticker feed
            // before evaluating, so each scan sees today's top gainers/volume.
            if (autoUniverse) refreshAutoUniverseIfStale()
            if (scanOn) rankUniverse(scanTop) else universe.toList()
        }.getOrElse { universe.toList() }
        val rows = symbols.mapNotNull { sym ->
            runCatching {
                val byTf = timeframes.associateWith { candles(sym, it) }
                val perTf = byTf.mapValues { (tf, cs) -> SignalAggregator.evaluateTimeframe(tf, cs) }
                val price = byTf[timeframes.first()]?.lastOrNull()?.close ?: 0.0
                SignalAggregator.evaluate(sym, price, perTf, gates())
            }.getOrNull()
        }
        if (rows.isNotEmpty()) {
            synchronized(signalLock) {
                signalCache = rows
                signalCacheAt = now
            }
        }
        return rows
    }

    /**
     * The cached board, never a fresh evaluation.
     *
     * The chat reads this: a fresh sweep costs ~30 REST calls, and the chat
     * must not wait for that before it can answer.
     */
    fun signalBoardCached(): List<SignalRow> = synchronized(signalLock) { signalCache }

    /** Test hook: seeds the signal cache without network. */
    internal fun seedSignalCache(rows: List<SignalRow>) {
        synchronized(signalLock) {
            signalCache = rows
            signalCacheAt = System.currentTimeMillis()
        }
    }

    /** Full signal explanation for chat and the periodic report. */
    fun signalsText(): String = try {
        val rows = signalBoard()
        if (rows.isEmpty()) "No signals: ${emptyBoardReason()}"
        else formatSignalBoard(rows, gates())
    } catch (e: Exception) {
        "Signals unavailable: ${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * Why the board is empty. A bare "no symbols evaluated" looks like a broken
     * app, so the board names the thing the user has to change.
     */
    fun emptyBoardReason(): String = when {
        universe.isEmpty() && autoUniverse ->
            "auto-universe has not picked anything yet — run: refresh auto (needs internet)"
        universe.isEmpty() ->
            "the universe is empty — set universe BTCUSDT,ETHUSDT or let the app pick: set auto universe on"
        !scanOn ->
            "the scanner is off — scan on (universe: ${universe.size} symbols)"
        else ->
            "klines failed for all ${universe.size} symbols — check they exist on Bitunix (set universe BTCUSDT,ETHUSDT)"
    }

    /** One-line summary for the chat context. */
    fun signalsBrief(): String = runCatching {
        // The CACHED board, never a fresh sweep: the chat assembles its
        // context before it can answer, and a fresh evaluation costs ~30
        // REST calls. The engine's own loop keeps the board fresh.
        signalBoardCached().take(4).joinToString("; ") { "${it.symbol} ${it.status}" }
    }.getOrElse { "" }

    /** Compact settings line for the chat context. */
    fun configLine(): String =
        "${if (live) "LIVE" else "PAPER"} · lev ${leverage}x · risk ${(riskFraction * 100).toInt()}% · " +
            "${timeframes.joinToString("+")} · universe ${universe.size} · " +
            "gates $minAgreeing/${minTfConfidence.toInt()}%/${minConfidence.toInt()}%" +
                " · tf agree ${minSideTfs.coerceAtMost(timeframes.size)}/${timeframes.size}"

    /**
     * The account balance the engine reasons about.
     *
     * PAPER: the simulated account ([PaperBroker.balance]).
     * LIVE:  the exchange balance — the WS push when the feed is up, else the
     *        last REST refresh. While the exchange has not answered yet it is
     *        0, NOT the paper balance: sizing on a fake number would place
     *        real orders with made-up size.
     */
    fun currentBalance(): Double = if (live) liveBalanceSnapshot() ?: 0.0 else broker.balance

    /** Exchange balance as display text. Never falls back to the paper account. */
    fun liveBalanceText(): String {
        val value = liveBalanceSnapshot()
        return if (value == null) "unavailable (exchange)" else "${fmt2(value)} USDT (exchange)"
    }

    /** Balance line whose source is always explicit: paper or exchange. */
    fun balanceText(): String = if (live) liveBalanceText() else "${fmt2(broker.balance)} USDT (paper)"

    /**
     * One-line account summary for the UI. In LIVE it reports exchange data only;
     * the paper balance and paper realized PnL are hidden by design.
     *
     * A near-empty Futures wallet gets the reason spelled out: money sitting in
     * the Funding wallet does not pay for futures orders, which is the single
     * most common reason a live entry fails with "insufficient balance".
     */
    fun accountLine(): String = if (live) {
        val open = feedPositions()
        val positions = if (open.isEmpty()) "— (feed)" else "${open.size} on the feed"
        val bal = liveBalanceSnapshot()
        val hint = if (bal != null && bal < 1.0) {
            " · Futures wallet is empty — funds in the Funding wallet do not pay for futures orders (transfer them, or trade paper: set live off)"
        } else {
            ""
        }
        "Balance ${liveBalanceText()} · open $positions$hint"
    } else {
        "Balance ${fmt2(broker.balance)} USDT (paper) · realized ${fmt(broker.realizedPnl)} · open ${broker.positions.size}"
    }

    /**
     * Risk-manager gate evaluated before EVERY new entry. Returns the blocking
     * reason (every caller surfaces it in chat) or null when a new entry is allowed.
     * Order: guard interval → max open positions (paper).
     */
    internal fun riskBlock(): String? {
        if (guardIntervalSec > 0 && lastEntryAt > 0) {
            val leftMs = guardIntervalSec * 1000 - (System.currentTimeMillis() - lastEntryAt)
            if (leftMs > 0) {
                return "Risk manager: guard interval — next entry allowed in ${fmt0(leftMs / 1000.0)}s"
            }
        }
        if (!live && maxOpenPositions > 0 && broker.positions.size >= maxOpenPositions) {
            return "Risk manager: max open positions ($maxOpenPositions) reached — close one or raise the cap"
        }
        return null
    }

    /** Records a successful entry: arms the guard interval. */
    internal fun noteEntry() {
        lastEntryAt = System.currentTimeMillis()
    }

    fun settingsTradeText(): String = buildString {
        appendLine(
            "Trader settings — ${if (live) "LIVE (real orders)" else "PAPER (simulated)"} · " +
                "engine ${if (engineOn) "on" else "off"} · trading ${if (trading) "on" else "off"} · " +
                "loop ${if (isLoopRunning) "running" else "stopped"}",
        )
        appendLine("  universe (${universe.size}, scanner ${if (scanOn) "on" else "off"}, top $scanTop): ${universe.joinToString(", ")}" +
            (if (autoUniverse) " · AUTO: $autoRankBy, min ${autoMinVolumeUsd.toLong()} USDT 24h vol" else ""))
        appendLine("  timeframes: ${timeframes.joinToString(", ")}")
        appendLine("  gates: >=$minAgreeing strategies per timeframe · >=${minTfConfidence.toInt()}% per timeframe · >=${minConfidence.toInt()}% combined · ${minSideTfs.coerceAtMost(timeframes.size)} of ${timeframes.size} timeframes must share the side")
        appendLine("  leverage ${leverage}x · margin per trade ${(riskFraction * 100).toInt()}% · report ${if (reportOn) "on" else "off"} every $reportIntervalMin min")
        appendLine("  exits: breakeven ${offable(risk.breakevenRoiPct)} ROI → SL to entry · trailing trigger ${offable(risk.trailingTriggerRoiPct)} ROI, ${fmt0(risk.trailDistanceRoiPct)}% behind best · initial SL ${fmt0(risk.stopFractionOfLiqPct)}% toward liq · liq guard ${offable(risk.liqGuardRoiPct)} ROI (closes before the exchange can liquidate)")
        appendLine("  risk manager: max open positions $maxOpenPositions · liq guard ${offable(risk.liqGuardRoiPct)} ROI · liq buffer ${offable(liqBufferPct)} · guard interval ${if (guardIntervalSec > 0) "${guardIntervalSec}s" else "off"}")
        appendLine("  feed: ${if (liveFeedOn) "connected" else "off"}")
        append("  change with: set universe <A,B,C> · set timeframes <15m,1h> · set scan top <n> · " +
            "set min agree <n> · set min tf <pct> · set min conf <pct> · set tf agree <n> · set leverage <n> · set risk <pct> · " +
            "set breakeven <pct> · set trail trigger <pct> · set trail distance <pct> · set stop frac <pct> · " +
            "set tp r <n> (TP = n× the stop distance) · " +
            "set max positions <n> · set liq guard <pct> · set liq buffer <pct> · set guard <sec>")
    }.trim()

    /** One scan cycle: analyse each symbol and open a paper trade when the signal passes. */
    fun scanOnce(): List<String> {
        val out = mutableListOf<String>()
        // One balance check for the whole scan. An empty futures wallet cannot
        // fund anything, so the scan stops here instead of sending one order
        // per symbol per tick — every one of them rejected by the exchange.
        if (live) {
            val available = runCatching { rest.availableUsdt() }.getOrNull()
            if (available != null && available < MIN_FUTURES_BALANCE_USDT) {
                return listOf("SKIP scan: ${emptyFuturesWalletNote(available)}")
            }
        }
        val symbols = runCatching {
            if (scanOn && autoUniverse) refreshAutoUniverseIfStale()
            if (scanOn) rankUniverse(scanTop) else universe.toList()
        }.getOrElse { universe.toList() }
        // The open positions are ONE account-wide query for the whole scan.
        val openPositions = if (live) runCatching { rest.pendingPositions() }.getOrNull() else null
        for (sym in symbols) {
            // Risk manager: guard interval → max open positions.
            riskBlock()?.let { out.add(it); break }
            // One kline fetch per timeframe; the price reuses the 15m series
            // instead of asking the exchange for the very same candles twice.
            val byTf = timeframes.associateWith { candles(sym, it) }
            val perTf = byTf.mapValues { (_, series) -> SignalAggregator.timeframe(series) }
            val sig = SignalAggregator.combine(perTf, gates()) ?: continue
            val price = byTf[PRICE_TIMEFRAME]?.lastOrNull()?.close
                ?: candles(sym, PRICE_TIMEFRAME).last().close
            if (live) {
                out.add(openLive(sym, sig.dir, price, sig.confidence, openPositions) ?: continue)
            } else {
                if (broker.positions.containsKey(sym)) continue
                val (lev, levNote) = entryLeverage(sym)
                broker.open(sym, sig.dir, price, lev, risk)?.let {
                    noteEntry()
                    out.add(
                        "OPEN ${if (sig.dir > 0) "LONG" else "SHORT"} $sym @ ${fmt(price)} conf ${fmt0(sig.confidence)}" +
                            (levNote?.let { " · $it" } ?: ""),
                    )
                }
            }
        }
        return out
    }

    /** Places a real market order with TP/SL attached. Returns a status line, or null to skip. */
    private fun openLive(
        symbol: String,
        dir: Int,
        price: Double,
        confidence: Double,
        openPositions: JSONArray? = null,
    ): String? = placeLive(symbol, dir, price, riskFraction, confidence, openPositions)

    /**
     * One-tap manual entry (paper or live).
     * [sizeFraction] is the share of the account margin committed (0.10 = 10%).
     */
    fun manualEntry(symbol: String, dir: Int, sizeFraction: Double): String {
        riskBlock()?.let { return it }
        val sym = symbol.trim().uppercase().ifEmpty { "BTCUSDT" }
        if (!live) {
            val px = symbolPrice(sym)
            if (px <= 0) return "No price for $sym."
            if (broker.positions.containsKey(sym)) return "SKIP $sym: already an open paper position."
            val (lev, levNote) = entryLeverage(sym)
            val pos = broker.openSized(sym, dir, px, lev, sizeFraction, risk, maxOpenPositions)
                ?: return "SKIP $sym: could not open."
            noteEntry()
            return "PAPER OPEN ${if (dir > 0) "LONG" else "SHORT"} $sym qty ${fmt(pos.qty)} @ ${fmt(px)} " +
                "stop ${fmt(pos.stop)} (paper)" + (levNote?.let { " · $it" } ?: "")
        }
        val px = symbolPrice(sym)
        if (px <= 0) return "No price for $sym."
        return placeLive(sym, dir, px, sizeFraction, null)
    }

    /** Closes every open position (paper: simulated, live: market close-all). */
    fun closeAll(): String {
        if (live) {
            return try {
                rest.closeAllPositions()
                "Close-all-positions sent to the exchange."
            } catch (e: Exception) {
                "Close all failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val syms = broker.positions.keys.toList()
        if (syms.isEmpty()) return "No open positions."
        var closed = 0
        for (s in syms) {
            val px = symbolPrice(s)
            if (px > 0 && broker.close(s, px) != null) closed++
        }
        return "Closed $closed paper position(s)."
    }

    /** Cancels every open order (live only). */
    fun cancelAllOrders(): String = if (!live) {
        "Paper mode: no exchange orders to cancel."
    } else {
        try {
            rest.cancelAllOrders()
            "Cancel-all-orders sent to the exchange."
        } catch (e: Exception) {
            "Cancel failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Last price for a symbol (exchange first, candles as fallback). */
    fun symbolPrice(symbol: String): Double {
        wsLivePrice(symbol)?.let { return it }
        val live = runCatching { rest.lastPrice(symbol) }.getOrNull() ?: 0.0
        if (live > 0) return live
        return candles(symbol, PRICE_TIMEFRAME).lastOrNull()?.close ?: 0.0
    }

    /** How the market and the trader's book feel right now (cached for 60s). */
    fun marketMood(): MarketMood {
        val now = System.currentTimeMillis()
        val cached = moodCache
        if (cached != null && now - cached.first < 60_000) return cached.second
        val sym = universe.firstOrNull() ?: "BTCUSDT"
        val series = runCatching { candles(sym, PRICE_TIMEFRAME) }.getOrDefault(emptyList())
        val funding = runCatching {
            rest.fundingRate(sym).optString("fundingRate").toDoubleOrNull()
        }.getOrNull()
        val mood = MarketMoodCalc.assess(series, funding, unrealizedPct())
        moodCache = now to mood
        return mood
    }

    private var moodCache: Pair<Long, MarketMood>? = null

    /** Unrealized P&L as a fraction of the margin committed, or null when flat. */
    private fun unrealizedPct(): Double? {
        // The paper book is not the live book; mixing them would mislabel the
        // market mood in LIVE mode.
        if (live) return null
        if (broker.positions.isEmpty()) return null
        val prices = broker.positions.keys.associateWith { symbolPrice(it) }
        val margin = broker.balance * riskFraction * broker.positions.size
        if (margin <= 0) return null
        return broker.unrealized(prices) / margin
    }

    /** Shared live order path used by the scanner and the one-tap trade button. */
    private fun placeLive(
        symbol: String,
        dir: Int,
        price: Double,
        marginFraction: Double,
        confidence: Double?,
        openPositions: JSONArray? = null,
    ): String {
        // Refresh the exchange balance FIRST: order sizing must use one
        // authoritative number, never the paper account.
        val available = rest.availableUsdt()
        exchangeBalance.set(available)
        exchangeBalanceAt.set(System.currentTimeMillis())
        riskBlock()?.let { return it }
        // An empty futures wallet cannot fund anything. Without this the loop
        // kept trying, once per symbol and once per tick, and the exchange
        // rejected every attempt — an error line every cycle for a condition
        // the user can fix in one tap. Say it once, clearly.
        if (available < MIN_FUTURES_BALANCE_USDT) {
            return "SKIP $symbol: ${emptyFuturesWalletNote(available)}"
        }
        // The open positions are ONE account-wide query, not one per symbol:
        // the scan asks for it once and hands it down. Asking again for every
        // symbol doubled the request rate of every tick.
        val livePositions = openPositions ?: rest.pendingPositions()
        for (i in 0 until livePositions.length()) {
            if (livePositions.getJSONObject(i).optString("symbol") == symbol) {
                return "SKIP $symbol: live position already open."
            }
        }
        val (lev, levNote) = entryLeverage(symbol)
        val levLine = levNote?.let { " · $it" } ?: ""
        // The maintenance margin rate of the tier this entry lands in — the
        // official liquidation price is Entry × (1 − 1/Leverage + MMR), so the
        // MMR decides how much room the position really has.
        val mmr = mmrFor(symbol, available * marginFraction * lev)
        // Liquidation-aware stop: refuse the trade when the configured stop
        // fraction would put the stop at or beyond the liquidation price. The
        // stop must stay inside liquidation with a safety margin; clamping it
        // silently would hide the risk, so the trade is refused instead.
        if (!TpSlPlanner.stopFits(price, dir, lev, risk.stopFractionOfLiqPct / 100.0, mmr)) {
            return "SKIP $symbol: stop would hit liquidation first at ${risk.stopFractionOfLiqPct.toInt()}% " +
                "of the liquidation distance (${lev}x leverage, MMR ${fmt1(mmr * 100)}%). " +
                "Lower stop frac or lower leverage.$levLine"
        }
        val openCount = runCatching { livePositions.length() }.getOrDefault(0)
        // Margin sizing: the configured share, capped by what the free position
        // slots can carry, minus the fee/margin buffer (committing 100% of the
        // balance makes Bitunix answer 20003 "insufficient balance").
        var margin = MarginSizing.compute(available, marginFraction * 100.0, openCount, maxOpenPositions)
        // The exchange's minimum order size sets a floor: below it no order can
        // be placed at all, so raise the margin to that minimum when the
        // balance can carry it.
        val info = pairInfo(symbol)
        if (info != null && info.minTradeVolume > 0) {
            val minMargin = MarginSizing.minMarginForMinOrder(
                price, info.minTradeVolume, lev, info.basePrecision,
            )
            if (minMargin > margin) {
                if (minMargin > available) {
                    return "SKIP $symbol: one ${info.minTradeVolume} contract needs ${fmt2(minMargin)} USDT " +
                        "margin, the account has ${fmt2(available)} USDT."
                }
                margin = minMargin
            }
        }
        // TP/SL triggers are LAST_PRICE per project rule, so plan the levels from
        // the signal price; fall back to the mark price when the exchange publishes one.
        val mark = runCatching {
            rest.markPrice(symbol).optString("markPrice").toDoubleOrNull()
        }.getOrNull()
        val triggerBasis = mark ?: price
        val plan = TpSlPlanner.plan(triggerBasis, dir, lev, risk.stopFractionOfLiqPct / 100.0, mmr)
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
            // TP sits tpR × the stop distance away from entry (set tp r <n>).
            // Previously the FIRST ladder rung (1R) was attached — a very short TP.
            val rDist = kotlin.math.abs(triggerBasis - plan.stop).coerceAtLeast(1e-12)
            val tp = if (dir > 0) triggerBasis + tpR * rDist else triggerBasis - tpR * rDist
            val order = rest.placeMarketOrder(
                symbol = symbol, qty = qty, side = side, tradeSide = tradeSide,
                tpPrice = formatPrice(symbol, tp),
                slPrice = formatPrice(symbol, plan.stop),
                reduceOnly = false,
            )
            val orderId = order.optString("orderId")
            val status = waitForFill(orderId)
            val conf = if (confidence != null) " conf ${fmt0(confidence)}" else ""
            if (status == "FILLED" || status == "PART_FILLED") {
                noteEntry()
                return "LIVE OPEN ${if (dir > 0) "LONG" else "SHORT"} $symbol qty $qty @ ~${fmt(price)}$conf " +
                    "| order $orderId $status$levLine"
            }
            // Accepted but never filled (min qty, insufficient margin, TP/SL
            // rejected — the exchange cancels right after placement). The old code
            // armed the guard interval HERE, locking entries out for 5 minutes
            // after every dead order — the "no trades ever" spiral. Surface why.
            val line = "LIVE OPEN FAILED $symbol: order ${orderId.ifBlank { "?" }} ended $status"
            onEvent?.invoke(line)
            return line
        } catch (e: BitunixApiException) {
            val line = if (e.code == 20003) {
                // Bitunix 20003: the Futures wallet is empty. Money parked in the
                // Funding wallet does NOT count for futures orders — that is the
                // single most confusing error in this app, so it gets spelled out.
                val bal = runCatching { refreshLiveBalance() }.getOrNull()
                "LIVE ERROR $symbol: Bitunix error 20003 — insufficient balance" +
                    (if (bal != null) " (Futures wallet: ${fmt2(bal)} USDT)" else "") +
                    ". Funds in the Funding wallet do not pay for futures orders — transfer USDT to the " +
                    "Futures wallet, or trade on paper: set live off"
            } else {
                "LIVE ERROR $symbol: ${e.message}"
            }
            onEvent?.invoke(line)
            return line
        } catch (e: Exception) {
            // Retryable/network paths that outlive the API-internal retries.
            val line = "LIVE ERROR $symbol: ${e.message ?: e.javaClass.simpleName}"
            onEvent?.invoke(line)
            return line
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
        parseBalance(bal)?.let {
            exchangeBalance.set(it)
            exchangeBalanceAt.set(System.currentTimeMillis())
        }
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
            val available = parseBalance(bal)
            appendLine("Account: available ${available?.let { fmt2(it) } ?: "?"} USDT, margin ${bal.optString("margin")}")
            val feed = feedPositions()
            if (feed.isNotEmpty()) appendLine("Feed: live feed tracking ${feed.joinToString(", ")}")
            appendLine("Open positions: ${positions.length()}")
            for (i in 0 until positions.length()) {
                val p = positions.getJSONObject(i)
                appendLine(
                    "  ${p.optString("symbol")} ${p.optString("side")} qty ${posQty(p)} " +
                        "entry ${posEntry(p)} liq ${liqText(p.optString("liqPrice"))} " +
                        "upnl ${p.optString("unrealizedPNL")}",
                )
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
                    val qty = posQty(p).toDoubleOrNull() ?: continue
                    val entry = posEntry(p).toDoubleOrNull() ?: continue
                    val notional = qty * entry
                    val tier = runCatching { tierFor(parseRiskTiers(rest.positionTiers(sym)), notional) }.getOrNull()
                    if (tier != null) {
                        appendLine("  tier $sym: notional ${fmt(notional)} tier ${tier.level} " +
                            "max lev ${tier.maxLeverage}x mmr ${fmt1(tier.mmr * 100)}%")
                    }
                }
            }
            appendLine("Pending orders: ${orders.length()}")
        }
    } catch (e: Exception) {
        val cached = liveBalanceSnapshot()
        val suffix = if (cached != null) " Last exchange balance: ${fmt2(cached)} USDT." else ""
        "Live status failed: ${e.message ?: e.javaClass.simpleName} (check Bitunix keys)$suffix"
    }

    /** True when there is something to manage: a paper position or a live feed position. */
    private fun hasOpenPositions(): Boolean =
        if (live) wsPositions.isNotEmpty() else broker.positions.isNotEmpty()

    /** Manages open positions against current prices. */
    fun tick(): List<String> {        val events = mutableListOf<String>()
        // The paper book is only managed in PAPER mode. In LIVE it must not
        // produce "breakeven"/"close" events for positions that do not exist
        // on the exchange.
        if (!live) {
            for (sym in broker.positions.keys.toList()) {
                val price = candles(sym, PRICE_TIMEFRAME).lastOrNull()?.close ?: continue
                events.addAll(broker.manage(sym, price, risk))
            }
        } else events.addAll(manageLiveStops())
        return events
    }

    /**
     * Live mid-management: walks the exchange's open positions with the mark price
     * and moves the exchange SL when the risk manager's breakeven or trailing rules
     * fire (modify_position_tpsl). A stop is NEVER widened — trailing only fires
     * against a known baseline SL and only when it improves it.
     */
    private fun manageLiveStops(): List<String> {
        val events = mutableListOf<String>()
        val positions = try {
            rest.pendingPositions()
        } catch (_: Exception) {
            return events // transient feed/API hiccup — retry on the next tick
        }
        // Announce opens/closes by diffing the exchange snapshot against the last
        // tick. This is REST-authoritative: it works even when the WS pushes are
        // missing, which is what the user kept reporting ("asan elam nemikone").
        val nowSeen = HashMap<String, String>()
        for (i in 0 until positions.length()) {
            val p = positions.getJSONObject(i)
            val sym = p.optString("symbol")
            val side = p.optString("side").uppercase()
            val qty = posQty(p)
            if (sym.isNotBlank()) nowSeen[sym] = "$side|$qty"
        }
        for ((sym, fp) in nowSeen) {
            if (seenLive.put(sym, fp) == null) {
                val p = (0 until positions.length()).firstOrNull {
                    positions.getJSONObject(it).optString("symbol") == sym
                }?.let { positions.getJSONObject(it) }
                val entry = p?.let { posEntry(it).toDoubleOrNull() } ?: 0.0
                val side = fp.substringBefore('|')
                val qty = fp.substringAfter('|')
                events.add(
                    "POSITION OPENED $sym $side qty $qty" +
                        (if (entry > 0) " @ ${fmt(entry)}" else "")
                )
            }
        }
        // Collect the closed symbols first: the maps are read while they are
        // iterated, so removing inside the loop would be a concurrent mutation.
        val closedSymbols = seenLive.keys.filter { it !in nowSeen }
        for (sym in closedSymbols) {
            seenLive.remove(sym)
            liveBest.remove(sym); liveLastStop.remove(sym); liveBreakevenDone.remove(sym)
            liqGuardWarned.remove(sym)
            // Pull the net result from history so the user immediately sees the PnL.
            val net = runCatching {
                val h = rest.historyPositions(sym, limit = 1)
                if (h.length() > 0) {
                    val p = h.getJSONObject(0)
                    netPositionPnl(
                        p.optString("realizedPNL").toDoubleOrNull() ?: 0.0,
                        p.optString("fee").toDoubleOrNull() ?: 0.0,
                        p.optString("funding").toDoubleOrNull() ?: 0.0,
                    )
                } else null
            }.getOrNull()
            events.add(
                "POSITION CLOSED $sym" + (if (net != null) " — net ${fmt(net)} USDT" else "")
            )
        }
        val openSymbols = mutableSetOf<String>()
        for (i in 0 until positions.length()) {
            val p = positions.getJSONObject(i)
            val sym = p.optString("symbol")
            val positionId = p.optString("positionId")
            if (sym.isBlank() || positionId.isBlank()) continue
            openSymbols.add(sym)
            val entry = posEntry(p).toDoubleOrNull() ?: continue
            val dir = when (p.optString("side").uppercase()) {
                "LONG", "BUY" -> 1
                "SHORT", "SELL" -> -1
                else -> continue
            }
            val lev = p.optString("leverage").toIntOrNull()?.takeIf { it > 0 } ?: effectiveLeverage(sym)
            // This symbol's maintenance margin rate — the official liquidation
            // price is Entry × (1 − 1/Leverage + MMR).
            val mmr = mmrFor(sym, entry * lev)
            val mark = wsMarketData(sym)?.optDouble("mp")?.takeIf { it > 0 && !it.isNaN() }
                ?: runCatching { rest.markPrice(sym).optString("markPrice").toDoubleOrNull() }
                    .getOrNull()?.takeIf { it > 0 }
                ?: continue
            val prevBest = liveBest[sym]
            val best = if (dir > 0) maxOf(mark, prevBest ?: mark) else minOf(mark, prevBest ?: mark)
            liveBest[sym] = best
            val roi = TpSlPlanner.roi(entry, mark, dir, lev)
            // Liquidation guard — the backstop for the day the exchange SL is
            // missing, rejected or gapped through: warn once, then flash-close
            // at market BEFORE the exchange's liquidation engine can take the
            // position. With a healthy SL this never fires (the SL sits at
            // 30% of the liq distance by default).
            if (risk.liqGuardRoiPct > 0) {
                val roiPct = roi * 100.0
                val warned = liqGuardWarned[sym] == true
                val closeThreshold = -risk.liqGuardRoiPct
                val warnThreshold = -(risk.liqGuardRoiPct - 15.0)
                // The exchange's OWN liquidation price when it publishes one
                // (liqPrice ≤ 0 means "no liquidation price"); otherwise the
                // official formula with this symbol's MMR.
                val exchangeLiq = p.optString("liqPrice").toDoubleOrNull()?.takeIf { it > 0 }
                val liq = exchangeLiq ?: TpSlPlanner.liqPrice(entry, dir, lev, mmr)
                // Room left before liquidation, in % of the current price.
                val dist = if (mark > 0) dir * (mark - liq) / mark * 100.0 else 0.0
                if (roiPct <= closeThreshold) {
                    liqGuardWarned.remove(sym)
                    try {
                        rest.flashClose(positionId)
                        events.add(
                            "LIQ GUARD closed $sym @ ~${fmt(mark)} — ROI ${fmt1(roiPct)}%, " +
                                "~${fmt1(dist)}% from the liquidation price; closed before the exchange could.",
                        )
                    } catch (e: Exception) {
                        events.add(
                            "LIQ GUARD: closing $sym failed — ${e.message ?: e.javaClass.simpleName} " +
                                "(close it manually NOW: close position $sym)",
                        )
                    }
                    continue
                } else if (roiPct <= warnThreshold && !warned) {
                    liqGuardWarned[sym] = true
                    events.add(
                        "LIQ GUARD WARNING $sym: ROI ${fmt1(roiPct)}% — the exchange can " +
                            "liquidate ~${fmt1(dist)}% from here. Close it or add margin.",
                    )
                }
            }
            // Current SL baseline: what this engine last set, else the exchange's
            // pending SL.
            var curStop = liveLastStop[sym]
            if (curStop == null) {
                curStop = runCatching { findSlPrice(rest.pendingTpSlOrders(sym, positionId)) }.getOrNull()
                    ?: runCatching { findSlPrice(rest.pendingTpSlOrders(sym)) }.getOrNull()
            }
            // No exchange SL visible (never attached, rejected, or the query
            // failed): fall back to the engine's OWN initial stop for this
            // position. Without a baseline the trailing rule could never fire
            // — that is exactly the "trailing did nothing" report.
            if (curStop == null) {
                curStop = TpSlPlanner.plan(entry, dir, lev, risk.stopFractionOfLiqPct / 100.0, mmr).stop
            }
            liveLastStop[sym] = curStop
            // Breakeven: SL to entry once ROI passes the level. The "done" flag
            // is set only when the stop really moved — a rejected or failed move
            // is retried on the next tick instead of being given up on.
            val bestRoi = TpSlPlanner.roi(entry, best, dir, lev)
            val be = breakevenStopFor(dir, entry, best, lev, curStop, risk)
            if (be != null) {
                if (applyLiveSl(sym, positionId, be, "Breakeven", bestRoi, events)) {
                    liveBreakevenDone[sym] = true
                }
                continue
            }
            if (liveBreakevenDone[sym] != true && risk.breakevenRoiPct > 0 &&
                bestRoi >= risk.breakevenRoiPct / 100.0
            ) {
                // The stop in place is already tighter than entry: there is
                // nothing to move, and the rule must not keep retrying.
                liveBreakevenDone[sym] = true
                continue
            }
            // Trailing: fires on the BEST price reached (not the current price),
            // so a pullback does not stop the stop from following the position.
            trailingStopFor(dir, entry, best, lev, curStop, risk)?.let { trail ->
                applyLiveSl(sym, positionId, trail, "Trailing", bestRoi, events)
            }
        }
        // Forget state for positions that closed (collect first, then remove —
        // the map is being iterated).
        val staleSymbols = liveBest.keys.filter { it !in openSymbols }
        for (sym in staleSymbols) {
            liveBest.remove(sym)
            liveLastStop.remove(sym)
            liveBreakevenDone.remove(sym)
            liqGuardWarned.remove(sym)
        }
        return events
    }

    /** A candidate stop only improves when it moves toward/beyond the current stop. */
    private fun improves(dir: Int, candidate: Double, current: Double): Boolean =
        improvesStop(dir, candidate, current)

    /**
     * Sends the new SL to the exchange and records it; failures surface as events.
     * Returns true when the exchange accepted the move — the breakeven rule only
     * marks itself done on success, so a rejected move is retried next tick.
     */
    private fun applyLiveSl(
        sym: String, positionId: String, sl: Double, reason: String, roi: Double,
        events: MutableList<String>,
    ): Boolean {
        return try {
            rest.modifyPositionTpSl(sym, positionId, tpPrice = null, slPrice = formatPrice(sym, sl))
            liveLastStop[sym] = sl
            events.add("SL moved to ${fmt(sl)} — $reason $sym (ROI ${fmt1(roi * 100)}%)")
            true
        } catch (e: Exception) {
            events.add("Risk manager: SL move on $sym failed — ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** The first usable slPrice in a TP/SL order list (null when there is none). */
    internal fun findSlPrice(orders: org.json.JSONArray): Double? {
        for (j in 0 until orders.length()) {
            val v = orders.getJSONObject(j).optString("slPrice").toDoubleOrNull()
            if (v != null && v > 0) return v
        }
        return null
    }

    fun status(): String = buildString {
        appendLine("Engine: ${if (engineOn) "on" else "off"}, trading: ${if (trading) "on" else "off"}, scan: ${if (scanOn) "on" else "off"}")
        appendLine("Mode: ${if (live) "LIVE" else "PAPER"}, leverage $leverage${if (live) "x (clamped per symbol)" else "x"}, margin $marginMode, position $positionMode")
        appendLine("Universe: ${universe.size} symbols${if (autoUniverse) " · AUTO $autoRankBy · min ${autoMinVolumeUsd.toLong()} USDT 24h vol · refreshed each scan" else ""}: ${universe.joinToString(", ").take(120)}")
        if (live) {
            appendLine("Balance: ${balanceText()}")
            appendLine(
                "Open positions (exchange): " +
                    if (liveFeedOn) "${feedPositions().size} on the feed" else "feed off — use 'positions'",
            )
        } else {
            appendLine("Balance: ${fmt(broker.balance)} USDT, open positions: ${broker.positions.size}")
        }
    }

    fun pnl(): String =
        "PAPER (demo) — Realized PnL: ${fmt(broker.realizedPnl)} USDT, balance ${fmt(broker.balance)} USDT." +
            " This is the simulated account; your real Bitunix balance is separate."

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
        val price = candles(symbol, PRICE_TIMEFRAME).lastOrNull()?.close ?: return "No price for $symbol."
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
        val fmtTime = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val body = rows.sortedByDescending { kotlin.math.abs(it.second) }.take(top)
            .joinToString("\n") { (sym, rate, next) ->
                // Locale.US keeps the decimal separator a dot on every device.
                String.format(
                    Locale.US,
                    "%-10s %s %.4f%%  %s  next %s",
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
                // The risk manager (breakeven, trailing, the liquidation guard) must
                // keep working on OPEN positions even when new entries are switched
                // off: "stop trading" stops entries, it must not abandon the
                // positions that are already running.
                if (!trading && !engineOn && !hasOpenPositions()) return@scheduleWithFixedDelay
                val events = tick() + if (trading) scanOnce() else emptyList()
                // A successful poll means the exchange is reachable again, so a
                // stale DNS failure must not keep showing in /diagnose. The
                // error state is NOT wiped outright though: a flaky line that
                // fails once in a while must not look "new" every time and
                // print the same line every cycle.
                EngineRuntimeError.clearIfStale()
                events.forEach(onEvent)
            } catch (e: Throwable) {
                // Exchange/network failures are engine errors, never provider
                // errors. Writing them to AgentRuntimeError made /diagnose blame
                // the LLM and suggest switching models during a DNS outage.
                // Throwable, not Exception: an Error that escapes this thread
                // would kill the app outright.
                val friendly = NetworkErrors.describe(NetworkErrors.rawMessage(e), intervalSec)
                if (EngineRuntimeError.record(friendly)) {
                    onEvent("Loop error: $friendly")
                }
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS)
        startReport()
    }

    private fun startReport() {
        if (reportTask != null) return
        reportTask = loopExecutor.scheduleWithFixedDelay({
            try {
                if (!reportOn || !engineOn) return@scheduleWithFixedDelay
                val signals = runCatching {
                    val rows = signalBoard(maxAgeMs = 0).take(3)
                    formatSignalBoard(rows, gates(), emptyBoardReason())
                }.getOrElse { "signals unavailable" }
                onEvent?.invoke(
                    "Periodic report: ${accountLine()}\n$signals"
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
    /**
     * People type commands the way they talk: "min volume 24h usd 10000",
     * "leverage 20", "auto universe on". The old code answered "Usage:" to all
     * of them, which looks like the app ignoring the chat, so the missing
     * "set " prefix is added here before the command is matched.
     */
    private fun normalizeCommand(t: String): String = when {
        t.startsWith("min volume") -> "set $t"
        t.startsWith("auto universe") -> "set $t"
        t.startsWith("auto rank") -> "set $t"
        t.startsWith("auto on") -> "set auto universe on"
        t.startsWith("auto off") -> "set auto universe off"
        t.startsWith("scan top") -> "set $t"
        t.startsWith("leverage ") -> "set $t"
        t.startsWith("risk ") -> "set $t"
        t.startsWith("universe ") -> "set $t"
        else -> t
    }

    fun handle(text: String): String {
        val t = normalizeCommand(text.trim().lowercase())
        return when {
            t.startsWith("engine on") -> {
                engineOn = true
                "Engine on (${if (live) "LIVE" else "paper"} mode)."
            }
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
            t.startsWith("set live") -> {
                val v = t.removePrefix("set live").trim()
                when {
                    v.startsWith("off") || v == "paper" -> {
                        live = false
                        configRev++
                        "Paper mode — orders are simulated, no real money. (Live trades need: set live on)"
                    }
                    v.startsWith("on") || v == "live" -> {
                        live = true
                        configRev++
                        "LIVE mode — real orders, real money. The Futures wallet must hold USDT."
                    }
                    else -> "Usage: set live on|off"
                }
            }
            t.startsWith("paper mode") -> { live = false; configRev++; "Paper mode — orders are simulated." }
            t.startsWith("live mode") -> { live = true; configRev++; "LIVE mode — real orders, real money." }
            t.startsWith("status") -> status()
            t.startsWith("balance") -> if (live) {
                val balance = refreshLiveBalance()
                if (balance == null) {
                    "Exchange balance unavailable — check Bitunix keys/network. (Paper balance is never used in LIVE.)"
                } else if (balance < 1.0) {
                    "Account (exchange): available ${fmt2(balance)} USDT\n" +
                        "The Futures wallet is empty. Money in the Funding wallet does NOT pay for futures " +
                        "orders — transfer USDT to the Futures wallet, or trade paper: set live off"
                } else "Account (exchange): available ${fmt2(balance)} USDT"
            } else pnl()
            t.startsWith("positions history") -> if (live) {
                try {
                    val arr = rest.historyPositions(limit = 10)
                    if (arr.length() == 0) {
                        val open = runCatching { rest.pendingPositions().length() }.getOrDefault(-1)
                        "No closed positions yet — history fills only after a trade opens AND closes." +
                            (if (open > 0) " Open now: $open." else if (open == 0) " Nothing open right now." else "")
                    }
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
            t.startsWith("trades_history") -> if (live) {
                "LIVE mode: use 'history orders' or 'positions history' for exchange fills. The paper trade log is not shown in LIVE."
            } else tradesHistory()
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
            t.startsWith("tpsl_history") -> "TP/SL plan: initial stop ${fmt0(risk.stopFractionOfLiqPct)}% toward liquidation, " +
                "ladder 1R..4R each 25%, breakeven ${if (risk.breakevenRoiPct > 0) "at ${fmt0(risk.breakevenRoiPct)}% ROI" else "off"}, " +
                "trail ${if (risk.trailingTriggerRoiPct > 0) "at ${fmt0(risk.trailingTriggerRoiPct)}% ROI, ${fmt0(risk.trailDistanceRoiPct)}% behind" else "off"}, " +
                "liq guard ${if (risk.liqGuardRoiPct > 0) "closes at -${fmt0(risk.liqGuardRoiPct)}% ROI" else "off"}. " +
                "New entries keep liquidation ≥${fmt0(liqBufferPct)}% away (liq buffer ${if (liqBufferPct > 0) "on" else "off"})."
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
                val fromWs = feedTpSl()
                if (liveFeedOn && fromWs.isNotEmpty()) {
                    "TP/SL (live WS, ${fromWs.size} events):\n" + fromWs.joinToString("\n") { o ->
                        "${o.optString("symbol")} ${o.optString("event")} ${o.optString("status")} " +
                            "tp ${o.optString("tpPrice")} sl ${o.optString("slPrice")} id ${o.optString("orderId")}"
                    }
                } else try {
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
            } else if (broker.positions.isEmpty()) "No open paper positions, so no TP/SL plan yet."
            else broker.positions.values.joinToString("\n") { p ->
                val ladder = p.plan.ladder.mapIndexed { i, (price, frac) ->
                    val state = if (i < p.nextRung) "hit" else "open"
                    "${i + 1}R ${fmt(price)} (${fmt0(frac * 100)}%, $state)"
                }.joinToString(", ")
                "${p.symbol} ${if (p.dir > 0) "LONG" else "SHORT"} entry ${fmt(p.entry)} " +
                    "stop ${fmt(p.stop)} liq ${fmt(p.plan.liquidation)}\n" +
                    "  TP ladder: $ladder\n" +
                    "  breakeven ${if (p.breakeven) "done" else "pending"}, " +
                    "paper TP/SL is simulated locally"
            }
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
            t.startsWith("pnl") -> if (live) {
                "LIVE mode: realized PnL is on the exchange — use 'positions history'. The paper account is not in use."
            } else pnl()
            t.startsWith("funding") -> try {
                fundingSummary(rest.fundingRateBatch())
            } catch (e: Exception) {
                "Funding failed: ${e.message}"
            }
            t.startsWith("report on") -> { reportOn = true; "Periodic reports on (every $reportIntervalMin min)." }
            t.startsWith("report off") -> { reportOn = false; "Periodic reports off." }
            t.startsWith("report interval") -> {
                val m = extractNumber(t.removePrefix("report interval"))?.toLong()
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
            t.startsWith("close all") -> closeAll()
            t.startsWith("manual entry") -> {
                val parts = t.removePrefix("manual entry").trim().split(Regex("\\s+"))
                val sym = parts.getOrElse(0) { "BTCUSDT" }.uppercase()
                val dir = if (parts.getOrElse(1) { "long" }.startsWith("s")) -1 else 1
                val pct = parts.getOrElse(2) { "10" }.toDoubleOrNull() ?: 10.0
                manualEntry(sym, dir, (pct / 100.0).coerceIn(0.01, 1.0))
            }
            t.startsWith("set leverage") -> {
                val v = extractNumber(t.removePrefix("set leverage"))?.toInt()
                if (v == null || v !in 1..MAX_LEVERAGE) "Usage: set leverage <1-$MAX_LEVERAGE>"
                else {
                    leverage = v
                    configRev++
                    "Leverage set to $v x." + if (liqBufferPct > 0.0) {
                        "\nNote: the liquidation buffer (liq buffer ${fmt0(liqBufferPct)}%) may cap new entries " +
                            "lower. The exact cap depends on the symbol's tier and its maintenance margin rate — " +
                            "the chat line on each entry says what it was clamped to (see: tiers)."
                    } else ""
                }
            }
            t.startsWith("set risk") -> {
                val v = extractNumber(t.removePrefix("set risk"))
                if (v == null || v !in 1.0..100.0) "Usage: set risk <1-100> (percent of balance per trade)"
                else {
                    riskFraction = v / 100.0
                    configRev++
                    "Margin per trade set to ${v.toInt()}% of balance."
                }
            }
            t.startsWith("set breakeven") -> {
                val v = extractNumber(t.removePrefix("set breakeven"))
                if (v == null || v !in 0.0..1000.0) "Usage: set breakeven <pct> (0 = off)"
                else {
                    risk = risk.copy(breakevenRoiPct = v)
                    configRev++
                    "Breakeven ${if (v > 0) "at ${fmt0(v)}% ROI — SL moves to entry" else "off"}."
                }
            }
            t.startsWith("set trail trigger") -> {
                val v = extractNumber(t.removePrefix("set trail trigger"))
                if (v == null || v !in 0.0..1000.0) "Usage: set trail trigger <pct> (0 = off)"
                else {
                    risk = risk.copy(trailingTriggerRoiPct = v)
                    configRev++
                    "Trailing trigger ${if (v > 0) "at ${fmt0(v)}% ROI" else "off"}."
                }
            }
            t.startsWith("set trail distance") -> {
                val v = extractNumber(t.removePrefix("set trail distance"))
                if (v == null || v !in 0.1..1000.0) "Usage: set trail distance <pct> (ROI distance behind the best price)"
                else {
                    risk = risk.copy(trailDistanceRoiPct = v)
                    configRev++
                    "Trailing distance set to ${fmt0(v)}% ROI behind the best price."
                }
            }
            t.startsWith("set tp r") || t.startsWith("set tp ") -> {
                val v = extractNumber(t.removePrefix("set tp r").removePrefix("set tp").trim().removePrefix("r"))
                if (v == null || v < 0.5 || v > 20.0) "Usage: set tp r <0.5-20> (TP = n× the stop distance; 1 = at the SL, 3 = 3× farther)"
                else {
                    tpR = v
                    configRev++
                    val slPct = risk.stopFractionOfLiqPct
                    "TP set to ${v}× the stop distance (SL is ${fmt0(slPct)}% toward liquidation)."
                }
            }
            t.startsWith("set stop frac") -> {
                val v = extractNumber(t.removePrefix("set stop fraction").removePrefix("set stop frac"))
                if (v == null || v !in 1.0..95.0) "Usage: set stop frac <10-90> (percent of the way to liquidation)"
                else {
                    risk = risk.copy(stopFractionOfLiqPct = v)
                    configRev++
                    "Initial stop set to ${fmt0(v)}% of the way to liquidation."
                }
            }
            t.startsWith("set max positions") -> {
                val v = extractNumber(t.removePrefix("set max positions").removePrefix("set maxpos"))?.toInt()
                if (v == null || v !in 1..20) "Usage: set max positions <1-20>"
                else {
                    maxOpenPositions = v
                    configRev++
                    "Max open positions set to $v."
                }
            }
            t.startsWith("set liq guard") -> {
                val v = extractNumber(t.removePrefix("set liq guard"))
                if (v == null || v !in 0.0..100.0)
                    "Usage: set liq guard <pct> (0 = off) — close a position when its loss reaches this % of its margin, before the exchange can liquidate"
                else {
                    risk = risk.copy(liqGuardRoiPct = v)
                    configRev++
                    "Liquidation guard on: a position is closed at -${fmt0(v)}% ROI " +
                        "(warn at -${fmt0(v - 15.0)}%). Set 0 to turn off."
                }
            }
            t.startsWith("set liq buffer") -> {
                val v = extractNumber(t.removePrefix("set liq buffer"))
                if (v == null || v !in 0.0..100.0)
                    "Usage: set liq buffer <pct> (0 = off) — new entries keep the estimated liquidation at least this % from entry (caps the leverage)"
                else {
                    liqBufferPct = v
                    if (v > 0.0) {
                        "Liquidation buffer set to ${fmt0(v)}% — new entries may be capped lower. The exact cap " +
                            "depends on the symbol's tier and its maintenance margin rate, so it is reported on " +
                            "each entry (see: tiers)."
                    } else {
                        "Liquidation buffer off — your leverage is used as set, capped only by the symbol's own " +
                            "tier limit (see: tiers)."
                    }
                }
            }
            t.startsWith("set guard") -> {
                val v = extractNumber(t.removePrefix("set guard interval").removePrefix("set guard"))
                if (v == null || v < 0.0) "Usage: set guard <seconds> (0 = off)"
                else {
                    guardIntervalSec = v.toLong()
                    configRev++
                    "Guard interval ${if (v > 0) "set to ${v.toLong()}s between entries" else "off"}."
                }
            }
            t.startsWith("signals") -> signalsText()
            t.startsWith("set universe") || t.startsWith("set symbol") -> {
                // "set universe", "set symbols" and "set symbol" all mean the same.
                val raw = t.removePrefix("set universe").removePrefix("set symbols").removePrefix("set symbol")
                if (raw.trim().equals("auto", true)) {
                    // "AUTO" hands the picking back to the app.
                    autoUniverse = true
                    signalCacheAt = 0
                    configRev++
                    "Auto-universe ON — the app picks the symbols itself " +
                        "(top $scanTop by $autoRankBy, min ${autoMinVolumeUsd.toLong()} USDT 24h vol).\n" +
                        refreshAutoUniverse()
                } else {
                    val parsed = parseSymbolsCsv(raw)
                    if (parsed == null) "Usage: set universe BTCUSDT,ETHUSDT,SOLUSDT (or AUTO)"
                    else {
                        // Verify against the exchange: stock tickers (RANK, MOVERS) are
                        // not Bitunix contracts, and the scanner then has nothing to
                        // evaluate while the board just says "no symbols evaluated".
                        val known = runCatching {
                            val arr = rest.tickers()
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optJSONObject(i)?.optString("symbol")?.takeIf { it.isNotBlank() }
                            }.toSet()
                        }.getOrNull()
                        if (known.isNullOrEmpty()) {
                            universe.clear(); universe.addAll(parsed)
                            signalCacheAt = 0
                            configRev++
                            autoUniverse = false
                            "Universe set (${parsed.size}): ${parsed.joinToString(", ")}\n" +
                                "Auto-universe OFF (you set a fixed list) — turn it back on: set auto universe on\n" +
                                "(could not check Bitunix's symbol list — the list was empty or the network is down)"
                        } else {
                            val check = matchUniverse(parsed, known)
                            universe.clear(); universe.addAll(check.accepted)
                            signalCacheAt = 0
                            configRev++
                            autoUniverse = false
                            buildString {
                                append("Universe set (${check.accepted.size}): ${check.accepted.joinToString(", ")}")
                                if (check.unknown.isNotEmpty()) {
                                    append("\nNot listed on Bitunix, ignored: ${check.unknown.joinToString(", ")}")
                                    append("\nBitunix trades USDT contracts — try BTCUSDT, ETHUSDT, SOLUSDT,")
                                    append("\nor let the app pick them: set auto universe on")
                                }
                                append("\nAuto-universe OFF (you set a fixed list) — turn it back on: set auto universe on")
                            }.trim()
                        }
                    }
                }
            }
            t.startsWith("set timeframes") -> {
                val parsed = parseTimeframesCsv(t.removePrefix("set timeframes"))
                if (parsed == null) {
                    "Usage: set timeframes 15m,1h — valid: ${KLINE_INTERVALS.joinToString(",")} (max 5)"
                } else {
                    timeframes = parsed
                    signalCacheAt = 0
                    configRev++
                    "Timeframes set: ${parsed.joinToString(", ")}"
                }
            }
            t.startsWith("set scan top") -> {
                val v = extractNumber(t.removePrefix("set scan top"))?.toInt()
                if (v == null || v !in 3..50) "Usage: set scan top <3-50>"
                else {
                    scanTop = v
                    signalCacheAt = 0
                    configRev++
                    "Scanner picks the top $v symbols by volume."
                }
            }
            t.startsWith("auto gainers") || t.startsWith("auto volume") ||
                t.startsWith("auto losers") || t.startsWith("auto movers") -> {
                // One tap from the Control screen: auto-universe on, this ranking.
                val mode = t.removePrefix("auto ").trim()
                autoUniverse = true
                autoRankBy = mode
                scanOn = true
                signalCacheAt = 0
                configRev++
                "Auto-universe ON — ranks by $mode (top $scanTop, min ${autoMinVolumeUsd.toLong()} USDT 24h vol).\n" +
                    refreshAutoUniverse()
            }
            t.startsWith("set auto universe") -> {
                val v = t.removePrefix("set auto universe").trim().lowercase()
                val on = v in listOf("on", "true", "1", "yes")
                val off = v in listOf("off", "false", "0", "no")
                if (!on && !off) {
                    "Usage: set auto universe on|off"
                } else {
                    autoUniverse = on
                    signalCacheAt = 0
                    configRev++
                    val head = "Auto-universe ${if (autoUniverse) "ON" else "OFF"} — " +
                        (if (autoUniverse) "each scan refreshes the universe to the top $scanTop by $autoRankBy " +
                        "(min ${autoMinVolumeUsd.toLong()} USDT 24h vol)" else "fixed universe from set universe")
                    if (autoUniverse) "$head\n${refreshAutoUniverse()}" else head
                }
            }
            t.startsWith("set min volume") -> {
                val v = extractAmount(t.removePrefix("set min volume"))
                if (v == null || v < 0.0) {
                    "Usage: set min volume <usdt> — e.g. set min volume 5000000 (or 5m)"
                } else {
                    autoMinVolumeUsd = v
                    signalCacheAt = 0
                    configRev++
                    val head = "Min 24h volume for auto-universe: ${v.toLong()} USDT."
                    if (autoUniverse) "$head\n${refreshAutoUniverse()}" else head
                }
            }
            t.startsWith("set auto rank") -> {
                val v = t.removePrefix("set auto rank").trim().lowercase()
                if (v !in listOf("gainers", "losers", "movers", "volume")) {
                    "Usage: set auto rank gainers|losers|movers|volume"
                } else {
                    autoRankBy = v
                    signalCacheAt = 0
                    configRev++
                    val head = "Auto-universe ranks by $v." +
                        if (v == "gainers") " (only coins that are up today)" else
                        if (v == "losers") " (only coins that are down today)" else
                        if (v == "movers") " (biggest move either way)" else " (deepest books first)"
                    if (autoUniverse) "$head\n${refreshAutoUniverse()}" else head
                }
            }
            t.startsWith("refresh auto") -> {
                if (!autoUniverse) {
                    "Auto-universe is off. Turn it on with: set auto universe on"
                } else {
                    refreshAutoUniverse()
                }
            }
            t.startsWith("set min agree") -> {
                val v = extractNumber(t.removePrefix("set min agree"))?.toInt()
                if (v == null || v !in 1..37) "Usage: set min agree <1-37> (strategies required per timeframe)"
                else {
                    minAgreeing = v
                    signalCacheAt = 0
                    configRev++
                    "Gate set: $v strategies must agree per timeframe."
                }
            }
            t.startsWith("set min tf") -> {
                val v = extractNumber(t.removePrefix("set min tf"))
                if (v == null || v !in 1.0..100.0) "Usage: set min tf <1-100> (per-timeframe confidence gate)"
                else {
                    minTfConfidence = v
                    signalCacheAt = 0
                    configRev++
                    "Gate set: each timeframe needs ${v.toInt()}% confidence."
                }
            }
            t.startsWith("set min conf") -> {
                val v = extractNumber(t.removePrefix("set min conf"))
                if (v == null || v !in 1.0..100.0) "Usage: set min conf <1-100> (combined confidence gate)"
                else {
                    minConfidence = v
                    signalCacheAt = 0
                    configRev++
                    "Gate set: combined confidence must be ${v.toInt()}%."
                }
            }
            t.startsWith("set tf agree") -> {
                val v = extractNumber(t.removePrefix("set tf agree"))?.toInt()
                if (v == null || v !in 1..timeframes.size)
                    "Usage: set tf agree <1-${timeframes.size}> (how many timeframes must share one side)"
                else {
                    minSideTfs = v
                    signalCacheAt = 0
                    configRev++
                    "Gate set: $v of ${timeframes.size} timeframes must share one side."
                }
            }
            t.startsWith("settings trade") -> settingsTradeText()
            t.startsWith("scan once") -> {
                val out = scanOnce()
                if (out.isEmpty()) "Scan done: no qualifying signals."
                else out.joinToString("\n")
            }
            else -> "Not supported yet: $text"
        }
    }

    companion object {
        /**
         * The timeframe the engine takes its entry price and its mid-management
         * marks from (the default first timeframe of [SignalAggregator.TIMEFRAMES]).
         */
        val PRICE_TIMEFRAME: String = SignalAggregator.TIMEFRAMES.first()

        /**
         * Upper bound for the leverage slider and the `set leverage` command.
         * Bitunix publishes no account-wide maximum in the docs; the authority is
         * the per-symbol maxLeverage from trading_pairs, whose highest listing
         * (verified live, 2026-11) is 200x for BTCUSDT and ETHUSDT.
         */
        const val MAX_LEVERAGE = 200

        /** How long a symbol's maintenance margin rate is cached (10 min). */
        const val MMR_CACHE_MS = 10 * 60 * 1000L

        /**
         * Below this the futures wallet cannot fund an entry, so the engine
         * stops trying instead of sending an order per symbol per tick.
         */
        const val MIN_FUTURES_BALANCE_USDT = 1.0
    }
}

/**
 * The one-line explanation of an empty futures wallet, or null when the
 * balance can fund an entry.
 *
 * An empty futures wallet cannot fund anything: the exchange rejects every
 * order. Without this check the loop kept trying — once per symbol, once per
 * tick — and printed an error line every cycle for a condition the user can
 * fix in one tap. Funds sitting in the Funding wallet do not pay for futures
 * orders, which is the usual reason.
 */
internal fun emptyFuturesWalletNote(available: Double): String? =
    if (available >= TraderEngine.MIN_FUTURES_BALANCE_USDT) null
    else "the futures wallet is empty (${fmt2(available)} USDT) — funds in the Funding wallet do not pay " +
        "for futures orders (transfer them to the Futures wallet, or trade paper: set live off)"

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
    // Public channels push every ~500ms — cached by the engine, never shown in chat.
    "tickers", "ticker", "price", "trade", "depth_book1", "depth_book5", "depth_book15", "depth_books" -> ""
    // Balance pushes land in the chat but stay out of the notifications
    // (the notifier only wakes the user for order/TP-SL/position events).
    "balance" -> "WS balance ${data.optString("coin")} available ${data.optString("available")} " +
        "margin ${data.optString("margin")}"
    "position" -> {
        // UPDATE fires on every position tick — only the lifecycle events
        // (OPEN / CLOSE) are worth a line.
        if (data.optString("event").uppercase() == "UPDATE") "" else {
            val sym = data.optString("symbol").ifBlank { symbol ?: "" }
            "WS position $sym ${data.optString("side")} qty ${data.optString("qty")} " +
                "pnl ${data.optString("unrealizedPNL")}"
        }
    }
    "order" -> "WS order ${data.optString("symbol")} ${data.optString("event")} " +
        "${data.optString("type")} qty ${data.optString("qty")} ${data.optString("orderStatus")} " +
        "id ${data.optString("orderId")}"
    "tpsl" -> "WS tpsl ${data.optString("symbol")} ${data.optString("event")} " +
        "${data.optString("status")} sl ${data.optString("slPrice")} tp ${data.optString("tpPrice")} " +
        "id ${data.optString("orderId")}"
    else -> ""
}

/**
 * Risk tier covering a notional value, or null when the value falls outside the
 * tier table. Tiers: level, startValue, endValue, leverage, maintenanceMarginRate.
 */
/**
 * Position quantity. The REST docs name it `qty`; `holdQty` is accepted as a
 * fallback because some order payloads use it.
 */
internal fun posQty(p: org.json.JSONObject): String =
    p.optString("qty").ifBlank { p.optString("holdQty") }

/** Average entry price: `avgOpenPrice` per the REST docs, `entryPrice` as fallback. */
internal fun posEntry(p: org.json.JSONObject): String =
    p.optString("avgOpenPrice").ifBlank { p.optString("entryPrice") }

/**
 * Liquidation price display. Per the docs, liqPrice <= 0 means the position is
 * at low risk and no liquidation price exists at that moment.
 */
internal fun liqText(raw: String): String {
    val v = raw.toDoubleOrNull() ?: return raw.ifBlank { "—" }
    return if (v <= 0.0) "— (low risk)" else raw
}

/**
 * One row of the exchange's tiered risk limit (`get_position_tiers`).
 *
 * The official table carries four factors per tier: the tier number, the
 * nominal value range, the MAXIMUM LEVERAGE the tier allows, and the
 * maintenance margin rate. Both directions matter:
 *
 *  - the MMR RISES with the tier, so the room between entry and liquidation
 *    SHRINKS as the position grows;
 *  - the allowed leverage FALLS, so a large position may not use a large
 *    leverage — the exchange rejects the order otherwise.
 *
 * BTCUSDT, read live from the exchange:
 *
 *   level 1:      0 –   100 000 → 200x, MMR 0.30%
 *   level 2: 100 000 –   400 000 → 150x, MMR 0.40%
 *   level 3: 400 000 – 1 000 000 → 100x, MMR 0.50%
 *   level 4: 1 000 000 – 4 000 000 →  75x, MMR 0.65%
 *
 * So the 75x tier carries 0.65% MMR, and at 75x the real room between entry
 * and liquidation is 1/75 − 0.0065 = 0.68% — not the 1.33% a naive
 * Entry/Leverage suggests.
 */
internal data class RiskTier(
    val level: Int,
    val startValue: Double,
    val endValue: Double,
    /** The tier's maximum leverage. The response field is named "leverage". */
    val maxLeverage: Int,
    val mmr: Double,
)

/**
 * Parses the tier table. A row without a usable range or rate is skipped
 * rather than guessed at.
 */
internal fun parseRiskTiers(tiers: org.json.JSONArray): List<RiskTier> {
    val out = ArrayList<RiskTier>()
    for (i in 0 until tiers.length()) {
        val t = tiers.optJSONObject(i) ?: continue
        val start = t.optString("startValue").toDoubleOrNull() ?: continue
        val end = t.optString("endValue").toDoubleOrNull() ?: Double.MAX_VALUE
        val mmr = t.optString("maintenanceMarginRate").toDoubleOrNull() ?: continue
        out += RiskTier(
            level = t.optInt("level", out.size + 1),
            startValue = start,
            endValue = end,
            maxLeverage = t.optInt("leverage", TraderEngine.MAX_LEVERAGE),
            mmr = mmr,
        )
    }
    return out
}

/** The tier a position of [notional] occupies. */
internal fun tierFor(tiers: List<RiskTier>, notional: Double): RiskTier? {
    for (t in tiers) {
        if (notional >= t.startValue && notional < t.endValue) return t
    }
    return null
}

/**
 * The largest leverage whose position would land in a tier that ALLOWS it.
 *
 * The position's notional is margin × leverage, so the leverage that keeps it
 * inside tier T is
 *
 *   ceil(T.startValue / margin) ≤ L ≤ ceil(T.endValue / margin) − 1
 *
 * and the tier additionally caps it at [RiskTier.maxLeverage] — the exchange
 * rejects an order above it. The answer is the largest such L over all tiers,
 * never above [wanted].
 *
 * Worked example (BTCUSDT, margin 26 666.67 USDT): the 1M–4M tier allows 75x
 * and a 75x position is worth 2M, so 75x is allowed and is the answer — the
 * 4M–10M tier allows only 50x, and it would need a 4M notional that 75x does
 * not reach.
 */
internal fun maxTierLeverage(tiers: List<RiskTier>, margin: Double, wanted: Int): Int {
    if (margin <= 0.0 || wanted <= 0) return 1
    var best = 1
    for (t in tiers) {
        val lo = if (t.startValue <= 0.0) 1 else ceil(t.startValue / margin).toInt().coerceAtLeast(1)
        val hi = if (!t.endValue.isFinite()) Int.MAX_VALUE
        else ceil(t.endValue / margin).toInt() - 1
        val lev = minOf(t.maxLeverage, hi, wanted)
        if (lev >= lo && lev > best) best = lev
    }
    return best.coerceAtLeast(1)
}

