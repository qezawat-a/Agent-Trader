package com.codeassist.agenttrader.trader

import com.codeassist.agenttrader.bitunix.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Indicator helpers. Each returns null when there is not enough history. */
object Indicators {

    fun sma(v: List<Double>, n: Int): Double? =
        if (v.size < n) null else v.takeLast(n).average()

    fun emaSeries(v: List<Double>, n: Int): List<Double> {
        if (v.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Double>(v.size)
        var prev = v[0]
        out.add(prev)
        for (i in 1 until v.size) {
            prev = v[i] * k + prev * (1 - k)
            out.add(prev)
        }
        return out
    }

    fun ema(v: List<Double>, n: Int): Double? =
        if (v.size < n) null else emaSeries(v, n).last()

    fun dema(v: List<Double>, n: Int): Double? {
        if (v.size < 2 * n) return null
        val e1 = emaSeries(v, n)
        val e2 = emaSeries(e1, n)
        return 2 * e1.last() - e2.last()
    }

    fun tema(v: List<Double>, n: Int): Double? {
        if (v.size < 3 * n) return null
        val e1 = emaSeries(v, n)
        val e2 = emaSeries(e1, n)
        val e3 = emaSeries(e2, n)
        return 3 * e1.last() - 3 * e2.last() + e3.last()
    }

    /** Wilder RSI. */
    fun rsi(v: List<Double>, n: Int = 14): Double? {
        if (v.size <= n) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = v[i] - v[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgG = gain / n
        var avgL = loss / n
        for (i in n + 1 until v.size) {
            val d = v[i] - v[i - 1]
            avgG = (avgG * (n - 1) + max(d, 0.0)) / n
            avgL = (avgL * (n - 1) + max(-d, 0.0)) / n
        }
        if (avgL == 0.0) return 100.0
        return 100 - 100 / (1 + avgG / avgL)
    }

    /** MACD (12/26/9): current line, signal and histogram, plus the previous histogram. */
    data class Macd(val line: Double, val signal: Double, val hist: Double, val prevHist: Double)

    fun macd(v: List<Double>): Macd? {
        if (v.size < 35) return null
        val fast = emaSeries(v, 12)
        val slow = emaSeries(v, 26)
        val line = List(v.size) { fast[it] - slow[it] }
        val signal = emaSeries(line, 9)
        val hist = List(v.size) { line[it] - signal[it] }
        return Macd(line.last(), signal.last(), hist.last(), hist[hist.size - 2])
    }

    data class Bands(val lower: Double, val mid: Double, val upper: Double, val width: Double)

    fun bollinger(v: List<Double>, n: Int = 20, k: Double = 2.0): Bands? {
        if (v.size < n) return null
        val w = v.takeLast(n)
        val mid = w.average()
        val sd = sqrt(w.sumOf { (it - mid) * (it - mid) } / n)
        return Bands(mid - k * sd, mid, mid + k * sd, if (mid == 0.0) 0.0 else 2 * k * sd / mid)
    }

    /** Session VWAP over the candles given (typical price weighted by volume). */
    fun vwap(c: List<Candle>): Double? {
        val vol = c.sumOf { it.volume }
        if (c.isEmpty() || vol == 0.0) return null
        return c.sumOf { (it.high + it.low + it.close) / 3 * it.volume } / vol
    }

    fun atr(c: List<Candle>, n: Int = 14): Double? {
        if (c.size <= n) return null
        val trs = (1 until c.size).map { i ->
            val prevClose = c[i - 1].close
            max(c[i].high - c[i].low, max(abs(c[i].high - prevClose), abs(c[i].low - prevClose)))
        }
        return trs.takeLast(n).average()
    }
}

/** Candle patterns over the most recent candles. */
object CandlePatterns {
    private fun body(k: Candle) = abs(k.close - k.open)
    private fun upper(k: Candle) = k.high - max(k.open, k.close)
    private fun lower(k: Candle) = minOf(k.open, k.close) - k.low
    private fun bull(k: Candle) = k.close > k.open
    private fun bear(k: Candle) = k.close < k.open
    private fun mid(k: Candle) = (k.open + k.close) / 2

    fun hammer(c: List<Candle>): Boolean {
        if (c.size < 6) return false
        val k = c.last()
        val downtrend = c[c.size - 6].close > k.close
        return downtrend && body(k) > 0 && lower(k) >= 2 * body(k) && upper(k) <= body(k)
    }

    fun shootingStar(c: List<Candle>): Boolean {
        if (c.size < 6) return false
        val k = c.last()
        val uptrend = c[c.size - 6].close < k.close
        return uptrend && body(k) > 0 && upper(k) >= 2 * body(k) && lower(k) <= body(k)
    }

    fun morningStar(c: List<Candle>): Boolean {
        if (c.size < 3) return false
        val a = c[c.size - 3]
        val b = c[c.size - 2]
        val k = c.last()
        return bear(a) && body(a) > 0 && body(b) < 0.3 * body(a) && bull(k) && k.close > mid(a)
    }

    fun darkCloud(c: List<Candle>): Boolean {
        if (c.size < 2) return false
        val a = c[c.size - 2]
        val k = c.last()
        return bull(a) && bear(k) && k.open > a.high && k.close < mid(a)
    }

    fun threeWhite(c: List<Candle>): Boolean {
        if (c.size < 3) return false
        val w = c.takeLast(3)
        return w.all { bull(it) } && w[1].close > w[0].close && w[2].close > w[1].close
    }

    fun threeBlack(c: List<Candle>): Boolean {
        if (c.size < 3) return false
        val w = c.takeLast(3)
        return w.all { bear(it) } && w[1].close < w[0].close && w[2].close < w[1].close
    }
}
