package com.aether.signal.premium.engine

import kotlin.math.*

// Port 1:1 dari FILTERS core.js.

data class FilterMeta(val id: String, val name: String, val desc: String, val defaults: Map<String, Double>)

val FILTER_DEFS: List<FilterMeta> = listOf(
    FilterMeta("trend", "Trend Filter", "Harga harus di sisi EMA50 yang searah.", emptyMap()),
    FilterMeta("ema", "EMA Filter", "Harga harus selaras EMA55 (filter APK asli).", emptyMap()),
    FilterMeta("htf", "HTF Trend", "Tren besar via EMA200 harus searah.", emptyMap()),
    FilterMeta("volume", "Volume Filter", "Volume candle harus di atas rata-rata agar entry valid.", mapOf("mult" to 1.0)),
    FilterMeta("atr_vol", "ATR Volatility", "Volatilitas ATR% minimal agar pergerakan cukup.", mapOf("minPct" to 0.2)),
    FilterMeta("adx", "ADX Filter", "Blokir tren lemah (ADX di bawah ambang).", mapOf("min" to 20.0)),
    FilterMeta("rsi", "RSI Filter", "Blokir LONG jenuh-beli & SHORT jenuh-jual.", mapOf("longMax" to 72.0, "shortMin" to 28.0)),
    FilterMeta("ms", "Market Structure Filter", "Struktur HH/HL untuk LONG, LH/LL untuk SHORT.", mapOf("lookback" to 6.0)),
    FilterMeta("sr", "S/R Filter", "Blokir entry yang terlalu dekat ke level lawan.", mapOf("bufferPct" to 0.3, "lookback" to 20.0)),
    FilterMeta("liq", "Liquidity Sweep Filter", "Harus ada sweep likuiditas searah sebelum entry.", mapOf("lookback" to 20.0)),
    FilterMeta("session", "Trading Session", "Hanya entry di jam sesi aktif (UTC).", emptyMap()),
    FilterMeta("min_vol", "Min Volatility", "Minimal volatilitas ATR% agar pasar tidak terlalu sepi.", mapOf("minPct" to 0.3)),
    FilterMeta("max_vol", "Max Volatility", "Blokir pasar terlalu liar (ATR% di atas ambang).", mapOf("maxPct" to 5.0)),
    FilterMeta("cooldown", "Cooldown", "Jeda minimal antar trade (candle).", mapOf("bars" to 3.0)),
    FilterMeta("dup", "Duplicate Protection", "Blokir sinyal duplikat searah yang berdekatan.", mapOf("bars" to 5.0))
)

fun filterList(): List<FilterMeta> = FILTER_DEFS

private fun swing(c: List<Candle>, idx: Int, lookback: Int): Pair<Double, Double> {
    var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
    for (j in maxOf(0, idx - lookback) until idx) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
    return hh to ll
}

private fun atrPct(x: Cache, idx: Int, c: List<Candle>): Double? {
    val a = x.atr14[idx]
    if (!(a > 0) || !(c[idx].c > 0)) return null
    return (a / c[idx].c) * 100
}

