package com.aether.signal.premium.data

import com.aether.signal.premium.engine.Candle
import com.aether.signal.premium.engine.exceedsTwoYears
import com.aether.signal.premium.engine.parseTimeframe
import org.json.JSONArray
import org.json.JSONObject

// FITUR 3/6 (V20): arsip historis gratis + funding — tanpa API berbayar.
// Sumber: endpoint PUBLIK (tanpa kunci): Binance spot klines + fapi fundingRate,
// Bybit spot kline + funding history, Yahoo chart (range). Semua gagal-jujur
// (exception berpesan jelas), laju dibatasi (jeda antar halaman), bisa dibatalkan.

// ---------- gabung murni ----------
/** Gabung dua daftar candle: dedup timestamp, urut waktu. */
fun mergeCandles(a: List<Candle>, b: List<Candle>): List<Candle> {
    if (a.isEmpty()) return b.sortedBy { it.t }
    if (b.isEmpty()) return a.sortedBy { it.t }
    val seen = HashSet<Long>(a.size + b.size)
    val out = ArrayList<Candle>(a.size + b.size)
    for (c in a) if (seen.add(c.t)) out.add(c)
    for (c in b) if (seen.add(c.t)) out.add(c)
    out.sortBy { it.t }
    return out
}

// ---------- arsip berkas (terpisah dari cache 1000-candle) ----------
const val ARCHIVE_MAX_CANDLES = 20000
const val ARCHIVE_MAX_FILES = 20
fun archiveKey(provider: String, symbol: String, timeframe: String): String {
    val s = "${provider}_${symbol.uppercase()}_${timeframe}_hist"
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return "$s.jsonl"
}

fun serializeArchive(candles: List<Candle>): String =
    candles.takeLast(ARCHIVE_MAX_CANDLES).joinToString("\n") {
        "${it.t},${it.o},${it.h},${it.l},${it.c},${it.v}"
    }

// ---------- katalog metadata ----------
data class ArchiveInfo(
    val provider: String,
    val symbol: String,
    val timeframe: String,
    val firstT: Long,
    val lastT: Long,
    val count: Int,
    val fetchedAt: Long,
    val source: String
)

fun encodeCatalog(list: List<ArchiveInfo>): String {
    val a = JSONArray()
    for (e in list) {
        a.put(JSONObject().put("provider", e.provider).put("symbol", e.symbol)
            .put("timeframe", e.timeframe).put("firstT", e.firstT).put("lastT", e.lastT)
            .put("count", e.count).put("fetchedAt", e.fetchedAt).put("source", e.source))
    }
    return a.toString()
}

fun parseCatalog(raw: String?): MutableList<ArchiveInfo> {
    val out = ArrayList<ArchiveInfo>()
    if (raw.isNullOrEmpty()) return out
    try {
        val a = JSONArray(raw)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(ArchiveInfo(o.optString("provider"), o.optString("symbol"),
                o.optString("timeframe"), o.optLong("firstT", 0), o.optLong("lastT", 0),
                o.optInt("count", 0), o.optLong("fetchedAt", 0), o.optString("source")))
        }
    } catch (e: Exception) { /* rusak → kosong */ }
    return out
}

// ---------- unduh rentang (paginasi startTime/endTime, gratis) ----------
const val RANGE_PAGE_LIMIT = 1000
const val RANGE_MAX_PAGES = 12
const val RANGE_PAGE_PAUSE_MS = 350L

data class RangeResult(val candles: List<Candle>, val pages: Int, val capped: Boolean)

/**
 * Unduh [fromMs, toMs] secara bertahap. Binance/Bybit: paginasi startTime/endTime
 * (maks 1000/halaman). Yahoo: satu tarikan range (tanpa paginasi — dinyatakan).
 * Demo: ditolak (generator tak punya histori tanggal). isCancelled dicek per halaman.
 */
