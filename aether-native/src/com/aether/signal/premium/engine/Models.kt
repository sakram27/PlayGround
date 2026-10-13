package com.aether.signal.premium.engine

import kotlin.math.*

// Port langsung dari core.js (sumber kebenaran). Nilai null indikator JS = Double.NaN.

const val MIN_CANDLES = 60
const val WARMUP = 60
const val DEFAULT_MAX_HOLDING = 100

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

data class Combo(val mode: String, val strategies: List<String>)

data class FilterCfg(
    val name: String,
    val enabled: Boolean = true,
    val params: Map<String, Double> = emptyMap(),
    val sessions: List<Pair<Int, Int>>? = null
)

data class BacktestParams(
    val asset: String = "BTCUSDT",
    val timeframe: String = "15m",
    val initialCapital: Double = 1000.0,
    val riskPerTrade: Double = 0.01,
    val leverage: Int = 1,
    val feePercent: Double = 0.0005,
    val slippagePercent: Double = 0.0002,
    val slPercent: Double = 0.015,
    val tpPercent: Double = 0.03,
    val maxHolding: Int = DEFAULT_MAX_HOLDING,
    val strategy: String = "ema_trend",
    val strategyParams: Map<String, Double> = emptyMap(),
    val combo: Combo? = null,
    val startDate: Long = 0,
    val endDate: Long = 0,
    val useAtr: Boolean = false,
    val atrSlMult: Double = 1.5,
    val atrTpMult: Double = 3.0,
    val filters: List<FilterCfg> = emptyList()
)

data class SignalDecision(
    val passed: Boolean,
    val direction: String,
    val confidence: Double,
    val reasons: List<String>,
    val strategy: String
)

data class FilterVerdict(val passed: Boolean, val failed: List<String>)

data class Trade(
    val direction: String, val asset: String, val timeframe: String,
    val entry: Double, val exit: Double, val stopLoss: Double, val takeProfit: Double,
    val entryTime: Long, val exitTime: Long, val strategy: String,
    val confidence: Double, val reasons: List<String>,
    val qty: Double, val riskAmount: Double, val fees: Double, val pnl: Double,
    val pnlPercent: Double, val rMultiple: Double, val result: String, val holding: Int
)

data class EquityPoint(val t: Long, val equity: Double)

data class BacktestDiag(
    val rawCount: Int = 0, val normalizedCount: Int = 0, val dateFilteredCount: Int = 0,
    val warmup: Int = WARMUP, val startIdx: Int = 0,
    val evaluatedBars: Int = 0, val skippedInPosition: Int = 0,
    val signalsRaw: Int = 0, val filteredOut: Int = 0,
    val skippedNoLevel: Int = 0, val skippedBadEntry: Int = 0,
    val skippedBadQty: Int = 0, val skippedBadPnl: Int = 0,
    val filterReasons: Map<String, Int> = emptyMap(),
    val evalFrom: Long = 0, val evalTo: Long = 0,
    val limitRequested: Int? = null, val received: Int? = null,
    val source: String? = null, val cacheUsed: String? = null,
    val note: String? = null
)

data class BacktestResult(
    val error: String? = null,
    val asset: String = "", val timeframe: String = "", val strategy: String = "",
    val initialCapital: Double = 0.0, val finalCapital: Double = 0.0,
    val totalTrades: Int = 0, val wins: Int = 0, val losses: Int = 0, val expired: Int = 0,
    val winRate: Double = 0.0, val lossRate: Double = 0.0,
    val grossProfit: Double = 0.0, val grossLoss: Double = 0.0,
    val netProfit: Double = 0.0, val netProfitPercent: Double = 0.0,
    val profitFactor: Double = 0.0, val expectancy: Double = 0.0,
    val averageWin: Double = 0.0, val averageLoss: Double = 0.0,
    val averageR: Double = 0.0, val averageRR: Double = 0.0,
    val maxDrawdown: Double = 0.0, val maxDrawdownPercent: Double = 0.0,
    val sharpe: Double = 0.0, val sortino: Double = 0.0, val calmar: Double = 0.0,
    val cagr: Double = 0.0, val exposure: Double = 0.0, val avgHolding: Double = 0.0,
    val longestWinStreak: Int = 0, val longestLossStreak: Int = 0, val filtered: Int = 0,
    val trades: List<Trade> = emptyList(), val equityCurve: List<EquityPoint> = emptyList(),
    val diag: BacktestDiag = BacktestDiag()
)