// return Pair(ok, why?) — why null bila ok.
fun evalFilter(name: String, c: List<Candle>, i: Int, x: Cache, dir: String, p: Map<String, Double>, sess: List<Pair<Int, Int>>?, ctx: FilterCtx): Pair<Boolean, String?> {
    return when (name) {
        "trend" -> {
            if (x.e50[i].isNaN()) false to "EMA50 belum siap"
            else if (dir == "LONG") (if (c[i].c > x.e50[i]) true to null else false to "close di bawah EMA50")
            else (if (c[i].c < x.e50[i]) true to null else false to "close di atas EMA50")
        }
        "ema" -> {
            if (x.e55[i].isNaN()) false to "EMA55 belum siap"
            else if (dir == "LONG") (if (c[i].c > x.e55[i]) true to null else false to "tidak selaras EMA55")
            else (if (c[i].c < x.e55[i]) true to null else false to "tidak selaras EMA55")
        }
        "htf" -> {
            if (x.e200[i].isNaN()) false to "EMA200 belum siap"
            else if (dir == "LONG") (if (c[i].c > x.e200[i]) true to null else false to "di bawah EMA200 (HTF bear)")
            else (if (c[i].c < x.e200[i]) true to null else false to "di atas EMA200 (HTF bull)")
        }
        "volume" -> {
            val avg = x.sVol20[i]
            if (!(avg > 0)) false to "data volume belum siap"
            else if (c[i].v >= avg * (p["mult"] ?: 1.0)) true to null else false to "volume lemah"
        }
        "atr_vol" -> {
            val v = atrPct(x, i, c) ?: return false to "ATR belum siap"
            if (v >= (p["minPct"] ?: 0.2)) true to null else false to "ATR terlalu rendah: ${fmt2(v)}%"
        }
        "adx" -> {
            val a = x.adx14[i]
            if (a.isNaN()) false to "ADX belum siap"
            else if (a >= (p["min"] ?: 20.0)) true to null else false to "ADX lemah (${fmt1(a)})"
        }
        "rsi" -> {
            val r = x.rsi14[i]
            if (r.isNaN()) false to "RSI belum siap"
            else if (dir == "LONG") (if (r <= (p["longMax"] ?: 72.0)) true to null else false to "RSI jenuh ${fmt0(r)}")
            else (if (r >= (p["shortMin"] ?: 28.0)) true to null else false to "RSI jenuh ${fmt0(r)}")
        }
        "ms" -> {
            if (i < 6) false to "data struktur kurang"
            else {
                val bull = c[i - 2].h < c[i].h || c[i - 2].l < c[i].l
                val bear = c[i - 2].h > c[i].h || c[i - 2].l > c[i].l
                if (dir == "LONG") (if (bull) true to null else false to "struktur tak bullish")
                else (if (bear) true to null else false to "struktur tak bearish")
            }
        }
        "sr" -> {
            val lb = (p["lookback"] ?: 20.0).toInt()
            val (hh, ll) = swing(c, i, lb)
            if (!hh.isFinite() || !ll.isFinite()) false to "S/R belum siap"
            else {
                val buf = (p["bufferPct"] ?: 0.3) / 100
                if (dir == "LONG") (if ((hh - c[i].c) / c[i].c >= buf) true to null else false to "terlalu dekat resistance")
                else (if ((c[i].c - ll) / c[i].c >= buf) true to null else false to "terlalu dekat support")
            }
        }
        "liq" -> {
            val lb = (p["lookback"] ?: 20.0).toInt()
            val (hh, ll) = swing(c, i, lb)
            var swept = false
            for (j in maxOf(1, i - lb) until i) {
                if (dir == "LONG" && c[j].l < ll && c[j].c > ll) { swept = true; break }
                if (dir == "SHORT" && c[j].h > hh && c[j].c < hh) { swept = true; break }
            }
            if (swept) true to null else false to "tanpa sweep searah"
        }
        "session" -> {
            val h = utcHour(c[i].t)
            val ss = sess ?: listOf(0 to 24)
            if (ss.any { (a, b) -> h >= a && h < b }) true to null else false to "di luar sesi"
        }
        "min_vol" -> {
            val v = atrPct(x, i, c) ?: return false to "ATR belum siap"
            if (v >= (p["minPct"] ?: 0.3)) true to null else false to "pasar terlalu sepi"
        }
        "max_vol" -> {
            val v = atrPct(x, i, c) ?: return false to "ATR belum siap"
            if (v <= (p["maxPct"] ?: 5.0)) true to null else false to "pasar terlalu liar"
        }
        "cooldown" -> {
            val b = (p["bars"] ?: 3.0).toInt()
            // Cermin JS: indeks dulu bila finite (backtest), lalu waktu (live), else ok.
            if (ctx.lastExit != Int.MIN_VALUE) {
                if (i - ctx.lastExit >= b) true to null else false to "cooldown"
            } else if (ctx.lastExitT != null && ctx.tfMs != null) {
                if (c[i].t - ctx.lastExitT!! >= b * ctx.tfMs!!) true to null else false to "cooldown"
            } else true to null
        }
        "dup" -> {
            val bars = (p["bars"] ?: 5.0).toInt()
            if (ctx.lastSigDir != null) {
                if (ctx.lastSigDir != dir) true to null
                else if (i - ctx.lastSigIdx >= bars) true to null else false to "duplikat diblok"
            } else if (ctx.lastSigT != null && ctx.tfMs != null && ctx.lastSigDirT == dir) {
                if (c[i].t - ctx.lastSigT!! >= bars * ctx.tfMs!!) true to null else false to "duplikat diblok"
            } else true to null
        }
        else -> true to null
    }
}

