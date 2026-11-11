package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.BitunixRest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Quantity/price formatting against seeded contract specs (no network). */
class EngineFormatTest {

    private val rest = BitunixRest("k", "s")
    private val engine = TraderEngine(rest)

    init {
        engine.seedPairInfo("BTCUSDT", PairInfo(basePrecision = 3, quotePrecision = 1, minTradeVolume = 0.001, maxLeverage = 125))
        engine.seedPairInfo("DOGEUSDT", PairInfo(basePrecision = 0, quotePrecision = 5, minTradeVolume = 1.0, maxLeverage = 20))
    }

    @Test
    fun qty_is_floored_to_base_precision() {
        assertEquals("0.123", engine.formatQty("BTCUSDT", 0.1234567))
    }

    @Test
    fun qty_below_min_volume_is_skipped_not_clamped_up() {
        assertNull(engine.formatQty("BTCUSDT", 0.00005))
    }

    @Test
    fun qty_just_under_min_volume_is_skipped() {
        assertNull(engine.formatQty("DOGEUSDT", 0.9))
    }

    @Test
    fun qty_at_or_above_min_volume_survives_flooring() {
        assertEquals("1", engine.formatQty("DOGEUSDT", 1.9))
    }

    @Test
    fun qty_never_gets_scientific_notation_at_zero_decimals() {
        assertEquals("12345", engine.formatQty("DOGEUSDT", 12345.67))
    }

    @Test
    fun qty_falls_back_to_six_decimals_without_pair_info() {
        assertEquals("0.123457", engine.formatQty("UNKNOWNUSDT", 0.1234567, null))
    }

    @Test
    fun non_positive_qty_is_rejected() {
        assertNull(engine.formatQty("BTCUSDT", 0.0))
    }

    @Test
    fun price_is_floored_to_quote_precision() {
        assertEquals("65432.1", engine.formatPrice("BTCUSDT", 65432.156))
    }

    @Test
    fun price_keeps_five_decimals_when_quote_precision_is_five() {
        assertEquals("0.06159", engine.formatPrice("DOGEUSDT", 0.061599))
    }

    @Test
    fun price_uses_default_one_decimal_without_pair_info() {
        assertEquals("65432.1", engine.formatPrice("UNKNOWNUSDT", 65432.156, null))
    }

    @Test
    fun leverage_clamp_overload() {
        assertEquals(125, engine.effectiveLeverage(500, 125))
        assertEquals(500, engine.effectiveLeverage(500, null))
        assertEquals(10, engine.effectiveLeverage(10, 125))
    }

    @Test
    fun leverage_is_clamped_to_symbol_max() {
        engine.leverage = 500
        assertEquals(125, engine.effectiveLeverage("BTCUSDT"))
        assertEquals(20, engine.effectiveLeverage("DOGEUSDT"))
        engine.leverage = 10
        assertEquals(10, engine.effectiveLeverage("DOGEUSDT"))
    }

    @Test
    fun big_decimal_format_floors_and_strips_zeroes() {
        assertEquals("65432.1", BigDecimalFormat.format(65432.156, 1))
        assertEquals("2", BigDecimalFormat.format(2.0, 3))
    }
}
