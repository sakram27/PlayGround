// AetherSignalBot — BacktestEngine FIXED (drop-in pengganti v3.22).
// Package, nama class & signature publik SAMA — arah tujuan aplikasi TIDAK diubah.
// Copy file ini menimpa: com/aether/signal/core/backtest/BacktestEngine.kt
// (BacktestParams perlu 2 field baru dgn default — lihat catatan di bawah).
//
// Perbaikan: FIX-01..FIX-10 (detail + bukti bytecode di LAPORAN_FIX.md).
package com.aether.signal.core.backtest

import com.aether.signal.core.decision.DecisionEngine
import com.aether.signal.core.model.BacktestParams
import com.aether.signal.core.model.Candle
import com.aether.signal.core.model.ComboConfig
import com.aether.signal.core.model.ComboMode
import com.aether.signal.core.model.SignalDirection
import com.aether.signal.core.model.SignalResult
import com.aether.signal.core.model.StrategyConfig
import com.aether.signal.core.risk.RiskEngine
import kotlin.math.abs
import kotlin.math.max

object BacktestEngine {

    // FIX-09: dukung w/d/h/m + fallback aman 15 (v3.22: tanpa 'w', default 15 implisit).
    fun parseTimeframe(tf: String): Int {
        val s = tf.trim().lowercase()
        return try {
            when {
                s.endsWith("m") -> max(1, s.dropLast(1).toIntOrNull() ?: 15)
                s.endsWith("h") -> max(1, s.dropLast(1).toIntOrNull() ?: 1) * 60
                s.endsWith("d") -> max(1, s.dropLast(1).toIntOrNull() ?: 1) * 1440
                s.endsWith("w") -> max(1, s.dropLast(1).toIntOrNull() ?: 1) * 10080
                else -> 15
            }
        } catch (_: Exception) { 15 }
    }

