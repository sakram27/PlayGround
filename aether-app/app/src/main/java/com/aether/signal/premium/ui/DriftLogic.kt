package com.aether.signal.premium.ui

import org.json.JSONObject

// FITUR 2 (V20): Live-vs-Backtest Drift Monitor — MURNI & teruji JVM.
// Membandingkan hasil sinyal LIVE (kemenangan = sinyal TP, kekalahan = sinyal SL)
// dengan ekspektasi backtest terakhir yang tersimpan. Bukan klaim eksekusi:
// aplikasi hanya menghasilkan sinyal, sehingga "menang/kalah" = peristiwa TP/SL
// tercatat, dinyatakan eksplisit di UI.

const val DRIFT_MIN_SAMPLE = 10
const val DRIFT_WR_WARN_PP = 15.0
const val DRIFT_PF_DROP_PCT = 30.0

data class DriftExpect(
    val winRate: Double,
    val profitFactor: Double,
    val trades: Int,
    val strategy: String,
    val asset: String,
    val timeframe: String,
    val at: Long
)

data class DriftReport(
    val liveWins: Int,
    val liveLosses: Int,
    val liveTrades: Int,
    val liveWinRate: Double?,
    val dWinRatePp: Double?,
    val dPfPct: Double?,
    val warned: Boolean,
    val status: String
)

/**
 * Bandingkan live vs ekspektasi. Peringatan hanya bila sampel ≥ 10 DAN
 * (|ΔWR| > 15pp ATAU PF live turun > 30% dari ekspektasi). PF live memakai
 * definisi agregat (+1.0 per TP, −1.0 per SL — SATUAN peristiwa, dinyatakan di UI,
 * bukan uang; agregat uang live tak tersedia karena tak ada eksekusi).
 */
fun driftReport(liveWins: Int, liveLosses: Int, exp: DriftExpect?): DriftReport {
    val n = liveWins + liveLosses
    if (exp == null) {
        return DriftReport(liveWins, liveLosses, n, null, null, null, false,
            "Belum ada ekspektasi backtest tersimpan — jalankan backtest dulu.")
    }
    if (n < DRIFT_MIN_SAMPLE) {
        val wr = if (n > 0) liveWins.toDouble() / n * 100 else null
        return DriftReport(liveWins, liveLosses, n, wr, null, null, false,
            "Sampel live belum memadai ($n dari minimal $DRIFT_MIN_SAMPLE) — belum disimpulkan.")
    }
    val liveWR = liveWins.toDouble() / n * 100
    val dWR = liveWR - exp.winRate
    // PF live dalam satuan peristiwa: menang=+1, kalah=−1.
    val livePF = if (liveLosses > 0) liveWins.toDouble() / liveLosses else if (liveWins > 0) 999.0 else 0.0
    val dPF = if (exp.profitFactor.isFinite() && exp.profitFactor > 0 && livePF.isFinite()) {
        (livePF - exp.profitFactor) / exp.profitFactor * 100
    } else null
    val warned = kotlin.math.abs(dWR) > DRIFT_WR_WARN_PP ||
        (dPF != null && dPF < -DRIFT_PF_DROP_PCT)
    val status = if (warned) {
        "PERINGATAN DRIFT: live menyimpang dari ekspektasi backtest " +
            "(ΔWR ${fmtDrift(dWR)}pp" + (if (dPF != null) " · ΔPF ${fmtDrift(dPF)}%" else "") + "). " +
            "Periksa kondisi pasar / parameter — bukan perintah mengubah strategi."
    } else {
        "Selaras: live dalam toleransi ekspektasi backtest " +
            "(ΔWR ${fmtDrift(dWR)}pp" + (if (dPF != null) " · ΔPF ${fmtDrift(dPF)}%" else "") + ")."
    }
    return DriftReport(liveWins, liveLosses, n, liveWR, dWR, dPF, warned, status)
}

private fun fmtDrift(v: Double): String {
    if (!v.isFinite()) return "—"
    return String.format(java.util.Locale.US, "%+.1f", v)
}

fun encodeDriftExpect(e: DriftExpect): String = JSONObject()
    .put("winRate", e.winRate).put("profitFactor", e.profitFactor).put("trades", e.trades)
    .put("strategy", e.strategy).put("asset", e.asset).put("timeframe", e.timeframe)
    .put("at", e.at).toString()

fun parseDriftExpect(raw: String?): DriftExpect? {
    if (raw.isNullOrEmpty()) return null
    return try {
        val o = JSONObject(raw)
        DriftExpect(o.optDouble("winRate", Double.NaN), o.optDouble("profitFactor", Double.NaN),
            o.optInt("trades", 0), o.optString("strategy"), o.optString("asset"),
            o.optString("timeframe"), o.optLong("at", 0))
    } catch (e: Exception) { null }
}
