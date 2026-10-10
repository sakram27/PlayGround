package com.aether.signal.premium.engine

// Port 1:1 dari STRATEGIES core.js. NONE = Triple("NONE", 0, []).

data class StrategyMeta(val id: String, val name: String, val desc: String)

private fun sig(dir: String, conf: Double, reasons: List<String>) = Triple(dir, conf, reasons)
private fun none() = Triple("NONE", 0.0, emptyList<String>())

typealias StratFn = (c: List<Candle>, i: Int, x: Cache, p: Map<String, Double>) -> Triple<String, Double, List<String>>

val STRATEGIES: Map<String, Pair<String, String>> = mapOf(
    "ema_trend" to ("EMA Trend" to "Close > EMA50 + EMA20 > EMA50 = LONG; sebaliknya SHORT."),
    "ema_cross" to ("EMA Cross 9/21" to "Golden/death cross EMA9 vs EMA21."),
    "rsi" to ("RSI Reversal" to "RSI<30 jenuh jual (LONG), RSI>70 jenuh beli (SHORT)."),
    "macd" to ("MACD" to "Histogram cross nol searah tren EMA50."),
    "bollinger" to ("Bollinger Mean-Revert" to "Reject lower band = LONG; reject upper = SHORT."),
    "stochastic" to ("Stochastic" to "Cross %K/%D di zona ekstrem."),
    "adx" to ("ADX Trend" to "ADX>25 + close di sisi EMA20 yang benar."),
    "supertrend" to ("Supertrend" to "Flip arah supertrend."),
    "vwap" to ("VWAP Revert" to "Deviasi jauh dari VWAP + wick reject."),
    "support_resistance" to ("Support / Resistance" to "Bounce dari swing 20-candle."),
    "breakout" to ("Breakout 20" to "Close menembus Donchian 20 + volume confirm."),
    "pullback" to ("Pullback EMA21" to "Tren EMA50 + pullback ke EMA21 + engulfing."),
    "market_structure" to ("Market Structure" to "HH/HL = LONG bias, LH/LL = SHORT bias."),
    "bos" to ("BOS" to "Break of Structure searah candle momentum."),
    "choch" to ("CHoCH" to "Change of character + close kembali."),
    "liquidity_sweep" to ("Liquidity Sweep" to "Sweep low/high + reclaim cepat."),
    "order_block" to ("Order Block" to "Impuls + kembali ke zona OB + reject."),
    "fvg" to ("Fair Value Gap" to "Gap 3-candle + fill separuh + lanjut."),
    "breaker" to ("Breaker Block" to "BOS gagal (false break) + kembali dalam range."),
    "fibonacci" to ("Fibonacci" to "Retrace 0.5–0.618 dari swing 30 + reject."),
    "smc_basic" to ("SMC Basic" to "BOS + displacement searah."),
    "ict_setup" to ("ICT Setup" to "Sweep + market-structure-shift + entry."),
    "volume" to ("Volume Spike" to "Lonjakan volume 2x + body kuat."),
    "mtf_confirm" to ("MTF Confirm" to "EMA50 + RSI filter (proxy multi-timeframe)."),
    "donchian_momentum" to ("Donchian Momentum" to "Mid-channel cross + RSI momentum.")
)

fun strategyList(): List<StrategyMeta> = STRATEGIES.map { StrategyMeta(it.key, it.value.first, it.value.second) }

