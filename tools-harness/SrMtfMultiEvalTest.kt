import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// VALIDASI MULTI-PERIODE SR+MTF — BUKAN PRODUKSI.
// - Memakai ulang: fetchRangeCandles/getCandles, normalizeCandles,
//   splitWarmup, buildCache, decideAt, runBacktest, runPortfolioBacktest,
//   tryOpenPosition, exitStepPosition, applyLegPosition, buildResult,
//   LoopAsset/EqState/OpenCounters/FilterCtx PRODUKSI (read-only).
// - Replika sendiri: mvRunPortfolio (cermin 1:1 runLoopMulti jalur
//   portofolio + timeline gabungan + ekuitas bersama + cap global, dengan
//   decide yang diinjeksikan) + evDecideTol/evRun dari SrMtfEvalTest.kt.
// - Paritas portofolio dibuktikan per jendela per mode pada tol=0.001
//   (uid + net + maxDD identik produksi) sebelum angka ambang dipakai.
// - MAJORITY tidak dijalankan ulang: ekuivalen MAJ-2 ≡ AND sudah dibuktikan
//   per-bar pada 10 pair (PASS paritas-replika-001 + blok MAJORITY run
//   sebelumnya) dan rumusnya need=2/2+1=2 — dinyatakan eksplisit, bukan
//   disamakan diam-diam.
var mvPass = 0
var mvFail = 0
fun mvOk(n: String, c: Boolean, x: String = "") {
    if (c) { mvPass++; println("PASS $n $x") } else { mvFail++; println("FAIL $n $x") }
}
fun mvDay(t: Long): String {
    if (t <= 0) return "-"
    return try {
        val f = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.format(java.util.Date(t))
    } catch (e: Exception) { t.toString() }
}
fun mvUtc(y: Int, m: Int, d: Int): Long {
    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, m - 1)
    c.set(java.util.Calendar.DAY_OF_MONTH, d)
    c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

data class MVPair(val sym: String, val candles: List<Candle>, val cache: Cache, val startIdx: Int)

// Cermin 1:1 runLoopMulti jalur portofolio (Backtest.kt:342-464): timeline
// gabungan, ekuitas + cap + counter BERSAMA, fctx per aset. Satu-satunya
// injeksi: decide(a, k) menggantikan decideAt.
fun mvRunPortfolio(pairs: List<MVPair>, params: BacktestParams, tag: String,
    decide: (LoopAsset, Int) -> SignalDecision): BacktestResult {
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
    var evaluated = 0; var sigRaw = 0; var filtered = 0; var candidates = 0; var opened = 0
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
                        if (op != null) { opens.add(op); seq++; opened++; if (opens.size > maxOpenObs) maxOpenObs = opens.size }
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
        runId = "mv-$tag", finishedAt = System.currentTimeMillis())
    val span = assets.flatMap { a -> a.candles }.sortedBy { it.t }
    val res = buildResult(params, span, trades, equityCurve, st.maxDD, filtered, d)
    // Kandidat & posisi dibuka diselipkan via diag.note (tak ada fieldnya) —
    // dilaporkan lewat return pair agar tak mengubah BacktestResult produksi.
    return res
}

