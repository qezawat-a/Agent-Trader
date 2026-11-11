package com.codeassist.agenttrader.bitunix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WsEndpointTest {

    @Test
    fun endpoints_match_the_official_docs() {
        assertEquals("wss://fapi.bitunix.com/public/", BitunixWs.WS_PUBLIC_URL)
        assertEquals("wss://fapi.bitunix.com/private/", BitunixWs.WS_PRIVATE_URL)
    }

    @Test
    fun ping_uses_unix_seconds_not_milliseconds() {
        val frame = org.json.JSONObject(BitunixWs.pingFrame(1_732_519_687_999))
        assertEquals("ping", frame.getString("op"))
        assertEquals(1_732_519_687L, frame.getLong("ping"))
    }

    @Test
    fun dns_failure_becomes_an_actionable_line() {
        val raw = "Unable to resolve host \"fapi.bitunix.com\": No address associated with hostname"
        val text = wsErrorText(raw)
        assertTrue(text.contains("DNS"), text)
        assertTrue(text.contains("internet"), text)
    }

    @Test
    fun other_errors_are_passed_through() {
        assertEquals("Connection reset", wsErrorText("Connection reset"))
        assertEquals("WS connection failed", wsErrorText(null))
        assertEquals("WS connection failed", wsErrorText("  "))
        assertTrue(wsErrorText("timeout").contains("timeout", ignoreCase = true))
    }
}
