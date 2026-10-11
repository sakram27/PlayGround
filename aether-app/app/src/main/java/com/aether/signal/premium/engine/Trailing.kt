package com.aether.signal.premium.engine

// V31: mesin trailing stop BACKTEST — matematika MURNI & teruji JVM.
// Terpisah penuh dari trailing virtual paper BotEngine (model aktivasi
// berbeda; BotEngine tak diubah). Dipakai runBacktest + ReplayEngine
// (cermin identik) sehingga replay = backtest.
//
// Model (fraksi seperti slPercent: 0.01 = 1%):
//   - Aktivasi: LONG perlu (high-entry)/entry ≥ actFrac; SHORT perlu
//     (entry-low)/entry ≥ actFrac. Sebelum itu trailing MATI total.
//   - Setelah aktif: extreme = high tertinggi (LONG) / low terendah (SHORT)
//     sejak aktivasi; stop = extreme∓dist (dist = distFrac × extreme).
//   - Ratchet: stop hanya bergerak ke arah untung (LONG naik saja,
//     SHORT turun saja); tak pernah menjauh.
//   - Sentuh: LONG low ≤ stop; SHORT high ≥ stop → tutup SELURUH sisa.
//   - Tanpa look-ahead: tiap bar hanya memakai hi/lo bar itu + state
//     bar sebelumnya. Urutan intrabar: cek sentuh DULU dengan stop lama,
//     baru ratchet (konservatif — tak memakai high bar berjalan untuk
//     menaikkan stop lalu mengklaim selamat).
//   - Prioritas multi-exit satu candle: harga keluar TERBURUK bagi posisi
//     (LONG = terendah, SHORT = tertinggi); seri → SL > TRAIL > TP.
//     Ini generalisasi konservatif aturan lama "SL mengalahkan TP".

/** State trailing satu posisi (dibuat per posisi, dibuang saat tutup). */
data class TrailSim(
    var active: Boolean = false,
    var extreme: Double = Double.NaN,
    var stop: Double = Double.NaN
)

/** Batas kewajaran input persen UI (0,05%–50%). Di luar itu dijepit.
 *  Dipakai handler input agar tersimpan selalu valid; mesin menjepit lagi. */
fun sanitizeTrailPct(v: Double, def: Double): Double =
    if (!v.isFinite()) def else v.coerceIn(0.05, 50.0)

/** Harga stop dari extreme. NaN bila input tak valid. */
fun trailStopFor(dir: String, extreme: Double, distFrac: Double): Double {
    if (!extreme.isFinite() || extreme <= 0 || !distFrac.isFinite() || distFrac <= 0) return Double.NaN
    return if (dir == "LONG") extreme * (1 - distFrac) else extreme * (1 + distFrac)
}

/**
 * Satu langkah trailing pada bar berjalan. Mengaktifkan bila ambang
 * terpenuhi, lalu mengembalikan harga stop bila TERSENTUH bar ini,
 * atau null bila bertahan. State [st] dimutasi (aktivasi tercatat) meski
 * exit lain memenangkan prioritas — posisi toh ditutup.
 */
fun trailExitPrice(
    dir: String, entry: Double, hi: Double, lo: Double,
    actFrac: Double, distFrac: Double, st: TrailSim
): Double? {
    if (!entry.isFinite() || entry <= 0) return null
    if (!actFrac.isFinite() || actFrac <= 0 || !distFrac.isFinite() || distFrac <= 0) return null
    if (!hi.isFinite() || !lo.isFinite()) return null
    if (!st.active) {
        val gain = if (dir == "LONG") (hi - entry) / entry else (entry - lo) / entry
        if (!gain.isFinite() || gain < actFrac) return null
        st.active = true
        st.extreme = if (dir == "LONG") hi else lo
        st.stop = trailStopFor(dir, st.extreme, distFrac)
        if (!st.stop.isFinite()) { st.active = false; return null }
    }
    return if (dir == "LONG") {
        if (lo <= st.stop) st.stop else null
    } else {
        if (hi >= st.stop) st.stop else null
    }
}

/**
 * Ratchet akhir bar TANPA exit: extreme mengikuti sisi menguntungkan,
 * stop hanya menguat. Panggil hanya bila bar ini tak menutup posisi
 * (cermin di mesin + replay).
 */
fun trailRatchet(dir: String, hi: Double, lo: Double, distFrac: Double, st: TrailSim) {
    if (!st.active) return
    if (!distFrac.isFinite() || distFrac <= 0) return
    if (dir == "LONG") {
        if (!hi.isFinite()) return
        if (!st.extreme.isFinite() || hi > st.extreme) st.extreme = hi
        val s = trailStopFor(dir, st.extreme, distFrac)
        if (s.isFinite() && (!st.stop.isFinite() || s > st.stop)) st.stop = s
    } else {
        if (!lo.isFinite()) return
        if (!st.extreme.isFinite() || lo < st.extreme) st.extreme = lo
        val s = trailStopFor(dir, st.extreme, distFrac)
        if (s.isFinite() && (!st.stop.isFinite() || s < st.stop)) st.stop = s
    }
}
