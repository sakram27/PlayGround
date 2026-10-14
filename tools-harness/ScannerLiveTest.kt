import com.aether.signal.premium.data.*

// UJI LIVE READ-ONLY (HTTP GET publik, tanpa order, tanpa kunci).
// Keluar 2 + "BELUM" bila jaringan tak terjangkau (jujur, bukan hijau palsu).
fun main() {
    var pass = 0
    fun ok(n: String, c: Boolean, x: String = "") {
        if (c) { pass++; println("PASS $n $x") } else println("FAIL $n $x")
    }
    var netDown = 0

    fun candles(provider: String, sym: String): String {
        return try {
            val r = getCandles(provider, sym, "15m", 60)
            val ts = r.candles.map { it.t }
            val ordered = ts.zipWithNext().all { (a, b) -> b > a }
            val ageMin = (System.currentTimeMillis() - (ts.lastOrNull() ?: 0)) / 60000
            ok("live-$provider", r.candles.size >= 60 && ordered,
                "n=${r.candles.size} ordered=$ordered ageMin=$ageMin last=${ts.lastOrNull()}")
            "ok"
        } catch (e: Exception) {
            val io = (e.message ?: "").let {
                it.contains("timed out", true) || it.contains("Unable to resolve", true) ||
                    it.contains("Connection refused", true) || it.contains("Network is unreachable", true)
            }
            if (io) netDown++
            println("FAIL live-$provider kind=${classifyFetchError(e.message)} msg=${e.message?.take(100)}")
            "fail"
        }
    }

    val b = candles("binance", "BTCUSDT")
    val y = candles("yahoo", "EUR/USD")
    val d = candles("demo", "BTCUSDT")
    // Bybit: dari wilayah ini 403 (PERMANENT) — bukti eksplisit, bukan sukses.
    try {
        getCandles("bybit", "BTCUSDT", "15m", 60)
        println("NOTE live-bybit: tak terduga sukses dari wilayah ini")
    } catch (e: Exception) {
        println("EVIDENCE live-bybit kind=${classifyFetchError(e.message)} msg=${e.message?.take(110)}")
    }

    if (b == "fail" && y == "fail" && d == "ok") {
        // Demo selalu lokal; bila keduanya gagal jaringan -> kemungkinan terblokir total.
    }

    // Universe: SATU panggilan ticker agregat (bukan 200 request).
    try {
        val u = fetchUniverse("binance", 200)
        ok("live-universe-200", !u.fallback && u.pairs.size <= 200 &&
            u.pairs.size == u.pairs.distinct().size && u.requested == 200,
            "diterima=${u.pairs.size}/diminta=${u.requested} fallback=${u.fallback}")
        val u5 = fetchUniverse("binance", 5)
        ok("live-universe-5", u5.pairs.size == 5, "n=${u5.pairs.size}")
    } catch (e: Exception) {
        println("FAIL live-universe msg=${e.message?.take(100)}")
    }

    // Logika offline (tanpa jaringan): batch + provider asing.
    val raw = listOf("BTC/USDT", "btcusdt", "ETHUSDT", "XAUUSD", "", "BTCUSDT")
    val normed = raw.mapNotNull { s ->
        val (n, e) = validateCustomPair(s, "binance")
        if (e == null) n else null
    }
    ok("offline-batch", normed.toSet() == setOf("BTCUSDT", "ETHUSDT"), "norm=$normed")
    val bad = fetchUniverse("nope_exchange", 200)
    ok("offline-unknown-prov", bad.fallback && bad.error != null, "err=${bad.error}")

    if (netDown >= 2) {
        println("BELUM DIVERIFIKASI: jaringan live tak terjangkau (netDown=$netDown)")
        kotlin.system.exitProcess(2)
    }
    println("SCANNER-LIVE done pass=$pass")
}
