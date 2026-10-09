package com.aether.signal.premium.ui

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// FITUR 3 & 4 (V12): riwayat notifikasi + jam tenang — logika MURNI & teruji JVM.
// Penyimpanan Android ditangani NotifBus; di sini hanya bentuk data + aturan waktu.

// ---------- Jam tenang ----------

const val MINUTES_PER_DAY = 1440

/**
 * Jam tenang. [days] memakai Calendar.DAY_OF_WEEK (1=Minggu … 7=Sabtu) dan
 * merujuk pada hari KETIKA rentang DIMULAI (penting untuk rentang lewat tengah malam).
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startMin: Int = 22 * 60,
    val endMin: Int = 7 * 60,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)
)

/** Nama hari untuk UI (urut Senin–Minggu); kunci = Calendar.DAY_OF_WEEK. */
val QUIET_DAY_ORDER: List<Pair<Int, String>> = listOf(
    2 to "Sen", 3 to "Sel", 4 to "Rab", 5 to "Kam", 6 to "Jum", 7 to "Sab", 1 to "Min"
)

/**
 * Apakah waktu kini (menit sejak tengah malam + hari Calendar) berada dalam jam tenang.
 * Batas mulai inklusif, batas selesai eksklusif.
 */
fun quietActive(q: QuietHours, nowMinOfDay: Int, dayOfWeek: Int): Boolean {
    if (!q.enabled || q.days.isEmpty()) return false
    if (q.startMin == q.endMin) return false
    val m = ((nowMinOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return if (q.endMin > q.startMin) {
        dayOfWeek in q.days && m >= q.startMin && m < q.endMin
    } else {
        if (m >= q.startMin) dayOfWeek in q.days
        else if (m < q.endMin) {
            val prev = if (dayOfWeek == 1) 7 else dayOfWeek - 1
            prev in q.days
        } else false
    }
}

/** Validasi; null bila valid. */
fun quietValidationError(q: QuietHours): String? = when {
    q.startMin !in 0 until MINUTES_PER_DAY || q.endMin !in 0..MINUTES_PER_DAY -> "Waktu tidak valid."
    q.startMin == q.endMin -> "Waktu mulai dan selesai tidak boleh sama."
    q.days.isEmpty() -> "Pilih minimal satu hari."
    else -> null
}

fun fmtMinuteOfDay(min: Int): String =
    String.format(Locale.US, "%02d:%02d", ((min % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY / 60,
        ((min % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY % 60)

fun quietDaysLabel(days: Set<Int>): String {
    if (days.isEmpty()) return "tidak ada hari"
    if (days.size == 7) return "setiap hari"
    return QUIET_DAY_ORDER.filter { it.first in days }.joinToString(", ") { it.second }
}

/** Ringkasan jadwal untuk Settings; jujur bila nonaktif/kosong. */
fun quietSummary(q: QuietHours): String {
    if (!q.enabled) return "Jam tenang NONAKTIF."
    if (q.days.isEmpty()) return "Jam tenang aktif, tetapi belum ada hari dipilih — tidak akan menahan notifikasi."
    if (q.startMin == q.endMin) return "Jam tenang aktif, tetapi jam mulai = selesai — dianggap tidak menahan."
    if (q.startMin == 0 && q.endMin == MINUTES_PER_DAY) {
        return "Jam tenang AKTIF · sepanjang hari · ${quietDaysLabel(q.days)}."
    }
    val cross = q.endMin < q.startMin
    return "Jam tenang AKTIF · ${fmtMinuteOfDay(q.startMin)}–${fmtMinuteOfDay(q.endMin)}" +
        (if (cross) " (lewat tengah malam)" else "") + " · ${quietDaysLabel(q.days)}."
}

// ---------- Preset jadwal jam tenang (V13, sekali ketuk) ----------

/** Preset 1: Malam Hari Kerja — Senin–Jumat 22:00→07:00 (lewat tengah malam). */
val QUIET_PRESET_WORK = QuietHours(true, 22 * 60, 7 * 60, setOf(2, 3, 4, 5, 6))

/**
 * Preset 2: Akhir Pekan Penuh — Sabtu+Minggu sepanjang hari.
 * Diwakili start=00:00 & end=1440 (tengah malam hari berikut = akhir hari).
 */
val QUIET_PRESET_WEEKEND = QuietHours(true, 0, MINUTES_PER_DAY, setOf(7, 1))

/** Cocokkan jadwal ke preset ("work"/"weekend"); null = tidak cocok (Kustom). */
fun matchQuietPreset(q: QuietHours): String? = when {
    q.startMin == QUIET_PRESET_WORK.startMin && q.endMin == QUIET_PRESET_WORK.endMin &&
        q.days == QUIET_PRESET_WORK.days -> "work"
    q.startMin == QUIET_PRESET_WEEKEND.startMin && q.endMin == QUIET_PRESET_WEEKEND.endMin &&
        q.days == QUIET_PRESET_WEEKEND.days -> "weekend"
    else -> null
}

/** Label status jadwal: nama preset, "Kustom" bila manual tak cocok, "Nonaktif" bila mati. */
fun quietPresetLabel(q: QuietHours): String {
    if (!q.enabled) return "Nonaktif"
    return when (matchQuietPreset(q)) {
        "work" -> "Malam Hari Kerja"
        "weekend" -> "Akhir Pekan"
        else -> "Kustom"
    }
}

fun encodeQuietDays(days: Set<Int>): String = days.sorted().joinToString(",")

fun parseQuietDays(raw: String?): Set<Int> =
    (raw ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

// ---------- Riwayat notifikasi ----------

const val NOTIF_HISTORY_CAP = 200

/**
 * Satu catatan notifikasi. [status] hanya diisi berdasarkan bukti nyata:
 * "DIKIRIM" (perintah tampil diterima Android tanpa error), "DITAHAN" (jam tenang),
 * "GAGAL_IZIN" (izin notifikasi mati saat akan tampil), "GAGAL" (exception lain).
 */
data class NotifRec(
    val at: Long,
    val kind: String,
    val pair: String,
    val tf: String,
    val dir: String,
    val price: Double,
    val status: String
)

fun notifKindLabel(kind: String): String = when (kind) {
    "entry" -> "Sinyal entry"
    "tp" -> "Take profit"
    "sl" -> "Stop loss"
    else -> kind
}

fun notifStatusLabel(status: String): String = when (status) {
    "DIKIRIM" -> "Dipublikasikan ke Android"
    "DITAHAN" -> "Ditahan jam tenang"
    "GAGAL_IZIN" -> "Tidak tampil: izin notifikasi mati"
    "GAGAL" -> "Gagal dikirim"
    else -> status
}

/** Tambah catatan terbaru di depan, batasi jumlah agar penyimpanan tidak membengkak. */
fun addNotifRec(list: MutableList<NotifRec>, rec: NotifRec, cap: Int = NOTIF_HISTORY_CAP) {
    list.add(0, rec)
    while (list.size > cap) list.removeAt(list.size - 1)
}

fun encodeNotifHistory(list: List<NotifRec>): String {
    val a = JSONArray()
    for (r in list) {
        a.put(JSONObject().put("at", r.at).put("kind", r.kind).put("pair", r.pair)
            .put("tf", r.tf).put("dir", r.dir).put("price", r.price).put("status", r.status))
    }
    return a.toString()
}

fun parseNotifHistory(raw: String?): MutableList<NotifRec> {
    val out = ArrayList<NotifRec>()
    if (raw.isNullOrEmpty()) return out
    try {
        val a = JSONArray(raw)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val at = o.optLong("at", 0)
            if (at <= 0) continue
            out.add(NotifRec(at, o.optString("kind"), o.optString("pair"), o.optString("tf"),
                o.optString("dir"), o.optDouble("price", 0.0), o.optString("status")))
        }
    } catch (e: Exception) { /* data rusak → kosong, jangan crash */ }
    while (out.size > NOTIF_HISTORY_CAP) out.removeAt(out.size - 1)
    return out
}

// ---------- Ekspor CSV + pencarian/penyaringan riwayat (V13) ----------

/**
 * Bangun isi CSV dari catatan nyata apa adanya (tanpa fabrikasi, tanpa rahasia —
 * NotifRec memang tidak menyimpan API key/token).
 * Memakai csvCell + isoUtc yang sama dengan CSV riwayat trade (RFC4180 & UTC konsisten).
 */
fun buildNotifCsv(list: List<NotifRec>): String {
    val sb = StringBuilder("n,waktu_utc,jenis,simbol,arah,timeframe,harga,status\n")
    list.forEachIndexed { i, r ->
        sb.append(listOf(
            (i + 1).toString(), isoUtc(r.at), notifKindLabel(r.kind), r.pair,
            r.dir, r.tf, r.price.toString(), notifStatusLabel(r.status)
        ).joinToString(",") { csvCell(it) }).append("\n")
    }
    return sb.toString()
}

/**
 * Saring riwayat aktual (urutan dipertahankan: terbaru dulu, seperti penyimpanan).
 * - query: cocokkan simbol (tak peduli huruf besar/kecil); kosong = semua.
 * - kind: "all" | "entry" | "tp" | "sl" (hanya jenis yang benar-benar dicatat).
 * - status: "all" | "sent"(DIKIRIM) | "held"(DITAHAN) | "failed"(GAGAL/GAGAL_IZIN).
 * Tidak menghapus/mengubah catatan maupun status.
 */
fun filterNotifHistory(
    list: List<NotifRec>,
    query: String,
    kind: String,
    status: String
): List<NotifRec> {
    val q = query.trim().lowercase()
    return list.filter { r ->
        (q.isEmpty() || r.pair.lowercase().contains(q)) &&
            (kind == "all" || r.kind == kind) &&
            when (status) {
                "all" -> true
                "sent" -> r.status == "DIKIRIM"
                "held" -> r.status == "DITAHAN"
                "failed" -> r.status == "GAGAL" || r.status == "GAGAL_IZIN"
                else -> true
            }
    }
}
