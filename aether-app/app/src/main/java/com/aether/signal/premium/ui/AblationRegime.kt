package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.BacktestResult

// FITUR 6 (V16): ablation study — pembanding per filter, MURNI & teruji JVM.
// Menjalankan ulang dilakukan Activity (mesin aktual, bg + batal); di sini hanya
// struktur delta & pengurutan. Filter yang tak dapat diisolasi ditandai via
// AblationTrial.comparable=false — tanpa ranking palsu.

data class AblationTrial(
    val filterId: String,
    val filterName: String,
    val comparable: Boolean,
    val res: BacktestResult?,
    val note: String = ""
)

data class AblationDelta(val dTrades: Int, val dNet: Double, val dWinRate: Double, val dDDPct: Double)

fun ablationDelta(base: BacktestResult, trial: BacktestResult): AblationDelta =
    AblationDelta(
        trial.totalTrades - base.totalTrades,
        trial.netProfit - base.netProfit,
        trial.winRate - base.winRate,
        trial.maxDrawdownPercent - base.maxDrawdownPercent
    )

/** Urutkan percobaan by dampak pilihan: "trades" | "net" | "dd". Tak-comparable selalu di bawah. */
fun sortAblation(base: BacktestResult, trials: List<AblationTrial>, sortBy: String): List<AblationTrial> {
    fun key(t: AblationTrial): Double {
        if (!t.comparable || t.res == null) return Double.NEGATIVE_INFINITY
        val d = ablationDelta(base, t.res)
        return when (sortBy) {
            "net" -> d.dNet
            "dd" -> -d.dDDPct // penurunan DD = dampak baik → nilai besar
            else -> d.dTrades.toDouble()
        }
    }
    return trials.sortedWith(compareByDescending<AblationTrial> { it.comparable }.thenByDescending { key(it) })
}

// ---------- FITUR 7: detektor regime pasar ----------
// Aturan klasifikasi eksplisit (didokumentasikan juga di UI):
//   slope = (EMA200[i]-EMA200[i-20]) / EMA200[i-20] * 100.
//   ADX ≥ 20 + slope > +0,5% → Tren naik; slope < −0,5% → Tren turun.
//   Selain itu (ADX lemah / slope datar) → Sideways.
//   ATR% ≥ 5 → atribut terpisah volatile=true (bukan kategori ke-5):
//   pasar boleh "Tren naik · volatil". Data tak cukup/NaN → null.

data class Regime(val label: String, val volatile: Boolean, val at: Long)

const val REGIME_ADX_MIN = 20.0
const val REGIME_SLOPE_PCT = 0.5
const val REGIME_ATR_PCT = 5.0
const val REGIME_LOOKBACK = 20

fun classifyRegime(
    e200: DoubleArray, adx: DoubleArray, atrPct: Double?, idx: Int,
    lookback: Int = REGIME_LOOKBACK, at: Long = System.currentTimeMillis()
): Regime? {
    if (idx < 0 || idx >= e200.size || idx >= adx.size) return null
    val j = idx - lookback
    if (j < 0) return null
    val base = e200[j]; val last = e200[idx]; val a = adx[idx]
    if (!base.isFinite() || !last.isFinite() || base <= 0 || !a.isFinite()) return null
    val slope = (last - base) / base * 100
    if (!slope.isFinite()) return null
    val label = if (a >= REGIME_ADX_MIN && slope > REGIME_SLOPE_PCT) "Tren naik"
    else if (a >= REGIME_ADX_MIN && slope < -REGIME_SLOPE_PCT) "Tren turun"
    else "Sideways"
    val vol = atrPct != null && atrPct.isFinite() && atrPct >= REGIME_ATR_PCT
    return Regime(label, vol, at)
}

// V28: kebutuhan data minimum diturunkan dari implementasi aktual, bukan tebakan.
// ema() menyemai SMA pada indeks ke-199 (butuh 200 titik agar e200[199] finite),
// dan regime memakai lookback 20 (butuh e200[idx-20] finite). Jadi ukuran minimum
// = 200 + 20 = 220 candle valid. Meminta tepat 200 = "belum siap" selamanya.
const val REGIME_MIN_CANDLES = 220

// V28: jumlah candle yang diminta Engine per tick. 300 = 220 minimum + margin 80
// untuk candle duplikat yang dibuang / bar tak valid. Masih dalam batas satu
// request semua provider (Binance/Bybit ≤1000, Yahoo takeLast, Demo generatif).
const val ENGINE_CANDLES = 300

/**
 * Diagnostik per-pair untuk card Kondisi Pasar: angka aktual dari tick terakhir
 * (diminta/diterima/valid), kebutuhan minimum, sumber, waktu, dan alasan status.
 * Bukan label palsu — setiap status dapat ditelusuri ke angka ini.
 */
data class RegimeDiag(
    val requested: Int = 0,
    val received: Int = 0,
    val valid: Int = 0,
    val minRequired: Int = REGIME_MIN_CANDLES,
    val provider: String = "",
    val timeframe: String = "",
    val updatedAt: Long = 0L,
    val error: String = "",
    val indicatorOk: Boolean = false
)

/**
 * V27: satu baris card Kondisi Pasar dari diagnostik aktual (murni, teruji).
 * Membedakan: menunggu / gagal ambil / data kurang / indikator gagal /
 * label valid (+basi). Tak ada angka karangan.
 */
fun regimeLine(
    sym: String, d: RegimeDiag?, r: Regime?,
    engRunning: Boolean, nowMs: Long
): String {
    if (d == null) {
        return if (engRunning) "$sym: Menunggu data… (Engine berjalan)"
        else "$sym: Menunggu evaluasi Engine — tekan Start Engine."
    }
    if (d.error.isNotEmpty()) return "$sym: Gagal mengambil data: ${d.error}"
    if (d.valid < d.minRequired) return "$sym: Data tidak cukup: diterima ${d.received}, " +
        "valid ${d.valid}, butuh ≥${d.minRequired} (EMA200). Sumber ${d.provider} ${d.timeframe}."
    if (!d.indicatorOk) return "$sym: Indikator gagal dihitung (EMA200/ADX belum siap)."
    if (r == null || r.label.isEmpty()) return "$sym: Indikator gagal dihitung."
    val ageMin = ((nowMs - r.at).coerceAtLeast(0) / 60000)
    val stale = if (ageMin > 30) " · data ${ageMin} mnt (mungkin basi)" else ""
    return "$sym: ${regimeText(r)}$stale"
}

fun regimeText(r: Regime?): String =
    if (r == null) "Data tidak cukup"
    else r.label + if (r.volatile) " · volatil" else ""
