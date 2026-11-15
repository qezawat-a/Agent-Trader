package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * Liquidation-aware stops: the stop must never reach or pass the liquidation
 * price. When it can't fit, the trade is refused rather than opened with a
 * stop that liquidation would hit first.
 */
class LiquidationStopTest {

    @Test
    fun stop_at_50_percent_is_inside_liquidation() {
        // entry 100, lev 10 -> liq = 90.5 (long, MMR 0.5%) -> stop at 50% = 95.25.
        assertTrue(TpSlPlanner.stopFits(100.0, 1, 10, 0.5))
        assertTrue(TpSlPlanner.stopFits(100.0, -1, 10, 0.5))
    }

    @Test
    fun stop_at_100_percent_reaches_liquidation_and_fails() {
        // stopFraction 1.0 puts stop exactly at liq -> refused.
        assertFalse(TpSlPlanner.stopFits(100.0, 1, 10, 1.0))
        assertFalse(TpSlPlanner.stopFits(100.0, -1, 10, 1.0))
    }

    @Test
    fun stop_beyond_liquidation_fails() {
        assertFalse(TpSlPlanner.stopFits(100.0, 1, 10, 1.2))
        assertFalse(TpSlPlanner.stopFits(100.0, -1, 10, 1.2))
    }

    @Test
    fun high_leverage_makes_stop_harder_to_fit() {
        // At 200x the room is 1/200 = 0.5% — exactly the MMR, so with the
        // default MMR 0.5% there is NO room and the entry is refused.
        assertFalse(TpSlPlanner.stopFits(100.0, 1, 200, 0.5))
        // With a smaller MMR (0.1%) there is room again and 90% still fits.
        assertTrue(TpSlPlanner.stopFits(100.0, 1, 200, 0.9, mmr = 0.001))
    }

    @Test
    fun plan_clamps_stop_inside_liquidation() {
        // entry 100, lev 10, stopFraction 1.0 -> clamped to safety margin.
        val plan = TpSlPlanner.plan(100.0, 1, 10, 1.0)
        val liqDist = TpSlPlanner.liqDistance(100.0, 10)
        val liq = 100.0 - liqDist
        val floor = liq + 0.05 * liqDist
        assertTrue(plan.stop > liq, "stop must be inside liquidation: stop=${plan.stop} liq=$liq")
        assertTrue(plan.stop >= floor, "stop must respect safety margin: stop=${plan.stop} floor=$floor")
        assertTrue(plan.stop < 100.0, "stop must be below entry for a long: ${plan.stop}")
    }

    @Test
    fun liquidation_distance_follows_the_official_formula() {
        // Liq = Entry × (1 − 1/Leverage + MMR)  →  distance = Entry × (1/Lev − MMR)
        // Bitunix's own example: entry 50 000, 20x, MMR 0.25% → liq 47 625.
        assertEquals(47_625.0, TpSlPlanner.liqPrice(50_000.0, 1, 20, mmr = 0.0025), 1e-6)
        // Short side mirrors it: entry 50 000, 20x, MMR 0.25% → liq 52 375.
        assertEquals(52_375.0, TpSlPlanner.liqPrice(50_000.0, -1, 20, mmr = 0.0025), 1e-6)
        // The MMR eats into the room: at 100x with MMR 0.5% there is only 0.5%.
        assertEquals(0.5, TpSlPlanner.liqDistance(100.0, 100, mmr = 0.005) / 100.0 * 100.0, 1e-9)
        // No MMR (old, optimistic model) → the full 1/leverage.
        assertEquals(1.0, TpSlPlanner.liqDistance(100.0, 100, mmr = 0.0) / 100.0 * 100.0, 1e-9)
    }

    @Test
    fun an_entry_without_liquidation_room_is_refused() {
        // 1/200 = 0.5% and MMR 0.5% → no room at all: refuse, do not open.
        assertFalse(TpSlPlanner.stopFits(100.0, 1, 200, 0.3, mmr = 0.005))
        assertEquals(0.0, TpSlPlanner.liqDistance(100.0, 200, mmr = 0.005), 1e-12)
    }
}
