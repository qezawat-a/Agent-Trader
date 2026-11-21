package com.codeassist.agenttrader.trader

import kotlin.math.ceil
import kotlin.math.pow

/**
 * How much margin one position may commit.
 *
 *  1. the configured share of the FREE balance;
 *  2. a floor at the minimum margin that still clears the exchange's minimum
 *     order size, so a small account can open a position at all.
 *
 * "Free" is the exchange's own word: the futures account reports `available`
 * as the "available quantity in the account" — the balance minus the margin
 * the open positions hold ("locked quantity of positions") minus the funds
 * frozen by open orders. A new position is a share of what is really free, so
 * the book can never ask for more than the account has.
 *
 * There is no second, invented cap. The old rule divided the balance by the
 * number of free position slots, so a 50% setting silently became 33% with
 * three open slots and kept shrinking as the book filled: the position never
 * grew to the size the user had set.
 */
object MarginSizing {

    /**
     * Margin one position may use.
     *
     * @param available the FREE balance (USDT): what is left after the open
     *   positions took their margin
     * @param marginPct configured share of the free balance per trade (percent)
     */
    fun compute(available: Double, marginPct: Double): Double {
        if (available <= 0.0) return 0.0
        val pct = (marginPct / 100.0).coerceIn(0.0001, 1.0)
        return available * pct
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
