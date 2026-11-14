package com.codeassist.agenttrader.util

import java.util.Locale

/**
 * Number formatting for the whole app.
 *
 * Every format is locale-independent (Locale.US). Kotlin's `String.format`
 * extension uses the device locale, so a phone set to e.g. German or Persian
 * would print "1,2345" instead of "1.2345" — wrong for a trading app where
 * prices, quantities and percentages are read back by the user and by the
 * exchange-facing code.
 *
 * One helper per precision keeps the call sites free of magic format strings
 * ("%.2f" scattered over 40 lines used to be the norm).
 */
fun fmt(v: Double): String = decimal(v, 4)

/** One decimal — percentages with a meaningful fraction (volatility, PnL%). */
fun fmt1(v: Double): String = decimal(v, 1)

/** Two decimals — balances and prices in the UI. */
fun fmt2(v: Double): String = decimal(v, 2)

/** Three decimals — funding rates. */
fun fmt3(v: Double): String = decimal(v, 3)

/** Six decimals — raw quantities before the symbol precision is applied. */
fun fmt6(v: Double): String = decimal(v, 6)

/** Zero decimals — whole-number percentages (confidence, ROI, thresholds). */
fun fmt0(v: Double): String = decimal(v, 0)

private fun decimal(v: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", v)