fun fetchRangeCandles(
    provider: String, symbol: String, timeframe: String,
    fromMs: Long, toMs: Long,
    isCancelled: () -> Boolean = { false }
): RangeResult {
    if (fromMs <= 0 || toMs <= 0 || toMs <= fromMs) throw RuntimeException("Rentang tanggal tidak valid.")
    if (exceedsTwoYears(fromMs, toMs)) {
        throw RuntimeException("Rentang melebihi 2 tahun kalender. Pilih periode maksimal 2 tahun.")
    }
    if (provider == "demo") throw RuntimeException("Provider Demo membangkitkan data sintetis — tak punya histori tanggal untuk diarsipkan.")
    val tfMin = try { parseTimeframe(timeframe) } catch (e: Exception) {
        throw RuntimeException("Timeframe tidak valid: $timeframe.")
    }
    if (provider == "yahoo") {
        // Yahoo hanya mendukung range bawaan (YAHOO_RANGE) — satu tarikan.
        val r = getCandles("yahoo", symbol, timeframe, 1000)
        val cut = r.candles.filter { it.t >= fromMs && it.t <= toMs }
        return RangeResult(cut, 1, false)
    }
    if (provider != "binance" && provider != "bybit") throw RuntimeException("Provider tidak dikenal: $provider.")
    val sym = symbol.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
    if ((provider == "binance" || provider == "bybit") && isForexLike(symbol)) {
        throw RuntimeException("Simbol forex/metal tidak tersedia di spot $provider. Pakai Yahoo/Demo.")
    }
    var out = emptyList<Candle>()
    var start = fromMs
    var pages = 0
    var capped = false
    while (true) {
        if (isCancelled()) throw RuntimeException("Unduhan dibatalkan pada halaman ${pages + 1}.")
        if (pages >= RANGE_MAX_PAGES) { capped = true; break }
        val page = fetchRangePage(provider, sym, timeframe, tfMin, start, toMs)
        pages++
        if (page.isEmpty()) break
        out = mergeCandles(out, page)
        val lastT = page.maxOf { it.t }
        if (lastT >= toMs) break
        start = lastT + 1
        if (page.size < RANGE_PAGE_LIMIT) break
        try { Thread.sleep(RANGE_PAGE_PAUSE_MS) } catch (e: Exception) { /* abaikan */ }
    }
    return RangeResult(out.filter { it.t in fromMs..toMs }, pages, capped)
}

private fun fetchRangePage(
    provider: String, sym: String, timeframe: String, tfMin: Int, startMs: Long, endMs: Long
): List<Candle> {
    // Peta interval Bybit lokal (cermin Providers, tanpa mengubahnya).
    val bybitTf = mapOf("1m" to "1", "3m" to "3", "5m" to "5", "15m" to "15",
        "30m" to "30", "1h" to "60", "2h" to "120", "4h" to "240",
        "6h" to "360", "12h" to "720", "1d" to "D", "1w" to "W")
    return try {
        if (provider == "binance") {
            val txt = fetchText(
                "https://api.binance.com/api/v3/klines?symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}" +
                    "&interval=${java.net.URLEncoder.encode(timeframe, "UTF-8")}" +
                    "&startTime=$startMs&endTime=$endMs&limit=$RANGE_PAGE_LIMIT")
            val j = JSONArray(txt)
            List(j.length()) { k ->
                val a = j.getJSONArray(k)
                Candle(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getDouble(5))
            }
        } else {
            val tf = bybitTf[timeframe.lowercase()] ?: throw RuntimeException("Bybit tidak mendukung timeframe $timeframe.")
            val txt = fetchText(
                "https://api.bybit.com/v5/market/kline?category=spot&symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}" +
                    "&interval=${java.net.URLEncoder.encode(tf, "UTF-8")}" +
                    "&start=$startMs&end=$endMs&limit=$RANGE_PAGE_LIMIT")
            val list = JSONObject(txt).getJSONObject("result").getJSONArray("list")
            List(list.length()) { k ->
                val a = list.getJSONArray(k)
                Candle(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getDouble(5))
            }.reversed()
        }
    } catch (e: Exception) {
        throw RuntimeException("Gagal mengambil halaman ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(startMs))}: ${e.message}")
    }
}

// ---------- funding rate (endpoint publik, tanpa kunci) ----------
data class FundingEvent(val t: Long, val rate: Double)

