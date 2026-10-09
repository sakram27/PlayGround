package com.aether.signal.premium.engine

import kotlin.math.*

// Port 1:1 runBacktest + sanitizeParams + calcRiskLevels + buildResult + genDemoCandles core.js.

fun sanitizeParams(raw: BacktestParams): BacktestParams {
    val p = raw
    val asset = p.asset.uppercase().replace(Regex("[^A-Z0-9]"), "").ifEmpty { "BTCUSDT" }
    parseTimeframe(p.timeframe)
    if (!(p.initialCapital > 0) || !p.initialCapital.isFinite()) throw IllegalArgumentException("Modal awal harus > 0.")
    val risk = clampD(p.riskPerTrade, 0.001, 0.10)
    val lev = clampD(p.leverage.toDouble(), 1.0, 100.0).toInt()
    val fee = clampD(p.feePercent, 0.0, 0.02)
    val slip = clampD(p.slippagePercent, 0.0, 0.02)
    val sl = clampD(p.slPercent, 0.0005, 0.5)
    val tp = clampD(p.tpPercent, 0.0005, 1.0)
    val mh = clampD(p.maxHolding.toDouble(), 1.0, 2000.0).toInt()
    val aSl = clampD(p.atrSlMult, 0.2, 10.0)
    val aTp = clampD(p.atrTpMult, 0.2, 20.0)
    if (p.startDate != 0L && p.endDate != 0L && p.endDate <= p.startDate)
        throw IllegalArgumentException("Tanggal akhir harus sesudah tanggal awal.")
    if (!STRATEGIES.containsKey(p.strategy)) throw IllegalArgumentException("Strategi tidak dikenal: " + p.strategy)
    val filters = (raw.filters).mapNotNull { f ->
        val def = FILTER_DEFS.find { it.id == f.name } ?: return@mapNotNull null
        val merged = HashMap(def.defaults)
        merged.putAll(f.params)
        FilterCfg(f.name, f.enabled, merged, f.sessions)
    }
    return p.copy(
        asset = asset, riskPerTrade = risk, leverage = lev, feePercent = fee,
        slippagePercent = slip, slPercent = sl, tpPercent = tp, maxHolding = mh,
        atrSlMult = aSl, atrTpMult = aTp, filters = filters
    )
}

private fun clampD(v: Double, a: Double, b: Double): Double = min(b, max(a, v))

data class RiskLevels(val sl: Double, val tp: Double, val riskDist: Double)

fun calcRiskLevels(entry: Double, direction: String, cache: Cache, idx: Int, params: BacktestParams): RiskLevels? {
    var sl: Double; var tp: Double
    if (params.useAtr && !cache.atr14[idx].isNaN() && cache.atr14[idx] > 0) {
        val a = cache.atr14[idx]
        if (direction == "LONG") { sl = entry - a * params.atrSlMult; tp = entry + a * params.atrTpMult } else { sl = entry + a * params.atrSlMult; tp = entry - a * params.atrTpMult }
    } else if (direction == "LONG") { sl = entry * (1 - params.slPercent); tp = entry * (1 + params.tpPercent) } else { sl = entry * (1 + params.slPercent); tp = entry * (1 - params.tpPercent) }
    if (!sl.isFinite() || !tp.isFinite() || sl <= 0 || tp <= 0) return null
    if (direction == "LONG" && !(sl < entry && tp > entry)) return null
    if (direction == "SHORT" && !(sl > entry && tp < entry)) return null
    val riskDist = abs(entry - sl)
    if (!(riskDist > 0) || !riskDist.isFinite()) return null
    return RiskLevels(sl, tp, riskDist)
}

fun errorResult(params: BacktestParams, message: String, diag: BacktestDiag = BacktestDiag(note = "NOT MEASURED: engine berhenti sebelum evaluasi (data < minimum).")): BacktestResult =
    BacktestResult(error = message, asset = params.asset, timeframe = params.timeframe, initialCapital = params.initialCapital, finalCapital = params.initialCapital, diag = diag)

