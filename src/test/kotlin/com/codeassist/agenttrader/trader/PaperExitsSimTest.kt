package com.codeassist.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PAPER mode end to end: a manual entry, then the engine's own loop tick()
 * with prices from the (fake) exchange ticker, exactly like the app's loop.
 * Nothing is called by hand: ladder, breakeven and trailing must fire alone.
 */
class PaperExitsSimTest {

    private fun paper(fake: FakeBitunix, be: Double, trigger: Double, dist: Double) =
        TraderEngine(fake).apply {
            live = false
            leverage = 50
            guardIntervalSec = 0
            risk = risk.copy(slPct = 1.0, breakevenRoiPct = be, trailingTriggerRoiPct = trigger, trailDistanceRoiPct = dist)
        }

    @Test
    fun paper_ladder_closes_a_quarter_per_rung_by_itself() {
        val fake = FakeBitunix(100.0)
        val e = paper(fake, 0.0, 0.0, 25.0)
        assertTrue("PAPER OPEN" in e.manualEntry("SSVUSDT", 1, 0.10))
        val pos = e.broker.positions["SSVUSDT"]!!
        val q0 = pos.qty
        for ((price, left) in listOf(101.5 to 0.75, 102.5 to 0.50, 103.5 to 0.25)) {
            fake.move(price)
            val ev = e.tick()
            assertTrue(ev.any { it.startsWith("TP") }, "rung at $price: $ev")
            assertEquals(q0 * left, pos.qty, q0 * 1e-6, "qty at $price")
        }
        fake.move(104.5)
        e.tick()
        assertNull(e.broker.positions["SSVUSDT"], "4R closes the rest")
    }

    @Test
    fun paper_breakeven_fires_by_itself_in_the_loop() {
        val fake = FakeBitunix(100.0)
        val e = paper(fake, 25.0, 0.0, 25.0)
        e.manualEntry("SSVUSDT", 1, 0.10)
        val pos = e.broker.positions["SSVUSDT"]!!
        assertEquals(99.0, pos.stop!!, 1e-9)
        fake.move(100.6) // +30% ROI at 50x
        val ev = e.tick()
        assertTrue(ev.any { "breakeven" in it }, ev.toString())
        assertEquals(100.0, pos.stop!!, 1e-9)
        // Price falls back to entry: closed at breakeven, not at the old stop.
        fake.move(99.95)
        e.tick()
        assertNull(e.broker.positions["SSVUSDT"])
    }

    @Test
    fun paper_trailing_follows_the_best_price_by_itself() {
        val fake = FakeBitunix(100.0)
        val e = paper(fake, 25.0, 40.0, 25.0)
        e.manualEntry("SSVUSDT", 1, 0.10)
        val pos = e.broker.positions["SSVUSDT"]!!
        fake.move(100.9) // +45% ROI → trailing on, 25% ROI behind = 0.5% price
        val ev = e.tick()
        assertTrue(ev.any { "trailing" in it }, ev.toString())
        assertEquals(100.9 * (1 - 0.005), pos.stop!!, 1e-6)
        fake.move(100.95)
        e.tick()
        assertEquals(100.95 * (1 - 0.005), pos.stop!!, 1e-6)
        // A pullback never loosens the stop.
        fake.move(100.6)
        e.tick()
        assertEquals(100.95 * (1 - 0.005), pos.stop!!, 1e-6)
    }
}
