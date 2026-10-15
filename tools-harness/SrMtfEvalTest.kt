import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// EKSPERIMEN TERPISAH — BUKAN PRODUKSI (audit SR+MTF, mode × ambang).
// Aturan:
// - strategyFn / decideAt / runBacktest / runLoopMulti / provider / fee /
//   eksekusi PRODUKSI tidak diubah, tidak di-override, tidak di-copy-paste
//   diam-diam: semua primitif dipakai langsung dari engine.*.
// - Satu-satunya kode eksperimen: evSrDirTol (SALINAN cabang S/R dengan
//   parameter toleransi, karena produksi meng-hardcode 0.001) + evDecideTol
//   (replika 1:1 rumus gabung decideAt untuk 2 kaki) + evRun (kerangka loop
//   1:1 runLoopMulti satu-aset, memanggil tryOpenPosition /
//   exitStepPosition / applyLegPosition / buildResult PRODUKSI).
// - Keabsahan replika DIBUKTIKAN runtime: pada tol=0.001, arah per bar dan
//   seluruh trade (uid + net) harus IDENTIK dengan produksi. Bila paritas
//   gagal, klaim ambang 0,15%/0,2% dinyatakan INVALID.
// - Data: klines Binance 15m limit 1000 via getCandles PRODUKSI, di-fetch
//   SEKALI lalu dipakai ulang semua varian (periode/pair/TF/biaya/eksekusi
//   sama). Tanpa look-ahead: keputusan bar k hanya memakai candle ≤ k,
//   entry di open bar k+1 (cermin tryOpenPosition produksi).
var evPass = 0
var evFail = 0
fun evOk(n: String, c: Boolean, x: String = "") {
    if (c) { evPass++; println("PASS $n $x") } else { evFail++; println("FAIL $n $x") }
}
fun evF2(v: Double): String = if (v.isNaN() || v.isInfinite()) "-" else String.format(java.util.Locale.US, "%.2f", v)

