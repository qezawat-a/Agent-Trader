package com.bitunix.agenttrader.trader

import com.bitunix.agenttrader.bitunix.BitunixRest
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Position TP/SL is based on the filled position, not the pre-entry ticker. */
class LiveEntryFillTest {

    private val engine = TraderEngine(BitunixRest("k", "s"))

    @Test
    fun filled_position_uses_documented_position_id_avg_open_price_and_side() {
        val rows = JSONArray(
            """[
              {"positionId":"other","symbol":"BTCUSDT","side":"SHORT","avgOpenPrice":"99"},
              {"positionId":"filled-long","symbol":"BTCUSDT","side":"LONG","avgOpenPrice":"101.25"}
            ]""",
        )

        val filled = filledPosition(rows, "BTCUSDT", 1)

        assertNotNull(filled)
        assertEquals("filled-long", filled.optString("positionId"))
        assertEquals(101.25, posEntry(filled).toDouble())
        assertNull(filledPosition(rows, "ETHUSDT", 1))
    }

    @Test
    fun levels_are_revalidated_against_avg_open_price() {
        val advice = TpSlAdvice(sl = 99.5, tp = 105.0, reason = "structure")

        // At the 100 USDT pre-fill quote this stop looked valid. The actual
        // 99 USDT fill makes it a stop above a LONG's average entry (30009).
        assertNull(
            engine.planForFilledEntry(
                dir = 1, avgOpenPrice = 99.0, leverage = 10, mmr = 0.005,
                advice = advice, fallbackSlPct = 0.0, fallbackTpR = 3.0,
            ),
        )

        val valid = engine.planForFilledEntry(
            dir = 1, avgOpenPrice = 101.0, leverage = 10, mmr = 0.005,
            advice = advice, fallbackSlPct = 0.0, fallbackTpR = 3.0,
        )
        assertNotNull(valid)
        assertEquals(99.5, valid.stop!!, 1e-9)
        assertEquals(105.0, valid.ladder.last().first, 1e-9)
    }
}
