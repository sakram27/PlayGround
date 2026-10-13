package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.BacktestResult
import com.aether.signal.premium.engine.EquityPoint
import com.aether.signal.premium.engine.Trade

// FITUR 1 (V20): Walk-Forward bergulir — MURNI & teruji JVM.
// Data in-range dibagi N lipatan BERURUTAN; lipatan k diuji dengan parameter
// yang SAMA (tanpa optimasi per lipatan — tidak ada kebocoran masa depan via
// parameter). Warmup tiap lipatan hanya dari data SEBELUM lipatan itu
// (disediakan runBacktest via splitWarmup — masa depan tak pernah dipakai).
// Agregat gabungan = pool trade (bukan rata-rata %), dengan kurva ekuitas
// gabungan yang dibangun ulang dari pnl (definisi terdokumentasi di poolEquity).

const val WF_MIN_FOLDS = 2
const val WF_MAX_FOLDS = 8

data class FoldRange(val index: Int, val from: Int, val to: Int) // [from, to) indeks in-range

/**
 * Bagi [total] candle menjadi [folds] lipatan berurutan (sisa ke lipatan akhir).
 * Null bila tak memungkinkan (folds di luar 2..8 atau tiap lipatan < minFold).
 */
fun wfSplits(total: Int, folds: Int, minFold: Int): List<FoldRange>? {
    if (folds < WF_MIN_FOLDS || folds > WF_MAX_FOLDS) return null
    if (minFold <= 0 || total < folds * minFold) return null
    val base = total / folds
    val out = ArrayList<FoldRange>()
    var from = 0
    for (k in 0 until folds) {
        val to = if (k == folds - 1) total else from + base
        if (to - from < minFold) return null
        out.add(FoldRange(k, from, to))
        from = to
    }
    return out
}

data class FoldReport(
    val index: Int,
    val fromT: Long,
    val toT: Long,
    val candles: Int,
    val res: BacktestResult
)

/**
 * Kurva ekuitas gabungan: modal awal + Σ net lipatan selesai + segmen lipatan
 * berjalan (kurva lipatan digeser agar kontinu). Definisi eksplisit, teruji.
 */
fun poolEquity(initialCapital: Double, folds: List<Pair<BacktestResult, List<EquityPoint>>>): List<EquityPoint> {
    val out = ArrayList<EquityPoint>()
    var base = initialCapital
    var first = true
    for ((res, curve) in folds) {
        for (p in curve) {
            val v = base + (p.equity - initialCapital)
            if (first) {
                out.add(EquityPoint(p.t, v))
                first = false
            } else {
                // Hindari duplikat waktu dengan titik akhir sebelumnya.
                if (p.t > out.last().t) out.add(EquityPoint(p.t, v))
            }
        }
        base += res.netProfit
    }
    return out
}

/** Pool trade semua lipatan, diurut waktu keluar (sumber agregat gabungan). */
fun poolTrades(folds: List<BacktestResult>): List<Trade> =
    folds.flatMap { it.trades }.sortedBy { it.exitTime }
