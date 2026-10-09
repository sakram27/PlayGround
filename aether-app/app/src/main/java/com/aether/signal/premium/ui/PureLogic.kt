package com.aether.signal.premium.ui

// Logika MURNI tanpa dependensi Android (teruji JVM). UI mendelegasikan ke sini.

// C1/C2: sanitasi sparkline — hanya finite, maksimal 120 titik, tanpa fabrikasi.
fun sanitizeSpark(raw: List<Double>): List<Float> =
    raw.filter { it.isFinite() }.takeLast(120).map { it.toFloat() }

// F1: rentang sumbu equity — padding 15% + penjaga datar/nol.
// Hanya menentukan BATAS SUMBU, bukan nilai data (hasil numerik tak berubah).
fun equityPlotRange(values: List<Double>): Pair<Float, Float> {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return 0f to 1f
    var mn = clean.minOrNull()!!
    var mx = clean.maxOrNull()!!
    if (!(mx > mn)) {
        val c = mn
        val d = kotlin.math.abs(c) * 0.01 + 1e-9
        mn = c - d
        mx = c + d
        if (mn == 0.0 && mx == 0.0) { mn = -1.0; mx = 1.0 }
    }
    val pad = (mx - mn) * 0.15
    return (mn - pad).toFloat() to (mx + pad).toFloat()
}

// D: dedup kejadian notifikasi — tambah bila baru, batasi ukuran.
fun dedupAdd(s: MutableSet<String>, key: String, cap: Int = 500): Boolean {
    if (s.contains(key)) return false
    s.add(key)
    while (s.size > cap) s.remove(s.first())
    return true
}

// E: mesin status monitoring MURNI & teruji (6 kondisi realistis).
fun deriveMonitorState(running: Boolean, lastError: String?, lastTickMs: Long, lastSummary: String): String = when {
    !running && lastError != null -> "Gagal berjalan: $lastError"
    !running && lastTickMs == 0L -> "Nonaktif."
    !running -> "Dihentikan."
    lastSummary.startsWith("Menunggu") -> "Menunggu koneksi · $lastSummary"
    else -> "Aktif memantau · ${lastSummary.ifEmpty { "menyiapkan…" }}"
}
