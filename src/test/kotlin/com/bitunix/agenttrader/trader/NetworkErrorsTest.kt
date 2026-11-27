package com.bitunix.agenttrader.trader

import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DNS/network failures must be recognised, explained, and kept out of the provider error. */
class NetworkErrorsTest {

    private val dns = "Unable to resolve host \"fapi.bitunix.com\": No address associated with hostname"

    @BeforeTest
    fun reset() = EngineRuntimeError.clear()

    @AfterTest
    fun tearDown() = EngineRuntimeError.clear()

    @Test
    fun android_dns_failure_is_recognised() {
        assertTrue(NetworkErrors.isDns(dns))
        assertTrue(NetworkErrors.isNetwork(dns))
    }

    @Test
    fun dns_failure_is_found_through_the_cause_chain() {
        val wrapped = IOException("request failed", UnknownHostException(dns))
        assertTrue(NetworkErrors.isNetwork(wrapped))
        assertTrue(NetworkErrors.isExchange(wrapped))
        assertTrue(NetworkErrors.describe(NetworkErrors.rawMessage(wrapped)).contains("DNS"))
    }

    @Test
    fun timeouts_and_refused_connections_are_network_errors() {
        assertTrue(NetworkErrors.isNetwork("connect timed out"))
        assertTrue(NetworkErrors.isNetwork("Connection refused"))
        assertTrue(NetworkErrors.isNetwork("network is unreachable"))
    }

    @Test
    fun exchange_api_errors_are_not_network_errors() {
        assertFalse(NetworkErrors.isNetwork("Bitunix error 10001: insufficient balance"))
    }

    @Test
    fun provider_hosts_are_not_mistaken_for_the_exchange() {
        val provider = UnknownHostException("Unable to resolve host \"api.openai.com\": No address associated with hostname")
        assertTrue(NetworkErrors.isNetwork(provider))
        assertFalse(NetworkErrors.isExchange(provider))
    }

    @Test
    fun describe_dns_tells_the_user_to_check_the_internet() {
        val text = NetworkErrors.describe(dns, retrySec = 30)
        assertTrue(text.contains("DNS"), text)
        assertTrue(text.contains("internet connection"), text)
        assertTrue(text.contains("30s"), text)
        assertFalse(text.contains("model"), text)
        // The friendly text must itself stay classifiable for /diagnose.
        assertTrue(NetworkErrors.isNetwork(text), text)
    }

    @Test
    fun describe_keeps_a_non_network_message() {
        assertEquals("Bitunix error 10001: insufficient balance", NetworkErrors.describe("Bitunix error 10001: insufficient balance"))
    }

    @Test
    fun identical_errors_are_reported_once_per_window() {
        val t0 = 1_000_000L
        val msg = NetworkErrors.describe(dns, retrySec = 30)

        assertTrue(EngineRuntimeError.record(msg, t0))
        assertFalse(EngineRuntimeError.record(msg, t0 + 30_000))
        assertEquals(1, EngineRuntimeError.repeats)
        assertTrue(EngineRuntimeError.network)

        // After the five-minute window the same failure is shown again.
        assertTrue(EngineRuntimeError.record(msg, t0 + 5 * 60_000))
    }

    @Test
    fun a_new_error_is_reported_immediately() {
        val t0 = 2_000_000L
        assertTrue(EngineRuntimeError.record("first failure", t0))
        assertTrue(EngineRuntimeError.record("second failure", t0 + 1_000))
        assertEquals(0, EngineRuntimeError.repeats)
        assertEquals("second failure", EngineRuntimeError.last)
    }

    @Test
    fun clear_removes_the_engine_error() {
        EngineRuntimeError.record(NetworkErrors.describe(dns))
        EngineRuntimeError.clear()
        assertEquals("", EngineRuntimeError.last)
        assertFalse(EngineRuntimeError.network)
    }

    // ---- a stream that dies mid-response ----

    @Test
    fun an_eof_is_recognised_as_a_dropped_stream() {
        assertTrue(NetworkErrors.isStreamDrop("EOFException"))
        assertTrue(NetworkErrors.isStreamDrop("End of input"))
        assertTrue(NetworkErrors.isStreamDrop("unexpected end of stream"))
        assertTrue(NetworkErrors.isNetwork("EOFException"))
    }

    @Test
    fun an_eof_is_not_mistaken_for_a_bad_request() {
        // The request itself was fine; the connection was cut. That is a
        // network fault, not an exchange error and not a provider error.
        assertFalse(NetworkErrors.isExchange(EOFException("End of input")))
        assertFalse(NetworkErrors.isDns("EOFException"))
    }

    @Test
    fun describe_an_eof_says_the_stream_was_cut() {
        val text = NetworkErrors.describe(NetworkErrors.rawMessage(EOFException("End of input")), retrySec = 30)
        assertTrue(text.contains("dropped mid-response"), text)
        assertTrue(text.contains("30s"), text)
        // The friendly text must itself stay classifiable for /diagnose.
        assertTrue(NetworkErrors.isNetwork(text), text)
    }

    @Test
    fun an_eof_without_a_message_still_names_the_fault() {
        // EOFException carries "End of input" at best and nothing at all at
        // worst — the class name is what identifies it.
        val raw = NetworkErrors.rawMessage(EOFException())
        assertTrue(raw.contains("EOF"), raw)
        assertTrue(NetworkErrors.isStreamDrop(raw), raw)
    }

    @Test
    fun an_intermittent_failure_is_not_reported_again_after_a_good_tick() {
        // The bug this fixes: a flaky line failed, the next tick succeeded and
        // CLEARED the error state, so the next failure looked brand new and
        // printed the same line again — an endless stream of "Loop error".
        val t0 = 3_000_000L
        val msg = NetworkErrors.describe(NetworkErrors.rawMessage(EOFException("End of input")), 30)

        assertTrue(EngineRuntimeError.record(msg, t0))
        // A successful tick right after: the state must SURVIVE it.
        assertFalse(EngineRuntimeError.clearIfStale(t0 + 1_000))
        assertFalse(EngineRuntimeError.clearIfStale(t0 + 2_000))
        assertEquals(msg, EngineRuntimeError.last)
        // So the same failure is still suppressed.
        assertFalse(EngineRuntimeError.record(msg, t0 + 3_000))
    }

    @Test
    fun a_stale_error_is_cleared_once_the_window_passed() {
        val t0 = 4_000_000L
        EngineRuntimeError.record("some failure", t0)
        assertTrue(EngineRuntimeError.clearIfStale(t0 + 6 * 60_000))
        assertEquals("", EngineRuntimeError.last)
        // And with the state gone, the next failure is reported again.
        assertTrue(EngineRuntimeError.record("some failure", t0 + 6 * 60_000 + 1))
    }
}