fun runBacktest(rawCandles: List<Any?>, rawParams: BacktestParams): BacktestResult {
    val params = sanitizeParams(rawParams)
    var diag = BacktestDiag(rawCount = rawCandles.size)
    var candles = normalizeCandles(rawCandles)
    diag = diag.copy(normalizedCount = candles.size)
    candles = filterByDate(candles, params.startDate, params.endDate)
    diag = diag.copy(dateFilteredCount = candles.size)
    if (candles.size < MIN_CANDLES) {
        return errorResult(params, "Butuh minimal $MIN_CANDLES candle (dapat ${candles.size}). " +
            (if (params.startDate != 0L || params.endDate != 0L) "Coba perlebar rentang tanggal. " else "") +
            "Minta 1000 candle dari provider.", diag)
    }
    val cache = buildCache(candles)
    val trades = ArrayList<Trade>()
    val equityCurve = ArrayList<EquityPoint>()
    var equity = params.initialCapital
    var peak = equity
    var maxDD = 0.0
    equityCurve.add(EquityPoint(candles[0].t, equity))

    val startIdx = maxOf(WARMUP, 2)
    diag = diag.copy(startIdx = startIdx, evalFrom = candles[startIdx].t, evalTo = candles[candles.size - 2].t)
    val activeFilters = params.filters.filter { it.enabled }
    val fctx = FilterCtx(lastExit = -1000000000)
    var filtered = 0
    var evaluated = 0; var sigRaw = 0
    var noLevel = 0; var badEntry = 0; var badQty = 0; var badPnl = 0
    val reasons = HashMap<String, Int>()
    var i = startIdx
    while (i < candles.size - 1) {
        evaluated++
        val dec = decideAt(candles, i, cache, params)
        if (!dec.passed) { i++; continue }
        sigRaw++
        if (activeFilters.isNotEmpty()) {
            val fr = applyFilters(candles, i, cache, dec.direction, activeFilters, fctx)
            fctx.lastSigDir = dec.direction; fctx.lastSigIdx = i
            if (!fr.passed) {
                filtered++
                for (w in fr.failed) { val k = w.take(60); reasons[k] = (reasons[k] ?: 0) + 1 }
                i++; continue
            }
        }
        val next = candles[i + 1]
        var entry = next.o
        entry = if (dec.direction == "LONG") entry * (1 + params.slippagePercent) else entry * (1 - params.slippagePercent)
        if (!entry.isFinite() || entry <= 0) { badEntry++; i++; continue }
        val lv = calcRiskLevels(entry, dec.direction, cache, i, params)
        if (lv == null) { noLevel++; i++; continue }
        val riskAmount = equity * params.riskPerTrade
        var qty = riskAmount / lv.riskDist
        val maxNotional = equity * params.leverage
        val notional = qty * entry
        if (!qty.isFinite() || qty <= 0) { badQty++; i++; continue }
        if (notional > maxNotional) qty = maxNotional / entry
        if (!(qty > 0) || !qty.isFinite()) { badQty++; i++; continue }

        var exit: Double? = null; var exitIdx = -1; var result = "EXPIRED"
        val lastJ = minOf(candles.size - 1, i + params.maxHolding)
        var j = i + 1
        while (j <= lastJ) {
            val b = candles[j]
            val slHit: Boolean; val tpHit: Boolean
            if (dec.direction == "LONG") { slHit = b.l <= lv.sl; tpHit = b.h >= lv.tp } else { slHit = b.h >= lv.sl; tpHit = b.l <= lv.tp }
            if (slHit && tpHit) {
                exit = if (dec.direction == "LONG") lv.sl * (1 - params.slippagePercent) else lv.sl * (1 + params.slippagePercent)
                exitIdx = j; result = "LOSS"; break
            }
            if (slHit) {
                exit = if (dec.direction == "LONG") lv.sl * (1 - params.slippagePercent) else lv.sl * (1 + params.slippagePercent)
                exitIdx = j; result = "LOSS"; break
            }
            if (tpHit) {
                exit = if (dec.direction == "LONG") lv.tp * (1 - params.slippagePercent) else lv.tp * (1 + params.slippagePercent)
                exitIdx = j; result = "WIN"; break
            }
            j++
        }
        if (exit == null) {
            val b = candles[lastJ]
            exit = if (dec.direction == "LONG") b.c * (1 - params.slippagePercent) else b.c * (1 + params.slippagePercent)
            exitIdx = lastJ; result = "EXPIRED"
        }
        val ex = exit
        val gross = (if (dec.direction == "LONG") ex - entry else entry - ex) * qty
        val fees = (entry * qty + ex * qty) * params.feePercent
        val pnl = gross - fees
        if (!pnl.isFinite()) { badPnl++; i++; continue }
        val rMult = if (riskAmount > 0) pnl / riskAmount else 0.0
        equity += pnl
        if (!equity.isFinite()) equity = params.initialCapital
        peak = maxOf(peak, equity)
        val dd = if (peak > 0) (peak - equity) / peak else 0.0
        if (dd > maxDD) maxDD = dd
        equityCurve.add(EquityPoint(candles[exitIdx].t, equity))
        trades.add(Trade(
            direction = dec.direction, asset = params.asset, timeframe = params.timeframe,
            entry = entry, exit = ex, stopLoss = lv.sl, takeProfit = lv.tp,
            entryTime = next.t, exitTime = candles[exitIdx].t,
            strategy = dec.strategy, confidence = dec.confidence, reasons = dec.reasons,
            qty = qty, riskAmount = riskAmount, fees = fees, pnl = pnl,
            pnlPercent = if (entry > 0) ((if (dec.direction == "LONG") ex - entry else entry - ex) / entry) * 100 * params.leverage else 0.0,
            rMultiple = rMult, result = result, holding = exitIdx - (i + 1) + 1
        ))
        i = exitIdx + 1 // cermin JS: i = exitIdx lalu i++ oleh for → lanjut di exitIdx+1
        fctx.lastExit = exitIdx
    }

    val skipped = maxOf(0, ((candles.size - 1) - startIdx) - evaluated)
    diag = diag.copy(
        evaluatedBars = evaluated, skippedInPosition = skipped, signalsRaw = sigRaw,
        filteredOut = filtered, skippedNoLevel = noLevel, skippedBadEntry = badEntry,
        skippedBadQty = badQty, skippedBadPnl = badPnl, filterReasons = reasons
    )
    return buildResult(params, candles, trades, equityCurve, maxDD, filtered, diag)
}

