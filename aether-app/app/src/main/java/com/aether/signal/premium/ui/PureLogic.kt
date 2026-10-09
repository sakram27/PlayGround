package com.aether.signal.premium.ui

// Logika MURNI tanpa dependensi Android (teruji JVM). UI mendelegasikan ke sini.

// C1/C2: sanitasi sparkline — hanya finite, maksimal 120 titik, tanpa fabrikasi.
fun sanitizeSpark(raw: List<Double>): List<Float> =
    raw.filter { it.isFinite() }.takeLast(120).map { it.toFloat() }

// F1: rentang sumbu equity — padding 15% + penjaga datar/nol.
// Hanya menentukan BATAS SUMBU, bukan nilai data (hasil numerik tak berubah).
fun equityPlotRange(values: List<Double>): Pair<Float, Float> {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return 0f to 1f
    var mn = clean.minOrNull()!!
    var mx = clean.maxOrNull()!!
    if (!(mx > mn)) {
        val c = mn
        val d = kotlin.math.abs(c) * 0.01 + 1e-9
        mn = c - d
        mx = c + d
        if (mn == 0.0 && mx == 0.0) { mn = -1.0; mx = 1.0 }
    }
    val pad = (mx - mn) * 0.15
    return (mn - pad).toFloat() to (mx + pad).toFloat()
}

// D: dedup kejadian notifikasi — tambah bila baru, batasi ukuran.
fun dedupAdd(s: MutableSet<String>, key: String, cap: Int = 500): Boolean {
    if (s.contains(key)) return false
    s.add(key)
    while (s.size > cap) s.remove(s.first())
    return true
}

// P9: pindah item dalam daftar kustom (murni, teruji). Dipakai drag-reorder;
// persistensi urutan ditangani pemanggil via LinkedHashSet.
fun <T> moveItem(list: MutableList<T>, from: Int, to: Int): Boolean {
    if (from !in list.indices || to !in list.indices || from == to) return false
    val s = list.removeAt(from)
    list.add(to, s)
    return true
}

// Batas wajar jumlah watchlist (C9): tiap pair = 1 request + komputasi indikator
// 200 candle. 30 menjaga baterai/rate-limit; ditampilkan di UI.
const val MAX_WATCH_PAIRS = 30
const val DEFAULT_WATCH_LIMIT = 12

// A7: serialisasi cache harga terakhir (murni, teruji). Format: price|chg|spark|t
// price = string tampilan, chg = "null" bila tak ada, spark = csv maks 60 titik.
fun serializeLastPrice(price: String, chg: Double?, spark: List<Double>, t: Long): String {
    val c = if (chg != null && chg.isFinite()) chg.toString() else "null"
    val s = spark.filter { it.isFinite() }.takeLast(60).joinToString(",")
    return "$price|$c|$s|$t"
}

data class LastPrice(val price: String, val chg: Double?, val spark: List<Double>, val t: Long)

fun parseLastPrice(raw: String?): LastPrice? {
    if (raw.isNullOrEmpty()) return null
    val p = raw.split("|")
    if (p.size != 4) return null
    val t = p[3].toLongOrNull() ?: return null
    if (t <= 0 || p[0].isEmpty()) return null
    val chg = if (p[1] == "null") null else p[1].toDoubleOrNull()
    val spark = if (p[2].isEmpty()) emptyList() else p[2].split(",").mapNotNull { it.toDoubleOrNull() }.filter { it.isFinite() }.takeLast(60)
    return LastPrice(p[0], chg, spark, t)
}

// A7: label kebasaan harga cache ("5 mnt lalu", "2 jam lalu", ...).
fun staleAgeLabel(updatedAt: Long, now: Long): String {
    val d = (now - updatedAt).coerceAtLeast(0)
    val m = d / 60000
    return when {
        m < 1 -> "baru saja"
        m < 60 -> "$m mnt lalu"
        m < 60 * 24 -> "${m / 60} jam lalu"
        else -> "${m / (60 * 24)} hari lalu"
    }
}

fun fmtClock(ts: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ts
    return String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

// E: jeda adaptif monitoring MURNI & teruji: 60 normal → 120 → 300 maks.
const val MONITOR_INTERVAL_SEC = 60L
const val MONITOR_BACKOFF_SEC = 120L
const val MONITOR_BACKOFF_MAX_SEC = 300L

fun backoffDelaySec(fails: Int): Long = when {
    fails <= 0 -> MONITOR_INTERVAL_SEC
    fails == 1 -> MONITOR_BACKOFF_SEC
    else -> MONITOR_BACKOFF_MAX_SEC
}

// P7: interval efektif dengan mode hemat (murni, teruji). Hemat mengikuti
// timeframe: interval = maks(300 dtk, 30×TF mnt), dibatasi 900 dtk.
// Normal tetap 60 dtk seperti sebelumnya.
const val SAVER_MIN_SEC = 300L
const val SAVER_MAX_SEC = 900L

fun saverIntervalSec(tfMinutes: Int): Long {
    val tf = if (tfMinutes > 0) tfMinutes else 15
    return minOf(SAVER_MAX_SEC, maxOf(SAVER_MIN_SEC, tf * 30L))
}

/** Jeda terjadwal efektif: basis (normal/hemat) lalu backoff bila gagal beruntun. */
fun effectiveDelaySec(fails: Int, batterySaver: Boolean, tfMinutes: Int): Long {
    val base = if (batterySaver) saverIntervalSec(tfMinutes) else MONITOR_INTERVAL_SEC
    if (fails <= 0) return base
    return maxOf(base, backoffDelaySec(fails))
}
// E: mesin status monitoring MURNI & teruji (6 kondisi realistis).
fun deriveMonitorState(running: Boolean, lastError: String?, lastTickMs: Long, lastSummary: String): String = when {
    !running && lastError != null -> "Gagal berjalan: $lastError"
    !running && lastTickMs == 0L -> "Nonaktif."
    !running -> "Dihentikan."
    lastSummary.startsWith("Menunggu") -> "Menunggu koneksi · $lastSummary"
    else -> "Aktif memantau · ${lastSummary.ifEmpty { "menyiapkan…" }}"
}

// P10: CSV riwayat — hanya kolom yang benar-benar disimpan di Trade
// (asset, direction, entry, exit, pnl, result, entryTime, exitTime).
// RFC4180: sel dikutif bila mengandung koma/kutip/baris baru.
fun csvCell(s: String): String {
    return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else s
}

fun isoUtc(ts: Long): String {
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.format(java.util.Date(ts))
    } catch (e: Exception) { "" }
}

/** Bangun isi CSV dari data Trade apa adanya (tanpa fabrikasi). */
fun buildHistCsv(trades: List<com.aether.signal.premium.engine.Trade>): String {
    val sb = StringBuilder("n,asset,time_masuk,time_keluar,arah,entry,exit,pnl,hasil\n")
    trades.forEachIndexed { i, t ->
        sb.append(listOf(
            (i + 1).toString(), t.asset, isoUtc(t.entryTime), isoUtc(t.exitTime),
            t.direction, t.entry.toString(), t.exit.toString(), t.pnl.toString(), t.result
        ).joinToString(",") { csvCell(it) }).append("\n")
    }
    return sb.toString()
}
