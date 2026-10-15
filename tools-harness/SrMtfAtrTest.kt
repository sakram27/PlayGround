import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// ATR-ADAPTIF S/R — BUKAN PRODUKSI. Aturan main:
// - decideAt/strategyFn/runBacktest/runPortfolioBacktest/fee/slip/sizing/entry/
//   exit SEMUA produksi, read-only. Baseline A memakai decideAt PRODUKSI.
// - Satu-satunya kode eksperimen: scSrAtr (cabang S/R dengan ambang adaptif)
//   + scAndA (salinan aturan bulat AND 2-kaki). MTF = strategyFn PRODUKSI utuh
//   (termasuk pita RSI + syarat arah candle S/R dipertahankan).
// - Rumus & faktor DIKOMIT SEBELUM membaca hasil validasi (lihat bawah).
//   Tidak ada fitting pada periode validasi.
//
// RUMUS (satuan persen, skala sama dengan 0,1% tetap):
//   atrPct(i) = atr14[i] / close[i] * 100   (atr14 = Wilder RMA-14 produksi,
//               hanya memakai candle ≤ i — tanpa masa depan)
//   tol(i) = clamp(k * atrPct(i), MIN_TOL, MAX_TOL)
//   ATR% teramati sebelumnya 0,5–0,9% → 0,1% tetap ≈ 0,11–0,2× ATR.
//   B KETAT   k=0.15  min 0,05% max 0,30%  (≈0,08–0,14% tipikal)
//   C SEDANG  k=0.25  min 0,05% max 0,40%  (≈0,13–0,23% tipikal)
//   D LONGGAR k=0.35  min 0,05% max 0,50%  (≈0,18–0,33% tipikal)
//   atr/close tak-valid → jatuh kembali ke 0,1% tetap (jujur, terhitung).
var atPass = 0
var atFail = 0
fun atOk(n: String, c: Boolean, x: String = "") {
    if (c) { atPass++; println("PASS $n $x") } else { atFail++; println("FAIL $n $x") }
}

data class AtrCfg(val id: String, val k: Double, val minTol: Double, val maxTol: Double)

// Ambang adaptif bar ini (persen). Mengembalikan tol + flag guard.
fun scTolAtr(c: List<Candle>, i: Int, x: Cache, cfg: AtrCfg): Pair<Double, String> {
    val a = x.atr14[i]; val px = c[i].c
    if (!a.isFinite() || !px.isFinite() || a <= 0 || px <= 0)
        return 0.001 to "fallback"
    val atrPct = a / px * 100.0
    if (!atrPct.isFinite() || atrPct <= 0) return 0.001 to "fallback"
    val raw = cfg.k * atrPct / 100.0
    return when {
        raw < cfg.minTol -> cfg.minTol to "min"
        raw > cfg.maxTol -> cfg.maxTol to "max"
        else -> raw to "ok"
    }
}

// Cabang S/R dengan ambang adaptif; jendela 20 bar + arah candle SAMA
// dengan produksi (Strategies.kt:135-147), hanya 0.001 → tol(i).
fun scSrAtr(c: List<Candle>, i: Int, x: Cache, cfg: AtrCfg,
    guard: MutableMap<String, Int>? = null): Triple<String, Double, List<String>> {
    if (i < 20) return Triple("NONE", 0.0, emptyList())
    var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
    for (j in i - 20 until i) { hh = maxOf(hh, c[j].h); ll = minOf(ll, c[j].l) }
    val (tol, g) = scTolAtr(c, i, x, cfg)
    if (guard != null) guard[g] = (guard[g] ?: 0) + 1
    return when {
        ll > 0 && kotlin.math.abs(c[i].l - ll) / ll < tol && c[i].c > c[i].o ->
            Triple("LONG", 0.55, listOf("support bounce atr"))
        hh > 0 && kotlin.math.abs(c[i].h - hh) / hh < tol && c[i].c < c[i].o ->
            Triple("SHORT", 0.55, listOf("resistance reject atr"))
        else -> Triple("NONE", 0.0, emptyList())
    }
}