fun buildResult(params: BacktestParams, candles: List<Candle>, trades: List<Trade>, equityCurve: List<EquityPoint>, maxDD: Double, filtered: Int = 0, diag: BacktestDiag = BacktestDiag()): BacktestResult {
    val wins = trades.filter { it.result == "WIN" }
    val losses = trades.filter { it.result == "LOSS" }
    val expired = trades.filter { it.result == "EXPIRED" }
    val total = trades.size
    val grossProfit = wins.sumOf { it.pnl }
    val grossLossAbs = abs(losses.sumOf { it.pnl })
    val netProfit = trades.sumOf { it.pnl }
    val finalCapital = params.initialCapital + netProfit
    val winRate = if (total > 0) wins.size.toDouble() / total * 100 else 0.0
    val profitFactor = if (grossLossAbs > 0) grossProfit / grossLossAbs else if (grossProfit > 0) Double.POSITIVE_INFINITY else 0.0
    val expectancy = if (total > 0) netProfit / total else 0.0
    val averageWin = if (wins.isNotEmpty()) grossProfit / wins.size else 0.0
    val averageLoss = if (losses.isNotEmpty()) -grossLossAbs / losses.size else 0.0
    val averageR = if (total > 0) trades.sumOf { it.rMultiple } / total else 0.0
    var lw = 0; var ll = 0; var cw = 0; var cl = 0
    for (t in trades) {
        if (t.result == "WIN") { cw++; cl = 0; lw = maxOf(lw, cw) } else if (t.result == "LOSS") { cl++; cw = 0; ll = maxOf(ll, cl) } else { cw = 0; cl = 0 }
    }
    val rets = trades.map { it.pnl / params.initialCapital }
    val sharpe = sharpeRatio(rets)
    val sortino = sortinoRatio(rets)
    val maxDDPct = maxDD * 100
    val years = if (candles.size > 1) maxOf((candles.last().t - candles.first().t).toDouble() / (365.25 * 86400000), 1 / 365.25) else 1 / 365.25
    val cagr = if (params.initialCapital > 0 && finalCapital > 0) (Math.pow(finalCapital / params.initialCapital, 1 / years) - 1) * 100 else 0.0
    val calmar = if (maxDD > 0) cagr / (maxDDPct.takeIf { it != 0.0 } ?: 1.0) else if (cagr > 0) Double.POSITIVE_INFINITY else 0.0
    val totalHolding = trades.sumOf { it.holding }
    val exposure = if (candles.isNotEmpty()) totalHolding.toDouble() / candles.size * 100 else 0.0
    val rrs = trades.map {
        val risk = abs(it.entry - it.stopLoss)
        val rew = abs(it.takeProfit - it.entry)
        if (risk > 0) rew / risk else 0.0
    }
    return BacktestResult(
        asset = params.asset, timeframe = params.timeframe, strategy = labelStrategy(params),
        initialCapital = params.initialCapital, finalCapital = finalCapital,
        totalTrades = total, wins = wins.size, losses = losses.size, expired = expired.size,
        winRate = winRate, lossRate = if (total > 0) losses.size.toDouble() / total * 100 else 0.0,
        grossProfit = grossProfit, grossLoss = -grossLossAbs, netProfit = netProfit,
        netProfitPercent = if (params.initialCapital != 0.0) netProfit / params.initialCapital * 100 else 0.0,
        profitFactor = if (profitFactor.isFinite()) profitFactor else if (profitFactor == Double.POSITIVE_INFINITY) 999.0 else 0.0,
        expectancy = expectancy, averageWin = averageWin, averageLoss = averageLoss, averageR = averageR,
        averageRR = if (rrs.isNotEmpty()) rrs.sum() / rrs.size else 0.0,
        maxDrawdown = params.initialCapital * maxDD, maxDrawdownPercent = maxDDPct,
        sharpe = finite(sharpe), sortino = finite(sortino),
        calmar = if (calmar.isFinite()) calmar else if (calmar == Double.POSITIVE_INFINITY) 999.0 else 0.0,
        cagr = finite(cagr), exposure = finite(exposure),
        avgHolding = if (total > 0) totalHolding.toDouble() / total else 0.0,
        longestWinStreak = lw, longestLossStreak = ll, filtered = filtered,
        trades = trades, equityCurve = equityCurve, diag = diag
    )
}

