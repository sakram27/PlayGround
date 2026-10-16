import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// REGIME GATE SIDEWAYS — BUKAN PRODUKSI, harness terisolasi.
// Definisi regime DIKOMIT SEBELUM membaca hasil validasi (lihat bawah) dan
// TIDAK diubah setelahnya:
//   SIDEWAYS boleh entry iff adx14[k] finite DAN adx14[k] < 20.0.
//   Alasan ambang: 20.0 = default produksi "ADX Filter / Blokir tren lemah"
//   (FILTER_DEFS adx min 20.0) — definisi "tren lemah" milik aplikasi sendiri.
//   adx14 NaN → DITOLAK konservatif (dihitung sebagai disaring, dilaporkan).
//   Hanya memakai indikator bar ≤ k (adx14[k] dari buildCache) — tanpa
//   candle masa depan. MTF/SR/AND/SL/TP/fee/slip/sizing/entry-open-berikut/
//   aturan konservatif SL+TP一起 TIDAK berubah (semua primitif produksi).
// Varian: A = baseline AND produksi; B = A + gate sideways.
// W0 = pengembangan; W1..W5 = validasi. Tanpa fitting pada validasi.
var rgPass = 0
var rgFail = 0
fun rgOk(n: String, c: Boolean, x: String = "") {
    if (c) { rgPass++; println("PASS $n $x") } else { rgFail++; println("FAIL $n $x") }
}
fun rgPf(pf: Double, w: Int, l: Int): String = when {
    pf.isInfinite() && pf > 0 -> "Inf(${w}W${l}L)"
    pf.isNaN() -> "NaN(${w}W${l}L)"
    else -> evF2(pf)
}

/** Gate sideways: true = boleh entry. Murni dari cache produksi bar ≤ k. */
fun rgSideways(cache: Cache, k: Int): Boolean {
    val a = cache.adx14.getOrNull(k) ?: Double.NaN
    return a.isFinite() && a < 20.0
}

data class RGMV(val sym: String, val candles: List<Candle>, val cache: Cache, val startIdx: Int)

// Cermin 1:1 mvRunPortfolio + penghitung alasan penolakan lengkap:
// gate (hanya varian B) → cap-penuh → level-tak-valid → dibuka. Satu-satunya
// injeksi: gate(a, k) mengembalikan false = kandidat disaring.
fun rgRun(pairs: List<RGMV>, params: BacktestParams, tag: String,
    decide: (LoopAsset, Int) -> SignalDecision,
    gate: ((LoopAsset, Int) -> Boolean)?): Triple<BacktestResult, Int, Int> {
    val assets = pairs.map { LoopAsset(it.sym, it.candles, it.cache, it.startIdx) }
    val cap = params.maxOpenTrades.coerceIn(MAX_OPEN_TRADES_MIN, MAX_OPEN_TRADES_MAX)
    val activeFilters = params.filters.filter { it.enabled }
    val trades = ArrayList<Trade>()
    val equityCurve = ArrayList<EquityPoint>()
    val st = EqState(params.initialCapital, params.initialCapital, 0.0)
    val firstT = assets.minOfOrNull { it.candles.firstOrNull()?.t ?: Long.MAX_VALUE } ?: 0L
    if (firstT > 0 && firstT != Long.MAX_VALUE) equityCurve.add(EquityPoint(firstT, st.equity))
    val opens = ArrayList<OpenPos>()
    var seq = 0; var maxOpenObs = 0; var rejectedLimit = 0
    var evaluated = 0; var sigRaw = 0; var filtered = 0; var candidates = 0
    var gateDropped = 0; var levelNull = 0
    val passed = HashMap<String, Int>()
    val cnt = OpenCounters(); var badPnl = 0
    val reasons = HashMap<String, Int>()
    val timeline = java.util.TreeMap<Long, ArrayList<Pair<LoopAsset, Int>>>()
    for (a in assets) {
        for ((k, c) in a.candles.withIndex()) {
            timeline.getOrPut(c.t) { ArrayList() }.add(a to k)
        }
    }
    var evalTo = assets.maxOfOrNull { it.candles[it.candles.size - 2].t } ?: 0L
    for ((t, jobs) in timeline) {
        for ((a, k) in jobs) {
            val b = a.candles[k]
            var closedHere = false
            val due = opens.filter { it.assetTag == a.sym && it.entryIdx <= k }.sortedBy { it.seq }
            for (op in due) {
                val lf = exitStepPosition(params, a.candles, op, b, k) ?: continue
                if (applyLegPosition(params, a.candles, st, equityCurve, trades, op, lf)) {
                    op.remaining -= lf.qtyClosed
                    if (lf.kind.startsWith("TP")) op.leg++
                    if (op.remaining / op.qtyOrig <= 1e-9 || lf.kind == "SL" || lf.kind == "TRAIL" || lf.kind == "EXPIRED") {
                        opens.remove(op); a.fctx.lastExit = k; closedHere = true
                    }
                } else { badPnl++; opens.remove(op); a.fctx.lastExit = k; closedHere = true }
            }
            if (!closedHere && k >= a.startIdx && k < a.candles.size - 1) {
                evaluated++
                val dec = decide(a, k)
                if (!dec.passed) continue
                candidates++
                if (gate != null && !gate(a, k)) { gateDropped++; continue }
                if (opens.size >= cap) {
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
                            for (w in fr.failed) { val key = w.take(60); reasons[key] = (reasons[key] ?: 0) + 1 }
                            ok = false
                        }
                    }
                    if (ok) {
                        val op = tryOpenPosition(a.candles, k, params, a.cache, st.equity, dec, seq, a.sym, cnt)
                        if (op != null) { opens.add(op); seq++; if (opens.size > maxOpenObs) maxOpenObs = opens.size }
                        else levelNull++
                    }
                }
            }
        }
    }
    val totalSlots = assets.sumOf { (it.candles.size - 1) - it.startIdx }
    val d = BacktestDiag(
        rawCount = assets.sumOf { it.candles.size }, normalizedCount = assets.sumOf { it.candles.size },
        startIdx = assets.minOf { it.startIdx },
        evaluatedBars = evaluated, skippedInPosition = maxOf(0, totalSlots - evaluated),
        signalsRaw = sigRaw, filteredOut = filtered,
        maxOpenTrades = cap, maxOpenObserved = maxOpenObs, rejectedByLimit = rejectedLimit,
        skippedNoLevel = cnt.noLevel, skippedBadEntry = cnt.badEntry,
        skippedBadQty = cnt.badQty, skippedBadPnl = badPnl,
        filterReasons = reasons, filterPassed = passed,
        evalFrom = assets.minOf { it.candles[it.startIdx].t }, evalTo = evalTo,
        runId = "rg-$tag", finishedAt = System.currentTimeMillis())
    val span = assets.flatMap { a -> a.candles }.sortedBy { it.t }
    return Triple(buildResult(params, span, trades, equityCurve, st.maxDD, filtered, d), gateDropped, levelNull)
}

