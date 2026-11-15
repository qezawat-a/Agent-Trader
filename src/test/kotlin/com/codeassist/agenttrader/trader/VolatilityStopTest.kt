package com.codeassist.agenttrader.trader

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stop must sit outside the symbol's own noise, and the leverage must be
 * derived so it still fits inside the liquidation distance.
 *
 * The ATR figures are the live 15m ATR(14) of the symbols that were actually
 * traded, measured from the exchange's klines.
 */
class VolatilityStopTest {

    /** ATR(14) as a fraction of the price, measured live on the 15m. */
    private fun atrPct(symbol: String) = when (symbol) {
        "FFUSDT" -> 0.01727
        "XPLUSDT" -> 0.00954
        "AKEUSDT" -> 0.02863
        "KAVAUSDT" -> 0.02499
        "BTCUSDT" -> 0.00321
        else -> 0.01
    }

    @Test
    fun at_75x_no_stop_can_fit_on_ff_so_the_entry_is_refused() {
        // The whole point: on FF at 75x the liquidation distance is 0.68% but
        // a noise-sized stop needs 2.59%. No stop can exist there, so the
        // entry must be refused — the leverage has to come down first.
        val entry = 0.09880
        val atr = entry * atrPct("FFUSDT")
        assertTrue(
            !TpSlPlanner.stopFits(entry, 1, 75, 0.30, 0.0065, atr, 1.5),
            "at 75x a noise-sized stop cannot fit inside the liquidation distance",
        )
    }

    @Test
    fun the_stop_sits_outside_the_noise_at_the_capped_leverage() {
        // FFUSDT at the leverage a sane stop allows: the stop is outside the
        // 15m noise AND inside the liquidation distance.
        val entry = 0.09880
        val atr = entry * atrPct("FFUSDT")
        val cap = TpSlPlanner.maxLeverageForStop(1.5 * atrPct("FFUSDT"), 0.0065)
        val plan = TpSlPlanner.plan(entry, 1, cap, 0.30, 0.0065, atr, 1.5)
        val dist = abs(entry - plan.stop) / entry
        assertTrue(
            dist >= 1.5 * atrPct("FFUSDT") - 1e-9,
            "stop at $dist is still inside the noise (${atrPct("FFUSDT")})",
        )
        val liqDist = TpSlPlanner.liqDistance(entry, cap, 0.0065)
        assertTrue(abs(entry - plan.stop) < liqDist, "the stop must stay inside the liquidation distance")
    }

    @Test
    fun the_leverage_is_capped_so_the_stop_fits_inside_liquidation() {
        val cap = TpSlPlanner.maxLeverageForStop(1.5 * atrPct("FFUSDT"), 0.0065)
        assertTrue(cap < 75, "75x must not be allowed on a symbol that volatile, got ${cap}x")
        // At the cap the stop fits with room to spare.
        val entry = 0.09880
        val atr = entry * atrPct("FFUSDT")
        val plan = TpSlPlanner.plan(entry, 1, cap, 0.30, 0.0065, atr, 1.5)
        val liqDist = TpSlPlanner.liqDistance(entry, cap, 0.0065)
        val stopDist = abs(entry - plan.stop)
        assertTrue(stopDist < liqDist, "the stop ($stopDist) must stay inside the liquidation distance ($liqDist)")
    }

    @Test
    fun a_low_volatility_symbol_keeps_the_high_leverage() {
        // BTCUSDT at 75x: a 0.48% stop fits inside the 0.68% of room, so the
        // user's 75x stands. The cap must not fire on every symbol.
        val cap = TpSlPlanner.maxLeverageForStop(1.5 * atrPct("BTCUSDT"), 0.0065)
        assertTrue(cap >= 75, "75x must survive on BTC, got ${cap}x")
    }

    @Test
    fun every_traded_symbol_gets_a_leverage_that_fits() {
        // The four symbols from the losing session, with the leverage the app
        // used and the one a volatility-sane stop allows.
        val cases = listOf(
            Triple("FFUSDT", 75, 29),
            Triple("XPLUSDT", 50, 44),
            Triple("AKEUSDT", 40, 19),
            Triple("KAVAUSDT", 25, 21),
        )
        for ((sym, used, expected) in cases) {
            val cap = TpSlPlanner.maxLeverageForStop(1.5 * atrPct(sym), 0.0065)
            assertEquals(expected, cap, "$sym: expected a cap of $expected, got $cap")
            assertTrue(cap < used, "$sym: $used x cannot fit a sane stop (cap ${cap}x)")
        }
    }

    @Test
    fun the_engine_lowers_the_leverage_and_says_why() {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.seedRiskTiers(
            "FFUSDT",
            parseRiskTiers(
                org.json.JSONArray(
                    """[{"level":1,"startValue":"0","endValue":"100000","leverage":200,"maintenanceMarginRate":"0.003"},
                        {"level":2,"startValue":"100000","endValue":"400000","leverage":150,"maintenanceMarginRate":"0.004"},
                        {"level":3,"startValue":"400000","endValue":"1000000","leverage":100,"maintenanceMarginRate":"0.005"},
                        {"level":4,"startValue":"1000000","endValue":"4000000","leverage":75,"maintenanceMarginRate":"0.0065"}]""",
                ),
            ),
        )
        e.broker.balance = 266_666.67
        e.riskFraction = 0.1
        e.liqBufferPct = 0.0
        e.leverage = 75
        val (lev, note) = e.entryLeverage("FFUSDT", atrPct("FFUSDT"))
        assertEquals(29, lev)
        assertNotNull(note)
        // The reason must be on the line the user reads: the noise, not a secret.
        assertTrue("ATR" in note!!, note)
        assertTrue("75x" in note, note)
    }
}
