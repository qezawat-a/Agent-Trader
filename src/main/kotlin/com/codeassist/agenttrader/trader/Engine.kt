package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.util.toAsciiDigits

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
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** How long a WebSocket price stays usable before it counts as stale. */
private const val WS_PRICE_STALE_MS = 15_000L

/** How long the cached signal board stays fresh. */
private const val SIGNAL_BOARD_MAX_AGE_MS = 60_000L

/** How long the cached market mood stays fresh. */
private const val MOOD_CACHE_MAX_AGE_MS = 60_000L

/** How long the auto-universe ranking stays fresh. */
private const val AUTO_UNIVERSE_MAX_AGE_MS = 60_000L

/**
 * A position, paper or live. [stop] is the user's configured stop price,
 * null when none — the book then relies on the user's own TP/SL or nothing
 * else. dir is 1 for long and -1 for short.
 */
data class Position(
    val symbol: String,
    val dir: Int,
    val entry: Double,
    var qty: Double,
    val originalQty: Double,
    val leverage: Int,
    val plan: TpSlPlan,
    var stop: Double?,
    var best: Double,
    var nextRung: Int = 0,
    var breakeven: Boolean = false,
)

/**
 * The two stop moves the user can fire by hand from the Control screen.
 *
 * "Fire" bypasses ONLY the ROI trigger: the stop still has to be a valid
 * trigger for the current mark, and it still never widens an existing stop.
 */
enum class ManualStopRule { BREAKEVEN, TRAILING }

/**
 * Exit rules — every value is user-changeable (chat or Control screen).
 * No stop exists for manual entries unless the user sets one (`set sl <pct>`);
 * the app never invents a distance or a fraction of liquidation — signal
 * entries get the automatic stop (`set auto tpsl` on by default).
 */
data class RiskSettings(
    /** Stop moves to entry once ROI reaches this % (0 = breakeven off). */
    val breakevenRoiPct: Double = 25.0,
    /** Trailing starts once ROI reaches this % — fires after breakeven (0 = off). */
    val trailingTriggerRoiPct: Double = 40.0,
    /** Trailing stop keeps this much ROI distance behind the best price. */
    val trailDistanceRoiPct: Double = 25.0,
    /**
     * Stop-loss distance from the entry, as a % of the price (e.g. 2 = 2%).
     * 0 = no user stop — signal entries still get the automatic stop
     * (`set auto tpsl` on by default), a manual entry carries none.
     *
     * Kept separate from TP/SL endpoints deliberately: when > 0, the TP ladder
     * is sized on this distance (R = entry − SL).
     */
    val slPct: Double = 0.0,
)

/**
 * Paper broker: simulated market fills and PnL. Default mode; no exchange calls.
 * Balance is in USDT. Each trade risks [MARGIN_FRACTION] of the balance as margin.
 */
class PaperBroker(@Volatile var balance: Double = 1000.0) {
    /**
     * The open paper positions. The trading loop writes them while the Compose
     * thread reads them, so the map is synchronized and every iteration goes
     * through [snapshot]: iterating a map another thread mutates is a
     * ConcurrentModificationException waiting for a busy market.
     */
    val positions: MutableMap<String, Position> =
        Collections.synchronizedMap(LinkedHashMap<String, Position>())

    @Volatile
    var realizedPnl = 0.0

    val history: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** A stable copy of the open positions — safe to iterate while the loop writes. */
    fun snapshot(): List<Map.Entry<String, Position>> =
        synchronized(positions) { positions.entries.toList() }

    /** How many paper positions are open. */
    fun positionCount(): Int = positions.size

    /** A stable copy of the trade history — safe to iterate while the loop writes. */
    fun historySnapshot(): List<String> = synchronized(history) { history.toList() }

    fun open(
        symbol: String, dir: Int, price: Double, leverage: Int,
        risk: RiskSettings = RiskSettings(),
        marginFraction: Double = MARGIN_FRACTION,
    ): Position? = openSized(symbol, dir, price, leverage, marginFraction, risk)

    /**
     * The margin the open positions hold — the paper twin of the exchange's
     * `margin` field ("locked quantity of positions").
     */
    fun committedMargin(): Double =
        snapshot().sumOf { (_, p) -> if (p.leverage > 0) p.qty * p.entry / p.leverage else 0.0 }

    /**
     * The free balance — the paper twin of the exchange's `available` field
     * ("available quantity in the account"). A new position takes a share of
     * THIS, so the simulated book reserves margin exactly like the exchange
     * and can never commit more than the account holds.
     */
    fun availableMargin(): Double = maxOf(0.0, balance - committedMargin())

    /** Opens a position committing [marginFraction] of the FREE balance as margin. */
    fun openSized(
        symbol: String, dir: Int, price: Double, leverage: Int,
        marginFraction: Double, risk: RiskSettings = RiskSettings(),
    ): Position? {
        if (positions.containsKey(symbol)) return null
        // Same sizing as the live path: the configured share of the free
        // balance — the margin the open positions hold is already locked.
        val margin = MarginSizing.compute(availableMargin(), marginFraction * 100.0)
        val qty = margin * leverage / price
        // No margin (an empty account, or a 0% setting) must not create a
        // position that holds nothing: it would show in the book, count
        // against the position cap and never produce a fill.
        if (qty <= 0.0) return null
        // Simulated market: the same plan semantics as LIVE — the stop is
        // exactly the user's configured distance (none by default).
        val plan = TpSlPlanner.plan(
            price, dir, leverage, risk.slPct / 100.0, TpSlPlanner.PAPER_SIMULATED_MMR,
        )
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
        // Guarded by improvesStop exactly like the live path — without it a stop
        // that is ALREADY tighter than entry (a tight `set sl`, or a trailing stop
        // that ran first) would be WIDENED back to entry, giving risk away.
        if (!p.breakeven && risk.breakevenRoiPct > 0 &&
            TpSlPlanner.roi(p.entry, p.best, p.dir, p.leverage) >= risk.breakevenRoiPct / 100.0
        ) {
            p.breakeven = true
            val cur = p.stop
            if (cur == null || improvesStop(p.dir, p.entry, cur)) {
                p.stop = p.entry
                events.add("PAPER breakeven $symbol: SL moved to entry ${fmt(p.entry)}")
            }
        }
        // Trailing: fires at the configured trigger ROI (after breakeven) and only tightens.
        if (risk.trailingTriggerRoiPct > 0 &&
            TpSlPlanner.roi(p.entry, p.best, p.dir, p.leverage) >= risk.trailingTriggerRoiPct / 100.0
        ) {
            val trail = TpSlPlanner.trailStop(p.best, p.dir, p.leverage, risk.trailDistanceRoiPct / 100.0)
            val improves = p.stop == null || if (p.dir > 0) trail > p.stop!! else trail < p.stop!!
            if (improves) {
                p.stop = trail
                events.add("PAPER trailing $symbol: SL moved to ${fmt(trail)}")
            }
        }
        val stop = p.stop
        if (stop != null) {
            val stopHit = if (p.dir > 0) price <= stop else price >= stop
            if (stopHit) events.add(close(symbol, stop) ?: "")
        }
        return events.filter { it.isNotEmpty() }
    }

    fun close(symbol: String, price: Double): String? {
        val p = positions[symbol] ?: return null
        realize(p, price, 1.0)
        return "CLOSE $symbol @ ${fmt(price)}"
    }

    /**
     * Fires a stop move NOW, without waiting for the ROI trigger.
     *
     * The two safety rules still apply:
     *  - the stop is never widened (a stop already tighter than the candidate
     *    is left alone);
     *  - breakeven only ever moves the stop to the entry price.
     *
     * The trigger ROI is deliberately ignored here — that is the whole point
     * of the button. The trailing DISTANCE is still the user's setting.
     */
    fun fire(symbol: String, price: Double, risk: RiskSettings, rule: ManualStopRule): String {
        val p = positions[symbol] ?: return "No open paper position on $symbol."
        if (price <= 0.0 || price.isNaN()) return "No price for $symbol."
        if (p.dir > 0) p.best = maxOf(p.best, price) else p.best = minOf(p.best, price)
        return when (rule) {
            ManualStopRule.BREAKEVEN -> {
                val cur = p.stop
                if (cur != null && !improvesStop(p.dir, p.entry, cur)) {
                    // Already tighter than entry: mark the rule done, but never
                    // widen the existing stop back to the entry.
                    p.breakeven = true
                    "PAPER breakeven $symbol: SL already tighter than entry (${fmt(cur)}) — left unchanged."
                } else {
                    p.stop = p.entry
                    p.breakeven = true
                    "PAPER breakeven $symbol: SL moved to entry ${fmt(p.entry)}."
                }
            }
            ManualStopRule.TRAILING -> {
                if (risk.trailDistanceRoiPct <= 0.0) {
                    "Trailing distance is off — set trail distance first (e.g. set trail distance 15)."
                } else {
                    val trail = TpSlPlanner.trailStop(
                        p.best, p.dir, p.leverage, risk.trailDistanceRoiPct / 100.0,
                    )
                    val cur = p.stop
                    if (cur != null && !improvesStop(p.dir, trail, cur)) {
                        "PAPER trailing $symbol: SL already tighter than ${fmt(trail)} — left unchanged."
                    } else {
                        p.stop = trail
                        "PAPER trailing $symbol: SL moved to ${fmt(trail)} (best ${fmt(p.best)})."
                    }
                }
            }
        }
    }

