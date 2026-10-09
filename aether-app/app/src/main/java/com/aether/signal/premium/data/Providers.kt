package com.aether.signal.premium.data

import com.aether.signal.premium.engine.Candle
import com.aether.signal.premium.engine.genDemoCandles
import com.aether.signal.premium.engine.parseTimeframe
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Port data.js: Binance (failover host) + Bybit + Yahoo + Demo + CSV + topPairs.

data class FetchMeta(
    val provider: String, val symbol: String, val timeframe: String,
    val limitRequested: Int, val received: Int, val cacheUsed: String, val source: String
)

data class FetchResult(val candles: List<Candle>, val meta: FetchMeta)

private val memCache = HashMap<String, Pair<List<Candle>, Long>>()

/** TTL cache memori (G5): tanpa ini harga membeku selamanya dalam satu proses.
 *  60 dtk untuk semua TF — cukup segar untuk monitor 60-dtk, hemat untuk backtest
 *  berulang. Bukan bagian rumus strategi. */
const val MEMCACHE_TTL_MS = 60_000L
val fetchHistory = ArrayList<FetchMeta>()

private fun ck(p: String, s: String, t: String, l: Int) = "$p|$s|$t|$l"

val YAHOO_UNIVERSE: Map<String, String> = mapOf(
    "EUR/USD" to "EURUSD=X", "GBP/USD" to "GBPUSD=X", "USD/JPY" to "USDJPY=X", "AUD/USD" to "AUDUSD=X",
    "USD/CAD" to "USDCAD=X", "USD/CHF" to "USDCHF=X", "NZD/USD" to "NZDUSD=X",
    "USD/IDR" to "IDR=X", "USD/SGD" to "SGD=X", "USD/MYR" to "MYR=X", "USD/INR" to "INR=X",
    "USD/CNY" to "CNY=X", "USD/KRW" to "KRW=X", "EUR/GBP" to "EURGBP=X", "EUR/JPY" to "EURJPY=X",
    "GBP/JPY" to "GBPJPY=X", "USD/TRY" to "TRY=X", "USD/ZAR" to "ZAR=X", "EUR/IDR" to "EURIDR=X",
    "XAU/USD" to "GC=F", "XAG/USD" to "SI=F", "XAU Spot" to "XAUUSD=X", "XAG Spot" to "XAGUSD=X",
    "BTC/USD" to "BTC-USD", "ETH/USD" to "ETH-USD"
)

fun yahooSymbol(labelOrCode: String): String? {
    if (YAHOO_UNIVERSE.containsKey(labelOrCode)) return YAHOO_UNIVERSE[labelOrCode]
    val s = labelOrCode.trim().uppercase().replace(Regex("[\\s_]"), "")
    for ((k, v) in YAHOO_UNIVERSE) {
        if (k.replace(Regex("[\\s_/]"), "").uppercase() == s) return v
    }
    var core = s.replace(Regex("=X$"), "").replace("/", "")
    if (Regex("^[A-Z]{6}$").matches(core) && Regex("USD|EUR|GBP|JPY|IDR").containsMatchIn(core)) return "$core=X"
    if (Regex("^[A-Z]{6,7}-USD$").matches(s)) return s
    return null
}

private val YAHOO_INTERVAL = mapOf("1m" to "1m", "5m" to "5m", "15m" to "15m", "30m" to "30m", "1h" to "60m", "4h" to "60m", "1d" to "1d", "1w" to "1wk")
private val YAHOO_RANGE = mapOf("1m" to "5d", "5m" to "1mo", "15m" to "1mo", "30m" to "1mo", "1h" to "6mo", "4h" to "1y", "1d" to "2y", "1w" to "5y")
private val BYBIT_TF = mapOf("1m" to "1", "3m" to "3", "5m" to "5", "15m" to "15", "30m" to "30", "1h" to "60", "2h" to "120", "4h" to "240", "6h" to "360", "12h" to "720", "1d" to "D", "1w" to "W")

/** Timeframe yang didukung penuh oleh SELURUH provider (Market memakai daftar ini). */
val MARKET_TIMEFRAMES = listOf("5m", "15m", "1h", "4h", "1d")
val MARKET_TF_LABELS = listOf("M5", "M15", "H1", "H4", "D1")

/** Provider nyata yang diimplementasikan (ditampilkan apa adanya, tanpa tambahan). */
val PROVIDER_IDS = listOf("binance", "bybit", "yahoo", "demo")
val PROVIDER_LABELS = listOf("Binance (Crypto)", "Bybit (Crypto)", "Yahoo (Forex & Metal)", "Demo (Offline)")

