package com.codeassist.agenttrader.trader

import kotlin.math.ceil
import kotlin.math.pow

/**
 * How much margin one position may commit.
 *
 *  1. the configured share of the available balance;
 *  2. a HARD CAP: the book can never hold more margin than the balance divided
 *     by the number of free position slots. Without it, 5 positions at 60% each
 *     ask for 300% of the balance and the exchange simply refuses;
 *  3. a floor at the minimum margin that still clears the exchange's minimum
 *     order size, so a small account can open a position at all.
 */
object MarginSizing {

    /**
     * Margin one position may use.
     *
     * @param available free balance on the exchange (USDT)
     * @param marginPct configured share of the balance per trade (percent)
     * @param openPositions positions already open
     * @param maxPositions the configured maximum number of open positions
     */
    fun compute(available: Double, marginPct: Double, openPositions: Int, maxPositions: Int): Double {
        if (available <= 0.0) return 0.0
        val pct = (marginPct / 100.0).coerceIn(0.0001, 1.0)
        var margin = available * pct
        // Hard cap: the remaining slots must be able to carry the rest.
        val slotsLeft = maxOf(1, maxPositions - openPositions)
        val cap = available / slotsLeft
        if (margin > cap) margin = cap
        return maxOf(0.0, margin)
    }

    /**
     * Minimum position value (notional) the exchange accepts for a symbol:
     * the minimum order quantity in base coins, rounded UP to [precision]
     * digits, multiplied by the price.
     */
    fun minOrderNotional(price: Double, minTradeVolume: Double, precision: Int): Double {
        if (price <= 0.0 || minTradeVolume <= 0.0) return 0.0
        val f = 10.0.pow(precision.coerceIn(0, 12).toDouble())
        val minQty = ceil(minTradeVolume * f) / f
        return minQty * price
    }

    /**
     * Margin needed so that qty = minTradeVolume at the given leverage:
     *   qty = margin * leverage / price  =>  minMargin = minTradeVolume * price / leverage
     */
    fun minMarginForMinOrder(
        price: Double,
        minTradeVolume: Double,
        leverage: Int,
        precision: Int,
    ): Double {
        if (leverage <= 0) return 0.0
        return minOrderNotional(price, minTradeVolume, precision) / leverage
    }
}
