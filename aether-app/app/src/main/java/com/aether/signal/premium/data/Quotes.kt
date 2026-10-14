package com.aether.signal.premium.data

import org.json.JSONArray
import org.json.JSONObject

// Kutipan harga ringan untuk pembaruan Market berkala (murni-IO, teruji JVM).
//
// Prinsip Cauchy Mirror dari keterbatasan provider yang terverifikasi:
// - Binance: endpoint batch `symbols=[...]` terbukti FLAKY pada mirror
//   (HTTP 400 intermiten, -1100). dipakai endpoint full `/ticker/24hr`
//   (1 panggilan/siklus, bobot 80) + filter client-side. Jangan klaim
//   200 pair diperbarui instan bila siklus belum selesai.
// - Bybit: `/v5/market/tickers?category=spot` (1 panggilan, full list).
// - Yahoo: tanpa endpoint batch publik (v7 butuh crumb) -> per-simbol
//   v8 chart ringan (range=1d, hanya 2 close terakhir untuk chg).
// - Demo: lokal, tanpa jaringan.
// WebSocket TIDAK dipakai (tak ada dependensi WS di aplikasi; biaya
// baterai/lifecycle + batas koneksi). Mekanisme resmi = polling REST
// batch dengan interval terdokumentasi + label kesegaran per baris.

/** Satu kutipan harga. chgPct = null bila provider tak memberinya. */
data class QuoteTick(
    val sym: String,
    val price: Double,
    val chgPct: Double?,
    val fetchedAt: Long
)

/** Hasil satu siklus refresh: kutipan + yang hilang + sumber. */
data class QuoteBatch(
    val ticks: Map<String, QuoteTick>,
    val missing: List<String>,
    val source: String,
    val tookMs: Long,
    val at: Long = System.currentTimeMillis()
)

/** Ambil peta full-list lalu saring ke simbol yang diminta (deterministik). */
fun pickQuotes(
    requested: List<String>,
    entries: List<Triple<String, Double, Double?>>,
    at: Long
): QuoteBatch {
    val want = LinkedHashSet(requested.map { it.trim().uppercase() })
    val got = LinkedHashMap<String, QuoteTick>()
    for ((sym, price, chg) in entries) {
        val s = sym.trim().uppercase()
        if (want.contains(s) && !got.containsKey(s) && price.isFinite() && price > 0) {
            got[s] = QuoteTick(s, price, chg?.takeIf { it.isFinite() }, at)
        }
    }
    return QuoteBatch(got, (want - got.keys).sorted(), "", 0, at)
}

private fun binanceTickerEntries(tmo: Int): List<Triple<String, Double, Double?>> {
    val (_, txt) = binanceFetch("/api/v3/ticker/24hr", tmo)
    val j = JSONArray(txt)
    val out = ArrayList<Triple<String, Double, Double?>>(j.length())
    for (k in 0 until j.length()) {
        val o = j.getJSONObject(k)
        out.add(Triple(
            o.optString("symbol", ""),
            o.optString("lastPrice", "NaN").toDoubleOrNull() ?: Double.NaN,
            o.optString("priceChangePercent", "").toDoubleOrNull()
        ))
    }
    return out
}

private fun bybitTickerEntries(tmo: Int): List<Triple<String, Double, Double?>> {
    val txt = fetchText("https://api.bybit.com/v5/market/tickers?category=spot", tmo)
    val list = JSONObject(txt).getJSONObject("result").getJSONArray("list")
    val out = ArrayList<Triple<String, Double, Double?>>(list.length())
    for (k in 0 until list.length()) {
        val o = list.getJSONObject(k)
        out.add(Triple(
            o.optString("symbol", ""),
            o.optString("lastPrice", "NaN").toDoubleOrNull() ?: Double.NaN,
            o.optString("price24hPcnt", "").toDoubleOrNull()?.let { it * 100 }
        ))
    }
    return out
}

/** Yahoo ringan: 2 close valid terakhir (harga + chg vs close sebelumnya). */
private fun yahooQuote(rawSymbol: String, tmo: Int): Triple<String, Double, Double?> {
    val ysym = yahooSymbol(rawSymbol)
        ?: throw RuntimeException("Yahoo tidak mengenal pair \"$rawSymbol\".")
    val j = JSONObject(fetchText(
        "https://query1.finance.yahoo.com/v8/finance/chart/" +
            "${java.net.URLEncoder.encode(ysym, "UTF-8")}?interval=15m&range=1d&includePrePost=false",
        tmo))
    val res = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
    val q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
    val closes = q.getJSONArray("close")
    val valid = ArrayList<Double>()
    for (k in 0 until closes.length()) {
        val c = closes.optDouble(k, Double.NaN)
        if (c.isFinite() && c > 0) valid.add(c)
    }
    if (valid.isEmpty()) throw RuntimeException("Yahoo mengembalikan data kosong untuk $rawSymbol.")
    val last = valid.last()
    val prev = if (valid.size >= 2) valid[valid.size - 2] else null
    val chg = if (prev != null && prev > 0) (last - prev) / prev * 100 else null
    return Triple(rawSymbol.trim().uppercase(), last, chg)
}

/**
 * Satu siklus refresh kutipan untuk daftar simbol.
 * - binance/bybit: 1 panggilan full-list + saring lokal (hemat request).
 * - yahoo: per-simbol ringan (universe kecil, interval refresh lebih jarang).
 * - demo: lokal instan dari generator seeded (sumber "demo", bukan live).
 * Tak pernah melempar untuk simbol individual yang hilang (masuk `missing`);
 * melempar hanya bila seluruh sumber gagal (diwayat jujur di pemanggil).
 */
fun fetchQuotes(
    provider: String,
    symbols: List<String>,
    timeoutMs: Int = -1
): QuoteBatch {
    val t0 = System.nanoTime()
    val at = System.currentTimeMillis()
    val want = symbols.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    if (want.isEmpty()) return QuoteBatch(emptyMap(), emptyList(), provider, 0, at)
    val tmo = if (timeoutMs > 0) timeoutMs else timeoutFor(provider)
    return when (provider) {
        "binance" -> {
            val b = pickQuotes(want, binanceTickerEntries(tmo), at)
            b.copy(source = "binance ticker/24hr", tookMs = (System.nanoTime() - t0) / 1_000_000)
        }
        "bybit" -> {
            val b = pickQuotes(want, bybitTickerEntries(tmo), at)
            b.copy(source = "bybit tickers spot", tookMs = (System.nanoTime() - t0) / 1_000_000)
        }
        "yahoo" -> {
            val got = LinkedHashMap<String, QuoteTick>()
            var lastErr: Exception? = null
            for (s in want) {
                try {
                    val (sym, price, chg) = yahooQuote(s, tmo)
                    got[sym] = QuoteTick(sym, price, chg, at)
                } catch (e: Exception) { lastErr = e }
            }
            if (got.isEmpty()) throw lastErr
                ?: RuntimeException("Yahoo gagal untuk semua ${want.size} simbol.")
            QuoteBatch(got, (want.toSet() - got.keys).sorted(), "yahoo chart-1d",
                (System.nanoTime() - t0) / 1_000_000, at)
        }
        "demo" -> {
            val got = LinkedHashMap<String, QuoteTick>()
            for (s in want) {
                val px = guessPrice(s)
                if (px.isFinite() && px > 0) got[s] = QuoteTick(s, px, null, at)
            }
            QuoteBatch(got, (want.toSet() - got.keys).sorted(), "demo (offline)",
                (System.nanoTime() - t0) / 1_000_000, at)
        }
        else -> throw RuntimeException("Provider tidak dikenal: $provider")
    }
}
