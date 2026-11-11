package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Net P&L per the Bitunix docs and the risk-tier lookup. */
class PositionMathTest {

    @Test
    fun net_pnl_subtracts_fee_and_adds_funding() {
        // realizedPNL excludes both fee and funding; fee is a cost.
        assertEquals(9.0, netPositionPnl(10.0, 2.0, 1.0))
        assertEquals(12.0, netPositionPnl(10.0, 2.0, 4.0))
        assertEquals(8.0, netPositionPnl(10.0, 2.0, 0.0))
    }

    @Test
    fun net_pnl_of_a_losing_position() {
        assertEquals(-10.5, netPositionPnl(-10.0, 1.5, 1.0))
    }

    private val tiers = JSONArray(
        """[{"level":1,"startValue":"0","endValue":"50000","leverage":125,"maintenanceMarginRate":"0.005"},
            {"level":2,"startValue":"50000","endValue":"250000","leverage":100,"maintenanceMarginRate":"0.01"},
            {"level":3,"startValue":"250000","endValue":"1000000","leverage":50,"maintenanceMarginRate":"0.02"}]""",
    )

    @Test
    fun tier_lookup_picks_the_band_containing_the_notional() {
        assertEquals(1, tierFor(tiers, 1000.0)?.optInt("level"))
        assertEquals(2, tierFor(tiers, 50000.0)?.optInt("level"))
        assertEquals(2, tierFor(tiers, 249999.0)?.optInt("level"))
        assertEquals(3, tierFor(tiers, 250000.0)?.optInt("level"))
    }

    @Test
    fun tier_lookup_is_null_outside_the_table() {
        assertNull(tierFor(tiers, 5_000_000.0))
        assertNull(tierFor(JSONArray("[]"), 1000.0))
    }

    @Test
    fun tier_lookup_skips_rows_without_numbers() {
        val broken = JSONArray("""[{"level":1},{"level":2,"startValue":"0","endValue":"100","leverage":10}]""")
        assertEquals(2, tierFor(broken, 50.0)?.optInt("level"))
    }

    @Test
    fun data_array_falls_back_to_position_list_and_list() {
        val rest = BitunixRest("k", "s")
        val positionList = JSONObject().put("positionList", JSONArray("""[{"symbol":"BTCUSDT"}]"""))
        assertEquals(1, rest.dataArray(positionList).length())
        assertEquals("BTCUSDT", rest.dataArray(positionList).getJSONObject(0).optString("symbol"))
        val list = JSONObject().put("list", JSONArray("""[{"symbol":"ETHUSDT"}]"""))
        assertEquals(1, rest.dataArray(list).length())
        assertTrue(rest.dataArray(JSONObject()).length() == 0)
    }

    @Test
    fun data_array_prefers_data_over_fallbacks() {
        val rest = BitunixRest("k", "s")
        val json = JSONObject()
            .put("data", JSONArray("""[{"symbol":"BTCUSDT"}]"""))
            .put("positionList", JSONArray("""[{"symbol":"X"}]"""))
        assertEquals("BTCUSDT", rest.dataArray(json).getJSONObject(0).optString("symbol"))
    }
}