data class FilterCtx(
    var lastExit: Int = Int.MIN_VALUE,
    var lastSigDir: String? = null,
    var lastSigIdx: Int = 0,
    var lastExitT: Long? = null,
    var lastSigT: Long? = null,
    var lastSigDirT: String? = null,
    var tfMs: Long? = null
)

fun parseTimeframe(tf: String): Int {
    val m = Regex("""^(\d+)\s*([mhdw])$""").matchEntire(tf.trim().lowercase())
        ?: throw IllegalArgumentException("Timeframe tidak valid: \"$tf\". Gunakan format 15m, 1h, 4h, 1d, 1w.")
    val n = m.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("Timeframe tidak valid: \"$tf\".")
    if (n <= 0) throw IllegalArgumentException("Timeframe tidak valid: \"$tf\".")
    val minutes = when (m.groupValues[2]) {
        "m" -> n; "h" -> n * 60; "d" -> n * 1440; else -> n * 10080
    }
    if (minutes <= 0) throw IllegalArgumentException("Timeframe tidak valid: \"$tf\".")
    return minutes
}

fun utcHour(t: Long): Int = ((t / 3600000L) % 24L).toInt()
fun epochDay(t: Long): Long = Math.floorDiv(t, 86400000L)

private fun numD(v: Any?, dflt: Double): Double {
    if (v == null) return dflt
    if (v is Number) { val d = v.toDouble(); return if (d.isFinite()) d else dflt }
    val s = v.toString()
    if (s.isEmpty()) return dflt
    val d = s.replace(',', '.').toDoubleOrNull() ?: return dflt
    return if (d.isFinite()) d else dflt
}

private fun clampD(v: Double, a: Double, b: Double): Double = min(b, max(a, v))

// Normalisasi dari peta generik (t/o/h/l/c/v atau timestamp/open/... atau indeks array).
fun normalizeCandles(raw: List<Any?>): List<Candle> {
    val out = ArrayList<Candle>()
    val seen = HashSet<Long>()
    for (c in raw) {
        var t = 0L; var o = Double.NaN; var h = Double.NaN; var l = Double.NaN
        var cl = Double.NaN; var v = 0.0
        when (c) {
            is Map<*, *> -> {
                t = toMs(c["t"] ?: c["timestamp"])
                o = toD(c["o"] ?: c["open"]); h = toD(c["h"] ?: c["high"])
                l = toD(c["l"] ?: c["low"]); cl = toD(c["c"] ?: c["close"])
                v = toD(c["v"] ?: c["volume"], 0.0)
            }
            is List<*> -> {
                if (c.size < 5) continue
                t = toMs(c[0]); o = toD(c[1]); h = toD(c[2]); l = toD(c[3]); cl = toD(c[4])
                v = if (c.size > 5) toD(c[5], 0.0) else 0.0
            }
            else -> continue
        }
        if (t <= 0) continue
        if (!(o.isFinite() && h.isFinite() && l.isFinite() && cl.isFinite()))
            throw IllegalArgumentException("Data candle tidak valid (mengandung nilai non-finite/rusak).")
        if (h < maxOf(o, cl, l) - 1e-12 || l > minOf(o, cl, h) + 1e-12)
            throw IllegalArgumentException("Data candle tidak valid (high < max(open,close) atau low > min(open,close)).")
        if (!seen.add(t)) continue
        out.add(Candle(t, o, h, l, cl, if (v.isFinite() && v >= 0) v else 0.0))
    }
    out.sortBy { it.t }
    return out
}

private fun toMs(v: Any?): Long {
    if (v == null) return 0
    if (v is Number) return v.toLong()
    return v.toString().toDoubleOrNull()?.toLong() ?: 0
}

private fun toD(v: Any?, dflt: Double = Double.NaN): Double {
    if (v == null) return dflt
    if (v is Number) return v.toDouble()
    return v.toString().toDoubleOrNull() ?: dflt
}

fun filterByDate(candles: List<Candle>, startDate: Long, endDate: Long): List<Candle> =
    candles.filter { (!startDate.isPositive() || it.t >= startDate) && (!endDate.isPositive() || it.t <= endDate) }

private fun Long.isPositive(): Boolean = this > 0
