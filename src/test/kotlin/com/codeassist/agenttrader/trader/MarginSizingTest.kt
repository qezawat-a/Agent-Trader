package com.codeassist.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Margin sizing: the configured share of the FREE balance — no minimum,
 * no invented caps.
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
    fun the_share_is_not_capped() {
        // No floor at the top either: the user sets the share.
        assertEquals(1200.0, MarginSizing.compute(1000.0, 120.0))
    }

    @Test
    fun an_empty_account_commits_nothing() {
        assertEquals(0.0, MarginSizing.compute(0.0, 10.0))
    }
}
