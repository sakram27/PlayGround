package com.aether.signal.premium.ui

import com.aether.signal.premium.ai.Sig
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.engine.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Port bot engine terpadu app.js (Start Bot + Dry Run): paper trading per pair tiap interval.

data class PaperPos(
    val pair: String, val dir: String, val entry: Double, val entryT: Long,
    val sl: Double, val tp: Double, val qty: Double, var mark: Double, var upl: Double
)

data class ClosedPos(
    val pair: String, val dir: String, val entry: Double, val exit: Double,
    val pnl: Double, val result: String, val entryT: Long, val exitT: Long
)

object BotEngine {
    val positions: ArrayList<PaperPos> = ArrayList()
    val closed: ArrayList<ClosedPos> = ArrayList()
    var equity: Double? = null
    var lastScan: String = ""
    private var sched: ScheduledFuture<*>? = null
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val fctx = HashMap<String, FilterCtx>()
    var onTick: (() -> Unit)? = null
    /** Telemetri jujur per tick (E): berapa pair berhasil diproses. Bukan logika sinyal. */
    @Volatile var lastOkPairs: Int = 0
    @Volatile var lastTotalPairs: Int = 0
    /** True bila tick digerakkan MonitorService (hindari 2 scheduler ganda). */
    var externallyDriven: Boolean = false
        private set

    fun setExternallyDriven(v: Boolean) { externallyDriven = v }

    fun start(): String {
        if (App.engPairs.isEmpty()) return "Pilih pair dulu."
        try { App.buildParams(App.engPairs.first()) } catch (e: Exception) { return "Config error: ${e.message}" }
        if (equity == null) equity = try { App.buildParams(App.engPairs.first()).initialCapital } catch (e: Exception) { 1000.0 }
        // V16 F10: bersihkan state trailing yatim (posisi hilang saat restart).
        try {
            val live = positions.map { positionKey(it.pair, it.dir, it.entryT) }.toHashSet()
            val all = App.trailAll()
            if (all.keys.removeAll { !live.contains(it) }) App.saveTrail()
        } catch (e: Exception) { /* abaikan */ }
        App.engRunning = true
        if (MonitorService.running || externallyDriven) {
            // Selalu Siaga sudah memantau: jangan buat scheduler kedua.
            exec.execute { tick(true); onTick?.invoke() }
            return "Dipantau Selalu Siaga."
        }
        exec.execute { tick(true); onTick?.invoke() }
        sched?.cancel(false)
        sched = exec.scheduleAtFixedRate({ if (App.engRunning) { tick(false); onTick?.invoke() } }, 90, 90, TimeUnit.SECONDS)
        return "Jalan."
    }

    fun stop() {
        App.engRunning = false
        sched?.cancel(false)
    }

    fun levels(entry: Double, dir: String, cache: Cache, idx: Int, p: BacktestParams): Pair<Double, Double> {
        if (p.useAtr && !cache.atr14[idx].isNaN() && cache.atr14[idx] > 0) {
            val a = cache.atr14[idx]
            return if (dir == "LONG") entry - a * p.atrSlMult to entry + a * p.atrTpMult
            else entry + a * p.atrTpMult to entry - a * p.atrTpMult
        }
        return if (dir == "LONG") entry * (1 - p.slPercent) to entry * (1 + p.tpPercent)
        else entry * (1 + p.slPercent) to entry * (1 - p.tpPercent)
    }

