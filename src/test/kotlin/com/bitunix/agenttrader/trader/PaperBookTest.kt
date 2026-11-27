package com.bitunix.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The paper book's arithmetic. The account is the number the user reads in the
 * chat, so a partial take-profit followed by a close must add up to exactly the
 * move the price made — not to the move plus the part already booked.
 */
class PaperBookTest {

    /** 1000 USDT, 10x, 10% margin, 2% stop → 10 units at 100, ladder 102/104/106/108. */
    private fun book(): PaperBroker = PaperBroker(1_000.0).also {
        it.open("BTCUSDT", 1, 100.0, 10, RiskSettings(slPct = 2.0), marginFraction = 0.10)
    }

    @Test
    fun opening_commits_the_configured_share_of_the_free_balance() {
        val b = book()
        val p = b.positions["BTCUSDT"]!!
        assertEquals(10.0, p.qty, 1e-9)
        assertEquals(100.0, b.committedMargin(), 1e-9)
        assertEquals(900.0, b.availableMargin(), 1e-9)
    }

    @Test
    fun a_full_close_after_a_partial_take_profit_books_only_the_remainder() {
        val b = book()
        // TP1 at 102 takes 25% off: +2 × 2.5 = +5.
        b.manage("BTCUSDT", 102.0, RiskSettings(slPct = 2.0))
        assertEquals(1_005.0, b.balance, 1e-9)
        assertEquals(7.5, b.positions["BTCUSDT"]!!.qty, 1e-9)

        // Closing the rest at the same price must add 2 × 7.5 = +15, not
        // 2 × 10 = +20 (which booked the closed quarter a second time).
        b.close("BTCUSDT", 102.0)
        assertEquals(1_020.0, b.balance, 1e-9)
        assertEquals(20.0, b.realizedPnl, 1e-9)
        assertNull(b.positions["BTCUSDT"])
    }

    @Test
    fun the_whole_ladder_adds_up_to_the_full_move_and_closes_the_position() {
        val b = book()
        val events = b.manage("BTCUSDT", 108.0, RiskSettings(slPct = 2.0))
        assertEquals(4, events.count { it.startsWith("TP") }, events.toString())
        // 2 + 4 + 6 + 8 = 20 per unit × 10 units = 50.
        assertEquals(50.0, b.realizedPnl, 1e-9)
        assertEquals(1_050.0, b.balance, 1e-9)
        assertNull(b.positions["BTCUSDT"])
        assertEquals(0.0, b.committedMargin(), 1e-9)
    }

    @Test
    fun unrealized_pnl_and_margin_follow_the_remaining_quantity() {
        val b = book()
        b.manage("BTCUSDT", 102.0, RiskSettings(slPct = 2.0))
        // 7.5 units × +3 = +22.5, on 75 USDT of margin left.
        assertEquals(22.5, b.unrealized(mapOf("BTCUSDT" to 103.0)), 1e-9)
        assertEquals(75.0, b.committedMargin(), 1e-9)
    }

    @Test
    fun a_close_without_a_position_is_a_no_op() {
        val b = book()
        b.close("BTCUSDT", 100.0)
        val balance = b.balance
        assertNull(b.close("BTCUSDT", 110.0))
        assertEquals(balance, b.balance, 1e-9)
    }

    @Test
    fun an_empty_or_zero_sized_account_opens_nothing() {
        assertNull(PaperBroker(0.0).open("BTCUSDT", 1, 100.0, 10))
        assertNull(book().openSized("ETHUSDT", 1, 100.0, 10, marginFraction = 0.0))
    }

    @Test
    fun the_paper_book_reserves_margin_so_two_entries_cannot_commit_it_twice() {
        val b = PaperBroker(1_000.0)
        val first = b.open("BTCUSDT", 1, 100.0, 10, marginFraction = 0.60)
        val second = b.open("ETHUSDT", 1, 100.0, 10, marginFraction = 0.60)
        assertNotNull(first)
        // 60% of 1000 = 600 committed; the second takes 60% of the 400 left.
        assertEquals(600.0, first .qty * first.entry / first.leverage, 1e-9)
        assertEquals(240.0, second!!.qty * second.entry / second.leverage, 1e-9)
        assertTrue(b.committedMargin() <= b.balance + 1e-9)
    }
}
