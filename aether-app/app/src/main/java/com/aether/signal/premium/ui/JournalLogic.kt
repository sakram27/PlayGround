package com.aether.signal.premium.ui

import org.json.JSONArray
import org.json.JSONObject

// FITUR 8 (V14): jurnal trading lokal — model + logika MURNI & teruji JVM.
// Penyimpanan (SharedPreferences) ditangani App; di sini hanya bentuk data,
// validasi, serialisasi, penggabungan impor, dan penyaringan.
// Kunci relasi transaksi: "asset|direction|entryTime|exitTime" — SAMA dengan
// kunci dedup App.addHist, sehingga konsisten. Tak ada relasi by kemiripan
// waktu/harga: hanya kunci eksak.

const val JOURNAL_CAP = 500
const val JOURNAL_TITLE_MAX = 120
const val JOURNAL_BODY_MAX = 4000
const val JOURNAL_PAIR_MAX = 20

data class JournalEntry(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pair: String,
    val kind: String,
    val tradeKey: String,
    val title: String,
    val body: String
)

/** Kunci identitas transaksi backtest (eksak, stabil lintas restart). */
fun tradeKeyOf(asset: String, direction: String, entryTime: Long, exitTime: Long): String =
    "$asset|$direction|$entryTime|$exitTime"

fun tradeKeyOf(t: com.aether.signal.premium.engine.Trade): String =
    tradeKeyOf(t.asset, t.direction, t.entryTime, t.exitTime)

/** Normalisasi + validasi satu entri. Null bila tak layak simpan. */
fun sanitizeJournalEntry(e: JournalEntry): JournalEntry? {
    if (e.id.isBlank()) return null
    if (e.createdAt <= 0) return null
    val upd = if (e.updatedAt >= e.createdAt) e.updatedAt else e.createdAt
    return e.copy(
        id = e.id.trim().take(64),
        pair = e.pair.trim().uppercase().take(JOURNAL_PAIR_MAX),
        kind = e.kind.trim().take(24).ifEmpty { "catatan" },
        tradeKey = e.tradeKey.trim().take(128),
        title = e.title.trim().take(JOURNAL_TITLE_MAX),
        body = e.body.trim().take(JOURNAL_BODY_MAX),
        updatedAt = upd
    )
}

fun encodeJournal(list: List<JournalEntry>): String {
    val a = JSONArray()
    for (e in list) {
        a.put(JSONObject().put("id", e.id).put("createdAt", e.createdAt)
            .put("updatedAt", e.updatedAt).put("pair", e.pair).put("kind", e.kind)
            .put("tradeKey", e.tradeKey).put("title", e.title).put("body", e.body))
    }
    return a.toString()
}

fun parseJournal(raw: String?): MutableList<JournalEntry> {
    val out = ArrayList<JournalEntry>()
    if (raw.isNullOrEmpty()) return out
    try {
        val a = JSONArray(raw)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val e = sanitizeJournalEntry(JournalEntry(
                o.optString("id"), o.optLong("createdAt", 0), o.optLong("updatedAt", 0),
                o.optString("pair"), o.optString("kind"), o.optString("tradeKey"),
                o.optString("title"), o.optString("body")))
            if (e != null) out.add(e)
        }
    } catch (e: Exception) { /* data rusak → kosong, jangan crash */ }
    while (out.size > JOURNAL_CAP) out.removeAt(out.size - 1)
    return out
}

/**
 * Gabung hasil impor ke lokal (by id). Entri id-baru ditambahkan (cap dijaga);
 * id yang sudah ada DILEWATI (lokal menang) dan dihitung — tak ada timpa diam-diam.
 * @return Triple(hasil, jmlBaru, jmlDilewati)
 */
fun mergeJournal(local: List<JournalEntry>, imported: List<JournalEntry>): Triple<List<JournalEntry>, Int, Int> {
    val ids = local.map { it.id }.toHashSet()
    val merged = ArrayList(local)
    var added = 0
    var skipped = 0
    for (e in imported) {
        if (ids.contains(e.id)) { skipped++; continue }
        ids.add(e.id); merged.add(e); added++
    }
    merged.sortByDescending { it.createdAt }
    while (merged.size > JOURNAL_CAP) merged.removeAt(merged.size - 1)
    return Triple(merged, added, skipped)
}

/**
 * Saring jurnal: query cocok ke judul/isi/pair (tak peduli kapital);
 * pair eksak ("" = semua); periode by createdAt (0 = tanpa batas).
 */
fun filterJournal(
    list: List<JournalEntry>,
    query: String,
    pair: String,
    fromTs: Long = 0,
    toTs: Long = 0
): List<JournalEntry> {
    val q = query.trim().lowercase()
    val p = pair.trim().uppercase()
    return list.filter { e ->
        (q.isEmpty() || e.title.lowercase().contains(q) || e.body.lowercase().contains(q) || e.pair.lowercase().contains(q)) &&
            (p.isEmpty() || e.pair == p) &&
            (fromTs <= 0 || e.createdAt >= fromTs) &&
            (toTs <= 0 || e.createdAt <= toTs)
    }
}

/** Catatan yang terkait eksak dengan satu transaksi (by tradeKey). */
fun notesForTrade(list: List<JournalEntry>, key: String): List<JournalEntry> {
    if (key.isEmpty()) return emptyList()
    return list.filter { it.tradeKey == key }.sortedByDescending { it.createdAt }
}
