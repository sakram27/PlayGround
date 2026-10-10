package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.*

// FITUR 1 (V14): replay backtest per candle — MURNI & teruji JVM.
// Meniru loop runBacktest PERSIS langkah demi langkah (keputusan, filter, entry di
// open bar berikut, scan SL/TP, EXPIRED, ukuran posisi, ekuitas), sehingga replay
// menghasilkan transaksi & ekuitas yang IDENTIK dengan backtest normal.
// Tanpa lookahead: frame bar ke-i hanya memakai candle[0..i] (+ open i+1 sebagai
// harga entry, sama seperti mesin). Tak ada file engine yang diubah.

/** Posisi virtual yang sedang terbuka pada akhir suatu frame. */
data class ReplayOpen(
    val dir: String,
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val qty: Double,
    val riskAmount: Double,
    val entryIdx: Int,
    val lastJ: Int
)

/** Ringkasan indikator pada satu bar (NaN = belum siap → UI tampil "—"). */
data class ReplayInd(
    val close: Double,
    val ema50: Double,
    val rsi14: Double,
    val adx14: Double,
    val atr14: Double
)

/** Satu frame replay = keadaan SETELAH memproses bar [idx]. */
data class ReplayFrame(
    val idx: Int,
    val total: Int,
    val candle: Candle,
    val evaluated: Boolean,
    val signalDir: String?,
    val signalReasons: List<String>,
    val filterPassed: Boolean?,
    val filterFailed: List<String>,
    val pendingEntry: Boolean,
    val justOpened: ReplayOpen?,
    val closed: Trade?,
    val open: ReplayOpen?,
    val equity: Double
)

private data class PendingOpen(
    val dir: String, val entry: Double, val lv: RiskLevels,
    val qty: Double, val riskAmount: Double, val entryIdx: Int, val lastJ: Int,
    val dec: SignalDecision
)

/**
 * Sesi replay deterministik. Dibangun dari candle + parameter yang SAMA dengan
 * backtest normal (sanitize + normalisasi + filter tanggal + buildCache identik).
 * Maju ([step]), mundur ([back], via putar-ulang deterministik), [reset].
 * [step] mengembalikan null bila data habis / tak cukup.
 */
class ReplaySession(rawCandles: List<Candle>, rawParams: BacktestParams) {
    val params: BacktestParams = sanitizeParams(rawParams)
    val error: String?
    val candles: List<Candle>
    val startIdx: Int
    val totalSteps: Int

    private val cache: Cache?
    private val activeFilters: List<FilterCfg>
    private var fctx = FilterCtx(lastExit = -1000000000)
    private var cursor: Int
    private var equity: Double
    private var peak: Double
    private var maxDD: Double
    private var open: ReplayOpen? = null
    private var pending: PendingOpen? = null
    private val frames = ArrayList<ReplayFrame>()
    private val trades = ArrayList<Trade>()

    init {
        val maps = rawCandles.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        var cs = normalizeCandles(maps)
        // V17: persiapan data IDENTIK dengan runBacktest (prefix pemanasan + rentang).
        val (prefix, inRange) = splitWarmup(cs, params.startDate, params.endDate)
        cs = prefix + inRange
        candles = cs
        val sIdx = maxOf(prefix.size + WARMUP, 2)
        if (inRange.size < MIN_CANDLES) {
            error = "Butuh minimal $MIN_CANDLES candle (dapat ${inRange.size})."
            cache = null
            activeFilters = emptyList()
            startIdx = 0; totalSteps = 0; cursor = 0
            equity = params.initialCapital; peak = equity; maxDD = 0.0
        } else if (sIdx > cs.size - 2) {
            error = "Data tidak cukup untuk evaluasi setelah pemanasan."
            cache = null
            activeFilters = emptyList()
            startIdx = 0; totalSteps = 0; cursor = 0
            equity = params.initialCapital; peak = equity; maxDD = 0.0
        } else {
            error = null
            cache = buildCache(cs)
            activeFilters = params.filters.filter { it.enabled }
            startIdx = sIdx
            totalSteps = (cs.size - 1) - startIdx
            cursor = startIdx
            equity = params.initialCapital; peak = equity; maxDD = 0.0
        }
    }

    fun doneSteps(): Int = frames.size
    fun isFinished(): Boolean = error != null || (cursor >= candles.size - 1 && pending == null && open == null)
    fun tradesSoFar(): List<Trade> = trades.toList()
    fun currentEquity(): Double = equity
    fun currentMaxDDPct(): Double = maxDD * 100

