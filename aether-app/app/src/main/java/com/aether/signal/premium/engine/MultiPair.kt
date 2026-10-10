package com.aether.signal.premium.engine

import kotlin.math.*

// Agregasi multi-pair + hyperopt. Definisi metrik = gabungan trade (bukan rata-rata %).

data class PairRow(val sym: String, val res: BacktestResult?, val err: String? = null)

fun pairStatusOf(row: PairRow): String = when {
    row.err != null -> "NO DATA"
    row.res == null -> "FAILED"
    row.res.error != null -> "NO DATA"
    row.res.totalTrades <= 0 -> "NO TRADES"
    else -> "SUCCESS"
}

data class OverallAgg(
    val totalTrades: Int, val wins: Int, val losses: Int,
    val winRate: Double, val profitFactor: Double, val netProfit: Double,
    val averageWin: Double, val averageLoss: Double, val maxDD: Double, val pairsOk: Int
)

fun aggregateOverall(rows: List<PairRow>, combinedDD: Double? = null): OverallAgg {
    val ok = rows.filter { it.res != null && it.res.error == null }
    val trades = ok.flatMap { it.res!!.trades }
    val wins = trades.filter { it.result == "WIN" }
    val losses = trades.filter { it.result == "LOSS" }
    val gp = wins.sumOf { it.pnl }
    val gl = abs(losses.sumOf { it.pnl })
    val net = trades.sumOf { it.pnl }
    return OverallAgg(
        totalTrades = trades.size, wins = wins.size, losses = losses.size,
        winRate = if (trades.isNotEmpty()) wins.size.toDouble() / trades.size * 100 else Double.NaN,
        // V18: samakan cakupan buildResult (tak hingga → 999) agar label "∞" konsisten.
        profitFactor = if (gl > 0) gp / gl else if (gp > 0) 999.0 else 0.0,
        netProfit = net,
        averageWin = if (wins.isNotEmpty()) gp / wins.size else 0.0,
        averageLoss = if (losses.isNotEmpty()) -gl / losses.size else 0.0,
        maxDD = combinedDD ?: ok.map { it.res!!.maxDrawdownPercent }.maxOrNull() ?: 0.0,
        pairsOk = ok.size
    )
}

data class CombinedEquity(val curve: List<EquityPoint>, val maxDDPct: Double, val net: Double, val n: Int)

fun buildCombinedEquity(rows: List<PairRow>, initialCapital: Double): CombinedEquity {
    val all = rows.filter { it.res != null && it.res.error == null }
        .flatMap { it.res!!.trades }.sortedBy { it.exitTime }
    var eq = initialCapital; var peak = eq; var dd = 0.0
    val curve = ArrayList<EquityPoint>()
    for (t in all) {
        eq += t.pnl
        if (!eq.isFinite()) continue
        peak = maxOf(peak, eq)
        dd = maxOf(dd, if (peak > 0) (peak - eq) / peak else 0.0)
        curve.add(EquityPoint(t.exitTime, eq))
    }
    return CombinedEquity(curve, dd * 100, eq - initialCapital, all.size)
}

// Hyperopt grid SL x TP x risiko (skor freqtrade-style, sama dengan app.js).
data class OptRow(val sl: Double, val tp: Double, val risk: Double, val res: BacktestResult, val score: Double)

fun hyperoptSearch(candles: List<Any?>, base: BacktestParams): List<OptRow> {
    val out = ArrayList<OptRow>()
    for (sl in listOf(1.0, 1.5, 2.5)) for (tp in listOf(2.0, 3.0, 5.0)) for (r in listOf(0.5, 1.0, 2.0)) {
        val res = runBacktest(candles, base.copy(slPercent = sl / 100, tpPercent = tp / 100, riskPerTrade = r / 100))
        if (res.error == null) {
            val score = res.netProfit - res.maxDrawdownPercent * (base.initialCapital * 0.002) + minOf(res.profitFactor, 5.0) * 10
            out.add(OptRow(sl, tp, r, res, score))
        }
    }
    return out.sortedByDescending { it.score }
}

fun classifyResult(res: BacktestResult?): String {
    if (res == null) return "FAILED"
    if (res.error != null) return if (Regex("minimal|min|candle|data kosong|tidak tersedia|gagal", RegexOption.IGNORE_CASE).containsMatchIn(res.error)) "NO DATA" else "FAILED"
    if (res.totalTrades <= 0) return "NO TRADES"
    return "SUCCESS"
}
