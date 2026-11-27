package com.bitunix.agenttrader.trader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An empty futures wallet cannot fund an entry, and the exchange rejects every
 * attempt. The loop used to keep trying — once per symbol, once per tick — and
 * print an error line every cycle for a condition the user can fix in one tap.
 */
class EmptyWalletTest {

    @Test
    fun an_empty_wallet_is_explained_once() {
        val note = emptyFuturesWalletNote(0.0)!!
        assertTrue(note.contains("futures wallet is empty"), note)
        assertTrue(note.contains("Funding wallet"), note)
        assertTrue(note.contains("set live off"), note)
    }

    @Test
    fun a_funded_wallet_is_not_blocked() {
        assertNull(emptyFuturesWalletNote(100.0))
        assertNull(emptyFuturesWalletNote(1.0))
        assertNull(emptyFuturesWalletNote(TraderEngine.MIN_FUTURES_BALANCE_USDT))
    }

    @Test
    fun a_wallet_below_one_usdt_is_empty() {
        assertTrue(emptyFuturesWalletNote(0.99)!!.contains("empty"))
        assertTrue(emptyFuturesWalletNote(0.0)!!.contains("0.00"))
    }
}