private val FOREX_BASES = setOf(
    "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "IDR", "SGD", "MYR", "INR",
    "CNY", "KRW", "TRY", "ZAR", "SEK", "NOK", "DKK", "PLN", "HKD", "MXN", "XAU", "XAG"
)

/** True bila simbol menyerupai forex/metal (mis. XAU/USD, XAUUSD, EURUSD), bukan crypto spot. */
fun isForexLike(rawSymbol: String): Boolean {
    val s = rawSymbol.trim().uppercase()
    if (s.contains("/")) {
        val base = s.split("/").firstOrNull()?.replace(Regex("[^A-Z]"), "") ?: ""
        if (FOREX_BASES.contains(base)) return true
    }
    val flat = s.replace(Regex("[^A-Z]"), "")
    if (flat.length == 6 && FOREX_BASES.any { flat.startsWith(it) || flat.endsWith(it) }) return true
    if ((flat.startsWith("XAU") || flat.startsWith("XAG")) && flat.length >= 6) return true
    return false
}

/** Nama ramah untuk simbol metal/forex pada pesan error. */
private fun metalName(sym: String): String {
    val f = sym.uppercase().replace(Regex("[^A-Z]"), "")
    return when {
        f.startsWith("XAU") -> "XAUUSD (emas)"
        f.startsWith("XAG") -> "XAGUSD (perak)"
        else -> sym.trim().uppercase()
    }
}

private val BINANCE_HOSTS = listOf("https://api.binance.com", "https://data-api.binance.vision", "https://api.binance.us")
private var binanceHost: String? = null

fun fetchText(url: String, timeoutMs: Int = 15000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
    c.setRequestProperty("User-Agent", "Mozilla/5.0 AetherSignalNative/4.1")
    try {
        val code = c.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code dari ${URL(url).host}")
        return c.inputStream.bufferedReader().readText()
    } finally { c.disconnect() }
}

private fun binanceFetch(path: String, timeoutMs: Int = 15000): Pair<String, String> {
    val hosts = listOfNotNull(binanceHost) + BINANCE_HOSTS.filter { it != binanceHost }
    var lastErr: Exception? = null
    for (h in hosts) {
        try {
            val data = fetchText(h + path, timeoutMs)
            binanceHost = h
            return h to data
        } catch (e: Exception) { lastErr = e }
    }
    val m = Regex("HTTP (\\d+)").find(lastErr?.message ?: "")
    if (m?.groupValues?.get(1) == "451") throw RuntimeException("Binance diblokir di wilayah/jaringan Anda (HTTP 451) dan semua mirror gagal. Pakai Yahoo Forex atau Demo.")
    throw RuntimeException("Binance gagal di semua host: ${lastErr?.message ?: "tidak diketahui"}. Pakai Yahoo Forex atau Demo.")
}

private fun resample(candles: List<Candle>, tfMin: Int): List<Candle> {
    val per = maxOf(1, Math.round(tfMin / 60.0).toInt())
    if (per <= 1) return candles
    val out = ArrayList<Candle>()
    var i = 0
    while (i < candles.size) {
        val g = candles.subList(i, minOf(i + per, candles.size))
        if (g.isEmpty()) break
        out.add(Candle(g[0].t, g[0].o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.sumOf { it.v }))
        i += per
    }
    return out
}

private fun yahooCandles(symbol: String, timeframe: String, limit: Int): List<Candle> {
    val ysym = yahooSymbol(symbol) ?: throw RuntimeException("Yahoo tidak mengenal pair \"$symbol\". Pilih dari daftar Forex (mis. EUR/USD, USD/IDR, XAU/USD).")
    val tf = timeframe.lowercase()
    val interval = YAHOO_INTERVAL[tf] ?: "15m"
    val range = YAHOO_RANGE[tf] ?: "1mo"
    var lastErr: Exception? = null
    for (host in listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")) {
        repeat(2) {
            try {
                val j = JSONObject(fetchText("$host/v8/finance/chart/${java.net.URLEncoder.encode(ysym, "UTF-8")}?interval=$interval&range=$range&includePrePost=false"))
                val res = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val ts = res.getJSONArray("timestamp")
                val q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
                var out = ArrayList<Candle>()
                for (k in 0 until ts.length()) {
                    val o = q.getJSONArray("open").optDouble(k, Double.NaN)
                    val h = q.getJSONArray("high").optDouble(k, Double.NaN)
                    val l = q.getJSONArray("low").optDouble(k, Double.NaN)
                    val c = q.getJSONArray("close").optDouble(k, Double.NaN)
                    if (!(o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite())) continue
                    val v = q.getJSONArray("volume").optDouble(k, 0.0).let { if (it.isFinite()) it else 0.0 }
                    out.add(Candle(ts.getLong(k) * 1000, o, h, l, c, v))
                }
                if (tf == "4h") out = ArrayList(resample(out, 240))
                out = ArrayList(out.takeLast(maxOf(limit, 60)))
                if (out.size < 60) throw RuntimeException("hanya ${out.size} candle (butuh ≥60). Coba timeframe lebih kecil.")
                return out
            } catch (e: Exception) { lastErr = e; try { Thread.sleep(600) } catch (ignored: Exception) {} }
        }
    }
    val code = Regex("HTTP (\\d+)").find(lastErr?.message ?: "")?.groupValues?.get(1)
    if (code == "422") throw RuntimeException("Yahoo menolak kombinasi $symbol $timeframe. Coba timeframe 1h/1d.")
    if (code == "429") throw RuntimeException("Yahoo rate-limit sesaat. Tunggu ±1 menit lalu coba lagi.")
    throw RuntimeException("Yahoo gagal untuk $symbol ($ysym): ${lastErr?.message ?: "tidak diketahui"}. Coba lagi atau pakai Demo.")
}

