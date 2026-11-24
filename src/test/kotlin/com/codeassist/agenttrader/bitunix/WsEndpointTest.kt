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
    fun heartbeat_frames_are_acks_and_must_not_be_answered() {
        // Docs: the server replies to our ping with {"op":"ping","pong":...}.
        assertTrue(BitunixWs.isHeartbeat("ping"))
        assertTrue(BitunixWs.isHeartbeat("pong"))
        assertTrue(!BitunixWs.isHeartbeat("login"))
        assertTrue(!BitunixWs.isHeartbeat("subscribe"))
        // A frame with NO "op" is a DATA push ({"ch":"tickers","ts":...,"data":[...]}),
        // never a heartbeat — treating it as one would drop the whole feed.
        assertTrue(!BitunixWs.isHeartbeat(""))
    }

    @Test
    fun login_success_reads_data_result_not_top_level_code() {
        // Live failure frame observed from wss://fapi.bitunix.com/private/:
        // {"op":"login","data":{"msg":"invalid params，Authorize: Token invalid","result":false}}
        val reject = org.json.JSONObject(
            """{"op":"login","data":{"msg":"invalid params，Authorize: Token invalid","nonce":"abc","result":false}}""",
        )
        assertTrue(!BitunixWs.isLoginSuccess(reject))
        assertTrue(BitunixWs.loginReason(reject)!!.contains("Token invalid"))

        // Success carries no top-level code — must NOT be rejected.
        val ok = org.json.JSONObject("""{"op":"login","data":{"result":true}}""")
        assertTrue(BitunixWs.isLoginSuccess(ok))

        // Legacy/alternate shapes still work.
        assertTrue(BitunixWs.isLoginSuccess(org.json.JSONObject("{\"op\":\"login\",\"code\":0}")))
        assertTrue(!BitunixWs.isLoginSuccess(org.json.JSONObject("{\"op\":\"login\",\"code\":10007}")))
        assertTrue(BitunixWs.loginReason(org.json.JSONObject("{\"op\":\"login\",\"msg\":\"bad sign\"}")) == "bad sign")
    }

    @Test
    fun dns_failure_becomes_an_actionable_line() {
        val raw = "Unable to resolve host \"fapi.bitunix.com\": No address associated with hostname"
        val text = wsErrorText(raw)
        assertTrue(text.contains("DNS"), text)
        assertTrue(text.contains("internet"), text)
    }

    @Test
    fun server_side_drops_become_a_reconnecting_line() {
        assertEquals("WS closed by server — reconnecting", wsErrorText("EOFException"))
        assertEquals("WS closed by server — reconnecting", wsErrorText("Connection reset"))
        assertEquals("WS closed by server — reconnecting", wsErrorText("broken pipe"))
        assertEquals("WS closed by server — reconnecting", wsErrorText("Socket closed"))
        assertEquals("WS closed by server — reconnecting", wsErrorText("canceled"))
    }

    @Test
    fun other_errors_are_passed_through() {
        assertEquals("WS connection failed", wsErrorText(null))
        assertEquals("WS connection failed", wsErrorText("  "))
        assertTrue(wsErrorText("timeout").contains("timeout", ignoreCase = true))
    }
}
