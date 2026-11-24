package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Funding snapshot formatting against the documented response shape. */
class FundingSummaryTest {

    private val engine = TraderEngine(BitunixRest("k", "s"))

    @Test
    fun extracts_rate_and_labels_crowded_side() {
        val arr = JSONArray(
            """[{"symbol":"BTCUSDT","markPrice":"60000","lastPrice":"60001","indexPrice":"60001",
                "fundingRate":"0.0005","fundingInterval":8,"nextFundingTime":"1770710400000",
                "maxFundingRate":"0.3","minFundingRate":"-0.3"}]""",
        )
        val out = engine.fundingSummary(arr, top = 5)
        assertTrue(out.contains("BTCUSDT"))
        assertTrue(out.contains("0.0500%"), "0.0005 must render as 0.0500%: $out")
        assertTrue(out.contains("longs pay shorts"))
        assertTrue(out.contains("crowded longs"))
        assertTrue(out.contains("▲"))
    }

    @Test
    fun negative_rate_means_crowded_shorts() {
        val arr = JSONArray("""[{"symbol":"ETHUSDT","fundingRate":"-0.004","nextFundingTime":0}]""")
        val out = engine.fundingSummary(arr, top = 5)
        assertTrue(out.contains("shorts pay longs"))
        assertTrue(out.contains("-0.4000%"))
        assertTrue(out.contains("▼"))
        assertTrue(out.contains("crowded shorts"))
    }

    @Test
    fun sorts_by_absolute_rate_extremes_first() {
        val arr = JSONArray(
            """[{"symbol":"BTCUSDT","fundingRate":"0.0005","nextFundingTime":0},
                {"symbol":"ETHUSDT","fundingRate":"-0.004","nextFundingTime":0},
                {"symbol":"SOLUSDT","fundingRate":"0.0001","nextFundingTime":0}]""",
        )
        val out = engine.fundingSummary(arr, top = 2)
        val eth = out.indexOf("ETHUSDT")
        val btc = out.indexOf("BTCUSDT")
        val sol = out.indexOf("SOLUSDT")
        assertTrue(eth >= 0 && btc > eth && sol == -1, "top 2 extremes, SOL dropped: $out")
    }

    @Test
    fun missing_next_time_renders_placeholder() {
        val arr = JSONArray("""[{"symbol":"BTCUSDT","fundingRate":"0.0005"}]""")
        val out = engine.fundingSummary(arr, top = 1)
        assertTrue(out.contains("next ?"))
    }

    @Test
    fun empty_or_unparseable_data_is_reported() {
        assertEquals("No funding data.", engine.fundingSummary(JSONArray("[]")))
        assertEquals("No funding data.", engine.fundingSummary(JSONArray("[1,2,3]")))
        assertEquals("No funding data.", engine.fundingSummary(JSONArray("""[{"symbol":"X"}]""")))
    }
}
