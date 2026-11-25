package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.agent.CommandRouter
import com.bitunix.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Automatic TP/SL on signal entries: the stop is the fixed auto SL
 * (the user's `set sl` base, or the app's default base when unset) and the TP
 * ladder keeps its 1R–4R semantics. Manual entries keep the user's stop only.
 */
class AutoTpSlTest {

    private fun engine(): TraderEngine = TraderEngine(BitunixRest("k", "s"))

    // ---- the planner's stop: a fixed distance, not scaled by strength ----

    @Test
    fun sl_pct_at_keeps_the_users_base_whatever_the_strength() {
        assertEquals(2.0, TpSlPlanner.slPctAt(2.0, 1.0))
        assertEquals(2.0, TpSlPlanner.slPctAt(2.0, 0.5))
        assertEquals(0.0, TpSlPlanner.slPctAt(2.0, 0.0))
    }

    @Test
    fun sl_pct_at_uses_the_auto_base_when_the_user_set_none() {
        assertEquals(TpSlPlanner.AUTO_SL_BASE_PCT, TpSlPlanner.slPctAt(0.0, 1.0))
        assertEquals(TpSlPlanner.AUTO_SL_BASE_PCT, TpSlPlanner.slPctAt(0.0, 0.5))
        assertEquals(0.0, TpSlPlanner.slPctAt(0.0, 0.0))
    }

    // ---- the engine's resolution: user base, default, auto on/off ----

    @Test
    fun auto_on_uses_the_user_base_or_the_default() {
        val e = engine()
        // sl unset → the app's default base applies, whatever the confidence.
        assertEquals(TpSlPlanner.AUTO_SL_BASE_PCT, e.slPctForSignal(1.0))
        assertEquals(TpSlPlanner.AUTO_SL_BASE_PCT, e.slPctForSignal(0.5))
        assertEquals(0.0, e.slPctForSignal(0.0))
        // a set base applies as-is.
        e.risk = e.risk.copy(slPct = 4.0)
        assertEquals(4.0, e.slPctForSignal(1.0))
        assertEquals(4.0, e.slPctForSignal(0.5))
    }

    @Test
    fun auto_off_keeps_the_users_stop_only() {
        val e = engine()
        e.autoTpsl = false
        assertEquals(0.0, e.slPctForSignal(1.0))
        e.risk = e.risk.copy(slPct = 3.0)
        assertEquals(3.0, e.slPctForSignal(1.0))
        assertEquals(3.0, e.slPctForSignal(0.5))
        assertEquals(3.0, e.slPctForSignal(null)) // manual entry
    }

    @Test
    fun the_auto_stop_lands_on_signal_entries_the_way_the_planner_reads_it() {
        val e = engine()
        val riskAt = e.risk.copy(slPct = e.slPctForSignal(0.8))
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 10, riskAt)!!
        // default base 2.0% of the price → stop at 98.0 (no strength scaling)
        assertEquals(98.0, pos.stop!!, 0.001)
    }

    // ---- chat commands ----

    @Test
    fun set_auto_tpsl_toggles_and_bumps_configRev() {
        val e = engine()
        assertTrue(e.autoTpsl)
        val before = e.configRev
        assertTrue(e.handle("set auto tpsl off").contains("OFF"))
        assertFalse(e.autoTpsl)
        assertTrue(e.configRev > before, "configRev must bump")
        assertTrue(e.handle("set auto tpsl on").contains("ON"))
        assertTrue(e.autoTpsl)
        assertTrue(e.handle("set auto tpsl xyz").contains("Usage"))
    }

    @Test
    fun set_auto_tpsl_routes_to_the_engine() {
        assertTrue(CommandRouter.parse("set auto tpsl off") is CommandRouter.Parsed.Trader)
    }

    @Test
    fun tpsl_history_mentions_the_auto_state() {
        val e = engine()
        val text = e.handle("tpsl_history")
        assertTrue("auto" in text, text)
        e.handle("set auto tpsl off")
        assertFalse("scales with the signal's strength" in e.handle("tpsl_history"))
    }

    @Test
    fun settings_trade_shows_the_auto_flag() {
        val e = engine()
        val text = e.settingsTradeText()
        assertTrue("auto tpsl: on" in text, text)
    }
}
