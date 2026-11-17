package com.codeassist.agenttrader.trader

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The maintenance-margin-rate lookup in the live liquidation guard is keyed on
 * the position's ACTUAL value (qty × entry), per the official tiered risk
 * limit ("the maintenance margin rate will be calculated based on the actual
 * value of the position and order"). It must not fall back to price ×
 * leverage, which is not a notional and lands in the wrong tier.
 */
class PositionNotionalTest {

    private fun bitcoinTiers() = parseRiskTiers(
        org.json.JSONArray(
            """[{"level":1,"startValue":"0","endValue":"100000","leverage":200,"maintenanceMarginRate":"0.003"},
                {"level":2,"startValue":"100000","endValue":"400000","leverage":150,"maintenanceMarginRate":"0.004"},
                {"level":3,"startValue":"400000","endValue":"1000000","leverage":100,"maintenanceMarginRate":"0.005"},
                {"level":4,"startValue":"1000000","endValue":"4000000","leverage":75,"maintenanceMarginRate":"0.0065"}]""",
        ),
    )

    private fun position(
        qty: String? = "0.02",
        entry: String? = "50000",
        lev: Int = 20,
    ) = JSONObject().apply {
        put("symbol", "BTCUSDT").put("positionId", "1")
        qty?.let { put("qty", it) }
        entry?.let { put("avgOpenPrice", it) }
        put("leverage", lev)
    }

    @Test
    fun notional_is_qty_times_entry_not_price_times_leverage() {
        // 0.02 × 50,000 = 1,000 — the position's actual value.
        assertEquals(1000.0, posNotional(position())!!, 1e-9)
    }

    @Test
    fun notional_falls_back_to_holdqty_like_posqty_does() {
        val p = JSONObject()
            .put("symbol", "BTCUSDT").put("positionId", "1")
            .put("holdQty", "0.5").put("avgOpenPrice", "100")
        assertEquals(50.0, posNotional(p)!!, 1e-9)
    }

    @Test
    fun notional_is_null_when_the_qty_or_entry_is_missing_or_bad() {
        assertNull(posNotional(position(qty = null)))
        assertNull(posNotional(position(qty = "0")))
        assertNull(posNotional(position(qty = "junk")))
        assertNull(posNotional(position(entry = null)))
        assertNull(posNotional(position(entry = "0")))
        assertNull(posNotional(JSONObject().put("positionId", "1")))
    }

    @Test
    fun the_guard_mmr_matches_the_tier_of_the_real_position() {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.seedRiskTiers("BTCUSDT", bitcoinTiers())
        // Real notional 1,000 USDT -> tier 1 -> MMR 0.30%.
        assertEquals(0.003, e.mmrFor("BTCUSDT", posNotional(position())!!), 1e-9)
        // The old lookup, price × leverage = 1,000,000 -> tier 4, MMR 0.65%.
        assertEquals(0.0065, e.mmrFor("BTCUSDT", 50_000.0 * 20), 1e-9)
    }

    @Test
    fun a_low_priced_high_qty_position_keeps_the_higher_tier_mmr() {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.seedRiskTiers(
            "DOGEUSDT",
            parseRiskTiers(
                org.json.JSONArray(
                    """[{"level":1,"startValue":"0","endValue":"1000","leverage":200,"maintenanceMarginRate":"0.003"},
                        {"level":2,"startValue":"1000","endValue":"5000","leverage":100,"maintenanceMarginRate":"0.004"},
                        {"level":3,"startValue":"5000","endValue":"50000","leverage":50,"maintenanceMarginRate":"0.005"}]""",
                ),
            ),
        )
        // 400,000 × 0.01 = 4,000 -> tier 2 (MMR 0.004).
        val big = JSONObject()
            .put("symbol", "DOGEUSDT").put("positionId", "2")
            .put("qty", "400000").put("avgOpenPrice", "0.01").put("leverage", 20)
        assertEquals(4_000.0, posNotional(big)!!, 1e-9)
        assertEquals(0.004, e.mmrFor("DOGEUSDT", posNotional(big)!!), 1e-9)
        // price × leverage (0.01 × 20 = 0.2) would have landed in tier 1.
        assertEquals(0.003, e.mmrFor("DOGEUSDT", 0.01 * 20), 1e-9)
    }

    @Test
    fun the_fallback_uses_the_default_mmr_when_no_notional_is_derivable() {
        // The guard path substitutes TpSlPlanner.PAPER_SIMULATED_MMR when posNotional is null;
        // this pins the constant the fallback relies on (0.5% = tier-1 majors' rate).
        assertEquals(0.005, TpSlPlanner.PAPER_SIMULATED_MMR, 1e-12)
    }
}
