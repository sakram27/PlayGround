import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*

// REPLAY LIVE TICK — BUKAN PRODUKSI, read-only terhadap aplikasi.
// Memverifikasi sinyal tersimpan BTCUSDT LONG t=1792089900000 (dari prefs
// sesi live emulator, src=dryrun-entry, strategi ema_trend) dengan data
// historis pada timestamp & input yang sama: strategi, Ars, filter kosong.
var lrPass = 0
var lrFail = 0
fun lrOk(n: String, c: Boolean, x: String = "") {
    if (c) { lrPass++; println("PASS $n $x") } else { lrFail++; println("FAIL $n $x") }
}
fun lrDay(t: Long): String {
    return try {
        val f = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.format(java.util.Date(t)) + " UTC"
    } catch (e: Exception) { t.toString() }
}

fun main() {
    val sigT = 1792089900000L
    val sigPx = 82532.35317
    val sigSl = 81707.0296383
    val sigTp = 84183.0002334
    // 1) BAR = open-time candle exchange: kelipatan 15 mnt, UTC.
    lrOk("bar-sejajar-tf", sigT % 900000L == 0L, "t=$sigT ${lrDay(sigT)}")
    // 2) Fetch historis mencakup BAR (read-only, range masa lalu).
    val from = sigT - 3L * 86400000L
    val to = sigT + 3600000L
    val win = LinkedHashMap<String, List<Candle>>()
    for (s in listOf("BTCUSDT", "ETHUSDT")) {
        val rr = fetchRangeCandles("binance", s, "15m", from, to)
        win[s] = normalizeCandles(rr.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
        println("FETCH $s n=${win[s]!!.size} ${lrDay(win[s]!!.first().t)}..${lrDay(win[s]!!.last().t)}")
    }
    val pp = sanitizeParams(BacktestParams(asset = "BTCUSDT", timeframe = "15m",
        strategy = "ema_trend", filters = emptyList(), maxOpenTrades = 1))
    for ((sym, cs) in win) {
        val idx = cs.indexOfFirst { it.t == sigT }
        lrOk("bar-ditemukan-$sym", idx >= 50, "idx=$idx t=${if (idx >= 0) lrDay(cs[idx].t) else "-"}")
        if (idx < 50) continue
        val cache = buildCache(cs)
        // 3) Keputusan strategi pada BAR yang sama.
        val (dir, conf, reasons) = strategyFn("ema_trend", cs, idx, cache, emptyMap())
        val dec = decideAt(cs, idx, cache, pp.copy(asset = sym))
        println("REPLAY $sym t=${lrDay(cs[idx].t)} o=${cs[idx].o} h=${cs[idx].h} l=${cs[idx].l} c=${cs[idx].c} " +
            "e20=${cache.e20[idx]} e50=${cache.e50[idx]} sr=$dir conf=$conf reasons=$reasons combo=${dec.passed}/${dec.direction}")
    }
    // 4) BTCUSDT harus LONG persis seperti sinyal tersimpan.
    val btc = win["BTCUSDT"]!!
    val bi = btc.indexOfFirst { it.t == sigT }
    val bc = buildCache(btc)
    val (bdir, bconf, brea) = strategyFn("ema_trend", btc, bi, bc, emptyMap())
    lrOk("replay-arah", bdir == "LONG", "dir=$bdir")
    lrOk("replay-indikator", btc[bi].c > bc.e50[bi] && bc.e20[bi] > bc.e50[bi],
        "c>EMA50 & EMA20>EMA50 (alasan tersimpan)")
    lrOk("replay-conf", bconf == 0.6, "conf=$bconf")
    // 5) Level konsisten dengan konfigurasi (SL 1,0% / TP 2,0% dari entry).
    val slPct = (sigPx - sigSl) / sigPx * 100
    val tpPct = (sigTp - sigPx) / sigPx * 100
    lrOk("replay-level", kotlin.math.abs(slPct - 1.0) < 0.01 && kotlin.math.abs(tpPct - 2.0) < 0.01,
        "sl=${"%.3f".format(slPct)}% tp=${"%.3f".format(tpPct)}% (default sesi)")
    // 6) ETHUSDT pada tick yang sama: kandidat LONG → menjelaskan CAP_FULL.
    val eth = win["ETHUSDT"]!!
    val ei = eth.indexOfFirst { it.t == sigT }
    if (ei >= 50) {
        val ec = buildCache(eth)
        val edir = strategyFn("ema_trend", eth, ei, ec, emptyMap()).first
        println("REPLAY ETH t=${lrDay(eth[ei].t)} dir=$edir (vonis live: CAP_FULL(1/1) = kandidat lolos tapi cap penuh)")
        lrOk("replay-eth-kandidat", edir == "LONG", "konsisten CAP_FULL, bukan NO_CANDIDATE")
    } else lrOk("replay-eth-kandidat", false, "bar tak ditemukan")
    // 7) Monoton + tanpa look-ahead jendela replay.
    for ((sym, cs) in win) {
        lrOk("replay-monoton-$sym", cs.zipWithNext().all { (a, b) -> b.t > a.t }, "")
    }
    println("LIVE-REPLAY done pass=$lrPass fail=$lrFail")
    if (lrFail > 0) kotlin.system.exitProcess(1)
}
