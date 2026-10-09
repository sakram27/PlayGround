package com.aether.signal.premium.ui

import java.util.Locale
import kotlin.math.abs

// FITUR 2 (V12): preset risiko — MURNI & teruji JVM.
// Memetakan ke parameter mesin yang BENAR-BENAR dipakai BacktestParams:
// riskPerTrade = riskPct/100, leverage, slPercent = slPct/100, tpPercent = tpPct/100,
// maxHolding. Tidak menyentuh strategi/filter/rumus (lihat App.buildParams).

data class RiskPreset(
    val id: String,
    val name: String,
    val tagline: String,
    val riskPct: Double,
    val leverage: Int,
    val maxHolding: Int,
    val slPct: Double,
    val tpPct: Double
)

// Urutan risiko: Konservatif < Seimbang < Agresif (riskPct, SL/TP, maxHolding).
// Seimbang = nilai bawaan App saat ini, agar "Kembalikan bawaan" konsisten.
val RISK_PRESETS: List<RiskPreset> = listOf(
    RiskPreset("conservative", "Konservatif",
        "Risiko paling kecil. Ukuran posisi lebih kecil, SL/TP lebih rapat, dan posisi ditahan lebih singkat. " +
            "Potensi kerugian per transaksi lebih kecil, tetapi peluang profit juga lebih kecil.",
        riskPct = 0.5, leverage = 1, maxHolding = 60, slPct = 1.0, tpPct = 2.0),
    RiskPreset("balanced", "Seimbang",
        "Titik tengah (setara nilai bawaan aplikasi). Cocok untuk sebagian besar pengguna.",
        riskPct = 1.0, leverage = 1, maxHolding = 100, slPct = 1.5, tpPct = 3.0),
    RiskPreset("aggressive", "Agresif",
        "Risiko lebih besar. Ukuran posisi dan leverage naik, SL/TP lebih lebar. " +
            "Kerugian per transaksi bisa jauh lebih besar — hanya untuk yang memahami risikonya.",
        riskPct = 2.0, leverage = 2, maxHolding = 150, slPct = 2.5, tpPct = 5.0)
)

fun presetById(id: String): RiskPreset? = RISK_PRESETS.find { it.id == id }

private fun eq(a: Double, b: Double) = abs(a - b) < 1e-9

/**
 * Cocokkan konfigurasi risiko aktif ke preset; null = "Kustom"
 * (nilai tidak lagi sama persis dengan preset mana pun).
 */
fun matchPreset(riskPct: Double, leverage: Int, maxHolding: Int, slPct: Double, tpPct: Double): RiskPreset? =
    RISK_PRESETS.firstOrNull {
        eq(it.riskPct, riskPct) && it.leverage == leverage &&
            it.maxHolding == maxHolding && eq(it.slPct, slPct) && eq(it.tpPct, tpPct)
    }

private fun trim(v: Double): String {
    if (!v.isFinite()) return "—"
    var s = String.format(Locale.US, "%.2f", v)
    s = s.trimEnd('0').trimEnd('.')
    return s
}

/** Ringkasan nilai preset (ditampilkan sebelum diterapkan). */
fun riskPresetSummary(p: RiskPreset): String =
    "Risiko ${trim(p.riskPct)}% per transaksi · Leverage ${p.leverage}× · " +
        "Maks tahan ${p.maxHolding} candle · SL ${trim(p.slPct)}% · TP ${trim(p.tpPct)}%"

/** Label status: nama preset, atau "Kustom" bila tak ada yang cocok. */
fun riskPresetLabel(riskPct: Double, leverage: Int, maxHolding: Int, slPct: Double, tpPct: Double): String =
    matchPreset(riskPct, leverage, maxHolding, slPct, tpPct)?.name ?: "Kustom"