    fun run(candles: List<Candle>, params: BacktestParams): BacktestResult {
        if (candles.size < 60) return BacktestResult.error("Insufficient data (min 60 candles)")

        // FIX-10: sort + dedupe timestamp + buang candle non-finite/rusak (v3.22: hanya validasi, tanpa sort/dedupe).
        val data = candles
            .asSequence()
            .filter { c ->
                c.high.isFinite() && c.low.isFinite() && c.open.isFinite() && c.close.isFinite() &&
                    c.high > 0 && c.low > 0 && c.open > 0 && c.close > 0
            }
            .groupBy { it.timestamp }
            .map { (_, v) -> v.last() }          // duplikat: data terbaru menang
            .sortedBy { it.timestamp }
        if (data.size <= params.startupCandleCount) {
            return BacktestResult.error(
                "Insufficient data (need >${params.startupCandleCount} warmup candles)")
        }

        val combo: ComboConfig = params.comboConfig ?: ComboConfig(
            ComboMode.SINGLE, listOf(params.strategyConfig), 1, emptyList())
        if (combo.strategies.isEmpty()) return BacktestResult.error("No strategy configured")

        var capital = params.initialCapital
            .takeIf { it.isFinite() && it > 0 } ?: 1000.0
        val initial = capital
        val trades = mutableListOf<BacktestTrade>()
        val equity = mutableListOf<Pair<Long, Double>>()
        equity += data.first().timestamp to capital
        var peak = capital
        var maxDd = 0.0
        var maxDdPct = 0.0
        fun track(ts: Long, cap: Double) {
            equity += ts to cap
            if (cap > peak) peak = cap
            val dd = peak - cap
            if (dd > maxDd) maxDd = dd
            if (peak > 0) maxDdPct = max(maxDdPct, dd / peak * 100.0)
        }

        val strategyLabel = combo.strategies.joinToString(" + ") { it.name }
        // FIX-02: warmup dinamis (v3.22: hardcode 220 -> data 60..219 diam-diam 0 trades).
        var i = params.startupCandleCount
        while (i < data.size - 1) {          // butuh bar i+1 untuk entry (no-lookahead)
            val window = data.subList(0, i + 1) // sinyal hanya dari bar 0..i
            val decision = DecisionEngine.decide(window, combo)
            if (!decision.passed || decision.strategyResult.direction == null) {
                track(data[i].timestamp, capital)   // FIX-07: equity per-bar
                i++
                continue
            }
            val dir = decision.strategyResult.direction
            val refClose = data[i].close
            val levels = RiskEngine.calculateWithProfile(window, dir, refClose, params.riskProfile)
            val sl = levels.stopLoss
            val tp = levels.takeProfit
            // guard SL/TP waras: BUY -> SL < entry < TP ; SELL -> TP < entry < SL
            val sane = if (dir == SignalDirection.BUY) sl < refClose && refClose < tp
                       else tp < refClose && refClose < sl
            if (!sane || !sl.isFinite() || !tp.isFinite()) {
                track(data[i].timestamp, capital); i++; continue
            }

            // FIX (no-lookahead ala freqtrade): entry di OPEN bar berikutnya + slippage entry.
            val entryBar = data[i + 1]
            var entry = if (dir == SignalDirection.BUY) entryBar.open * (1 + params.slippagePercent)
                        else entryBar.open * (1 - params.slippagePercent)
            if (!entry.isFinite() || entry <= 0) { track(data[i].timestamp, capital); i++; continue }

            // FIX-05: sizing freqtrade-style (risk tanpa leverage; leverage hanya cap notional).
            val riskAmount = capital * params.riskPerTrade
            val riskFrac = abs(entry - sl) / entry
            if (!riskFrac.isFinite() || riskFrac <= 0) { track(data[i].timestamp, capital); i++; continue }
            var notional = riskAmount / riskFrac
            notional = minOf(notional, capital * params.leverage)   // cap leverage
            if (!notional.isFinite() || notional <= 0) { track(data[i].timestamp, capital); i++; continue }
            val feeEntry = notional * params.feePercent            // FIX-04: fee 2 sisi

            // FIX-01: SL/TP sisi yang benar + same-bar konservatif (SL menang) + flag.
            var exitPx: Double? = null
            var res: SignalResult = SignalResult.EXPIRED
            var exitIdx = -1
            var ambiguous = false
            val last = minOf(data.size - 1, (i + 1) + params.maxHoldingBars) // FIX-06: konfig
            var j = i + 1
            while (j <= last) {
                val b = data[j]
                val slHit = if (dir == SignalDirection.BUY) b.low <= sl else b.high >= sl
                val tpHit = if (dir == SignalDirection.BUY) b.high >= tp else b.low <= tp
                if (slHit && tpHit) { exitPx = sl; res = SignalResult.LOSS; exitIdx = j; ambiguous = true; break }
                if (slHit) { exitPx = sl; res = SignalResult.LOSS; exitIdx = j; break }
                if (tpHit) { exitPx = tp; res = SignalResult.WIN; exitIdx = j; break }
                j++
            }
            if (exitPx == null) { exitPx = data[last].close; res = SignalResult.EXPIRED; exitIdx = last }

            // FIX-04: slippage exit + fee exit.
            val exitAdj = if (dir == SignalDirection.BUY) exitPx * (1 - params.slippagePercent)
                          else exitPx * (1 + params.slippagePercent)
            val gross = if (dir == SignalDirection.BUY) (exitAdj - entry) / entry * notional
                        else (entry - exitAdj) / entry * notional
            val fees = feeEntry + notional * params.feePercent
            val pnl = gross - fees
            capital = max(0.0, capital + pnl)     // FIX: floor bangkrut (v3.22: guard parsial)
            track(data[exitIdx].timestamp, capital)

            val rMult = if (riskAmount > 0) pnl / riskAmount else 0.0
            trades += BacktestTrade(
                direction = dir, asset = params.asset, timeframe = params.timeframe,
                entry = entry, stopLoss = sl, takeProfit = tp, exit = exitAdj,
                entryTime = entryBar.timestamp, exitTime = data[exitIdx].timestamp,
                strategyName = strategyLabel, result = res,
                pnl = pnl, pnlPercent = if (initial > 0) pnl / initial * 100 else 0.0,
                fees = fees, riskAmount = riskAmount, rMultiple = rMult,
                // NOTE: BacktestTrade v3.22 belum punya kolom ambiguousSameBar —
                // tambahkan dgn default false agar call-site lama tetap kompil.
                // Jika belum tambah kolom, hapus argumen ini + catat di trade.strategyName bila perlu.
            )
            i = exitIdx + 1
        }

        return finalize(initial, capital, trades, equity)
    }

