package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.agent.CommandRouter
import com.bitunix.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Control screen's "Fire breakeven" / "Fire trailing" buttons must move
 * the stop NOW — they bypass the ROI trigger, but they never widen a stop and
 * they never invent one for a position that is not open.
 */
class ManualFireTest {

    private fun engine(): TraderEngine = TraderEngine(BitunixRest("k", "s"))

    @Test
    fun fire_breakeven_moves_the_paper_stop_to_entry_without_the_roi_trigger() {
        val e = engine()
        // No ROI rule is armed at all — the button is the trigger.
        e.risk = e.risk.copy(breakevenRoiPct = 0.0, trailingTriggerRoiPct = 0.0)
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 5, e.risk.copy(slPct = 2.0))!!
        assertEquals(98.0, pos.stop!!, 0.001)

        val out = e.fireStopRule(ManualStopRule.BREAKEVEN, "BTCUSDT")
        assertTrue("moved to entry" in out, out)
        assertEquals(100.0, pos.stop!!, 0.001)
    }

    @Test
    fun fire_breakeven_never_widens_a_stop_that_is_already_tighter() {
        val e = engine()
        e.risk = e.risk.copy(breakevenRoiPct = 0.0)
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 5, e.risk.copy(slPct = 2.0))!!
        // A trailing stop already ABOVE the entry (for a LONG) is tighter than
        // breakeven: moving it back to the entry would give risk back.
        pos.stop = 105.0
        val out = e.fireStopRule(ManualStopRule.BREAKEVEN, "BTCUSDT")
        assertTrue("already tighter" in out, out)
        assertEquals(105.0, pos.stop!!, 0.001)
    }

    @Test
    fun fire_trailing_uses_the_best_price_and_the_trail_distance() {
        val e = engine()
        e.risk = e.risk.copy(trailingTriggerRoiPct = 0.0, trailDistanceRoiPct = 10.0)
        // No user stop → no TP ladder, so the position stays open while the
        // price moves up.
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 5, e.risk.copy(slPct = 0.0))!!
        // Best price 110 with 5x leverage → 10% ROI distance = 2% price distance → stop 107.8.
        e.broker.manage("BTCUSDT", 110.0, e.risk.copy(breakevenRoiPct = 0.0, trailingTriggerRoiPct = 0.0))
        val out = e.fireStopRule(ManualStopRule.TRAILING, "BTCUSDT")
        assertTrue("moved to" in out, out)
        assertEquals(107.8, pos.stop!!, 0.001)
    }

    @Test
    fun fire_trailing_refuses_when_the_distance_is_off() {
        val e = engine()
        e.risk = e.risk.copy(trailDistanceRoiPct = 0.0)
        e.broker.open("BTCUSDT", 1, 100.0, 5, e.risk.copy(slPct = 2.0))
        val out = e.fireStopRule(ManualStopRule.TRAILING, "BTCUSDT")
        assertTrue("distance is off" in out, out)
    }

    @Test
    fun fire_without_a_symbol_needs_exactly_one_open_position() {
        val e = engine()
        val out = e.fireStopRule(ManualStopRule.BREAKEVEN, "")
        assertTrue("Usage" in out, out)

        e.broker.open("BTCUSDT", 1, 100.0, 5, e.risk.copy(slPct = 2.0))
        val one = e.fireStopRule(ManualStopRule.BREAKEVEN, "")
        assertTrue("moved to entry" in one, one)
    }

    @Test
    fun the_chat_command_fires_the_rule_too() {
        val e = engine()
        e.risk = e.risk.copy(breakevenRoiPct = 0.0, trailingTriggerRoiPct = 0.0)
        e.broker.open("BTCUSDT", 1, 100.0, 5, e.risk.copy(slPct = 2.0))
        val out = e.handle("fire breakeven BTCUSDT")
        assertTrue("moved to entry" in out, out)
    }

    @Test
    fun the_fire_commands_reach_the_engine_from_chat() {
        assertTrue(CommandRouter.parse("fire breakeven") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("fire trailing") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("fire breakeven BTCUSDT") is CommandRouter.Parsed.Trader)
        assertTrue(CommandRouter.parse("fire trailing ETHUSDT") is CommandRouter.Parsed.Trader)
    }
}
