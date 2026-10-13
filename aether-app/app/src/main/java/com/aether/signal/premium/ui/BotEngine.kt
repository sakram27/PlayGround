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
    // V31 OBSERVASI trailing (bukan ekonomi): kunci posisi yang sudah
    // diberitakan aktif + stop terakhir (memory sesi; sentuh = Hit via onSignal).
    private val trailActiveNotified = HashSet<String>()
    private val trailLastStop = HashMap<String, Double>()
    private fun forgetTrailNotify(key: String) {
        trailActiveNotified.remove(key)
        trailLastStop.remove(key)
    }
    /** Telemetri jujur per tick (E): berapa pair berhasil diproses. Bukan logika sinyal. */
    @Volatile var lastOkPairs: Int = 0
    @Volatile var lastTotalPairs: Int = 0
    /** True bila tick digerakkan MonitorService (hindari 2 scheduler ganda). */
    var externallyDriven: Boolean = false
        private set

    /** Sesi beku Tahap 3: snapshot konfigurasi saat startup. Tick memakai ini,
     *  bukan global live — suntingan layar lain saat Engine berjalan tidak
     *  mengubah parameter aktif sampai Engine dihentikan. */
    var activeSession: EngineSession? = null
        private set

    /** Bekukan sesi dari global saat ini. Melempar bila konfigurasi invalid. */
    fun freezeSessionNow(): EngineSession {
        if (App.engPairs.isEmpty()) throw IllegalArgumentException("Pilih pair dulu.")
        val s = freezeSession(App.captureGlobals(), App.engPairs.toList(), System.currentTimeMillis())
        activeSession = s
        EngineDiag.sessionCfgFp = s.fp
        EngineDiag.sessionPairs = s.pairs
        EngineDiag.sessionStrategy = s.params.strategy
        EngineDiag.sessionTimeframe = s.params.timeframe
        return s
    }

    /** Pastikan ada sesi aktif (dipakai jalur MonitorService). */
    fun ensureSession(): EngineSession = activeSession ?: freezeSessionNow()

    fun clearSession() {
        activeSession = null
        EngineDiag.sessionCfgFp = ""
        EngineDiag.sessionPairs = emptyList()
        EngineDiag.sessionStrategy = ""
        EngineDiag.sessionTimeframe = ""
        EngineDiag.cfgDiverged = false
        EngineDiag.liveCfgFp = ""
    }

    fun setExternallyDriven(v: Boolean) { externallyDriven = v }

    @Volatile private var busy = false

    @Synchronized
    fun start(): String {
        if (App.engRunning || busy) return "Sudah berjalan — hentikan dulu bila ingin ganti konfigurasi."
        if (App.engPairs.isEmpty()) return "Pilih pair dulu."
        val sess: EngineSession
        try {
            sess = freezeSessionNow()
        } catch (e: Exception) {
            return "Config error: ${e.message}"
        }
        busy = true
        try {
            if (equity == null) equity = try { App.buildParams(App.engPairs.first()).initialCapital } catch (e: Exception) { 1000.0 }
            try {
                val live = positions.map { positionKey(it.pair, it.dir, it.entryT) }.toHashSet()
                val all = App.trailAll()
                if (all.keys.removeAll { !live.contains(it) }) App.saveTrail()
            } catch (e: Exception) { /* abaikan */ }
            App.engRunning = true
            EngineDiag.startedAt = System.currentTimeMillis()
            if (MonitorService.running || externallyDriven) {
                // Selalu Siaga sudah memantau: jangan buat scheduler kedua.
                exec.execute {
                    try { tick(true); onTick?.invoke() } catch (e: Exception) {
                        EngineDiag.lastError = (e.message ?: "gagal").take(160)
                    }
                }
                return "Dipantau Selalu Siaga."
            }
            exec.execute {
                try { tick(true); onTick?.invoke() } catch (e: Exception) {
                    EngineDiag.lastError = (e.message ?: "gagal").take(160)
                }
            }
            sched?.cancel(false)
            // V32: tugas terjadwal tak boleh mati diam-diam bila melempar.
            sched = exec.scheduleAtFixedRate({
                try {
                    if (App.engRunning) { tick(false); onTick?.invoke() }
                } catch (e: Exception) {
                    EngineDiag.lastError = (e.message ?: "gagal").take(160)
                    EngineDiag.lastPhase = "error scheduler: ${(e.message ?: "gagal").take(80)}"
                }
            }, 90, 90, TimeUnit.SECONDS)
            return "Jalan."
        } finally {
            busy = false
        }
    }

    @Synchronized
    fun stop(): String {
        if (!App.engRunning || busy) return "Sudah berhenti."
        busy = true
        try {
            App.engRunning = false
            sched?.cancel(false)
            clearSession()
            return "Dihentikan."
        } finally {
            busy = false
        }
    }

    /** Ringkasan konfigurasi efektif sesi aktif untuk pesan UI (Tahap 3.8). */
    fun sessionSummary(): String {
        val s = activeSession ?: return "tanpa sesi"
        return "${s.params.strategy} · ${s.params.timeframe} · ${s.pairs.size} pair · fp ${s.fp.take(12)}"
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
        // V32: tick tak boleh membunuh scheduler — exception di luar
        // try-per-pair dicatat di sini, loop tetap hidup. (scheduleAtFixedRate
        // berhenti diam-diam bila tugas melempar.)
        try {
        // Tahap 3: parameter + pairs + provider berasal dari SESI BEKU startup,
        // bukan global live. Suntingan layar lain saat berjalan tak mengubah
        // parameter aktif; divergensi dicatat di EngineDiag.
        val sess: EngineSession
        try {
            sess = ensureSession()
        } catch (e: Exception) {
            lastScan = "Dry run error: ${e.message}"
            return
        }
        val p: BacktestParams = sess.params
        if (equity == null) equity = p.initialCapital
        val tfMs = try { parseTimeframe(p.timeframe) * 60000L } catch (e: Exception) { 900000L }
        var ok = 0
        // V28: hanya pair yang datanya valid DAN evaluasinya selesai tanpa
        // exception yang dihitung OK (dipanggil di titik keluar normal).
        fun markPairOk() { ok++; EngineDiag.evalOk++ }
        val syms = sess.pairs.toList()
        lastTotalPairs = syms.size
        // V26: telemetri aktual per tick (dibaca panel diagnostik).
        EngineDiag.beginTick()
        // V36 + Tahap 3: sidik sesi (dipakai Engine) vs sidik global live.
        // Sama = konsisten; beda = pengguna menyunting saat Engine berjalan
        // (sesi beku tetap dipakai sampai Engine dihentikan).
        try { EngineDiag.lastCfgFp = sess.fp } catch (e: Exception) { /* abaikan */ }
        try {
            val live = liveFingerprint(App.captureGlobals())
            EngineDiag.liveCfgFp = live
            EngineDiag.cfgDiverged = live.isNotEmpty() && live != sess.fp
        } catch (e: Exception) { /* abaikan */ }
        EngineDiag.lastPhase = "mengambil data ${syms.size} pair"
        for (sym in syms) {
            EngineDiag.evalTotal++
            try {
                // V28: minta ENGINE_CANDLES (300), bukan 200. Akar bug "belum siap":
                // meminta tepat 200 membuat e200[idx-20] selalu NaN (EMA menyemai
                // pada indeks 199), sehingga indikator tak pernah valid walau data
                // sempurna. Lihat REGIME_MIN_CANDLES=220 di AblationRegime.kt.
                val fr = getCandles(sess.provider, sym, p.timeframe, ENGINE_CANDLES)
                val raw = fr.candles
                // V32: asal data (live vs cache) dicatat — bukan diklaim live.
                val srcName = try { fr.meta.cacheUsed } catch (e: Exception) { "" }
                val candles = normalizeCandles(raw.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
                if (candles.isEmpty()) {
                    EngineDiag.pairError(sym, "data kosong")
                    EngineDiag.noteVerdict(sym, "INSUFFICIENT_DATA", "data kosong")
                    EngineDiag.lastError = "data kosong ($sym)"
                    continue
                }
                val cache = buildCache(candles)
                val last = candles.last()
                if (last.t > EngineDiag.lastDataAt) EngineDiag.lastDataAt = last.t
                // V28: "OK" = data valid diterima DAN evaluasi selesai tanpa
                // exception. Dihitung di titik keluar normal (bawah try), bukan di
                // sini — pair yang crash saat evaluasi tak boleh dihitung OK.
                // V16 F7 + V27: regime dari cache yang SUDAH diambil (tanpa request
                // tambahan) + diagnostik aktual per pair (bukan label palsu).
                try {
                    val idx = candles.size - 1
                    val atr = cache.atr14[idx]
                    val atrPct = if (atr.isFinite() && atr > 0 && last.c > 0) atr / last.c * 100 else null
                    val indOk = cache.e200[idx].isFinite() &&
                        (idx - REGIME_LOOKBACK >= 0 && cache.e200[idx - REGIME_LOOKBACK].isFinite()) &&
                        cache.adx14[idx].isFinite()
                    if (indOk) {
                        classifyRegime(cache.e200, cache.adx14, atrPct, idx)?.let { App.regime[sym] = it }
                    }
                    App.regimeDiag[sym] = RegimeDiag(
                        requested = ENGINE_CANDLES, received = raw.size, valid = candles.size,
                        provider = sess.provider, timeframe = p.timeframe,
                        updatedAt = System.currentTimeMillis(), error = "", indicatorOk = indOk,
                        source = srcName)
                } catch (e: Exception) {
                    App.regimeDiag[sym] = RegimeDiag(
                        requested = ENGINE_CANDLES, received = raw.size, valid = candles.size,
                        provider = sess.provider, timeframe = p.timeframe,
                        updatedAt = System.currentTimeMillis(),
                        error = (e.message ?: "gagal").take(120), indicatorOk = false,
                        source = srcName)
                }
                val open = positions.find { it.pair == sym }
                if (open != null) {
                    EngineDiag.noteVerdict(sym, "BLOCKED_POSITION", open.dir)
                    // V32: posisi terbuka menahan entry baru pair ini (desain,
                    // bukan bug) — dihitung agar panel jujur.
                    EngineDiag.blockedPosition++
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
                                    // V31 OBSERVASI (ekonomi tak berubah): "Aktif" =
                                    // stop pertama kali mengunci di atas/di bawah SL
                                    // awal; "Bergerak" = stop berubah ke arah untung.
                                    // Keduanya peristiwa nyata tick ini.
                                    val locked = if (open.dir == "LONG") cl > open.sl else cl < open.sl
                                    if (locked && trailActiveNotified.add(key)) {
                                        try {
                                            NotifBus.trailEvent("trail_on", "ton|$key", sym, p.timeframe, open.dir, cl,
                                                "Trailing Stop Aktif",
                                                "$sym ${open.dir} — Trailing Stop aktif pada harga ${App.fmt(cl, 4)}. " +
                                                    "Harga acuan ${if (open.dir == "LONG") "tertinggi" else "terendah"}: ${App.fmt(ne, 4)}. " +
                                                    "Jarak trailing: ${App.fmt(App.trailLockPct, 0)}%.")
                                        } catch (e: Exception) { /* notifikasi tak boleh matikan tick */ }
                                    }
                                    val prevStop = trailLastStop[key]
                                    if (locked && prevStop != null && prevStop != cl) {
                                        try {
                                            NotifBus.trailEvent("trail_move", "tmv|$key", sym, p.timeframe, open.dir, cl,
                                                "Trailing Stop Diperbarui",
                                                "$sym ${open.dir} — Harga trailing stop bergerak dari ${App.fmt(prevStop, 4)} ke ${App.fmt(cl, 4)}.",
                                                dedupe = false)
                                        } catch (e: Exception) { /* abaikan */ }
                                    }
                                    if (locked) trailLastStop[key] = cl
                                    val touchPx = if (open.dir == "LONG") b.l else b.h
                                    if (trailTouched(open.dir, touchPx, cl)) {
                                        // Keluar pada level stop (harga stop, bukan fill
                                        // terbukti — dinyatakan di UI). Rumus pnl =
                                        // rumus exit mesin yang sama.
                                        // V36: slippage ± sisi seperti backtest
                                        // (dulu tanpa slippage → tak sebanding).
                                        val exit = trailExitWithSlip(open.dir, cl, p.slippagePercent)
                                        val gross = (if (open.dir == "LONG") exit - open.entry else open.entry - exit) * open.qty
                                        val fees = (open.entry * open.qty + exit * open.qty) * p.feePercent
                                        val pnl = gross - fees
                                        equity = (equity ?: p.initialCapital) + pnl
                                        positions.remove(open)
                                        closed.add(0, ClosedPos(sym, open.dir, open.entry, exit, pnl, paperResult(pnl), open.entryT, b.t))
                                        if (closed.size > 300) { closed.subList(300, closed.size).clear() }
                                        val fx2 = fctx[sym] ?: FilterCtx()
                                        fx2.lastExitT = b.t; fx2.tfMs = tfMs
                                        fctx[sym] = fx2
                                        App.clearTrail(key)
                                        forgetTrailNotify(key)
                                        // src khusus: trailing paper NYATA — arsip +
                                        // notifikasi Hit via onSignal (cermin SL/TP).
                                        val tsig = Sig(sym, p.timeframe, open.dir, exit, open.sl, open.tp, b.t,
                                            "dryrun-trail", "t${b.t}${sym}",
                                            strategy = "", confidence = 0.0,
                                            reasons = listOf("trailing stop tersentuh @ ${App.fmt(cl, 4)}"),
                                            decidedAt = System.currentTimeMillis())
                                        if (App.signals.any { it.pair == tsig.pair && it.tf == tsig.tf && it.t == tsig.t && it.dir == tsig.dir }) {
                                            EngineDiag.dupHeld++
                                        } else {
                                            EngineDiag.noteStored()
                                        }
                                        App.pushSignal(tsig)
                                        try { NotifBus.onSignal(tsig) } catch (e: Exception) { /* notifikasi tak boleh matikan tick */ }
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
                        forgetTrailNotify(positionKey(sym, open.dir, open.entryT))
                        // V36: label berbasis laba bersih (dulu pemicu: TP selalu
                        // WIN walau fee/slippage membuatnya negatif).
                        closed.add(0, ClosedPos(sym, open.dir, open.entry, exit, pnl, paperResult(pnl), open.entryT, b.t))
                        if (closed.size > 300) { closed.subList(300, closed.size).clear() }
                        val fx = fctx[sym] ?: FilterCtx()
                        fx.lastExitT = b.t; fx.tfMs = tfMs
                        fctx[sym] = fx
                        val sig = Sig(sym, p.timeframe, open.dir, exit, open.sl, open.tp, b.t, "dryrun-" + if (win) "TP" else "SL", "d${b.t}${sym}")
                        if (App.signals.any { it.pair == sig.pair && it.tf == sig.tf && it.t == sig.t && it.dir == sig.dir }) {
                            EngineDiag.dupHeld++
                        } else {
                            EngineDiag.noteStored()
                        }
                        App.pushSignal(sig)
                        // Notifikasi SL/TP NYATA (level tersentuh per definisi mesin) + dedup.
                        EngineDiag.notifCalls++
                        try { NotifBus.onSignal(sig) } catch (e: Exception) { /* notifikasi tak boleh matikan tick */ }
                        break
                    }
                    // V26: kedaluwarsa cermin backtest (maxHolding). Tanpa ini posisi
                    // paper menggantung selamanya dan menahan entry baru untuk pair
                    // tersebut tanpa batas — akar "engine jalan tapi tak ada sinyal".
                    if (positions.contains(open) && p.maxHolding > 0) {
                        val xi = expiryIndex(open.entryT, candles.map { it.t }, p.maxHolding)
                        if (xi >= 0) {
                            val bx = candles[xi]
                            val exit = if (open.dir == "LONG") bx.c * (1 - p.slippagePercent)
                            else bx.c * (1 + p.slippagePercent)
                            val gross = (if (open.dir == "LONG") exit - open.entry else open.entry - exit) * open.qty
                            val fees = (open.entry * open.qty + exit * open.qty) * p.feePercent
                            val pnl = gross - fees
                            equity = (equity ?: p.initialCapital) + pnl
                            positions.remove(open)
                            closed.add(0, ClosedPos(sym, open.dir, open.entry, exit, pnl, "EXPIRED", open.entryT, bx.t))
                            if (closed.size > 300) { closed.subList(300, closed.size).clear() }
                            val fx3 = fctx[sym] ?: FilterCtx()
                            fx3.lastExitT = bx.t; fx3.tfMs = tfMs
                            fctx[sym] = fx3
                            App.clearTrail(positionKey(sym, open.dir, open.entryT))
                            forgetTrailNotify(positionKey(sym, open.dir, open.entryT))
                            // Arsip Engine tanpa notifikasi (seperti trail): keluar
                            // kedaluwarsa bukan TP/SL, jadi tidak dibunyikan.
                            val esig = Sig(sym, p.timeframe, open.dir, exit, open.sl, open.tp, bx.t,
                                "dryrun-expired", "x${bx.t}${sym}",
                                strategy = "", confidence = 0.0,
                                reasons = listOf("batas tahan ${p.maxHolding} bar tercapai"),
                                decidedAt = System.currentTimeMillis())
                            if (App.signals.any { it.pair == esig.pair && it.tf == esig.tf && it.t == esig.t && it.dir == esig.dir }) {
                                EngineDiag.dupHeld++
                            } else {
                                EngineDiag.noteStored()
                            }
                            App.pushSignal(esig)
                        }
                    }
                } else {
                    // V33: bar tertutup terakhir SELALU dievaluasi; bar berjalan
                    // HANYA bila plausibly tutup (umur >= interval TF). Cermin
                    // backtest yang tak pernah menilai bar berjalan (volume
                    // parsial → gagal filter volume sistematis + indikator
                    // tak stabil). Tanpa look-ahead.
                    val nowMs = System.currentTimeMillis()
                    val evalIdx = engineEvalIndices(candles.size, last.t, nowMs, tfMs)
                    for (i in evalIdx) {
                    if (positions.any { it.pair == sym }) break
                    EngineDiag.lastPhase = "evaluasi strategi ($sym)"
                    val dec = decideAt(candles, i, cache, p)
                    if (!dec.passed) {
                        EngineDiag.noteVerdict(sym, "NO_CANDIDATE", dec.strategy.ifEmpty { p.strategy })
                        continue
                    }
                    EngineDiag.candidates++
                    if (dec.direction == "LONG") EngineDiag.candLong++ else EngineDiag.candShort++
                    // Bar acuan = bar yang dievaluasi (waktu setup, bukan
                    // bar berjalan) — jendela cooldown/dup selaras backtest.
                    val sigBar = candles[i]
                    val fx = fctx[sym] ?: FilterCtx()
                    // V33 FIX (bug terbukti): cermin backtest — terapkan filter
                    // DULU dengan ctx lama, BARU catat sinyal ke ctx (berlaku
                    // untuk tick/bar berikut). Urutan lama (catat dulu) membuat
                    // filter dup memblokir DIRINYA SENDIRI (selisih 0 < 5 bar)
                    // sehingga SETIAP kandidat pertama per pair gagal dengan
                    // "duplikat diblok" bila filter dup aktif.
                    fx.tfMs = tfMs
                    fctx[sym] = fx
                    // V16 F1: jejak keputusan dari data aktual saat sinyal dibuat.
                    val activeIds = p.filters.filter { it.enabled }.map { it.name }
                    var failedIds: List<String> = emptyList()
                    if (p.filters.isNotEmpty()) {
                        val fr = applyFilters(candles, i, cache, dec.direction, p.filters, fx)
                        failedIds = fr.failed.mapNotNull { filterIdForReasonKey(it) }.distinct()
                        fx.lastSigT = sigBar.t; fx.lastSigDirT = dec.direction
                        if (!fr.passed) {
                            EngineDiag.filteredOut++
                            for (w in fr.failed) EngineDiag.noteFilterReject(w)
                            EngineDiag.noteVerdict(sym, "FILTERED", fr.failed.firstOrNull()?.take(60) ?: "")
                            continue
                        }
                    }
                    var entry = last.c
                    entry = if (dec.direction == "LONG") entry * (1 + p.slippagePercent) else entry * (1 - p.slippagePercent)
                    val (sl, tp) = levels(entry, dec.direction, cache, i, p)
                    if (!sl.isFinite() || !tp.isFinite() || sl <= 0 || tp <= 0) { EngineDiag.invalidLevels++; EngineDiag.noteVerdict(sym, "INVALID_LEVEL", "sl/tp"); continue }
                    val riskDist = Math.abs(entry - sl)
                    if (!(riskDist > 0)) { EngineDiag.invalidLevels++; EngineDiag.noteVerdict(sym, "INVALID_LEVEL", "riskDist"); continue }
                    var qty = ((equity ?: p.initialCapital) * p.riskPerTrade) / riskDist
                    val maxNot = (equity ?: p.initialCapital) * p.leverage
                    if (qty * entry > maxNot) qty = maxNot / entry
                    if (!(qty > 0)) { EngineDiag.invalidLevels++; EngineDiag.noteVerdict(sym, "INVALID_LEVEL", "qty"); continue }
                    positions.add(PaperPos(sym, dec.direction, entry, sigBar.t, sl, tp, qty, last.c, 0.0))
                    // V16 F2: skor dari komponen yang tersedia saat ini (parsial eksplisit).
                    val passedIds = activeIds.filter { !failedIds.contains(it) }
                    val slPctV = if (entry > 0) Math.abs(entry - sl) / entry * 100 else Double.NaN
                    val tpPctV = if (entry > 0) Math.abs(entry - tp) / entry * 100 else Double.NaN
                    val sc = signalScore(passedIds.size, activeIds.size, dec.confidence,
                        slPctV.takeIf { it.isFinite() }, tpPctV.takeIf { it.isFinite() })
                    val sig = Sig(sym, p.timeframe, dec.direction, entry, sl, tp, sigBar.t, "dryrun-entry", "e${sigBar.t}${sym}",
                        strategy = dec.strategy, confidence = dec.confidence,
                        reasons = cleanStrList(dec.reasons),
                        passedFilters = passedIds, failedFilters = failedIds,
                        decidedAt = System.currentTimeMillis(),
                        score = sc?.total ?: -1.0,
                        scoreDetail = (if (sc != null) "${scoreExplain(passedIds.size, activeIds.size)} · ${sc.coverage}" else "") +
                            (try {
                                val fp = EngineDiag.lastCfgFp
                                if (fp.isNotEmpty()) " · cfg ${fp.take(16)}" else ""
                            } catch (e: Exception) { "" }))
                    if (App.signals.any { it.pair == sig.pair && it.tf == sig.tf && it.t == sig.t && it.dir == sig.dir }) {
                        EngineDiag.dupHeld++
                    } else {
                        EngineDiag.noteStored()
                    }
                    App.pushSignal(sig)
                    EngineDiag.noteVerdict(sym, "SIGNAL_CREATED", dec.direction)
                    // Notifikasi entry NYATA (tepat setelah mesin valid) + dedup.
                    EngineDiag.notifCalls++
                    try { NotifBus.onSignal(sig) } catch (e: Exception) { /* notifikasi tak boleh matikan tick */ }
                    }
                }
                // V28: sampai sini = data valid + evaluasi selesai tanpa exception.
                markPairOk()
            } catch (e: Exception) {
                // V26: kegagalan per-pair dicatat (bukan ditelan diam-diam).
                EngineDiag.pairError(sym, e.message ?: "gagal")
                EngineDiag.lastError = "${sym}: ${e.message ?: "gagal"}".take(160)
                App.regimeDiag[sym] = RegimeDiag(
                    requested = ENGINE_CANDLES, provider = sess.provider, timeframe = p.timeframe,
                    updatedAt = System.currentTimeMillis(),
                    error = (e.message ?: "gagal").take(120), indicatorOk = false,
                    source = "")
                /* lanjut pair berikut */
            }
        }
        EngineDiag.lastTickAt = System.currentTimeMillis()
        EngineDiag.lastPhase = if (EngineDiag.evalOk > 0) "selesai" else "selesai (tanpa data valid)"
        lastOkPairs = ok
        try { App.saveRegime() } catch (e: Exception) { /* abaikan */ }
        try { App.saveRegimeDiag() } catch (e: Exception) { /* abaikan */ }
        lastScan = "Jalan · equity $${App.fmt(equity ?: 0.0)} · ${java.util.Date()}"
        } catch (e: Exception) {
            EngineDiag.lastError = (e.message ?: "gagal").take(160)
            EngineDiag.lastPhase = "error: ${(e.message ?: "gagal").take(80)}"
            EngineDiag.lastTickAt = System.currentTimeMillis()
            lastScan = "Error tick: ${e.message}"
        }
    }

    fun netClosed(): Double = closed.sumOf { it.pnl }
}
