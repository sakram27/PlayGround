package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.Candle
import com.aether.signal.premium.engine.EquityPoint
import com.aether.signal.premium.engine.Trade

// V17 (forensik backtest): audit jendela data & kualitas — MURNI & teruji JVM.
// Bekerja pada candle hasil normalisasi (terurut, dedup). Tidak mengubah data.

// ---------- fingerprint dataset ----------
fun datasetFingerprint(
    provider: String, symbol: String, tf: String, limit: Int,
    firstT: Long, lastT: Long, count: Int
): String {
    val raw = "$provider|$symbol|$tf|n=$count|$firstT|$lastT"
    var h = -3750763034362895579L // FNV-1a 64
    for (ch in raw) {
        h = h xor ch.code.toLong()
        h *= 1099511628211L
    }
    val hex = java.lang.Long.toUnsignedString(h, 16).padStart(16, '0').takeLast(8)
    return "$raw|h=$hex"
}

// ---------- celah & kualitas jendela ----------
data class GapInfo(val afterT: Long, val missing: Int)

data class WindowQuality(
    val count: Int,
    val firstT: Long,
    val lastT: Long,
    val unordered: Int,
    val gaps: List<GapInfo>,
    val outOfRange: Int,
    val unclosedLast: Boolean,
    val medianGapMs: Long,
    val expectedGapMs: Long
)

/** Median jarak timestamp (0 bila <2 candle). */
fun medianGap(candles: List<Candle>): Long {
    if (candles.size < 2) return 0L
    val ds = ArrayList<Long>(candles.size - 1)
    for (i in 1 until candles.size) ds.add(candles[i].t - candles[i - 1].t)
    ds.sort()
    return ds[ds.size / 2]
}

fun auditWindow(
    candles: List<Candle>, tfMinutes: Int,
    startDate: Long, endDate: Long, nowMs: Long
): WindowQuality {
    val tf = if (tfMinutes > 0) tfMinutes else 15
    val expected = tf.toLong() * 60000L
    if (candles.isEmpty()) {
        return WindowQuality(0, 0, 0, 0, emptyList(), 0, false, 0, expected)
    }
    var unordered = 0
    val gaps = ArrayList<GapInfo>()
    var out = 0
    for (i in candles.indices) {
        val t = candles[i].t
        if ((startDate > 0 && t < startDate) || (endDate > 0 && t > endDate)) out++
        if (i == 0) continue
        val dt = t - candles[i - 1].t
        if (dt <= 0) {
            unordered++
            continue
        }
        if (dt > (expected * 3 / 2)) {
            val missing = (dt.toDouble() / expected).toLong().toInt() - 1
            if (missing > 0) gaps.add(GapInfo(candles[i - 1].t, missing))
        }
    }
    val med = medianGap(candles)
    val unclosed = candles.last().t > nowMs - expected
    return WindowQuality(candles.size, candles.first().t, candles.last().t,
        unordered, gaps, out, unclosed, med, expected)
}

/** Cakupan tanggal diminta vs data tersedia. */
fun coverageStatus(requestedFrom: Long, oldestT: Long, count: Int): String = when {
    count == 0 -> "kosong"
    requestedFrom <= 0 -> "penuh (tanpa batas awal)"
    oldestT <= 0 -> "kosong"
    oldestT <= requestedFrom -> "penuh"
    else -> "parsial"
}

fun fmtUtc(ts: Long): String {
    if (ts <= 0) return "—"
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.format(java.util.Date(ts)) + " UTC"
    } catch (e: Exception) { "—" }
}

// ---------- V18: pelaporan jujur (mata uang, PF, drawdown) ----------

/**
 * Kode mata uang kuotasi dari simbol (mata uang pelaporan PnL mesin).
 * "BTCUSDT"→USDT, "EUR/USD"→USD, "USD/JPY"→JPY. Tak dikenal → 3 huruf akhir.
 */
fun quoteCurrency(asset: String): String {
    val f = asset.uppercase().replace(Regex("[^A-Z]"), "")
    if (f.isEmpty()) return "—"
    if (f.endsWith("USDT")) return "USDT"
    return f.takeLast(3)
}

