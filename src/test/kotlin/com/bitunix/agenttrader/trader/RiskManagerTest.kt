package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.agent.CommandRouter
import com.bitunix.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Risk manager & exits: guard interval, max open positions, the user-configured
 * SL distance, breakeven/trailing and the tiered-risk-limit rules the live
 * entry path follows (no invented caps).
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

    @Test
    fun max_open_positions_also_caps_live_entries() {
        // The paper broker knows nothing about the real account, so LIVE must
        // cap on the EXCHANGE's open positions — otherwise the cap silently
        // does not apply exactly when real money is at stake.
        val e = engine()
        e.live = true
        e.maxOpenPositions = 2
        e.liveOpenCount = 1
        assertNull(e.riskBlock())
        e.liveOpenCount = 2
        val blocked = e.riskBlock()
        assertNotNull(blocked)
        assertTrue("max open positions" in blocked, blocked)
    }

    @Test
    fun the_paper_positions_snapshot_is_stable_while_the_loop_writes() {
        val e = engine()
        e.broker.open("BTCUSDT", 1, 100.0, 10)
        e.broker.open("ETHUSDT", 1, 100.0, 10)
        val snap = e.broker.snapshot()
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), snap.map { it.key })
        // A write after the snapshot must not change the copy the UI reads.
        e.broker.open("SOLUSDT", 1, 100.0, 10)
        assertEquals(2, snap.size)
        assertEquals(3, e.broker.positionCount())
    }

    // ---- the paper stop: the user's own SL ----

    @Test
    fun the_paper_stop_fires_at_the_configured_distance() {
        val e = engine()
        e.risk = e.risk.copy(slPct = 2.85) // stop at 97.15
        e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)

        // -2.85% price = -28.5% ROI at 10x: the stop fires at its own price.
        val events = e.broker.manage("BTCUSDT", 97.0, e.risk)
        assertTrue(events.any { it.startsWith("CLOSE BTCUSDT @ 97.15") }, events.toString())
    }

    @Test
    fun no_sl_configured_means_no_stop_at_all() {
        val e = engine()
        e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)
        val events = e.broker.manage("BTCUSDT", 80.0, e.risk) // -200% ROI, still no invented stop
        assertTrue(events.none { it.startsWith("CLOSE") }, events.toString())
        assertNotNull(e.broker.positions["BTCUSDT"])
    }

    @Test
    fun the_stop_loss_defaults_to_off() {
        val e = engine()
        assertEquals(0.0, e.risk.slPct, 1e-9)
    }

    // ---- the selected leverage, exactly as the user set it (paper) ----

    @Test
    fun paper_entry_uses_the_users_leverage() {
        val e = engine()
        e.leverage = 200
        val (lev, note) = e.entryLeverage("BTCUSDT")
        assertEquals(200, lev)
        assertNull(note)
    }

    // ---- the tiered risk limit: tier from the selected leverage ----

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

    private fun liveEngine(leverage: Int): TraderEngine {
        val e = engine()
        e.live = true
        e.seedRiskTiers("BTCUSDT", btcTiers())
        e.broker.balance = 266_666.67
        e.riskFraction = 0.1
        e.leverage = leverage
        return e
    }

    @Test
    fun tier_for_leverage_maps_to_the_highest_tier_covering_the_selection() {
        assertEquals(1, tierForLeverage(btcTiers(), 200)?.level) // 200x is tier 1 of the table
        assertEquals(3, tierForLeverage(btcTiers(), 100)?.level)
        assertEquals(4, tierForLeverage(btcTiers(), 75)?.level)
        assertEquals(5, tierForLeverage(btcTiers(), 50)?.level)
        assertNull(tierForLeverage(btcTiers(), 250)) // no tier covers it
    }

    @Test
    fun the_75x_tier_leaves_a_75x_entry_alone() {
        // 26 666.67 × 10% × 75 = 2 000 000 position value → tier 4, cap 4M — fine.
        val e = liveEngine(75)
        assertEquals(75, e.entryLeverage("BTCUSDT").first)
    }

    @Test
    fun a_small_position_may_use_the_symbol_maximum() {
        // 1000 × 10% × 200x = 20 000 position value — fits tier 1.
        val e = liveEngine(200)
        e.broker.balance = 1_000.0
        assertEquals(200, e.entryLeverage("BTCUSDT").first)
    }

    @Test
    fun live_refuses_when_the_position_would_exceed_the_tier_cap() {
        // 200x on 26 666.67 margin = 5.3M position value; the selection maps to
        // tier 1, capped at 100k USDT — the exchange would reject the order, so
        // the entry is refused here instead.
        val e = liveEngine(200)
        val (lev, note) = e.entryLeverage("BTCUSDT")
        assertEquals(0, lev)
        assertTrue(note != null && "tier 1" in note, note)
    }

    @Test
    fun live_refuses_when_no_tier_covers_the_selection() {
        // The documented BTC table caps at 125x; the configured leverage is
        // higher than every tier allows.
        val documented = parseRiskTiers(
            JSONArray("""[{"level":1,"startValue":"0","endValue":"150000","leverage":125,"maintenanceMarginRate":"0.004"}]"""),
        )
        val e = engine()
        e.live = true
        e.seedRiskTiers("BTCUSDT", documented)
        e.broker.balance = 1_000.0
        e.leverage = 150
        val (lev, note) = e.entryLeverage("BTCUSDT")
        assertEquals(0, lev)
        assertTrue(note != null && "exceeds every tier" in note, note)
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
        assertTrue(events.any { it.startsWith("PAPER breakeven BTCUSDT") }, events.toString())
        assertEquals(100.0, pos.stop!!, 1e-9)

        // +0.81% price = +8.1% ROI (0.8 is not exact in floating point — stay above it)
        // → trailing fires 5% ROI (0.5% price) behind the best.
        events = e.broker.manage("BTCUSDT", 100.81, e.risk)
        assertTrue(events.any { it.startsWith("PAPER trailing BTCUSDT") }, events.toString())
        assertEquals(100.81 * 0.995, pos.stop!!, 1e-6)

        // A pullback never widens the stop.
        e.broker.manage("BTCUSDT", 100.7, e.risk)
        assertEquals(100.81 * 0.995, pos.stop!!, 1e-6)
    }

    @Test
    fun breakeven_and_trailing_can_be_turned_off() {
        val e = engine()
        e.risk = e.risk.copy(breakevenRoiPct = 0.0, trailingTriggerRoiPct = 0.0)
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 10, e.risk)!!
        val events = e.broker.manage("BTCUSDT", 120.0, e.risk) // +200% ROI — nothing fires
        assertTrue(
            events.none { it.startsWith("PAPER breakeven") || it.startsWith("PAPER trailing") },
            events.toString(),
        )
        assertNull(pos.stop)
    }

    // ---- the user's SL distance ----

    @Test
    fun sl_distance_is_the_users_own_setting() {
        // slPct is a fraction of the price, exactly the user's choice.
        assertEquals(98.0, TpSlPlanner.plan(100.0, 1, 10, 0.02).stop!!, 1e-9)
        assertEquals(102.0, TpSlPlanner.plan(100.0, -1, 10, 0.02).stop!!, 1e-9)
        // 0 = no stop at all — not an invented default.
        assertNull(TpSlPlanner.plan(100.0, 1, 10, 0.0).stop)
    }

    // ---- chat commands & routing ----

    @Test
    fun risk_commands_update_the_engine_and_bump_configRev() {
        val e = engine()
        val before = e.configRev
        assertTrue(e.handle("set breakeven 5").contains("5"))
        assertTrue(e.handle("set trail trigger 8").contains("8"))
        assertTrue(e.handle("set trail distance 5").contains("5"))
        assertTrue(e.handle("set sl 2").contains("2"))
        assertTrue(e.handle("set max positions 4").contains("4"))
        assertTrue(e.handle("set guard 120").contains("120"))
        assertTrue(e.configRev > before, "configRev must bump")
        assertEquals(5.0, e.risk.breakevenRoiPct)
        assertEquals(8.0, e.risk.trailingTriggerRoiPct)
        assertEquals(5.0, e.risk.trailDistanceRoiPct)
        assertEquals(2.0, e.risk.slPct)
        assertEquals(4, e.maxOpenPositions)
        assertEquals(120L, e.guardIntervalSec)
    }

    @Test
    fun the_stop_can_be_turned_off() {
        val e = engine()
        assertTrue(e.handle("set sl 2").contains("2"))
        assertEquals(2.0, e.risk.slPct)
        assertTrue(e.handle("set sl 0").contains("off"))
        assertEquals(0.0, e.risk.slPct)
    }

    @Test
    fun risk_prefixes_route_to_the_engine_not_the_llm() {
        val cmds = listOf(
            "set breakeven 5", "set trail trigger 8", "set trail distance 5",
            "set sl 2", "set max positions 4",
            "set guard 120",
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

    // ---- SL trigger vs. mark (Bitunix error 30023) ----

    @Test
    fun long_sl_must_be_below_mark_and_short_sl_above() {
        // The reported failure: BLESSUSDT LONG, mark 0.019512, a stop derived
        // from the best price landed above mark and the exchange rejected it.
        assertTrue(slTriggerValid(1, 0.0190, 0.019512))
        assertTrue(!slTriggerValid(1, 0.0196, 0.019512)) // above mark → 30023
        assertTrue(slTriggerValid(-1, 0.0200, 0.019512))
        assertTrue(!slTriggerValid(-1, 0.0194, 0.019512)) // below mark → 30023
    }

    @Test
    fun sl_trigger_keeps_a_small_gap_from_mark() {
        // Equal to mark (or within the buffer) is rejected too — the mark moves
        // between our snapshot and the exchange's check.
        assertTrue(!slTriggerValid(1, 0.019512, 0.019512))
        assertTrue(!slTriggerValid(1, 0.019500, 0.019512)) // within 0.1%
        assertTrue(!slTriggerValid(-1, 0.019512, 0.019512))
    }

    @Test
    fun sl_trigger_rejects_missing_or_invalid_prices() {
        assertTrue(!slTriggerValid(1, 0.0, 0.019512))
        assertTrue(!slTriggerValid(1, 0.0190, 0.0))
        assertTrue(!slTriggerValid(1, Double.NaN, 0.019512))
    }
}