// SALINAN cabang support_resistance produksi (Strategies.kt:135-147) dengan
// toleransi sebagai parameter. Ambang/conf/alasan selain tol IDENTIK.
fun evSrDirTol(c: List<Candle>, i: Int, tol: Double): Triple<String, Double, List<String>> {
    if (i < 20) return Triple("NONE", 0.0, emptyList())
    var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
    for (j in i - 20 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
    return when {
        ll > 0 && kotlin.math.abs(c[i].l - ll) / ll < tol && c[i].c > c[i].o ->
            Triple("LONG", 0.55, listOf("support bounce"))
        hh > 0 && kotlin.math.abs(c[i].h - hh) / hh < tol && c[i].c < c[i].o ->
            Triple("SHORT", 0.55, listOf("resistance reject"))
        else -> Triple("NONE", 0.0, emptyList())
    }
}

// Replika 1:1 rumus gabung decideAt (Filters.kt:186-196) untuk 2 kaki:
// kaki SR (toleransi eksperimen) + kaki MTF (strategyFn PRODUKSI).
fun evDecideTol(mode: String, candles: List<Candle>, idx: Int, cache: Cache,
    tol: Double, strategyParams: Map<String, Double>): SignalDecision {
    val m = mode.ifEmpty { "OR" }
    val rSr = evSrDirTol(candles, idx, tol).let { Triple("support_resistance", it.first, it.second) }
    val rMtf = strategyFn("mtf_confirm", candles, idx, cache, strategyParams).let { Triple("mtf_confirm", it.first, it.second) }
    val all = listOf(rSr, rMtf)
    val longs = all.filter { it.second == "LONG" }
    val shorts = all.filter { it.second == "SHORT" }
    if (m == "AND") {
        if (longs.size == all.size) return SignalDecision(true, "LONG", longs.map { it.third }.average(), listOf("support bounce"), "support_resistance+mtf_confirm")
        if (shorts.size == all.size) return SignalDecision(true, "SHORT", shorts.map { it.third }.average(), listOf("resistance reject"), "support_resistance+mtf_confirm")
        return SignalDecision(false, "NONE", 0.0, emptyList(), "support_resistance+mtf_confirm")
    }
    val need = if (m == "MAJORITY") all.size / 2 + 1 else 1
    if (longs.size >= need && longs.size >= shorts.size)
        return SignalDecision(true, "LONG", longs.map { it.third }.average(), listOf("combo-long"), longs.map { it.first }.joinToString("+"))
    if (shorts.size >= need && shorts.size > longs.size)
        return SignalDecision(true, "SHORT", shorts.map { it.third }.average(), listOf("combo-short"), shorts.map { it.first }.joinToString("+"))
    return SignalDecision(false, "NONE", 0.0, emptyList(), "support_resistance+mtf_confirm")
}

// Kerangka loop 1:1 runLoopMulti satu-aset (baris 387-447 produksi):
// exit via exitStepPosition, entry via tryOpenPosition, ledger via
// applyLegPosition, metrik via buildResult — SEMUA produksi.
fun evRun(candles: List<Candle>, cache: Cache, startIdx: Int, params: BacktestParams,
    decide: (Int) -> SignalDecision, tag: String): BacktestResult {
    val a = LoopAsset(params.asset, candles, cache, startIdx)
    val cap = params.maxOpenTrades.coerceIn(MAX_OPEN_TRADES_MIN, MAX_OPEN_TRADES_MAX)
    val activeFilters = params.filters.filter { it.enabled }
    val trades = ArrayList<Trade>()
    val equityCurve = ArrayList<EquityPoint>()
    val st = EqState(params.initialCapital, params.initialCapital, 0.0)
    if (candles.isNotEmpty()) equityCurve.add(EquityPoint(candles.first().t, st.equity))
    val opens = ArrayList<OpenPos>()
    var seq = 0; var maxOpenObs = 0; var rejectedLimit = 0
    var evaluated = 0; var sigRaw = 0; var filtered = 0
    val passed = HashMap<String, Int>()
    val cnt = OpenCounters(); var badPnl = 0
    val reasons = HashMap<String, Int>()
    for (k in candles.indices) {
        val b = candles[k]
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
            val dec = decide(k)
            if (!dec.passed) continue
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
                }
            }
        }
    }
    val totalSlots = (candles.size - 1) - startIdx
    val d = BacktestDiag(
        rawCount = candles.size, normalizedCount = candles.size, startIdx = startIdx,
        evaluatedBars = evaluated, skippedInPosition = maxOf(0, totalSlots - evaluated),
        signalsRaw = sigRaw, filteredOut = filtered,
        maxOpenTrades = cap, maxOpenObserved = maxOpenObs, rejectedByLimit = rejectedLimit,
        skippedNoLevel = cnt.noLevel, skippedBadEntry = cnt.badEntry,
        skippedBadQty = cnt.badQty, skippedBadPnl = badPnl,
        filterReasons = reasons, filterPassed = passed,
        evalFrom = candles[startIdx].t, evalTo = candles[candles.size - 2].t,
        runId = "ev-$tag", finishedAt = System.currentTimeMillis())
    val span = candles.sortedBy { it.t }
    return buildResult(params, span, trades, equityCurve, st.maxDD, filtered, d)
}

fun evMsToDay(t: Long): String {
    if (t <= 0) return "-"
    return try {
        val f = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.format(java.util.Date(t))
    } catch (e: Exception) { t.toString() }
}

