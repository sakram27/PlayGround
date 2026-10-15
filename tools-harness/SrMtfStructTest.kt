import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// KONFIRMASI STRUKTUR SR+MTF — BUKAN PRODUKSI.
// Aturan pra-spesifikasi (sebelum run, tak diubah setelah melihat hasil):
//   A = baseline AND+0,1%+MTF (decideAt + runPortfolioBacktest PRODUKSI).
//   B = A + BOS diagnostik saja (status BOS/CHoCH dicatat per kandidat &
//       per trade; tidak ada penolakan).
//   C = A + gerbang BOS: kandidat hanya lolos bila strategyFn("bos", k)
//       searah kandidat, pada bar k yang SAMA (tanpa data masa depan).
//   D = A + gerbang CHoCH: sama dengan strategyFn("choch", k).
//   Tanpa menggabungkan BOS+CHoCH sebagai syarat ganda; tanpa ubahan lain.
// Memakai ulang: mvRunPortfolio/evRun (cermin terparitas), seluruh primitif
// produksi. Audit Tahap-1: BOS = close menembus ekstrem 10-bar + body>0,2%
// (Strategies.kt:191-204); CHoCH = flip sisi EMA21 + close menembus ekstrem
// bar lalu (205-217); keduanya hanya indeks ≤ i; live & backtest memakai
// strategyFn yang SAMA via decideAt.
var stPass = 0
var stFail = 0
fun stOk(n: String, c: Boolean, x: String = "") {
    if (c) { stPass++; println("PASS $n $x") } else { stFail++; println("FAIL $n $x") }
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
        stOk("periode-${w.id}", winPD[w.id]!!.size == 10, "10/10 n=1056 startIdx=60")
    }
    stOk("periode-lengkap", fetchOk, "60/60 fetch OK")

    val ppBase = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
        strategy = "support_resistance",
        combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
        filters = emptyList(), maxOpenTrades = 10))
    println("PARAM-EFEKTIF fee=${ppBase.feePercent} slip=${ppBase.slippagePercent} sl=${ppBase.slPercent} " +
        "tp=${ppBase.tpPercent} mh=${ppBase.maxHolding} capAwal=${ppBase.initialCapital} " +
        "risk=${ppBase.riskPerTrade} lev=${ppBase.leverage} tpMode=${ppBase.tpMode} capPortofolio=10 filter=0")

    // Gate struktur pada bar k yang sama (tanpa masa depan).
    fun gateBos(pd: PD, k: Int, dir: String): Boolean =
        strategyFn("bos", pd.candles, k, pd.cache, emptyMap()).first == dir
    fun gateChoch(pd: PD, k: Int, dir: String): Boolean =
        strategyFn("choch", pd.candles, k, pd.cache, emptyMap()).first == dir

    println("atribusi win|pair|srL|srS|mtL|mtS|bosL|bosS|choL|choS|andN|bosYa|chochYa|didukung|ditentang|takJelas")
    println("var|win|cand|disaring|dibuka|trades|wins|win%|grossP|grossL|PF|net|fee|avgW|avgL|long|short|rej|expLeg|ddPort%|ddPairMax%")
    var mathOk = true
    for (w in windows) {
        val pds = winPD[w.id]!!
        // ---- Tahap 2: atribusi per kandidat baseline (produksi) ----
        data class AT(var srL: Int, var srS: Int, var mtL: Int, var mtS: Int, var bosL: Int,
            var bosS: Int, var choL: Int, var choS: Int, var andN: Int)
        val tot = AT(0, 0, 0, 0, 0, 0, 0, 0, 0)
        // Kaitan kandidat→trade baseline untuk Tahap 2 (didukung/ditentang/takJelas + pnl).
        data class CD(val sym: String, val k: Int, val dir: String, val bos: String, val choch: String)
        val cands = ArrayList<CD>()
        for (pd in pds) {
            val g = AT(0, 0, 0, 0, 0, 0, 0, 0, 0)
            for (k in pd.startIdx until pd.candles.size - 1) {
                val sr = strategyFn("support_resistance", pd.candles, k, pd.cache, emptyMap()).first
                val mt = strategyFn("mtf_confirm", pd.candles, k, pd.cache, emptyMap()).first
                val bo = strategyFn("bos", pd.candles, k, pd.cache, emptyMap()).first
                val ch = strategyFn("choch", pd.candles, k, pd.cache, emptyMap()).first
                if (sr == "LONG") g.srL++; if (sr == "SHORT") g.srS++
                if (mt == "LONG") g.mtL++; if (mt == "SHORT") g.mtS++
                if (bo == "LONG") g.bosL++; if (bo == "SHORT") g.bosS++
                if (ch == "LONG") g.choL++; if (ch == "SHORT") g.choS++
                val dec = decideAt(pd.candles, k, pd.cache, ppBase)
                if (dec.passed) { g.andN++; cands.add(CD(pd.sym, k, dec.direction, bo, ch)) }
            }
            println("ATRIB ${w.id}|${pd.sym}|${g.srL}|${g.srS}|${g.mtL}|${g.mtS}|${g.bosL}|${g.bosS}|" +
                "${g.choL}|${g.choS}|${g.andN}|0|0|0|0|0")
            tot.srL += g.srL; tot.srS += g.srS; tot.mtL += g.mtL; tot.mtS += g.mtS
            tot.bosL += g.bosL; tot.bosS += g.bosS; tot.choL += g.choL; tot.choS += g.choS; tot.andN += g.andN
        }
        println("ATRIB-TOT ${w.id}|sr=${tot.srL + tot.srS} mtf=${tot.mtL + tot.mtS} bos=${tot.bosL + tot.bosS} " +
            "choch=${tot.choL + tot.choS} and=${tot.andN}")
        // ---- Runs: A produksi; C/D gerbang pada bar sama ----
        data class VR(val r: BacktestResult, val cand: Int, val dropped: Int)
        val results = LinkedHashMap<String, VR>()
        run {
            var cand = 0
            for (pd in pds) for (k in pd.startIdx until pd.candles.size - 1)
                if (decideAt(pd.candles, k, pd.cache, ppBase).passed) cand++
            val pr = runPortfolioBacktest(pds.map { PortfolioAsset(it.sym,
                it.candles.map { c -> mapOf("t" to c.t, "o" to c.o, "h" to c.h, "l" to c.l, "c" to c.c, "v" to c.v) as Any? }) }, ppBase)
            if (pr.result.error != null) { println("RUN-ERROR ${w.id} A: ${pr.result.error}"); return@run }
            // Paritas kendaraan harness per jendela (syarat sah varian C/D).
            val mvp = pds.map { MVPair(it.sym, it.candles, it.cache, it.startIdx) }
            val hr = mvRunPortfolio(mvp, ppBase, "${w.id}-Apar") { a, k -> decideAt(a.candles, k, a.cache, ppBase) }
            val okPar = pr.result.trades.map { it.uid }.sorted() == hr.trades.map { it.uid }.sorted() &&
                kotlin.math.abs(pr.result.netProfit - hr.netProfit) < 1e-6 * maxOf(1.0, kotlin.math.abs(pr.result.netProfit))
            stOk("paritas-${w.id}-A", okPar, "tr=${pr.result.totalTrades}/${hr.totalTrades}")
            if (!okPar) mathOk = false
            results["A"] = VR(pr.result, cand, 0)
        }
        for ((id, gate) in listOf("C" to "bos", "D" to "choch")) {
            var cand = 0; var dropped = 0
            for (pd in pds) for (k in pd.startIdx until pd.candles.size - 1) {
                val dec = decideAt(pd.candles, k, pd.cache, ppBase)
                if (!dec.passed) continue
                cand++
                val ok = if (gate == "bos") gateBos(pd, k, dec.direction) else gateChoch(pd, k, dec.direction)
                if (!ok) dropped++
            }
            val mvp = pds.map { MVPair(it.sym, it.candles, it.cache, it.startIdx) }
            val r = mvRunPortfolio(mvp, ppBase, "${w.id}-$id") { a, k ->
                val pd = pds.first { it.sym == a.sym }
                val dec = decideAt(a.candles, k, a.cache, ppBase)
                if (!dec.passed) return@mvRunPortfolio SignalDecision(false, "NONE", 0.0, emptyList(), "exp")
                val ok = if (gate == "bos") gateBos(pd, k, dec.direction) else gateChoch(pd, k, dec.direction)
                if (ok) dec else SignalDecision(false, "NONE", 0.0, emptyList(), "exp")
            }
            if (r.trades.any { it.exitTime < it.entryTime }) { mathOk = false; println("LOOKAHEAD-GAGAL ${w.id} $id") }
            results[id] = VR(r, cand, dropped)
        }
        // B = diagnostik: hasil A + status struktur per trade yang dibuka.
        val rA = results["A"]!!.r
        data class BK(val sup: Double, val opp: Double, val unc: Double, val supN: Int, val oppN: Int, val uncN: Int)
        var bSup = 0.0; var bOpp = 0.0; var bUnc = 0.0; var bSupN = 0; var bOppN = 0; var bUncN = 0
        val tradeKey = rA.trades.map { "${it.asset}|${it.entryTime}" to it.pnl }
        for (cd in cands) {
            val entryT = pds.first { it.sym == cd.sym }.candles[cd.k + 1].t
            val legs = tradeKey.filter { it.first == "${cd.sym}|$entryT" }
            if (legs.isEmpty()) continue // kandidat tak dibuka (cap penuh)
            val pnl = legs.sumOf { it.second }
            val sup = cd.bos == cd.dir || cd.choch == cd.dir
            val opp = !sup && (cd.bos == (if (cd.dir == "LONG") "SHORT" else "LONG") ||
                cd.choch == (if (cd.dir == "LONG") "SHORT" else "LONG"))
            when {
                sup -> { bSup += pnl; bSupN++ }
                opp -> { bOpp += pnl; bOppN++ }
                else -> { bUnc += pnl; bUncN++ }
            }
        }
        println("B-ATRIB ${w.id}: didukung n=$bSupN pnl=${evF2(bSup)} | ditentang n=$bOppN pnl=${evF2(bOpp)} | " +
            "takJelas n=$bUncN pnl=${evF2(bUnc)} (pnl kaki layak dibuka, setelah biaya)")
        for ((id, vr) in results) {
            val r = vr.r
            var ddPairMax = 0.0
            for (pd in pds) {
                val rp = evRun(pd.candles, pd.cache, pd.startIdx, ppBase.copy(asset = pd.sym),
                    { k ->
                        val dec = decideAt(pd.candles, k, pd.cache, ppBase)
                        if (!dec.passed || id == "A") dec
                        else {
                            val ok = if (id == "C") gateBos(pd, k, dec.direction) else gateChoch(pd, k, dec.direction)
                            if (ok) dec else SignalDecision(false, "NONE", 0.0, emptyList(), "exp")
                        }
                    }, "dd-${w.id}-$id-${pd.sym}")
                ddPairMax = maxOf(ddPairMax, rp.maxDrawdownPercent)
                for (t in rp.trades) {
                    if (t.entryTime < pd.candles[pd.startIdx + 1].t || t.exitTime > pd.candles.last().t) {
                        mathOk = false; println("BATAS-GAGAL ${w.id} $id ${pd.sym}")
                    }
                }
            }
            if (kotlin.math.abs(r.netProfit - (r.grossProfit + r.grossLoss)) > 1e-6 * maxOf(1.0, kotlin.math.abs(r.netProfit))) {
                mathOk = false; println("MATH-GAGAL ${w.id} $id")
            }
            val longs = r.trades.count { it.direction == "LONG" }
            val shorts = r.trades.count { it.direction == "SHORT" }
            val opened = r.trades.map { "${it.asset}|${it.entryTime}" }.toSet().size
            val fee = r.trades.sumOf { it.fees }
            println("$id|${w.id}|${vr.cand}|${vr.dropped}|$opened|${r.totalTrades}|${r.wins}|${evF2(r.winRate)}|" +
                "${evF2(r.grossProfit)}|${evF2(r.grossLoss)}|${evF2(r.profitFactor)}|${evF2(r.netProfit)}|" +
                "${evF2(fee)}|${evF2(r.averageWin)}|${evF2(r.averageLoss)}|$longs|$shorts|" +
                "${r.diag.rejectedByLimit}|${r.expired}|${r.breakeven}|${evF2(r.maxDrawdownPercent)}|${evF2(ddPairMax)}")
        }
    }
    stOk("matematika & batas", mathOk, "net==gp+gl, dalam periode, tanpa look-ahead")
    println("STRUCT-EVAL done pass=$stPass fail=$stFail")
    if (stFail > 0) kotlin.system.exitProcess(1)
}