fun getCandles(provider: String = "binance", symbol: String = "BTCUSDT", timeframe: String = "15m", limit: Int = 500, timeoutMs: Int = 15000): FetchResult {
    val rawSymbol = symbol.trim()
    parseTimeframe(timeframe)
    val lim = minOf(1000, maxOf(60, limit))
    val key = ck(provider, rawSymbol.uppercase(), timeframe, lim)
    memCache[key]?.let { (cached, at) ->
        if (System.currentTimeMillis() - at < MEMCACHE_TTL_MS) {
            val meta = FetchMeta(provider, rawSymbol.uppercase(), timeframe, lim, cached.size, "memory", provider)
            fetchHistory.add(meta)
            return FetchResult(cached, meta)
        } else {
            memCache.remove(key)
        }
    }
    val sym = rawSymbol.uppercase().replace(Regex("[^A-Z0-9]"), "")
    // XAUUSD/forex TIDAK tersedia di spot crypto. Gagal jujur di sini (jangan
    // biarkan provider mengembalikan HTTP 400 yang membingungkan, dan jangan
    // diam-diam memakai data demo sebagai pengganti).
    if ((provider == "binance" || provider == "bybit") && isForexLike(rawSymbol)) {
        val provName = if (provider == "binance") "Binance" else "Bybit"
        throw RuntimeException(
            "${metalName(rawSymbol)} tidak tersedia di $provName spot (khusus crypto). " +
                "Pilih provider Yahoo (Forex & Metal) atau Demo untuk simbol ini."
        )
    }
    val candles: List<Candle> = when (provider) {
        "demo" -> genDemoCandles(symbol.length * 777 + timeframe.length * 131, lim, guessPrice(symbol), parseTimeframe(timeframe))
        "binance" -> {
            if (sym.isEmpty()) throw RuntimeException("Pilih pair dulu dari listview.")
            val (_, txt) = binanceFetch("/api/v3/klines?symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}&interval=${java.net.URLEncoder.encode(timeframe, "UTF-8")}&limit=$lim", timeoutMs)
            val j = JSONArray(txt)
            if (j.length() == 0) throw RuntimeException("Binance mengembalikan data kosong untuk $sym $timeframe. Cek penulisan simbol.")
            List(j.length()) { k ->
                val a = j.getJSONArray(k)
                Candle(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getDouble(5))
            }
        }
        "bybit" -> {
            if (sym.isEmpty()) throw RuntimeException("Pilih pair dulu dari listview.")
            val tf = BYBIT_TF[timeframe.lowercase()] ?: throw RuntimeException("Bybit tidak mendukung timeframe $timeframe. Gunakan 1m/5m/15m/1h/4h/1d/1w.")
            val txt = try {
                fetchText("https://api.bybit.com/v5/market/kline?category=spot&symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}&interval=${java.net.URLEncoder.encode(tf, "UTF-8")}&limit=$lim", timeoutMs)
            } catch (e: Exception) { throw RuntimeException("Bybit tak terjangkau (umum: diblokir wilayah/jaringan). Pakai Binance, Yahoo, atau Demo. [${e.message ?: "network"}]") }
            val list = JSONObject(txt).getJSONObject("result").getJSONArray("list")
            if (list.length() == 0) throw RuntimeException("Bybit mengembalikan data kosong untuk $sym. Pastikan simbol spot valid.")
            List(list.length()) { k ->
                val a = list.getJSONArray(k)
                Candle(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getDouble(5))
            }.reversed()
        }
        "yahoo" -> yahooCandles(rawSymbol, timeframe, lim)
        else -> throw RuntimeException("Provider tidak dikenal: $provider")
    }
    val bad = candles.count { !(it.o.isFinite() && it.h.isFinite() && it.l.isFinite() && it.c.isFinite()) }
    if (bad > 0) throw RuntimeException("Provider mengembalikan $bad candle rusak. Coba refresh.")
    memCache[key] = candles to System.currentTimeMillis()
    val meta = FetchMeta(provider, rawSymbol.uppercase(), timeframe, lim, candles.size, "none", provider)
    fetchHistory.add(meta)
    if (fetchHistory.size > 60) fetchHistory.removeAt(0)
    return FetchResult(candles, meta)
}

