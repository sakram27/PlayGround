package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.Candle
import kotlin.math.abs

// FITUR 5 (V20): validasi silang dua sumber gratis — MURNI & teruji JVM.
// Hanya pasangan SEBANDING (simbol & kuotasi & TF sama, mis. BTCUSDT spot
// Binance vs Bybit). Beda kuotasi (BTC/USD vs BTCUSDT) = tak sebanding.
// Kecocokan angka identik BUKAN bukti benar — dinyatakan di UI.

const val XVAL_PRICE_TOL_PCT = 0.5

data class XValRow(val t: Long, val aClose: Double, val bClose: Double, val divPct: Double)

data class XValReport(
    val compared: Int,
    val matched: Int,
    val maxDivPct: Double,
    val worstT: Long,
    val rows: List<XValRow>,
    // V21: divergensi maksimum tiap komponen OHLC (close tetap primer).
    val maxDivO: Double,
    val maxDivH: Double,
    val maxDivL: Double
)

/** Divergensi % |a-b|/|a|; null bila tak terdefinisi. */
private fun divPQ(a: Double, b: Double): Double? {
    if (!a.isFinite() || !b.isFinite() || a == 0.0) return null
    val d = kotlin.math.abs(a - b) / kotlin.math.abs(a) * 100
    return if (d.isFinite()) d else null
}

/**
 * Bandingkan candle A vs B per timestamp (inner join). "Cocok" = |div close| ≤ tol.
 * Volume dilaporkan apa adanya per sumber (wajar beda antar exchange).
 * Definisi candle diasumsikan sama (spot vs spot, batas hari UTC) — bila ragu,
 * laporkan sebagai keterbatasan, bukan dicampur diam-diam (lihat UI).
 */
fun compareCandles(
    a: List<Candle>, b: List<Candle>, tolPct: Double = XVAL_PRICE_TOL_PCT
): XValReport {
    val bm = HashMap<Long, Candle>()
    for (c in b) bm.putIfAbsent(c.t, c)
    val rows = ArrayList<XValRow>()
    var matched = 0
    var maxDiv = 0.0
    var worstT = 0L
    var mO = 0.0
    var mH = 0.0
    var mL = 0.0
    for (c in a) {
        val o = bm[c.t] ?: continue
        if (!c.c.isFinite() || !o.c.isFinite() || c.c <= 0 || o.c <= 0) continue
        val div = abs(c.c - o.c) / c.c * 100
        if (div.isFinite()) {
            rows.add(XValRow(c.t, c.c, o.c, div))
            if (div <= tolPct) matched++
            if (div > maxDiv) {
                maxDiv = div
                worstT = c.t
            }
        }
        divPQ(c.o, o.o)?.let { if (it > mO) mO = it }
        divPQ(c.h, o.h)?.let { if (it > mH) mH = it }
        divPQ(c.l, o.l)?.let { if (it > mL) mL = it }
    }
    return XValReport(rows.size, matched, maxDiv, worstT, rows.takeLast(8), mO, mH, mL)
}

/** Simbol sebanding lintas provider: normalisasi sama persis (termasuk kuotasi). */
fun comparableSymbols(a: String, b: String): Boolean {
    fun norm(s: String) = s.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
    val na = norm(a)
    val nb = norm(b)
    if (na.isEmpty() || nb.isEmpty()) return false
    return na == nb
}