/** Parse respons funding Binance fapi ATAU Bybit v5 (terdeteksi dari bentuk). */
fun parseFundingJson(txt: String): List<FundingEvent> {
    val t = txt.trim()
    if (t.startsWith("[")) {
        // Binance: [{"symbol","fundingRate":"0.0001","fundingTime":...}]
        val a = JSONArray(t)
        return (0 until a.length()).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            val ft = o.optLong("fundingTime", 0)
            val r = o.optString("fundingRate", "").toDoubleOrNull() ?: return@mapNotNull null
            if (ft <= 0 || !r.isFinite()) null else FundingEvent(ft, r)
        }
    }
    // Bybit: {"result":{"list":[[symbol, rate, time], ...]}}
    val o = JSONObject(t)
    val list = o.optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
    return (0 until list.length()).mapNotNull { k ->
        val a = list.optJSONArray(k) ?: return@mapNotNull null
        val r = a.optString(1, "").toDoubleOrNull() ?: return@mapNotNull null
        val ft = a.optLong(2, 0)
        if (ft <= 0 || !r.isFinite()) null else FundingEvent(ft, r)
    }
}

/**
 * Ambil riwayat funding (publik). Binance: fapi fundingRate (limit 1000).
 * Bybit: funding history linear (limit 200). Gagal → exception jujur.
 */
fun fetchFunding(provider: String, symbol: String, limit: Int = 200): List<FundingEvent> {
    val sym = symbol.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
    if (sym.isEmpty()) throw RuntimeException("Pilih pair dulu.")
    return when (provider) {
        "binance" -> parseFundingJson(fetchText(
            "https://fapi.binance.com/fapi/v1/fundingRate?symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}&limit=${limit.coerceIn(1, 1000)}"))
        "bybit" -> parseFundingJson(fetchText(
            "https://api.bybit.com/v5/market/funding/history?category=linear&symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}&limit=${limit.coerceIn(1, 200)}"))
        else -> throw RuntimeException("Funding hanya tersedia untuk Binance/Bybit (Yahoo/Demo tak punya funding).")
    }.sortedBy { it.t }
}

/**
 * V21: funding dibatasi periode trade (startTime/endTime bila didukung).
 * Di luar itu sama dengan fetchFunding; gagal → exception jujur.
 */
fun fetchFundingRange(provider: String, symbol: String, fromMs: Long, toMs: Long): List<FundingEvent> {
    val sym = symbol.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
    if (sym.isEmpty()) throw RuntimeException("Pilih pair dulu.")
    if (fromMs <= 0 || toMs <= fromMs) throw RuntimeException("Rentang funding tidak valid.")
    return when (provider) {
        "binance" -> parseFundingJson(fetchText(
            "https://fapi.binance.com/fapi/v1/fundingRate?symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}" +
                "&startTime=$fromMs&endTime=$toMs&limit=1000"))
        "bybit" -> parseFundingJson(fetchText(
            "https://api.bybit.com/v5/market/funding/history?category=linear&symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}" +
                "&start=$fromMs&end=$toMs&limit=200"))
        else -> throw RuntimeException("Funding hanya tersedia untuk Binance/Bybit (Yahoo/Demo tak punya funding).")
    }.filter { it.t in fromMs..toMs }.sortedBy { it.t }
}

// ---------- penyimpanan arsip (berkas _hist.jsonl di direktori cache) ----------
object ArchiveStore {
    private fun base(): java.io.File? = CandleDiskCache.dir

    fun save(provider: String, symbol: String, timeframe: String, candles: List<Candle>): Int {
        val b = base() ?: return 0
        return try {
            if (!b.exists()) b.mkdirs()
            val f = java.io.File(b, archiveKey(provider, symbol, timeframe))
            val old = if (f.exists()) f.readText() else ""
            val merged = mergeCandles(parseCandles(old), parseCandles(serializeArchive(candles)))
            val cut = merged.takeLast(ARCHIVE_MAX_CANDLES)
            f.writeText(serializeArchive(cut))
            prune(b)
            cut.size
        } catch (e: Exception) { 0 }
    }

    fun load(provider: String, symbol: String, timeframe: String): List<Candle> {
        val b = base() ?: return emptyList()
        return try {
            val f = java.io.File(b, archiveKey(provider, symbol, timeframe))
            if (!f.exists()) return emptyList()
            parseCandles(f.readText())
        } catch (e: Exception) { emptyList() }
    }

    fun delete(provider: String, symbol: String, timeframe: String): Boolean {
        val b = base() ?: return false
        return try { java.io.File(b, archiveKey(provider, symbol, timeframe)).delete() } catch (e: Exception) { false }
    }

