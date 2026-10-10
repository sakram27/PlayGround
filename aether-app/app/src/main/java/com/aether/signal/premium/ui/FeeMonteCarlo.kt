package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.Trade
import java.util.Random

// FITUR 3 (V16): audit biaya transaksi backtest — MURNI & teruji JVM.
// Definisi dari mesin (Backtest.kt): fees per transaksi = (entry*qty + exit*qty)
// * feePercent → mencakup fee ENTRY dan EXIT. Laba bersih (pnl) SUDAH bersih fee.
// Maka: laba kotor (sebelum fee) = net + totalFees (rekonstruksi, bukan estimasi
// baru — memakai t.fees & t.pnl transaksi yang sama dengan laporan). Fee TIDAK
// dikurangkan dua kali: feeShare = totalFees / labaKotor.

data class FeeAudit(
    val trades: Int,
    val totalFees: Double,
    val netProfit: Double,
    val grossBeforeFees: Double,
    /** Persen laba kotor yang habis untuk fee; null bila tak terdefinisi. */
    val feeSharePct: Double?
)

fun feeAudit(trades: List<Trade>): FeeAudit {
    val fees = trades.sumOf { if (it.fees.isFinite()) it.fees else 0.0 }
    val net = trades.sumOf { if (it.pnl.isFinite()) it.pnl else 0.0 }
    val gross = net + fees
    val share = if (gross > 0 && gross.isFinite() && fees.isFinite()) fees / gross * 100 else null
    return FeeAudit(trades.size, fees, net, gross, share)
}

// ---------- FITUR 5: Monte Carlo atas urutan transaksi ----------
// Mengocok URUTAN pnl (Fisher-Yates, seed → reproduksibel), bukan memprediksi
// masa depan. Satu permutasi memakai multiset pnl yang sama → ekuitas akhir
// SELALU sama; yang diuji adalah ketidakpastian JALUR (maxDD) dan peluang
// menyentuh ambang ruin. Ini dinyatakan eksplisit pada hasil.
// Definisi ruin: ekuitas minimum sepanjang jalur < modal*(1-ruinDrawdownPct/100).

data class MCResult(
    val sims: Int,
    val n: Int,
    val seed: Long,
    val initialCapital: Double,
    val ruinDrawdownPct: Double,
    val finalEquity: Double,
    val medianDDPct: Double,
    val minDDPct: Double,
    val maxDDPct: Double,
    val ruinProb: Double,
    val ruinCount: Int
)

/** Null bila tak dapat dihitung (transaksi kosong/tak valid, modal/sims tak valid). */
fun monteCarlo(
    pnls: List<Double>,
    initialCapital: Double,
    sims: Int,
    seed: Long,
    ruinDrawdownPct: Double = 50.0
): MCResult? {
    val clean = pnls.filter { it.isFinite() }
    if (clean.isEmpty() || !initialCapital.isFinite() || initialCapital <= 0) return null
    if (sims <= 0 || ruinDrawdownPct <= 0 || ruinDrawdownPct >= 100) return null
    val ruinLevel = initialCapital * (1 - ruinDrawdownPct / 100)
    val n = clean.size
    val finalEq = initialCapital + clean.sum()
    val dds = ArrayList<Double>(sims)
    var ruin = 0
    for (s in 0 until sims) {
        val order = clean.toMutableList()
        val rnd = Random(seed + s)
        for (i in n - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val tmp = order[i]; order[i] = order[j]; order[j] = tmp
        }
        var eq = initialCapital
        var peak = eq
        var minEq = eq
        var maxDd = 0.0
        for (p in order) {
            eq += p
            if (eq > peak) peak = eq
            if (eq < minEq) minEq = eq
            val dd = if (peak > 0) (peak - eq) / peak else 0.0
            if (dd > maxDd) maxDd = dd
        }
        dds.add(maxDd * 100)
        if (minEq < ruinLevel) ruin++
    }
    dds.sort()
    fun q(f: Double): Double = dds[(f * (dds.size - 1)).toInt().coerceIn(dds.indices)]
    return MCResult(sims, n, seed, initialCapital, ruinDrawdownPct, finalEq,
        q(0.5), dds.first(), dds.last(), ruin.toDouble() / sims, ruin)
}
