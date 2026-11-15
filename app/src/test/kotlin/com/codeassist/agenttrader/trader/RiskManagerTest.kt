package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.agent.CommandRouter
import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Risk manager & exits (v1.1.7): guard interval, max open positions,
 * user-configurable breakeven/trailing/initial stop, the liquidation guard
 * (closes before the exchange can liquidate) and the entry-side liq buffer.
 */
class RiskManagerTest {

    private fun engine(): TraderEngine = TraderEngine(BitunixRest("k", "s"))

    // ---- guard interval ----

    @Test
    fun guard_interval_blocks_entries_right_after_one() {
        val e = engine()
        e.guardIntervalSec = 300
        e.lastEntryAt = System.currentTimeMillis()
        val blocked = e.riskBlock()
        assertNotNull(blocked)
        assertTrue("guard interval" in blocked, blocked)
    }

    @Test
    fun guard_interval_expires_after_its_window() {
        val e = engine()
        e.guardIntervalSec = 300
        e.lastEntryAt = System.currentTimeMillis() - 301_000
        assertNull(e.riskBlock())
    }

    @Test
    fun noteEntry_arms_the_guard_interval() {
        val e = engine()
        assertEquals(0L, e.lastEntryAt)
        e.noteEntry()
        assertTrue(e.lastEntryAt > 0)
    }

    // ---- max open positions ----

    @Test
    fun max_open_positions_blocks_paper_entries_at_the_cap() {
        val e = engine()
        e.maxOpenPositions = 2
        e.broker.open("BTCUSDT", 1, 100.0, 10)
        e.broker.open("ETHUSDT", 1, 100.0, 10)
        val blocked = e.riskBlock()
        assertNotNull(blocked)
        assertTrue("max open positions" in blocked, blocked)
    }

    // ---- liquidation guard (paper broker) ----

    @Test
    fun liq_guard_closes_a_crashing_position_before_its_stop() {
        val e = engine()
        // Stop far toward liquidation (would fire at -90% ROI); the guard floor
        // sits at -60% — the guard must close FIRST, at the market price.
        e.risk = e.risk.copy(stopFractionOfLiqPct = 90.0, liqGuardRoiPct = 60.0)
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)!!
        val balanceBefore = e.broker.balance

