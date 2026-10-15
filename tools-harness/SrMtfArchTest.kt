import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// ARSITEKTUR ENTRY ALTERNATIF — BUKAN PRODUKSI.
// Definisi varian (dikomit sebelum run; tak diubah setelah melihat hasil):
//   A = baseline: decideAt AND(sr,mtf) + runPortfolioBacktest PRODUKSI.
//   B = bias EMA50 (sisi tren, indikator produksi, TF sama) + trigger SR
//       produksi searah bias. SENGAJA berbeda dari AND: melepas pita RSI +
//       timing sinyal MTF. Kebedaannya dibuktikan via selisih himpunan kandidat.
//   C = keputusan AND produksi + evalFilter("adx", min=20.0 default produksi)
//       sebagai konfirmasi tunggal (ekuivalen menyalakan filter ADX).
//   D = trigger MTF produksi + konteks proximity-SR searah (scSrNoDir harness
//       teruji sebelumnya, tanpa syarat arah candle) pada bar yang SAMA.
//   E = referensi OR produksi (dijalankan ulang identik, bukan angka lama).
// Memakai ulang: seluruh primitif produksi + mvRunPortfolio/evRun terparitas.
// Tanpa data masa depan: semua evaluasi memakai candle ≤ k, entry open k+1.
var arPass = 0
var arFail = 0
fun arOk(n: String, c: Boolean, x: String = "") {
    if (c) { arPass++; println("PASS $n $x") } else { arFail++; println("FAIL $n $x") }
}
fun arPf(pf: Double, w: Int, l: Int): String = when {
    pf.isInfinite() && pf > 0 -> "Inf(${w}W${l}L)"
    pf.isNaN() -> "NA(${w}W${l}L)"
    else -> evF2(pf)
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
    println("ATURAN-DIKOMIT: A=AND prod; B=biasEMA50+triggerSR; C=AND+evalFilter(adx,20.0); " +
        "D=triggerMTF+konteksProximitySR; E=OR prod. Tanpa BOS/CHoCH wajib.")

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
        arOk("periode-${w.id}", winPD[w.id]!!.size == 10, "10/10 n=1056 startIdx=60")
    }
    arOk("periode-lengkap", fetchOk, "60/60 fetch OK")

    val ppBase = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
        strategy = "support_resistance",
        combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
        filters = emptyList(), maxOpenTrades = 10))
    val ppOr = ppBase.copy(combo = Combo("OR", listOf("support_resistance", "mtf_confirm")))
    println("PARAM-EFEKTIF fee=${ppBase.feePercent} slip=${ppBase.slippagePercent} sl=${ppBase.slPercent} " +
        "tp=${ppBase.tpPercent} mh=${ppBase.maxHolding} capAwal=${ppBase.initialCapital} " +
        "risk=${ppBase.riskPerTrade} lev=${ppBase.leverage} tpMode=${ppBase.tpMode} capPortofolio=10 filter=0")

    // Decide per varian (B/C/D memakai komponen produksi; tak ada masa depan).
    fun decA(pd: PD, k: Int): SignalDecision = decideAt(pd.candles, k, pd.cache, ppBase)
    fun decE(pd: PD, k: Int): SignalDecision = decideAt(pd.candles, k, pd.cache, ppOr)
    fun decB(pd: PD, k: Int): SignalDecision {
        if (k < 50 || pd.cache.e50[k].isNaN()) return SignalDecision(false, "NONE", 0.0, emptyList(), "exp-b")
        val sr = strategyFn("support_resistance", pd.candles, k, pd.cache, emptyMap())
        val allowL = pd.candles[k].c > pd.cache.e50[k]
        val allowS = pd.candles[k].c < pd.cache.e50[k]
        return when {
            sr.first == "LONG" && allowL -> SignalDecision(true, "LONG", sr.second, sr.third, "bias+sr")
            sr.first == "SHORT" && allowS -> SignalDecision(true, "SHORT", sr.second, sr.third, "bias+sr")
            else -> SignalDecision(false, "NONE", 0.0, emptyList(), "exp-b")
        }
    }
    fun decC(pd: PD, k: Int): SignalDecision {
        val base = decideAt(pd.candles, k, pd.cache, ppBase)
        if (!base.passed) return base
        // Konfirmasi tunggal = fungsi filter ADX PRODUKSI + default produksinya.
        val (ok, _) = evalFilter("adx", pd.candles, k, pd.cache, base.direction,
            mapOf("min" to 20.0), null, FilterCtx())
        return if (ok) base else SignalDecision(false, "NONE", 0.0, emptyList(), "exp-c")
    }
    fun decD(pd: PD, k: Int): SignalDecision {
        val mt = strategyFn("mtf_confirm", pd.candles, k, pd.cache, emptyMap())
        if (mt.first == "NONE") return SignalDecision(false, "NONE", 0.0, emptyList(), "exp-d")
        val ctx = scSrNoDir(pd.candles, k, 0.001)
        return if (ctx.first == mt.first)
            SignalDecision(true, mt.first, mt.second, mt.third, "mtf+srctx")
        else SignalDecision(false, "NONE", 0.0, emptyList(), "exp-d")
    }
    val decMap = mapOf("A" to ::decA, "B" to ::decB, "C" to ::decC, "D" to ::decD, "E" to ::decE)

    println("var|win|cand|disaring|dibuka|trades|wins|win%|grossP|grossL|PF|net|fee|avgW|avgL|long|short|rej|expLeg|ddPort%|ddPairMax%")
    var mathOk = true
    for (w in windows) {
        val pds = winPD[w.id]!!
        // Reproduksibilitas baseline: dua run produksi identik.
        run {
            fun uids(): List<String> {
                val pr = runPortfolioBacktest(pds.map { PortfolioAsset(it.sym,
                    it.candles.map { c -> mapOf("t" to c.t, "o" to c.o, "h" to c.h, "l" to c.l, "c" to c.c, "v" to c.v) as Any? }) }, ppBase)
                return pr.result.trades.map { it.uid }.sorted() + listOf("NET=${pr.result.netProfit}")
            }
            arOk("reproduksibel-${w.id}", uids() == uids(), "run ganda identik")
        }
        // Kebedaan B vs baseline: selisih himpunan kandidat (bukti bukan varian identik).
        run {
            var shared = 0; var onlyB = 0; var onlyA = 0
            for (pd in pds) {
                for (k in pd.startIdx until pd.candles.size - 1) {
                    val a = decA(pd, k).passed
                    val b = decB(pd, k).passed
                    if (a && b) shared++ else if (b) onlyB++ else if (a) onlyA++
                }
            }
            println("DISTINCT ${w.id}: A∩B=$shared hanyaB=$onlyB hanyaA=$onlyA")
            if (shared > 0 && onlyB == 0 && onlyA == 0) {
                mathOk = false; println("IDENTIK-GAGAL ${w.id}: B sama persis dengan A")
            }
        }
        for ((id, dec) in decMap) {
            val pp = if (id == "E") ppOr else ppBase
            var cand = 0
            for (pd in pds) for (k in pd.startIdx until pd.candles.size - 1)
                if (dec(pd, k).passed) cand++
            val mvp = pds.map { MVPair(it.sym, it.candles, it.cache, it.startIdx) }
            val r = if (id == "A" || id == "E") {
                val pr = runPortfolioBacktest(pds.map { PortfolioAsset(it.sym,
                    it.candles.map { c -> mapOf("t" to c.t, "o" to c.o, "h" to c.h, "l" to c.l, "c" to c.c, "v" to c.v) as Any? }) }, pp)
                if (pr.result.error != null) { println("RUN-ERROR ${w.id} $id: ${pr.result.error}"); continue }
                // Paritas kendaraan harness (syarat sah C/D yang memakainya).
                val hr = mvRunPortfolio(mvp, pp, "${w.id}-$id-par") { a, k -> dec(pds.first { it.sym == a.sym }, k) }
                val okPar = pr.result.trades.map { it.uid }.sorted() == hr.trades.map { it.uid }.sorted() &&
                    kotlin.math.abs(pr.result.netProfit - hr.netProfit) < 1e-6 * maxOf(1.0, kotlin.math.abs(pr.result.netProfit))
                arOk("paritas-${w.id}-$id", okPar, "tr=${pr.result.totalTrades}")
                if (!okPar) mathOk = false
                pr.result
            } else {
                mvRunPortfolio(mvp, pp, "${w.id}-$id") { a, k -> dec(pds.first { it.sym == a.sym }, k) }
            }
            if (r.trades.any { it.exitTime < it.entryTime }) { mathOk = false; println("LOOKAHEAD-GAGAL ${w.id} $id") }
            var ddPairMax = 0.0
            for (pd in pds) {
                val rp = evRun(pd.candles, pd.cache, pd.startIdx, pp.copy(asset = pd.sym),
                    { k -> dec(pd, k) }, "dd-${w.id}-$id-${pd.sym}")
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
            // Kandidat disaring khusus varian bergerbang (C/D): lolos-AND tapi gagal konfirmasi.
            var dropped = 0
            if (id == "C" || id == "D") {
                for (pd in pds) for (k in pd.startIdx until pd.candles.size - 1) {
                    val base = decideAt(pd.candles, k, pd.cache, ppBase)
                    if (base.passed && !dec(pd, k).passed) dropped++
                }
            }
            println("$id|${w.id}|$cand|$dropped|$opened|${r.totalTrades}|${r.wins}|${evF2(r.winRate)}|" +
                "${evF2(r.grossProfit)}|${evF2(r.grossLoss)}|${arPf(r.profitFactor, r.wins, r.totalTrades - r.wins)}|" +
                "${evF2(r.netProfit)}|${evF2(fee)}|${evF2(r.averageWin)}|${evF2(r.averageLoss)}|$longs|$shorts|" +
                "${r.diag.rejectedByLimit}|${r.expired}|${r.breakeven}|${evF2(r.maxDrawdownPercent)}|${evF2(ddPairMax)}")
        }
    }
    arOk("matematika & batas", mathOk, "paritas+net==gp+gl+dalam-periode+tanpa-lookahead")
    println("ARCH-EVAL done pass=$arPass fail=$arFail")
    if (arFail > 0) kotlin.system.exitProcess(1)
}
