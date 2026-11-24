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
    fun notional_is_null_when_only_undocumented_qty_fields_are_present() {
        val p = JSONObject()
            .put("symbol", "BTCUSDT").put("positionId", "1")
            .put("holdQty", "0.5").put("avgOpenPrice", "100")
        assertNull(posNotional(p))
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


}
