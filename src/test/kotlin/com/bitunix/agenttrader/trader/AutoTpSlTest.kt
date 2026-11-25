package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.agent.CommandRouter
import com.bitunix.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatic TP/SL on signal entries: the agent (the model) sets SL and TP per
 * entry from the signal and the candles. No fixed %, and no fixed fallback:
 * no valid answer means no entry. Manual entries keep the user's stop only.
 */
class AutoTpSlTest {

    private fun engine(): TraderEngine = TraderEngine(BitunixRest("k", "s"))

    private fun ask(e: TraderEngine, dir: Int = 1) = e.agentTpSl(
        "BTCUSDT", dir, 100.0, 10, 80.0, emptyMap(), mapOf("15m" to TfSignal(dir, 80.0, 3)),
        TpSlPlanner.PAPER_SIMULATED_MMR,
    )

    @Test
    fun the_agent_answer_is_parsed_from_json() {
        val a = AgentTpSl.parse("sure: {\"sl\": 97.5, \"tp\": 106, \"reason\": \"below the swing low\"}")!!
        assertEquals(97.5, a.sl)
        assertEquals(106.0, a.tp)
        assertEquals("below the swing low", a.reason)
        assertNull(AgentTpSl.parse("no idea"))
    }

    @Test
    fun the_agent_levels_are_used_as_given() {
        val e = engine()
        e.tpslAdvisor = { """{"sl": 97, "tp": 109, "reason": "r"}""" }
        val (a, why) = ask(e)
        assertNull(why)
        assertEquals(97.0, a!!.sl)
        val (riskAt, tpR) = e.adviceToPlan(a, 100.0)!!
        assertEquals(3.0, riskAt.slPct, 1e-9) // 3% from entry
        assertEquals(3.0, tpR, 1e-9) // 9 / 3 = 3R
        val pos = e.broker.open("BTCUSDT", 1, 100.0, 10, riskAt, tpR = tpR)!!
        assertEquals(97.0, pos.stop!!, 1e-9)
        assertEquals(109.0, pos.plan.ladder.last().first, 1e-9)
    }

    @Test
    fun no_model_or_a_bad_answer_means_no_entry_never_a_fixed_stop() {
        val e = engine()
        assertNull(ask(e).first) // no advisor
        e.tpslAdvisor = { "nothing" }
        assertNull(ask(e).first)
        e.tpslAdvisor = { """{"sl": 101, "tp": 105}""" } // LONG stop above entry
        assertNotNull(ask(e).second)
        e.tpslAdvisor = { """{"sl": 80, "tp": 105}""" } // beyond liquidation at 10x
        assertTrue(ask(e).second!!.contains("liquidation"))
        e.tpslAdvisor = { error("offline") }
        assertTrue(ask(e).second!!.contains("model error"))
    }

    @Test
    fun a_manual_entry_without_agent_levels_is_not_opened() {
        val e = engine()
        val out = e.manualEntry("BTCUSDT", 1, 0.1)
        assertTrue(out.startsWith("SKIP"), out)
        assertTrue(e.broker.positions.isEmpty())
    }

    @Test
    fun an_empty_model_reply_is_reported_and_retried_once() {
        val e = engine()
        var calls = 0
        e.tpslAdvisor = { calls++; "" }
        val why = ask(e).second!!
        assertEquals(2, calls)
        assertTrue("empty reply" in why, why)
    }

    @Test
    fun manual_entries_keep_the_users_stop_only() {
        val e = engine()
        assertEquals(0.0, e.slPctForSignal(null))
        e.risk = e.risk.copy(slPct = 3.0)
        assertEquals(3.0, e.slPctForSignal(null))
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
        assertFalse("set by the agent" in e.handle("tpsl_history"))
    }

    @Test
    fun settings_trade_shows_the_auto_flag() {
        val e = engine()
        val text = e.settingsTradeText()
        assertTrue("auto tpsl: on" in text, text)
    }

    @Test
    fun a_stop_past_liquidation_is_sent_back_once_with_the_limits() {
        val e = engine()
        val seen = ArrayList<List<Pair<String, String>>>()
        e.tpslAdvisor = { msgs ->
            seen += msgs
            if (seen.size == 1) """{"sl": 80, "tp": 105}""" else """{"sl": 96, "tp": 106, "reason": "tighter"}"""
        }
        val (a, why) = ask(e)
        assertNull(why, why)
        assertEquals(96.0, a!!.sl)
        assertEquals(2, seen.size)
        assertTrue("liquidation" in seen[1].last().second, seen[1].last().second)
    }
}
