package com.aether.signal.premium.ui

// FITUR 1/2/4 (V16): jejak keputusan + skor konfirmasi + badge usia — MURNI & teruji JVM.
// Jejak disimpan saat sinyal dibuat (BotEngine/backtest) dan tidak dihitung ulang;
// sinyal lama (tanpa jejak) dinyatakan "tidak tersedia" oleh UI.

// ---------- F1: model jejak (disimpan di Sig, lihat AiInterpreter) ----------

const val TRACE_LIST_MAX = 12

fun cleanStrList(v: List<String>): List<String> =
    v.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(TRACE_LIST_MAX)

fun encodeStrList(v: List<String>): String = cleanStrList(v).joinToString(" | ")
fun parseStrList(raw: String?): List<String> =
    (raw ?: "").split("|").map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(TRACE_LIST_MAX)

// ---------- F2: Skor Konfirmasi 0–100 (BUKAN probabilitas menang) ----------
// Formula terdokumentasi (bobot):
//   F (filter, bobot 60): passed/active*60 — filter nonaktif tak dihitung gagal.
//   C (confidence mesin 0..1, bobot 20): conf*20 — hanya bila conf>0 dan ≤1.
//   R (kualitas RR, bobot 20): clamp(rr/3,0,1)*20 dengan rr=tpD/slD.
// Tiap komponen yang datanya tak ada dinyatakan ABSEN (bukan nol); skor =
// rata-rata berbobot atas komponen yang HADIR. Nol komponen hadir → null.

const val SCORE_W_FILTER = 60.0
const val SCORE_W_CONF = 20.0
const val SCORE_W_RR = 20.0

data class ScoreParts(
    val filterPts: Double?,
    val confPts: Double?,
    val rrPts: Double?,
    val rr: Double?
)

data class SignalScore(val total: Double, val parts: ScoreParts, val coverage: String)

fun scoreParts(passed: Int, active: Int, confidence: Double, slDistPct: Double?, tpDistPct: Double?): ScoreParts {
    val f = if (active > 0 && passed >= 0)
        (passed.coerceIn(0, active).toDouble() / active * SCORE_W_FILTER) else null
    val c = if (confidence > 0 && confidence <= 1.0) confidence * SCORE_W_CONF else null
    var rr: Double? = null
    val r = if (slDistPct != null && tpDistPct != null && slDistPct.isFinite() && tpDistPct.isFinite() && slDistPct > 0) {
        rr = tpDistPct / slDistPct
        rr.coerceIn(0.0, 3.0) / 3.0 * SCORE_W_RR
    } else null
    return ScoreParts(f, c, r, rr)
}

fun signalScore(passed: Int, active: Int, confidence: Double, slDistPct: Double?, tpDistPct: Double?): SignalScore? {
    val p = scoreParts(passed, active, confidence, slDistPct, tpDistPct)
    var sum = 0.0
    var w = 0.0
    val cov = ArrayList<String>()
    if (p.filterPts != null) { sum += p.filterPts; w += SCORE_W_FILTER; cov.add("filter") }
    if (p.confPts != null) { sum += p.confPts; w += SCORE_W_CONF; cov.add("confidence") }
    if (p.rrPts != null) { sum += p.rrPts; w += SCORE_W_RR; cov.add("RR") }
    if (w <= 0) return null
    return SignalScore((sum / w * 100).coerceIn(0.0, 100.0), p, cov.joinToString("+"))
}

/** Penjelasan komponen, mis. "7 dari 9 filter aktif lolos". */
fun scoreExplain(passed: Int, active: Int): String =
    if (active > 0) "$passed dari $active filter aktif lolos" else "tanpa filter aktif"

// ---------- F4: badge usia sinyal ----------
// Ambang eksplisit: usia > STALE_FACTOR × TF → "Kedaluwarsa mungkin".
// Satuan ditangani dalam ms (menit/jam/hari konsisten).

const val STALE_FACTOR = 30

data class StaleBadge(val stale: Boolean, val text: String)

/** tfMinutes dari parseTimeframe; t/nowMs epoch-ms. */
fun staleBadge(t: Long, nowMs: Long, tfMinutes: Int, factor: Int = STALE_FACTOR): StaleBadge {
    if (t <= 0) return StaleBadge(false, "waktu tak tercatat")
    val tf = if (tfMinutes > 0) tfMinutes else 15
    val age = (nowMs - t).coerceAtLeast(0)
    val limit = factor.toLong() * tf * 60000L
    val ageTxt = staleAgeLabel(t, nowMs)
    return if (age > limit) StaleBadge(true, "Kedaluwarsa mungkin · $ageTxt")
    else StaleBadge(false, ageTxt)
}
