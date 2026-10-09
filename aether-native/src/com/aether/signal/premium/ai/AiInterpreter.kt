package com.aether.signal.premium.ai

import com.aether.signal.premium.engine.Trade

// Port interpreter deskriptif ui-premium.js aiAnalyze (bukan mesin AI eksternal):
// agregat DATA → ANALYSIS/OPINION berlabel. Tanpa logika trading.

data class Sig(val pair: String, val tf: String, val dir: String, val price: Double, val sl: Double, val tp: Double, val t: Long, val src: String, val id: String)
data class AiBlock(val title: String, val items: List<Pair<String, String>>) // tag to html-ish text

fun aiAnalyze(sigs: List<Sig>, hist: List<Trade>, summaryLine: String, deep: Boolean): List<AiBlock> {
    if (sigs.isEmpty() && hist.isEmpty() && (summaryLine.isBlank() || summaryLine == "Belum ada hasil."))
        return listOf(AiBlock("Kosong", listOf("DATA" to "Belum ada data engine. Jalankan backtest atau Start engine dulu.")))
    val wins = hist.count { it.result == "WIN" }
    val net = hist.sumOf { it.pnl }
    val wr = if (hist.isNotEmpty()) wins.toDouble() / hist.size * 100 else Double.NaN
    val byPair = HashMap<String, Triple<Int, Int, Double>>()
    for (t in hist) {
        val k = t.asset
        val cur = byPair[k] ?: Triple(0, 0, 0.0)
        byPair[k] = Triple(cur.first + 1, cur.second + (if (t.result == "WIN") 1 else 0), cur.third + t.pnl)
    }
    val pairs = byPair.keys.sortedByDescending { byPair[it]!!.third }
    val longs = sigs.count { it.dir == "LONG" }
    val shorts = sigs.count { it.dir == "SHORT" }
    val last = sigs.firstOrNull()
    val out = ArrayList<AiBlock>()
    out.add(AiBlock("AI Market Summary", listOf(
        "DATA" to "Sinyal tersimpan: ${sigs.size} (LONG $longs / SHORT $shorts). Trade riwayat: ${hist.size}.",
        "ANALYSIS" to if (longs + shorts == 0) "Tidak ada bias arah yang terbaca — engine belum menghasilkan sinyal."
            else if (longs > shorts) "Aliran sinyal condong LONG ($longs vs $shorts)."
            else if (shorts > longs) "Aliran sinyal condong SHORT ($shorts vs $longs)."
            else "Aliran sinyal seimbang LONG/SHORT.",
        "ANALYSIS" to if (deep && pairs.isNotEmpty()) "Pair teratas by net: " + pairs.take(3).joinToString(", ") { "$it (${fmtN(byPair[it]!!.third)})" } + "."
            else "Cakupan pair mengikuti konfigurasi Backtest & Market Hub."
    )))
    out.add(AiBlock("Signal Analysis", if (last != null) listOf(
        "DATA" to "Sinyal terakhir: ${last.dir} ${last.pair} ${last.tf} @ ${fmtN(last.price, 4)} (SL ${fmtN(last.sl, 4)} / TP ${fmtN(last.tp, 4)}, via ${last.src}).",
        "ANALYSIS" to "Level risiko/imbalan dibaca langsung dari level engine — bukan rekomendasi baru.",
        "OPINION" to "Pastikan setup selaras dengan bias timeframe lebih besar sebelum menindaklanjuti."
    ) else listOf("DATA" to "Belum ada sinyal tersimpan.", "OPINION" to "Jalankan backtest atau Start engine untuk menghasilkan sinyal.")))
    out.add(AiBlock("Risk Analysis", if (hist.isNotEmpty()) listOf(
        "DATA" to "Net riwayat ${fmtN(net)} dari ${hist.size} trade · win rate ${if (wr.isFinite()) "%.1f".format(wr) + "%" else "—"}.",
        "ANALYSIS" to if (wr.isFinite()) (if (wr >= 50) "Proporsi menang di atas setengah — jaga disiplin risiko agar expectancy bertahan." else "Proporsi menang di bawah setengah — strategi mengandalkan payoff per trade; waspadai drawdown.") else "Belum cukup sampel untuk menilai.",
        "OPINION" to "Batasi risiko per trade kecil dan hindari leverage tinggi saat volatilitas melebar."
    ) else listOf("DATA" to "Belum ada trade riwayat.", "OPINION" to "Ukur risiko lewat backtest dulu sebelum live/paper.")))
    out.add(AiBlock("Strategy & Backtest Analysis", listOf(
        "DATA" to if (summaryLine.isNotBlank() && summaryLine != "Belum ada hasil.") summaryLine else "Belum ada ringkasan backtest sesi ini.",
        "ANALYSIS" to if (pairs.isNotEmpty()) "Distribusi performa terkonsentrasi pada: " + pairs.take(3).joinToString(", ") + "." else "Jalankan backtest multi-pair untuk melihat distribusi per pair.",
        "OPINION" to "Strategi yang bagus di backtest belum tentu bagus ke depan — uji di beberapa timeframe sebelum dipercaya."
    )))
    out.add(AiBlock("Recommendation", listOf(
        "OPINION" to "Fokus: 1) pastikan provider OK (Settings → Tes Koneksi), 2) mulai dari 1 pair + 1 strategi, 3) catat setiap anomali sebelum mengubah parameter.",
        "OPINION" to "Semua angka di atas berasal dari engine — tidak ada data buatan."
    )))
    return out
}

fun fmtN(v: Double, d: Int = 2): String {
    if (!v.isFinite()) return "—"
    return String.format(java.util.Locale("id", "ID"), "%,.${d}f", v)
}
