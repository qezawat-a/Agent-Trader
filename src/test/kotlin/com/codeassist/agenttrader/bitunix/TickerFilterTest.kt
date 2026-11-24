package com.codeassist.agenttrader.bitunix

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tickers endpoint ignores the `symbols` filter and returns every symbol,
 * so the client filters the rows itself.
 */
class TickerFilterTest {

    private val all = JSONArray(
        """[{"symbol":"ALABUSDT","lastPrice":"233","markPrice":"233"},
            {"symbol":"BTCUSDT","lastPrice":"74037.9","markPrice":"74038"},
            {"symbol":"ETHUSDT","lastPrice":"2300.5","markPrice":"2300.7"},
            {"symbol":"SUSHIUSDT","lastPrice":"0.1753","markPrice":"0.1753"}]""",
    )

    @Test
    fun blank_symbols_passes_everything_through() {
        assertEquals(4, BitunixRest.filterTickers(all, "").length())
        assertEquals(4, BitunixRest.filterTickers(all, "   ").length())
    }

    @Test
    fun single_symbol_keeps_only_that_row() {
        val out = BitunixRest.filterTickers(all, "BTCUSDT")
        assertEquals(1, out.length())
        assertEquals("BTCUSDT", out.getJSONObject(0).optString("symbol"))
        assertEquals("74037.9", out.getJSONObject(0).optString("lastPrice"))
    }

    @Test
    fun comma_separated_symbols_keep_their_rows() {
        val out = BitunixRest.filterTickers(all, "BTCUSDT,ETHUSDT")
        assertEquals(2, out.length())
        val symbols = (0 until out.length()).map { out.getJSONObject(it).optString("symbol") }
        assertTrue(symbols.containsAll(listOf("BTCUSDT", "ETHUSDT")), symbols.toString())
        assertTrue(!symbols.contains("ALABUSDT"), symbols.toString())
    }

    @Test
    fun filtering_is_case_insensitive_and_tolerates_spaces() {
        val out = BitunixRest.filterTickers(all, " btcusdt , ETHUSDT ")
        assertEquals(2, out.length())
    }

    @Test
    fun unknown_symbol_yields_no_rows() {
        assertEquals(0, BitunixRest.filterTickers(all, "NOPEUSDT").length())
    }
}
