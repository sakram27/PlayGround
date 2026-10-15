import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// AUDIT KELANGKAAN SR+MTF — BUKAN PRODUKSI.
// - Memakai ulang produksi (read-only): fetchRangeCandles, normalizeCandles,
//   splitWarmup, buildCache, strategyFn, decideAt, runPortfolioBacktest,
//   mvRunPortfolio/evRun/evDecideTol (harness terparitas sebelumnya).
// - Kode eksperimen: scSrNoDir (lepas syarat arah candle), scMtfNoRsi (lepas
//   pita RSI), scAnd (salinan 6-baris aturan bulat decideAt untuk 2 kaki).
//   Baseline AND+0,1% selalu memakai decideAt PRODUKSI.
// - W0 = pengembangan; W1..W3 = validasi. Ambang 0,1/0,15/0,2 pra-spesifikasi
//   pengguna (bukan hasil optimasi).
var scPass = 0
var scFail = 0
fun scOk(n: String, c: Boolean, x: String = "") {
    if (c) { scPass++; println("PASS $n $x") } else { scFail++; println("FAIL $n $x") }
}

// E1: S/R tanpa syarat arah candle (satu faktor diubah; jendela & tol sama).
// Prioritas dual-proximity (= dekat support DAN resistance sekaligus, range
// 20-bar < 0,2%) MEMPERTAHANKAN aturan produksi (arah candle sebagai
// tie-break, cermin when berurutan produksi) agar perubahan benar-benar
// satu faktor: pelonggaran arah di luar kasus seri.
fun scSrNoDir(c: List<Candle>, i: Int, tol: Double): Triple<String, Double, List<String>> {
    if (i < 20) return Triple("NONE", 0.0, emptyList())
    var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
    for (j in i - 20 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
    val nearS = ll > 0 && kotlin.math.abs(c[i].l - ll) / ll < tol
    val nearR = hh > 0 && kotlin.math.abs(c[i].h - hh) / hh < tol
    if (nearS && nearR) {
        // Seri: aturan produksi (hijau=LONG, merah=SHORT).
        return when {
            c[i].c > c[i].o -> Triple("LONG", 0.55, listOf("support proximity"))
            c[i].c < c[i].o -> Triple("SHORT", 0.55, listOf("resistance proximity"))
            else -> Triple("NONE", 0.0, emptyList())
        }
    }
    return when {
        nearS -> Triple("LONG", 0.55, listOf("support proximity"))
        nearR -> Triple("SHORT", 0.55, listOf("resistance proximity"))
        else -> Triple("NONE", 0.0, emptyList())
    }
}

// E2: MTF tanpa pita RSI (satu faktor diubah; sisi EMA50 dipertahankan).
fun scMtfNoRsi(c: List<Candle>, i: Int, x: Cache): Triple<String, Double, List<String>> {
    if (i < 50 || x.e50[i].isNaN() || x.rsi14[i].isNaN()) return Triple("NONE", 0.0, emptyList())
    return when {
        c[i].c > x.e50[i] -> Triple("LONG", 0.58, listOf("MTF ema-side"))
        c[i].c < x.e50[i] -> Triple("SHORT", 0.58, listOf("MTF ema-side"))
        else -> Triple("NONE", 0.0, emptyList())
    }
}

// Salinan aturan bulat decideAt untuk 2 kaki (Filters.kt:186-189).
fun scAnd(a: Triple<String, String, Double>, b: Triple<String, String, Double>): SignalDecision {
    val longs = listOf(a, b).filter { it.second == "LONG" }
    val shorts = listOf(a, b).filter { it.second == "SHORT" }
    if (longs.size == 2) return SignalDecision(true, "LONG", longs.map { it.third }.average(), listOf("exp-long"), "exp")
    if (shorts.size == 2) return SignalDecision(true, "SHORT", shorts.map { it.third }.average(), listOf("exp-short"), "exp")
    return SignalDecision(false, "NONE", 0.0, emptyList(), "exp")
}

fun main() {
    val syms = listOf("SHIBUSDT", "PENGUUSDT", "ARBUSDT", "BTCUSDT", "AVAXUSDT",
        "SEIUSDT", "ADAUSDT", "SUIUSDT", "DOTUSDT", "BCHUSDT")
    data class W(val id: String, val role: String, val from: Long, val to: Long)
    fun utc(y: Int, m: Int, d: Int): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(y, m - 1, d, 0, 0, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    val windows = listOf(
        W("W0", "DEV", utc(2026, 10, 4), utc(2026, 10, 14) + 86399000L),
        W("W1", "VAL", utc(2026, 9, 22), utc(2026, 10, 2) + 86399000L),
        W("W2", "VAL", utc(2026, 9, 10), utc(2026, 9, 20) + 86399000L),
        W("W3", "VAL", utc(2026, 8, 29), utc(2026, 9, 8) + 86399000L))

    // ===== TAHAP 1: periode, pemanasan vs evaluasi, kesamaan antar varian =====
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
                if (prefix.isNotEmpty()) println("WARMUP-ANEH ${w.id} $s prefix=${prefix.size} (ekspektasi 0)")
                val candles = prefix + inRange
                val startIdx = maxOf(prefix.size + WARMUP, 2)
                if (startIdx > candles.size - 2) { println("DATA-KURANG ${w.id} $s"); continue }
                winPD[w.id]!!.add(PD(s, candles, buildCache(candles), startIdx))
            } catch (e: Exception) {
                fetchOk = false
                println("FETCH-FAIL ${w.id} $s: ${e.message?.take(120)}")
            }
        }
        val pds = winPD[w.id]!!
        if (pds.size != 10) { fetchOk = false; println("PERIODE-GAGAL ${w.id}: hanya ${pds.size}/10 pair") }
        else {
            val n0 = pds.first().candles.size
            val sameN = pds.all { it.candles.size == n0 }
            val sameEval = pds.all { it.startIdx == pds.first().startIdx }
            println("PERIODE ${w.id}: n=$n0/pair (11 hari × 96 = 1056 nominal) startIdx=${pds.first().startIdx} " +
                "eval=[${mvDay(pds.first().candles[pds.first().startIdx].t)}..${mvDay(pds.first().candles[n0 - 2].t)}] " +
                "samaN=$sameN samaEval=$sameEval " +
                "firstT=${pds.first().candles.first().t} lastT=${pds.first().candles.last().t}")
            scOk("periode-${w.id}", sameN && sameEval && n0 == 1056, "n=$n0")
        }
    }
    scOk("periode-lengkap", fetchOk, "40/40 fetch OK")

    // ===== TAHAP 2: dekomposisi kelangkaan (baseline AND 0,1%, decideAt produksi) =====
    println("dekomposisi win|pair|evalBar|srL|srS|mtL|mtS|sepakat|konflik|srDiam|mtfDiam|keduanyaDiam|andLolos")
    data class DG(val srL: Int, val srS: Int, val mtL: Int, val mtS: Int, val agree: Int,
        val conflict: Int, val srSilent: Int, val mtSilent: Int, val bothSilent: Int, val andPass: Int,
        val dirBlockL: Int, val dirBlockS: Int, val proxMiss: Int, val bandBlock: Int, val noSide: Int)
    for (w in windows) {
        var t = DG(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val pp = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
            strategy = "support_resistance",
            combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
            filters = emptyList(), maxOpenTrades = 10))
        for (pd in winPD[w.id]!!) {
            var g = DG(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            for (k in pd.startIdx until pd.candles.size - 1) {
                val c = pd.candles; val x = pd.cache
                val sr = strategyFn("support_resistance", c, k, x, emptyMap()).first
                val mt = strategyFn("mtf_confirm", c, k, x, emptyMap()).first
                val srL = if (sr == "LONG") 1 else 0; val srS = if (sr == "SHORT") 1 else 0
                val mtL = if (mt == "LONG") 1 else 0; val mtS = if (mt == "SHORT") 1 else 0
                val agree = if ((sr == "LONG" && mt == "LONG") || (sr == "SHORT" && mt == "SHORT")) 1 else 0
                val conflict = if ((sr == "LONG" && mt == "SHORT") || (sr == "SHORT" && mt == "LONG")) 1 else 0
                val srS_ = if (sr == "NONE") 1 else 0; val mtS_ = if (mt == "NONE") 1 else 0
                // Sub-klasifikasi SR NONE: arah-terhalang vs jarak-meleset.
                var dbl = 0; var dbs = 0; var pm = 0
                if (sr == "NONE" && k >= 20) {
                    var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
                    for (j in k - 20 until k) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
                    val nearS = ll > 0 && kotlin.math.abs(c[k].l - ll) / ll < 0.001
                    val nearR = hh > 0 && kotlin.math.abs(c[k].h - hh) / hh < 0.001
                    if (nearS && c[k].c <= c[k].o) dbl = 1
                    else if (nearR && c[k].c >= c[k].o) dbs = 1
                    else if (!nearS && !nearR) pm = 1
                }
                // Sub-klasifikasi MTF NONE: sisi-OK-tapi-pita-gagal vs tanpa-sisi.
                var bb = 0; var ns = 0
                if (mt == "NONE" && k >= 50 && !x.e50[k].isNaN() && !x.rsi14[k].isNaN()) {
                    val longSide = c[k].c > x.e50[k]; val shortSide = c[k].c < x.e50[k]
                    val bandOk = (longSide && x.rsi14[k] > 55 && x.rsi14[k] < 75) ||
                        (shortSide && x.rsi14[k] < 45 && x.rsi14[k] > 25)
                    if ((longSide || shortSide) && !bandOk) bb = 1
                    else if (!longSide && !shortSide) ns = 1
                }
                val andP = if (decideAt(c, k, x, pp).passed) 1 else 0
                g = DG(g.srL + srL, g.srS + srS, g.mtL + mtL, g.mtS + mtS, g.agree + agree,
                    g.conflict + conflict, g.srSilent + srS_, g.mtSilent + mtS_,
                    g.bothSilent + (if (srS_ == 1 && mtS_ == 1) 1 else 0), g.andPass + andP,
                    g.dirBlockL + dbl, g.dirBlockS + dbs, g.proxMiss + pm, g.bandBlock + bb, g.noSide + ns)
            }
            val evalN = pd.candles.size - 1 - pd.startIdx
            println("DBG ${w.id}|${pd.sym}|$evalN|${g.srL}|${g.srS}|${g.mtL}|${g.mtS}|${g.agree}|" +
                "${g.conflict}|${g.srSilent}|${g.mtSilent}|${g.bothSilent}|${g.andPass}")
            t = DG(t.srL + g.srL, t.srS + g.srS, t.mtL + g.mtL, t.mtS + g.mtS, t.agree + g.agree,
                t.conflict + g.conflict, t.srSilent + g.srSilent, t.mtSilent + g.mtSilent,
                t.bothSilent + g.bothSilent, t.andPass + g.andPass,
                t.dirBlockL + g.dirBlockL, t.dirBlockS + g.dirBlockS, t.proxMiss + g.proxMiss,
                t.bandBlock + g.bandBlock, t.noSide + g.noSide)
        }
        val evalTot = winPD[w.id]!!.sumOf { it.candles.size - 1 - it.startIdx }
        println("TOT ${w.id}|$evalTot|sr=${t.srL + t.srS} mtf=${t.mtL + t.mtS} sepakat=${t.agree} " +
            "konflik=${t.conflict} srDiam=${t.srSilent} mtfDiam=${t.mtSilent} keduanyaDiam=${t.bothSilent} " +
            "andLolos=${t.andPass} arahTerhalang=${t.dirBlockL + t.dirBlockS} jarakMeleset=${t.proxMiss} " +
            "pitaGagal=${t.bandBlock} tanpaSisi=${t.noSide}")
    }

    // ===== TAHAP 4: eksperimen satu faktor (BASE vs E1 vs E2), portofolio =====
    println("exp|win|cand|dibuka|trades|wins|win%|grossP|grossL|PF|net|fee|avgW|avgL|long|short|rej|expLeg|ddPort%|ddPairMax%")
    data class EX(val id: String, val srTol: (List<Candle>, Int, Cache) -> Triple<String, Double, List<String>>,
        val mt: (List<Candle>, Int, Cache) -> Triple<String, Double, List<String>>)
    val exps = listOf(
        EX("BASE", { c, i, x -> Triple(strategyFn("support_resistance", c, i, x, emptyMap()).first, 0.55, emptyList()) },
            { c, i, x -> Triple(strategyFn("mtf_confirm", c, i, x, emptyMap()).first, 0.58, emptyList()) }),
        EX("E1", { c, i, _ -> scSrNoDir(c, i, 0.001) },
            { c, i, x -> Triple(strategyFn("mtf_confirm", c, i, x, emptyMap()).first, 0.58, emptyList()) }),
        EX("E2", { c, i, x -> Triple(strategyFn("support_resistance", c, i, x, emptyMap()).first, 0.55, emptyList()) },
            { c, i, x -> scMtfNoRsi(c, i, x) }))
    var monoOk = true
    for (w in windows) {
        val pds = winPD[w.id]!!
        // Cache per pair sudah jadi; BASE memakai decideAt produksi langsung.
        for (e in exps) {
            val pp = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
                strategy = "support_resistance",
                combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
                filters = emptyList(), maxOpenTrades = 10))
            fun decFor(paSym: String, candles: List<Candle>, cache: Cache, k: Int): SignalDecision {
                if (e.id == "BASE") return decideAt(candles, k, cache, pp)
                val a = e.srTol(candles, k, cache); val b = e.mt(candles, k, cache)
                return scAnd(Triple("sr", a.first, a.second), Triple("mtf", b.first, b.second))
            }
            var cand = 0
            for (pd in pds) {
                for (k in pd.startIdx until pd.candles.size - 1) {
                    if (decFor(pd.sym, pd.candles, pd.cache, k).passed) cand++
                }
            }
            val mvp = pds.map { MVPair(it.sym, it.candles, it.cache, it.startIdx) }
            val r = mvRunPortfolio(mvp, pp, "${w.id}-${e.id}") { a, k -> decFor(a.sym, a.candles, a.cache, k) }
            // Exit se-candle dengan entry (entry di open, SL/TP kena bar sama)
            // SAH di produksi (exitStepPosition bar entryIdx); hanya mundur yang dilarang.
            if (r.trades.any { it.exitTime < it.entryTime }) { monoOk = false; println("LOOKAHEAD-GAGAL ${w.id} ${e.id}") }
            // Batas evaluasi: entry & exit di dalam candle evaluasi.
            for (pd in pds) {
                val lo = pd.candles[pd.startIdx + 1].t; val hi = pd.candles.last().t
                if (r.trades.any { it.asset == pd.sym && (it.entryTime < lo || it.exitTime > hi) }) {
                    monoOk = false; println("BATAS-GAGAL ${w.id} ${e.id} ${pd.sym}")
                }
            }
            var ddPairMax = 0.0
            for (pd in pds) {
                val rp = evRun(pd.candles, pd.cache, pd.startIdx, pp.copy(asset = pd.sym),
                    { k -> decFor(pd.sym, pd.candles, pd.cache, k) }, "dd-${w.id}-${e.id}-${pd.sym}")
                ddPairMax = maxOf(ddPairMax, rp.maxDrawdownPercent)
            }
            val mathOk = kotlin.math.abs(r.netProfit - (r.grossProfit + r.grossLoss)) < 1e-6 * maxOf(1.0, kotlin.math.abs(r.netProfit))
            if (!mathOk) { monoOk = false; println("MATH-GAGAL ${w.id} ${e.id}") }
            val longs = r.trades.count { it.direction == "LONG" }
            val shorts = r.trades.count { it.direction == "SHORT" }
            val opened = r.trades.map { "${it.asset}|${it.entryTime}" }.toSet().size
            val fee = r.trades.sumOf { it.fees }
            println("${e.id}|${w.id}|$cand|$opened|${r.totalTrades}|${r.wins}|${evF2(r.winRate)}|" +
                "${evF2(r.grossProfit)}|${evF2(r.grossLoss)}|${evF2(r.profitFactor)}|${evF2(r.netProfit)}|" +
                "${evF2(fee)}|${evF2(r.averageWin)}|${evF2(r.averageLoss)}|$longs|$shorts|" +
                "${r.diag.rejectedByLimit}|${r.expired}|${r.breakeven}|${evF2(r.maxDrawdownPercent)}|${evF2(ddPairMax)}")
        }
    }
    // Monotonisitas sanity: pelonggaran tak boleh mengurangi kandidat.
    // (Dicek manual dari tabel; tidak diasertakan kaku karena E1/E2 definisi beda.)
    // ===== Monotonisitas: pelonggaran satu faktor tak boleh mengurangi kandidat.
    // (Konstruksi E1/E2 menjamin superset per arah; assert runtime jujur.)
    var monoCandOk = true
    for (w in windows) {
        val pds = winPD[w.id]!!
        val pp = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
            strategy = "support_resistance",
            combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
            filters = emptyList(), maxOpenTrades = 10))
        var cB = 0; var c1 = 0; var c2 = 0
        for (pd in pds) {
            for (k in pd.startIdx until pd.candles.size - 1) {
                if (decideAt(pd.candles, k, pd.cache, pp).passed) cB++
                val a1 = scSrNoDir(pd.candles, k, 0.001)
                val b1 = Triple(strategyFn("mtf_confirm", pd.candles, k, pd.cache, emptyMap()).first, 0.58, emptyList<String>())
                if (scAnd(Triple("sr", a1.first, a1.second), Triple("mtf", b1.first, b1.second)).passed) c1++
                val a2 = Triple(strategyFn("support_resistance", pd.candles, k, pd.cache, emptyMap()).first, 0.55, emptyList<String>())
                val b2 = scMtfNoRsi(pd.candles, k, pd.cache)
                if (scAnd(Triple("sr", a2.first, a2.second), Triple("mtf", b2.first, b2.second)).passed) c2++
            }
        }
        println("MONO ${w.id}: BASE=$cB E1=$c1 E2=$c2")
        if (c1 < cB || c2 < cB) { monoCandOk = false; println("MONO-GAGAL ${w.id}") }
    }
    scOk("monotonisitas-pelonggaran", monoCandOk, "E1,E2 kandidat >= BASE tiap jendela")
    scOk("batas-evaluasi & matematika", monoOk, "tanpa look-ahead, dalam periode, net==gp+gl")
    println("SCARCE-EVAL done pass=$scPass fail=$scFail")
    if (scFail > 0) kotlin.system.exitProcess(1)
}
