package com.aether.signal.premium.ui

import com.aether.signal.premium.ai.Sig
import kotlin.math.abs

// Logika MURNI halaman detail sinyal (teruji JVM). UI mendelegasikan ke sini.
// Sumber kebenaran: objek Sig tersimpan (entry/SL/TP saat sinyal dibuat).
// Yang TIDAK disimpan (strategi pembuat, alasan indikator, TP2/TP3) dinyatakan
// "tidak tersedia" oleh pemanggil — tanpa fabrikasi.

/** Cari sinyal by id stabil (bukan posisi daftar yang bisa bergeser). */
fun findSignalById(signals: List<Sig>, id: String): Sig? {
    if (id.isEmpty()) return null
    return signals.firstOrNull { it.id == id }
}

/** Label sumber kejadian sesuai fakta pembuatan di BotEngine/BacktestActivity. */
fun srcLabel(src: String): String = when (src) {
    "dryrun-entry" -> "Sinyal entry live (paper)"
    "dryrun-TP" -> "Take profit tercapai (paper)"
    "dryrun-SL" -> "Stop loss tercapai (paper)"
    "dryrun-trail" -> "Keluar trailing stop (paper)"
    "backtest" -> "Arsip backtest"
    "" -> "Tidak diketahui"
    else -> src
}

/** True bila kolom `price` Sig adalah harga ENTRY (bukan harga keluar). */
fun isEntryLike(src: String): Boolean = src == "dryrun-entry" || src == "backtest"

/**
 * Status entry dari data aktual (tanpa menghitung ulang sinyal):
 * - isOpen: posisi paper (pair+dir+entryT cocok) masih terbuka.
 * - hasLaterExit: ada sinyal TP/SL pair+dir yang sama dengan waktu lebih baru.
 */
fun deriveSignalStatus(src: String, isOpen: Boolean, hasLaterExit: Boolean): String = when (src) {
    "backtest" -> "Arsip backtest (bukan posisi live)"
    "dryrun-TP" -> "Keluar — take profit tercapai"
    "dryrun-SL" -> "Keluar — stop loss tercapai"
    "dryrun-trail" -> "Keluar — trailing stop tersentuh"
    "dryrun-entry" -> when {
        isOpen -> "Aktif (posisi paper terbuka)"
        hasLaterExit -> "Sudah keluar (TP/SL tercatat)"
        else -> "Menunggu (tak terlacak di posisi terbuka)"
    }
    else -> "Tidak diketahui"
}

data class SignalDistances(
    val slDistPrice: Double?,
    val slDistPct: Double?,
    val tpDistPrice: Double?,
    val tpDistPct: Double?,
    val rr: Double?
)

/** Jarak & RR dari nilai tersimpan (bukan harga pasar kini). Null = tak dapat dihitung. */
fun signalDistances(entry: Double, sl: Double, tp: Double): SignalDistances {
    if (!entry.isFinite() || entry <= 0) return SignalDistances(null, null, null, null, null)
    val slD = if (sl.isFinite() && sl > 0) abs(entry - sl) else Double.NaN
    val tpD = if (tp.isFinite() && tp > 0) abs(entry - tp) else Double.NaN
    val slP = if (slD.isFinite()) slD / entry * 100 else null
    val tpP = if (tpD.isFinite()) tpD / entry * 100 else null
    val rr = if (slD.isFinite() && tpD.isFinite() && slD > 0) tpD / slD else null
    return SignalDistances(
        slD.takeIf { it.isFinite() }, slP,
        tpD.takeIf { it.isFinite() }, tpP, rr
    )
}

/**
 * Penjelasan arah dari geometri level tersimpan (bukan karangan indikator):
 * LONG valid bila SL di bawah & TP di atas entry (sebaliknya SHORT).
 */
fun directionRationale(dir: String, entry: Double, sl: Double, tp: Double): String {
    if (!entry.isFinite() || entry <= 0 || !sl.isFinite() || !tp.isFinite() || sl <= 0 || tp <= 0) {
        return "Geometri level tidak lengkap — arah tidak dapat diverifikasi dari data tersimpan."
    }
    val slBelow = sl < entry
    val tpAbove = tp > entry
    return when (dir) {
        "LONG" -> if (slBelow && tpAbove)
            "Level tersimpan menempatkan stop di bawah entry dan target di atas entry, konsisten dengan arah LONG."
        else
            "Geometri level tersimpan tidak lazim untuk LONG (SL di atas entry atau TP di bawah entry)."
        "SHORT" -> if (!slBelow && !tpAbove)
            "Level tersimpan menempatkan stop di atas entry dan target di bawah entry, konsisten dengan arah SHORT."
        else
            "Geometri level tersimpan tidak lazim untuk SHORT (SL di bawah entry atau TP di atas entry)."
        else -> "Arah \"$dir\" tidak dikenal."
    }
}
