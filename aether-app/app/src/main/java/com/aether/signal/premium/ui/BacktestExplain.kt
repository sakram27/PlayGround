package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.BacktestDiag
import com.aether.signal.premium.engine.BacktestResult
import com.aether.signal.premium.engine.FILTER_DEFS

// FITUR 1 (V12): penjelasan hasil backtest nol transaksi — MURNI & teruji JVM.
// Seluruh angka berasal dari BacktestDiag mesin yang benar-benar dijalankan.
// Tidak ada fabrikasi: bila diagnostik tak lengkap, keterbatasannya dinyatakan.

/** Pemisah ribuan gaya Indonesia (1.234) tanpa dependensi Android. */
fun fmtId(n: Int): String {
    val neg = n < 0
    val s = kotlin.math.abs(n.toLong()).toString()
    val b = StringBuilder()
    for ((i, ch) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) b.append('.')
        b.append(ch)
    }
    return (if (neg) "-" else "") + b.toString()
}

/**
 * Terjemahan nama filter teknis (kunci filterReasons) menjadi kalimat Indonesia.
 * Kunci berbentuk "RSI Filter (RSI jenuh 74)"; bagian dalam kurung diabaikan.
 * Nama yang tak dikenal dikembalikan apa adanya — jujur, tanpa mengarang.
 */
fun humanFilterReason(key: String): String {
    val base = key.substringBefore(" (").trim()
    return when (base) {
        "Trend Filter" -> "Harga tidak searah tren EMA50"
        "EMA Filter" -> "Harga tidak selaras EMA55"
        "HTF Trend" -> "Tren besar (EMA200) tidak searah"
        "Volume Filter" -> "Volume di bawah rata-rata"
        "ATR Volatility" -> "Volatilitas ATR terlalu rendah"
        "ADX Filter" -> "Tren terlalu lemah (ADX rendah)"
        "RSI Filter" -> "RSI berada di area jenuh"
        "Market Structure Filter" -> "Struktur harga tidak searah"
        "S/R Filter" -> "Terlalu dekat level support/resistance"
        "Liquidity Sweep Filter" -> "Tidak ada sweep likuiditas searah"
        "Trading Session" -> "Di luar jam sesi aktif"
        "Min Volatility" -> "Pasar terlalu sepi (volatilitas rendah)"
        "Max Volatility" -> "Pasar terlalu liar (volatilitas tinggi)"
        "Cooldown" -> "Masih dalam jeda cooldown"
        "Duplicate Protection" -> "Sinyal duplikat searah diblokir"
        else -> base
    }
}

data class ReasonCount(val label: String, val raw: String, val count: Int)

/** V24: indeks label sumbu-X equity (5 label tersebar jujur; murni, teruji). */
fun equityLabelIdx(n: Int): List<Int> {
    if (n <= 0) return emptyList()
    if (n == 1) return listOf(0)
    return listOf(0, n / 4, n / 2, n * 3 / 4, n - 1).distinct().filter { it in 0 until n }
}

/** Maksimal [n] alasan penolakan terbanyak (jumlah tertinggi; seri diurut nama). */
fun topReasons(map: Map<String, Int>, n: Int): List<ReasonCount> =
    map.entries.filter { it.value > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(n)
        .map { ReasonCount(humanFilterReason(it.key), it.key, it.value) }

/**
 * Petakan kunci filterReasons ("RSI Filter (RSI jenuh 74)") ke id filter ("rsi").
 * Mengembalikan null bila tak dapat dipetakan — pemanggil wajib jujur ke pengguna.
 */
fun filterIdForReasonKey(key: String): String? {
    val base = key.substringBefore(" (").trim()
    if (base.isEmpty()) return null
    return FILTER_DEFS.find { it.name == base }?.id
}

/** Id filter penyebab utama (jumlah penolakan tertinggi); null bila tak ada/tak terpeta. */
fun primaryFilterId(filterReasons: Map<String, Int>): String? {
    val top = topReasons(filterReasons, 1).firstOrNull() ?: return null
    return filterIdForReasonKey(top.raw)
}

private fun hasDiag(d: BacktestDiag): Boolean =
    d.evaluatedBars > 0 || d.rawCount > 0 || d.signalsRaw > 0 || d.filteredOut > 0 ||
        d.skippedNoLevel > 0 || d.skippedBadEntry > 0 || d.skippedBadQty > 0 ||
        d.skippedBadPnl > 0 || d.received != null

/**
 * Penjelasan "Mengapa Tidak Ada Transaksi?".
 * Mengembalikan string kosong bila tidak relevan (ada transaksi atau ada error).
 */
fun explainNoTrades(r: BacktestResult): String {
    if (r.error != null) return ""
    if (r.totalTrades > 0) return ""
    val d = r.diag
    if (!hasDiag(d)) {
        return "Rincian penyebab tidak tersedia pada hasil ini karena diagnostik masih kosong. " +
            "Jalankan backtest ulang agar alasan penolakan benar-benar tercatat."
    }
    val raw = d.signalsRaw
    val filtered = d.filteredOut
    val skipped = d.skippedNoLevel + d.skippedBadEntry + d.skippedBadQty + d.skippedBadPnl

    if (raw == 0) {
        if (d.evaluatedBars == 0) {
            return "Mesin tidak mengevaluasi satu bar pun. Biasanya karena data terlalu pendek " +
                "(setelah masa pemanasan indikator) atau rentang tanggal terlalu sempit. " +
                "Coba perbesar jumlah candle atau lebarkan rentang tanggal."
        }
        return "Mesin mengevaluasi ${fmtId(d.evaluatedBars)} bar, tetapi strategi tidak menghasilkan " +
            "satu pun sinyal entry (0 sinyal mentah). Ini bukan karena filter, karena tidak ada sinyal " +
            "yang perlu disaring. Coba ganti strategi, timeframe, atau pair."
    }

    val sb = StringBuilder()
    sb.append("Strategi menghasilkan ${fmtId(raw)} sinyal entry. ")
    if (filtered > 0) {
        sb.append("${fmtId(filtered)} sinyal tidak lolos penyaringan filter. ")
        val top = topReasons(d.filterReasons, 3)
        if (top.isEmpty()) {
            sb.append("Namun rincian alasan filter tidak tersedia pada hasil ini, " +
                "sehingga alasan terbanyak tidak dapat dipastikan.")
        } else {
            sb.append("Alasan terbanyak: ")
            sb.append(top.joinToString("; ") { "${it.label}, ${fmtId(it.count)} kali" })
            sb.append(".")
        }
    } else if (skipped == 0) {
        sb.append("Namun tidak ada satu pun yang menjadi transaksi. " +
            "Periksa parameter strategi/filter serta rentang data.")
    }
    if (filtered > 0 && filtered < raw) {
        sb.append(" Sisanya lolos filter. ")
    }
    if (skipped > 0) {
        val parts = ArrayList<String>()
        if (d.skippedNoLevel > 0) parts.add("${fmtId(d.skippedNoLevel)} gagal menghitung level SL/TP")
        if (d.skippedBadEntry > 0) parts.add("${fmtId(d.skippedBadEntry)} harga entry tidak valid")
        if (d.skippedBadQty > 0) parts.add("${fmtId(d.skippedBadQty)} ukuran posisi nol (modal/risiko terlalu kecil)")
        if (d.skippedBadPnl > 0) parts.add("${fmtId(d.skippedBadPnl)} hasil PnL tidak valid")
        sb.append("${fmtId(skipped)} sinyal lolos filter tetapi tidak menjadi transaksi: ")
        sb.append(parts.joinToString(", "))
        sb.append(".")
    }
    return sb.toString()
}
