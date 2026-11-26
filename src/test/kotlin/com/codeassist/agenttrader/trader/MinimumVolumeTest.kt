package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The exchange minimum (minTradeVolume, read from trading_pairs) is enforced before an order is sent. */
class MinimumVolumeTest {

    private val engine = TraderEngine(BitunixRest("k", "s"))
    private val near = PairInfo(basePrecision = 1, quotePrecision = 4, minTradeVolume = 10.0, maxLeverage = 50)

    @Test
    fun an_order_under_the_exchange_minimum_is_not_placed() {
        assertNull(engine.formatQty("NEARUSDT", 4.0, near))
        // Floored to one decimal this is 9.9, which is still under 10.
        assertNull(engine.formatQty("NEARUSDT", 9.99, near))
    }

    @Test
    fun an_order_at_or_above_the_minimum_is_placed() {
        assertNotNull(engine.formatQty("NEARUSDT", 10.0, near))
        assertNotNull(engine.formatQty("NEARUSDT", 12.34, near))
    }
}
