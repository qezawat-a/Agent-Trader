package com.bitunix.agenttrader.trader

/**
 * How much margin one position may commit: the configured share of the
 * FREE balance — no minimum, no invented caps.
 */
object MarginSizing {

    /**
     * Margin one position may use: [available] × [marginPct] percent of it.
     */
    fun compute(available: Double, marginPct: Double): Double = available * marginPct / 100.0
}

