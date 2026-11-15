package com.codeassist.agenttrader.trader

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
}
