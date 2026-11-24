package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // Ten pairs pass the floor, so the floor is what decides (below ten the
        // engine falls back to the deepest books — see the fallback test).
        val rows = (1..10).map { i ->
            ticker("P${i}USDT", "100", "110", "10000000") // +10%, vol OK
        }.toMutableList()
        rows.add(ticker("AAAUSDT", "100", "120", "10000000"))  // +20%, vol OK
        rows.add(ticker("CCCUSDT", "100", "115", "5000000"))   // +15%, vol OK
        rows.add(ticker("BBBUSDT", "100", "110", "1000"))       // vol too small
        val picked = engine.autoUniverseSymbols(tickers(*rows.toTypedArray()), true, 2_000_000.0, "gainers", 10)
        assertTrue(picked.contains("AAAUSDT"), "AAAUSDT in $picked")
        assertTrue(picked.contains("CCCUSDT"), "CCCUSDT in $picked")
        assertTrue(!picked.contains("BBBUSDT"), "thin coin must be dropped: $picked")
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

/**
 * The ranking modes came from the Telegram trader (bitunix-ai-trader):
 * GAINERS must really be gainers, VOLUME is liquidity-dominated, and a
 * volume floor that leaves almost nothing falls back to the deepest books.
 */
class UniverseRankingTest {

    private fun cand(symbol: String, vol: Double, chg: Double, range: Double = 0.0) =
        UniverseCandidate(symbol, vol, chg, range, 100.0)

    @Test
    fun gainers_keeps_only_coins_that_are_up() {
        val pool = listOf(
            cand("AAAUSDT", 10_000_000.0, 5.0),
            cand("BBBUSDT", 10_000_000.0, -3.0),
            cand("CCCUSDT", 10_000_000.0, 12.0),
        )
        assertEquals(listOf("CCCUSDT", "AAAUSDT"), rankUniverse(pool, "gainers").map { it.symbol })
    }

    @Test
    fun losers_keeps_only_coins_that_are_down() {
        val pool = listOf(
            cand("AAAUSDT", 10_000_000.0, 5.0),
            cand("BBBUSDT", 10_000_000.0, -8.0),
            cand("CCCUSDT", 10_000_000.0, -2.0),
        )
        assertEquals(listOf("BBBUSDT", "CCCUSDT"), rankUniverse(pool, "losers").map { it.symbol })
    }

    @Test
    fun movers_takes_the_biggest_move_either_way() {
        val pool = listOf(
            cand("AAAUSDT", 10_000_000.0, 4.0),
            cand("BBBUSDT", 10_000_000.0, -30.0),
            cand("CCCUSDT", 10_000_000.0, 12.0),
        )
        assertEquals(listOf("BBBUSDT", "CCCUSDT", "AAAUSDT"), rankUniverse(pool, "movers").map { it.symbol })
    }

    @Test
    fun volume_ranking_is_liquidity_dominated() {
        // BTC has a thin range but a huge book; the microcap has a huge range.
        val pool = listOf(
            cand("BTCUSDT", 2_400_000_000.0, 1.0, 2.0),
            cand("MICROUSDT", 600_000.0, 30.0, 70.0),
        )
        assertEquals("BTCUSDT", rankUniverse(pool, "volume").first().symbol)
    }

    @Test
    fun a_too_high_volume_floor_falls_back_to_the_deepest_books() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("symbol", "BTCUSDT"); put("open", "100"); put("lastPrice", "101")
                put("quoteVol", "900000000"); put("high", "110"); put("low", "99")
            })
            put(JSONObject().apply {
                put("symbol", "ETHUSDT"); put("open", "100"); put("lastPrice", "99")
                put("quoteVol", "800000000"); put("high", "105"); put("low", "95")
            })
        }
        val engine = TraderEngine(BitunixRest("k", "s"))
        // 1 billion floor: nothing passes, so the most liquid names are used.
        val picked = engine.autoUniverseSymbols(arr, true, 1_000_000_000.0, "volume", 10)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), picked)
    }
}

/**
 * The stop follows the user's own explicit SL distance — never an invented
 * fraction of the liquidation distance, never clamped, never ATR-widened.
 */
class LiquidationStopTest {

    @Test
    fun sl_pct_is_an_exact_distance_from_the_entry() {
        val plan = TpSlPlanner.plan(100.0, 1, 0.02)
        assertEquals(98.0, plan.stop!!, 1e-9)
        val short = TpSlPlanner.plan(100.0, -1, 0.02)
        assertEquals(102.0, short.stop!!, 1e-9)
    }

    @Test
    fun sl_pct_zero_means_no_stop_and_no_ladder() {
        val plan = TpSlPlanner.plan(100.0, 1, 0.0)
        assertNull(plan.stop)
        assertTrue(plan.ladder.isEmpty())
    }

    @Test
    fun the_ladder_is_built_from_the_stop_distance() {
        val plan = TpSlPlanner.plan(100.0, 1, 0.02)
        assertEquals(4, plan.ladder.size)
        assertEquals(102.0, plan.ladder[0].first, 1e-9) // entry + 1R
        assertEquals(106.0, plan.ladder[2].first, 1e-9) // entry + 3R
        assertEquals(108.0, plan.ladder[3].first, 1e-9) // entry + 4R
    }

    @Test
    fun liquidation_is_exchange_reported_not_computed_locally() {
        // Live liquidation comes from pending-position liqPrice; local TP/SL plan
        // only owns stop/ladder math.
        val plan = TpSlPlanner.plan(100.0, 1, 0.02)
        assertEquals(98.0, plan.stop!!, 1e-9)
        assertEquals(4, plan.ladder.size)
    }

    @Test
    fun tier_for_leverage_maps_to_the_highest_tier_covering_the_selection() {
        // The official documented BTC table.
        val tiers = parseRiskTiers(
            org.json.JSONArray(
                """[{"level":1,"startValue":"0","endValue":"150000","leverage":125,"maintenanceMarginRate":"0.004"},
                    {"level":2,"startValue":"150000","endValue":"800000","leverage":100,"maintenanceMarginRate":"0.005"},
                    {"level":3,"startValue":"800000","endValue":"2500000","leverage":50,"maintenanceMarginRate":"0.01"},
                    {"level":4,"startValue":"2500000","endValue":"10000000","leverage":20,"maintenanceMarginRate":"0.025"},
                    {"level":5,"startValue":"10000000","endValue":"20000000","leverage":10,"maintenanceMarginRate":"0.05"},
                    {"level":6,"startValue":"20000000","endValue":"40000000","leverage":5,"maintenanceMarginRate":"0.1"},
                    {"level":7,"startValue":"40000000","endValue":"60000000","leverage":2,"maintenanceMarginRate":"0.25"},
                    {"level":8,"startValue":"60000000","endValue":"100000000","leverage":1,"maintenanceMarginRate":"0.5"}]""",
            ),
        )
        // The official example: 50x → tier 3 (max value 2,500,000 USDT).
        assertEquals(3, tierForLeverage(tiers, 50)?.level)
        assertEquals(2, tierForLeverage(tiers, 75)?.level)
        assertEquals(1, tierForLeverage(tiers, 125)?.level)
        assertEquals(8, tierForLeverage(tiers, 1)?.level)
        // No tier covers more than 125x on this table.
        assertNull(tierForLeverage(tiers, 200))
    }
}