fun main() {
    val syms = listOf("SHIBUSDT", "PENGUUSDT", "ARBUSDT", "BTCUSDT", "AVAXUSDT",
        "SEIUSDT", "ADAUSDT", "SUIUSDT", "DOTUSDT", "BCHUSDT")
    fun utc(y: Int, m: Int, d: Int): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(y, m - 1, d, 0, 0, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    data class W(val id: String, val role: String, val from: Long, val to: Long)
    val windows = listOf(
        W("W0", "DEV", utc(2026, 10, 4), utc(2026, 10, 14) + 86399000L),
        W("W1", "VAL", utc(2026, 9, 22), utc(2026, 10, 2) + 86399000L),
        W("W2", "VAL", utc(2026, 9, 10), utc(2026, 9, 20) + 86399000L),
        W("W3", "VAL", utc(2026, 8, 29), utc(2026, 9, 8) + 86399000L),
        W("W4", "VAL", utc(2026, 7, 18), utc(2026, 7, 28) + 86399000L),
        W("W5", "VAL", utc(2026, 6, 6), utc(2026, 6, 16) + 86399000L))
    println("REGIME-DIKOMIT: SIDEWAYS iff adx14[k] finite & < 20.0 (default produksi ADX Filter); NaN = ditolak.")

    data class PD(val sym: String, val candles: List<Candle>, val cache: Cache, val startIdx: Int)
    val winPD = LinkedHashMap<String, ArrayList<PD>>()
    var fetchOk = true
    for (w in windows) {
        winPD[w.id] = ArrayList()
        for (s in syms) {
            try {
                val rr = fetchRangeCandles("binance", s, "15m", w.from, w.to)
                val full = normalizeCandles(rr.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
                val (prefix, inRange) = splitWarmup(full, 0L, 0L)
                val candles = prefix + inRange
                val startIdx = maxOf(prefix.size + WARMUP, 2)
                if (prefix.isNotEmpty() || startIdx > candles.size - 2 || candles.size != 1056) {
                    fetchOk = false
                    println("DATA-ANEH ${w.id} $s n=${candles.size} prefix=${prefix.size}")
                    continue
                }
                winPD[w.id]!!.add(PD(s, candles, buildCache(candles), startIdx))
            } catch (e: Exception) {
                fetchOk = false
                println("FETCH-FAIL ${w.id} $s: ${e.message?.take(120)}")
            }
        }
        rgOk("periode-${w.id}", winPD[w.id]!!.size == 10, "10/10 n=1056 startIdx=60")
    }
    rgOk("periode-lengkap", fetchOk, "60/60 fetch OK")

    val pp = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
        strategy = "support_resistance",
        combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
        filters = emptyList(), maxOpenTrades = 10))
    println("PARAM-EFEKTIF fee=${pp.feePercent} slip=${pp.slippagePercent}(terkandung di harga, tak terpisah) " +
        "sl=${pp.slPercent} tp=${pp.tpPercent} mh=${pp.maxHolding} capAwal=${pp.initialCapital} " +
        "risk=${pp.riskPerTrade} lev=${pp.leverage} tpMode=${pp.tpMode} capPortofolio=10 filter=0")

    println("var|win|cand|disaringGate|dibuka|trades|wins|win%|grossP|grossL|PF|net|fee|avgW|avgL|long|short|rejCap|lvlNull|expLeg|exposure%|ddPort%|ddPairMax%")
    var mathOk = true
    for (w in windows) {
        val pds = winPD[w.id]!!
        // Cakupan gate: % bar evaluasi sideways (konteks frekuensi).
        var sideN = 0; var sideTot = 0
        for (pd in pds) for (k in pd.startIdx until pd.candles.size - 1) {
            sideTot++; if (rgSideways(pd.cache, k)) sideN++
        }
        println("GATE-COVER ${w.id}: sideways=$sideN/$sideTot (${evF2(sideN.toDouble() / sideTot * 100)}%)")
        val mvp = pds.map { RGMV(it.sym, it.candles, it.cache, it.startIdx) }
        // A: produksi untuk angka; paritas kendaraan harness.
        val pr = runPortfolioBacktest(pds.map { PortfolioAsset(it.sym,
            it.candles.map { c -> mapOf("t" to c.t, "o" to c.o, "h" to c.h, "l" to c.l, "c" to c.c, "v" to c.v) as Any? }) }, pp)
        if (pr.result.error != null) { println("RUN-ERROR ${w.id} A: ${pr.result.error}"); mathOk = false; continue }
        val rA = pr.result
        run {
            val (hr, _, _) = rgRun(mvp, pp, "${w.id}-Apar", { a, k -> decideAt(a.candles, k, a.cache, pp) }, null)
            val okPar = rA.trades.map { it.uid }.sorted() == hr.trades.map { it.uid }.sorted() &&
                kotlin.math.abs(rA.netProfit - hr.netProfit) < 1e-6 * maxOf(1.0, kotlin.math.abs(rA.netProfit)) &&
                kotlin.math.abs(rA.maxDrawdownPercent - hr.maxDrawdownPercent) < 1e-9
            rgOk("paritas-${w.id}-A", okPar, "tr=${rA.totalTrades}")
            if (!okPar) mathOk = false
        }
        if (pds.isEmpty()) continue
        // B: gate sideways.
        val (rB, droppedB, lvlB) = rgRun(mvp, pp, "${w.id}-B",
            { a, k -> decideAt(a.candles, k, a.cache, pp) },
            { a, k -> rgSideways(a.cache, k) })
        // Subset B⊆A TIDAK diasertkan: kandidat yang bertepatan dengan bar
        // penutupan posisi dilewat diam-diam (closedHere, tanpa counter) di
        // KEDUA run produksi maupun mirror — polanya bergantung state posisi,
        // sehingga B dapat membuka kandidat yang di run-A tertelan close-bar.
        // Dilaporkan informatif sebagai bukti mekanisme, bukan kegagalan.
        val uA = rA.trades.map { it.uid }.toSet()
        val uB = rB.trades.map { it.uid }.toSet()
        val inter = uB.count { it in uA }
        println("SUBSET-${w.id}: B∩A=$inter/${uB.size} B\\A=${uB.size - inter} (wajar bila >0: skip closedHere beda state)")
        // Verifikasi mekanisme: tiap uid B\A harus berkorespondensi dengan bar
        // yang di run-A menutup posisi aset yang sama (closedHere menelan
        // kandidat diam-diam) — bila tidak, itu kegagalan mirror yang nyata.
        var closedOk = true
        for (u in uB) {
            if (u in uA) continue
            val parts = u.split("|")
            val sym = parts[0]; val entryT = parts[2].toLong()
            val pd = pds.first { it.sym == sym }
            val k = pd.candles.indexOfFirst { it.t == entryT } - 1
            val aCloses = rA.trades.filter { it.asset == sym }.map { t ->
                pd.candles.indexOfFirst { it.t == t.exitTime }
            }.toSet()
            val explained = k in aCloses
            println("CLOSEDHERE-${w.id}: $u signalBar=$k closeBarsA=$aCloses dijelaskan=$explained")
            if (!explained) closedOk = false
        }
        rgOk("closedhere-${w.id}", closedOk, "B\\A berasal dari skip close-bar run-A")
        // Perbandingan adil: pnl posisi dipertahankan vs disaring (dari run A).
        var keepN = 0; var keepPnl = 0.0; var dropN = 0; var dropPnl = 0.0
        val byPos = rA.trades.groupBy { "${it.asset}|${it.entryTime}" }
        for ((key, legs) in byPos) {
            val sym = key.substringBefore("|")
            val et = key.substringAfter("|").toLong()
            val pd = pds.first { it.sym == sym }
            val k = pd.candles.indexOfFirst { it.t == et } - 1
            val pnl = legs.sumOf { it.pnl }
            if (k >= pd.startIdx && rgSideways(pd.cache, k)) { keepN++; keepPnl += pnl }
            else { dropN++; dropPnl += pnl }
        }
        println("ADIL-${w.id}: dipertahankan n=$keepN pnl=${evF2(keepPnl)} | disaring n=$dropN pnl=${evF2(dropPnl)}")
        for ((id, r, dropped) in listOf(
            Triple("A", rA, 0), Triple("B", rB, droppedB))) {
            var ddPairMax = 0.0
            for (pd in pds) {
                val rp = evRun(pd.candles, pd.cache, pd.startIdx, pp.copy(asset = pd.sym),
                    { k ->
                        val dec = decideAt(pd.candles, k, pd.cache, pp)
                        if (!dec.passed) dec
                        else if (id == "A" || rgSideways(pd.cache, k)) dec
                        else SignalDecision(false, "NONE", 0.0, emptyList(), "exp-rg")
                    }, "dd-${w.id}-$id-${pd.sym}")
                ddPairMax = maxOf(ddPairMax, rp.maxDrawdownPercent)
                for (t in rp.trades) {
                    if (t.entryTime < pd.candles[pd.startIdx + 1].t || t.exitTime > pd.candles.last().t) {
                        mathOk = false; println("BATAS-GAGAL ${w.id} $id ${pd.sym}")
                    }
                }
                // Per-pair ringkas (wajib: hasil per pair dan per periode).
                val feeP = rp.trades.sumOf { it.fees }
                println("PAIR $id|${w.id}|${pd.sym}|tr=${rp.totalTrades}|w=${rp.wins}|net=${evF2(rp.netProfit)}|" +
                    "pf=${rgPf(rp.profitFactor, rp.wins, rp.totalTrades - rp.wins)}|fee=${evF2(feeP)}|dd=${evF2(rp.maxDrawdownPercent)}")
            }
            if (r.trades.any { it.exitTime < it.entryTime }) { mathOk = false; println("LOOKAHEAD-GAGAL ${w.id} $id") }
            if (kotlin.math.abs(r.netProfit - (r.grossProfit + r.grossLoss)) > 1e-6 * maxOf(1.0, kotlin.math.abs(r.netProfit))) {
                mathOk = false; println("MATH-GAGAL ${w.id} $id")
            }
            val longs = r.trades.count { it.direction == "LONG" }
            val shorts = r.trades.count { it.direction == "SHORT" }
            val opened = r.trades.map { "${it.asset}|${it.entryTime}" }.toSet().size
            val fee = r.trades.sumOf { it.fees }
            val evalBars = pds.sumOf { it.candles.size - 1 - it.startIdx }
            val expo = r.trades.sumOf { it.holding }.toDouble() / evalBars * 100
            val lvl = if (id == "B") lvlB else r.diag.skippedNoLevel + r.diag.skippedBadEntry + r.diag.skippedBadQty
            // Kandidat: hitung ulang pada rentang evaluasi (produksi).
            var cand = 0
            for (pd in pds) for (k in pd.startIdx until pd.candles.size - 1) {
                val dec = decideAt(pd.candles, k, pd.cache, pp)
                if (!dec.passed) continue
                if (id == "A" || rgSideways(pd.cache, k)) cand++
            }
            println("$id|${w.id}|$cand|$dropped|$opened|${r.totalTrades}|${r.wins}|${evF2(r.winRate)}|" +
                "${evF2(r.grossProfit)}|${evF2(r.grossLoss)}|${rgPf(r.profitFactor, r.wins, r.totalTrades - r.wins)}|" +
                "${evF2(r.netProfit)}|${evF2(fee)}|${evF2(r.averageWin)}|${evF2(r.averageLoss)}|$longs|$shorts|" +
                "${r.diag.rejectedByLimit}|$lvl|${r.expired}|${evF2(expo)}|${evF2(r.maxDrawdownPercent)}|${evF2(ddPairMax)}")
        }
    }
    rgOk("matematika & batas", mathOk, "paritas+subset+net==gp+gl+dalam-periode+tanpa-lookahead")
    println("REGIME-EVAL done pass=$rgPass fail=$rgFail")
    if (rgFail > 0) kotlin.system.exitProcess(1)
}
