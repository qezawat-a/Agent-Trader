package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat commands that change engine state (no network involved — the engine
 * is only asked to update its own settings).
 */
class EngineCommandTest {

    private val engine = TraderEngine(BitunixRest("k", "s"))

    @Test
    fun tp_distance_command_sets_the_r_multiple() {
        val reply = engine.handle("set tp r 3")
        assertEquals(3.0, engine.tpR)
        assertTrue(reply.contains("3"))
    }

    @Test
    fun tp_distance_out_of_range_is_rejected() {
        engine.handle("set tp r 99")
        assertEquals(1.0, engine.tpR)
    }

    @Test
    fun tf_agree_command_sets_the_majority_gate() {
        engine.handle("set timeframes 15m,1h,4h")
        engine.handle("set tf agree 2")
        assertEquals(2, engine.minSideTfs)
        assertTrue(engine.settingsTradeText().contains("2 of 3 timeframes"))
    }

    @Test
    fun tf_agree_cannot_exceed_the_number_of_timeframes() {
        engine.handle("set timeframes 15m,1h")
        engine.handle("set tf agree 5")
        assertEquals(Int.MAX_VALUE, engine.minSideTfs)
    }

    @Test
    fun timeframes_keep_their_case_because_1m_and_1M_are_different() {
        // Regression: the argument used to be cut from a lowercased copy, so a
        // monthly candle "1M" became the one-minute "1m".
        val reply = engine.handle("set timeframes 15m,1M")
        assertEquals(listOf("15m", "1M"), engine.timeframes)
        assertTrue(reply.contains("15m, 1M"), reply)
    }

    @Test
    fun close_position_without_a_symbol_closes_nothing() {
        // Regression: a missing symbol defaulted to BTCUSDT and silently closed
        // a position the user never named.
        val reply = engine.handle("close position")
        assertTrue(reply.contains("Usage:"), reply)
        assertTrue(reply.contains("nothing was closed"), reply)
    }

    @Test
    fun close_position_matches_case_insensitively() {
        // The prefix match must not depend on the model writing lowercase.
        val reply = engine.handle("Close Position")
        assertTrue(reply.contains("Usage:"), reply)
        assertTrue(reply.contains("nothing was closed"), reply)
    }
}
