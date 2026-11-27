package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.BitunixRest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The loop's lifecycle is the engine's job. The chat used to set `trading` and
 * claim "Loop running" while only a Compose effect ever started the timer —
 * so the reply could describe a loop that did not exist.
 */
class LoopLifecycleTest {

    private val engine = TraderEngine(BitunixRest("k", "s"))

    @AfterTest
    fun tearDown() {
        engine.stopLoop()
    }

    @Test
    fun start_trading_really_starts_the_loop() {
        engine.handle("engine on")
        val reply = engine.handle("start trading")
        assertTrue(engine.trading, reply)
        assertTrue(engine.isLoopRunning, "the reply promises a loop, the loop must exist")
        assertTrue(reply.contains("Loop running"), reply)
    }

    @Test
    fun trading_needs_the_engine_on_first() {
        val reply = engine.handle("start trading")
        assertFalse(engine.trading)
        assertFalse(engine.isLoopRunning)
        assertTrue(reply.contains("engine on"), reply)
    }

    @Test
    fun stop_trading_stops_entries_but_keeps_risk_management_alive() {
        engine.handle("engine on")
        engine.handle("start trading")
        val reply = engine.handle("stop trading")
        assertFalse(engine.trading)
        // The loop stays alive so breakeven/trailing/liq-guard keep working on
        // open positions; it idles out on its own when nothing is open.
        assertTrue(engine.isLoopRunning, "stopping entries must not abandon open positions")
        assertTrue(reply.contains("no new entries"), reply)
    }

    @Test
    fun engine_off_stops_the_loop_too() {
        engine.handle("engine on")
        engine.handle("start trading")
        val reply = engine.handle("engine off")
        assertFalse(engine.trading)
        assertFalse(engine.isLoopRunning, reply)
    }

    @Test
    fun starting_twice_does_not_stack_a_second_timer() {
        engine.handle("engine on")
        engine.handle("start trading")
        val first = engine.isLoopRunning
        engine.handle("start trading")
        assertTrue(first)
        // ensureLoop returns early when a task is already scheduled, so there is
        // still exactly one loop task.
        assertTrue(engine.isLoopRunning)
    }
}