private fun guessPrice(sym: String): Double = when {
    sym.contains("BTC") -> 67000.0
    sym.contains("ETH") -> 3500.0
    sym.contains("SOL") -> 170.0
    sym.contains("BNB") -> 590.0
    else -> 100.0
}

fun parseCSV(text: String): List<Candle> {
    val lines = text.split(Regex("\r?\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) throw RuntimeException("File CSV kosong.")
    val out = ArrayList<Candle>()
    val first = lines[0].split(Regex("[,;\t]"))
    val start = if (first[0].any { it.isLetter() }) 1 else 0
    for (idx in start until lines.size) {
        val p = lines[idx].split(Regex("[,;\t]")).map { it.trim() }
        if (p.size < 5) continue
        var t = try { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(p[0])?.time ?: Long.MIN_VALUE } catch (e: Exception) { Long.MIN_VALUE }
        if (t == Long.MIN_VALUE) {
            val n = p[0].toDoubleOrNull() ?: continue
            t = (n * (if (p[0].length <= 10) 1000 else 1)).toLong()
        }
        val o = p[1].toDoubleOrNull(); val h = p[2].toDoubleOrNull(); val l = p[3].toDoubleOrNull(); val c = p[4].toDoubleOrNull()
        if (o == null || h == null || l == null || c == null) continue
        val v = if (p.size > 5 && p[5].isNotEmpty()) p[5].toDoubleOrNull() ?: 0.0 else 0.0
        out.add(Candle(t, o, h, l, c, v))
    }
    if (out.size < 60) throw RuntimeException("CSV hanya menghasilkan ${out.size} candle valid (butuh ≥60). Format: time,open,high,low,close,volume.")
    return out
}

/** Daftar darurat bila provider diblokir/tak terjangkau. BUKAN data pasar —
 *  hanya simbol; harga tetap diambil live per pair (atau gagal jujur per baris). */
val FALLBACK_PAIRS = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT")

fun topPairs(provider: String = "binance", limit: Int = 50): List<String> {
    if (provider == "demo") return listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT").take(limit)
    if (provider == "yahoo") return YAHOO_UNIVERSE.keys.toList().take(limit)
    return try {
        fetchTopPairs(provider, limit)
    } catch (e: Exception) {
        FALLBACK_PAIRS
    }
}

private fun fetchTopPairs(provider: String, limit: Int): List<String> {
    if (provider == "bybit") {
        val j = JSONObject(fetchText("https://api.bybit.com/v5/market/tickers?category=spot"))
        val list = j.getJSONObject("result").getJSONArray("list")
        val rows = ArrayList<Pair<String, Double>>()
        for (k in 0 until list.length()) {
            val o = list.getJSONObject(k)
            val s = o.getString("symbol")
            if (s.endsWith("USDT")) rows.add(s to o.optDouble("turnover24h", 0.0))
        }
        return rows.sortedByDescending { it.second }.take(limit).map { it.first }
    }
    val (_, txt) = binanceFetch("/api/v3/ticker/24hr")
    val j = JSONArray(txt)
    val rows = ArrayList<Pair<String, Double>>()
    for (k in 0 until j.length()) {
        val o = j.getJSONObject(k)
        val s = o.getString("symbol")
        if (s.endsWith("USDT")) rows.add(s to o.optDouble("quoteVolume", 0.0))
    }
    return rows.sortedByDescending { it.second }.take(limit).map { it.first }
}

/** Seperti topPairs, plus penanda apakah hasilnya daftar darurat.
 *  @return Pair(daftarPair, isFallback) */
fun topPairsWithSource(provider: String = "binance", limit: Int = 50): Pair<List<String>, Boolean> {
    if (provider == "demo") return listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT").take(limit) to false
    if (provider == "yahoo") return YAHOO_UNIVERSE.keys.toList().take(limit) to false
    return try {
        fetchTopPairs(provider, limit) to false
    } catch (e: Exception) {
        FALLBACK_PAIRS to true
    }
}