fun main() {
    val syms = listOf("SHIBUSDT", "PENGUUSDT", "ARBUSDT", "BTCUSDT", "AVAXUSDT",
        "SEIUSDT", "ADAUSDT", "SUIUSDT", "DOTUSDT", "BCHUSDT")
    // Jendela non-tumpang-tindih (UTC), ±10-11 hari (±1000 candle 15m).
    // W0 = pengembangan/observasi awal; W1..W3 = validasi.
    data class W(val id: String, val role: String, val from: Long, val to: Long)
    val windows = listOf(
        W("W0", "DEV", mvUtc(2026, 10, 4), mvUtc(2026, 10, 14) + 86399000L),
        W("W1", "VAL", mvUtc(2026, 9, 22), mvUtc(2026, 10, 2) + 86399000L),
        W("W2", "VAL", mvUtc(2026, 9, 10), mvUtc(2026, 9, 20) + 86399000L),
        W("W3", "VAL", mvUtc(2026, 8, 29), mvUtc(2026, 9, 8) + 86399000L))
    println("JENDELA: " + windows.joinToString(" | ") { "${it.id}(${it.role}) ${mvDay(it.from)}..${mvDay(it.to)}" })

    data class WP(val sym: String, val candles: List<Candle>, val n: Int, val pages: Int, val capped: Boolean)
    val winData = LinkedHashMap<String, ArrayList<WP>>()
    val winFail = LinkedHashMap<String, ArrayList<String>>()
    for (w in windows) {
        winData[w.id] = ArrayList()
        winFail[w.id] = ArrayList()
        for (s in syms) {
            try {
                val rr = fetchRangeCandles("binance", s, "15m", w.from, w.to)
                if (rr.candles.size < MIN_CANDLES + WARMUP + 2) {
                    winFail[w.id]!!.add("$s: n=${rr.candles.size} kurang")
                    println("FETCH-KURANG ${w.id} $s n=${rr.candles.size} pages=${rr.pages} capped=${rr.capped}")
                    continue
                }
                winData[w.id]!!.add(WP(s, rr.candles, rr.candles.size, rr.pages, rr.capped))
                println("FETCH-OK ${w.id} $s n=${rr.candles.size} pages=${rr.pages} capped=${rr.capped} " +
                    "${mvDay(rr.candles.first().t)}..${mvDay(rr.candles.last().t)}")
            } catch (e: Exception) {
                winFail[w.id]!!.add("$s: ${e.message?.take(100)}")
                println("FETCH-FAIL ${w.id} $s: ${e.message?.take(120)}")
            }
        }
    }
    // Rezim deskriptif dari data (bukan klaim): median drift + rerata ATR%.
    data class WR(val id: String, val medDrift: Double, val meanAtr: Double)
    val regInfo = ArrayList<WR>()
    for (w in windows) {
        val ds = winData[w.id]!!.map { wp ->
            val c = wp.candles
            val cache = buildCache(c)
            val drift = c.last().c / c.first().c - 1
            val a = cache.atr14[c.size - 2]
            val atr = if (a.isFinite() && a > 0 && c.last().c > 0) a / c.last().c * 100 else Double.NaN
            drift to atr
        }
        val med = ds.map { it.first }.sorted().let { it[it.size / 2] }
        val ma = ds.mapNotNull { it.second.takeIf { v -> v.isFinite() } }.average()
        regInfo.add(WR(w.id, med * 100, ma))
    }
    val volMed = regInfo.map { it.meanAtr }.sorted().let { (it[1] + it[2]) / 2 }
    for (r in regInfo) {
        val arah = if (r.medDrift >= 3) "NAIK" else if (r.medDrift <= -3) "TURUN" else "DATAR"
        val vol = if (r.meanAtr >= volMed) "VOL-TINGGI" else "vol-rendah"
        println("REZIM ${r.id}: medDrift=${evF2(r.medDrift)}% atr=${evF2(r.meanAtr)}% → $arah+$vol (deskriptif)")
    }

    val pProbe = sanitizeParams(BacktestParams(asset = "BTCUSDT", timeframe = "15m",
        strategy = "support_resistance",
        combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
        filters = emptyList(), maxOpenTrades = 10))
    println("PARAM-EFEKTIF fee=${pProbe.feePercent} slip=${pProbe.slippagePercent} sl=${pProbe.slPercent} " +
        "tp=${pProbe.tpPercent} mh=${pProbe.maxHolding} capAwal=${pProbe.initialCapital} " +
        "risk=${pProbe.riskPerTrade} lev=${pProbe.leverage} tpMode=${pProbe.tpMode} capPortofolio=10 filter=0")

    // Varian: A=AND 0.1, B=AND 0.15, C=AND 0.2, D=OR 0.1.
    data class V(val id: String, val mode: String, val tol: Double, val prod: Boolean)
    val variants = listOf(
        V("A", "AND", 0.001, true), V("B", "AND", 0.0015, false),
        V("C", "AND", 0.002, false), V("D", "OR", 0.001, true))
    println("varian|win|nPair|nCandle|kandidat|dibuka|trades|wins|win%|grossP|grossL|PF|net|fee|avgW|avgL|long|short|rejCap|exp|be|ddPort%|ddPairMax%")

    var allMathOk = true
    for (w in windows) {
        val wps = winData[w.id]!!
        if (wps.isEmpty()) { println("SKIP ${w.id}: tanpa data"); continue }
        // Siapkan LoopAsset-style per pair (normalisasi + warmup + cache produksi).
        data class PA(val sym: String, val candles: List<Candle>, val cache: Cache, val startIdx: Int)
        val pas = wps.map { wp ->
            val full = normalizeCandles(wp.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
            val (prefix, inRange) = splitWarmup(full, 0L, 0L)
            val candles = prefix + inRange
            PA(wp.sym, candles, buildCache(candles), maxOf(prefix.size + WARMUP, 2))
        }
        for (v in variants) {
            val pp = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
                strategy = "support_resistance",
                combo = Combo(v.mode, listOf("support_resistance", "mtf_confirm")),
                filters = emptyList(), maxOpenTrades = 10))
            val mvp = pas.map { MVPair(it.sym, it.candles, it.cache, it.startIdx) }
            // Kandidat dihitung pada rentang evaluasi produksi.
            var cand = 0
            for (pa in pas) {
                for (k in pa.startIdx until pa.candles.size - 1) {
                    val d = if (v.tol == 0.001) decideAt(pa.candles, k, pa.cache, pp)
                    else evDecideTol(v.mode, pa.candles, k, pa.cache, v.tol, pp.strategyParams)
                    if (d.passed) cand++
                }
            }
            // Paritas portofolio pada tol produksi.
            var r: BacktestResult
            if (v.tol == 0.001) {
                val pr = runPortfolioBacktest(pas.map { PortfolioAsset(it.sym,
                    it.candles.map { c -> mapOf("t" to c.t, "o" to c.o, "h" to c.h, "l" to c.l, "c" to c.c, "v" to c.v) as Any? }) }, pp)
                if (pr.result.error != null) { println("RUN-ERROR ${w.id} ${v.id}: ${pr.result.error}"); continue }
                val hr = mvRunPortfolio(mvp, pp, "${w.id}-${v.id}") { a, k -> decideAt(a.candles, k, a.cache, pp) }
                val up = pr.result.trades.map { it.uid }.sorted()
                val uh = hr.trades.map { it.uid }.sorted()
                val netEq = kotlin.math.abs(pr.result.netProfit - hr.netProfit) < 1e-6 * maxOf(1.0, kotlin.math.abs(pr.result.netProfit))
                val ddEq = kotlin.math.abs(pr.result.maxDrawdownPercent - hr.maxDrawdownPercent) < 1e-9
                val okPar = up == uh && netEq && ddEq
                mvOk("paritas-portofolio-${w.id}-${v.id}", okPar,
                    "tr=${pr.result.totalTrades}/${hr.totalTrades} net=${evF2(pr.result.netProfit)}/${evF2(hr.netProfit)}")
                if (!okPar) { allMathOk = false; continue }
                r = pr.result
            } else {
                r = mvRunPortfolio(mvp, pp, "${w.id}-${v.id}") { a, k ->
                    evDecideTol(v.mode, a.candles, k, a.cache, v.tol, pp.strategyParams) }
            }
            // maxDD per pair (metrik terpisah) via loop satu-aset terparitas.
            var ddPairMax = 0.0
            val longs = r.trades.count { it.direction == "LONG" }
            val shorts = r.trades.count { it.direction == "SHORT" }
            for (pa in pas) {
                val rp = evRun(pa.candles, pa.cache, pa.startIdx, pp.copy(asset = pa.sym),
                    { k -> evDecideTol(v.mode, pa.candles, k, pa.cache, v.tol, pp.strategyParams) }, "dd-${w.id}-${v.id}-${pa.sym}")
                ddPairMax = maxOf(ddPairMax, rp.maxDrawdownPercent)
            }
            // Konsistensi matematis: net == gp+gl ; PF == gp/|gl|.
            val mathNet = kotlin.math.abs(r.netProfit - (r.grossProfit + r.grossLoss)) < 1e-6 * maxOf(1.0, kotlin.math.abs(r.netProfit))
            val mathPf = if (r.grossLoss < 0) kotlin.math.abs(r.profitFactor - r.grossProfit / -r.grossLoss) < 1e-9
            else true
            if (!mathNet || !mathPf) { allMathOk = false; println("MATH-GAGAL ${w.id} ${v.id}") }
            val fee = r.trades.sumOf { it.fees }
            // Posisi yang benar-benar dibuka = pasangan aset|entryT unik (bukan kaki).
            val opened = r.trades.map { "${it.asset}|${it.entryTime}" }.toSet().size
            println("${v.id}|${w.id}|${pas.size}|${pas.sumOf { it.candles.size }}|$cand|$opened|${r.totalTrades}|" +
                "${r.wins}|${evF2(r.winRate)}|${evF2(r.grossProfit)}|${evF2(r.grossLoss)}|" +
                "${evF2(r.profitFactor)}|${evF2(r.netProfit)}|${evF2(fee)}|${evF2(r.averageWin)}|" +
                "${evF2(r.averageLoss)}|$longs|$shorts|${r.diag.rejectedByLimit}|${r.expired}|${r.breakeven}|" +
                "${evF2(r.maxDrawdownPercent)}|${evF2(ddPairMax)}")
        }
    }
    mvOk("matematika-konsisten", allMathOk, "net==gp+gl & PF==gp/|gl| semua varian")
    println("MV-EVAL done pass=$mvPass fail=$mvFail")
    if (mvFail > 0) kotlin.system.exitProcess(1)
}