// Salinan aturan bulat AND 2-kaki (Filters.kt:186-189).
fun scAndA(a: Triple<String, String, Double>, b: Triple<String, String, Double>): SignalDecision {
    val longs = listOf(a, b).filter { it.second == "LONG" }
    val shorts = listOf(a, b).filter { it.second == "SHORT" }
    if (longs.size == 2) return SignalDecision(true, "LONG", longs.map { it.third }.average(), listOf("exp-long"), "exp")
    if (shorts.size == 2) return SignalDecision(true, "SHORT", shorts.map { it.third }.average(), listOf("exp-short"), "exp")
    return SignalDecision(false, "NONE", 0.0, emptyList(), "exp")
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
    val cfgs = listOf(
        AtrCfg("B", 0.15, 0.0005, 0.0030),
        AtrCfg("C", 0.25, 0.0005, 0.0040),
        AtrCfg("D", 0.35, 0.0005, 0.0050))
    println("RUMUS-DIKOMIT: tol=k*atrPct clamp[min,max]; " +
        cfgs.joinToString(" ") { "${it.id}:k=${it.k} [${it.minTol * 100},${it.maxTol * 100}]%" })

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
                if (startIdx > candles.size - 2 || prefix.isNotEmpty()) {
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
        val pds = winPD[w.id]!!
        val n0 = pds.firstOrNull()?.candles?.size ?: 0
        println("PERIODE ${w.id}(${w.role}): ${pds.size}/10 pair n=$n0 " +
            "eval=[${mvDay(pds.firstOrNull()?.candles?.getOrNull(pds.firstOrNull()?.startIdx ?: 0)?.t ?: 0L)}.." +
            "${mvDay(pds.firstOrNull()?.candles?.getOrNull(n0 - 2)?.t ?: 0L)}]")
        atOk("periode-${w.id}", pds.size == 10 && pds.all { it.candles.size == n0 && it.startIdx == 60 }, "n=$n0")
    }
    atOk("periode-lengkap", fetchOk, "60/60 fetch OK")

    // Rezim deskriptif dari data.
    for (w in windows) {
        val ds = winPD[w.id]!!.map { pd ->
            val drift = pd.candles.last().c / pd.candles.first().c - 1
            val a = pd.cache.atr14[pd.candles.size - 2]
            drift to (if (a.isFinite() && a > 0) a / pd.candles.last().c * 100 else Double.NaN)
        }
        val med = ds.map { it.first }.sorted().let { it[it.size / 2] * 100 }
        val ma = ds.mapNotNull { it.second.takeIf { v -> v.isFinite() } }.average()
        val arah = if (med >= 3) "NAIK" else if (med <= -3) "TURUN" else "DATAR"
        println("REZIM ${w.id}: medDrift=${evF2(med)}% atrAkhir=${evF2(ma)}% → $arah")
    }

    val ppBase = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
        strategy = "support_resistance",
        combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
        filters = emptyList(), maxOpenTrades = 10))
    println("PARAM-EFEKTIF fee=${ppBase.feePercent} slip=${ppBase.slippagePercent} sl=${ppBase.slPercent} " +
        "tp=${ppBase.tpPercent} mh=${ppBase.maxHolding} capAwal=${ppBase.initialCapital} " +
        "risk=${ppBase.riskPerTrade} lev=${ppBase.leverage} tpMode=${ppBase.tpMode} capPortofolio=10 filter=0")

    println("var|win|cand|dibuka|trades|wins|win%|grossP|grossL|PF|net|fee|avgW|avgL|long|short|rej|expLeg|ddPort%|ddPairMax%|guardMin%|guardMax%|guardFb%")
    var mathOk = true
    for (w in windows) {
        val pds = winPD[w.id]!!
        // Kandidat dihitung pada rentang evaluasi produksi yang sama.
        fun candOf(decide: (PD, Int) -> SignalDecision): Int {
            var c = 0
            for (pd in pds) for (k in pd.startIdx until pd.candles.size - 1)
                if (decide(pd, k).passed) c++
            return c
        }
        data class VR(val r: BacktestResult, val cand: Int, val gmin: Double, val gmax: Double, val gfb: Double)
        val results = LinkedHashMap<String, VR>()
        // A: baseline produksi.
        run {
            val cand = candOf { pd, k -> decideAt(pd.candles, k, pd.cache, ppBase) }
            val pr = runPortfolioBacktest(pds.map { PortfolioAsset(it.sym,
                it.candles.map { c -> mapOf("t" to c.t, "o" to c.o, "h" to c.h, "l" to c.l, "c" to c.c, "v" to c.v) as Any? }) }, ppBase)
            if (pr.result.error != null) { println("RUN-ERROR ${w.id} A: ${pr.result.error}"); return@run }
            results["A"] = VR(pr.result, cand, 0.0, 0.0, 0.0)
        }
        for (cfg in cfgs) {
            val guardTot = HashMap<String, Int>()
            fun dec(pd: PD, k: Int): SignalDecision {
                val a = scSrAtr(pd.candles, k, pd.cache, cfg, guardTot)
                val b = strategyFn("mtf_confirm", pd.candles, k, pd.cache, ppBase.strategyParams)
                return scAndA(Triple("sr", a.first, a.second), Triple("mtf", b.first, b.second))
            }
            val cand = candOf { pd, k -> dec(pd, k) }
            val mvp = pds.map { MVPair(it.sym, it.candles, it.cache, it.startIdx) }
            val r = mvRunPortfolio(mvp, ppBase, "${w.id}-${cfg.id}") { a, k ->
                val pd = pds.first { it.sym == a.sym }
                dec(pd, k)
            }
            if (r.trades.any { it.exitTime < it.entryTime }) { mathOk = false; println("LOOKAHEAD-GAGAL ${w.id} ${cfg.id}") }
            val tot = guardTot.values.sum().toDouble()
            val gmin = if (tot > 0) (guardTot["min"] ?: 0) / tot * 100 else 0.0
            val gmax = if (tot > 0) (guardTot["max"] ?: 0) / tot * 100 else 0.0
            val gfb = if (tot > 0) (guardTot["fallback"] ?: 0) / tot * 100 else 0.0
            results[cfg.id] = VR(r, cand, gmin, gmax, gfb)
        }
        for ((id, vr) in results) {
            val r = vr.r
            var ddPairMax = 0.0
            for (pd in pds) {
                val rp = evRun(pd.candles, pd.cache, pd.startIdx, ppBase.copy(asset = pd.sym),
                    { k ->
                        if (id == "A") decideAt(pd.candles, k, pd.cache, ppBase)
                        else {
                            val cfg = cfgs.first { it.id == id }
                            val a = scSrAtr(pd.candles, k, pd.cache, cfg)
                            val b = strategyFn("mtf_confirm", pd.candles, k, pd.cache, ppBase.strategyParams)
                            scAndA(Triple("sr", a.first, a.second), Triple("mtf", b.first, b.second))
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
            println("$id|${w.id}|${vr.cand}|$opened|${r.totalTrades}|${r.wins}|${evF2(r.winRate)}|" +
                "${evF2(r.grossProfit)}|${evF2(r.grossLoss)}|${evF2(r.profitFactor)}|${evF2(r.netProfit)}|" +
                "${evF2(fee)}|${evF2(r.averageWin)}|${evF2(r.averageLoss)}|$longs|$shorts|" +
                "${r.diag.rejectedByLimit}|${r.expired}|${r.breakeven}|${evF2(r.maxDrawdownPercent)}|" +
                "${evF2(ddPairMax)}|${evF2(vr.gmin)}|${evF2(vr.gmax)}|${evF2(vr.gfb)}")
        }
    }
    atOk("matematika & batas", mathOk, "net==gp+gl, dalam periode, tanpa look-ahead")
    println("ATR-EVAL done pass=$atPass fail=$atFail")
    if (atFail > 0) kotlin.system.exitProcess(1)
}
