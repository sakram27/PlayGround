package com.freqtrade.mobile.data

import kotlin.math.abs

// Mesin strategi sederhana ala Freqtrade yang jalan 100% di HP (offline/paper).
// Mendukung: SMA crossover, RSI, EMA. + backtest + simulator paper-trading.

enum class StrategyId(val title: String, val desc: String) {
    SMA_CROSS("SMA Cross", "Beli saat SMA-9 cross-up SMA-21, jual saat cross-down"),
    RSI_MEAN("RSI Mean-Reversion", "Beli RSI<30, jual RSI>70"),
    EMA_TREND("EMA Trend", "Beli saat harga > EMA-20 dan RSI>50"),
}

fun sma(values: List<Double>, period: Int): List<Double?> {
    return values.indices.map { i ->
        if (i + 1 < period) null
        else values.subList(i + 1 - period, i + 1).average()
    }
}

fun ema(values: List<Double>, period: Int): List<Double?> {
    if (values.isEmpty()) return emptyList()
    val k = 2.0 / (period + 1)
    val out = MutableList<Double?>(values.size) { null }
    var prev = values.take(period).average()
    for (i in values.indices) {
        if (i < period - 1) continue
        prev = if (i == period - 1) prev else values[i] * k + prev * (1 - k)
        out[i] = prev
    }
    return out
}

fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
    val out = MutableList<Double?>(closes.size) { null }
    if (closes.size <= period) return out
    var gain = 0.0; var loss = 0.0
    for (i in 1..period) {
        val d = closes[i] - closes[i - 1]
        if (d >= 0) gain += d else loss -= d
    }
    gain /= period; loss /= period
    out[period] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    for (i in period + 1 until closes.size) {
        val d = closes[i] - closes[i - 1]
        gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
        loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
        out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    }
    return out
}

enum class Signal { BUY, SELL, HOLD }

fun signalFor(strategy: StrategyId, candles: List<Candle>, idx: Int): Signal {
    val closes = candles.map { it.close }
    return when (strategy) {
        StrategyId.SMA_CROSS -> {
            val s9 = sma(closes, 9); val s21 = sma(closes, 21)
            if (idx < 22) return Signal.HOLD
            val a9 = s9[idx] ?: return Signal.HOLD
            val a21 = s21[idx] ?: return Signal.HOLD
            val p9 = s9[idx - 1] ?: return Signal.HOLD
            val p21 = s21[idx - 1] ?: return Signal.HOLD
            when {
                p9 <= p21 && a9 > a21 -> Signal.BUY
                p9 >= p21 && a9 < a21 -> Signal.SELL
                else -> Signal.HOLD
            }
        }
        StrategyId.RSI_MEAN -> {
            val r = rsi(closes)[idx] ?: return Signal.HOLD
            when {
                r < 30 -> Signal.BUY
                r > 70 -> Signal.SELL
                else -> Signal.HOLD
            }
        }
        StrategyId.EMA_TREND -> {
            val e = ema(closes, 20)[idx] ?: return Signal.HOLD
            val r = rsi(closes)[idx] ?: return Signal.HOLD
            when {
                closes[idx] > e && r > 50 -> Signal.BUY
                closes[idx] < e -> Signal.SELL
                else -> Signal.HOLD
            }
        }
    }
}

data class BacktestResult(
    val totalTrades: Int,
    val winRate: Double,
    val totalProfitPct: Double,
    val maxDrawdownPct: Double,
    val profitPerTrade: List<Double>,
)

fun backtest(strategy: StrategyId, candles: List<Candle>, feePct: Double = 0.1): BacktestResult {
    if (candles.size < 30) return BacktestResult(0, 0.0, 0.0, 0.0, emptyList())
    var inPos = false; var entry = 0.0
    val profits = mutableListOf<Double>()
    var equity = 100.0; var peak = 100.0; var maxDd = 0.0
    for (i in candles.indices) {
        val sig = signalFor(strategy, candles, i)
        val price = candles[i].close
        if (!inPos && sig == Signal.BUY) { inPos = true; entry = price }
        else if (inPos && sig == Signal.SELL) {
            val pct = (price - entry) / entry * 100 - feePct * 2
            profits.add(pct)
            equity *= (1 + pct / 100)
            peak = maxOf(peak, equity)
            maxDd = minOf(maxDd, (equity - peak) / peak * 100)
            inPos = false
        }
    }
    val wins = profits.count { it > 0 }
    return BacktestResult(
        totalTrades = profits.size,
        winRate = if (profits.isEmpty()) 0.0 else wins * 100.0 / profits.size,
        totalProfitPct = profits.sum(),
        maxDrawdownPct = maxDd,
        profitPerTrade = profits,
    )
}

// Simulator paper-trading live (pakai harga terakhir Binance, polling tiap refresh).
data class PaperTrade(
    val id: Int,
    val symbol: String,
    val side: String = "long",
    val entryPrice: Double,
    var currentPrice: Double,
    val amount: Double,
    val openTime: Long = System.currentTimeMillis(),
    val strategy: String = "",
) {
    val profitPct: Double get() = (currentPrice - entryPrice) / entryPrice * 100
    val profitAbs: Double get() = (currentPrice - entryPrice) * amount
}

class PaperBroker {
    private var nextId = 1
    val open = mutableListOf<PaperTrade>()
    val closed = mutableListOf<Pair<PaperTrade, Double>>()
    var balanceUsdt: Double = 1000.0

    fun buy(symbol: String, price: Double, stakeUsdt: Double, strategy: String): PaperTrade? {
        if (stakeUsdt > balanceUsdt || stakeUsdt <= 0) return null
        val amount = stakeUsdt / price
        balanceUsdt -= stakeUsdt
        val t = PaperTrade(nextId++, symbol, "long", price, price, amount, strategy = strategy)
        open.add(t)
        return t
    }

    fun sell(tradeId: Int, price: Double): Double? {
        val t = open.firstOrNull { it.id == tradeId } ?: return null
        t.currentPrice = price
        val proceeds = t.amount * price * 0.999 // fee 0.1%
        balanceUsdt += proceeds
        open.remove(t)
        closed.add(t to t.profitPct)
        return t.profitPct
    }

    fun updatePrices(prices: Map<String, Double>) {
        open.forEach { t ->
            val key = t.symbol.replace("/", "")
            prices[key]?.let { t.currentPrice = it }
        }
    }

    fun totalProfit(): Double = closed.sumOf { it.second }
}