private fun finite(v: Double): Double = if (v.isFinite()) v else 0.0

fun sharpeRatio(rets: List<Double>): Double {
    if (rets.size < 2) return 0.0
    val m = rets.sum() / rets.size
    val sd = sqrt(rets.sumOf { (it - m) * (it - m) } / (rets.size - 1))
    return if (sd == 0.0) 0.0 else (m / sd) * sqrt(rets.size.toDouble())
}

fun sortinoRatio(rets: List<Double>): Double {
    if (rets.size < 2) return 0.0
    val m = rets.sum() / rets.size
    val dn = rets.filter { it < 0 }
    if (dn.isEmpty()) return if (m > 0) 99.0 else 0.0
    val dsd = sqrt(dn.sumOf { it * it } / dn.size)
    return if (dsd == 0.0) 0.0 else (m / dsd) * sqrt(rets.size.toDouble())
}

fun labelStrategy(params: BacktestParams): String =
    if (!params.combo?.strategies.isNullOrEmpty()) params.combo.strategies.joinToString(" + ") + " (" + (params.combo.mode.ifEmpty { "OR" }) + ")"
    else params.strategy

// Demo generator seeded — replikasi bit-eksak LCG JS.
fun genDemoCandles(seed: Int = 42, n: Int = 500, startPrice: Double = 65000.0, tfMin: Int = 15, nowMs: Long = System.currentTimeMillis()): List<Candle> {
    var rnd = seed.toLong() and 0xFFFFFFFFL
    fun rand(): Double {
        rnd = (rnd * 1664525L + 1013904223L) and 0xFFFFFFFFL
        return rnd.toDouble() / 4294967296.0
    }
    val out = ArrayList<Candle>()
    var price = startPrice
    var t = nowMs - n * tfMin * 60000L
    t -= t % (tfMin * 60000L)
    var trend = 0.0
    for (idx in 0 until n) {
        if (idx % 80 == 0) trend = (rand() - 0.5) * 0.004
        val drift = trend + (rand() - 0.5) * 0.006
        val o = price
        val c = maxOf(1.0, o * (1 + drift))
        val h = maxOf(o, c) * (1 + rand() * 0.0015)
        val l = minOf(o, c) * (1 - rand() * 0.0015)
        val v = 50 + rand() * 200 + abs(drift) * 80000
        out.add(Candle(t, o, h, l, c, v))
        price = c; t += tfMin * 60000L
    }
    return out
}
