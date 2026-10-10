package com.aether.signal.premium.ui

import com.aether.signal.premium.ai.Sig

// Format notifikasi/laporan sinyal KAYA yang ASLI (V18).
// Setiap baris berasal dari objek Sig tersimpan (jejak V16: strategy, confidence,
// reasons, passed/failedFilters, decidedAt, score) atau turunan aritmetika
// langsungnya (jarak, RR). Yang tidak tersimpan → "Tidak tersedia".
// TIDAK ada kalimat narasi yang dikarang; TIDAK ada pemetaan imajinatif
// fragmen mesin menjadi cerita. Builder ini dipakai oleh: teks notifikasi
// (BigText), tombol Bagikan di detail, dan dialog riwayat — satu sumber.

const val RICH_NA = "Tidak tersedia"

// Format angka lokal (murni JVM; konsisten dengan App.fmt/fmtDate untuk tampilan).
private fun rFmt(v: Double, d: Int = 4): String {
    if (!v.isFinite()) return RICH_NA
    return String.format(java.util.Locale.US, "%,.${d}f", v)
}

private fun rTrim(v: Double): String {
    if (!v.isFinite()) return RICH_NA
    var s = String.format(java.util.Locale.US, "%.2f", v)
    s = s.trimEnd('0').trimEnd('.')
    return s
}

private fun rDate(ts: Long): String {
    if (ts <= 0) return RICH_NA
    return try {
        val f = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale("id", "ID"))
        f.format(java.util.Date(ts))
    } catch (e: Exception) { RICH_NA }
}

data class RichSignal(val title: String, val body: String)

/** Judul pendek (baris notifikasi): "Entry LONG BTCUSDT". */
fun richTitle(s: Sig): String = when (s.src) {
    "dryrun-entry" -> "Entry ${s.dir} ${s.pair}"
    "dryrun-TP" -> "Take profit tercapai · ${s.dir} ${s.pair}"
    "dryrun-SL" -> "Stop loss tercapai · ${s.dir} ${s.pair}"
    "dryrun-trail" -> "Trailing stop tersentuh · ${s.dir} ${s.pair}"
    "backtest" -> "Sinyal backtest ${s.dir} ${s.pair}"
    else -> "Sinyal ${s.dir} ${s.pair}"
}

/** Baris alasan: HANYA dari jejak tersimpan; kosong bila tak ada. */
fun richReasonLines(s: Sig): List<String> {
    val out = ArrayList<String>()
    for (r in cleanStrList(s.reasons)) out.add("• $r")
    if (s.passedFilters.isNotEmpty()) {
        for (f in cleanStrList(s.passedFilters)) out.add("• Lolos filter: $f")
    }
    if (s.failedFilters.isNotEmpty()) {
        for (f in cleanStrList(s.failedFilters)) out.add("• Ditolak filter: $f")
    }
    return out
}

/** Nama strategi tampilan: id → nama registri bila dikenal, else apa adanya. */
fun richStrategyName(s: Sig, known: Map<String, String> = emptyMap()): String {
    if (s.strategy.isEmpty()) return RICH_NA
    return known[s.strategy] ?: s.strategy
}

fun buildRichSignal(s: Sig, strategyNames: Map<String, String> = emptyMap()): RichSignal {
    val title = richTitle(s)
    val b = StringBuilder()
    // Status
    val st = deriveSignalStatus(s.src, false, false)
    b.append("${s.pair} · ${s.dir.ifEmpty { RICH_NA }}\n")
    b.append("Timeframe: ${s.tf.ifEmpty { RICH_NA }}\n")
    b.append("Status: ${if (s.src == "dryrun-entry") st.replace(" (posisi paper terbuka)", "").replace(" (tak terlacak di posisi terbuka)", "") else st}\n")
    b.append("\nINFORMASI TRANSAKSI\n")
    val entryLike = isEntryLike(s.src)
    b.append("• ${if (entryLike) "Entry" else "Exit"}: ${rFmt(s.price)}\n")
    b.append("• Stop Loss: ${rFmt(s.sl)}\n")
    b.append("• Take Profit: ${rFmt(s.tp)}\n")
    if (entryLike) {
        val d = signalDistances(s.price, s.sl, s.tp)
        if (d.rr != null && d.rr.isFinite()) {
            b.append("• Risk/Reward: 1:${rTrim(d.rr)}\n")
        } else {
            b.append("• Risk/Reward: $RICH_NA (jarak SL nol/tak valid)\n")
        }
    }
    b.append("\nSTRATEGI PEMICU\n")
    val strat = richStrategyName(s, strategyNames)
    b.append("$strat\n")
    b.append("\nMENGAPA SINYAL INI MUNCUL?\n")
    val lines = richReasonLines(s)
    if (lines.isEmpty()) {
        b.append("• $RICH_NA (jejak keputusan tidak tersimpan untuk sinyal ini)\n")
    } else {
        for (l in lines) b.append("$l\n")
    }
    b.append("\nKONDISI SINYAL\n")
    if (s.score >= 0) {
        b.append("Skor Konfirmasi: ${rTrim(s.score)}/100 (BUKAN probabilitas menang)")
        if (s.scoreDetail.isNotEmpty()) b.append(" · ${s.scoreDetail}")
        b.append("\n")
    } else {
        b.append("Skor Konfirmasi: $RICH_NA\n")
    }
    if (s.confidence > 0 && s.confidence <= 1.0) {
        b.append("Confidence mesin: ${rTrim(s.confidence)}\n")
    }
    if (s.decidedAt > 0) b.append("Diputuskan: ${rDate(s.decidedAt)}\n")
    b.append("Waktu sinyal: ${if (s.t > 0) rDate(s.t) else RICH_NA}\n")
    b.append("\nSinyal edukasi. Bukan nasihat keuangan. Tidak mengeksekusi order.")
    return RichSignal(title, b.toString().trim())
}