    fun indAt(idx: Int): ReplayInd {
        val c = cache ?: return ReplayInd(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        return ReplayInd(candles[idx].c, c.e50[idx], c.rsi14[idx], c.adx14[idx], c.atr14[idx])
    }

    /** Maju satu bar. Mengembalikan frame, atau null bila selesai/error. */
    fun step(): ReplayFrame? {
        if (error != null) return null
        val cs = candles
        val cx = cache ?: return null
        val i = cursor

        // 1) Materialisasi entry yang dijadwalkan bar sinyal sebelumnya (cermin mesin:
        //    entry dieksekusi di open bar i+1). Didahulukan agar entry di bar
        //    terakhir pun tetap diproses seperti mesin.
        val pend = pending
        if (pend != null && pend.entryIdx == i) {
            pending = null
            open = ReplayOpen(pend.dir, pend.entry, pend.lv.sl, pend.lv.tp,
                pend.qty, pend.riskAmount, pend.entryIdx, pend.lastJ)
            val f = ReplayFrame(i, cs.size, cs[i], evaluated = false,
                signalDir = null, signalReasons = emptyList(),
                filterPassed = null, filterFailed = emptyList(), pendingEntry = false,
                justOpened = open, closed = null, open = open, equity = equity)
            // Fall-through: bar entry juga diperiksa SL/TP-nya (scan mesin mulai di j=i+1).
            return checkExitFrame(f, i)
        }
        if (pend != null) pending = null // pengaman: tak boleh ada pending basi

        // 2) Posisi terbuka → periksa exit pada bar ini (logika scan mesin, j=i).
        //    Boleh jalan hingga bar TERAKHIR inklusif (scan mesin j<=lastJ<=size-1).
        if (open != null) {
            if (i >= cs.size) return null
            val f = ReplayFrame(i, cs.size, cs[i], evaluated = false,
                signalDir = null, signalReasons = emptyList(),
                filterPassed = null, filterFailed = emptyList(), pendingEntry = false,
                justOpened = null, closed = null, open = open, equity = equity)
            return checkExitFrame(f, i)
        }

        // 3) Datar → evaluasi keputusan persis seperti mesin.
        //    Batas i<size-1: butuh bar berikut untuk harga entry (cermin while mesin).
        if (i >= cs.size - 1) return null
        val dec = decideAt(cs, i, cx, params)
        if (!dec.passed) {
            cursor++
            val f = ReplayFrame(i, cs.size, cs[i], evaluated = true,
                signalDir = null, signalReasons = emptyList(),
                filterPassed = null, filterFailed = emptyList(), pendingEntry = false,
                justOpened = null, closed = null, open = null, equity = equity)
            frames.add(f)
            return f
        }
        if (activeFilters.isNotEmpty()) {
            val fr = applyFilters(cs, i, cx, dec.direction, activeFilters, fctx)
            fctx.lastSigDir = dec.direction; fctx.lastSigIdx = i
            if (!fr.passed) {
                cursor++
                val f = ReplayFrame(i, cs.size, cs[i], evaluated = true,
                    signalDir = dec.direction, signalReasons = dec.reasons,
                    filterPassed = false, filterFailed = fr.failed,
                    pendingEntry = false, justOpened = null, closed = null,
                    open = null, equity = equity)
                frames.add(f)
                return f
            }
        }
        var entry = cs[i + 1].o
        entry = if (dec.direction == "LONG") entry * (1 + params.slippagePercent)
        else entry * (1 - params.slippagePercent)
        if (!entry.isFinite() || entry <= 0) {
            cursor++
            val f = ReplayFrame(i, cs.size, cs[i], evaluated = true,
                signalDir = dec.direction, signalReasons = dec.reasons,
                filterPassed = true, filterFailed = emptyList(), pendingEntry = false,
                justOpened = null, closed = null, open = null, equity = equity)
            frames.add(f)
            return f
        }
        val lv = calcRiskLevels(entry, dec.direction, cx, i, params)
        if (lv == null) {
            cursor++
            val f = ReplayFrame(i, cs.size, cs[i], evaluated = true,
                signalDir = dec.direction, signalReasons = dec.reasons,
                filterPassed = true, filterFailed = emptyList(), pendingEntry = false,
                justOpened = null, closed = null, open = null, equity = equity)
            frames.add(f)
            return f
        }
        val riskAmount = equity * params.riskPerTrade
        var qty = riskAmount / lv.riskDist
        val maxNotional = equity * params.leverage
        val notional = qty * entry
        if (!qty.isFinite() || qty <= 0) {
            cursor++
            val f = ReplayFrame(i, cs.size, cs[i], evaluated = true,
                signalDir = dec.direction, signalReasons = dec.reasons,
                filterPassed = true, filterFailed = emptyList(), pendingEntry = false,
                justOpened = null, closed = null, open = null, equity = equity)
            frames.add(f)
            return f
        }
        if (notional > maxNotional) qty = maxNotional / entry
        if (!(qty > 0) || !qty.isFinite()) {
            cursor++
            val f = ReplayFrame(i, cs.size, cs[i], evaluated = true,
                signalDir = dec.direction, signalReasons = dec.reasons,
                filterPassed = true, filterFailed = emptyList(), pendingEntry = false,
                justOpened = null, closed = null, open = null, equity = equity)
            frames.add(f)
            return f
        }
        val lastJ = minOf(cs.size - 1, i + params.maxHolding)
        pending = PendingOpen(dec.direction, entry, lv, qty, riskAmount, i + 1, lastJ, dec)
        cursor++
        val f = ReplayFrame(i, cs.size, cs[i], evaluated = true,
            signalDir = dec.direction, signalReasons = dec.reasons,
            filterPassed = true, filterFailed = emptyList(), pendingEntry = true,
            justOpened = null, closed = null, open = null, equity = equity)
        frames.add(f)
        return f
    }

    /** Periksa SL/TP/EXPIRED pada bar [i] untuk posisi terbuka (cermin badan scan mesin). */
    private fun checkExitFrame(base: ReplayFrame, i: Int): ReplayFrame {
        val cs = candles
        val op = open ?: run { cursor++; frames.add(base); return base }
        val b = cs[i]
        val slHit: Boolean; val tpHit: Boolean
        if (op.dir == "LONG") { slHit = b.l <= op.sl; tpHit = b.h >= op.tp }
        else { slHit = b.h >= op.sl; tpHit = b.l <= op.tp }
        var exit: Double? = null
        var result = "EXPIRED"
        if (slHit && tpHit) {
            exit = if (op.dir == "LONG") op.sl * (1 - params.slippagePercent) else op.sl * (1 + params.slippagePercent)
            result = "LOSS"
        } else if (slHit) {
            exit = if (op.dir == "LONG") op.sl * (1 - params.slippagePercent) else op.sl * (1 + params.slippagePercent)
            result = "LOSS"
        } else if (tpHit) {
            exit = if (op.dir == "LONG") op.tp * (1 - params.slippagePercent) else op.tp * (1 + params.slippagePercent)
            result = "WIN"
        } else if (i >= op.lastJ) {
            val lb = cs[op.lastJ]
            exit = if (op.dir == "LONG") lb.c * (1 - params.slippagePercent) else lb.c * (1 + params.slippagePercent)
            result = "EXPIRED"
        }
        cursor++
        if (exit == null) {
            val f = base.copy(open = open, equity = equity)
            frames.add(f)
            return f
        }
        val ex = exit
        val gross = (if (op.dir == "LONG") ex - op.entry else op.entry - ex) * op.qty
        val fees = (op.entry * op.qty + ex * op.qty) * params.feePercent
        val pnl = gross - fees
        if (!pnl.isFinite()) {
            // Cermin mesin (badPnl): posisi gugur tanpa transaksi & tanpa perubahan ekuitas.
            open = null
            val f = base.copy(open = null, equity = equity)
            frames.add(f)
            return f
        }
        val rMult = if (op.riskAmount > 0) pnl / op.riskAmount else 0.0
        equity += pnl
        if (!equity.isFinite()) equity = params.initialCapital
        peak = maxOf(peak, equity)
        val dd = if (peak > 0) (peak - equity) / peak else 0.0
        if (dd > maxDD) maxDD = dd
        val t = Trade(
            direction = op.dir, asset = params.asset, timeframe = params.timeframe,
            entry = op.entry, exit = ex, stopLoss = op.sl, takeProfit = op.tp,
            entryTime = cs[op.entryIdx].t, exitTime = cs[i].t,
            strategy = params.strategy, confidence = 0.0, reasons = emptyList(),
            qty = op.qty, riskAmount = op.riskAmount, fees = fees, pnl = pnl,
            pnlPercent = if (op.entry > 0) ((if (op.dir == "LONG") ex - op.entry else op.entry - ex) / op.entry) * 100 * params.leverage else 0.0,
            rMultiple = rMult, result = result, holding = i - op.entryIdx + 1,
            uid = "${params.asset}|${op.dir}|${cs[op.entryIdx].t}|${cs[i].t}"
        )
        trades.add(t)
        open = null
        fctx.lastExit = i
        val f = base.copy(closed = t, open = null, equity = equity)
        frames.add(f)
        return f
    }

    /**
     * Mundur [n] frame. Deterministik via putar-ulang dari awal (murah: ≤ ~1000 bar),
     * sehingga tak ada status basi/duplikat. Mengembalikan frame posisi baru (atau null).
     */
    fun back(n: Int = 1): ReplayFrame? {
        if (frames.isEmpty()) return null
        val target = (frames.size - n).coerceAtLeast(0)
        reset()
        var f: ReplayFrame? = null
        repeat(target) { f = step() }
        return if (target == 0) null else f
    }

    /** Reset total: status, ekuitas, posisi, dan frame dibersihkan. */
    fun reset() {
        cursor = startIdx
        equity = params.initialCapital; peak = equity; maxDD = 0.0
        open = null; pending = null
        fctx = FilterCtx(lastExit = -1000000000)
        frames.clear(); trades.clear()
    }

    fun lastFrame(): ReplayFrame? = frames.lastOrNull()
}