/** True bila daftar trade mencampur kuotasi berbeda (agregat lintas mata uang). */
fun mixedQuotes(trades: List<Trade>): Boolean =
    trades.map { quoteCurrency(it.asset) }.distinct().size > 1

/** Kuotasi tunggal daftar aset; "XXX" bila campur; "—" bila kosong. */
fun mixedQuoteOf(assets: List<String>): String {
    val qs = assets.map { quoteCurrency(it) }.distinct()
    return when {
        qs.isEmpty() -> "—"
        qs.size > 1 -> "XXX"
        else -> qs.first()
    }
}

private fun grpId(v: Double, d: Int = 2): String {
    if (!v.isFinite()) return "—"
    return String.format(java.util.Locale("id", "ID"), "%,.${d}f", v)
}

/**
 * Format uang sadar-kuotasi. USD/USDT → identik App.fmtMoney ("$1.234,50");
 * lain → "1.234,50 JPY". Kode "XXX"/tak dikenal → angka + "(campuran)".
 */
fun fmtMoneyQ(v: Double, quote: String): String {
    if (!v.isFinite()) return "—"
    val q = quote.uppercase()
    return if (q == "USD" || q == "USDT") {
        (if (v < 0) "-" else "") + "$" + grpId(kotlin.math.abs(v))
    } else if (q == "—" || q == "XXX" || q.isEmpty()) {
        grpId(v) + " (campuran)"
    } else {
        (if (v < 0) "-" else "") + grpId(kotlin.math.abs(v)) + " " + q
    }
}

/**
 * Label jujur Profit Factor: tanpa kerugian → tak terdefinisi (bukan bukti
 * strategi hebat); tanpa kemenangan → 0; NaN/tak hingga → "—".
 */
fun describePF(pf: Double, wins: Int, losses: Int): String = when {
    losses == 0 && wins > 0 -> "tak terdefinisi (belum ada kerugian)"
    losses == 0 -> "tak terdefinisi (belum ada trade kalah)"
    !pf.isFinite() -> "—"
    else -> grpId(pf)
}

/** Jendela drawdown maksimum dari kurva ekuitas (titik-tutup). Null bila <2 titik. */
data class DDWindow(val ddPct: Double, val peakT: Long, val troughT: Long)

fun ddWindows(curve: List<EquityPoint>): DDWindow? {
    if (curve.size < 2) return null
    var peak = curve[0].equity
    var peakT = curve[0].t
    var best = 0.0
    var bestPeakT = peakT
    var bestTroughT = peakT
    for (p in curve) {
        if (!p.equity.isFinite()) continue
        if (p.equity > peak) {
            peak = p.equity
            peakT = p.t
        }
        val dd = if (peak > 0) (peak - p.equity) / peak else 0.0
        if (dd > best) {
            best = dd
            bestPeakT = peakT
            bestTroughT = p.t
        }
    }
    return DDWindow(best * 100, bestPeakT, bestTroughT)
}

// ---------- V21: indikator kestabilan walk-forward (BUKAN uji ADF) ----------
// Aturan eksplisit: stabil bila ≥60% lipatan profit DAN simpangan baku kecil.
// Ini BUKAN uji stasioneritas formal (ADF/KPSS butuh pustaka statistik) —
// dinyatakan tegas di UI. Hanya deskripsi konsistensi antar lipatan.

data class StabilityReport(val profitablePct: Double, val stdNet: Double, val verdict: String)

fun wfStability(foldNets: List<Double>): StabilityReport? {
    if (foldNets.size < 2) return null
    val clean = foldNets.filter { it.isFinite() }
    if (clean.size != foldNets.size || clean.isEmpty()) return null
    val m = clean.sum() / clean.size
    val variance = clean.sumOf { (it - m) * (it - m) } / clean.size
    val std = kotlin.math.sqrt(variance)
    val prof = clean.count { it > 0 }.toDouble() / clean.size * 100
    val verdict = when {
        prof >= 60.0 && (m == 0.0 || std / kotlin.math.abs(m) < 1.0) -> "stabil"
        prof >= 60.0 -> "cukup stabil (variansi tinggi)"
        else -> "tidak stabil"
    }
    return StabilityReport(prof, std, verdict)
}
