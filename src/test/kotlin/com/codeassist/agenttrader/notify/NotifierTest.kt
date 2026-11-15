package com.codeassist.agenttrader.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which engine event lines deserve a status-bar notification. */
class NotifierTest {

    @Test
    fun trade_events_and_reports_are_notifiable() {
        assertTrue(notifiable("Opened LONG BTCUSDT 0.01 @ 68000"))
        assertTrue(notifiable("SL moved to 0.07579"))
        assertTrue(notifiable("Breakeven reached: stop moved to entry"))
        assertTrue(notifiable("Closed SHORT ETHUSDT, realized 12.5 USDT"))
        assertTrue(notifiable("Periodic report: balance 1040 USDT, positions 1, realized 5 USDT"))
        assertTrue(notifiable("WS order BTCUSDT CREATE LIMIT FILLED"))
        assertTrue(notifiable("WS tpsl BTCUSDT CLOSE FILLED"))
        assertTrue(notifiable("WS position BTCUSDT long qty 0.01 pnl 1.2"))
    }

    @Test
    fun ws_noise_stays_silent() {
        // The balance channel pushes on every account change — spam if notified.
        assertFalse(notifiable("WS balance USDT available 100 margin 10"))
        assertFalse(notifiable("WS: DNS lookup failed"))
        assertFalse(notifiable("WS closed: timeout"))
        assertFalse(notifiable(""))
    }

    @Test
    fun titles_match_the_event_kind() {
        assertEquals("Report", notificationTitle("Periodic report: balance 1040 USDT"))
        assertEquals("Order update", notificationTitle("WS order BTCUSDT CREATE LIMIT FILLED"))
        assertEquals("TP/SL update", notificationTitle("WS tpsl BTCUSDT CLOSE FILLED"))
        assertEquals("Position update", notificationTitle("WS position BTCUSDT long"))
        assertEquals("Agent Trader", notificationTitle("SL moved to 0.07579"))
    }
}