        // -120% ROI at 10x: past the guard, past the stop.
        val events = e.broker.manage("BTCUSDT", 88.0, e.risk)
        assertTrue(events.any { it.startsWith("LIQ GUARD closed BTCUSDT") }, events.toString())
        assertNull(e.broker.positions["BTCUSDT"])
        // Closed at the market price, not at the optimistic stop price:
        // margin 80 (10% of 1000, minus the fee buffer) → qty 8 at 10x,
        // so the loss is 8 * (100 - 88) = 96 USDT.
        assertTrue(e.broker.balance < balanceBefore, "the loss must be realized")
        assertEquals(904.0, e.broker.balance, 0.01)
        assertTrue(e.broker.history.any { it.startsWith("PARTIAL BTCUSDT -") }, e.broker.history.toString())
        assertTrue(pos.qty <= 1e-12)
    }

    @Test
    fun liq_guard_warns_once_then_closes() {
        val e = engine()
        e.risk = e.risk.copy(stopFractionOfLiqPct = 95.0, liqGuardRoiPct = 60.0)
        e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)

        // -48% ROI: inside the warn zone (-45%..-60%) — one warning, no close.
        val warn = e.broker.manage("BTCUSDT", 95.2, e.risk)
        assertTrue(warn.any { it.startsWith("LIQ GUARD WARNING") }, warn.toString())
        assertNotNull(e.broker.positions["BTCUSDT"])

        // The warning fires once; a second tick at the same price stays silent.
        val again = e.broker.manage("BTCUSDT", 95.2, e.risk)
        assertTrue(again.none { it.startsWith("LIQ GUARD") }, again.toString())

        // -61% ROI: the guard closes at market, before the exchange could.
        val close = e.broker.manage("BTCUSDT", 93.9, e.risk)
        assertTrue(close.any { it.startsWith("LIQ GUARD closed BTCUSDT") }, close.toString())
        assertNull(e.broker.positions["BTCUSDT"])
    }

    @Test
    fun liq_guard_off_leaves_the_stop_in_charge() {
        val e = engine()
        e.risk = e.risk.copy(liqGuardRoiPct = 0.0)
        e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)
        val events = e.broker.manage("BTCUSDT", 88.0, e.risk)
        assertTrue(events.none { it.startsWith("LIQ GUARD") }, events.toString())
        // The ordinary stop took the position out at its own price.
        assertTrue(events.any { it.startsWith("CLOSE BTCUSDT") }, events.toString())
    }

    @Test
    fun a_healthy_stop_fires_before_the_guard() {
        val e = engine()
        // Default stop frac 30 (-30% ROI) vs guard 60 (-60% ROI): the stop wins.
        e.risk = e.risk.copy(stopFractionOfLiqPct = 30.0, liqGuardRoiPct = 60.0)
        e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)
        // The stop sits at 30% of the liq distance (100 × (1/10 − 0.005) = 9.5)
        // → 97.15. The price crosses it.
        val events = e.broker.manage("BTCUSDT", 97.0, e.risk)
        assertTrue(events.none { it.startsWith("LIQ GUARD") }, events.toString())
        assertTrue(events.any { it.startsWith("CLOSE BTCUSDT @ 97.15") }, events.toString())
    }

    // ---- entry-side liquidation buffer ----

    @Test
    fun the_liq_buffer_is_off_by_default() {
        // A default that silently lowers the leverage the user chose is wrong:
        // 5% buffer + 0.65% MMR capped a 75x account at 17x without asking.
        val e = engine()
        assertEquals(0.0, e.liqBufferPct, 1e-9)
    }

    @Test
    fun liq_buffer_caps_entry_leverage() {
        val e = engine()
        e.liqBufferPct = 2.0
        e.leverage = 125
        val (lev, note) = e.entryLeverage("BTCUSDT")
        // 2% of room + MMR 0.5% → 1/leverage ≥ 2.5% → at most 40x.
        assertEquals(40, lev)
        assertNotNull(note)
        assertTrue("clamped 125x → 40x" in note, note)
    }

    @Test
    fun liq_buffer_leaves_low_leverage_alone() {
        val e = engine()
        e.liqBufferPct = 2.0
        e.leverage = 20
        val (lev, note) = e.entryLeverage("BTCUSDT")
        assertEquals(20, lev)
        assertNull(note)
    }

    @Test
    fun liq_buffer_off_means_no_clamp() {
        val e = engine()
        e.liqBufferPct = 0.0
        e.leverage = 200
        val (lev, note) = e.entryLeverage("BTCUSDT")
        assertEquals(200, lev)
        assertNull(note)
    }

    // ---- the tiered risk limit: the exchange's own leverage ceiling ----

    /** The live BTCUSDT tier table (read from the exchange). */
    private fun btcTiers() = parseRiskTiers(
        JSONArray(
            """[{"level":1,"startValue":"0","endValue":"100000","leverage":200,"maintenanceMarginRate":"0.003"},
                {"level":2,"startValue":"100000","endValue":"400000","leverage":150,"maintenanceMarginRate":"0.004"},
                {"level":3,"startValue":"400000","endValue":"1000000","leverage":100,"maintenanceMarginRate":"0.005"},
                {"level":4,"startValue":"1000000","endValue":"4000000","leverage":75,"maintenanceMarginRate":"0.0065"},
                {"level":5,"startValue":"4000000","endValue":"10000000","leverage":50,"maintenanceMarginRate":"0.01"}]""",
        ),
    )

    @Test
    fun the_75x_tier_leaves_a_75x_entry_alone() {
        // The case that bit the user: 75x available, and the position lands in
        // the tier that ALLOWS 75x. Nothing may downgrade it.
        val e = engine()
        e.seedRiskTiers("BTCUSDT", btcTiers())
        e.broker.balance = 266_666.67
        e.riskFraction = 0.1
        e.liqBufferPct = 0.0
        e.leverage = 75
        val (lev, note) = e.entryLeverage("BTCUSDT")
        // 266 666.67 × 10% × 75 = 2 000 000 notional → the 1M–4M tier.
        assertEquals(75, lev)
        assertNull(note)
    }

    @Test
    fun a_tier_that_forbids_the_leverage_is_respected() {
        // 200x on a 2M notional: the 4M–10M tier (the notional 200x would
        // reach) allows only 50x, and the 1M–4M tier allows 75x — so 75x is
        // the largest the exchange accepts for this margin.
        val e = engine()
        e.seedRiskTiers("BTCUSDT", btcTiers())
        e.broker.balance = 266_666.67
        e.riskFraction = 0.1
        e.liqBufferPct = 0.0
        e.leverage = 200
        val (lev, note) = e.entryLeverage("BTCUSDT")
        assertEquals(75, lev)
        assertNotNull(note)
        assertTrue("clamped 200x → 75x" in note, note)
    }

    @Test
    fun a_small_position_may_use_the_symbol_maximum() {
        val e = engine()
        e.seedRiskTiers("BTCUSDT", btcTiers())
        e.broker.balance = 1000.0
        e.riskFraction = 0.1
        e.liqBufferPct = 0.0
        e.leverage = 200
        val (lev, note) = e.entryLeverage("BTCUSDT")
        // 1000 × 10% × 200 = 20 000 notional → the first tier, which allows 200x.
        assertEquals(200, lev)
        assertNull(note)
    }

    @Test
    fun the_rate_comes_from_the_tier_the_position_occupies() {
        // 5% buffer, 75x wanted. The PRE-CAP notional (2M) sits in the tier
        // with MMR 0.65%, but the position that actually opens (17x → 453k)
        // sits in the tier with MMR 0.50%. The rate must be the one the
        // position occupies — reading the pre-cap tier made the app believe
        // it had less room than it has, and capped the leverage one step too low.
        val e = engine()
        e.seedRiskTiers("BTCUSDT", btcTiers())
        e.broker.balance = 266_666.67
        e.riskFraction = 0.1
        e.liqBufferPct = 5.0
        e.leverage = 75
        val (lev, note) = e.entryLeverage("BTCUSDT")
        assertEquals(17, lev)
        assertNotNull(note)
        assertTrue("MMR 0.5%" in note, note)
    }

    // ---- chat commands & routing ----

    // ---- breakeven & trailing (paper broker) ----

    @Test
    fun breakeven_and_trailing_use_the_configured_levels() {
        val e = engine()
        e.risk = e.risk.copy(breakevenRoiPct = 5.0, trailingTriggerRoiPct = 8.0, trailDistanceRoiPct = 5.0)
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)!!

        // +0.5% price = +5% ROI at 10x → breakeven: SL to entry.
        var events = e.broker.manage("BTCUSDT", 100.5, e.risk)
        assertTrue(events.any { it.startsWith("Breakeven BTCUSDT") }, events.toString())
        assertEquals(100.0, pos.stop, 1e-9)

        // +0.81% price = +8.1% ROI (0.8 is not exact in floating point — stay above it)
        // → trailing fires 5% ROI (0.5% price) behind the best.
        events = e.broker.manage("BTCUSDT", 100.81, e.risk)
        assertTrue(events.any { it.startsWith("Trailing BTCUSDT") }, events.toString())
        assertEquals(100.81 * 0.995, pos.stop, 1e-6)

        // A pullback never widens the stop.
        e.broker.manage("BTCUSDT", 100.7, e.risk)
        assertEquals(100.81 * 0.995, pos.stop, 1e-6)
    }

    @Test
    fun breakeven_and_trailing_can_be_turned_off() {
        val e = engine()
        e.risk = e.risk.copy(breakevenRoiPct = 0.0, trailingTriggerRoiPct = 0.0)
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)!!
        val events = e.broker.manage("BTCUSDT", 120.0, e.risk) // +200% ROI — nothing fires
        assertTrue(events.none { it.startsWith("Breakeven") || it.startsWith("Trailing") }, events.toString())
        assertEquals(pos.plan.stop, pos.stop, 1e-9)
    }

    // ---- initial stop fraction ----

    @Test
    fun stop_fraction_is_user_configurable() {
        // The liq distance is Entry × (1/leverage − MMR) = 100 × (0.1 − 0.005) = 9.5,
        // so liq is at 90.5 and 30% of the way to it is 97.15.
        assertEquals(97.15, TpSlPlanner.plan(100.0, 1, 10, 0.30).stop, 1e-9)
        assertEquals(102.85, TpSlPlanner.plan(100.0, -1, 10, 0.30).stop, 1e-9)
        // Defaults keep the long-standing 50% behavior.
        assertEquals(95.25, TpSlPlanner.plan(100.0, 1, 10).stop, 1e-9)
    }

    // ---- chat commands & routing ----

    @Test
    fun risk_commands_update_the_engine_and_bump_configRev() {
        val e = engine()
        val before = e.configRev
        assertTrue(e.handle("set breakeven 5").contains("5"))
        assertTrue(e.handle("set trail trigger 8").contains("8"))
        assertTrue(e.handle("set trail distance 5").contains("5"))
        assertTrue(e.handle("set stop frac 30").contains("30"))
        assertTrue(e.handle("set max positions 4").contains("4"))
        assertTrue(e.handle("set liq guard 70").contains("70"))
        assertTrue(e.handle("set liq buffer 3").contains("3"))
        assertTrue(e.handle("set guard 120").contains("120"))
        assertTrue(e.configRev > before, "configRev must bump")
        assertEquals(5.0, e.risk.breakevenRoiPct)
        assertEquals(8.0, e.risk.trailingTriggerRoiPct)
        assertEquals(5.0, e.risk.trailDistanceRoiPct)
        assertEquals(30.0, e.risk.stopFractionOfLiqPct)
        assertEquals(4, e.maxOpenPositions)
        assertEquals(70.0, e.risk.liqGuardRoiPct)
        assertEquals(3.0, e.liqBufferPct)
        assertEquals(120L, e.guardIntervalSec)
    }

    @Test
    fun liq_guard_can_be_turned_off() {
        val e = engine()
        assertTrue(e.handle("set liq guard 0").contains("off"))
        assertEquals(0.0, e.risk.liqGuardRoiPct)
        assertTrue(e.handle("set liq buffer 0").contains("off"))
        assertEquals(0.0, e.liqBufferPct)
    }

    @Test
    fun risk_prefixes_route_to_the_engine_not_the_llm() {
        val cmds = listOf(
            "set breakeven 5", "set trail trigger 8", "set trail distance 5",
            "set stop frac 30", "set max positions 4", "set liq guard 60",
            "set liq buffer 2", "set guard 120",
        )
        for (cmd in cmds) {
            assertTrue(CommandRouter.parse(cmd) is CommandRouter.Parsed.Trader, cmd)
        }
    }

    @Test
    fun tpsl_history_reflects_the_configured_risk() {
        val e = engine()
        e.handle("set breakeven 5")
        e.handle("set trail trigger 8")
        e.handle("set trail distance 5")
        val text = e.handle("tpsl_history")
        assertTrue("breakeven at 5% ROI" in text, text)
        assertTrue("trail at 8% ROI, 5% behind" in text, text)
        assertTrue("breakeven off" !in text, text)
        // Turning them off shows it honestly.
        e.handle("set breakeven 0")
        e.handle("set trail trigger 0")
        val off = e.handle("tpsl_history")
        assertTrue("breakeven off" in off, off)
        assertTrue("trail off" in off, off)
    }

    @Test
    fun settings_trade_shows_the_risk_manager_lines() {
        val e = engine()
        e.handle("set guard 60")
        val text = e.handle("settings trade")
        assertTrue("risk manager" in text.lowercase(), text)
        assertTrue("breakeven" in text.lowercase(), text)
        assertTrue("60s" in text, text)
    }
}
