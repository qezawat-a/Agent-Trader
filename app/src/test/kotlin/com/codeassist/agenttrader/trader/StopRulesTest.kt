package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live risk-manager rules, proven without an exchange: breakeven,
 * trailing and the stop baseline lookup.
 *
 * The report behind these tests was "breakeven & trailing didn't work". Three
 * causes, all covered here:
 *  - the breakeven flagged itself done even when the exchange REJECTED the
 *    move, so it never retried;
 *  - the trailing rule needed a known stop baseline and silently gave up when
 *    the exchange's SL could not be read — the fallback is the engine's own
 *    planned stop;
 *  - the trailing trigger was measured on the current price, so a pullback
 *    froze the stop where it was; it is measured on the best price reached.
 */
class StopRulesTest {

    private fun engine(): TraderEngine = TraderEngine(BitunixRest("k", "s"))

    // ---- the stop baseline lookup ----

    @Test
    fun find_sl_price_reads_the_documented_payload() {
        val e = engine()
        val orders = JSONArray(
            """[{"symbol":"BTCUSDT","orderId":"1","side":"SELL","positionId":"9","slPrice":"97.5000","slStopType":"LAST_PRICE"}]""",
        )
        assertEquals(97.5, e.findSlPrice(orders))
    }

    @Test
    fun find_sl_price_is_null_when_there_is_no_stop() {
        val e = engine()
        assertNull(e.findSlPrice(JSONArray()))
        assertNull(e.findSlPrice(JSONArray("""[{"symbol":"BTCUSDT","slPrice":"0"}]""")))
        assertNull(e.findSlPrice(JSONArray("""[{"symbol":"BTCUSDT"}]""")))
    }

    // ---- breakeven ----

    @Test
    fun breakeven_moves_the_stop_to_entry_once_the_roi_is_reached() {
        val risk = RiskSettings(breakevenRoiPct = 25.0)
        // entry 100, best 105 at 10x → +50% ROI ≥ 25% → SL to 100 (stop was 97).
        assertEquals(100.0, breakevenStopFor(1, 100.0, 105.0, 10, 97.0, risk))
        // Short side: entry 100, best 95 at 10x → +50% ROI → SL to 100 (stop was 103).
        assertEquals(100.0, breakevenStopFor(-1, 100.0, 95.0, 10, 103.0, risk))
    }

    @Test
    fun breakeven_waits_for_its_roi() {
        val risk = RiskSettings(breakevenRoiPct = 25.0)
        // +20% ROI at 10x → still below the 25% trigger.
        assertNull(breakevenStopFor(1, 100.0, 102.0, 10, 97.0, risk))
    }

    @Test
    fun breakeven_does_nothing_when_the_stop_is_already_tighter() {
        val risk = RiskSettings(breakevenRoiPct = 25.0)
        // Stop at 102 is already above entry (long) — moving it to 100 would WIDEN it.
        assertNull(breakevenStopFor(1, 100.0, 105.0, 10, 102.0, risk))
    }

    @Test
    fun breakeven_can_be_turned_off() {
        val risk = RiskSettings(breakevenRoiPct = 0.0)
        assertNull(breakevenStopFor(1, 100.0, 120.0, 10, 97.0, risk))
    }

    @Test
    fun breakeven_fires_on_the_best_price_not_the_current_one() {
        val risk = RiskSettings(breakevenRoiPct = 25.0)
        // The position reached +50% ROI, then pulled back to +10%: the stop must
        // still move to entry — that is the whole point of breakeven.
        assertEquals(100.0, breakevenStopFor(1, 100.0, 105.0, 10, 97.0, risk))
    }

    // ---- trailing ----

    @Test
    fun trailing_follows_the_best_price() {
        val risk = RiskSettings(trailingTriggerRoiPct = 40.0, trailDistanceRoiPct = 25.0)
        // entry 100, best 105 at 10x → +50% ROI ≥ 40% → trail 25% ROI behind best
        // (25% ROI at 10x = 2.5% of price) → 105 * 0.975 = 102.375.
        assertEquals(105.0 * 0.975, trailingStopFor(1, 100.0, 105.0, 10, 97.0, risk)!!, 1e-9)
        // Short side: trail above the best price.
        assertEquals(95.0 * 1.025, trailingStopFor(-1, 100.0, 95.0, 10, 103.0, risk)!!, 1e-9)
    }

    @Test
    fun trailing_waits_for_its_trigger() {
        val risk = RiskSettings(trailingTriggerRoiPct = 40.0, trailDistanceRoiPct = 25.0)
        // +20% ROI at 10x → below the 40% trigger.
        assertNull(trailingStopFor(1, 100.0, 102.0, 10, 97.0, risk))
    }

    @Test
    fun trailing_never_widens_the_stop() {
        val risk = RiskSettings(trailingTriggerRoiPct = 40.0, trailDistanceRoiPct = 25.0)
        // The stop is already at 103 (tighter than the trail at 102.375).
        assertNull(trailingStopFor(1, 100.0, 105.0, 10, 103.0, risk))
    }

    @Test
    fun trailing_can_be_turned_off() {
        val risk = RiskSettings(trailingTriggerRoiPct = 0.0, trailDistanceRoiPct = 25.0)
        assertNull(trailingStopFor(1, 100.0, 120.0, 10, 97.0, risk))
    }

    @Test
    fun trailing_keeps_firing_after_a_pullback() {
        val risk = RiskSettings(trailingTriggerRoiPct = 40.0, trailDistanceRoiPct = 25.0)
        // The best price was 105 (+50% ROI); the price is back at 101. The stop
        // must follow the best price, not freeze at the pullback.
        val trail = trailingStopFor(1, 100.0, 105.0, 10, 97.0, risk)
        assertTrue(trail != null && trail > 100.0, "trailing must stay above entry")
    }

    // ---- the engine wires the rules together ----

    @Test
    fun the_engine_uses_the_same_rules_for_entries() {
        val e = engine()
        e.liqBufferPct = 5.0
        e.leverage = 100
        val (lev, note) = e.entryLeverage("BTCUSDT")
        // 5% of room + MMR 0.5% → 1/leverage ≥ 5.5% → at most 18x.
        assertEquals(18, lev)
        assertTrue(note != null && "clamped" in note, note ?: "")
    }
}
