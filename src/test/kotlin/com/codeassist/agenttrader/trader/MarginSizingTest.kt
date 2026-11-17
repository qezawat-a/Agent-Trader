package com.codeassist.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Margin sizing: the share of the balance, the hard cap by free position slots
 * and the exchange's minimum order size.
 */
class MarginSizingTest {

    @Test
    fun share_of_the_balance_is_used_as_set() {
        // 10% of 1000 = 100, nothing more and nothing less.
        assertEquals(100.0, MarginSizing.compute(1000.0, 10.0, 0, 5))
    }

    @Test
    fun the_book_is_capped_by_the_free_slots() {
        // 60% of 1000 = 600, but 5 free slots can only carry 1000/5 = 200.
        val margin = MarginSizing.compute(1000.0, 60.0, 0, 5)
        assertEquals(200.0, margin)
    }

    @Test
    fun the_cap_uses_the_slots_that_are_left() {
        // 3 of 5 slots used -> 2 free -> cap 500.
        assertEquals(500.0, MarginSizing.compute(1000.0, 60.0, 3, 5))
    }

    @Test
    fun an_empty_account_gets_no_margin() {
        assertEquals(0.0, MarginSizing.compute(0.0, 10.0, 0, 5))
        assertEquals(0.0, MarginSizing.compute(-5.0, 10.0, 0, 5))
    }

    @Test
    fun minimum_order_size_is_rounded_up() {
        // 0.001 base coins at 3 decimals -> 0.001 * 100 = 0.1 USDT notional.
        assertEquals(0.1, MarginSizing.minOrderNotional(100.0, 0.001, 3), absoluteTolerance = 1e-9)
        // 0.0005 base coins at 3 decimals rounds UP to 0.001.
        assertEquals(0.1, MarginSizing.minOrderNotional(100.0, 0.0005, 3), absoluteTolerance = 1e-9)
    }

    @Test
    fun minimum_margin_covers_the_exchange_minimum() {
        // qty = margin * leverage / price  =>  margin = minQty * price / leverage
        val margin = MarginSizing.minMarginForMinOrder(100.0, 0.01, 10, 4)
        assertEquals(0.1, margin, absoluteTolerance = 1e-9)
    }

    @Test
    fun the_floor_is_reachable_with_a_small_balance() {
        // A 5 USDT account with a 10% share commits 0.5 USDT, but one contract
        // needs 1 USDT of margin at 1x — the floor wins and the account can
        // still carry it.
        val computed = MarginSizing.compute(5.0, 10.0, 0, 5)
        val floor = MarginSizing.minMarginForMinOrder(1000.0, 0.001, 1, 4)
        assertTrue(floor > computed, "the exchange minimum is above the 10% share here")
        assertTrue(floor <= 5.0, "and the account can still carry it")
    }
}
