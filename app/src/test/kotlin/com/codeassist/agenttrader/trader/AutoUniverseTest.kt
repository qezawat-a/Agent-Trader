package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoUniverseTest {

    private val engine = TraderEngine(BitunixRest("test-key", "test-secret"))

    private fun ticker(symbol: String, open: String, last: String, quoteVol: String): JSONObject =
        JSONObject().apply {
            put("symbol", symbol)
            put("open", open)
            put("lastPrice", last)
            put("quoteVol", quoteVol)
        }

    private fun tickers(vararg rows: JSONObject): JSONArray = JSONArray().apply {
        for (r in rows) put(r)
    }

    @Test
    fun disabled_returns_empty() {
        val arr = tickers(ticker("BTCUSDT", "100", "110", "10000000"))
        assertTrue(engine.autoUniverseSymbols(arr, false, 0.0, "gainers", 10).isEmpty())
    }

    @Test
    fun filters_by_min_24h_volume() {
        val arr = tickers(
            ticker("AAAUSDT", "100", "120", "10000000"),   // +20%, vol OK
            ticker("BBBUSDT", "100", "110", "1000"),        // vol too small
            ticker("CCCUSDT", "100", "115", "5000000"),     // +15%, vol OK
        )
        val picked = engine.autoUniverseSymbols(arr, true, 2_000_000.0, "gainers", 10)
        assertEquals(listOf("AAAUSDT", "CCCUSDT"), picked)
    }

    @Test
    fun ranks_by_gainers() {
        val arr = tickers(
            ticker("AAAUSDT", "100", "110", "10000000"),   // +10%
            ticker("BBBUSDT", "100", "130", "10000000"),   // +30% — first
            ticker("CCCUSDT", "100", "120", "10000000"),   // +20%
        )
        val picked = engine.autoUniverseSymbols(arr, true, 0.0, "gainers", 10)
        assertEquals(listOf("BBBUSDT", "CCCUSDT", "AAAUSDT"), picked)
    }

    @Test
    fun ranks_by_volume() {
        val arr = tickers(
            ticker("AAAUSDT", "100", "110", "10000000"),
            ticker("BBBUSDT", "100", "105", "90000000"),   // highest volume
            ticker("CCCUSDT", "100", "108", "50000000"),
        )
        val picked = engine.autoUniverseSymbols(arr, true, 0.0, "volume", 10)
        assertEquals(listOf("BBBUSDT", "CCCUSDT", "AAAUSDT"), picked)
    }

    @Test
    fun takes_only_top_n() {
        val arr = tickers(
            ticker("A1USDT", "100", "110", "10000000"),
            ticker("B2USDT", "100", "120", "10000000"),
            ticker("C3USDT", "100", "130", "10000000"),
        )
        assertEquals(listOf("C3USDT", "B2USDT"), engine.autoUniverseSymbols(arr, true, 0.0, "gainers", 2))
    }

    @Test
    fun empty_ticker_list_returns_empty() {
        assertTrue(engine.autoUniverseSymbols(JSONArray(), true, 0.0, "gainers", 10).isEmpty())
    }
}
