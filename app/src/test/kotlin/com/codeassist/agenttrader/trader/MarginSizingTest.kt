package com.codeassist.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Margin sizing: the configured share of the FREE balance and the exchange's
 * minimum order size.
 */
class MarginSizingTest {

    @Test
    fun share_of_the_free_balance_is_used_as_set() {
        // 10% of 1000 = 100, nothing more and nothing less.
        assertEquals(100.0, MarginSizing.compute(1000.0, 10.0))
    }

    @Test
    fun the_size_is_never_shrunk_behind_the_users_back() {
        // The old rule divided the balance by the free position slots: with 3
        // max positions a 50% setting became 33%, and the more the book filled,
        // the smaller every new position got. The user sets the size; the app
        // uses that size.
        assertEquals(500.0, MarginSizing.compute(1000.0, 50.0))
        assertEquals(1000.0, MarginSizing.compute(1000.0, 100.0))
    }

    @Test
    fun the_margin_can_never_exceed_the_free_balance() {
        // 120% is not a thing: the share is clamped, so a position can never
        // ask for more than the account holds.
        assertEquals(1000.0, MarginSizing.compute(1000.0, 120.0))
    }

    @Test
    fun an_empty_account_gets_no_margin() {
        assertEquals(0.0, MarginSizing.compute(0.0, 10.0))
        assertEquals(0.0, MarginSizing.compute(-5.0, 10.0))
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
        assertEquals(0.1, MarginSizing.minMarginForMinOrder(100.0, 0.01, 10, 4), absoluteTolerance = 1e-9)
    }

    @Test
    fun the_floor_is_reachable_with_a_small_balance() {
        // A 5 USDT account with a 10% share commits 0.5 USDT, but one contract
        // needs 1 USDT of margin at 1x — the floor wins and the account can
        // still carry it.
        val computed = MarginSizing.compute(5.0, 10.0)
        val floor = MarginSizing.minMarginForMinOrder(1000.0, 0.001, 1, 4)
        assertTrue(floor > computed, "the exchange minimum is above the 10% share here")
        assertTrue(floor <= 5.0, "and the account can still carry it")
    }
}