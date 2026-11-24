package com.codeassist.agenttrader.trader

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat context for LIVE positions. It used to pass only the symbols from
 * the WS cache; the model then filled in the missing leverage, notional, SL and
 * PnL with invented numbers. These lines carry the exchange's fields and
 * nothing else.
 */
class LivePositionContextTest {

    private val open = JSONObject(
        """{"positionId":"12345678","symbol":"TELUSDT","qty":"431","entryValue":"34.78",
           "side":"LONG","positionMode":"HEDGE","marginMode":"ISOLATION","leverage":40,
           "fee":"0.01","funding":"0","realizedPNL":"0","margin":"0.87",
           "unrealizedPNL":"-0.0006","liqPrice":"0.0791","marginRate":"0.01",
           "avgOpenPrice":"0.0807"}""",
    )

    @Test
    fun an_open_position_carries_the_exchange_facts() {
        val line = livePositionLine(open)
        assertTrue(line.contains("TELUSDT"), line)
        assertTrue(line.contains("LONG"), line)
        assertTrue(line.contains("qty 431"), line)
        assertTrue(line.contains("@ 0.0807"), line)
        assertTrue(line.contains("34.78 USDT notional"), line)
        assertTrue(line.contains("40x"), line)
        assertTrue(line.contains("uPnL -0.0006 USDT"), line)
        assertTrue(line.contains("liq 0.0791"), line)
    }

    @Test
    fun a_missing_field_is_not_invented() {
        val line = livePositionLine(
            JSONObject().put("symbol", "2ZUSDT").put("side", "SHORT").put("qty", "10"),
        )
        assertTrue(line.contains("2ZUSDT SHORT"), line)
        assertFalse(line.contains("notional"), line)
        assertFalse(line.contains("uPnL"), line)
        assertFalse(line.contains("SL"), line)
        assertFalse(line.contains("TP"), line)
        assertFalse(line.contains("40x"), line)
    }

    @Test
    fun history_rows_are_closed_and_buy_means_long() {
        val p = JSONObject()
            .put("symbol", "VELVETUSDT").put("side", "BUY")
            .put("realizedPNL", "0.0859").put("fee", "0.0121").put("funding", "0.0000")
        val line = closedPositionLine(p)
        assertTrue(line.contains("VELVETUSDT LONG CLOSED"), line)
        assertTrue(line.contains("net 0.0738 USDT"), line)
        assertTrue(line.contains("realized 0.0859"), line)
    }

    @Test
    fun a_sell_history_row_is_a_closed_short() {
        val p = JSONObject()
            .put("symbol", "VELVETUSDT").put("side", "SELL")
            .put("realizedPNL", "-0.1184").put("fee", "0.0120")
        val line = closedPositionLine(p)
        assertTrue(line.contains("VELVETUSDT SHORT CLOSED"), line)
        assertTrue(line.contains("net -0.1304 USDT"), line)
    }
}
