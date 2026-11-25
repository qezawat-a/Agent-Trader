package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The user types commands the way he talks ("min volume 24h usd 10000").
 * A strict number parse answered "Usage:" to those, which looked like the app
 * ignoring the chat, so the parsers have to read the sentence.
 */
class CommandParsingTest {

    private val engine = TraderEngine(BitunixRest("test-key", "test-secret"))

    @Test
    fun number_ignores_duration_tokens() {
        assertEquals(10000.0, extractNumber("24h usdt 10000"))
        assertEquals(0.0, extractNumber("24h usdt 0"))
        assertEquals(10.0, extractNumber("10"))
        assertEquals(20.0, extractNumber("20x"))
        assertEquals(24.0, extractNumber("24h"))
    }

    @Test
    fun amount_reads_million_suffix() {
        assertEquals(5_000_000.0, extractAmount("5m usdt"))
        assertEquals(5_000_000.0, extractAmount("5000000"))
        assertEquals(10000.0, extractAmount("24h usd 10000"))
        assertEquals(0.0, extractAmount("0"))
        assertEquals(2_000.0, extractAmount("2k"))
    }

    @Test
    fun engine_takes_a_spoken_min_volume() {
        engine.autoUniverse = false
        val out = engine.handle("min volume 24h usd 10000")
        assertEquals(10000.0, engine.autoMinVolumeUsd)
        assertTrue(out.contains("10000"), "reply should state the amount: $out")
    }

    @Test
    fun engine_takes_a_spoken_scan_top() {
        engine.autoUniverse = false
        engine.handle("scan top 12")
        assertEquals(12, engine.scanTop)
    }

    @Test
    fun universe_flags_symbols_the_exchange_does_not_list() {
        val known = setOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
        val check = matchUniverse(listOf("BTC", "RANK", "MOVERS", "ETHUSDT"), known)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), check.accepted)
        assertEquals(listOf("RANK", "MOVERS"), check.unknown)
    }

    @Test
    fun universe_keeps_known_symbols_as_they_are() {
        val known = setOf("BTCUSDT", "ETHUSDT")
        val check = matchUniverse(listOf("btcusdt"), known)
        assertEquals(listOf("BTCUSDT"), check.accepted)
        assertTrue(check.unknown.isEmpty())
    }
}