fun strategyFn(id: String, c: List<Candle>, i: Int, x: Cache, p: Map<String, Double>): Triple<String, Double, List<String>> {
    return when (id) {
        "ema_trend" -> {
            if (i < 50 || x.e20[i].isNaN() || x.e50[i].isNaN()) none()
            else {
                val bull = c[i].c > x.e50[i] && x.e20[i] > x.e50[i]
                val bear = c[i].c < x.e50[i] && x.e20[i] < x.e50[i]
                when {
                    bull -> sig("LONG", 0.6, listOf("close>EMA50", "EMA20>EMA50"))
                    bear -> sig("SHORT", 0.6, listOf("close<EMA50", "EMA20<EMA50"))
                    else -> none()
                }
            }
        }
        "ema_cross" -> {
            if (i < 22 || x.e9[i].isNaN() || x.e21[i].isNaN() || x.e9[i - 1].isNaN() || x.e21[i - 1].isNaN()) none()
            else when {
                x.e9[i - 1] <= x.e21[i - 1] && x.e9[i] > x.e21[i] -> sig("LONG", 0.65, listOf("EMA9 cross-up EMA21"))
                x.e9[i - 1] >= x.e21[i - 1] && x.e9[i] < x.e21[i] -> sig("SHORT", 0.65, listOf("EMA9 cross-down EMA21"))
                else -> none()
            }
        }
        "rsi" -> {
            val os = p["oversold"] ?: 30.0; val ob = p["overbought"] ?: 70.0
            val r = x.rsi14[i]; if (r.isNaN()) none()
            else when {
                r < os && c[i].c > c[i - 1].c -> sig("LONG", 0.6, listOf("RSI oversold " + fmt1(r)))
                r > ob && c[i].c < c[i - 1].c -> sig("SHORT", 0.6, listOf("RSI overbought " + fmt1(r)))
                else -> none()
            }
        }
        "macd" -> {
            val h = x.macd.hist
            if (i < 27 || h[i].isNaN() || h[i - 1].isNaN()) none()
            else {
                val up = !x.e50[i].isNaN() && c[i].c > x.e50[i]
                val dn = !x.e50[i].isNaN() && c[i].c < x.e50[i]
                when {
                    h[i - 1] <= 0 && h[i] > 0 && up -> sig("LONG", 0.62, listOf("MACD cross-up"))
                    h[i - 1] >= 0 && h[i] < 0 && dn -> sig("SHORT", 0.62, listOf("MACD cross-down"))
                    else -> none()
                }
            }
        }
        "bollinger" -> {
            if (i < 20 || x.bb.lower[i].isNaN()) none()
            else when {
                c[i].l <= x.bb.lower[i] && c[i].c > x.bb.lower[i] -> sig("LONG", 0.58, listOf("lower-band reject"))
                c[i].h >= x.bb.upper[i] && c[i].c < x.bb.upper[i] -> sig("SHORT", 0.58, listOf("upper-band reject"))
                else -> none()
            }
        }
        "stochastic" -> {
            val k = x.stoch.k; val d = x.stoch.d
            if (i < 16 || k[i].isNaN() || d[i].isNaN() || k[i - 1].isNaN() || d[i - 1].isNaN()) none()
            else when {
                k[i - 1] <= d[i - 1] && k[i] > d[i] && k[i] < 30 -> sig("LONG", 0.6, listOf("stoch cross-up <30"))
                k[i - 1] >= d[i - 1] && k[i] < d[i] && k[i] > 70 -> sig("SHORT", 0.6, listOf("stoch cross-down >70"))
                else -> none()
            }
        }
        "adx" -> {
            val a = x.adx14[i]
            if (a.isNaN() || x.e20[i].isNaN()) none()
            else {
                val th = p["adxMin"] ?: 25.0
                if (a < th) none()
                else when {
                    c[i].c > x.e20[i] -> sig("LONG", 0.55, listOf("ADX " + fmt1(a)))
                    c[i].c < x.e20[i] -> sig("SHORT", 0.55, listOf("ADX " + fmt1(a)))
                    else -> none()
                }
            }
        }
        "supertrend" -> {
            if (i < 11) none()
            else when {
                x.st.dir[i] == 1 && x.st.dir[i - 1] == -1 -> sig("LONG", 0.66, listOf("supertrend flip bull"))
                x.st.dir[i] == -1 && x.st.dir[i - 1] == 1 -> sig("SHORT", 0.66, listOf("supertrend flip bear"))
                else -> none()
            }
        }
        "vwap" -> {
            if (i < 5 || x.vw[i].isNaN()) none()
            else {
                val dev = (c[i].c - x.vw[i]) / x.vw[i]
                when {
                    dev < -0.004 && c[i].c > c[i].o -> sig("LONG", 0.55, listOf("di bawah VWAP, reject"))
                    dev > 0.004 && c[i].c < c[i].o -> sig("SHORT", 0.55, listOf("di atas VWAP, reject"))
                    else -> none()
                }
            }
        }
        "support_resistance" -> {
            if (i < 21) none()
            else {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (j in i - 20 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                when {
                    Math.abs(c[i].l - ll) / ll < 0.001 && c[i].c > c[i].o -> sig("LONG", 0.55, listOf("support bounce"))
                    Math.abs(c[i].h - hh) / hh < 0.001 && c[i].c < c[i].o -> sig("SHORT", 0.55, listOf("resistance reject"))
                    else -> none()
                }
            }
        }
        "breakout" -> {
            if (i < 21) none()
            else {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (j in i - 20 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                val avgV = x.sVol20[i]
                when {
                    c[i].c > hh && c[i].v >= avgV * 1.2 -> sig("LONG", 0.63, listOf("breakout high20 vol-ok"))
                    c[i].c < ll && c[i].v >= avgV * 1.2 -> sig("SHORT", 0.63, listOf("breakdown low20 vol-ok"))
                    else -> none()
                }
            }
        }
        "pullback" -> {
            if (i < 51 || x.e21[i].isNaN() || x.e50[i].isNaN()) none()
            else {
                val upTrend = x.e21[i] > x.e50[i]
                val dnTrend = x.e21[i] < x.e50[i]
                val nearEma = Math.abs(c[i].l - x.e21[i]) / x.e21[i] < 0.003 || Math.abs(c[i].h - x.e21[i]) / x.e21[i] < 0.003
                val bullEng = c[i].c > c[i].o && c[i].c >= c[i - 1].o && c[i].o <= c[i - 1].c
                val bearEng = c[i].c < c[i].o && c[i].o >= c[i - 1].c && c[i].c <= c[i - 1].o
                when {
                    upTrend && nearEma && bullEng -> sig("LONG", 0.62, listOf("pullback bull"))
                    dnTrend && nearEma && bearEng -> sig("SHORT", 0.62, listOf("pullback bear"))
                    else -> none()
                }
            }
        }
        "market_structure" -> {
            if (i < 6) none()
            else {
                val hh = c[i - 2].h < c[i].h && (c.getOrNull(i - 4)?.h ?: Double.POSITIVE_INFINITY) < c[i - 2].h
                val hl = c[i - 2].l < c[i].l && (c.getOrNull(i - 4)?.l ?: Double.POSITIVE_INFINITY) < c[i - 2].l
                val lh = c[i - 2].h > c[i].h && (c.getOrNull(i - 4)?.h ?: Double.NEGATIVE_INFINITY) > c[i - 2].h
                val ll = c[i - 2].l > c[i].l && (c.getOrNull(i - 4)?.l ?: Double.NEGATIVE_INFINITY) > c[i - 2].l
                when {
                    (hh || hl) && c[i].c > c[i].o -> sig("LONG", 0.56, listOf("struktur bullish"))
                    (lh || ll) && c[i].c < c[i].o -> sig("SHORT", 0.56, listOf("struktur bearish"))
                    else -> none()
                }
            }
        }
        "bos" -> {
            if (i < 11) none()
            else {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (j in i - 10 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                val body = Math.abs(c[i].c - c[i].o) / c[i].o
                when {
                    c[i].c > hh && body > 0.002 -> sig("LONG", 0.6, listOf("BOS up"))
                    c[i].c < ll && body > 0.002 -> sig("SHORT", 0.6, listOf("BOS down"))
                    else -> none()
                }
            }
        }
        "choch" -> {
            if (i < 12 || x.e21[i].isNaN()) none()
            else {
                val wasDn = c[i - 2].c < x.e21[i - 2]
                val nowUp = c[i].c > x.e21[i] && c[i].c > c[i - 1].h
                val wasUp = c[i - 2].c > x.e21[i - 2]
                val nowDn = c[i].c < x.e21[i] && c[i].c < c[i - 1].l
                when {
                    wasDn && nowUp -> sig("LONG", 0.6, listOf("CHoCH bull"))
                    wasUp && nowDn -> sig("SHORT", 0.6, listOf("CHoCH bear"))
                    else -> none()
                }
            }
        }
        "liquidity_sweep" -> {
            if (i < 21) none()
            else {
                var ll = Double.POSITIVE_INFINITY; var hh = Double.NEGATIVE_INFINITY
                for (j in i - 20 until i) { ll = minOf(ll, c[j].l); hh = maxOf(hh, c[j].h) }
                when {
                    c[i].l < ll && c[i].c > ll && c[i].c > c[i].o -> sig("LONG", 0.61, listOf("sweep low reclaim"))
                    c[i].h > hh && c[i].c < hh && c[i].c < c[i].o -> sig("SHORT", 0.61, listOf("sweep high reclaim"))
                    else -> none()
                }
            }
        }
        "order_block" -> {
            if (i < 6) none()
            else {
                val impUp = (c[i - 3].c - c[i - 3].o) / c[i - 3].o > 0.006
                val impDn = (c[i - 3].o - c[i - 3].c) / c[i - 3].o > 0.006
                when {
                    impUp && c[i].l <= c[i - 3].o && c[i].c > c[i].o -> sig("LONG", 0.57, listOf("OB bull retap"))
                    impDn && c[i].h >= c[i - 3].o && c[i].c < c[i].o -> sig("SHORT", 0.57, listOf("OB bear retap"))
                    else -> none()
                }
            }
        }
        "fvg" -> {
            if (i < 4) none()
            else {
                val gapUp = c[i - 2].l > c[i - 3].h
                val gapDn = c[i - 2].h < c[i - 3].l
                when {
                    gapUp && c[i].l <= (c[i - 2].l + c[i - 3].h) / 2 && c[i].c > c[i].o -> sig("LONG", 0.56, listOf("FVG bull"))
                    gapDn && c[i].h >= (c[i - 2].h + c[i - 3].l) / 2 && c[i].c < c[i].o -> sig("SHORT", 0.56, listOf("FVG bear"))
                    else -> none()
                }
            }
        }
        "breaker" -> {
            if (i < 12) none()
            else {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (j in i - 11 until i - 1) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                when {
                    c[i - 1].h > hh && c[i].c < hh && c[i].c < c[i].o -> sig("SHORT", 0.58, listOf("breaker bear"))
                    c[i - 1].l < ll && c[i].c > ll && c[i].c > c[i].o -> sig("LONG", 0.58, listOf("breaker bull"))
                    else -> none()
                }
            }
        }
        "fibonacci" -> {
            if (i < 31) none()
            else {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (j in i - 30 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                val range = hh - ll
                if (range <= 0) none()
                else {
                    val rUp = (hh - c[i].c) / range
                    val rDn = (c[i].c - ll) / range
                    when {
                        rUp > 0.45 && rUp < 0.68 && c[i].c > c[i].o && c[i - 1].c < c[i].c -> sig("LONG", 0.55, listOf("fib golden bull"))
                        rDn > 0.45 && rDn < 0.68 && c[i].c < c[i].o && c[i - 1].c > c[i].c -> sig("SHORT", 0.55, listOf("fib golden bear"))
                        else -> none()
                    }
                }
            }
        }
        "smc_basic" -> {
            if (i < 11) none()
            else {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (j in i - 10 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                val disp = Math.abs(c[i].c - c[i].o) / c[i].o
                val range = hh - ll
                when {
                    c[i].c > hh && disp > 0.003 && range > 0 -> sig("LONG", 0.6, listOf("SMC BOS+displacement"))
                    c[i].c < ll && disp > 0.003 && range > 0 -> sig("SHORT", 0.6, listOf("SMC BOS+displacement"))
                    else -> none()
                }
            }
        }
        "ict_setup" -> {
            if (i < 21) none()
            else {
                // V22 FIX: jendela 20 bar TIDAK mencakup bar sweep (i-1) itu sendiri.
                // Sebelumnya `i-20 until i` membuat min <= low bar sweep selalu,
                // sehingga sweepLow/sweepHigh mustahil benar → strategi mati total.
                var ll = Double.POSITIVE_INFINITY; var hh = Double.NEGATIVE_INFINITY
                for (j in i - 21 until i - 1) { ll = minOf(ll, c[j].l); hh = maxOf(hh, c[j].h) }
                val sweepLow = c[i - 1].l < ll
                val sweepHigh = c[i - 1].h > hh
                when {
                    sweepLow && c[i].c > c[i - 1].h && c[i].c > c[i].o -> sig("LONG", 0.62, listOf("ICT sweep+mss"))
                    sweepHigh && c[i].c < c[i - 1].l && c[i].c < c[i].o -> sig("SHORT", 0.62, listOf("ICT sweep+mss"))
                    else -> none()
                }
            }
        }
        "volume" -> {
            if (i < 21) none()
            else {
                val avg = x.sVol20[i]
                if (avg == 0.0 || avg.isNaN()) none()
                else {
                    val body = (c[i].c - c[i].o) / c[i].o
                    when {
                        c[i].v > avg * 2 && body > 0.003 -> sig("LONG", 0.57, listOf("vol spike bull"))
                        c[i].v > avg * 2 && body < -0.003 -> sig("SHORT", 0.57, listOf("vol spike bear"))
                        else -> none()
                    }
                }
            }
        }
        "mtf_confirm" -> {
            if (i < 50 || x.e50[i].isNaN() || x.rsi14[i].isNaN()) none()
            else when {
                c[i].c > x.e50[i] && x.rsi14[i] > 55 && x.rsi14[i] < 75 -> sig("LONG", 0.58, listOf("MTF bull confirm"))
                c[i].c < x.e50[i] && x.rsi14[i] < 45 && x.rsi14[i] > 25 -> sig("SHORT", 0.58, listOf("MTF bear confirm"))
                else -> none()
            }
        }
        "donchian_momentum" -> {
            if (i < 21 || x.rsi14[i].isNaN()) none()
            else {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (j in i - 20 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                val mid = (hh + ll) / 2
                when {
                    c[i].c > mid && c[i - 1].c <= mid && x.rsi14[i] > 50 -> sig("LONG", 0.57, listOf("donchian mid cross-up"))
                    c[i].c < mid && c[i - 1].c >= mid && x.rsi14[i] < 50 -> sig("SHORT", 0.57, listOf("donchian mid cross-down"))
                    else -> none()
                }
            }
        }
        else -> none()
    }
}

// toFixed(1) JS: 1 desimal. Kotlin String.format Locale.US.
fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
