package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The position size must be the size the user set — in PAPER exactly as in
 * LIVE.
 *
 * The paper path opened every signal entry with a fixed 10% margin, whatever
 * "margin per trade" said, and both paths then divided the balance by the
 * number of free position slots: a 50% setting silently became 33% with three
 * open slots and kept shrinking as the book filled. The position never grew.
 */
class PositionSizeConsistencyTest {

    private fun engine(): TraderEngine = TraderEngine(BitunixRest("k", "s"))

    @Test
    fun a_paper_signal_entry_uses_the_configured_margin_per_trade() {
        val e = engine()
        e.riskFraction = 0.5
        val pos = e.openPaper("BTCUSDT", 1, 100.0, 10, e.risk)
        assertNotNull(pos)
        // 50% of the 1000 USDT free balance = 500 USDT margin at 10x = 50 coins.
        assertEquals(500.0, pos.qty * pos.entry / pos.leverage, 1e-9)
        assertEquals(50.0, pos.qty, 1e-9)
    }

    @Test
    fun the_chat_setting_reaches_the_paper_entry() {
        val e = engine()
        e.handle("set risk 50")
        assertEquals(0.5, e.riskFraction, 1e-9)
        val pos = e.openPaper("BTCUSDT", 1, 100.0, 10, e.risk)
        assertNotNull(pos)
        assertEquals(500.0, pos.qty * pos.entry / pos.leverage, 1e-9)
    }

    @Test
    fun the_paper_book_reserves_the_margin_like_the_exchange() {
        val e = engine()
        e.riskFraction = 0.5
        e.openPaper("BTCUSDT", 1, 100.0, 10, e.risk)
        // 500 of 1000 is locked by the position: 500 is free.
        assertEquals(500.0, e.broker.committedMargin(), 1e-9)
        assertEquals(500.0, e.broker.availableMargin(), 1e-9)

        val second = e.openPaper("ETHUSDT", 1, 100.0, 10, e.risk)
        assertNotNull(second)
        // The second entry is a share of what is LEFT: 50% of 500 = 250.
        assertEquals(250.0, second.qty * second.entry / second.leverage, 1e-9)
        // The book never commits more than the account holds.
        assertTrue(e.broker.committedMargin() <= e.broker.balance + 1e-9)
    }

    @Test
    fun the_position_cap_does_not_change_the_size() {
        val e = engine()
        e.maxOpenPositions = 3
        e.riskFraction = 0.5
        val pos = e.openPaper("BTCUSDT", 1, 100.0, 10, e.risk)
        assertNotNull(pos)
        assertEquals(500.0, pos.qty * pos.entry / pos.leverage, 1e-9)
    }

    @Test
    fun closing_a_position_returns_its_margin() {
        val e = engine()
        e.riskFraction = 0.5
        e.openPaper("BTCUSDT", 1, 100.0, 10, e.risk)
        assertEquals(500.0, e.broker.availableMargin(), 1e-9)
        e.broker.close("BTCUSDT", 100.0)
        assertEquals(0.0, e.broker.committedMargin(), 1e-9)
        assertEquals(1000.0, e.broker.availableMargin(), 1e-9)
    }

    @Test
    fun the_engine_and_the_sizing_rule_agree() {
        val e = engine()
        e.riskFraction = 0.35
        val free = e.broker.availableMargin()
        val pos = e.openPaper("BTCUSDT", 1, 250.0, 20, e.risk)
        assertNotNull(pos)
        val margin = pos.qty * pos.entry / pos.leverage
        assertEquals(MarginSizing.compute(free, 35.0), margin, 1e-9)
    }

    @Test
    fun the_paper_account_line_shows_the_free_balance() {
        val e = engine()
        e.riskFraction = 0.5
        e.openPaper("BTCUSDT", 1, 100.0, 10, e.risk)
        val line = e.accountLine()
        assertTrue("free 500.00" in line, line)
    }
}