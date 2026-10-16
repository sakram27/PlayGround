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
            if (lb < 2) return false to "lookback terlalu kecil"
            // V22 FIX: tiap bar j dibandingkan terhadap ayunan SEBELUM j (eksklusi),
            // bukan terhadap min/maks yang mencakup j itu sendiri (mustahil terlampaui).
            var swept = false
            for (j in maxOf(1, i - lb) until i) {
                var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                for (k in maxOf(0, j - lb) until j) { hh = maxOf(hh, c[k].h); ll = minOf(ll, c[k].l) }
                if (!hh.isFinite() || !ll.isFinite()) continue
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

// ---------- Diagnostik NO_CANDIDATE (murni, tanpa ubah keputusan) ----------
// ATURAN DIJAGA (audit nol-kandidat): ambang entry, EMA/RSI, mode OR/AND/
// MAJORITY, dan penggabungan hasil TIDAK disentuh — decideAt/strategyFn di
// atas tidak berubah. Helper di bawah hanya MEMBACA ulang strategyFn yang
// murni (tanpa fetch jaringan, tanpa state) pada input yang SAMA dengan yang
// dipakai decideAt, lalu merangkumnya agar vonis dapat dibedakan:
// mode aktual + arah tiap kaki + waktu bar yang dievaluasi.

/** Label ringkas strategi untuk detail vonis (deterministik, tanpa rahasia). */
fun shortStrategyLabel(id: String): String = when (id) {
    "support_resistance" -> "SR"
    "mtf_confirm" -> "MTF"
    else -> id.uppercase().replace("_", "").take(8).ifEmpty { "?" }
}

/** Arah tiap kaki (tunggal/kombo) pada bar ini — input sama dengan decideAt. */
fun comboLegDirs(params: BacktestParams, candles: List<Candle>, idx: Int, cache: Cache): List<Pair<String, String>> {
    val combo = params.combo
    if (combo == null || combo.strategies.isEmpty()) {
        return listOf(params.strategy to strategyFn(params.strategy, candles, idx, cache, params.strategyParams).first)
    }
    return combo.strategies.map { name ->
        name to if (!STRATEGIES.containsKey(name)) "NONE"
        else strategyFn(name, candles, idx, cache, params.strategyParams).first
    }
}

/** Detail vonis NO_CANDIDATE: MODE + arah per kaki + waktu bar. Maks 120 char. */
fun noCandidateDetail(params: BacktestParams, candles: List<Candle>, idx: Int, cache: Cache, includeSrTelemetry: Boolean = false): String {
    val combo = params.combo
    val mode = if (combo == null || combo.strategies.isEmpty()) "SINGLE" else combo.mode.ifEmpty { "OR" }
    val barT = candles.getOrNull(idx)?.t ?: 0L
    val sb = StringBuilder("MODE=$mode")
    for ((name, dir) in comboLegDirs(params, candles, idx, cache)) {
        val seg = " | ${shortStrategyLabel(name)}=$dir"
        if (sb.length + seg.length + " | BAR=$barT".length > 120) break
        sb.append(seg)
    }
    sb.append(" | BAR=$barT")
    // Telemetri SR kompak (diagnostik saja): hanya bila SR dievaluasi, hanya
    // bila muat dalam 120 char agar BAR tak terpotong. Keputusan tak berubah.
    if (includeSrTelemetry &&
        (params.strategy == "support_resistance" ||
            params.combo?.strategies?.contains("support_resistance") == true)) {
        val seg = " | " + srTelemetryCompact(candles, idx)
        if (sb.length + seg.length <= 120) sb.append(seg)
    }
    return sb.toString().take(120)
}

// ---------- Telemetri SR (diagnostik murni, Pek. A) ----------
// MENCERMINKAN strategyFn "support_resistance" (Strategies.kt:135-147) secara
// ekspresi-per-ekspresi: jendela 20 bar (i-20..i-1), jarak |l-ll|/ll dan
// |h-hh|/hh lawan ambang tol, syarat arah candle, prioritas LONG dulu.
// result SELALU sama dengan strategyFn pada input identik (dibuktikan uji
// sweep); yang ditambah hanya jarak terukur + arah + alasan eksplisit.
// Tidak membaca candle masa depan (hanya indeks <= idx), tidak fetch data,
// tidak mengubah urutan evaluasi engine.

/** Ambang S/R produksi (0,1%) — satu-satunya sumber nilai untuk telemetri. */
const val SR_TOL = 0.001

/** Hasil telemetri satu bar: keputusan + bukti ukur + alasan. */
data class SrTelemetry(
    val result: String, // LONG | SHORT | NONE — identik strategyFn
    val distSupportPct: Double?, // |l-ll|/ll*100, null bila tak bermakna
    val distResistancePct: Double?, // |h-hh|/hh*100, null bila tak bermakna
    val tolPct: Double = SR_TOL * 100,
    val dir: String, // BULLISH | BEARISH | FLAT | INVALID
    val reason: String // DATA_KURANG | DATA_INVALID | JARAK_DI_LUAR_TOLERANSI |
    // ARAH_SUPPORT_GAGAL | ARAH_RESISTANCE_GAGAL | ARAH_GAGAL_KEDUANYA |
    // SUPPORT_BOUNCE | RESISTANCE_REJECT
)

fun srDirectionOf(c: Candle): String = when {
    !c.o.isFinite() || !c.c.isFinite() -> "INVALID"
    c.c > c.o -> "BULLISH"
    c.c < c.o -> "BEARISH"
    else -> "FLAT"
}

fun srTelemetry(candles: List<Candle>, idx: Int, tol: Double = SR_TOL): SrTelemetry {
    if (idx < 0 || idx >= candles.size) return SrTelemetry("NONE", null, null, tol * 100, "INVALID", "DATA_KURANG")
    val c = candles[idx]
    val dir = srDirectionOf(c)
    if (idx < 20) return SrTelemetry("NONE", null, null, tol * 100, dir, "DATA_KURANG")
    var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
    for (j in idx - 20 until idx) { hh = maxOf(hh, candles[j].h); ll = minOf(ll, candles[j].l) }
    // Ekspresi mentah persis produksi (Double: pembagian nol → Inf/NaN, tak throw).
    val nearS = Math.abs(c.l - ll) / ll < tol
    val nearR = Math.abs(c.h - hh) / hh < tol
    val bull = c.c > c.o
    val bear = c.c < c.o
    val result = when {
        nearS && bull -> "LONG"
        nearR && bear -> "SHORT"
        else -> "NONE"
    }
    // Jarak tampil: hanya bila bermakna (finite & tak-negatif); selain itu "-".
    fun pct(raw: Double): Double? = if (raw.isFinite() && raw >= 0) raw else null
    val dS = pct(Math.abs(c.l - ll) / ll * 100)
    val dR = pct(Math.abs(c.h - hh) / hh * 100)
    val ohlcOk = c.o.isFinite() && c.h.isFinite() && c.l.isFinite() && c.c.isFinite()
    val lvlOk = hh.isFinite() && ll.isFinite() && hh > 0 && ll > 0
    val reason = when {
        result == "LONG" -> "SUPPORT_BOUNCE"
        result == "SHORT" -> "RESISTANCE_REJECT"
        !ohlcOk || !lvlOk -> "DATA_INVALID"
        !nearS && !nearR -> "JARAK_DI_LUAR_TOLERANSI"
        nearS && nearR -> "ARAH_GAGAL_KEDUANYA"
        nearS -> "ARAH_SUPPORT_GAGAL"
        else -> "ARAH_RESISTANCE_GAGAL"
    }
    return SrTelemetry(result, dS, dR, tol * 100, dir, reason)
}

fun srReasonCompact(reason: String): String = when (reason) {
    "DATA_KURANG" -> "DATA-K"
    "DATA_INVALID" -> "DATA-X"
    "JARAK_DI_LUAR_TOLERANSI" -> "JARAK"
    "ARAH_SUPPORT_GAGAL" -> "ARAH-S"
    "ARAH_RESISTANCE_GAGAL" -> "ARAH-R"
    "ARAH_GAGAL_KEDUANYA" -> "ARAH-2"
    "SUPPORT_BOUNCE" -> "BOUNCE"
    "RESISTANCE_REJECT" -> "REJECT"
    else -> reason.take(8)
}

fun srDirCompact(dir: String): String = when (dir) {
    "BULLISH" -> "BULL"
    "BEARISH" -> "BEAR"
    "INVALID" -> "INV"
    else -> dir
}

/** Segmen kompak untuk detail vonis 120-char. */
fun srTelemetryCompact(candles: List<Candle>, idx: Int, tol: Double = SR_TOL): String {
    val t = srTelemetry(candles, idx, tol)
    fun p(v: Double?): String =
        if (v == null) "-" else String.format(java.util.Locale.US, "%.2f", v) + "%"
    return "SRt:dS=${p(t.distSupportPct)},dR=${p(t.distResistancePct)}," +
        "${srDirCompact(t.dir)},${srReasonCompact(t.reason)}"
}

/** Baris verbose per pair (laporan/harness, tanpa batas 120 char). */
fun srTelemetryLine(sym: String, candles: List<Candle>, idx: Int, tol: Double = SR_TOL): String {
    val t = srTelemetry(candles, idx, tol)
    val barT = candles.getOrNull(idx)?.t ?: 0L
    fun p(v: Double?): String =
        if (v == null) "-" else String.format(java.util.Locale.US, "%.2f", v) + "%"
    val tolS = String.format(java.util.Locale.US, "%.2f", t.tolPct) + "%"
    return "$sym | SR=${t.result} | distS=${p(t.distSupportPct)} | distR=${p(t.distResistancePct)} | " +
        "tol=$tolS | arah=${t.dir} | alasan=${t.reason} | BAR=$barT"
}

fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
fun fmt0(v: Double): String = String.format(java.util.Locale.US, "%.0f", v)
