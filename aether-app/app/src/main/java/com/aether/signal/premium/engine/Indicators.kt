package com.aether.signal.premium.engine

import kotlin.math.*

// Port 1:1 dari indikator core.js. Array memakai Double.NaN untuk nilai null JS.

fun ema(values: List<Double>, period: Int): DoubleArray {
    val out = DoubleArray(values.size) { Double.NaN }
    if (values.size < period || period < 1) return out
    val k = 2.0 / (period + 1)
    var prev = 0.0
    for (i in values.indices) {
        if (i + 1 < period) continue
        if (i + 1 == period) {
            var s = 0.0
            for (j in i - period + 1..i) s += values[j]
            prev = s / period
        } else {
            prev = values[i] * k + prev * (1 - k)
        }
        out[i] = prev
    }
    return out
}

fun sma(values: List<Double>, period: Int): DoubleArray {
    val out = DoubleArray(values.size) { Double.NaN }
    if (period < 1) return out
    var s = 0.0
    for (i in values.indices) {
        s += values[i]
        if (i >= period) s -= values[i - period]
        if (i + 1 >= period) out[i] = s / period
    }
    return out
}

fun rsi(closes: List<Double>, period: Int = 14): DoubleArray {
    val out = DoubleArray(closes.size) { Double.NaN }
    if (closes.size <= period) return out
    var gain = 0.0; var loss = 0.0
    for (i in 1..period) {
        val d = closes[i] - closes[i - 1]
        if (d >= 0) gain += d else loss -= d
    }
    gain /= period; loss /= period
    out[period] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    for (i in period + 1 until closes.size) {
        val d = closes[i] - closes[i - 1]
        gain = (gain * (period - 1) + max(d, 0.0)) / period
        loss = (loss * (period - 1) + max(-d, 0.0)) / period
        out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    }
    return out
}

fun atr(candles: List<Candle>, period: Int = 14): DoubleArray {
    val out = DoubleArray(candles.size) { Double.NaN }
    if (candles.size <= period) return out
    val trs = DoubleArray(candles.size)
    for (i in candles.indices) {
        trs[i] = if (i == 0) candles[0].h - candles[0].l else maxOf(
            candles[i].h - candles[i].l,
            abs(candles[i].h - candles[i - 1].c),
            abs(candles[i].l - candles[i - 1].c)
        )
    }
    var a = trs.slice(0 until period).sum() / period
    out[period - 1] = a
    for (i in period until candles.size) { a = (a * (period - 1) + trs[i]) / period; out[i] = a }
    return out
}

