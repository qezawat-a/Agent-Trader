package com.codeassist.agenttrader.trader

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Leverage and liquidation come only from the user's setting and the
 * symbol's own live tiered risk-limit table — never from a volatility-derived
 * cap or a buffer the app invented.
 */
class TieredRiskLimitTest {

    /** The live BTCUSDT tier table (read from the exchange, all five tiers). */
    private fun btcTiers() = parseRiskTiers(
        JSONArray(
            """[{"level":1,"startValue":"0","endValue":"100000","leverage":200,"maintenanceMarginRate":"0.003"},
                {"level":2,"startValue":"100000","endValue":"400000","leverage":150,"maintenanceMarginRate":"0.004"},
                {"level":3,"startValue":"400000","endValue":"1000000","leverage":100,"maintenanceMarginRate":"0.005"},
                {"level":4,"startValue":"1000000","endValue":"4000000","leverage":75,"maintenanceMarginRate":"0.0065"},
                {"level":5,"startValue":"4000000","endValue":"10000000","leverage":50,"maintenanceMarginRate":"0.01"}]""",
        ),
    )

    private fun liveEngine(leverage: Int): TraderEngine {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.live = true
        e.seedRiskTiers("BTCUSDT", btcTiers())
        e.broker.balance = 266_666.67
        e.riskFraction = 0.1
        e.leverage = leverage
        return e
    }

    @Test
    fun paper_uses_the_users_leverage_as_set() {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.leverage = 42
        assertEquals(42, e.entryLeverage("BTCUSDT").first)
    }

    @Test
    fun live_refuses_when_the_tier_table_is_unavailable() {
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.live = true
        e.seedRiskTiers("BTCUSDT", emptyList()) // what a failed query caches
        e.leverage = 10
        val (lev, reason) = e.entryLeverage("BTCUSDT")
        assertEquals(0, lev)
        assertTrue(reason != null && "tiered risk-limit table unavailable" in reason, reason)
    }

    @Test
    fun live_refuses_when_no_tier_covers_the_selection() {
        // The documented BTC table caps at 125x; the configured leverage is
        // higher than every tier allows.
        val documented = parseRiskTiers(
            JSONArray("""[{"level":1,"startValue":"0","endValue":"150000","leverage":125,"maintenanceMarginRate":"0.004"}]"""),
        )
        val e = TraderEngine(com.codeassist.agenttrader.bitunix.BitunixRest("k", "s"))
        e.live = true
        e.seedRiskTiers("BTCUSDT", documented)
        e.broker.balance = 1_000.0
        e.leverage = 150
        val (lev, reason) = e.entryLeverage("BTCUSDT")
        assertEquals(0, lev)
        assertTrue(reason != null && "exceeds every tier" in reason, reason)
    }

    @Test
    fun live_uses_the_tier_the_selection_maps_to() {
        // 75x → the tier whose cap is 75x (level 4), MMR from that tier.
        assertEquals(4, tierForLeverage(btcTiers(), 75)?.level)
        assertEquals(0.0065, tierForLeverage(btcTiers(), 75)?.mmr!!, 1e-9)
        val e = liveEngine(75)
        assertEquals(75, e.entryLeverage("BTCUSDT").first)
    }

    @Test
    fun live_refuses_when_the_position_would_exceed_the_tier_cap() {
        // 200x on 26,666 USDT of margin = 5.3M of position value, but the tier a
        // 200x selection maps to is tier 1, capped at 100k USDT — refuse.
        val e = liveEngine(200)
        val (lev, reason) = e.entryLeverage("BTCUSDT")
        assertEquals(0, lev)
        assertTrue(reason != null && "tier 1" in reason, reason)
    }

    @Test
    fun a_small_position_may_use_the_symbol_maximum() {
        // 1000 USDT balance × 10% × 200x = 20,000 position value — fits tier 1.
        val e = liveEngine(200)
        e.broker.balance = 1_000.0
        assertEquals(200, e.entryLeverage("BTCUSDT").first)
    }

    @Test
    fun every_tier_rate_comes_from_the_table() {
        assertEquals(0.003, tierForLeverage(btcTiers(), 200)?.mmr!!, 1e-9)
        assertEquals(0.004, tierForLeverage(btcTiers(), 150)?.mmr!!, 1e-9)
        assertEquals(0.005, tierForLeverage(btcTiers(), 100)?.mmr!!, 1e-9)
        assertEquals(0.0065, tierForLeverage(btcTiers(), 75)?.mmr!!, 1e-9)
        assertEquals(0.01, tierForLeverage(btcTiers(), 50)?.mmr!!, 1e-9)
        assertNull(tierForLeverage(btcTiers(), 250))
    }
}
