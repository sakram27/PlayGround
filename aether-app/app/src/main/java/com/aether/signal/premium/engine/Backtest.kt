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
    // V26: skema TP bertingkat (default = Single TP, perilaku lama utuh).
    val tpm = p.tpMode.coerceIn(1, 3)
    val m2 = clampD(p.tp2Mult, 1.0, 10.0)
    var m3 = clampD(p.tp3Mult, 1.0, 10.0)
    if (m3 < m2) m3 = m2 // jaga urutan tangga TP2 ≤ TP3
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
        atrSlMult = aSl, atrTpMult = aTp, tpMode = tpm, tp2Mult = m2, tp3Mult = m3,
        filters = filters
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

// V22: tahap mesin untuk Backtest Process Monitor. Aditif — callback opsional,
// nol perubahan logika saat null. Dipanggil dari thread pemanggil (bg).
sealed interface EngineStage {
    data class Validated(val normalized: Int, val inRange: Int) : EngineStage
    object IndicatorsDone : EngineStage
    data class Evaluating(val done: Int, val total: Int, val sigRaw: Int, val filtered: Int, val trades: Int) : EngineStage
    object MetricsDone : EngineStage
}

fun runBacktest(rawCandles: List<Any?>, rawParams: BacktestParams, onStage: ((EngineStage) -> Unit)? = null): BacktestResult {
    val params = sanitizeParams(rawParams)
    var diag = BacktestDiag(rawCount = rawCandles.size)
    // V21: batas 2 tahun ditegakkan MESIN (bukan hanya UI) — tolak sebelum unduh/proses.
    if (exceedsTwoYears(params.startDate, params.endDate)) {
        diag = diag.copy(runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
        return errorResult(params, "Rentang melebihi 2 tahun kalender. Pilih periode maksimal 2 tahun.", diag)
    }
    val full = normalizeCandles(rawCandles)
    diag = diag.copy(normalizedCount = full.size)
    // V17: data pemanasan (pra-startDate, tanpa transaksi) dipisah dari rentang
    // transaksi [startDate, endDate]. Evaluasi mulai pada bar WARMUP rentang
    // transaksi — set bar yang SAMA seperti sebelumnya, hanya riwayat indikator
    // lebih panjang bila data pra-start tersedia. Aturan strategi/filter/entry/
    // exit/slippage/fee tak berubah.
    val (prefix, inRange) = splitWarmup(full, params.startDate, params.endDate)
    diag = diag.copy(dateFilteredCount = inRange.size, warmupPrefix = prefix.size)
    try { onStage?.invoke(EngineStage.Validated(full.size, inRange.size)) } catch (e: Exception) { /* monitor tak boleh matikan mesin */ }
    if (inRange.size < MIN_CANDLES) {
        diag = diag.copy(runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
        return errorResult(params, "Butuh minimal $MIN_CANDLES candle (dapat ${inRange.size}). " +
            (if (params.startDate != 0L || params.endDate != 0L) "Coba perlebar rentang tanggal. " else "") +
            "Minta 1000 candle dari provider.", diag)
    }
    val candles = prefix + inRange
    val cache = buildCache(candles)
    try { onStage?.invoke(EngineStage.IndicatorsDone) } catch (e: Exception) { /* abaikan */ }
    val trades = ArrayList<Trade>()
    val equityCurve = ArrayList<EquityPoint>()
    var equity = params.initialCapital
    var peak = equity
    var maxDD = 0.0
    equityCurve.add(EquityPoint(candles[0].t, equity))

    val startIdx = maxOf(prefix.size + WARMUP, 2)
    // Batas tepat-60: butuh ≥1 bar untuk evaluasi setelah pemanasan.
    if (startIdx > candles.size - 2) {
        diag = diag.copy(runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
        return errorResult(params, "Data tidak cukup untuk evaluasi setelah pemanasan " +
            "(rentang ${inRange.size} candle). Perlebar rentang tanggal.", diag)
    }
    diag = diag.copy(startIdx = startIdx, evalFrom = candles[startIdx].t, evalTo = candles[candles.size - 2].t)
    val activeFilters = params.filters.filter { it.enabled }
    val fctx = FilterCtx(lastExit = -1000000000)
    var filtered = 0
    val passed = HashMap<String, Int>()
    var evaluated = 0; var sigRaw = 0
    var noLevel = 0; var badEntry = 0; var badQty = 0; var badPnl = 0
    val reasons = HashMap<String, Int>()
    var i = startIdx
    val totalSlots = (candles.size - 1) - startIdx
    val emitStep = maxOf(1, totalSlots / 20)
    var lastTradesEmitted = -1
    fun emitEval(force: Boolean = false) {
        val cb = onStage ?: return
        if (!force && evaluated % emitStep != 0 && trades.size == lastTradesEmitted) return
        lastTradesEmitted = trades.size
        try {
            cb(EngineStage.Evaluating(evaluated, totalSlots, sigRaw, filtered, trades.size))
        } catch (e: Exception) { /* monitor tak boleh matikan mesin */ }
    }
    while (i < candles.size - 1) {
        evaluated++
        emitEval()
        val dec = decideAt(candles, i, cache, params)
        if (!dec.passed) { i++; continue }
        sigRaw++
        if (activeFilters.isNotEmpty()) {
            val fr = applyFilters(candles, i, cache, dec.direction, activeFilters, fctx)
            if (fr.passed) for (f in activeFilters) passed[f.name] = (passed[f.name] ?: 0) + 1
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

        // V26: evaluasi target keluar bertingkat. SL selalu didahulukan bila satu
        // candle menyentuh SL+TP (konservatif, seperti perilaku lama). TP diisi
        // berurutan TP1→TP2→TP3; kaki terakhir menutup seluruh sisa posisi.
        val tpDist = abs(lv.tp - entry)
        val targets = tpTargets(entry, dec.direction, tpDist, params.tpMode, params.tp2Mult, params.tp3Mult)
        if (targets.isEmpty()) { noLevel++; i++; continue }
        val fracs = tpFractions(params.tpMode)
        val qtyOrig = qty
        var remaining = qty
        var leg = 0
        data class Leg(val kind: String, val exit: Double, val exitIdx: Int, val qtyClosed: Double, val target: Double)
        val legs = ArrayList<Leg>()
        val lastJ = minOf(candles.size - 1, i + params.maxHolding)
        var j = i + 1
        while (j <= lastJ && remaining / qtyOrig > 1e-9) {
            val b = candles[j]
            val slHit: Boolean
            val tpHit: Boolean
            if (dec.direction == "LONG") {
                slHit = b.l <= lv.sl
                tpHit = leg < targets.size && b.h >= targets[leg]
            } else {
                slHit = b.h >= lv.sl
                tpHit = leg < targets.size && b.l <= targets[leg]
            }
            if (slHit && tpHit) {
                val ex = if (dec.direction == "LONG") lv.sl * (1 - params.slippagePercent) else lv.sl * (1 + params.slippagePercent)
                legs.add(Leg("SL", ex, j, remaining, lv.tp)); remaining = 0.0; break
            }
            if (slHit) {
                val ex = if (dec.direction == "LONG") lv.sl * (1 - params.slippagePercent) else lv.sl * (1 + params.slippagePercent)
                legs.add(Leg("SL", ex, j, remaining, lv.tp)); remaining = 0.0; break
            }
            if (tpHit) {
                val isLast = leg >= targets.size - 1
                val qClose = if (isLast) remaining else qtyOrig * fracs.getOrElse(leg) { 1.0 }
                val ex = if (dec.direction == "LONG") targets[leg] * (1 - params.slippagePercent) else targets[leg] * (1 + params.slippagePercent)
                legs.add(Leg("TP${leg + 1}", ex, j, qClose, targets[leg])); remaining -= qClose; leg++
            }
            j++
        }
        if (remaining / qtyOrig > 1e-9) {
            val b = candles[lastJ]
            val ex = if (dec.direction == "LONG") b.c * (1 - params.slippagePercent) else b.c * (1 + params.slippagePercent)
            legs.add(Leg("EXPIRED", ex, lastJ, remaining, lv.tp)); remaining = 0.0
        }
        // Materialisasi kaki menjadi catatan ledger (satu Trade per kaki).
        // Fase 1 (murni): hitung semua kaki dulu. Gagal di sini = posisi gugur
        // total tanpa menyentuh ekuitas/kurva/ledger (cermin badPnl lama).
        data class LegCalc(val leg: Leg, val result: String, val gross: Double, val fees: Double, val pnl: Double, val legRisk: Double, val rMult: Double)
        val calcs = ArrayList<LegCalc>()
        var badLeg = false
        for (lf in legs) {
            val result = when (lf.kind) {
                "SL" -> "LOSS"
                "EXPIRED" -> "EXPIRED"
                else -> "WIN"
            }
            val gross = (if (dec.direction == "LONG") lf.exit - entry else entry - lf.exit) * lf.qtyClosed
            val fees = (entry * lf.qtyClosed + lf.exit * lf.qtyClosed) * params.feePercent
            val pnl = gross - fees
            if (!pnl.isFinite() || !lf.qtyClosed.isFinite() || lf.qtyClosed <= 0) { badLeg = true; break }
            val legRisk = if (qtyOrig > 0) riskAmount * (lf.qtyClosed / qtyOrig) else 0.0
            val rMult = if (legRisk > 0) pnl / legRisk else 0.0
            if (!rMult.isFinite() && pnl != 0.0) { badLeg = true; break }
            calcs.add(LegCalc(lf, result, gross, fees, pnl, legRisk, rMult))
        }
        // Uji tuntas: ekuitas hasil pun harus finite (cermin penjaga lama).
        if (!badLeg) {
            var probe = equity
            for (c in calcs) {
                probe += c.pnl
                if (!probe.isFinite()) { badLeg = true; break }
            }
        }
        if (badLeg || calcs.isEmpty()) { badPnl++; i++; continue }
        // Fase 2: tulis ledger + kurva + ekuitas berurutan.
        var lastExitIdx = i + 1
        for ((k, c) in calcs.withIndex()) {
            val lf = c.leg
            equity += c.pnl
            peak = maxOf(peak, equity)
            val dd = if (peak > 0) (peak - equity) / peak else 0.0
            if (dd > maxDD) maxDD = dd
            equityCurve.add(EquityPoint(candles[lf.exitIdx].t, equity))
            trades.add(Trade(
                direction = dec.direction, asset = params.asset, timeframe = params.timeframe,
                entry = entry, exit = lf.exit, stopLoss = lv.sl, takeProfit = lf.target,
                entryTime = next.t, exitTime = candles[lf.exitIdx].t,
                strategy = dec.strategy, confidence = dec.confidence, reasons = dec.reasons + "leg ${lf.kind}",
                qty = lf.qtyClosed, riskAmount = c.legRisk, fees = c.fees, pnl = c.pnl,
                pnlPercent = if (entry > 0) ((if (dec.direction == "LONG") lf.exit - entry else entry - lf.exit) / entry) * 100 * params.leverage else 0.0,
                rMultiple = c.rMult, result = c.result, holding = lf.exitIdx - (i + 1) + 1,
                uid = "${params.asset}|${dec.direction}|${next.t}|${candles[lf.exitIdx].t}|leg$k"
            ))
            lastExitIdx = lf.exitIdx
        }
        i = lastExitIdx + 1 // cermin JS: i = exitIdx lalu i++ oleh for → lanjut di exitIdx+1
        fctx.lastExit = lastExitIdx
    }
    emitEval(force = true)

    val skipped = maxOf(0, ((candles.size - 1) - startIdx) - evaluated)
    diag = diag.copy(
        evaluatedBars = evaluated, skippedInPosition = skipped, signalsRaw = sigRaw,
        filteredOut = filtered, skippedNoLevel = noLevel, skippedBadEntry = badEntry,
        skippedBadQty = badQty, skippedBadPnl = badPnl, filterReasons = reasons, filterPassed = passed,
        runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis()
    )
    try { onStage?.invoke(EngineStage.MetricsDone) } catch (e: Exception) { /* abaikan */ }
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
