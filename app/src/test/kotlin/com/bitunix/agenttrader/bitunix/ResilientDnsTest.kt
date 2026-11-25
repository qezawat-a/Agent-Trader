package com.bitunix.agenttrader.bitunix

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResilientDnsTest {
    private val ip = InetAddress.getByAddress("fapi.bitunix.com", byteArrayOf(1, 2, 3, 4))
    private val failing = Dns { throw UnknownHostException("Unable to resolve host \"$it\"") }

    @Test
    fun parses_a_dns_json_answer() {
        val body = """{"Status":0,"Answer":[{"name":"x","type":5,"data":"cname."},{"name":"x","type":1,"data":"104.18.1.2"}]}"""
        assertEquals(listOf("104.18.1.2"), ResilientDns.parseDohAnswer(body))
    }

    @Test
    fun falls_back_to_doh_then_to_the_last_good_address() {
        var systemUp = true
        var dohUp = true
        val dns = ResilientDns(
            system = Dns { if (systemUp) listOf(ip) else throw UnknownHostException(it) },
            doh = { if (dohUp) listOf(ip) else emptyList() },
        )
        assertEquals(listOf(ip), dns.lookup("fapi.bitunix.com"))
        systemUp = false
        assertEquals(listOf(ip), dns.lookup("fapi.bitunix.com")) // via DoH
        dohUp = false
        assertEquals(listOf(ip), dns.lookup("fapi.bitunix.com")) // cached
    }

    @Test
    fun fails_when_nothing_ever_resolved() {
        val dns = ResilientDns(system = failing, doh = { emptyList() })
        assertFailsWith<UnknownHostException> { dns.lookup("fapi.bitunix.com") }
    }
}
