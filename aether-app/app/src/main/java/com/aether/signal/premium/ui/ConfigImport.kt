package com.aether.signal.premium.ui

import com.aether.signal.premium.data.BACKTEST_TIMEFRAMES
import com.aether.signal.premium.data.MARKET_TIMEFRAMES
import com.aether.signal.premium.data.PROVIDER_IDS
import org.json.JSONObject

// P11: impor konfigurasi antarperangkat — validasi MURNI (teruji JVM) + terapkan atomik.
// Skema: 1 = lama (tanpa schemaVersion), 2 = kini (+batterySaver, +notif).
// Termasuk: provider, strategi, timeframe, watchlist kustom, batas, pair engine,
// mode hemat, sakelar notifikasi. TIDAK termasuk: sinyal/riwayat live (arsip baca
// saja, tidak dipulihkan), kredensial (aplikasi tidak menyimpannya).

const val CONFIG_SCHEMA_VERSION = 2

data class ImportPlan(
    val schemaVersion: Int,
    val provider: String?,
    val strategy: String?,
    val timeframe: String?,
    val customPairs: List<String>,
    val invalidCustom: Int,
    val watchLimit: Int?,
    val engPairs: List<String>,
    val batterySaver: Boolean?,
    val notif: Map<String, Boolean>,
    val quiet: QuietHours?,
    val warnings: List<String>
)

/** Parse + validasi PENUH sebelum ada yang diterapkan. Gagal → exception, nol perubahan. */
fun parseConfigImport(txt: String): ImportPlan {
    val o = try { JSONObject(txt) } catch (e: Exception) {
        throw RuntimeException("Berkas bukan JSON valid.")
    }
    if (o.optString("app") != "aether-signal") throw RuntimeException("Bukan berkas ekspor Aether Signal.")
    val schema = if (o.has("schemaVersion")) o.optInt("schemaVersion", -1) else 1
    if (schema < 1) throw RuntimeException("Nomor skema tidak valid.")
    if (schema > CONFIG_SCHEMA_VERSION) {
        throw RuntimeException("Berkas dari aplikasi lebih baru (skema $schema). Perbarui aplikasi dulu.")
    }
    val warnings = ArrayList<String>()
    if (schema < CONFIG_SCHEMA_VERSION) {
        warnings.add("Berkas lama (skema $schema): mode hemat & notifikasi memakai nilai saat ini.")
    }
    val cfg = o.optJSONObject("config")
        ?: throw RuntimeException("Berkas tidak berisi bagian konfigurasi.")
    val provider = cfg.optString("provider", "").takeIf { it.isNotEmpty() }
    if (provider != null && !PROVIDER_IDS.contains(provider)) {
        throw RuntimeException("Provider \"$provider\" tidak dikenal aplikasi ini.")
    }
    val strategy = cfg.optString("strategy", "").takeIf { it.isNotEmpty() }
    val tf = cfg.optString("timeframe", "").takeIf { it.isNotEmpty() }
    if (tf != null && !BACKTEST_TIMEFRAMES.contains(tf) && !MARKET_TIMEFRAMES.contains(tf)) {
        throw RuntimeException("Timeframe \"$tf\" tidak didukung.")
    }
    val customs = ArrayList<String>()
    var invalid = 0
    val cp = cfg.optJSONArray("customPairs")
    if (cp != null) {
        for (i in 0 until cp.length()) {
            val s = cp.optString(i, "").trim()
            if (s.isEmpty()) { invalid++; continue }
            if (customs.size >= MAX_WATCH_PAIRS) { invalid++; continue }
            if (!customs.contains(s)) customs.add(s) else invalid++
        }
        if (invalid > 0) warnings.add("$invalid entri kustom dilewati (kosong/ganda/melebihi batas).")
    }
    val watchLimit = if (cfg.has("watchLimit")) cfg.optInt("watchLimit", -1).coerceIn(1, MAX_WATCH_PAIRS) else null
    val engs = ArrayList<String>()
    val ep = cfg.optJSONArray("engPairs")
    if (ep != null) {
        for (i in 0 until ep.length()) {
            val s = ep.optString(i, "").trim()
            if (s.isNotEmpty() && !engs.contains(s)) engs.add(s)
        }
        if (engs.isEmpty()) warnings.add("Daftar pair engine kosong: memakai yang aktif saat ini.")
    }
    val battery = if (cfg.has("batterySaver")) cfg.optBoolean("batterySaver") else null
    val notif = HashMap<String, Boolean>()
    val nj = cfg.optJSONObject("notif")
    if (nj != null) {
        for (k in listOf("entry", "sl", "tp", "sound", "vibrate")) {
            if (nj.has(k)) notif[k] = nj.optBoolean(k)
        }
    }
    val quiet = cfg.optJSONObject("quiet")?.let { qo ->
        val start = qo.optInt("start", 22 * 60).coerceIn(0, MINUTES_PER_DAY - 1)
        val end = qo.optInt("end", 7 * 60).coerceIn(0, MINUTES_PER_DAY - 1)
        val days = parseQuietDays(qo.optString("days", ""))
        QuietHours(qo.optBoolean("enabled", false), start, end, days)
    }
    return ImportPlan(schema, provider, strategy, tf, customs, invalid,
        watchLimit, engs, battery, notif, quiet, warnings)
}

/** Ringkasan untuk dialog persetujuan (Bahasa Indonesia). */
fun importSummary(p: ImportPlan): String {
    val b = StringBuilder()
    b.append("Skema v${p.schemaVersion} · yang akan dipulihkan:\n")
    b.append("• Provider: ${p.provider ?: "(tetap)"}\n")
    b.append("• Strategi: ${p.strategy ?: "(tetap)"}\n")
    b.append("• Timeframe: ${p.timeframe ?: "(tetap)"}\n")
    b.append("• Pair kustom: ${p.customPairs.size}" +
        if (p.customPairs.isNotEmpty()) " (${p.customPairs.take(5).joinToString(", ")}${if (p.customPairs.size > 5) "…" else ""})" else "" + "\n")
    b.append("• Batas watchlist: ${p.watchLimit ?: "(tetap)"}\n")
    b.append("• Pair engine: ${if (p.engPairs.isNotEmpty()) p.engPairs.joinToString(", ") else "(tetap)"}\n")
    b.append("• Hemat baterai: ${p.batterySaver?.let { if (it) "aktif" else "mati" } ?: "(tetap)"}\n")
    if (p.notif.isNotEmpty()) b.append("• Notifikasi: ${p.notif.entries.joinToString(", ") { "${it.key}=${if (it.value) "on" else "off"}" }}\n")
    p.quiet?.let { b.append("• Jam tenang: ${quietSummary(it)}\n") }
    for (w in p.warnings) b.append("⚠ $w\n")
    b.append("Tidak dipulihkan: sinyal & riwayat live, cache harga, status kesehatan.")
    return b.toString()
}
