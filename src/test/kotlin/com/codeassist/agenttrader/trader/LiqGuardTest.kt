package com.codeassist.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The liquidation guard is the BACKSTOP: it closes a position that the stop
 * could not protect.
 *
 * A flat ROI threshold only works at low leverage. The liquidation ROI is
 * −(1 − L·MMR), so at 75x with MMR 0.65% the exchange liquidates at −51.25%
 * while a −60% guard would only fire AFTERWARDS — the backstop would never
 * run at all, which is exactly where it is most needed.
 *
 * The guard is therefore anchored to the DISTANCE to the liquidation price:
 * it closes at 85% of the way there, whichever trigger comes first.
 */
class LiqGuardTest {

    /** BTCUSDT tier 4 (1M–4M notional, max 75x): MMR 0.65% — the live table. */
    private val mmr = 0.0065

    @Test
    fun the_guard_fires_before_the_exchange_liquidates_at_75x() {
        val entry = 100.0
        val lev = 75
        val liq = TpSlPlanner.liqPrice(entry, 1, lev, mmr)
        val liqRoi = TpSlPlanner.roi(entry, liq, 1, lev) * 100.0
        val threshold = TpSlPlanner.guardThresholdRoiPct(60.0, entry, liq, lev)
        // The flat -60% ROI the user asked for WOULD have been too late.
        assertTrue(-60.0 < liqRoi, "expected -60% to be too late; liquidation is at $liqRoi%")
        // The guard must close before the liquidation price.
        assertTrue(threshold > liqRoi, "guard at $threshold% but liquidation at $liqRoi%")
    }

    @Test
    fun the_stop_still_fires_before_the_guard() {
        val entry = 100.0
        val lev = 75
        val liq = TpSlPlanner.liqPrice(entry, 1, lev, mmr)
        val plan = TpSlPlanner.plan(entry, 1, lev, 0.30, mmr)
        val stopRoi = TpSlPlanner.roi(entry, plan.stop, 1, lev) * 100.0
        val guardRoi = TpSlPlanner.guardThresholdRoiPct(60.0, entry, liq, lev)
        // The guard is a backstop: the real stop must still win.
        assertTrue(stopRoi > guardRoi, "stop at $stopRoi% must come before the guard at $guardRoi%")
    }

    @Test
    fun the_users_roi_wins_when_it_is_already_safe() {
        // 20x with MMR 0.65%: the liquidation is far away, so −60% ROI is a
        // sensible, EARLIER trigger and must be respected as set.
        val entry = 100.0
        val lev = 20
        val liq = TpSlPlanner.liqPrice(entry, 1, lev, mmr)
        val liqRoi = TpSlPlanner.roi(entry, liq, 1, lev) * 100.0
        val threshold = TpSlPlanner.guardThresholdRoiPct(60.0, entry, liq, lev)
        assertEquals(-60.0, threshold, 1e-9)
        assertTrue(threshold > liqRoi, "the user's threshold must still be before the liquidation")
    }

    @Test
    fun a_short_is_guarded_the_same_way() {
        val entry = 100.0
        val lev = 75
        val liq = TpSlPlanner.liqPrice(entry, -1, lev, mmr)
        val liqRoi = TpSlPlanner.roi(entry, liq, -1, lev) * 100.0
        val threshold = TpSlPlanner.guardThresholdRoiPct(60.0, entry, liq, lev)
        assertTrue(threshold > liqRoi, "short guard at $threshold% but liquidation at $liqRoi%")
    }

    @Test
    fun the_paper_book_closes_before_the_liquidation_price() {
        val broker = PaperBroker()
        val risk = RiskSettings()
        broker.open("BTCUSDT", 1, 100.0, 75, risk, mmr = mmr)
        val p = broker.positions["BTCUSDT"]!!
        val liq = p.plan.liquidation
        // Still ABOVE the liquidation price, but past the guard's threshold.
        val price = 99.40
        assertTrue(price > liq, "the test price must still be above the liquidation ($liq)")
        val events = broker.manage("BTCUSDT", price, risk)
        assertTrue(events.any { it.startsWith("LIQ GUARD closed") }, events.toString())
        assertTrue(broker.positions["BTCUSDT"] == null, "the position must be closed")
    }
}