    fun prune(b: java.io.File) {
        try {
            val files = b.listFiles { f -> f.isFile && f.name.endsWith("_hist.jsonl") }
                ?.sortedBy { it.lastModified() } ?: return
            var drop = files.size - ARCHIVE_MAX_FILES
            var i = 0
            while (drop > 0 && i < files.size) {
                try { files[i].delete() } catch (e: Exception) { /* abaikan */ }
                drop--; i++
            }
        } catch (e: Exception) { /* abaikan */ }
    }
}

// ---------- V21: alur freqtrade (periksa lokal → unduh yang hilang → validasi) ----------

data class EnsuredRange(
    val candles: List<Candle>,
    val fromMs: Long,
    val toMs: Long,
    val source: String,
    val fetchedAt: Long,
    val pages: Int,
    val capped: Boolean,
    val usedLocal: Boolean,
    val downloaded: Boolean
)

/** Segmen hilang [fromMs, toMs] dari cakupan lokal (murni, teruji). */
fun planRangeFetch(
    localFirst: Long, localLast: Long, localCount: Int, fromMs: Long, toMs: Long
): List<Pair<Long, Long>> {
    if (fromMs <= 0 || toMs <= fromMs || localCount <= 0 || localFirst <= 0 || localLast <= 0) {
        return if (fromMs > 0 && toMs > fromMs) listOf(fromMs to toMs) else emptyList()
    }
    val out = ArrayList<Pair<Long, Long>>()
    if (localFirst > fromMs) out.add(fromMs to (localFirst - 1))
    if (localLast < toMs) out.add((localLast + 1) to toMs)
    return out
}

/**
 * Pastikan cakupan [fromMs, toMs] (+Ekstensi pemanasan pra-start bila warmupBars>0).
 * Urutan: tolak >2thn → baca arsip lokal → unduh segmen hilang → gabung →
 * simpan arsip → kembalikan + label sumber. Gagal unduh = exception (gagal jujur,
 * bukan hasil parsial diam-diam). Ekstensi pemanasan dilaporkan, bukan disembunyikan.
 */
fun ensureDatedCandles(
    provider: String, symbol: String, timeframe: String,
    fromMs: Long, toMs: Long,
    warmupBars: Int = com.aether.signal.premium.engine.WARMUP,
    isCancelled: () -> Boolean = { false },
    onProgress: (String) -> Unit = {}
): EnsuredRange {
    if (fromMs <= 0 || toMs <= fromMs) throw RuntimeException("Rentang tanggal tidak valid.")
    if (exceedsTwoYears(fromMs, toMs)) {
        throw RuntimeException("Rentang melebihi 2 tahun kalender. Pilih periode maksimal 2 tahun.")
    }
    val tfMin = try { parseTimeframe(timeframe) } catch (e: Exception) {
        throw RuntimeException("Timeframe tidak valid: $timeframe.")
    }
    val extFrom = maxOf(0L, fromMs - warmupBars.coerceIn(0, 200).toLong() * tfMin * 60000L)
    val local = ArchiveStore.load(provider, symbol, timeframe)
        .filter { it.t in extFrom..toMs }
    val missing = planRangeFetch(
        local.minOfOrNull { it.t } ?: 0, local.maxOfOrNull { it.t } ?: 0,
        local.size, extFrom, toMs)
    var merged = local
    var pages = 0
    var capped = false
    var downloaded = false
    for ((i, seg) in missing.withIndex()) {
        if (isCancelled()) throw RuntimeException("Pengunduhan dibatalkan.")
        onProgress("Mengunduh ${i + 1}/${missing.size}…")
        val r = fetchRangeCandles(provider, symbol, timeframe, seg.first, seg.second, isCancelled)
        pages += r.pages
        if (r.capped) capped = true
        if (r.candles.isNotEmpty()) downloaded = true
        merged = mergeCandles(merged, r.candles)
    }
    merged = merged.filter { it.t in extFrom..toMs }.sortedBy { it.t }
    if (merged.isNotEmpty()) {
        ArchiveStore.save(provider, symbol, timeframe, merged)
    }
    val now = System.currentTimeMillis()
    val source = when {
        local.isNotEmpty() && downloaded -> "arsip+jaringan"
        downloaded -> "jaringan"
        local.isNotEmpty() -> "arsip"
        else -> "kosong"
    }
    return EnsuredRange(merged, fromMs, toMs, source, now, pages, capped,
        local.isNotEmpty(), downloaded)
}
