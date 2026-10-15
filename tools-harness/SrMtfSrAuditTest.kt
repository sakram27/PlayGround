import com.aether.signal.premium.engine.*
import com.aether.signal.premium.data.*
import com.aether.signal.premium.ui.*

// AUDIT SR=NONE PER PAIR — BUKAN PRODUKSI, read-only.
// - Fetch IDENTIK live: getCandles("binance", sym, "15m", 300=ENGINE_CANDLES).
// - Bar evaluasi = engineEvalIndices produksi (size-2 selalu + size-1 bila tua).
// - Perhitungan bayangan manual dicocokkan dengan strategyFn PRODUKSI per bar
//   (bukan pengganti definisi). Batasan jujur: bar adalah kondisi PASAR SAAT
//   INI, bukan bar sesi pengguna (timestamp-nya tak tersedia) — mekanisme
//   yang diaudit identik.
var srPass = 0
var srFail = 0
fun srOk(n: String, c: Boolean, x: String = "") {
    if (c) { srPass++; println("PASS $n $x") } else { srFail++; println("FAIL $n $x") }
}
fun srT(t: Long): String {
    return try {
        val f = java.text.SimpleDateFormat("dd-MM HH:mm", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.format(java.util.Date(t)) + "Z"
    } catch (e: Exception) { t.toString() }
}

fun main() {
    val syms = listOf("BTCUSDT", "DOGEUSDT", "SUIUSDT", "ADAUSDT", "AVAXUSDT",
        "AAVEUSDT", "ARBUSDT", "DOTUSDT", "BCHUSDT", "PENGUUSDT", "SEIUSDT")
    val tol = 0.001
    val nowMs = System.currentTimeMillis()
    val tfMs = 900000L
    println("AUDIT-SR pair|barT|status|O|H|L|C|LL20|HH20|distS%|distR%|tol%|bull|srProd|sebab|mtf|AND")
    var nProxMiss = 0; var nDirFail = 0; var nDataKurang = 0; var nFire = 0
    var allMatch = true
    for (s in syms) {
        val fr = try {
            getCandles("binance", s, "15m", 300)
        } catch (e: Exception) {
            println("FETCH-FAIL $s: ${e.message?.take(120)}")
            continue
        }
        val raw = fr.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }
        val candles = normalizeCandles(raw)
        if (candles.isEmpty()) { println("DATA-KOSONG $s"); continue }
        val cache = buildCache(candles)
        val last = candles.last()
        val evalIdx = engineEvalIndices(candles.size, last.t, nowMs, tfMs)
        for (i in evalIdx) {
            val c = candles[i]
            val status = if (i == candles.size - 1) {
                if (nowMs - c.t >= tfMs) "tertutup-tua" else "BERJALAN"
            } else "tertutup"
            // Bayangan manual rumus produksi (Strategies.kt:135-147).
            var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (j in maxOf(0, i - 20) until i) { hh = maxOf(hh, candles[j].h); ll = minOf(ll, candles[j].l) }
            val dataCukup = i >= 20
            val nearS = dataCukup && ll > 0 && kotlin.math.abs(c.l - ll) / ll < tol
            val nearR = dataCukup && hh > 0 && kotlin.math.abs(c.h - hh) / hh < tol
            val bull = c.c > c.o; val bear = c.c < c.o
            val manual = when {
                !dataCukup -> "NONE"
                nearS && bull -> "LONG"
                nearR && bear -> "SHORT"
                else -> "NONE"
            }
            val prod = strategyFn("support_resistance", candles, i, cache, emptyMap()).first
            if (manual != prod) { allMatch = false; println("COCOK-GAGAL $s idx=$i manual=$manual prod=$prod") }
            val sebab = when {
                !dataCukup -> { nDataKurang++; "3-data-kurang" }
                prod != "NONE" -> { nFire++; "FIRE" }
                !nearS && !nearR -> { nProxMiss++; "1-jarak-meleset" }
                else -> { nDirFail++; "2-arah-gagal" }
            }
            val mt = strategyFn("mtf_confirm", candles, i, cache, emptyMap()).first
            val pp = sanitizeParams(BacktestParams(asset = s, timeframe = "15m",
                strategy = "support_resistance",
                combo = Combo("AND", listOf("support_resistance", "mtf_confirm")),
                filters = emptyList(), maxOpenTrades = 1))
            val andP = decideAt(candles, i, cache, pp).passed
            val dS = if (ll > 0) kotlin.math.abs(c.l - ll) / ll * 100 else Double.NaN
            val dR = if (hh > 0) kotlin.math.abs(c.h - hh) / hh * 100 else Double.NaN
            println("ROW $s|${srT(c.t)}|$status|${c.o}|${c.h}|${c.l}|${c.c}|$ll|$hh|" +
                "${evF2(dS)}|${evF2(dR)}|0.100|bull=$bull|prod=$prod|$sebab|mtf=$mt|AND=$andP")
        }
    }
    srOk("bayangan-cocok-produksi", allMatch, "rumus manual == strategyFn semua bar")
    println("RINGKASAN: proximity-meleset=$nProxMiss arah-gagal=$nDirFail data-kurang=$nDataKurang fire=$nFire")
    println("SR-AUDIT done pass=$srPass fail=$srFail")
    if (srFail > 0) kotlin.system.exitProcess(1)
}
