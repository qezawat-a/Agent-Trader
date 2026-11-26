package com.codeassist.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The chat assembles its context before it can answer. If that context needed
 * a fresh signal evaluation, the chat would wait for ~30 REST calls — and the
 * evaluation used to hold the engine monitor, so the balance refresh waited
 * too. The result was a chat that never replied and a balance that never
 * showed.
 *
 * The chat must read the CACHED board. The engine's own loop keeps it fresh.
 */
class ChatContextTest {

    @Test
    fun the_chat_reads_the_cached_board_and_never_evaluates() {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.seedSignalCache(
            listOf(
                SignalRow("BTCUSDT", 100.0, emptyList(), 1, 70.0, "long", listOf("both sides agree")),
                SignalRow("ETHUSDT", 50.0, emptyList(), -1, 55.0, "short", listOf("weak")),
            ),
        )
        val brief = e.signalsBrief()
        assertTrue(brief.contains("BTCUSDT"), brief)
        assertTrue(brief.contains("ETHUSDT"), brief)
    }

    @Test
    fun an_empty_cache_gives_an_empty_brief_rather_than_an_evaluation() {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        // No cached rows: the brief must be empty, not a fresh sweep. A sweep
        // here is what made the chat wait for the exchange.
        assertTrue(e.signalsBrief().isEmpty())
        assertTrue(e.signalBoardCached().isEmpty())
    }
}
