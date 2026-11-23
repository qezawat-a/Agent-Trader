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

    /**
     * The live BTCUSDT tier table, read from the exchange
     * (GET /api/v1/futures/position/get_position_tiers?symbol=BTCUSDT).
     *
     * The rate RISES with the tier and the allowed leverage FALLS, so the
     * 75x tier is the one that carries 0.65% MMR.
     */
    private val btcTiers = JSONArray(
        """[{"symbol":"BTCUSDT","level":1,"startValue":"0","endValue":"100000","leverage":200,"maintenanceMarginRate":"0.003"},
            {"symbol":"BTCUSDT","level":2,"startValue":"100000","endValue":"400000","leverage":150,"maintenanceMarginRate":"0.004"},
            {"symbol":"BTCUSDT","level":3,"startValue":"400000","endValue":"1000000","leverage":100,"maintenanceMarginRate":"0.005"},
            {"symbol":"BTCUSDT","level":4,"startValue":"1000000","endValue":"4000000","leverage":75,"maintenanceMarginRate":"0.0065"},
            {"symbol":"BTCUSDT","level":5,"startValue":"4000000","endValue":"10000000","leverage":50,"maintenanceMarginRate":"0.01"}]""",
    )

    @Test
    fun tier_lookup_picks_the_band_containing_the_notional() {
        val tiers = parseRiskTiers(btcTiers)
        assertEquals(1, tierFor(tiers, 1000.0)?.level)
        assertEquals(1, tierFor(tiers, 99_999.0)?.level)
        assertEquals(2, tierFor(tiers, 100_000.0)?.level)
        assertEquals(3, tierFor(tiers, 400_000.0)?.level)
        assertEquals(4, tierFor(tiers, 2_000_000.0)?.level)
        assertEquals(5, tierFor(tiers, 5_000_000.0)?.level)
    }

    @Test
    fun the_75x_tier_carries_the_065_percent_rate() {
        val tier = tierFor(parseRiskTiers(btcTiers), 2_000_000.0)!!
        assertEquals(75, tier.maxLeverage)
        assertEquals(0.0065, tier.mmr, 1e-9)
    }

    @Test
    fun tier_lookup_is_null_outside_the_table() {
        assertNull(tierFor(parseRiskTiers(btcTiers), 50_000_000.0))
        assertNull(tierFor(parseRiskTiers(JSONArray("[]")), 1000.0))
    }

    @Test
    fun tier_lookup_skips_rows_without_numbers() {
        // No startValue, and no maintenance margin rate: neither row is usable.
        val broken = JSONArray("""[{"level":1},{"level":2,"startValue":"0","endValue":"100","leverage":10}]""")
        assertNull(tierFor(parseRiskTiers(broken), 50.0))
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

    // ---- get_pending_positions (doc sample) ----

    private val docPosition = org.json.JSONObject(
        """{"positionId":"12345678","symbol":"BTCUSDT","qty":"0.5","entryValue":"30000",
           "side":"LONG","positionMode":"HEDGE","marginMode":"ISOLATION","leverage":100,
           "fee":"0.1","funding":"-0.2","realizedPNL":"102.9","margin":"300",
           "unrealizedPNL":"1.5","liqPrice":"22209","marginRate":"0.01",
           "avgOpenPrice":"1.0","ctime":1691382137448,"mtime":1691382137448}""",
    )

    @Test
    fun pending_position_uses_documented_field_names() {
        assertEquals("0.5", posQty(docPosition))
        assertEquals("1.0", posEntry(docPosition))
        assertEquals("22209", liqText(docPosition.optString("liqPrice")))
    }

    @Test
    fun undocumented_field_names_are_ignored() {
        val legacy = org.json.JSONObject("""{"holdQty":"2","entryPrice":"10"}""")
        assertEquals("", posQty(legacy))
        assertEquals("", posEntry(legacy))
    }

    @Test
    fun non_positive_liq_price_means_low_risk() {
        assertEquals("— (low risk)", liqText("0"))
        assertEquals("— (low risk)", liqText("-12.5"))
        assertEquals("—", liqText(""))
    }

    // ---- get_position_tiers (doc sample) ----

    private val docTiers = JSONArray(
        """[{"symbol":"BTCUSDT","level":1,"startValue":"0","endValue":"50000","leverage":125,"maintenanceMarginRate":"0.004"},
            {"symbol":"BTCUSDT","level":2,"startValue":"50000","endValue":"200000","leverage":100,"maintenanceMarginRate":"0.005"}]""",
    )

    @Test
    fun doc_tier_table_bounds_are_half_open() {
        val tiers = parseRiskTiers(docTiers)
        assertEquals(1, tierFor(tiers, 0.0)?.level)
        assertEquals(1, tierFor(tiers, 49_999.0)?.level)
        assertEquals(2, tierFor(tiers, 50_000.0)?.level)
        assertEquals(2, tierFor(tiers, 199_999.0)?.level)
        assertNull(tierFor(tiers, 200_000.0))
        assertEquals(125, tierFor(tiers, 1000.0)?.maxLeverage)
        assertEquals(0.004, tierFor(tiers, 1000.0)?.mmr!!, 1e-9)
    }
}