fun main() {
    println("=== A. Semantik mode (dari kode + paritas runtime) ===")
    // Bukti kode: Filters.kt decideAt — OR need=1 (LONG menang seri),
    // AND bulat, MAJORITY need=n/2+1 (n=2 → need=2 ≡ AND).
    val modes = listOf("OR", "AND", "MAJORITY")
    println("MODE-SEMANTIK kode: OR need=1 seri→LONG | AND bulat | MAJORITY need=n/2+1 (n=2 → need=2 ≡ AND)")

    println("=== B. Fetch data (getCandles PRODUKSI, sekali untuk semua varian) ===")
    val syms = listOf("SHIBUSDT", "PENGUUSDT", "ARBUSDT", "BTCUSDT", "AVAXUSDT",
        "SEIUSDT", "ADAUSDT", "SUIUSDT", "DOTUSDT", "BCHUSDT")
    data class DS(val sym: String, val raw: List<Any?>, val src: String, val n: Int, val t0: Long, val t1: Long)
    val data = ArrayList<DS>()
    val gagal = ArrayList<String>()
    for (s in syms) {
        try {
            val fr = getCandles("binance", s, "15m", 1000)
            val cs = fr.candles
            val raw = cs.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }
            val src = try { fr.meta.cacheUsed.ifEmpty { fr.meta.provider } } catch (e: Exception) { "?" }
            data.add(DS(s, raw, src, cs.size, cs.firstOrNull()?.t ?: 0L, cs.lastOrNull()?.t ?: 0L))
            println("FETCH-OK $s n=${cs.size} src=$src ${evMsToDay(cs.firstOrNull()?.t ?: 0L)}..${evMsToDay(cs.lastOrNull()?.t ?: 0L)}")
        } catch (e: Exception) {
            gagal.add("$s: ${e.message?.take(100)}")
            println("FETCH-FAIL $s: ${e.message?.take(120)}")
        }
    }
    if (data.isEmpty()) {
        println("EKSPERIMEN-BATAL: tidak ada data (jaringan/provider gagal) — tidak ada angka yang dilaporkan.")
        kotlin.system.exitProcess(2)
    }
    if (gagal.isNotEmpty()) println("PAIR-DIKELUARKAN: ${gagal.joinToString(" | ")}")

    // Parameter efektif (sanitasi produksi) — didokumentasikan, sama semua varian.
    val pProbe = sanitizeParams(BacktestParams(asset = "BTCUSDT", timeframe = "15m",
        strategy = "support_resistance",
        combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
        filters = emptyList(), maxOpenTrades = 1))
    println("PARAM-EFEKTIF fee=${pProbe.feePercent} slip=${pProbe.slippagePercent} sl=${pProbe.slPercent} " +
        "tp=${pProbe.tpPercent} mh=${pProbe.maxHolding} capAwal=${pProbe.initialCapital} " +
        "risk=${pProbe.riskPerTrade} lev=${pProbe.leverage} tpMode=${pProbe.tpMode} trail=${pProbe.trailOn} filter=0")

    // Siapkan per pair: normalisasi + warmup + cache persis produksi.
    data class PS(val sym: String, val candles: List<Candle>, val cache: Cache, val startIdx: Int, val src: String)
    val prepared = ArrayList<PS>()
    for (d in data) {
        val full = normalizeCandles(d.raw)
        val (prefix, inRange) = splitWarmup(full, 0L, 0L)
        if (inRange.size < MIN_CANDLES) {
            println("DATA-KURANG ${d.sym}: ${inRange.size} < $MIN_CANDLES — dilewati (bukan nol).")
            continue
        }
        val candles = prefix + inRange
        val cache = buildCache(candles)
        prepared.add(PS(d.sym, candles, cache, maxOf(prefix.size + WARMUP, 2), d.src))
    }
    if (prepared.isEmpty()) {
        println("EKSPERIMEN-BATAL: tidak ada pair memenuhi minimum candle.")
        kotlin.system.exitProcess(2)
    }

    println("=== C. Paritas replika vs produksi pada tol=0.001 (syarat sah ambang lain) ===")
    var parityOk = true
    for (ps in prepared) {
        for (mode in modes) {
            val pp = sanitizeParams(BacktestParams(asset = ps.sym, timeframe = "15m",
                strategy = "support_resistance",
                combo = Combo(mode, listOf("support_resistance", "mtf_confirm")),
                filters = emptyList(), maxOpenTrades = 1))
            // 1) arah per bar identik
            for (k in ps.startIdx until ps.candles.size - 1) {
                val dProd = decideAt(ps.candles, k, ps.cache, pp)
                val dRep = evDecideTol(mode, ps.candles, k, ps.cache, 0.001, pp.strategyParams)
                if (dProd.passed != dRep.passed || dProd.direction != dRep.direction) {
                    parityOk = false
                    println("PARITY-ARAH-GAGAL ${ps.sym} $mode bar=$k prod=${dProd.passed}/${dProd.direction} rep=${dRep.passed}/${dRep.direction}")
                    break
                }
            }
            if (!parityOk) break
            // 2) MAJORITY-2 ≡ AND pada semua bar
            if (mode == "MAJORITY") {
                val ppAnd = pp.copy(combo = Combo("AND", listOf("support_resistance", "mtf_confirm")))
                for (k in ps.startIdx until ps.candles.size - 1) {
                    val a = decideAt(ps.candles, k, ps.cache, pp)
                    val b = decideAt(ps.candles, k, ps.cache, ppAnd)
                    if (a.passed != b.passed || a.direction != b.direction) {
                        parityOk = false
                        println("PARITY-MAJ-GAGAL ${ps.sym} bar=$k")
                        break
                    }
                }
                if (!parityOk) break
            }
            // 3) trade identik (uid + net)
            val rProd = runBacktest(ps.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }, pp)
            if (rProd.error != null) {
                parityOk = false
                println("PARITY-PROD-ERROR ${ps.sym} $mode: ${rProd.error}")
                break
            }
            val rRep = evRun(ps.candles, ps.cache, ps.startIdx, pp,
                { k -> evDecideTol(mode, ps.candles, k, ps.cache, 0.001, pp.strategyParams) }, "par-${ps.sym}-$mode")
            val uProd = rProd.trades.map { it.uid }.sorted()
            val uRep = rRep.trades.map { it.uid }.sorted()
            val netEq = kotlin.math.abs(rProd.netProfit - rRep.netProfit) < 1e-6 * maxOf(1.0, kotlin.math.abs(rProd.netProfit))
            if (uProd != uRep || !netEq || rProd.totalTrades != rRep.totalTrades) {
                parityOk = false
                println("PARITY-TRADE-GAGAL ${ps.sym} $mode prod_tr=${rProd.totalTrades} rep_tr=${rRep.totalTrades} netP=${rProd.netProfit} netR=${rRep.netProfit}")
                break
            }
        }
        if (!parityOk) break
    }
    evOk("paritas-replika-001", parityOk, "arah+trade identik produksi pada tol=0.001")
    // Anti look-ahead: semua trade exit setelah entry.
    var laOk = true
    for (ps in prepared) {
        val pp = sanitizeParams(BacktestParams(asset = ps.sym, timeframe = "15m",
            strategy = "support_resistance",
            combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
            filters = emptyList(), maxOpenTrades = 1))
        val r = evRun(ps.candles, ps.cache, ps.startIdx, pp,
            { k -> evDecideTol("AND", ps.candles, k, ps.cache, 0.001, pp.strategyParams) }, "la-${ps.sym}")
        if (r.trades.any { it.exitTime <= it.entryTime }) { laOk = false; break }
    }
    evOk("tanpa-lookahead", laOk, "exitTime > entryTime semua trade")

    if (!parityOk) {
        println("AMBANG-LAIN-INVALID: paritas gagal — angka 0,15%/0,2% TIDAK dilaporkan.")
        println("EV-EVAL done pass=$evPass fail=$evFail")
        kotlin.system.exitProcess(1)
    }

    println("=== D. Matriks mode × ambang (kandidat dari decide aktual; trade dari loop terparitas) ===")
    println("varian | nPair | kandidat | trades(kaki) | wins | win% | PF | net-$ | fee-$ | maxDD%max | rejCap")
    data class VR(val variant: String, val cand: Int, val tr: Int, val w: Int, val wr: Double,
        val pf: Double, val net: Double, val fee: Double, val dd: Double, val rej: Int,
        val gp: Double, val gl: Double)
    val rows = ArrayList<VR>()
    for (mode in modes) {
        for (tol in listOf(0.001, 0.0015, 0.002)) {
            var cand = 0; var tr = 0; var w = 0; var gp = 0.0; var gl = 0.0
            var net = 0.0; var fee = 0.0; var dd = 0.0; var rej = 0
            val tag = "${mode}-${(tol * 100).toString().replace(".", "p")}"
            for (ps in prepared) {
                val pp = sanitizeParams(BacktestParams(asset = ps.sym, timeframe = "15m",
                    strategy = "support_resistance",
                    combo = Combo(mode, listOf("support_resistance", "mtf_confirm")),
                    filters = emptyList(), maxOpenTrades = 1))
                // Kandidat: hitung decide aktual pada rentang evaluasi produksi.
                var c = 0
                for (k in ps.startIdx until ps.candles.size - 1) {
                    val d = if (tol == 0.001) decideAt(ps.candles, k, ps.cache, pp)
                    else evDecideTol(mode, ps.candles, k, ps.cache, tol, pp.strategyParams)
                    if (d.passed) {
                        c++
                        if (d.direction != "LONG" && d.direction != "SHORT") {
                            println("ARAH-ANEH ${ps.sym} $tag bar=$k dir=${d.direction}")
                        }
                    }
                }
                cand += c
                val r = if (tol == 0.001) runBacktest(
                    ps.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }, pp)
                else evRun(ps.candles, ps.cache, ps.startIdx, pp,
                    { k -> evDecideTol(mode, ps.candles, k, ps.cache, tol, pp.strategyParams) }, "$tag-${ps.sym}")
                if (r.error != null) { println("RUN-ERROR ${ps.sym} $tag: ${r.error}"); continue }
                tr += r.totalTrades; w += r.wins; gp += r.grossProfit; gl += r.grossLoss
                net += r.netProfit; fee += r.trades.sumOf { it.fees }
                dd = maxOf(dd, r.maxDrawdownPercent); rej += r.diag.rejectedByLimit
                println("  $tag ${ps.sym} cand=$c tr=${r.totalTrades} w=${r.wins} wr=${evF2(r.winRate)} net=${evF2(r.netProfit)} dd=${evF2(r.maxDrawdownPercent)} rej=${r.diag.rejectedByLimit}")
            }
            val wr = if (tr > 0) w.toDouble() / tr * 100 else Double.NaN
            // Cermin rumus produksi (Backtest.kt:570): PF = laba / |rugi|.
            val pf = if (gl < 0) gp / -gl else if (gp > 0) Double.POSITIVE_INFINITY else 0.0
            rows.add(VR(tag, cand, tr, w, wr, pf, net, fee, dd, rej, gp, gl))
            println("$tag | ${prepared.size} | $cand | $tr | $w | ${evF2(wr)} | ${evF2(pf)} | ${evF2(net)} | ${evF2(fee)} | ${evF2(dd)} | $rej")
        }
    }

    println("=== E. Ringkasan rekomendasi (angka di atas; net-setelah-biaya + DD, bukan win% saja) ===")
    for (r in rows) {
        println("${r.variant}: net=${evF2(r.net)} PF=${evF2(r.pf)} wr=${evF2(r.wr)} dd=${evF2(r.dd)} tr=${r.tr} cand=${r.cand}")
    }
    println("EV-EVAL done pass=$evPass fail=$evFail")
    if (evFail > 0) kotlin.system.exitProcess(1)
}
