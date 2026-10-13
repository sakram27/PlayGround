package com.aether.signal.premium.ui

// FITUR 8 (V16): digest notifikasi per jam — MURNI & teruji JVM.
// Hanya sinyal ENTRY yang digabung (SL/TP tetap langsung — keluar posisi itu
// kritis waktu). Tiap sinyal tetap dicatat individual di riwayat; ringkasan
// memakai SATU id notifikasi per bucket + onlyAlertOnce → satu bunyi per jam.
// Bucket = jam kalender lokal (zona waktu perangkat, konsisten dengan jam tenang).

/** Kunci bucket jam lokal: "yyyyMMddHH". */
fun digestBucketKey(year: Int, month1: Int, day: Int, hour: Int): String =
    String.format("%04d%02d%02d%02d", year, month1, day, hour)

data class DigestBucket(
    val key: String,
    val hourLabel: String,
    val count: Int,
    val lines: List<String>
)

const val DIGEST_LINES_MAX = 5

/** Tambah satu baris ke bucket (batas baris agar teks ringkas). */
fun digestAdd(b: DigestBucket?, key: String, hourLabel: String, line: String): DigestBucket {
    val base = if (b != null && b.key == key) b else DigestBucket(key, hourLabel, 0, emptyList())
    val lines = (base.lines + line).takeLast(DIGEST_LINES_MAX)
    return base.copy(count = base.count + 1, lines = lines)
}

/** Teks ringkasan: jumlah + isi cukup + jalur buka aplikasi. Tanpa info dihapus. */
fun digestSummary(b: DigestBucket): String {
    val sb = StringBuilder()
    sb.append("${b.count} sinyal entry dalam jam ${b.hourLabel}. ")
    if (b.lines.isNotEmpty()) sb.append(b.lines.joinToString(" · "))
    sb.append(" — ketuk untuk buka Signals.")
    return sb.toString()
}

// ---------- FITUR 9: konsensus multi-timeframe ----------
// Vote per TF: LONG / SHORT / NONE / UNAVAILABLE — dibedakan eksplisit.
// Konsensus HANYA bila semua yang tersedia sepakat; TF tanpa data tak memaksa label.

const val VOTE_LONG = "LONG"
const val VOTE_SHORT = "SHORT"
const val VOTE_NONE = "NONE"
const val VOTE_UNAVAILABLE = "UNAVAILABLE"

data class TfVote(val tf: String, val vote: String, val note: String = "")

data class ConsensusVerdict(val summary: String, val detail: String)

fun consensusVerdict(votes: List<TfVote>): ConsensusVerdict {
    if (votes.isEmpty()) return ConsensusVerdict("Belum ada data", "Tidak ada kerangka waktu yang diperiksa.")
    val avail = votes.filter { it.vote == VOTE_LONG || it.vote == VOTE_SHORT || it.vote == VOTE_NONE }
    val unav = votes.filter { it.vote == VOTE_UNAVAILABLE }
    val longs = avail.count { it.vote == VOTE_LONG }
    val shorts = avail.count { it.vote == VOTE_SHORT }
    val nones = avail.count { it.vote == VOTE_NONE }
    val tail = if (unav.isNotEmpty()) " (${unav.size} TF data tak tersedia: ${unav.joinToString(", ") { it.tf }})" else ""
    val detail = votes.joinToString("\n") {
        "${it.tf}: " + when (it.vote) {
            VOTE_LONG -> "LONG"
            VOTE_SHORT -> "SHORT"
            VOTE_NONE -> "tidak ada sinyal"
            else -> "data tak tersedia" + if (it.note.isNotEmpty()) " (${it.note})" else ""
        }
    }
    val summary = when {
        avail.isEmpty() -> "Belum dapat disimpulkan$tail"
        longs > 0 && shorts == 0 && nones == 0 -> "$longs dari ${avail.size} sepakat LONG$tail"
        shorts > 0 && longs == 0 && nones == 0 -> "$shorts dari ${avail.size} sepakat SHORT$tail"
        longs > 0 && shorts > 0 -> "Arah terbelah (LONG $longs vs SHORT $shorts)$tail"
        else -> "Tidak sepakat penuh (LONG $longs · SHORT $shorts · tanpa sinyal $nones)$tail"
    }
    return ConsensusVerdict(summary, detail)
}
