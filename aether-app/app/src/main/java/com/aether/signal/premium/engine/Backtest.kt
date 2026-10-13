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
    val trDist = if (p.trailDistPct.isFinite()) p.trailDistPct.coerceIn(0.0005, 0.5) else 0.01
    val trAct = if (p.trailActPct.isFinite()) p.trailActPct.coerceIn(0.0005, 0.5) else 0.01
    // Tahap 4: batas posisi disanitasi eksplisit (rekonstruksi V41).
    val mo = sanitizeMaxOpenTrades(p.maxOpenTrades)
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
        trailDistPct = trDist, trailActPct = trAct, maxOpenTrades = mo,
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

fun runBacktest(rawCandles: List<Any?>, rawParams: BacktestParams,
    // V30: pembatalan kooperatif — UI meneruskan isCancelled = { cancelled }.
    // Diperiksa tiap bar evaluasi + tiap bar pencarian exit; batal = errorResult
    // "Dibatalkan", bukan hasil sukses. Ditempatkan SEBELUM onStage agar
    // trailing-lambda lama { ev -> ... } tetap mengikat onStage. Default =
    // perilaku lama (tak pernah batal).
    isCancelled: () -> Boolean = { false },
    onStage: ((EngineStage) -> Unit)? = null): BacktestResult {
    val params = sanitizeParams(rawParams)
    var diag = BacktestDiag(rawCount = rawCandles.size)
    // V34: sidik dari parameter sanitasi = yang benar-benar dipakai simulasi.
    diag = diag.copy(cfgFp = configFingerprint(params))
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
    val startIdx = maxOf(prefix.size + WARMUP, 2)
    // Batas tepat-60: butuh ≥1 bar untuk evaluasi setelah pemanasan.
    if (startIdx > candles.size - 2) {
        diag = diag.copy(runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
        return errorResult(params, "Data tidak cukup untuk evaluasi setelah pemanasan " +
            "(rentang ${inRange.size} candle). Perlebar rentang tanggal.", diag)
    }
    // Tahap 4: inti posisi-bersamaan (rekonstruksi V41, bukan klaim identik).
    // - cap = coerceIn(maxOpenTrades,1,10) dari parameter sanitasi.
    // - Tiap bar: exit-kan posisi jatuh tempo (urut seq), lalu — bila tak ada
    //   penutupan bar ini (closedHere) — evaluasi entry baru. Entry di open
    //   bar berikut (tanpa look-ahead), SL/TP/fee/slippage/risk/maxHolding
    //   sama seperti simulasi sekuensial lama. Dengan cap=1 urutan buka-tutup
    //   identik dengan perilaku lama.
    val cap = params.maxOpenTrades.coerceIn(MAX_OPEN_TRADES_MIN, MAX_OPEN_TRADES_MAX)
    val asset = LoopAsset(params.asset, candles, cache, startIdx)
    return runLoopMulti(
        assets = listOf(asset), params = params, cap = cap,
        totalSlots = (candles.size - 1) - startIdx,
        isCancelled = isCancelled, onStage = onStage, diag = diag,
        startT = candles[startIdx].t, endT = candles[candles.size - 2].t,
        diagStartIdx = startIdx
    ).first
}

// ---------- Tahap 4: primitif posisi (dipakai single + portofolio) ----------

/** Posisi terbuka satu kaki-grup (rekonstruksi V41 OpenPos — ditandai). */
class OpenPos(
    val seq: Int,
    val dir: String,
    val entry: Double,
    val entryIdx: Int,
    val entryT: Long,
    val assetTag: String,
    val qtyOrig: Double,
    var remaining: Double,
    var leg: Int,
    val targets: List<Double>,
    val fracs: List<Double>,
    val lv: RiskLevels,
    val riskAmount: Double,
    val dec: SignalDecision,
    val tst: TrailSim,
    val lastJ: Int
)

/** Satu kaki keluar (hasil satu bar exit). */
data class Leg(val kind: String, val exit: Double, val exitIdx: Int, val qtyClosed: Double, val target: Double)

/** Ekuitas bersama (portofolio memakai satu untuk semua pair). */
class EqState(var equity: Double, var peak: Double, var maxDD: Double)

class OpenCounters(var noLevel: Int = 0, var badEntry: Int = 0, var badQty: Int = 0)

/**
 * Coba buka posisi dari sinyal bar [sigIdx] (entry di open bar berikut).
 * Mengembalikan OpenPos bila valid, else null (counter dicatat).
 * Aturan identik simulasi lama: sizing risiko dari ekuitas SAAT ini.
 */
fun tryOpenPosition(
    candles: List<Candle>, sigIdx: Int, params: BacktestParams, cache: Cache,
    equityNow: Double, dec: SignalDecision, seq: Int, assetTag: String,
    cnt: OpenCounters
): OpenPos? {
    val next = candles[sigIdx + 1]
    var entry = next.o
    entry = if (dec.direction == "LONG") entry * (1 + params.slippagePercent) else entry * (1 - params.slippagePercent)
    if (!entry.isFinite() || entry <= 0) { cnt.badEntry++; return null }
    val lv = calcRiskLevels(entry, dec.direction, cache, sigIdx, params)
    if (lv == null) { cnt.noLevel++; return null }
    val riskAmount = equityNow * params.riskPerTrade
    var qty = riskAmount / lv.riskDist
    val maxNotional = equityNow * params.leverage
    val notional = qty * entry
    if (!qty.isFinite() || qty <= 0) { cnt.badQty++; return null }
    if (notional > maxNotional) qty = maxNotional / entry
    if (!(qty > 0) || !qty.isFinite()) { cnt.badQty++; return null }
    // V26: evaluasi target keluar bertingkat (sama seperti mesin lama).
    val tpDist = abs(lv.tp - entry)
    val targets = tpTargets(entry, dec.direction, tpDist, params.tpMode, params.tp2Mult, params.tp3Mult)
    if (targets.isEmpty()) { cnt.noLevel++; return null }
    val fracs = tpFractions(params.tpMode)
    val lastJ = minOf(candles.size - 1, sigIdx + params.maxHolding)
    return OpenPos(
        seq, dec.direction, entry, sigIdx + 1, next.t, assetTag,
        qty, qty, 0, targets, fracs, lv, riskAmount, dec, TrailSim(), lastJ
    )
}

/**
 * Satu langkah exit posisi pada bar [idx] (maksimal satu kaki per bar).
 * Prioritas konservatif: harga keluar TERBURUK bagi posisi; seri →
 * SL > TRAIL > TP. Tanpa hit dan idx >= lastJ → EXPIRED. Null = bertahan.
 * Port 1:1 aturan bar mesin lama (dipindah dari loop exit inline).
 */
fun exitStepPosition(params: BacktestParams, candles: List<Candle>, op: OpenPos, b: Candle, idx: Int): Leg? {
    val slHit: Boolean
    val tpHit: Boolean
    if (op.dir == "LONG") {
        slHit = b.l <= op.lv.sl
        tpHit = op.leg < op.targets.size && b.h >= op.targets[op.leg]
    } else {
        slHit = b.h >= op.lv.sl
        tpHit = op.leg < op.targets.size && b.l <= op.targets[op.leg]
    }
    // V31: kandidat trailing (hanya bila trailOn).
    var trailHit = false
    var trailStopPx = Double.NaN
    if (params.trailOn) {
        val hit = trailExitPrice(op.dir, op.entry, b.h, b.l, params.trailActPct, params.trailDistPct, op.tst)
        if (hit != null && hit.isFinite() && hit > 0) {
            trailHit = true
            trailStopPx = hit
        }
    }
    fun trailEx(): Double = if (op.dir == "LONG") trailStopPx * (1 - params.slippagePercent)
    else trailStopPx * (1 + params.slippagePercent)
    if (slHit || tpHit || trailHit) {
        data class Cand(val px: Double, val kind: String)
        val cands = ArrayList<Cand>()
        if (slHit) cands.add(Cand(
            if (op.dir == "LONG") op.lv.sl * (1 - params.slippagePercent) else op.lv.sl * (1 + params.slippagePercent), "SL"))
        if (tpHit) cands.add(Cand(
            if (op.dir == "LONG") op.targets[op.leg] * (1 - params.slippagePercent) else op.targets[op.leg] * (1 + params.slippagePercent), "TP${op.leg + 1}"))
        if (trailHit) cands.add(Cand(trailEx(), "TRAIL"))
        val rank = mapOf("SL" to 0, "TRAIL" to 1)
        val win = if (op.dir == "LONG") cands.minWithOrNull(compareBy({ it.px }, { rank[it.kind] ?: 2 }))
        else cands.maxWithOrNull(compareBy({ it.px }, { -(rank[it.kind] ?: 2) }))
        if (win != null && (win.kind == "SL" || win.kind == "TRAIL")) {
            return Leg(win.kind, win.px, idx, op.remaining, op.lv.tp)
        }
        if (win != null) {
            val isLast = op.leg >= op.targets.size - 1
            val qClose = if (isLast) op.remaining else op.qtyOrig * op.fracs.getOrElse(op.leg) { 1.0 }
            return Leg("TP${op.leg + 1}", win.px, idx, qClose, op.targets[op.leg])
        }
    } else if (params.trailOn) {
        // Bar tanpa exit → ratchet (extreme hanya dari bar ini/masa lalu).
        trailRatchet(op.dir, b.h, b.l, params.trailDistPct, op.tst)
    }
    if (idx >= op.lastJ) {
        val ex = if (op.dir == "LONG") b.c * (1 - params.slippagePercent) else b.c * (1 + params.slippagePercent)
        return Leg("EXPIRED", ex, idx, op.remaining, op.lv.tp)
    }
    return null
}

/**
 * Materialisasi satu kaki ke ledger (satu Trade per kaki). False = data tak valid → posisi gugur total tanpa menyentuh ekuitas/kurva
 * (cermin badPnl lama). Format uid identik mesin lama:
 * "aset|arah|entryT|exitT|legK" (K = indeks kaki posisi itu).
 */
fun applyLegPosition(
    params: BacktestParams, candles: List<Candle>, st: EqState,
    equityCurve: ArrayList<EquityPoint>, trades: ArrayList<Trade>,
    op: OpenPos, lf: Leg
): Boolean {
    val result = when (lf.kind) {
        "SL" -> "LOSS"
        "EXPIRED" -> "EXPIRED"
        "TRAIL" -> "TRAIL"
        else -> "WIN"
    }
    val gross = (if (op.dir == "LONG") lf.exit - op.entry else op.entry - lf.exit) * lf.qtyClosed
    val fees = (op.entry * lf.qtyClosed + lf.exit * lf.qtyClosed) * params.feePercent
    val pnl = gross - fees
    if (!pnl.isFinite() || !lf.qtyClosed.isFinite() || lf.qtyClosed <= 0) return false
    val legRisk = if (op.qtyOrig > 0) op.riskAmount * (lf.qtyClosed / op.qtyOrig) else 0.0
    val rMult = if (legRisk > 0) pnl / legRisk else 0.0
    if (!rMult.isFinite() && pnl != 0.0) return false
    val probe = st.equity + pnl
    if (!probe.isFinite()) return false
    st.equity = probe
    st.peak = maxOf(st.peak, st.equity)
    val dd = if (st.peak > 0) (st.peak - st.equity) / st.peak else 0.0
    if (dd > st.maxDD) st.maxDD = dd
    equityCurve.add(EquityPoint(candles[lf.exitIdx].t, st.equity))
    trades.add(Trade(
        direction = op.dir, asset = op.assetTag, timeframe = params.timeframe,
        entry = op.entry, exit = lf.exit, stopLoss = op.lv.sl, takeProfit = lf.target,
        entryTime = op.entryT, exitTime = candles[lf.exitIdx].t,
        strategy = op.dec.strategy, confidence = op.dec.confidence,
        reasons = op.dec.reasons + "leg ${lf.kind}",
        qty = lf.qtyClosed, riskAmount = legRisk, fees = fees, pnl = pnl,
        pnlPercent = if (op.entry > 0) ((if (op.dir == "LONG") lf.exit - op.entry else op.entry - lf.exit) / op.entry) * 100 * params.leverage else 0.0,
        rMultiple = rMult, result = result, holding = lf.exitIdx - op.entryIdx + 1,
        uid = "${op.assetTag}|${op.dir}|${op.entryT}|${candles[lf.exitIdx].t}|leg${op.leg}"
    ))
    return true
}

// ---------- Loop mesin bersama (single-pair memakai ini dengan 1 aset) ----------

/** Konteks satu aset dalam loop (single = satu; portofolio = banyak). */
class LoopAsset(
    val sym: String,
    val candles: List<Candle>,
    val cache: Cache,
    val startIdx: Int,
    val fctx: FilterCtx = FilterCtx(lastExit = -1000000000)
)

/**
 * Loop evaluasi+exit generik. Mengembalikan (hasil, linkedDiag).
 * [slotTotal] = jumlah slot evaluasi (untuk skippedInPosition).
 * Aturan closedHere: bar tempat penutupan terjadi melewatkan entry baru
 * (rekonstruksi V41 — didokumentasikan, diuji eksplisit).
 */
/**
 * Loop multi-aset generik (portofolio global). Timeline digabung per
 * timestamp (urut), aset diproses deterministik sesuai urutan input.
 * Ekuitas + cap + counter BERSAMA seluruh aset.
 */
fun runLoopMulti(
    assets: List<LoopAsset>,
    params: BacktestParams,
    cap: Int,
    totalSlots: Int,
    isCancelled: () -> Boolean,
    onStage: ((EngineStage) -> Unit)?,
    diag: BacktestDiag,
    startT: Long,
    endT: Long,
    diagStartIdx: Int = 0
): Pair<BacktestResult, BacktestDiag> {
    var d = diag
    val activeFilters = params.filters.filter { it.enabled }
    val trades = ArrayList<Trade>()
    val equityCurve = ArrayList<EquityPoint>()
    val st = EqState(params.initialCapital, params.initialCapital, 0.0)
    val firstT = assets.minOfOrNull { it.candles.firstOrNull()?.t ?: Long.MAX_VALUE } ?: 0L
    if (firstT > 0 && firstT != Long.MAX_VALUE) equityCurve.add(EquityPoint(firstT, st.equity))
    val opens = ArrayList<OpenPos>()
    var seq = 0
    var maxOpenObs = 0
    var rejectedLimit = 0
    var evaluated = 0
    var sigRaw = 0
    var filtered = 0
    val passed = HashMap<String, Int>()
    val cnt = OpenCounters()
    var badPnl = 0
    val reasons = HashMap<String, Int>()
    // Timeline gabungan: timestamp → daftar (aset, indeksBar), deterministik.
    val timeline = java.util.TreeMap<Long, ArrayList<Pair<LoopAsset, Int>>>()
    for (a in assets) {
        for ((k, c) in a.candles.withIndex()) {
            timeline.getOrPut(c.t) { ArrayList() }.add(a to k)
        }
    }
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
    var evalTo = endT
    for ((t, jobs) in timeline) {
        if (isCancelled()) {
            d = d.copy(evaluatedBars = evaluated, signalsRaw = sigRaw, filteredOut = filtered,
                maxOpenTrades = cap, maxOpenObserved = maxOpenObs, rejectedByLimit = rejectedLimit,
                skippedNoLevel = cnt.noLevel, skippedBadEntry = cnt.badEntry,
                skippedBadQty = cnt.badQty, skippedBadPnl = badPnl,
                filterReasons = reasons, filterPassed = passed,
                runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
            return errorResult(params, "Dibatalkan oleh pengguna pada bar $evaluated/$totalSlots " +
                "(${trades.size} kaki tercatat sebelum batal — bukan hasil lengkap).", d) to d
        }
        for ((a, k) in jobs) {
            val b = a.candles[k]
            // 1) Exit posisi jatuh tempo aset ini (urut seq).
            var closedHere = false
            val due = opens.filter { it.assetTag == a.sym && it.entryIdx <= k }.sortedBy { it.seq }
            for (op in due) {
                val lf = exitStepPosition(params, a.candles, op, b, k) ?: continue
                if (applyLegPosition(params, a.candles, st, equityCurve, trades, op, lf)) {
                    op.remaining -= lf.qtyClosed
                    if (lf.kind.startsWith("TP")) op.leg++
                    if (op.remaining / op.qtyOrig <= 1e-9 || lf.kind == "SL" || lf.kind == "TRAIL" || lf.kind == "EXPIRED") {
                        opens.remove(op)
                        a.fctx.lastExit = k
                        closedHere = true
                    }
                } else {
                    badPnl++
                    opens.remove(op)
                    a.fctx.lastExit = k
                    closedHere = true
                }
            }
            // 2) Entry baru: bukan bar penutupan + ada bar berikut untuk entry.
            if (!closedHere && k >= a.startIdx && k < a.candles.size - 1) {
                evaluated++
                emitEval()
                evalTo = t
                val dec = decideAt(a.candles, k, a.cache, params)
                if (!dec.passed) continue
                if (opens.size >= cap) {
                    // Penuh: probe filter pada ctx SALINAN. Lolos = permintaan
                    // sah yang ditolak batas → rejectedByLimit (tak masuk signalsRaw).
                    val probeOk = if (activeFilters.isEmpty()) true
                    else applyFilters(a.candles, k, a.cache, dec.direction, activeFilters, a.fctx.copy()).passed
                    if (probeOk) rejectedLimit++
                } else {
                    sigRaw++
                    var ok = true
                    if (activeFilters.isNotEmpty()) {
                        val fr = applyFilters(a.candles, k, a.cache, dec.direction, activeFilters, a.fctx)
                        if (fr.passed) for (f in activeFilters) passed[f.name] = (passed[f.name] ?: 0) + 1
                        a.fctx.lastSigDir = dec.direction; a.fctx.lastSigIdx = k
                        if (!fr.passed) {
                            filtered++
                            for (w in fr.failed) {
                                val key = w.take(60); reasons[key] = (reasons[key] ?: 0) + 1
                            }
                            ok = false
                        }
                    }
                    if (ok) {
                        val op = tryOpenPosition(a.candles, k, params, a.cache, st.equity, dec, seq, a.sym, cnt)
                        if (op != null) {
                            opens.add(op); seq++
                            if (opens.size > maxOpenObs) maxOpenObs = opens.size
                        }
                    }
                }
            }
        }
    }
    emitEval(force = true)
    val skipped = maxOf(0, totalSlots - evaluated)
    d = d.copy(
        startIdx = diagStartIdx, evalFrom = startT,
        evaluatedBars = evaluated, skippedInPosition = skipped, signalsRaw = sigRaw,
        filteredOut = filtered, maxOpenTrades = cap, maxOpenObserved = maxOpenObs,
        rejectedByLimit = rejectedLimit,
        skippedNoLevel = cnt.noLevel, skippedBadEntry = cnt.badEntry,
        skippedBadQty = cnt.badQty, skippedBadPnl = badPnl,
        filterReasons = reasons, filterPassed = passed,
        evalTo = evalTo,
        runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis()
    )
    try { onStage?.invoke(EngineStage.MetricsDone) } catch (e: Exception) { /* abaikan */ }
    // pseudo-candles hanya untuk span tahun + denominator exposure buildResult.
    val span = assets.flatMap { a -> a.candles }.sortedBy { it.t }
    return buildResult(params, span, trades, equityCurve, st.maxDD, filtered, d) to d
}

// ---------- Portofolio multi-pair: cap GLOBAL lintas pair ----------

data class PortfolioAsset(val sym: String, val raw: List<Any?>)

data class PortfolioResult(
    val result: BacktestResult,
    val excluded: Map<String, String>,
    val included: List<String>
)

/**
 * Simulasi gabungan multi-pair dengan SATU ekuitas + SATU batas posisi
 * global (cap). Timeline digabung per timestamp (urut naik); event
 * bersamaan diproses deterministik sesuai urutan input [assets].
 * Perbedaan vs V41 (dokumentasi jujur): V41 menjalankan runBacktest
 * TERPISAH per pair (cap per-pair); implementasi ini menegakkan cap
 * GLOBAL lintas pair sesuai misi Tahap 4 — hasil agregat TIDAK identik
 * V41 bila cap < jumlah pair dan sinyal tumpang tindih.
 */
fun runPortfolioBacktest(
    assets: List<PortfolioAsset>,
    rawParams: BacktestParams,
    isCancelled: () -> Boolean = { false },
    onStage: ((EngineStage) -> Unit)? = null
): PortfolioResult {
    val params = sanitizeParams(rawParams)
    var diag = BacktestDiag(rawCount = assets.sumOf { it.raw.size })
    diag = diag.copy(cfgFp = configFingerprint(params))
    if (exceedsTwoYears(params.startDate, params.endDate)) {
        diag = diag.copy(runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
        return PortfolioResult(
            errorResult(params, "Rentang melebihi 2 tahun kalender. Pilih periode maksimal 2 tahun.", diag),
            assets.associate { it.sym to "rentang > 2 tahun" }, emptyList())
    }
    val list = ArrayList<LoopAsset>()
    val excluded = LinkedHashMap<String, String>()
    var normTotal = 0
    var dateTotal = 0
    var prefixTotal = 0
    for (a in assets) {
        if (isCancelled()) {
            diag = diag.copy(runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
            return PortfolioResult(
                errorResult(params, "Dibatalkan oleh pengguna — portofolio belum lengkap.", diag),
                excluded, list.map { it.sym })
        }
        val full = try {
            normalizeCandles(a.raw)
        } catch (e: Exception) {
            excluded[a.sym] = "Data ${a.sym} tidak valid: ${e.message}"
            continue
        }
        val (prefix, inRange) = splitWarmup(full, params.startDate, params.endDate)
        if (inRange.size < MIN_CANDLES) {
            excluded[a.sym] = "Butuh minimal $MIN_CANDLES candle (dapat ${inRange.size})."
            continue
        }
        val candles = prefix + inRange
        val startIdx = maxOf(prefix.size + WARMUP, 2)
        if (startIdx > candles.size - 2) {
            excluded[a.sym] = "Data tidak cukup untuk evaluasi setelah pemanasan (rentang ${inRange.size} candle)."
            continue
        }
        normTotal += full.size
        dateTotal += inRange.size
        prefixTotal += prefix.size
        list.add(LoopAsset(a.sym, candles, buildCache(candles), startIdx))
    }
    diag = diag.copy(normalizedCount = normTotal, dateFilteredCount = dateTotal, warmupPrefix = prefixTotal)
    if (list.isEmpty()) {
        diag = diag.copy(runId = "r${System.currentTimeMillis()}", finishedAt = System.currentTimeMillis())
        val why = excluded.values.firstOrNull() ?: "tidak ada pair memenuhi syarat data."
        return PortfolioResult(errorResult(params, "Semua pair gagal: $why", diag), excluded, emptyList())
    }
    try { onStage?.invoke(EngineStage.Validated(normTotal, dateTotal)) } catch (e: Exception) { /* abaikan */ }
    try { onStage?.invoke(EngineStage.IndicatorsDone) } catch (e: Exception) { /* abaikan */ }
    val cap = params.maxOpenTrades.coerceIn(MAX_OPEN_TRADES_MIN, MAX_OPEN_TRADES_MAX)
    val totalSlots = list.sumOf { (it.candles.size - 1) - it.startIdx }
    val startT = list.minOf { it.candles[it.startIdx].t }
    val endT = list.maxOf { it.candles[it.candles.size - 2].t }
    val (res, _) = runLoopMulti(list, params, cap, totalSlots, isCancelled, onStage, diag,
        startT, endT, diagStartIdx = list.minOf { it.startIdx })
    return PortfolioResult(res, excluded, list.map { it.sym })
}

fun buildResult(params: BacktestParams, candles: List<Candle>, trades: List<Trade>, equityCurve: List<EquityPoint>, maxDD: Double, filtered: Int = 0, diag: BacktestDiag = BacktestDiag()): BacktestResult {
    // V30: kategorisasi JUJUR berbasis laba bersih per kaki (bukan label pemicu).
    // Untung = pnl > 0; Rugi = pnl < 0; Impas = pnl tepat 0. Kaki EXPIRED ikut
    // dihitung sesuai tandanya (bukan dibuang dari pembilang). Label pemicu
    // WIN/LOSS/EXPIRED tetap tersimpan per kaki di Trade.result; hitungan
    // `expired` = jumlah kaki berpemicu EXPIRED (info, bukan kategori laba).
    // Total Transaksi = jumlah kaki tertutup (satu kaki = satu baris ledger).
    val wins = trades.filter { it.pnl > 0 }
    val losses = trades.filter { it.pnl < 0 }
    val be = trades.filter { it.pnl == 0.0 }
    val expired = trades.filter { it.result == "EXPIRED" }
    val total = trades.size
    val grossProfit = wins.sumOf { it.pnl }
    val grossLossAbs = abs(losses.sumOf { it.pnl })
    val netProfit = trades.sumOf { it.pnl }
    val finalCapital = params.initialCapital + netProfit
    val winRate = if (total > 0) wins.size.toDouble() / total * 100 else 0.0
    // V30: tanpa kerugian → +∞ (UI: "∞ · tak terdefinisi"), bukan 999.
    val profitFactor = if (grossLossAbs > 0) grossProfit / grossLossAbs else if (grossProfit > 0) Double.POSITIVE_INFINITY else 0.0
    val expectancy = if (total > 0) netProfit / total else 0.0
    val averageWin = if (wins.isNotEmpty()) grossProfit / wins.size else 0.0
    val averageLoss = if (losses.isNotEmpty()) -grossLossAbs / losses.size else 0.0
    val averageR = if (total > 0) trades.sumOf { it.rMultiple } / total else 0.0
    var lw = 0; var ll = 0; var cw = 0; var cl = 0
    for (t in trades) {
        // V30: beruntun mengikuti kategori laba yang sama (pnl), bukan label.
        if (t.pnl > 0) { cw++; cl = 0; lw = maxOf(lw, cw) } else if (t.pnl < 0) { cl++; cw = 0; ll = maxOf(ll, cl) } else { cw = 0; cl = 0 }
    }
    val rets = trades.map { it.pnl / params.initialCapital }
    val sharpe = sharpeRatio(rets)
    val sortino = sortinoRatio(rets)
    val maxDDPct = maxDD * 100
    val years = if (candles.size > 1) maxOf((candles.last().t - candles.first().t).toDouble() / (365.25 * 86400000), 1 / 365.25) else 1 / 365.25
    val cagr = if (params.initialCapital > 0 && finalCapital > 0) (Math.pow(finalCapital / params.initialCapital, 1 / years) - 1) * 100 else 0.0
    val calmar = if (maxDD > 0) cagr / (maxDDPct.takeIf { it != 0.0 } ?: 1.0) else if (cagr > 0) Double.POSITIVE_INFINITY else Double.NaN
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
        breakeven = be.size,
        winRate = winRate, lossRate = if (total > 0) losses.size.toDouble() / total * 100 else 0.0,
        grossProfit = grossProfit, grossLoss = -grossLossAbs, netProfit = netProfit,
        netProfitPercent = if (params.initialCapital != 0.0) netProfit / params.initialCapital * 100 else 0.0,
        profitFactor = profitFactor,
        expectancy = expectancy, averageWin = averageWin, averageLoss = averageLoss, averageR = averageR,
        averageRR = if (rrs.isNotEmpty()) rrs.sum() / rrs.size else 0.0,
        maxDrawdown = params.initialCapital * maxDD, maxDrawdownPercent = maxDDPct,
        sharpe = sharpe, sortino = sortino, calmar = calmar,
        cagr = finite(cagr), exposure = finite(exposure),
        avgHolding = if (total > 0) totalHolding.toDouble() / total else 0.0,
        longestWinStreak = lw, longestLossStreak = ll, filtered = filtered,
        trades = trades, equityCurve = equityCurve, diag = diag
    )
}

private fun finite(v: Double): Double = if (v.isFinite()) v else 0.0

// V30: Sharpe per-transaksi yang jujur = mean/sd (tanpa risk-free,
// tanpa annualisasi — dinyatakan di UI). Tanpa faktor √N: faktor itu membuat
// skor tumbuh bersama jumlah sampel (statistik-t, bukan Sharpe).
fun sharpeRatio(rets: List<Double>): Double {
    if (rets.size < 2) return Double.NaN
    val m = rets.sum() / rets.size
    val sd = sqrt(rets.sumOf { (it - m) * (it - m) } / (rets.size - 1))
    return if (sd == 0.0) {
        if (m > 0) Double.POSITIVE_INFINITY else if (m < 0) Double.NEGATIVE_INFINITY else Double.NaN
    } else m / sd
}

// V30: Sortino per-transaksi = mean/downside-sd. Tanpa downside & mean > 0 →
// +∞ (bukan 99); tanpa downside & mean ≤ 0 → 0.
fun sortinoRatio(rets: List<Double>): Double {
    if (rets.size < 2) return Double.NaN
    val m = rets.sum() / rets.size
    val dn = rets.filter { it < 0 }
    if (dn.isEmpty()) return if (m > 0) Double.POSITIVE_INFINITY else if (m < 0) Double.NEGATIVE_INFINITY else Double.NaN
    val dsd = sqrt(dn.sumOf { it * it } / dn.size)
    return if (dsd == 0.0) Double.NaN else m / dsd
}

// V30: Faktor Pemulihan (Recovery Factor) = laba bersih ÷ maxDD nominal.
// +∞ bila DD nol & laba positif; NaN bila keduanya nol (UI: "—").
fun recoveryFactor(netProfit: Double, maxDDMoney: Double): Double = when {
    maxDDMoney > 0 -> netProfit / maxDDMoney
    netProfit > 0 -> Double.POSITIVE_INFINITY
    else -> Double.NaN
}

/**
 * V30: rekonsiliasi kartu Hasil terhadap catatan transaksi + kurva.
 * Menghitung ulang setiap metrik dari sumbernya dan mengembalikan daftar
 * selisih (kosong = OK). Toleransi relatif 1e-6 untuk nilai uang.
 */
fun reconcileResult(r: BacktestResult): List<String> {
    val out = ArrayList<String>()
    if (r.error != null) return out // hasil gagal tak berekonsiliasi
    val t = r.trades
    fun close(a: Double, b: Double): Boolean {
        if (a == b) return true
        if (!a.isFinite() || !b.isFinite()) return a == b
        return abs(a - b) <= 1e-6 * maxOf(1.0, abs(a), abs(b))
    }
    val w = t.count { it.pnl > 0 }; val l = t.count { it.pnl < 0 }; val b = t.count { it.pnl == 0.0 }
    if (w != r.wins || l != r.losses || b != r.breakeven)
        out.add("cacah untung/rugi/impas ${r.wins}/${r.losses}/${r.breakeven} vs hitung-ulang $w/$l/$b")
    if (w + l + b != r.totalTrades)
        out.add("rekonsiliasi cacah: $w+$l+$b ≠ total ${r.totalTrades}")
    val gp = t.filter { it.pnl > 0 }.sumOf { it.pnl }
    val gl = abs(t.filter { it.pnl < 0 }.sumOf { it.pnl })
    if (!close(gp, r.grossProfit)) out.add("laba kotor ${r.grossProfit} vs $gp")
    if (!close(-gl, r.grossLoss)) out.add("rugi kotor ${r.grossLoss} vs ${-gl}")
    val net = t.sumOf { it.pnl }
    if (!close(net, r.netProfit)) out.add("laba bersih ${r.netProfit} vs $net")
    if (!close(gp - gl, net)) out.add("internal: kotor-bersih tak konsisten")
    if (!close(r.initialCapital + net, r.finalCapital))
        out.add("ekuitas akhir ${r.finalCapital} vs modal+net ${r.initialCapital + net}")
    val wr = if (t.isNotEmpty()) w.toDouble() / t.size * 100 else 0.0
    if (!close(wr, r.winRate)) out.add("win rate ${r.winRate} vs $wr")
    val pf = if (gl > 0) gp / gl else if (gp > 0) Double.POSITIVE_INFINITY else 0.0
    if (pf.isInfinite() != r.profitFactor.isInfinite() || (pf.isFinite() && !close(pf, r.profitFactor)))
        out.add("PF ${r.profitFactor} vs $pf")
    // MaxDD dihitung ulang dari kurva (puncak→lembah berurutan waktu).
    var peak = Double.NEGATIVE_INFINITY; var dd = 0.0
    for (p in r.equityCurve) {
        if (!p.equity.isFinite()) continue
        if (p.equity > peak) peak = p.equity
        if (peak > 0) dd = maxOf(dd, (peak - p.equity) / peak)
    }
    if (!close(dd * 100, r.maxDrawdownPercent)) out.add("maxDD% ${r.maxDrawdownPercent} vs ${dd * 100}")
    if (!close(r.initialCapital * dd, r.maxDrawdown)) out.add("maxDD nominal ${r.maxDrawdown} vs ${r.initialCapital * dd}")
    val lastEq = r.equityCurve.filter { it.equity.isFinite() }.lastOrNull()?.equity
    if (lastEq != null && !close(lastEq, r.finalCapital))
        out.add("ujung kurva $lastEq vs ekuitas akhir ${r.finalCapital}")
    return out
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