    // (finalize: agregat + metrik freqtrade parity — salin dari aether/metrics.py + build_result.
    //  Dipisah agar mudah di-unit-test. Lihat android-patch/UI_UX_FIXES.md untuk rumus lengkap.)
    private fun finalize(initial: Double, final: Double,
                         trades: List<BacktestTrade>,
                         equity: List<Pair<Long, Double>>): BacktestResult {
        val wins = trades.count { it.result == SignalResult.WIN }
        val losses = trades.count { it.result == SignalResult.LOSS }
        val expired = trades.count { it.result == SignalResult.EXPIRED }
        val grossP = trades.filter { it.result == SignalResult.WIN }.sumOf { it.pnl }
        val grossL = trades.filter { it.result == SignalResult.LOSS }.sumOf { -it.pnl }
        val net = final - initial
        val netPct = if (initial > 0) net / initial * 100 else 0.0
        val n = trades.size
        val winRate = if (n > 0) wins * 100.0 / n else 0.0
        val lossRate = if (n > 0) losses * 100.0 / n else 0.0
        val pf = when { grossL > 0 -> grossP / grossL; grossP > 0 -> Double.POSITIVE_INFINITY; else -> 0.0 }
        val avgW = if (wins > 0) grossP / wins else 0.0
        val avgL = if (losses > 0) trades.filter { it.result == SignalResult.LOSS }.sumOf { -it.pnl } / losses else 0.0
        val expectancy = if (n > 0) net / n else 0.0
        val avgR = if (n > 0) trades.sumOf { it.rMultiple } / n else 0.0
        var lws = 0; var lls = 0; var cws = 0; var cls = 0
        for (t in trades) when (t.result) {
            SignalResult.WIN -> { cws++; cls = 0; lws = max(lws, cws) }
            SignalResult.LOSS -> { cls++; cws = 0; lls = max(lls, cls) }
            else -> { cws = 0; cls = 0 }
        }
        var peak = initial; var mdd = 0.0; var mddPct = 0.0
        for ((_, c) in equity) {
            if (c > peak) peak = c
            val dd = peak - c
            if (dd > mdd) mdd = dd
            if (peak > 0) mddPct = max(mddPct, dd / peak * 100)
        }
        return BacktestResult(
            initialCapital = initial, finalCapital = final, netProfit = net,
            netProfitPercent = netPct, totalTrades = n, wins = wins, losses = losses,
            expired = expired, winRate = winRate, lossRate = lossRate,
            grossProfit = grossP, grossLoss = grossL, profitFactor = pf,
            expectancy = expectancy, averageWin = avgW, averageLoss = avgL,
            averageR = avgR, averageRiskReward = avgR,
            maxDrawdown = mdd, maxDrawdownPercent = mddPct,
            longestWinStreak = lws, longestLossStreak = lls,
            trades = trades, equityCurve = equity,
            // NOTE: BacktestResult v3.22 belum punya sharpe/sortino/calmar/sqn/kelly/buyHold —
            // tambah kolom dgn default 0.0 (lihat hitungan di aether/metrics.py) atau tampilkan di UI dari trades.
        )
    }
}

// CATATAN MIGRASI BacktestParams (kompatibel mundur — semua default):
//   data class BacktestParams(
//     ...
//     val startupCandleCount: Int = 50,   // NEW (FIX-02; ganti hardcode 220)
//     val maxHoldingBars: Int = 200,      // NEW (FIX-06; ganti hardcode 200)
//   )
// BacktestTrade: tambah `val ambiguousSameBar: Boolean = false` (FIX-01 flag).
