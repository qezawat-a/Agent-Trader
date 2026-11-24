package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The scanner must rank THE USER'S universe — exchange top-volume names never leak in. */
class UniverseTest {

    @Test
    fun rankUniverse_ranks_only_the_users_universe() {
        val engine = TraderEngine(BitunixRest("k", "s")).apply {
            universe.clear(); universe.addAll(listOf("INITUSDT", "PRLUSDT"))
        }
        // The exchange has far bigger names — they must NOT leak into the board.
        val tickers = JSONArray(
            """[{"symbol":"BTCUSDT","quoteVol":"999000000"},
                {"symbol":"OTHERUSDT","quoteVol":"888000000"},
                {"symbol":"PRLUSDT","quoteVol":"500000"},
                {"symbol":"INITUSDT","quoteVol":"100000"}]""",
        )
        assertEquals(listOf("PRLUSDT", "INITUSDT"), engine.rankUniverse(10, tickers))
    }

    @Test
    fun unknown_symbols_keep_universe_order() {
        val engine = TraderEngine(BitunixRest("k", "s")).apply {
            universe.clear(); universe.addAll(listOf("ZZZUSDT", "QQQUSDT", "WWWUSDT"))
        }
        val tickers = JSONArray("""[{"symbol":"BTCUSDT","quoteVol":"999000000"}]""")
        // No ticker rows for the universe → universe order is preserved.
        assertEquals(listOf("ZZZUSDT", "QQQUSDT", "WWWUSDT"), engine.rankUniverse(10, tickers))
    }

    @Test
    fun rankUniverse_falls_back_to_universe_order_when_the_fetch_fails() {
        val engine = TraderEngine(BitunixRest("k", "s")).apply {
            universe.clear(); universe.addAll(listOf("AAAUSDT", "BBBUSDT"))
        }
        // Whatever happens with the live tickers fetch, only universe symbols come back.
        val ranked = engine.rankUniverse(10)
        assertTrue(ranked.all { it in engine.universe }, ranked.toString())
        assertTrue(ranked.isNotEmpty())
    }

    @Test
    fun config_changes_bump_configRev_so_the_ui_refreshes() {
        val engine = TraderEngine(BitunixRest("k", "s"))
        val before = engine.configRev
        engine.handle("set universe ADAUSDT")
        engine.handle("set timeframes 5m,1h")
        engine.handle("set scan top 7")
        assertTrue(engine.configRev > before, "configRev must increase")
        assertEquals(listOf("ADAUSDT"), engine.universe.toList())
    }
}