data class Macd(val line: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd {
    val ef = ema(closes, fast); val es = ema(closes, slow)
    val line = DoubleArray(closes.size) { i ->
        if (ef[i].isNaN() || es[i].isNaN()) Double.NaN else ef[i] - es[i]
    }
    val valid = line.filter { !it.isNaN() }
    val sigValid = ema(valid, signal)
    val sig = DoubleArray(closes.size) { Double.NaN }
    var k = 0
    for (i in closes.indices) {
        if (!line[i].isNaN()) { sig[i] = if (k < sigValid.size) sigValid[k++] else Double.NaN }
    }
    val hist = DoubleArray(closes.size) { i ->
        if (line[i].isNaN() || sig[i].isNaN()) Double.NaN else line[i] - sig[i]
    }
    return Macd(line, sig, hist)
}

data class Bollinger(val mid: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)

fun bollinger(closes: List<Double>, period: Int = 20, mult: Double = 2.0): Bollinger {
    val mid = sma(closes, period)
    val upper = DoubleArray(closes.size) { Double.NaN }
    val lower = DoubleArray(closes.size) { Double.NaN }
    for (i in closes.indices) {
        if (mid[i].isNaN()) continue
        var s = 0.0
        for (j in i - period + 1..i) s += (closes[j] - mid[i]) * (closes[j] - mid[i])
        val sd = sqrt(s / period)
        upper[i] = mid[i] + mult * sd
        lower[i] = mid[i] - mult * sd
    }
    return Bollinger(mid, upper, lower)
}

data class Stoch(val k: DoubleArray, val d: DoubleArray)

fun stochastic(candles: List<Candle>, kPeriod: Int = 14, dPeriod: Int = 3): Stoch {
    val k = DoubleArray(candles.size) { Double.NaN }
    for (i in kPeriod - 1 until candles.size) {
        var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
        for (j in i - kPeriod + 1..i) { hh = max(hh, candles[j].h); ll = min(ll, candles[j].l) }
        k[i] = if (hh == ll) 50.0 else ((candles[i].c - ll) / (hh - ll)) * 100
    }
    val kv = List(candles.size) { if (k[it].isNaN()) 50.0 else k[it] }
    val dRaw = sma(kv, dPeriod)
    val d = DoubleArray(candles.size) { i -> if (k[i].isNaN()) Double.NaN else dRaw[i] }
    return Stoch(k, d)
}

data class Supertrend(val dir: IntArray, val line: DoubleArray)

fun supertrend(candles: List<Candle>, period: Int = 10, mult: Double = 3.0): Supertrend {
    val a = atr(candles, period)
    val dir = IntArray(candles.size) { 0 }
    val line = DoubleArray(candles.size) { Double.NaN }
    var up = 0.0; var dn = 0.0
    for (i in candles.indices) {
        if (a[i].isNaN()) continue
        val hl2 = (candles[i].h + candles[i].l) / 2
        val nbUp = hl2 - mult * a[i]; val nbDn = hl2 + mult * a[i]
        if (i == 0 || a[i - 1].isNaN()) { up = nbUp; dn = nbDn } else {
            up = if (candles[i - 1].c > up) max(nbUp, up) else nbUp
            dn = if (candles[i - 1].c < dn) min(nbDn, dn) else nbDn
        }
        dir[i] = when {
            i == 0 || dir[i - 1] == 0 -> if (candles[i].c >= (up + dn) / 2) 1 else -1
            dir[i - 1] == 1 && candles[i].c < up -> -1
            dir[i - 1] == -1 && candles[i].c > dn -> 1
            else -> dir[i - 1]
        }
        line[i] = if (dir[i] == 1) up else dn
    }
    return Supertrend(dir, line)
}

fun vwap(candles: List<Candle>): DoubleArray {
    val out = DoubleArray(candles.size) { Double.NaN }
    if (candles.isEmpty()) return out
    var pv = 0.0; var vv = 0.0
    var curDay = epochDay(candles[0].t)
    for (i in candles.indices) {
        val d = epochDay(candles[i].t)
        if (d != curDay) { pv = 0.0; vv = 0.0; curDay = d }
        val tp = (candles[i].h + candles[i].l + candles[i].c) / 3
        pv += tp * candles[i].v; vv += candles[i].v
        out[i] = if (vv > 0) pv / vv else candles[i].c
    }
    return out
}

fun adx(candles: List<Candle>, period: Int = 14): DoubleArray {
    val out = DoubleArray(candles.size) { Double.NaN }
    if (candles.size <= period * 2) return out
    var spDM = 0.0; var smDM = 0.0; var sTR = 0.0
    val dxs = ArrayList<Double>()
    for (i in 1 until candles.size) {
        val upM = candles[i].h - candles[i - 1].h
        val dnM = candles[i - 1].l - candles[i].l
        val pDM = if (upM > dnM && upM > 0) upM else 0.0
        val mDM = if (dnM > upM && dnM > 0) dnM else 0.0
        val tr = maxOf(candles[i].h - candles[i].l, abs(candles[i].h - candles[i - 1].c), abs(candles[i].l - candles[i - 1].c))
        if (i <= period) { spDM += pDM; smDM += mDM; sTR += tr } else {
            spDM = spDM - spDM / period + pDM
            smDM = smDM - smDM / period + mDM
            sTR = sTR - sTR / period + tr
            val pDI = if (sTR == 0.0) 0.0 else (spDM / sTR) * 100
            val mDI = if (sTR == 0.0) 0.0 else (smDM / sTR) * 100
            dxs.add(if (pDI + mDI == 0.0) 0.0 else (abs(pDI - mDI) / (pDI + mDI)) * 100)
        }
    }
    var adxV = dxs.slice(0 until period).sum() / period
    for (i in dxs.indices) {
        if (i < period - 1) continue
        if (i == period - 1) out[period * 2 - 1] = adxV
        else { adxV = (adxV * (period - 1) + dxs[i]) / period; out[period + 1 + i] = adxV }
    }
    return out
}

class Cache(
    val closes: List<Double>,
    val e20: DoubleArray, val e50: DoubleArray, val e200: DoubleArray,
    val e55: DoubleArray, val e9: DoubleArray, val e21: DoubleArray,
    val s20: DoubleArray, val s50: DoubleArray, val sVol20: DoubleArray,
    val rsi14: DoubleArray, val atr14: DoubleArray, val adx14: DoubleArray,
    val macd: Macd, val bb: Bollinger, val stoch: Stoch, val st: Supertrend, val vw: DoubleArray
)

fun buildCache(candles: List<Candle>): Cache {
    val closes = candles.map { it.c }
    val vols = candles.map { it.v }
    return Cache(
        closes,
        ema(closes, 20), ema(closes, 50), ema(closes, 200),
        ema(closes, 55), ema(closes, 9), ema(closes, 21),
        sma(closes, 20), sma(closes, 50), sma(vols.map { it }, 20),
        rsi(closes, 14), atr(candles, 14), adx(candles, 14),
        macd(closes), bollinger(closes), stochastic(candles), supertrend(candles), vwap(candles)
    )
}
