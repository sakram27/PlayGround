package com.freqdroid.app.engine

import com.freqdroid.app.data.Candle
import kotlin.math.abs

object Indicators {
    fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val out = MutableList(values.size) { 0.0 }
        var prev = values[0]
        out[0] = prev
        for (i in 1 until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    fun sma(values: List<Double>, period: Int): List<Double?> {
        return values.indices.map { i ->
            if (i + 1 < period) null else values.subList(i + 1 - period, i + 1).average()
        }
    }

    fun rsi(closes: List<Double>, period: Int = 14): List<Double> {
        if (closes.size <= period) return List(closes.size) { 50.0 }
        val out = MutableList(closes.size) { 50.0 }
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        gain /= period; loss /= period
        out[period] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            gain = (gain * (period - 1) + g) / period
            loss = (loss * (period - 1) + l) / period
            out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }
}

data class BacktestTrade(
    val pair: String,
    val entryIndex: Int,
    val exitIndex: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val profitPct: Double
)

data class BacktestResult(
    val trades: List<BacktestTrade>,
    val winRate: Double,
    val totalProfitPct: Double,
    val maxDrawdownPct: Double,
    val totalTrades: Int
)

/**
 * Mini strategy ala-freqtrade: EMA-fast cross EMA-slow + RSI filter.
 * buy: emaFast > emaSlow && rsi < 70 ; sell: emaFast < emaSlow || rsi > 78
 * Mirip konsep "SampleStrategy" freqtrade tapi disederhanakan agar jalan di HP.
 */
object PaperEngine {
    fun backtest(
        pair: String,
        candles: List<Candle>,
        fast: Int = 9,
        slow: Int = 21,
        rsiPeriod: Int = 14,
        feePct: Double = 0.075
    ): BacktestResult {
        if (candles.size < slow + 5) return BacktestResult(emptyList(), 0.0, 0.0, 0.0, 0)
        val closes = candles.map { it.close }
        val emaF = Indicators.ema(closes, fast)
        val emaS = Indicators.ema(closes, slow)
        val rsi = Indicators.rsi(closes, rsiPeriod)
        val trades = mutableListOf<BacktestTrade>()
        var openIdx: Int? = null
        var openPrice = 0.0
        for (i in slow until closes.size) {
            val buy = emaF[i] > emaS[i] && emaF[i - 1] <= emaS[i - 1] && rsi[i] < 70
            val sell = openIdx != null && (emaF[i] < emaS[i] || rsi[i] > 78)
            if (buy && openIdx == null) { openIdx = i; openPrice = closes[i] }
            if (sell && openIdx != null) {
                val gross = (closes[i] - openPrice) / openPrice * 100
                val net = gross - feePct * 2
                trades.add(BacktestTrade(pair, openIdx, i, openPrice, closes[i], net))
                openIdx = null
            }
        }
        openIdx?.let {
            val gross = (closes.last() - openPrice) / openPrice * 100
            trades.add(BacktestTrade(pair, it, closes.size - 1, openPrice, closes.last(), gross - feePct * 2))
        }
        val wins = trades.count { it.profitPct > 0 }
        val total = trades.sumOf { it.profitPct }
        // max drawdown dari equity curve
        var peak = 0.0; var equity = 0.0; var dd = 0.0
        for (t in trades) { equity += t.profitPct; peak = maxOf(peak, equity); dd = minOf(dd, equity - peak) }
        return BacktestResult(
            trades,
            if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size,
            total,
            dd,
            trades.size
        )
    }

    fun signal(candles: List<Candle>): String {
        if (candles.size < 30) return "WAIT"
        val closes = candles.map { it.close }
        val emaF = Indicators.ema(closes, 9)
        val emaS = Indicators.ema(closes, 21)
        val rsi = Indicators.rsi(closes)
        val i = closes.size - 1
        return when {
            emaF[i] > emaS[i] && rsi[i] < 70 && emaF[i-1] <= emaS[i-1] -> "BUY"
            emaF[i] < emaS[i] || rsi[i] > 78 -> "SELL"
            else -> "HOLD"
        }
    }
}