    /**
     * Books [fraction] of the position at [price].
     *
     * A FULL close ([fraction] = 1) realizes what is still open, not the
     * original size: after TP1 has taken 25% off, closing the rest must book
     * the remaining 75% only. Sizing it on [Position.originalQty] booked the
     * ladder's already-closed part a second time and inflated the paper
     * balance by a quarter of the position on every "close the rest".
     */
    private fun realize(p: Position, price: Double, fraction: Double) {
        val closeQty = if (fraction >= 1.0) p.qty else minOf(p.originalQty * fraction, p.qty)
        if (closeQty <= 0.0) {
            positions.remove(p.symbol)
            return
        }
        val pnl = p.dir * (price - p.entry) * closeQty
        realizedPnl += pnl
        balance += pnl
        p.qty -= closeQty
        val done = p.qty <= 1e-12
        history.add("${if (done) "CLOSE" else "PARTIAL"} ${p.symbol} ${fmt(pnl)} USDT")
        if (done) positions.remove(p.symbol)
    }

    fun unrealized(prices: Map<String, Double>): Double = snapshot().sumOf { (_, p) ->
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
 * Small gap so a mark that drifts between our snapshot and the exchange's check
 * still accepts the trigger (Bitunix error 30023).
 */
private const val SL_TRIGGER_MARK_BUFFER = 0.001 // 0.1%

/**
 * Bitunix requires a LONG's SL trigger to sit strictly BELOW the mark price and
 * a SHORT's strictly ABOVE it. Breakeven/trailing stops are derived from the
 * BEST price reached, so after a pullback the candidate can end up on the wrong
 * side of the current mark — sending that is always rejected (30023). Skipping
 * it here keeps the existing stop in place and quietly retries next tick when
 * price recovers, instead of spamming a failed move on every single tick.
 */
internal fun slTriggerValid(dir: Int, sl: Double, mark: Double): Boolean {
    if (sl <= 0 || mark <= 0 || mark.isNaN() || sl.isNaN()) return false
    return if (dir > 0) sl < mark * (1 - SL_TRIGGER_MARK_BUFFER)
    else sl > mark * (1 + SL_TRIGGER_MARK_BUFFER)
}

/**
 * The breakeven stop for an open position, or null when the rule does not
 * apply yet / the stop in place is already tighter than entry.
 *
 * Breakeven fires on the BEST ROI the position reached: at
 * [RiskSettings.breakevenRoiPct] the position has earned enough that the loss
 * can be taken out at the entry — even if the price pulled back since.
 */
internal fun breakevenStopFor(
    dir: Int, entry: Double, best: Double, lev: Int, curStop: Double?, risk: RiskSettings,
): Double? {
    if (risk.breakevenRoiPct <= 0) return null
    val roi = TpSlPlanner.roi(entry, best, dir, lev)
    if (roi < risk.breakevenRoiPct / 100.0) return null
    return if (curStop == null || improvesStop(dir, entry, curStop)) entry else null
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
    dir: Int, entry: Double, best: Double, lev: Int, curStop: Double?, risk: RiskSettings,
): Double? {
    if (risk.trailingTriggerRoiPct <= 0) return null
    val bestRoi = TpSlPlanner.roi(entry, best, dir, lev)
    if (bestRoi < risk.trailingTriggerRoiPct / 100.0) return null
    val trail = TpSlPlanner.trailStop(best, dir, lev, risk.trailDistanceRoiPct / 100.0)
    return if (curStop == null || improvesStop(dir, trail, curStop)) trail else null
}

/**
 * Trader engine: owns the broker, the scanner universe, and the on/off switches.
 * Live trading needs explicit enabling and API keys. Until then every order is paper.
 */
class TraderEngine(private val rest: BitunixRest) {
    val broker = PaperBroker()

    // The trading loop writes these while the Compose thread reads them.
    @Volatile
    var engineOn = false

    @Volatile
    var trading = false

    @Volatile
    var scanOn = false

    /**
     * How many positions are open ON THE EXCHANGE. The paper broker says
     * nothing about the real account, so LIVE counts its positions here and
     * the risk manager caps live entries with it. Refreshed from every REST
     * snapshot; the exchange remains the authority.
     */
    @Volatile
    internal var liveOpenCount: Int = 0

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

    @Volatile
    var leverage = 5

    @Volatile
    var marginMode = "ISOLATION"

    @Volatile
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
    /** The rank mode the cached universe was built with (see [refreshAutoUniverseIfStale]). */
    private var autoRankByUsed: String = ""

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
    @Volatile
    var risk = RiskSettings()

    /** Maximum simultaneously open paper positions (0 = unlimited). */
    @Volatile
    var maxOpenPositions = 3

    /** Minimum seconds between two entries (0 = off). */
    @Volatile
    var guardIntervalSec = 300L

    /** Take-profit distance as an R-multiple of the stop distance (live orders attach
     *  this rung for the full qty; paper keeps its 4-rung ladder). 1.0 = at the SL distance. */
    var tpR: Double = 1.0
    /** LIVE TP ladder (off by default). When on, TP is split into [ladderRungs]
     *  steps at 1R, 2R … nR, each closing 1/n of the position; the last step is
     *  the entry order's own TP and closes the rest. */
    var ladderOn: Boolean = false
    var ladderRungs: Int = 4

    /**
     * Signal-driven entries get an automatic TP/SL: a stop scaled by the signal
     * strength (user's `set sl` base, or [TpSlPlanner.AUTO_SL_BASE_PCT] when unset)
     * plus the 1R–4R TP ladder. Manual entries keep the user's own stop only.
     */
    var autoTpsl: Boolean = true
        set(value) { field = value; configRev++ }

    // Live-position mid-management state, keyed by symbol.
    private val liveBest = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val liveLastStop = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val liveBreakevenDone = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
    @Volatile
    var reportOn = false

    @Volatile
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
        return if (System.currentTimeMillis() - ts <= WS_PRICE_STALE_MS) price else null
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
                if (privateChannel) {
                    client.subscribePrivateAll(listOf("balance", "position", "order", "tpsl"))
                    // A reconnect replays future pushes, not what happened while
                    // the socket was down. Seed the cache from REST so a position
                    // closed during the outage cannot linger as "open".
                    Thread { refreshLivePositions() }
                        .apply { isDaemon = true; name = "position-sync" }
                        .start()
                }
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
        // sizing while the feed reconnects.
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

    /** Test hook: seeds the private-feed cache without opening a socket. */
    internal fun seedFeedPositions(symbols: List<String>) {
        synchronized(wsPositions) {
            wsPositions.clear()
            for (sym in symbols) {
                wsPositions[sym] = JSONObject().put("symbol", sym).put("side", "LONG").put("qty", "1")
            }
        }
    }

    /**
     * Replaces the private-feed position cache with a REST snapshot. REST is
     * the source of truth; the WebSocket cache is only a fast view of it.
     *
     * The feed pushes CHANGES, not state. A position that closes while the
     * socket is reconnecting never sends its zero-qty frame, so the old cache
     * kept announcing a position that no longer existed. The chat then handed
     * that stale symbol to the model, which filled in the missing details.
     */
    internal fun reconcilePositions(positions: JSONArray) {
        val fresh = HashMap<String, JSONObject>()
        for (i in 0 until positions.length()) {
            val p = positions.getJSONObject(i)
            val sym = p.optString("symbol")
            if (sym.isNotBlank()) fresh[sym] = p
        }
        synchronized(wsPositions) {
            wsPositions.clear()
            wsPositions.putAll(fresh)
        }
    }

    /**
     * Refreshes the feed cache from the exchange. Blocking (network) — call it
     * from a background thread. Returns the open count, or null when the
     * exchange is unreachable.
     */
    fun refreshLivePositions(): Int? = try {
        val positions = rest.pendingPositions()
        reconcilePositions(positions)
        liveOpenCount = positions.length()
        positions.length()
    } catch (_: Exception) {
        null
    }

    /**
     * The chat's authoritative position context: one line per open live
     * position, read from REST instead of the feed cache.
     *
     * Blocking (network) — the caller runs on a background thread. The model
     * must never fill in a field this snapshot does not carry (leverage, SL,
     * TP, uPnL); the prompt says so explicitly.
     */
    fun livePositionsSummary(): String {
        val positions = try {
            rest.pendingPositions()
        } catch (e: Exception) {
            val cached = feedPositions()
            val why = e.message ?: e.javaClass.simpleName
            return if (cached.isEmpty()) {
                "unavailable (exchange: $why) — call <tool>positions</tool>"
            } else {
                "feed cache only (exchange unreachable: $why): ${cached.joinToString(", ")} — " +
                    "symbols only; call <tool>positions</tool> for the live snapshot"
            }
        }
        reconcilePositions(positions)
        liveOpenCount = positions.length()
        if (positions.length() == 0) return "none open on the exchange"
        return (0 until positions.length()).joinToString("\n") { i ->
            livePositionLine(positions.getJSONObject(i))
        }
    }

    private fun candles(symbol: String, tf: String): List<Candle> = rest.klines(symbol, tf, 200)

    /** Closed klines for the Trade-screen chart. Callers must use a background thread. */
    fun chartCandles(symbol: String, interval: String, limit: Int = 120): List<Candle> =
        rest.klines(symbol, interval, limit.coerceIn(1, 200))

    /** Mark price for the board: WS first, then REST — never a made-up number. */
    private fun boardMark(symbol: String, fallback: Double = 0.0): Double =
        wsLivePrice(symbol)
            ?: runCatching { rest.markPrice(symbol).optString("markPrice").toDoubleOrNull() }
                .getOrNull()?.takeIf { it > 0.0 }
            ?: runCatching { rest.lastPrice(symbol) }.getOrDefault(fallback)

    /**
     * What the Trade screen shows while a position is open: position facts,
     * its TP/SL levels, and the pending order list.
     *
     * BLOCKING (REST) — callers must run it off the UI thread. Each endpoint is
     * fetched independently so one failed call still leaves the rest visible.
     */
    fun tradeBoard(): TradeBoard {
        if (!live) {
            val positions = broker.snapshot().map { (sym, p) ->
                val mark = boardMark(sym, p.entry)
                val margin = if (p.leverage > 0) p.qty * p.entry / p.leverage else p.qty * p.entry
                val pnl = (mark - p.entry) * p.qty * p.dir
                BoardPosition(
                    symbol = p.symbol,
                    side = if (p.dir > 0) "LONG" else "SHORT",
                    qty = p.qty,
                    entry = p.entry,
                    mark = mark,
                    leverage = p.leverage,
                    margin = margin,
                    pnl = pnl,
                    pnlPct = if (margin > 0.0) pnl / margin * 100.0 else 0.0,
                    liq = p.plan.liquidation.takeIf { it > 0.0 },
                    stop = p.stop,
                    tps = p.plan.ladder.mapIndexed { i, (price, frac) ->
                        BoardTp("${i + 1}R", price, "${fmt0(frac * 100)}%", i < p.nextRung)
                    },
                    paper = true,
                )
            }
            return TradeBoard(
                positions = positions,
                orders = emptyList(),
                note = "PAPER — TP/SL and liquidation are simulated locally; no exchange orders.",
            )
        }

        val problems = ArrayList<String>()
        val posArr = try {
            rest.pendingPositions()
        } catch (e: Exception) {
            problems += "positions: ${e.message ?: e.javaClass.simpleName}"
            org.json.JSONArray()
        }
        val ordArr = try {
            rest.pendingOrders(limit = 50)
        } catch (e: Exception) {
            problems += "orders: ${e.message ?: e.javaClass.simpleName}"
            org.json.JSONArray()
        }
        val tpArr = try {
            rest.pendingTpSlOrders(limit = 50)
        } catch (e: Exception) {
            problems += "TP/SL: ${e.message ?: e.javaClass.simpleName}"
            org.json.JSONArray()
        }
        val positions = (0 until posArr.length()).mapNotNull { i ->
            val p = posArr.optJSONObject(i) ?: return@mapNotNull null
            val sym = p.optString("symbol")
            val parsed = parseBoardPosition(p, boardMark(sym))
            val (stop, tps) = tpslFor(tpArr, sym)
            parsed.copy(stop = stop, tps = tps)
        }
        return TradeBoard(positions, parseBoardOrders(ordArr, tpArr), problems.joinToString(" · "))
    }

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
     * The leverage an entry may use — nothing invented here.
     *
     * PAPER: the simulated leverage is used as set; there are no exchange
     * limits to respect and the book is rehearsal.
     *
     * LIVE: the symbol's OWN tiered risk-limit table (get_position_tiers)
     * decides, per the official rules. The tier is calculated from the
     * leverage the user selects — the highest tier whose maximum leverage
     * still covers the selection. The intended position value must stay
     * within that tier's nominal range; otherwise the entry is refused.
     * The MMR used for the plan is that tier's rate (never a fixed fallback).
     *
     * Returns (leverage, refusalReason): refusalReason is non-null exactly
     * when the entry must not open; a 0x leverage signals a refusal.
     */
    internal fun entryLeverage(symbol: String): Pair<Int, String?> {
        val wanted = effectiveLeverage(symbol)
        if (!live) return wanted to null
        val tiers = riskTiers(symbol)
        if (tiers.isEmpty()) return 0 to
            "SKIP $symbol: tiered risk-limit table unavailable — refusing to trade without the exchange's limits"
        val tier = tierForLeverage(tiers, wanted)
            ?: return 0 to "SKIP $symbol: ${wanted}x exceeds every tier this symbol allows"
        val margin = marginForEntry()
        if (margin > 0.0 && tier.endValue.isFinite() && margin * wanted > tier.endValue) {
            return 0 to "SKIP $symbol: position value ${fmt(margin * wanted)} exceeds tier ${tier.level} cap (${fmt(tier.endValue)} USDT)"
        }
        return wanted to null
    }

    /** The margin one entry commits: the configured share of the FREE balance. */
    private fun marginForEntry(): Double {
        val available = if (live) {
            runCatching { rest.availableUsdt() }.getOrNull() ?: liveBalanceSnapshot() ?: broker.balance
        } else broker.availableMargin()
        return MarginSizing.compute(available, riskFraction * 100.0)
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
        // The paper book has no exchange tiers: the simulated default rate
        // applies. The LIVE path is the one that must match the exchange.
        if (!live) return emptyList()
        val tiers = runCatching { parseRiskTiers(rest.positionTiers(symbol)) }.getOrDefault(emptyList())
        tierCache[symbol] = tiers to System.currentTimeMillis()
        return tiers
    }

    /**
     * The maintenance margin rate (MMR) for a symbol at a given notional — the
     * official tier table is the source of every rate. When the notional is
     * beyond the table, the highest MMR in it applies (the most conservative
     * tiered rate the exchange publishes). When the table is entirely
     * unavailable (API down), the simulator's MMR stands in as a placeholder;
     * the exchange's own `liqPrice` is always preferred over this formula.
     */
    internal fun mmrFor(symbol: String, notional: Double): Double {
        val tiers = riskTiers(symbol)
        if (tiers.isEmpty()) return TpSlPlanner.PAPER_SIMULATED_MMR
        tierFor(tiers, notional)?.let { return it.mmr.coerceIn(0.0, 0.5) }
        return tiers.maxByOrNull { it.mmr }?.mmr?.coerceIn(0.0, 0.5)
            ?: TpSlPlanner.PAPER_SIMULATED_MMR
    }

    /** Formats qty to the symbol's base precision, floored. */
    fun formatQty(symbol: String, raw: Double): String? = formatQty(symbol, raw, pairInfo(symbol))

    internal fun formatQty(symbol: String, raw: Double, info: PairInfo?): String? {
        if (info == null) return fmt6(raw)
        // valueOf() uses the canonical decimal string, avoiding the BigDecimal(double)
        // binary artifact that made 0.123 floor to 0.122 at 3 decimals.
        val qty = java.math.BigDecimal.valueOf(raw)
            .setScale(info.basePrecision, java.math.RoundingMode.FLOOR)
            .toDouble()
        // Only a literally non-positive order is not placed.
        if (qty <= 0) return null
        // The exchange rejects anything under its minimum (error 30016: "amount should be
        // larger than 10 NEAR"). Checked after flooring to the base precision, so the caller
        // reports "below min volume" instead of sending an order that is certain to fail.
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
    internal fun refreshAutoUniverseIfStale(maxAgeMs: Long = AUTO_UNIVERSE_MAX_AGE_MS): String {
        if (!autoUniverse) return ""
        val now = System.currentTimeMillis()
        // The size check is the bug: if the cached universe already has scanTop
        // symbols it never re-picks, so selecting Gainers (or changing the
        // rank mode/min volume) leaves the old set in place. A manual refresh
        // (maxAgeMs = 0) must always refresh.
        if (now - autoCacheAt <= maxAgeMs && universe.size >= scanTop && autoRankByUsed == autoRankBy) return ""
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
        autoRankByUsed = autoRankBy
        configRev++
        return "Auto-universe: ${picked.size} symbols → ${picked.joinToString(", ")}"
    }

    /** One-off manual refresh of the auto universe. */
    fun refreshAutoUniverse(): String = refreshAutoUniverseIfStale(0)

    /**
     * Evaluates the universe and returns one explainable row per symbol.
     * Cached for [maxAgeMs] because each row costs two kline requests.
     */
    fun signalBoard(maxAgeMs: Long = SIGNAL_BOARD_MAX_AGE_MS): List<SignalRow> {
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
        "Balance ${fmt2(broker.balance)} USDT (paper) · free ${fmt2(broker.availableMargin())} · " +
            "realized ${fmt(broker.realizedPnl)} · open ${broker.positions.size}"
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
        // LIVE caps on the EXCHANGE's open positions, not on the paper book —
        // the paper broker cannot know how many real positions exist.
        if (maxOpenPositions > 0) {
            val open = if (live) liveOpenCount else broker.positionCount()
            if (open >= maxOpenPositions) {
                return "Risk manager: max open positions ($maxOpenPositions) reached — close one or raise the cap"
            }
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
        appendLine("  leverage ${leverage}x · margin per trade ${(riskFraction * 100).toInt()}% of the free balance · report ${if (reportOn) "on" else "off"} every $reportIntervalMin min")
        appendLine("  exits: breakeven ${offable(risk.breakevenRoiPct)} ROI → SL to entry · trailing trigger ${offable(risk.trailingTriggerRoiPct)} ROI, ${fmt0(risk.trailDistanceRoiPct)}% behind best · SL ${if (risk.slPct > 0) "${fmt1(risk.slPct)}% from entry" else "none (set sl <pct> — no stop by default)"}")
        appendLine("  auto tpsl: ${if (autoTpsl) "on — signal entries get the strength-scaled stop (user's set sl base, or ${TpSlPlanner.AUTO_SL_BASE_PCT.toInt()}% when none set) + the 1R–4R TP ladder" else "off — entries carry the user's stop only"}")
        appendLine("  risk manager: max open positions $maxOpenPositions · guard interval ${if (guardIntervalSec > 0) "${guardIntervalSec}s" else "off"}")
        appendLine("  feed: ${if (liveFeedOn) "connected" else "off"}")
        append("  change with: set universe <A,B,C> · set timeframes <15m,1h> · set scan top <n> · " +
            "set min agree <n> · set min tf <pct> · set min conf <pct> · set tf agree <n> · set leverage <n> · set risk <pct> · " +
            "set breakeven <pct> · set trail trigger <pct> · set trail distance <pct> · set sl <pct> · " +
            "set tp r <n> (TP = n× the stop distance) · " +
            "set auto tpsl on|off · " +
            "set max positions <n> · set guard <sec>")
    }.trim()

    /** One scan cycle: analyse each symbol and open a paper trade when the signal passes. */
    fun scanOnce(): List<String> {
        val out = mutableListOf<String>()
        val symbols = runCatching {
            // Auto-universe (gainers/volume/...) must refresh even when the
            // scanner is off: otherwise the trading loop keeps scanning the
            // default BTC/ETH/SOL list instead of today's gainers.
            if (autoUniverse) refreshAutoUniverseIfStale()
            if (scanOn) rankUniverse(scanTop) else universe.toList()
        }.getOrElse { universe.toList() }
        // The open positions are ONE account-wide query for the whole scan.
        val openPositions = if (live) {
            runCatching { rest.pendingPositions() }
                .onSuccess {
                    reconcilePositions(it)
                    liveOpenCount = it.length()
                }
                .getOrNull()
        } else null
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
                // Signal entries get the strength-scaled auto stop; the risk
                // manager keeps its own values for management and manual entry.
                val riskAt = risk.copy(slPct = slPctForSignal(sig.confidence / 100.0))
                openPaper(sym, sig.dir, price, lev, riskAt)?.let { pos ->
                    noteEntry()
                    out.add(
                        "OPEN ${if (sig.dir > 0) "LONG" else "SHORT"} $sym @ ${fmt(price)} conf ${fmt0(sig.confidence)}" +
                            " · margin ${fmt(pos.qty * pos.entry / pos.leverage)} USDT" +
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
     * A PAPER entry sized exactly like the LIVE one: the configured margin per
     * trade ([riskFraction]) of the free balance.
     *
     * The old paper path used a fixed 10% margin no matter what "margin per
     * trade" said, so the position never grew past a tenth of the account —
     * while the live path used the setting. One rule, both books.
     */
    internal fun openPaper(
        symbol: String, dir: Int, price: Double, leverage: Int, riskAt: RiskSettings,
    ): Position? = broker.open(symbol, dir, price, leverage, riskAt, riskFraction)

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
            val pos = broker.openSized(
                sym, dir, px, lev, sizeFraction, risk,
            ) ?: return "SKIP $sym: could not open."
            noteEntry()
            return "PAPER OPEN ${if (dir > 0) "LONG" else "SHORT"} $sym qty ${fmt(pos.qty)} @ ${fmt(px)} " +
                "stop ${pos.stop?.let { fmt(it) } ?: "none"} (paper)" +
                " · margin ${fmt(pos.qty * pos.entry / pos.leverage)} USDT" +
                (levNote?.let { " · $it" } ?: "")
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
                liveOpenCount = 0
                "Close-all-positions sent to the exchange."
            } catch (e: Exception) {
                "Close all failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val syms = broker.snapshot().map { it.key }
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
        if (cached != null && now - cached.first < MOOD_CACHE_MAX_AGE_MS) return cached.second
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
        val open = broker.snapshot()
        if (open.isEmpty()) return null
        val prices = open.associate { it.key to symbolPrice(it.key) }
        val margin = broker.committedMargin()
        if (margin <= 0) return null
        return broker.unrealized(prices) / margin
    }

    /**
     * The SL distance, as a % of the price, for a signal entry of the given
     * strength: auto mode scales the user's `set sl` base (or
     * [TpSlPlanner.AUTO_SL_BASE_PCT]) by the strength. Null strength = manual
     * entry — it keeps the user's own stop only.
     */
    fun slPctForSignal(strength: Double?): Double =
        if (autoTpsl && strength != null) TpSlPlanner.slPctAt(risk.slPct, strength) else risk.slPct

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
        // The open positions are ONE account-wide query, not one per symbol:
        // the scan asks for it once and hands it down. Asking again for every
        // symbol doubled the request rate of every tick.
        val livePositions = openPositions ?: rest.pendingPositions()
        for (i in 0 until livePositions.length()) {
            if (livePositions.getJSONObject(i).optString("symbol") == symbol) {
                return "SKIP $symbol: live position already open."
            }
        }
        // The leverage an entry may use — nothing invented: PAPER simulates at
        // the user's setting; LIVE checks the selected leverage against the
        // symbol's own live tiered risk-limit table and refuses when it does
        // not cover the order.
        val (lev, levNote) = entryLeverage(symbol)
        if (lev <= 0) return levNote ?: "SKIP $symbol"
        val levLine = levNote?.let { " · $it" } ?: ""
        // The maintenance margin rate of the tier this entry lands in — the
        // official liquidation price is Entry × (1 − 1/Leverage + MMR), so the
        // MMR decides how much room the position really has. Every tier
        // rate comes from the symbol's get_position_tiers table.
        val mmr = mmrFor(symbol, available * marginFraction * lev)
        // Margin sizing: the configured share of the FREE balance. The
        // exchange's `available` already excludes the margin the open positions
        // hold, so the size is the size the user set.
        val margin = MarginSizing.compute(available, marginFraction * 100.0)
        // TP/SL triggers are LAST_PRICE per project rule, so plan the levels from
        // the signal price; fall back to the mark price when the exchange publishes one.
        val mark = runCatching {
            rest.markPrice(symbol).optString("markPrice").toDoubleOrNull()
        }.getOrNull()
        val triggerBasis = mark ?: price
        val plan = TpSlPlanner.plan(
            triggerBasis, dir, lev, slPctForSignal(confidence) / 100.0, mmr,
        )
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
            // TP sits tpR × the stop distance away from entry (set tp r <n>) —
            // exists only when the user configured a stop (set sl <pct>).
            val rDist = plan.stop?.let { kotlin.math.abs(triggerBasis - it) }?.coerceAtLeast(1e-12)
            // With the ladder on, the entry carries NO TP: a TP on the entry
            // order closes the whole position, so every rung (the last one too)
            // is its own partial TP order with its own qty, placed after the fill.
            val tp = if (ladderOn) null
                else rDist?.let { if (dir > 0) triggerBasis + tpR * it else triggerBasis - tpR * it }
            val order = rest.placeMarketOrder(
                symbol = symbol, qty = qty, side = side, tradeSide = tradeSide,
                tpPrice = tp?.let { formatPrice(symbol, it) },
                slPrice = plan.stop?.let { formatPrice(symbol, it) },
                reduceOnly = false,
            )
            val orderId = order.optString("orderId")
            val status = waitForFill(orderId)
            val conf = if (confidence != null) " conf ${fmt0(confidence)}" else ""
            if (status == "FILLED" || status == "PART_FILLED") {
                liveOpenCount += 1
                noteEntry()
                // The entry order's slPrice is an ORDER-level stop. Breakeven and
                // trailing move the POSITION-level TP/SL order, which does not
                // exist yet — so arm it now, from the position the exchange just
                // opened. Without this the first move has to create it, and until
                // then the exchange holds a stop the risk manager cannot see.
                val armed = runCatching {
                    val pos = rest.pendingPositions(symbol)
                    val pid = if (pos.length() > 0) pos.getJSONObject(0).optString("positionId") else ""
                    if (pid.isBlank()) return@runCatching null
                    // The entry order already carried tpPrice/slPrice, and Bitunix
                    // turns that into a TP/SL order on the position. Placing a
                    // Position TP/SL on top of it gave TWO TP/SL orders. Only arm
                    // one when the exchange shows no stop for this position yet.
                    if (stopOrderFor(symbol, pid) != null) return@runCatching pid
                    rest.placePositionTpSl(
                        symbol, pid, tpPrice = tp?.let { formatPrice(symbol, it) },
                        slPrice = plan.stop?.let { formatPrice(symbol, it) },
                    )
                    pid
                }.getOrNull()
                // Ladder rungs 1R..nR: one partial TP order each, 1/n of the qty;
                // the last rung takes the remainder so the sum is exactly the qty.
                val ladderNote = if (!ladderOn || rDist == null || armed == null) "" else
                    placeLadder(symbol, armed, dir, triggerBasis, rDist, qty)
                val slNote = ladderNote + when {
                    plan.stop == null -> ""
                    armed != null -> " | SL armed on position"
                    else -> " | WARNING: position-level SL not armed — breakeven/trailing will create it on first move"
                }
                return "LIVE OPEN ${if (dir > 0) "LONG" else "SHORT"} $symbol qty $qty @ ~${fmt(price)}$conf " +
                    "| margin ${fmt2(margin)} USDT | order $orderId $status$levLine$slNote"
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
        // Asking for the live snapshot is also a reconciliation: the feed cache
        // is what the UI and the open-position gate read between REST calls.
        reconcilePositions(positions)
        liveOpenCount = positions.length()
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
    fun tick(): List<String> {
        val events = mutableListOf<String>()
        // The paper book is only managed in PAPER mode. In LIVE it must not
        // produce "breakeven"/"close" events for positions that do not exist
        // on the exchange.
        if (!live) {
            for (sym in broker.snapshot().map { it.key }) {
                // Mark with the same price the app shows: WS last price, then
                // the exchange's REST last price, then the candle close.
                // Managing on the last 15m candle close meant a stop or a TP
                // level was only noticed when that candle closed — up to
                // fifteen minutes after the price had already gone through it.
                val price = runCatching { symbolPrice(sym) }.getOrDefault(0.0)
                if (price <= 0) continue
                events.addAll(broker.manage(sym, price, risk))
            }
        } else events.addAll(manageLiveStops())
        return events
    }

    /**
     * Fires a stop move NOW for an open position (the Control screen's
     * "Fire breakeven" / "Fire trailing" buttons, and the matching chat
     * commands).
     *
     * The ROI trigger is bypassed on purpose — the user asked for the move.
     * Everything else still has to hold: the stop never widens, and the
     * exchange still has to accept the trigger side of the current mark.
     *
     * PAPER: the simulated stop moves immediately.
     * LIVE: the position-level TP/SL order is modified/created and the new
     * stop is read back from the exchange before it is reported as done.
     *
     * BLOCKING (REST) — callers must run it off the UI thread.
     */
    fun fireStopRule(rule: ManualStopRule, symbolArg: String = ""): String {
        val wanted = symbolArg.trim().uppercase()
        if (!live) {
            val open = broker.snapshot().map { it.key }
            val sym = when {
                wanted.isNotBlank() -> wanted
                open.size == 1 -> open.first()
                else -> return "Usage: fire ${rule.name.lowercase()} <SYMBOL> — open: " +
                    open.joinToString(", ").ifEmpty { "(none)" }
            }
            val pos = broker.positions[sym] ?: return "No open paper position on $sym."
            // The paper book already tracks the best price it has seen; a REST
            // round-trip here would only add latency (and fail offline).
            val px = wsLivePrice(sym) ?: pos.best
            if (px <= 0.0) return "No price for $sym."
            return broker.fire(sym, px, risk, rule)
        }
        if (rule == ManualStopRule.TRAILING && risk.trailDistanceRoiPct <= 0.0) {
            return "Trailing distance is off — set trail distance first (e.g. set trail distance 15)."
        }
        val positions = try {
            rest.pendingPositions()
        } catch (e: Exception) {
            return "Live positions failed: ${e.message ?: e.javaClass.simpleName}"
        }
        val openSymbols = (0 until positions.length())
            .map { positions.getJSONObject(it).optString("symbol").uppercase() }
            .filter { it.isNotBlank() }
        val sym = when {
            wanted.isNotBlank() -> wanted
            openSymbols.size == 1 -> openSymbols.first()
            else -> return "Usage: fire ${rule.name.lowercase()} <SYMBOL> — open: " +
                openSymbols.joinToString(", ").ifEmpty { "(none)" }
        }
        val p = (0 until positions.length())
            .map { positions.getJSONObject(it) }
            .firstOrNull { it.optString("symbol").uppercase() == sym }
            ?: return "No live position on $sym."
        val positionId = p.optString("positionId")
        if (positionId.isBlank()) return "No live position id for $sym."
        val entry = posEntry(p).toDoubleOrNull() ?: return "No entry price for $sym."
        val dir = when (p.optString("side").uppercase()) {
            "LONG", "BUY" -> 1
            "SHORT", "SELL" -> -1
            else -> return "Unknown position side for $sym."
        }
        val lev = p.optString("leverage").toIntOrNull()?.takeIf { it > 0 } ?: effectiveLeverage(sym)
        val mark = wsMarketData(sym)?.optDouble("mp")?.takeIf { it > 0 && !it.isNaN() }
            ?: runCatching { rest.markPrice(sym).optString("markPrice").toDoubleOrNull() }
                .getOrNull()?.takeIf { it > 0 }
            ?: return "No mark price for $sym."
        // The stop already on the exchange is the baseline: the candidate must
        // improve it, never widen it.
        val curStop = liveLastStop[sym]
            ?: runCatching { findSlPrice(rest.pendingTpSlOrders(sym, positionId)) }.getOrNull()
            ?: runCatching { findSlPrice(rest.pendingTpSlOrders(sym)) }.getOrNull()
        val candidate = when (rule) {
            ManualStopRule.BREAKEVEN -> entry
            ManualStopRule.TRAILING -> {
                val prevBest = liveBest[sym]
                val best = if (dir > 0) maxOf(mark, prevBest ?: mark) else minOf(mark, prevBest ?: mark)
                liveBest[sym] = best
                TpSlPlanner.trailStop(best, dir, lev, risk.trailDistanceRoiPct / 100.0)
            }
        }
        if (curStop != null && !improvesStop(dir, candidate, curStop)) {
            return "LIVE ${rule.name.lowercase()} $sym: SL already tighter than ${fmt(candidate)} — left unchanged."
        }
        if (!slTriggerValid(dir, candidate, mark)) {
            return "LIVE ${rule.name.lowercase()} $sym: ${fmt(candidate)} is on the wrong side of the current mark ${fmt(mark)} — not sent."
        }
        val roi = TpSlPlanner.roi(entry, mark, dir, lev)
        val events = mutableListOf<String>()
        val reason = if (rule == ManualStopRule.BREAKEVEN) "Manual breakeven" else "Manual trailing"
        val ok = applyLiveSl(sym, positionId, candidate, dir, mark, reason, roi, events)
        if (!ok) return "LIVE ${rule.name.lowercase()} $sym: SL move not confirmed — ${events.joinToString(" ")}"
        if (rule == ManualStopRule.BREAKEVEN) liveBreakevenDone[sym] = true
        return "LIVE ${rule.name.lowercase()} $sym: SL moved to ${fmt(candidate)}."
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
        // The REST snapshot is authoritative: use it to repair the feed cache
        // too, so the UI and the chat cannot keep a position the exchange no
        // longer reports (a close during a WS reconnect sends no removal push).
        reconcilePositions(positions)
        liveOpenCount = positions.length()
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
            // price is Entry × (1 − 1/Leverage + MMR). The tiered risk limit is
            // keyed on the position's actual value, so use qty × entry, not
            // price × leverage (that overstates the notional on high-priced
            // symbols and understates it on low-priced/high-qty ones).
            val mmr = posNotional(p)?.let { mmrFor(sym, it) } ?: TpSlPlanner.PAPER_SIMULATED_MMR
            val mark = wsMarketData(sym)?.optDouble("mp")?.takeIf { it > 0 && !it.isNaN() }
                ?: runCatching { rest.markPrice(sym).optString("markPrice").toDoubleOrNull() }
                    .getOrNull()?.takeIf { it > 0 }
                ?: continue
            val prevBest = liveBest[sym]
            val best = if (dir > 0) maxOf(mark, prevBest ?: mark) else minOf(mark, prevBest ?: mark)
            liveBest[sym] = best
            // Current SL baseline: what this engine last CONFIRMED on the
            // exchange, else the exchange's pending SL. A stop that only exists
            // in local memory is not a baseline — reporting it as one is how the
            // chat came to claim a stop the exchange never had.
            var curStop = liveLastStop[sym]
            var curStopIsReal = curStop != null
            if (curStop == null) {
                curStop = runCatching { findSlPrice(rest.pendingTpSlOrders(sym, positionId)) }.getOrNull()
                    ?: runCatching { findSlPrice(rest.pendingTpSlOrders(sym)) }.getOrNull()
                curStopIsReal = curStop != null
            }
            // No exchange SL visible (never attached, rejected, or the query
            // failed): use the engine's OWN planned stop as a COMPARISON
            // baseline only, so the trailing rule can still fire. It is NOT
            // cached as the live stop — nothing is armed on the exchange, and
            // the first real move must go through place_position_tp_sl_order.
            if (curStop == null) {
                curStop = TpSlPlanner.plan(entry, dir, lev, slPctForSignal(1.0) / 100.0, mmr).stop
            }
            if (curStop != null && curStopIsReal) liveLastStop[sym] = curStop
            else if (!curStopIsReal) liveLastStop.remove(sym)
            // Breakeven: SL to entry once ROI passes the level. The "done" flag
            // is set only when the stop really moved — a rejected or failed move
            // is retried on the next tick instead of being given up on.
            val bestRoi = TpSlPlanner.roi(entry, best, dir, lev)
            val be = breakevenStopFor(dir, entry, best, lev, curStop, risk)
            if (be != null) {
                if (applyLiveSl(sym, positionId, be, dir, mark, "Breakeven", bestRoi, events)) {
                    liveBreakevenDone[sym] = true
                    // The stop just moved to entry; trailing is evaluated on the
                    // NEXT tick against the confirmed new baseline. Continuing
                    // here is safe because the move really happened.
                    continue
                }
                // The move did NOT happen. Do not `continue`: trailing must still
                // get its chance this tick, and the failed breakeven is retried
                // next tick (the done flag was not set).
            } else if (liveBreakevenDone[sym] != true && risk.breakevenRoiPct > 0 &&
                bestRoi >= risk.breakevenRoiPct / 100.0
            ) {
                // The stop in place is already tighter than entry: there is
                // nothing to move, and the rule must not keep retrying.
                liveBreakevenDone[sym] = true
            }
            // Trailing: fires on the BEST price reached (not the current price),
            // so a pullback does not stop the stop from following the position.
            trailingStopFor(dir, entry, best, lev, curStop, risk)?.let { trail ->
                applyLiveSl(sym, positionId, trail, dir, mark, "Trailing", bestRoi, events)
            }
        }
        // Forget state for positions that closed (collect first, then remove —
        // the map is being iterated).
        val staleSymbols = liveBest.keys.filter { it !in openSymbols }
        for (sym in staleSymbols) {
            liveBest.remove(sym)
            liveLastStop.remove(sym)
            liveBreakevenDone.remove(sym)
        }
        return events
    }

    /** A candidate stop only improves when it moves toward/beyond the current stop. */
    private fun improves(dir: Int, candidate: Double, current: Double): Boolean =
        improvesStop(dir, candidate, current)

    /**
     * Moves the stop on the EXCHANGE, then reports it — in that order.
     *
     * The reported bug was "the chat says the SL moved but nothing moved on the
     * exchange". Three separate causes, all fixed here:
     *
     *  1. Only `modify_position_tp_sl_order` was called. That endpoint MODIFIES
     *     an existing position-level TP/SL order and fails when the position has
     *     none — which is the normal case, because the entry attaches its stop
     *     through `place_order`'s `slPrice`. So the move was rejected every
     *     time. Now the armed order is read first: modify when one exists,
     *     `place_position_tp_sl_order` (which CREATES it) when none does.
     *  2. The read-back treated "no SL found" as success. A null read means NO
     *     stop is armed at all — the worst possible state — yet it was reported
     *     as moved. A null/zero read is now a failure.
     *  3. Local state was written before the exchange answered. `liveLastStop`
     *     and the "SL moved" event are now written ONLY after the exchange
     *     confirms the new price, so the chat can never claim a move that did
     *     not happen.
     *
     * Returns true only on a confirmed move; the breakeven rule marks itself
     * done on that return value alone, so a rejected move is retried next tick.
     */
    private fun applyLiveSl(
        sym: String, positionId: String, sl: Double, dir: Int, mark: Double,
        reason: String, roi: Double, events: MutableList<String>,
    ): Boolean {
        // A stop derived from the best price can be above the current mark after
        // a pullback. Bitunix rejects that (30023) on every retry — skip quietly,
        // keep the existing stop and try again when price comes back.
        if (!slTriggerValid(dir, sl, mark)) return false
        val price = formatPrice(sym, sl)
        val target = price.toDoubleOrNull() ?: sl
        // 1. Decide the endpoint from what is ACTUALLY armed, not from an error
        //    message: modify only works on an existing position-level order, and
        //    place fails when one already exists ("Each position can only have
        //    one Position TP/SL Order"). Asking first makes the choice exact.
        val existing = runCatching { rest.positionTpSlOrder(sym, positionId) }.getOrNull()
        // The stop set with the entry order lives in its own TP/SL order (it
        // carries a qty). Move THAT order instead of opening a second stop.
        val entryStop = if (existing == null) runCatching { stopOrderFor(sym, positionId) }.getOrNull() else null
        val entryStopId = entryStop?.let { o -> o.optString("id").ifBlank { o.optString("orderId") } }.orEmpty()
        try {
            if (existing != null) {
                rest.modifyPositionTpSl(sym, positionId, tpPrice = null, slPrice = price)
            } else if (entryStop != null && entryStopId.isNotBlank()) {
                // Keep its TP exactly as it is; change only the stop price.
                val tpP = entryStop.optString("tpPrice").takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }
                val tpQ = entryStop.optString("tpQty").takeIf { it.isNotBlank() && tpP != null }
                val slQ = entryStop.optString("slQty").ifBlank { entryStop.optString("tpQty") }.takeIf { it.isNotBlank() }
                rest.modifyTpSlOrder(
                    entryStopId, tpPrice = tpP, slPrice = price, tpQty = tpQ, slQty = slQ,
                )
            } else {
                // The stop came from the entry order (place_order's slPrice), so
                // no position-level order exists yet — CREATE it.
                rest.placePositionTpSl(sym, positionId, tpPrice = null, slPrice = price)
            }
        } catch (e: Exception) {
            events.add("Risk manager: SL move on $sym FAILED — ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        // 2. Verify on the exchange before claiming anything. Read the
        //    POSITION-LEVEL order specifically: a partial TP/SL order carries its
        //    own slPrice and would otherwise be mistaken for the position's stop.
        val applied = runCatching {
            if (existing == null && entryStopId.isNotBlank()) {
                val orders = rest.pendingTpSlOrders(sym, positionId)
                (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
                    .firstOrNull { (it.optString("id").ifBlank { it.optString("orderId") }) == entryStopId }
                    ?.optString("slPrice")?.toDoubleOrNull()?.takeIf { it > 0 }
            } else findPositionSlPrice(sym, positionId)
        }.getOrNull()
        if (applied == null || applied <= 0) {
            events.add("Risk manager: SL on $sym NOT confirmed on the exchange — retrying")
            return false
        }
        // Relative tolerance: an absolute 0.005 is meaningless on a 0.00001-priced
        // pair and far too tight on a 100000-priced one.
        val tolerance = kotlin.math.max(1e-9, target * 1e-4)
        if (kotlin.math.abs(applied - target) > tolerance) {
            events.add("Risk manager: SL on $sym still ${fmt(applied)} — not ${fmt(sl)}, retrying")
            return false
        }
        // Confirmed by the exchange — only now is local state allowed to change.
        liveLastStop[sym] = applied
        events.add("SL moved to ${fmt(applied)} — $reason $sym (ROI ${fmt1(roi * 100)}%)")
        return true
    }

    /** The first usable slPrice in a TP/SL order list (null when there is none). */
    internal fun findSlPrice(orders: org.json.JSONArray): Double? {
        for (j in 0 until orders.length()) {
            val v = orders.getJSONObject(j).optString("slPrice").toDoubleOrNull()
            if (v != null && v > 0) return v
        }
        return null
    }

    /** Places the LIVE TP ladder; returns the note for the LIVE OPEN line. */
    internal fun placeLadder(
        symbol: String, positionId: String, dir: Int, basis: Double, rDist: Double, qty: String,
    ): String {
        val total = qty.toDoubleOrNull() ?: 0.0
        val n = ladderRungs.coerceIn(2, 6)
        val rungQty = formatQty(symbol, total / n)
            ?: return " | ladder skipped: 1/$n of qty is below the min volume"
        val each = rungQty.toDoubleOrNull() ?: 0.0
        val errs = mutableListOf<String>()
        var ok = 0
        for (r in 1..n) {
            val q = if (r < n) rungQty else formatQty(symbol, total - each * (n - 1)) ?: rungQty
            val px = if (dir > 0) basis + r * rDist else basis - r * rDist
            runCatching {
                rest.placeTpSlOrder(symbol, positionId, tpPrice = formatPrice(symbol, px), tpQty = q)
            }.onSuccess { ok++ }.onFailure { errs.add("${r}R: ${it.message ?: it.javaClass.simpleName}") }
        }
        return " | ladder $ok/$n rungs placed (qty $rungQty each)" +
            if (errs.isNotEmpty()) " — FAILED ${errs.joinToString("; ")}" else ""
    }

    /** Any pending TP/SL order on this position that holds a stop (null when none). */
    internal fun stopOrderFor(symbol: String, positionId: String): org.json.JSONObject? {
        val orders = rest.pendingTpSlOrders(symbol, positionId)
        for (j in 0 until orders.length()) {
            val o = orders.optJSONObject(j) ?: continue
            if (o.optString("positionId").let { it.isNotBlank() && it != positionId }) continue
            if ((o.optString("slPrice").toDoubleOrNull() ?: 0.0) > 0) return o
        }
        return null
    }

    /**
     * The stop armed on the POSITION-LEVEL TP/SL order, or null when the position
     * has none. This is the only order `modify_position_tp_sl_order` can change,
     * so it is the only one that proves a move landed.
     */
    internal fun findPositionSlPrice(symbol: String, positionId: String): Double? {
        val o = rest.positionTpSlOrder(symbol, positionId) ?: return null
        return o.optString("slPrice").toDoubleOrNull()?.takeIf { it > 0 }
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
                liveOpenCount = maxOf(0, liveOpenCount - 1)
                "Flash-close sent for $symbol (position $id)."
            } catch (e: Exception) {
                "Close failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val price = candles(symbol, PRICE_TIMEFRAME).lastOrNull()?.close ?: return "No price for $symbol."
        return broker.close(symbol, price) ?: "No open position on $symbol."
    }

    fun tradesHistory(): String = if (broker.history.isEmpty()) "No trades yet."
    else broker.historySnapshot().takeLast(20).joinToString("\n")

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

    /**
     * Starts the autonomous loop and installs [onEvent] as the event sink.
     * Callers that only need the timer should use [ensureLoop]; callers that turn
     * entries on should use [startTrading], which does both.
     */
    fun startLoop(intervalSec: Long, onEvent: (String) -> Unit) {
        this.onEvent = onEvent
        ensureLoop(intervalSec)
    }

    /**
     * Starts the loop if it is not already running, keeping the current event
     * sink.
     *
     * This is the single owner of the loop's lifecycle. `trading == true` must
     * always imply a running loop: the chat used to set the flag and claim
     * "Loop running" while only a Compose effect ever started the timer.
     */
    fun ensureLoop(intervalSec: Long = 30) {
        if (isLoopRunning) return
        loopTask = loopExecutor.scheduleWithFixedDelay({
            try {
                // The risk manager (breakeven, trailing) must
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
                events.forEach { onEvent?.invoke(it) }
            } catch (e: Throwable) {
                // Exchange/network failures are engine errors, never provider
                // errors. Writing them to AgentRuntimeError made /diagnose blame
                // the LLM and suggest switching models during a DNS outage.
                // Throwable, not Exception: an Error that escapes this thread
                // would kill the app outright.
                val friendly = NetworkErrors.describe(NetworkErrors.rawMessage(e), intervalSec)
                if (EngineRuntimeError.record(friendly)) {
                    onEvent?.invoke("Loop error: $friendly")
                }
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS)
        startReport()
    }

    /**
     * Turns entries on and guarantees the loop is running, so the reply "Loop
     * running" describes the real state instead of a promise.
     */
    fun startTrading(intervalSec: Long = 30): String {
        if (!engineOn) return "Turn the engine on first: engine on"
        trading = true
        ensureLoop(intervalSec)
        return "Trading started (${if (live) "LIVE — real orders" else "paper"}). Loop running: scan + manage."
    }

    /**
     * Stops new entries but leaves the loop alive while positions are open: the
     * risk manager (breakeven, trailing) must not abandon a
     * position the moment the user stops opening new ones.
     */
    fun stopTrading(): String {
        trading = false
        return if (hasOpenPositions()) {
            "Trading stopped — no new entries. Open positions stay managed " +
                "(breakeven, trailing)."
        } else {
            "Trading stopped — no new entries, nothing open."
        }
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
    private fun normalizeCommand(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.startsWith("min volume") -> "set $text"
            lower.startsWith("auto universe") -> "set $text"
            lower.startsWith("auto tpsl") -> "set $text"
            lower.startsWith("auto rank") -> "set $text"
            lower.startsWith("auto on") -> "set auto universe on"
            lower.startsWith("auto off") -> "set auto universe off"
            lower.startsWith("scan top") -> "set $text"
            lower.startsWith("leverage ") -> "set $text"
            lower.startsWith("risk ") -> "set $text"
            lower.startsWith("universe ") -> "set $text"
            else -> text
        }
    }

    /** The argument after [prefix] in the text's ORIGINAL casing, or "" when absent. */
    private fun argsOf(text: String, prefix: String): String =
        if (text.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
            text.substring(prefix.length).trim()
        } else ""

    fun handle(text: String): String {
        val raw = normalizeCommand(toAsciiDigits(text.trim()))
        val t = raw.lowercase()
        return when {
            t.startsWith("engine on") -> {
                engineOn = true
                "Engine on (${if (live) "LIVE" else "paper"} mode). Trading stays off until: start trading"
            }
            t.startsWith("engine off") -> {
                engineOn = false
                trading = false
                stopLoop()
                if (hasOpenPositions()) {
                    "Engine off. The management loop stopped too — open positions now have only their exchange SL/TP."
                } else {
                    "Engine off."
                }
            }
            t.startsWith("start trading") -> startTrading()
            t.startsWith("stop trading") -> stopTrading()
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
                        val open = runCatching {
                            val p = rest.pendingPositions()
                            reconcilePositions(p)
                            p.length()
                        }.getOrDefault(-1)
                        "No closed positions yet — history fills only after a trade opens AND closes." +
                            (if (open > 0) " Open now: $open — use 'positions'." else if (open == 0) " Nothing open right now." else "")
                    }
                    else "Closed positions (newest first) — these are NOT open; use 'positions' for open ones:\n" +
                        (0 until arr.length()).joinToString("\n") { i -> closedPositionLine(arr.getJSONObject(i)) }
                } catch (e: Exception) {
                    "History failed: ${e.message}"
                }
            } else "Paper mode: realized ${fmt(broker.realizedPnl)} USDT total. See trades_history."
            t.startsWith("positions") -> if (live) liveStatus() else
                (if (broker.snapshot().isEmpty()) "No open paper positions."
                else broker.snapshot().joinToString("\n") { e ->
                    val p = e.value
                    "${p.symbol} ${if (p.dir > 0) "LONG" else "SHORT"} qty ${fmt(p.qty)} entry ${fmt(p.entry)} stop ${p.stop?.let { fmt(it) } ?: "none"}"
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
            t.startsWith("tpsl_history") -> {
                val base = slPctForSignal(1.0)
                val stop = if (base > 0) {
                    "${fmt1(base)}% from entry" +
                        (if (autoTpsl) " — auto: scales with the signal's strength (set auto tpsl off or set sl 0 to change)" else " (set sl 0 for none)")
                } else "none (set sl <pct>)"
                "TP/SL: stop $stop, " +
                    "ladder 1R..4R each 25% of the stop distance, breakeven ${if (risk.breakevenRoiPct > 0) "at ${fmt0(risk.breakevenRoiPct)}% ROI" else "off"}, " +
                    "trail ${if (risk.trailingTriggerRoiPct > 0) "at ${fmt0(risk.trailingTriggerRoiPct)}% ROI, ${fmt0(risk.trailDistanceRoiPct)}% behind" else "off"}."
            }
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
                if (t.startsWith("tpsl ws") && liveFeedOn && fromWs.isNotEmpty()) {
                    "TP/SL (live WS, ${fromWs.size} events):\n" + fromWs.joinToString("\n") { o ->
                        "${o.optString("symbol")} ${o.optString("event")} ${o.optString("status")} " +
                            "tp ${o.optString("tpPrice")} sl ${o.optString("slPrice")} id ${o.optString("orderId")}"
                    }
                } else try {
                    val arr = rest.pendingTpSlOrders(limit = 50)
                    if (arr.length() == 0) "No pending TP/SL orders."
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val o = arr.getJSONObject(i)
                        // tpQty/slQty as the exchange holds them: shows whether a
                        // rung really closes part of the position or all of it.
                        "${o.optString("symbol")} tp ${o.optString("tpPrice")} tpQty ${o.optString("tpQty").ifBlank { "-" }} " +
                            "sl ${o.optString("slPrice")} slQty ${o.optString("slQty").ifBlank { "-" }} " +
                            "id ${o.optString("id").ifBlank { o.optString("orderId") }}"
                    }
                } catch (e: Exception) {
                    "TP/SL failed: ${e.message}"
                }
            } else if (broker.snapshot().isEmpty()) "No open paper positions, so no TP/SL plan yet."
            else broker.snapshot().joinToString("\n") { e ->
                val p = e.value
                val ladder = p.plan.ladder.mapIndexed { i, (price, frac) ->
                    val state = if (i < p.nextRung) "hit" else "open"
                    "${i + 1}R ${fmt(price)} (${fmt0(frac * 100)}%, $state)"
                }.joinToString(", ")
                "${p.symbol} ${if (p.dir > 0) "LONG" else "SHORT"} entry ${fmt(p.entry)} " +
                    "stop ${p.stop?.let { fmt(it) } ?: "none"} liq ${fmt(p.plan.liquidation)}\n" +
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
            t.startsWith("close position") -> {
                // No symbol, no trade: defaulting to BTCUSDT silently closed an
                // account the user never named.
                val symbol = argsOf(raw, "close position").uppercase()
                if (symbol.isEmpty()) {
                    val open = if (live) feedPositions() else broker.snapshot().map { it.key }
                    "Usage: close position <SYMBOL> — nothing was closed. Open: " +
                        open.joinToString(", ").ifEmpty { "(none)" }
                } else closePosition(symbol)
            }
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
                    "Leverage set to $v x. The exact limit for each symbol comes from its own live tier table (see: tiers)."
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
            t.startsWith("fire breakeven") || t.startsWith("fire trailing") -> {
                val rule = if (t.startsWith("fire breakeven")) ManualStopRule.BREAKEVEN else ManualStopRule.TRAILING
                val sym = argsOf(raw, if (rule == ManualStopRule.BREAKEVEN) "fire breakeven" else "fire trailing")
                    .trim().uppercase()
                fireStopRule(rule, sym)
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
            t.startsWith("set ladder") -> {
                val a = t.removePrefix("set ladder").trim()
                when {
                    a == "on" -> { ladderOn = true; configRev++; "LIVE TP ladder on: $ladderRungs rungs at 1R..${ladderRungs}R, 1/$ladderRungs of the position each. Applies to new entries." }
                    a == "off" -> { ladderOn = false; configRev++; "LIVE TP ladder off: one TP at ${tpR}R closes the whole position." }
                    a.startsWith("rungs") -> {
                        val v = extractNumber(a.removePrefix("rungs"))?.toInt()
                        if (v == null || v !in 2..6) "Usage: set ladder rungs <2-6>"
                        else { ladderRungs = v; configRev++; "Ladder rungs set to $v (1R..${v}R)." }
                    }
                    else -> "Usage: set ladder on | set ladder off | set ladder rungs <2-6>"
                }
            }
            t.startsWith("set tp r") || t.startsWith("set tp ") -> {
                val v = extractNumber(t.removePrefix("set tp r").removePrefix("set tp").trim().removePrefix("r"))
                if (v == null || v < 0.5 || v > 20.0) "Usage: set tp r <0.5-20> (TP = n× the stop distance; 1 = at the SL, 3 = 3× farther)"
                else {
                    tpR = v
                    configRev++
                    val slPct = risk.slPct
                    if (slPct > 0.0)
                        "TP set to ${v}× the stop distance (SL is ${fmt1(slPct)}% from entry)."
                    else
                        "TP set to ${v}× the stop distance. No user stop is set (set sl <pct>); with auto tpsl on, signal entries still get the automatic stop and this ladder on new entries."
                }
            }
            t.startsWith("set sl") -> {
                val v = extractNumber(t.removePrefix("set sl"))
                if (v == null || v !in 0.0..50.0) "Usage: set sl <pct> (stop distance from entry, % of price; 0 = none — signal entries still get the automatic stop)"
                else {
                    risk = risk.copy(slPct = v)
                    configRev++
                    if (v <= 0.0) "Stop off — new entries carry no user stop (signal entries still get the automatic stop; set auto tpsl off to disable it)."
                    else "Stop set to ${fmt1(v)}% from the entry."
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
                // Cut from the ORIGINAL text: parseTimeframesCsv is case-sensitive
                // and "1M" is a month while "1m" is one minute.
                val parsed = parseTimeframesCsv(argsOf(raw, "set timeframes"))
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
            t.startsWith("set auto tpsl") -> {
                val v = t.removePrefix("set auto tpsl").trim().lowercase()
                val on = v in listOf("on", "true", "1", "yes")
                val off = v in listOf("off", "false", "0", "no")
                if (!on && !off) {
                    "Usage: set auto tpsl on|off"
                } else {
                    autoTpsl = on
                    configRev++
                    if (autoTpsl)
                        "Automatic TP/SL ON — signal entries get the strength-scaled stop " +
                            "(the user's set sl base, or ${TpSlPlanner.AUTO_SL_BASE_PCT.toInt()}% when none set) plus the 1R–4R TP ladder."
                    else
                        "Automatic TP/SL OFF — entries carry the user's stop only (set sl <pct>)."
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
 * Position notional in quote currency: `qty × entry`. The tiered risk limit is
 * keyed on the position's ACTUAL value ("the maintenance margin rate will be
 * calculated based on the actual value of the position and order"), not on
 * price × leverage. Null when the qty or the entry price is missing/non-positive.
 */
internal fun posNotional(p: org.json.JSONObject): Double? {
    val qty = posQty(p).toDoubleOrNull() ?: return null
    val entry = posEntry(p).toDoubleOrNull() ?: return null
    if (qty <= 0.0 || entry <= 0.0) return null
    return qty * entry
}

/**
 * Liquidation price display. Per the docs, liqPrice <= 0 means the position is
 * at low risk and no liquidation price exists at that moment.
 */
internal fun liqText(raw: String): String {
    val v = raw.toDoubleOrNull() ?: return raw.ifBlank { "—" }
    return if (v <= 0.0) "— (low risk)" else raw
}

/**
 * One open live position for the chat context, using only fields the exchange
 * actually reports. Missing fields stay missing: a made-up leverage, stop or
 * PnL reads like a fact and is worse than "not shown".
 */
internal fun livePositionLine(p: JSONObject): String {
    val symbol = p.optString("symbol").ifBlank { "?" }
    val side = p.optString("side").uppercase().ifBlank { "?" }
    val qty = posQty(p).ifBlank { "?" }
    val entry = posEntry(p).ifBlank { "?" }
    // The exchange's own position value when it publishes one, else qty × entry.
    val notional = p.optString("entryValue").toDoubleOrNull()
        ?: qty.toDoubleOrNull()?.let { q ->
            entry.toDoubleOrNull()?.let { e -> q * e }
        }
    val leverage = p.optInt("leverage", 0)
    val upnl = p.optString("unrealizedPNL")
    return buildString {
        append("$symbol $side qty $qty @ $entry")
        if (notional != null) append(" · ${fmt2(notional)} USDT notional")
        if (leverage > 0) append(" · ${leverage}x")
        if (upnl.isNotBlank()) append(" · uPnL $upnl USDT")
        append(" · liq ${liqText(p.optString("liqPrice"))}")
    }
}

/**
 * One row of `positions history`: a CLOSED position. The exchange's `side` is
 * the direction it held (BUY = long, SELL = short), not a closing order, so it
 * is mapped before display — "VELVETUSDT BUY" used to read like an open buy.
 */
internal fun closedPositionLine(p: JSONObject): String {
    val side = when (p.optString("side").uppercase()) {
        "BUY", "LONG" -> "LONG"
        "SELL", "SHORT" -> "SHORT"
        else -> p.optString("side").ifBlank { "?" }
    }
    val realized = p.optString("realizedPNL").toDoubleOrNull() ?: 0.0
    val fee = p.optString("fee").toDoubleOrNull() ?: 0.0
    val funding = p.optString("funding").toDoubleOrNull() ?: 0.0
    return "${p.optString("symbol").ifBlank { "?" }} $side CLOSED — net " +
        "${fmt(netPositionPnl(realized, fee, funding))} USDT " +
        "(realized ${fmt(realized)} fee ${fmt(fee)} funding ${fmt(funding)})"
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
 * The tier a SELECTED leverage maps to, per the official rule: "the risk
 * limit tier of the position is calculated by the leverage the user selects"
 * (Bitunix example: 50x → tier 3). That is the largest tier level whose
 * maximum leverage still covers the selection — e.g. with the documented
 * BTC table, 200x → tier 1, 150x → tier 2, 100x → tier 3, 75x → tier 4.
 */
internal fun tierForLeverage(tiers: List<RiskTier>, wanted: Int): RiskTier? =
    tiers.filter { it.maxLeverage >= wanted }.maxByOrNull { it.level }