fun filterDisplayName(id: String): String = FILTER_DEFS.find { it.id == id }?.name ?: id

fun applyFilters(candles: List<Candle>, idx: Int, cache: Cache, direction: String, active: List<FilterCfg>, ctx: FilterCtx): FilterVerdict {
    val failed = ArrayList<String>()
    for (f in active) {
        if (!f.enabled) continue
        val def = FILTER_DEFS.find { it.id == f.name } ?: continue
        val merged = HashMap(def.defaults)
        merged.putAll(f.params)
        val (ok, why) = try {
            evalFilter(f.name, candles, idx, cache, direction, merged, f.sessions ?: defSessions(f.name), ctx)
        } catch (e: Exception) { false to "error" }
        if (!ok) failed.add(filterDisplayName(f.name) + (if (why != null) " ($why)" else ""))
    }
    return FilterVerdict(failed.isEmpty(), failed)
}

private fun defSessions(name: String): List<Pair<Int, Int>>? =
    if (name == "session") listOf(0 to 24) else null

// ---------- decision (single + combo) ----------
fun decideAt(candles: List<Candle>, idx: Int, cache: Cache, params: BacktestParams): SignalDecision {
    val combo = params.combo
    if (combo == null || combo.strategies.isEmpty()) {
        val (d, cf, rs) = strategyFn(params.strategy, candles, idx, cache, params.strategyParams)
        return SignalDecision(d != "NONE", d, cf, rs, params.strategy)
    }
    val mode = combo.mode.ifEmpty { "OR" }
    data class R(val name: String, val dir: String, val conf: Double, val reasons: List<String>)
    val results = combo.strategies.map { name ->
        if (!STRATEGIES.containsKey(name)) R(name, "NONE", 0.0, emptyList())
        else {
            val (d, cf, rs) = strategyFn(name, candles, idx, cache, params.strategyParams)
            R(name, d, cf, rs)
        }
    }
    val longs = results.filter { it.dir == "LONG" }
    val shorts = results.filter { it.dir == "SHORT" }
    if (mode == "AND") {
        if (longs.size == results.size) return SignalDecision(true, "LONG", longs.map { it.conf }.average(), longs.flatMap { it.reasons }, combo.strategies.joinToString("+"))
        if (shorts.size == results.size) return SignalDecision(true, "SHORT", shorts.map { it.conf }.average(), shorts.flatMap { it.reasons }, combo.strategies.joinToString("+"))
        return SignalDecision(false, "NONE", 0.0, emptyList(), combo.strategies.joinToString("+"))
    }
    val need = if (mode == "MAJORITY") results.size / 2 + 1 else 1
    if (longs.size >= need && longs.size >= shorts.size)
        return SignalDecision(true, "LONG", longs.map { it.conf }.average(), longs.flatMap { it.reasons }, longs.map { it.name }.joinToString("+"))
    if (shorts.size >= need && shorts.size > longs.size)
        return SignalDecision(true, "SHORT", shorts.map { it.conf }.average(), shorts.flatMap { it.reasons }, shorts.map { it.name }.joinToString("+"))
    return SignalDecision(false, "NONE", 0.0, emptyList(), combo.strategies.joinToString("+"))
}

fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
fun fmt0(v: Double): String = String.format(java.util.Locale.US, "%.0f", v)
