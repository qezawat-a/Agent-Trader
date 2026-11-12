package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.agent.CommandRouter
import com.codeassist.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Risk manager & exits (v1.1.7): guard interval, max open positions, daily loss
 * cap, user-configurable breakeven/trailing/initial stop, and live routing.
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

    // ---- daily loss cap ----

    @Test
    fun daily_loss_cap_blocks_new_entries_for_the_day() {
        val e = engine()
        e.dayStamp = e.todayStamp() // today already baselined
        e.dayStartBalance = 1000.0
        e.broker.balance = 900.0 // -10%
        e.maxDailyLossPct = 5.0
        val blocked = e.riskBlock()
        assertNotNull(blocked)
        assertTrue("daily loss cap" in blocked, blocked)
    }

    @Test
    fun daily_loss_baseline_resets_when_the_utc_day_rolls() {
        val e = engine()
        e.dayStamp = "2000-01-01" // stale stamp → baseline re-arms from current balance
        e.dayStartBalance = 1000.0
        e.broker.balance = 900.0
        assertNull(e.riskBlock())
    }

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
        // 30% of the way to liquidation: liq at 90 → stop at 97.
        assertEquals(97.0, TpSlPlanner.plan(100.0, 1, 10, 0.30).stop, 1e-9)
        assertEquals(103.0, TpSlPlanner.plan(100.0, -1, 10, 0.30).stop, 1e-9)
        // Defaults keep the long-standing 50% behavior.
        assertEquals(95.0, TpSlPlanner.plan(100.0, 1, 10).stop, 1e-9)
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
        assertTrue(e.handle("set daily loss 5").contains("5"))
        assertTrue(e.handle("set guard 120").contains("120"))
        assertTrue(e.configRev > before, "configRev must bump")
        assertEquals(5.0, e.risk.breakevenRoiPct)
        assertEquals(8.0, e.risk.trailingTriggerRoiPct)
        assertEquals(5.0, e.risk.trailDistanceRoiPct)
        assertEquals(30.0, e.risk.stopFractionOfLiqPct)
        assertEquals(4, e.maxOpenPositions)
        assertEquals(5.0, e.maxDailyLossPct)
        assertEquals(120L, e.guardIntervalSec)
    }

    @Test
    fun risk_prefixes_route_to_the_engine_not_the_llm() {
        val cmds = listOf(
            "set breakeven 5", "set trail trigger 8", "set trail distance 5",
            "set stop frac 30", "set max positions 4", "set daily loss 5", "set guard 120",
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
        assertTrue("5% ROI" in text, text)
        assertTrue("8% ROI" in text, text)
        assertTrue("off" !in text, text)
        // Turning them off shows it honestly.
        e.handle("set breakeven 0")
        e.handle("set trail trigger 0")
        assertTrue("off" in e.handle("tpsl_history"))
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
