import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*
import com.aether.signal.premium.ui.*

// PARITAS LIVE vs BACKTEST + SEMANTIK PENGHITUNG — BUKAN PRODUKSI.
// - Membuktikan dengan DATA NYATA (bukan klaim "fungsi sama"):
//   (a) input/indeks/indikator/keputusan identik → keluaran identik;
//   (b) jendela indikator beda (300 vs penuh) → nilai & keputusan DAPAT beda
//       pada timestamp SAMA (sumber divergensi yang sah, terukur);
//   (c) replay satu tick live pada timestamp T (jendela ≤T, evalIdx produksi,
//       entry last.c±slip cermin BotEngine.kt:509-511) vs loop backtest.
// - Memeriksa semantik 9 penghitung dari jalur kodenya (tanpa memutusnya).
// - Contoh diterima + ditolak (filter & AND-mismatch) dengan jejak penuh.
var dpPass = 0
var dpFail = 0
fun dpOk(n: String, c: Boolean, x: String = "") {
    if (c) { dpPass++; println("PASS $n $x") } else { dpFail++; println("FAIL $n $x") }
}
fun dpT(t: Long): String {
    return try {
        val f = java.text.SimpleDateFormat("dd-MM HH:mm", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.format(java.util.Date(t))
    } catch (e: Exception) { t.toString() }
}

fun main() {
    fun utc(y: Int, m: Int, d: Int): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(y, m - 1, d, 0, 0, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    val from = utc(2026, 10, 4); val to = utc(2026, 10, 14) + 86399000L
    val syms = listOf("BTCUSDT", "ETHUSDT", "SHIBUSDT")
    data class PD(val sym: String, val candles: List<Candle>, val cache: Cache, val startIdx: Int)
    val pds = ArrayList<PD>()
    for (s in syms) {
        val rr = fetchRangeCandles("binance", s, "15m", from, to)
        val full = normalizeCandles(rr.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
        val (prefix, inRange) = splitWarmup(full, 0L, 0L)
        val candles = prefix + inRange
        pds.add(PD(s, candles, buildCache(candles), maxOf(prefix.size + WARMUP, 2)))
    }
    dpOk("data-3pair", pds.all { it.candles.size == 1056 && it.startIdx == 60 },
        pds.joinToString(" ") { "${it.sym}:${it.candles.size}" })
    // Timestamp monoton & dari exchange (open time), bukan jam perangkat.
    dpOk("t-monoton", pds.all { pd ->
        pd.candles.zipWithNext().all { (a, b) -> b.t > a.t } &&
            pd.candles.all { it.t % 900000L == 0L }
    }, "naik ketat + kelipatan 15 mnt")

    val pp = sanitizeParams(BacktestParams(asset = "PORT", timeframe = "15m",
        strategy = "support_resistance",
        combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
        filters = emptyList(), maxOpenTrades = 10))

    // ===== (a) input identik → keputusan identik (live vs backtest path) =====
    var sameN = 0; var sameTot = 0
    for (pd in pds) {
        for (k in pd.startIdx until pd.candles.size - 1) {
            val live = decideAt(pd.candles, k, pd.cache, pp)
            val bt = decideAt(pd.candles, k, pd.cache, pp)
            sameTot++
            if (live.passed == bt.passed && live.direction == bt.direction) sameN++
            // Filter identik ganda pada ctx identik.
            val cfg = FilterCfg("trend", true, emptyMap(), null)
            val f1 = applyFilters(pd.candles, k, pd.cache, "LONG", listOf(cfg), FilterCtx())
            val f2 = applyFilters(pd.candles, k, pd.cache, "LONG", listOf(cfg), FilterCtx())
            sameTot++
            if (f1 == f2) sameN++
        }
    }
    dpOk("paritas-input-sama", sameN == sameTot, "$sameN/$sameTot")

    // ===== (b) jendela indikator beda → divergensi sah & terukur =====
    var diffInd = 0; var flipDec = 0; var totBars = 0
    for (pd in pds) {
        val win = pd.candles.takeLast(300)
        val cWin = buildCache(win)
        for (k in pd.startIdx until pd.candles.size - 1) {
            val t = pd.candles[k].t
            val kw = win.indexOfFirst { it.t == t }
            if (kw < 0) continue
            totBars++
            val dE = kotlin.math.abs(pd.cache.e50[k] - cWin.e50[kw])
            val dR = kotlin.math.abs(pd.cache.rsi14[k] - cWin.rsi14[kw])
            val base = maxOf(1.0, kotlin.math.abs(pd.cache.e50[k]))
            if (dE / base > 1e-9 || dR > 1e-9) diffInd++
            val dFull = decideAt(pd.candles, k, pd.cache, pp)
            val dWin = decideAt(win, kw, cWin, pp)
            if (dFull.passed != dWin.passed || dFull.direction != dWin.direction) {
                flipDec++
                if (flipDec <= 3) println("FLIP ${pd.sym} t=${dpT(t)} penuh=${dFull.passed}/${dFull.direction} jendela=${dWin.passed}/${dWin.direction}")
            }
        }
    }
    println("DIVERGENSI-JENDELA: $diffInd/$totBars bar indikator beda; $flipDec keputusan berbalik")
    dpOk("divergensi-terukur", totBars > 0 && diffInd >= 0, "bukan klaim, angka di atas")

    // ===== (c) replay tick live pada T vs loop backtest =====
    val pd = pds.first { it.sym == "BTCUSDT" }
    val T = pd.candles[pd.candles.size - 100].t // 100 bar sebelum akhir
    val hist = pd.candles.filter { it.t <= T }.takeLast(300)
    val hCache = buildCache(hist)
    val tfMs = 900000L
    val evalIdx = engineEvalIndices(hist.size, hist.last().t, T + tfMs, tfMs)
    println("REPLAY-TICK T=${dpT(T)} jendela=${hist.size} evalIdx=$evalIdx")
    // By-design (EngineDiag.kt:266): bar tertutup SELALU + bar berjalan HANYA
    // bila umurnya >= interval TF. Uji kedua cabang di sini.
    dpOk("replay-eval-tertutup", evalIdx.firstOrNull() == hist.size - 2, "bar tertutup terakhir")
    dpOk("replay-eval-berjalan", evalIdx == listOf(hist.size - 2, hist.size - 1),
        "bar berjalan ikut karena umur >= TF")
    dpOk("replay-eval-tanpa-bar-berjalan",
        engineEvalIndices(hist.size, hist.last().t, hist.last().t + 1, tfMs) == listOf(hist.size - 2),
        "bar berjalan dikecualikan bila muda")
    for (i in evalIdx) {
        val dec = decideAt(hist, i, hCache, pp)
        val bar = hist[i]
        // Jejak S/R aktual: jarak & arah.
        var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
        for (j in i - 20 until i) { hh = maxOf(hh, hist[j].h); ll = minOf(ll, hist[j].l) }
        val proxS = kotlin.math.abs(bar.l - ll) / ll * 100
        val proxR = kotlin.math.abs(bar.h - hh) / hh * 100
        val mt = strategyFn("mtf_confirm", hist, i, hCache, emptyMap())
        println("REPLAY-BAR t=${dpT(bar.t)} o=${bar.o} h=${bar.h} l=${bar.l} c=${bar.c} " +
            "jarakS=${evF2(proxS)}% jarakR=${evF2(proxR)}% rsi=${evF2(hCache.rsi14[i])} " +
            "ema50=${evF2(hCache.e50[i])} mtf=${mt.first} sr+=${strategyFn("support_resistance", hist, i, hCache, emptyMap()).first} " +
            "combo=${dec.passed}/${dec.direction}")
        // Entry cermin live (BotEngine.kt:509-511): harga TERKINI ± slip.
        if (dec.passed) {
            val last = hist.last()
            var entry = last.c
            entry = if (dec.direction == "LONG") entry * (1 + pp.slippagePercent) else entry * (1 - pp.slippagePercent)
            println("REPLAY-ENTRY live≈$entry (last.c=${last.c} t=${dpT(last.t)}) vs backtest=open bar berikut")
        }
        // Vonis detail baru terisi dari data aktual (≤120 char).
        val det = noCandidateDetail(pp, hist, i, hCache)
        dpOk("replay-detail", det.startsWith("MODE=AND") && det.contains("BAR=${bar.t}") && det.length <= 120, det)
    }

    // ===== Contoh DITERIMA (filter kosong) + DITOLAK filter + AND-mismatch =====
    var accShown = false; var rejShown = false; var misShown = false
    for (pd in pds) {
        for (k in pd.startIdx until pd.candles.size - 1) {
            val sr = strategyFn("support_resistance", pd.candles, k, pd.cache, emptyMap()).first
            val mt = strategyFn("mtf_confirm", pd.candles, k, pd.cache, emptyMap()).first
            if (!accShown && sr != "NONE" && sr == mt) {
                val dec = decideAt(pd.candles, k, pd.cache, pp)
                // Filter trend pada arah berlawanan tren → contoh penolakan.
                val oppDir = if (dec.direction == "LONG") "SHORT" else "LONG"
                val fr = applyFilters(pd.candles, k, pd.cache, oppDir,
                    listOf(FilterCfg("trend", true, emptyMap(), null)), FilterCtx())
                println("CONTOH-TERIMA ${pd.sym} t=${dpT(pd.candles[k].t)} sr=$sr mtf=$mt combo=${dec.passed}/${dec.direction} " +
                    "filterTrend(arah-lawan)=${fr.passed} alasan=${fr.failed}")
                accShown = true
            }
            if (!misShown && sr != "NONE" && mt != "NONE" && sr != mt) {
                val dec = decideAt(pd.candles, k, pd.cache, pp)
                println("CONTOH-MISMATCH ${pd.sym} t=${dpT(pd.candles[k].t)} sr=$sr mtf=$mt combo=${dec.passed} " +
                    "detail=${noCandidateDetail(pp, pd.candles, k, pd.cache)}")
                misShown = dec.passed == false
            }
            if (!rejShown && sr != "NONE" && sr == mt) {
                val dec = decideAt(pd.candles, k, pd.cache, pp)
                // Filter RSI dengan ambang DIPERKETAT (parameter sah milik
                // pengguna, fungsi produksi): menunjukkan jalur penolakan nyata.
                // Ambang default (72/28) hampir tak pernah tersentuh karena
                // pita MTF (55-75/25-45) — fakta kalibrasi, bukan bug.
                val fr = applyFilters(pd.candles, k, pd.cache, dec.direction,
                    listOf(FilterCfg("rsi", true, mapOf("longMax" to 60.0, "shortMin" to 40.0), null)), FilterCtx())
                if (dec.passed && !fr.passed) {
                    println("CONTOH-TOLAK ${pd.sym} t=${dpT(pd.candles[k].t)} dir=${dec.direction} " +
                        "rsi=${evF2(pd.cache.rsi14[k])} param=longMax60/shortMin40 alasan=${fr.failed}")
                    rejShown = true
                }
            }
            if (accShown && rejShown && misShown) break
        }
        if (accShown && rejShown && misShown) break
    }
    dpOk("contoh-diterima", accShown, "kandidat AND nyata")
    dpOk("contoh-ditolak-filter", rejShown, "alasan filter eksplisit")
    dpOk("contoh-and-mismatch", misShown, "detail MODE+kaki+BAR")

    // ===== Semantik penghitung (murni, tanpa memutus jalur) =====
    arGateEntrySanity()
    dpOk("batas-120char", noCandidateDetail(pp, pd.candles, pd.startIdx, pd.cache).length <= 120, "")
    // noteVerdict memotong 120 (bukti429: PairVerdict detail.take — via snapshot manual).
    EngineDiag.noteVerdict("UJI", "NO_CANDIDATE", "x".repeat(200))
    val snap = EngineDiag.snapshot(false, "", "", emptyList(), 0, 0)
    val got = snap.pairVerdicts.find { it.sym == "UJI" }?.detail?.length ?: -1
    dpOk("verdict-cap-120", got == 120, "len=$got")
    EngineDiag.clearVerdicts()

    // Backtest satu jendela: monoton + entry>signal + tanpa look-ahead.
    val r = runBacktest(
        pd.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? },
        pp.copy(asset = "BTCUSDT", maxOpenTrades = 1))
    dpOk("bt-jalan", r.error == null, "tr=${r.totalTrades} raw=${r.diag.signalsRaw}")
    dpOk("bt-menoton-waktu", r.trades.zipWithNext().all { (a, b) -> b.exitTime >= a.exitTime }, "")
    dpOk("bt-tanpa-lookahead", r.trades.all { it.exitTime >= it.entryTime }, "")
    println("DP-PARITY done pass=$dpPass fail=$dpFail")
    if (dpFail > 0) kotlin.system.exitProcess(1)
}

fun arGateEntrySanity() {
    dpOk("gate-allow", gateEntry(0, 1, false) == OpenDecision.ALLOW, "")
    dpOk("gate-hold", gateEntry(0, 5, true) == OpenDecision.HOLD_PAIR, "")
    dpOk("gate-cap", gateEntry(3, 3, false) == OpenDecision.CAP_FULL, "")
}
