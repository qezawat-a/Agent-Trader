package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LIVE and PAPER are separate accounts. The reported bug was LIVE showing the
 * simulated 1000 USDT (or whatever the paper broker held) instead of the
 * exchange balance, so every user-visible line must name its source.
 */
class BalanceDisplayTest {

    private fun engine(): TraderEngine = TraderEngine(BitunixRest("k", "s"))

    @Test
    fun paper_balance_is_labelled_paper() {
        val e = engine()
        assertEquals(1000.0, e.currentBalance())
        assertEquals("1000.00 USDT (paper)", e.balanceText())
        assertTrue("realized" in e.accountLine())
    }

    @Test
    fun live_without_an_exchange_answer_shows_unavailable_not_paper() {
        val e = engine()
        e.live = true

        assertEquals(0.0, e.currentBalance())
        assertEquals("unavailable (exchange)", e.balanceText())
        assertFalse("1000.00" in e.balanceText())
        assertFalse("paper" in e.balanceText())
        assertFalse("realized" in e.accountLine())
    }

    @Test
    fun live_uses_the_exchange_balance_after_a_seed_or_refresh() {
        val e = engine()
        e.live = true
        e.seedLiveBalance(12_345.678)

        assertEquals(12_345.678, e.currentBalance())
        assertEquals("12345.68 USDT (exchange)", e.balanceText())
        assertTrue("12345.68" in e.accountLine())
        assertFalse("1000.00" in e.accountLine())
    }

    @Test
    fun leaving_live_forgets_the_exchange_balance() {
        val e = engine()
        e.live = true
        e.seedLiveBalance(500.0)
        assertEquals(500.0, e.currentBalance())

        e.live = false
        assertNull(e.liveBalanceSnapshot())
        assertEquals("1000.00 USDT (paper)", e.balanceText())
    }

    @Test
    fun live_status_reports_the_exchange_not_the_paper_book() {
        val e = engine()
        e.live = true
        e.seedLiveBalance(500.0)

        val status = e.status()
        assertTrue("Balance: 500.00 USDT (exchange)" in status, status)
        assertFalse("Balance: 1000.00" in status, status)
    }

    @Test
    fun live_pnl_command_does_not_print_the_paper_account() {
        val e = engine()
        e.live = true

        val reply = e.handle("pnl")
        assertTrue("exchange" in reply, reply)
        assertFalse("PAPER (demo)" in reply, reply)
        assertFalse("1000.00" in reply, reply)
    }

    @Test
    fun refresh_is_a_no_op_outside_live_mode() {
        val e = engine()
        assertNull(e.refreshLiveBalance())
        assertNull(e.refreshLiveBalanceIfStale())
    }
}