    fun tick(manual: Boolean) {
        if (!App.engRunning && !manual) return
        val p: BacktestParams
        try { p = App.buildParams(App.engPairs.firstOrNull() ?: return) } catch (e: Exception) { lastScan = "Dry run error: ${e.message}"; return }
        if (equity == null) equity = p.initialCapital
        val tfMs = try { parseTimeframe(p.timeframe) * 60000L } catch (e: Exception) { 900000L }
        var ok = 0
        val syms = App.engPairs.toList()
        lastTotalPairs = syms.size
        for (sym in syms) {
            try {
                val raw = getCandles(App.provider, sym, p.timeframe, 200).candles
                val candles = normalizeCandles(raw.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
                val cache = buildCache(candles)
                val last = candles.last()
                ok++
                // V16 F7: regime dari cache yang SUDAH diambil (tanpa request tambahan).
                try {
                    val idx = candles.size - 1
                    val atr = cache.atr14[idx]
                    val atrPct = if (atr.isFinite() && atr > 0 && last.c > 0) atr / last.c * 100 else null
                    classifyRegime(cache.e200, cache.adx14, atrPct, idx)?.let { App.regime[sym] = it }
                } catch (e: Exception) { /* label opsional, jangan matikan tick */ }
                val open = positions.find { it.pair == sym }
                if (open != null) {
                    open.mark = last.c
                    open.upl = (if (open.dir == "LONG") last.c - open.entry else open.entry - last.c) * open.qty
                    val fresh = candles.filter { it.t > open.entryT }
                    for (b in fresh) {
                        val slHit = if (open.dir == "LONG") b.l <= open.sl else b.h >= open.sl
                        val tpHit = if (open.dir == "LONG") b.h >= open.tp else b.l <= open.tp
                        if (!slHit && !tpHit) {
                            // V16 F10: trailing stop virtual — TERISOLASI dari mesin
                            // strategi/backtest. Level awal (SL) selalu diprioritaskan
                            // di atas (dicek dulu); trailing tak pernah melebarkan risiko.
                            if (App.trailOn) {
                                val key = positionKey(sym, open.dir, open.entryT)
                                val dist = trailDistance(open.entry, open.tp, App.trailLockPct)
                                if (dist != null) {
                                    val st = App.trailAll()[key]
                                        ?: TrailState(open.sl, open.entry)
                                    val markRef = if (open.dir == "LONG") b.h else b.l
                                    val (ns, ne) = ratchet(open.dir, st.stop, st.extreme, markRef, dist)
                                    val cl = if (open.dir == "LONG") maxOf(ns, open.sl) else minOf(ns, open.sl)
                                    App.trailAll()[key] = TrailState(cl, ne)
                                    App.saveTrail()
                                    val touchPx = if (open.dir == "LONG") b.l else b.h
                                    if (trailTouched(open.dir, touchPx, cl)) {
                                        // Keluar pada level stop (harga stop, bukan fill
                                        // terbukti — dinyatakan di UI). Rumus pnl =
                                        // rumus exit mesin yang sama.
                                        val exit = cl
                                        val gross = (if (open.dir == "LONG") exit - open.entry else open.entry - exit) * open.qty
                                        val fees = (open.entry * open.qty + exit * open.qty) * p.feePercent
                                        val pnl = gross - fees
                                        val win = if (open.dir == "LONG") exit > open.entry else exit < open.entry
                                        equity = (equity ?: p.initialCapital) + pnl
                                        positions.remove(open)
                                        closed.add(0, ClosedPos(sym, open.dir, open.entry, exit, pnl, if (win) "WIN" else "LOSS", open.entryT, b.t))
                                        if (closed.size > 300) { closed.subList(300, closed.size).clear() }
                                        val fx2 = fctx[sym] ?: FilterCtx()
                                        fx2.lastExitT = b.t; fx2.tfMs = tfMs
                                        fctx[sym] = fx2
                                        App.clearTrail(key)
                                        // src khusus: NotifBus mengabaikan (tanpa notifikasi),
                                        // tercatat di daftar sinyal + dapat dibuka di detail.
                                        val tsig = Sig(sym, p.timeframe, open.dir, exit, open.sl, open.tp, b.t,
                                            "dryrun-trail", "t${b.t}${sym}",
                                            strategy = "", confidence = 0.0,
                                            reasons = listOf("trailing stop tersentuh @ ${App.fmt(cl, 4)}"),
                                            decidedAt = System.currentTimeMillis())
                                        App.pushSignal(tsig)
                                        break
                                    }
                                }
                            }
                            continue
                        }
                        val win = tpHit && !slHit
                        val exit = if (win)
                            (if (open.dir == "LONG") open.tp * (1 - p.slippagePercent) else open.tp * (1 + p.slippagePercent))
                        else
                            (if (open.dir == "LONG") open.sl * (1 - p.slippagePercent) else open.sl * (1 + p.slippagePercent))
                        val gross = (if (open.dir == "LONG") exit - open.entry else open.entry - exit) * open.qty
                        val fees = (open.entry * open.qty + exit * open.qty) * p.feePercent
                        val pnl = gross - fees
                        equity = (equity ?: p.initialCapital) + pnl
                        positions.remove(open)
                        closed.add(0, ClosedPos(sym, open.dir, open.entry, exit, pnl, if (win) "WIN" else "LOSS", open.entryT, b.t))
                        if (closed.size > 300) { closed.subList(300, closed.size).clear() }
                        val fx = fctx[sym] ?: FilterCtx()
                        fx.lastExitT = b.t; fx.tfMs = tfMs
                        fctx[sym] = fx
                        val sig = Sig(sym, p.timeframe, open.dir, exit, open.sl, open.tp, b.t, "dryrun-" + if (win) "TP" else "SL", "d${b.t}${sym}")
                        App.pushSignal(sig)
                        // Notifikasi SL/TP NYATA (level tersentuh per definisi mesin) + dedup.
                        try { NotifBus.onSignal(sig) } catch (e: Exception) { /* notifikasi tak boleh matikan tick */ }
                        break
                    }
                } else {
                    val i = candles.size - 1
                    val dec = decideAt(candles, i, cache, p)
                    if (!dec.passed) continue
                    val fx = fctx[sym] ?: FilterCtx()
                    fx.lastSigT = last.t; fx.lastSigDirT = dec.direction; fx.tfMs = tfMs
                    fctx[sym] = fx
                    // V16 F1: jejak keputusan dari data aktual saat sinyal dibuat.
                    val activeIds = p.filters.filter { it.enabled }.map { it.name }
                    var failedIds: List<String> = emptyList()
                    if (p.filters.isNotEmpty()) {
                        val fr = applyFilters(candles, i, cache, dec.direction, p.filters, fx)
                        failedIds = fr.failed.mapNotNull { filterIdForReasonKey(it) }.distinct()
                        if (!fr.passed) continue
                    }
                    var entry = last.c
                    entry = if (dec.direction == "LONG") entry * (1 + p.slippagePercent) else entry * (1 - p.slippagePercent)
                    val (sl, tp) = levels(entry, dec.direction, cache, i, p)
                    if (!sl.isFinite() || !tp.isFinite() || sl <= 0 || tp <= 0) continue
                    val riskDist = Math.abs(entry - sl)
                    if (!(riskDist > 0)) continue
                    var qty = ((equity ?: p.initialCapital) * p.riskPerTrade) / riskDist
                    val maxNot = (equity ?: p.initialCapital) * p.leverage
                    if (qty * entry > maxNot) qty = maxNot / entry
                    if (!(qty > 0)) continue
                    positions.add(PaperPos(sym, dec.direction, entry, last.t, sl, tp, qty, last.c, 0.0))
                    // V16 F2: skor dari komponen yang tersedia saat ini (parsial eksplisit).
                    val passedIds = activeIds.filter { !failedIds.contains(it) }
                    val slPctV = if (entry > 0) Math.abs(entry - sl) / entry * 100 else Double.NaN
                    val tpPctV = if (entry > 0) Math.abs(entry - tp) / entry * 100 else Double.NaN
                    val sc = signalScore(passedIds.size, activeIds.size, dec.confidence,
                        slPctV.takeIf { it.isFinite() }, tpPctV.takeIf { it.isFinite() })
                    val sig = Sig(sym, p.timeframe, dec.direction, entry, sl, tp, last.t, "dryrun-entry", "e${last.t}${sym}",
                        strategy = dec.strategy, confidence = dec.confidence,
                        reasons = cleanStrList(dec.reasons),
                        passedFilters = passedIds, failedFilters = failedIds,
                        decidedAt = System.currentTimeMillis(),
                        score = sc?.total ?: -1.0,
                        scoreDetail = if (sc != null) "${scoreExplain(passedIds.size, activeIds.size)} · ${sc.coverage}" else "")
                    App.pushSignal(sig)
                    // Notifikasi entry NYATA (tepat setelah mesin valid) + dedup.
                    try { NotifBus.onSignal(sig) } catch (e: Exception) { /* notifikasi tak boleh matikan tick */ }
                }
            } catch (e: Exception) { /* lanjut pair berikut */ }
        }
        lastOkPairs = ok
        try { App.saveRegime() } catch (e: Exception) { /* abaikan */ }
        lastScan = "Jalan · equity $${App.fmt(equity ?: 0.0)} · ${java.util.Date()}"
    }

    fun netClosed(): Double = closed.sumOf { it.pnl }
}
