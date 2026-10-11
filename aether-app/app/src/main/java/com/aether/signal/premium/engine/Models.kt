package com.aether.signal.premium.engine

import kotlin.math.*

// Port langsung dari core.js (sumber kebenaran). Nilai null indikator JS = Double.NaN.

const val MIN_CANDLES = 60
const val WARMUP = 60
const val DEFAULT_MAX_HOLDING = 100

// V26: tangga TP bertingkat. TP1 selalu = jarak TP bawaan; TP2/TP3 adalah
// kelipatan jarak TP1 (default 2x/3x, dapat dikonfigurasi). Penutupan sebagian
// mengikuti fraksi tetap: TP2 = 50/50; TP3 = sepertiga per kaki dengan kaki
// terakhir menutup seluruh sisa (anti-residu pembulatan).
const val TP2_MULT_DEFAULT = 2.0
const val TP3_MULT_DEFAULT = 3.0
val TP2_FRACS: List<Double> = listOf(0.5, 0.5)
val TP3_FRACS: List<Double> = listOf(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)

/** Label mode TP untuk ringkasan/UI. */
fun tpModeLabel(mode: Int): String = when (mode.coerceIn(1, 3)) {
    2 -> "TP2"
    3 -> "TP3"
    else -> "Single TP"
}

/**
 * V31: konversi input persen TP2/TP3 (angka persen: 2.5 = 2,5%) menjadi
 * kelipatan jarak TP1 untuk mesin. tp2Mult = tp2Pct/tpPct. Bukan fraksi:
 * 2,5% TIDAK pernah menjadi 0,025 atau 25% — pembagian oleh tpPct dilakukan
 * tepat sekali di sini/di buildParams; mesin menerima hasil akhirnya.
 * Guard: tpPct ≤ 0 → default (2.5/2.0, 3.5/2.0 terhadap TP bawaan).
 */
fun tpMultsFromPct(tpPct: Double, tp2Pct: Double, tp3Pct: Double): Pair<Double, Double> {
    if (!tpPct.isFinite() || tpPct <= 0) return 1.25 to 1.75
    val m2 = if (tp2Pct.isFinite() && tp2Pct > 0) tp2Pct / tpPct else TP2_MULT_DEFAULT
    val m3 = if (tp3Pct.isFinite() && tp3Pct > 0) tp3Pct / tpPct else TP3_MULT_DEFAULT
    return m2 to m3
}

/** Fraksi penutupan per kaki (terakhir = sisa). Selalu dijumlahkan ≈ 1. */
fun tpFractions(mode: Int): List<Double> = when (mode.coerceIn(1, 3)) {
    2 -> TP2_FRACS
    3 -> TP3_FRACS
    else -> listOf(1.0)
}

/**
 * Target TP terurut searah posisi dari jarak TP1 aktual (mendukung mode ATR
 * karena memakai jarak, bukan tpPercent). Target tak valid (tak strictly
 * melampaui target sebelumnya searah posisi, non-finite, ≤0) DIBUANG dengan
 * jujur — TP1 dari level tervalidasi selalu dipertahankan.
 */
fun tpTargets(entry: Double, dir: String, tp1Dist: Double, mode: Int, m2: Double, m3: Double): List<Double> {
    if (!entry.isFinite() || entry <= 0 || !tp1Dist.isFinite() || tp1Dist <= 0) return emptyList()
    val sgn = if (dir == "LONG") 1.0 else -1.0
    val tp1 = entry + sgn * tp1Dist
    if (!tp1.isFinite() || tp1 <= 0) return emptyList()
    val out = ArrayList<Double>()
    out.add(tp1)
    val mults = listOf(m2, m3).take(maxOf(0, mode.coerceIn(1, 3) - 1))
    for (m in mults) {
        if (!m.isFinite() || m <= 1.0) continue
        val t = entry + sgn * tp1Dist * m
        if (!t.isFinite() || t <= 0) continue
        if ((sgn > 0 && t > out.last()) || (sgn < 0 && t < out.last())) out.add(t)
    }
    return out
}

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

data class Combo(val mode: String, val strategies: List<String>)

data class FilterCfg(
    val name: String,
    val enabled: Boolean = true,
    val params: Map<String, Double> = emptyMap(),
    val sessions: List<Pair<Int, Int>>? = null
)

data class BacktestParams(
    val asset: String = "BTCUSDT",
    val timeframe: String = "15m",
    val initialCapital: Double = 1000.0,
    val riskPerTrade: Double = 0.01,
    val leverage: Int = 1,
    val feePercent: Double = 0.0005,
    val slippagePercent: Double = 0.0002,
    val slPercent: Double = 0.015,
    val tpPercent: Double = 0.03,
    val maxHolding: Int = DEFAULT_MAX_HOLDING,
    val strategy: String = "ema_trend",
    val strategyParams: Map<String, Double> = emptyMap(),
    val combo: Combo? = null,
    val startDate: Long = 0,
    val endDate: Long = 0,
    val useAtr: Boolean = false,
    val atrSlMult: Double = 1.5,
    val atrTpMult: Double = 3.0,
    // V26: skema Take Profit bertingkat. 1 = Single TP (perilaku lama, utuh).
    // 2/3 = TP1..TPn dengan penutupan sebagian mengikuti fraksi tetap.
    val tpMode: Int = 1,
    // Kelipatan jarak TP1 untuk TP2/TP3 (TP1 = jarak TP bawaan |tp - entry|).
    val tp2Mult: Double = TP2_MULT_DEFAULT,
    val tp3Mult: Double = TP3_MULT_DEFAULT,
    // V31: trailing stop backtest (terpisah dari trailing virtual paper
    // BotEngine). trailOn=false → perilaku lama utuh. Satuan FRAKSI seperti
    // slPercent (0.01 = 1%) — konversi /100 tepat sekali di buildParams.
    val trailOn: Boolean = false,
    val trailDistPct: Double = 0.01,
    val trailActPct: Double = 0.01,
    val filters: List<FilterCfg> = emptyList()
)

data class SignalDecision(
    val passed: Boolean,
    val direction: String,
    val confidence: Double,
    val reasons: List<String>,
    val strategy: String
)

data class FilterVerdict(val passed: Boolean, val failed: List<String>)

data class Trade(
    val direction: String, val asset: String, val timeframe: String,
    val entry: Double, val exit: Double, val stopLoss: Double, val takeProfit: Double,
    val entryTime: Long, val exitTime: Long, val strategy: String,
    val confidence: Double, val reasons: List<String>,
    val qty: Double, val riskAmount: Double, val fees: Double, val pnl: Double,
    val pnlPercent: Double, val rMultiple: Double, val result: String, val holding: Int,
    // V18: identitas unik stabil untuk audit ("aset|arah|entryTime|exitTime").
    val uid: String = ""
)

data class EquityPoint(val t: Long, val equity: Double)

/**
 * V31: satu kaki dihitung MENANG bila berlabel WIN, atau berlabel TRAIL
 * dengan laba bersih positif (konsisten dengan kategorisasi pnl buildResult).
 * Dipakai penghitung tampilan riwayat (hist memuat kaki backtest + paper).
 */
fun isWinTrade(t: Trade): Boolean = t.result == "WIN" || (t.result == "TRAIL" && t.pnl > 0)

/**
 * V34: sidik konfigurasi efektif — kanonik, stabil, mencakup SELURUH
 * parameter yang memengaruhi simulasi. Dihitung dari parameter sanitasi
 * (bukan input mentah UI) agar identik dengan yang dipakai mesin.
 * Format angka Double.toString (deterministik untuk nilai yang sama).
 */
fun configFingerprint(p: BacktestParams): String {
    fun f(v: Double): String = v.toString()
    val sb = StringBuilder()
    sb.append("asset=").append(p.asset).append("|tf=").append(p.timeframe)
        .append("|strat=").append(p.strategy)
        .append("|stratP=").append(p.strategyParams.toList().sortedBy { it.first }
            .joinToString(",") { "${it.first}=${f(it.second)}" })
    val combo = p.combo
    sb.append("|combo=").append(if (combo == null) "-" else "${combo.mode}:${combo.strategies.sorted().joinToString("+")}")
    sb.append("|filters=").append(p.filters.filter { it.enabled }.sortedBy { it.name }
        .joinToString(",") { fc ->
            val prm = fc.params.toList().sortedBy { it.first }.joinToString(";") { "${it.first}=${f(it.second)}" }
            val ss = fc.sessions?.sortedWith(compareBy({ it.first }, { it.second }))?.joinToString(";") { "${it.first}-${it.second}" } ?: ""
            "${fc.name}[$prm]{$ss}"
        })
    sb.append("|sl=").append(f(p.slPercent)).append("|tp=").append(f(p.tpPercent))
        .append("|tpm=").append(p.tpMode).append("|m2=").append(f(p.tp2Mult)).append("|m3=").append(f(p.tp3Mult))
        .append("|trail=").append(p.trailOn).append(",").append(f(p.trailDistPct)).append(",").append(f(p.trailActPct))
        .append("|risk=").append(f(p.riskPerTrade)).append("|lev=").append(p.leverage)
        .append("|cap=").append(f(p.initialCapital))
        .append("|fee=").append(f(p.feePercent)).append("|slip=").append(f(p.slippagePercent))
        .append("|mh=").append(p.maxHolding)
        .append("|atr=").append(p.useAtr).append(",").append(f(p.atrSlMult)).append(",").append(f(p.atrTpMult))
        .append("|dates=").append(p.startDate).append(",").append(p.endDate)
    return sb.toString()
}

/**
 * V34: ringkasan konfigurasi efektif (Bahasa Indonesia) — dari parameter yang
 * DIKIRIM ke mesin, bukan bacaan komponen UI. [hyperoptSrc] = baris asal
 * Hyperopt bila parameter berasal dari hasil terapan, else null.
 */
fun effectiveConfigSummary(p: BacktestParams, hyperoptSrc: String? = null): String {
    val b = StringBuilder()
    val stratName = STRATEGIES[p.strategy]?.first ?: p.strategy
    b.append("Strategi: $stratName (${labelStrategy(p)})")
    if (p.strategyParams.isNotEmpty()) {
        b.append(" (").append(p.strategyParams.toList().sortedBy { it.first }
            .joinToString(", ") { "${it.first}=${it.second}" }).append(")")
    }
    b.append("\nPair: ${p.asset} · TF: ${p.timeframe}")
    val flt = p.filters.filter { it.enabled }
    b.append("\nFilter aktif (${flt.size}): ")
    b.append(if (flt.isEmpty()) "tidak ada" else flt.sortedBy { it.name }.joinToString(", ") { fc ->
        val nm = FILTER_DEFS.find { it.id == fc.name }?.name ?: fc.name
        val prm = fc.params.toList().sortedBy { it.first }.joinToString(";") { "${it.first}=${it.second}" }
        if (prm.isEmpty()) nm else "$nm($prm)"
    })
    b.append("\nSL: ${p.slPercent * 100}% · TP: ${p.tpPercent * 100}% (${tpModeLabel(p.tpMode)})")
    b.append("\nTrailing: ${if (p.trailOn) "AKTIF (jarak ${p.trailDistPct * 100}%, aktivasi ${p.trailActPct * 100}%)" else "mati"}")
    b.append("\nRentang: ${if (p.startDate > 0 || p.endDate > 0) "${p.startDate}→${p.endDate}" else "seluruh data"}")
    b.append("\nModal: ${p.initialCapital} · Risiko: ${p.riskPerTrade * 100}% · Lev: ${p.leverage}× · Fee: ${p.feePercent * 100}% · Slip: ${p.slippagePercent * 100}%")
    b.append("\nSumber: ${hyperoptSrc ?: "konfigurasi manual"}")
    return b.toString()
}

data class BacktestDiag(
    val rawCount: Int = 0, val normalizedCount: Int = 0, val dateFilteredCount: Int = 0,
    val warmup: Int = WARMUP, val startIdx: Int = 0,
    val evaluatedBars: Int = 0, val skippedInPosition: Int = 0,
    val signalsRaw: Int = 0, val filteredOut: Int = 0,
    val skippedNoLevel: Int = 0, val skippedBadEntry: Int = 0,
    val skippedBadQty: Int = 0, val skippedBadPnl: Int = 0,
    val filterReasons: Map<String, Int> = emptyMap(),
    // V22: hitung lolos per filter aktif (diagnostik; tanpa ubah keputusan).
    val filterPassed: Map<String, Int> = emptyMap(),
    val evalFrom: Long = 0, val evalTo: Long = 0,
    val limitRequested: Int? = null, val received: Int? = null,
    val source: String? = null, val cacheUsed: String? = null,
    val note: String? = null,
    // V17 (forensik periode): identitas & cakupan run. Data, bukan aturan.
    val warmupPrefix: Int = 0,
    val runId: String = "",
    val finishedAt: Long = 0L,
    // V34: sidik konfigurasi efektif — dihitung mesin dari parameter yang
    // DISANITASI dan benar-benar dipakai simulasi. Dua run berkonfigurasi
    // berbeda wajib bersidik beda; sama persis bila konfigurasi sama.
    val cfgFp: String = ""
)

data class BacktestResult(
    val error: String? = null,
    val asset: String = "", val timeframe: String = "", val strategy: String = "",
    val initialCapital: Double = 0.0, val finalCapital: Double = 0.0,
    val totalTrades: Int = 0, val wins: Int = 0, val losses: Int = 0, val expired: Int = 0,
    // V30: transaksi impas (laba bersih tepat nol) — kategori sendiri, bukan
    // untung/rugi. wins+losses+breakeven = totalTrades selalu.
    val breakeven: Int = 0,
    val winRate: Double = 0.0, val lossRate: Double = 0.0,
    val grossProfit: Double = 0.0, val grossLoss: Double = 0.0,
    val netProfit: Double = 0.0, val netProfitPercent: Double = 0.0,
    val profitFactor: Double = 0.0, val expectancy: Double = 0.0,
    val averageWin: Double = 0.0, val averageLoss: Double = 0.0,
    val averageR: Double = 0.0, val averageRR: Double = 0.0,
    val maxDrawdown: Double = 0.0, val maxDrawdownPercent: Double = 0.0,
    val sharpe: Double = 0.0, val sortino: Double = 0.0, val calmar: Double = 0.0,
    val cagr: Double = 0.0, val exposure: Double = 0.0, val avgHolding: Double = 0.0,
    val longestWinStreak: Int = 0, val longestLossStreak: Int = 0, val filtered: Int = 0,
    val trades: List<Trade> = emptyList(), val equityCurve: List<EquityPoint> = emptyList(),
    val diag: BacktestDiag = BacktestDiag()
)

data class FilterCtx(
    var lastExit: Int = Int.MIN_VALUE,
    var lastSigDir: String? = null,
    var lastSigIdx: Int = 0,
    var lastExitT: Long? = null,
    var lastSigT: Long? = null,
    var lastSigDirT: String? = null,
    var tfMs: Long? = null
)

fun parseTimeframe(tf: String): Int {
    val m = Regex("""^(\d+)\s*([mhdw])$""").matchEntire(tf.trim().lowercase())
        ?: throw IllegalArgumentException("Timeframe tidak valid: \"$tf\". Gunakan format 15m, 1h, 4h, 1d, 1w.")
    val n = m.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("Timeframe tidak valid: \"$tf\".")
    if (n <= 0) throw IllegalArgumentException("Timeframe tidak valid: \"$tf\".")
    val minutes = when (m.groupValues[2]) {
        "m" -> n; "h" -> n * 60; "d" -> n * 1440; else -> n * 10080
    }
    if (minutes <= 0) throw IllegalArgumentException("Timeframe tidak valid: \"$tf\".")
    return minutes
}

// ---------- V21: batas 2 tahun kalender (UTC, kabisat benar) ----------

/**
 * True bila (from, to] melebihi 2 tahun kalender. Tepat 2 tahun = diizinkan.
 * Batas hanya berlaku bila kedua ujung > 0.
 */
fun exceedsTwoYears(fromMs: Long, toMs: Long): Boolean {
    if (fromMs <= 0 || toMs <= 0 || toMs <= fromMs) return false
    return try {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = fromMs
        c.add(java.util.Calendar.YEAR, 2)
        toMs > c.timeInMillis
    } catch (e: Exception) {
        // Fallback konservatif: 730 hari.
        toMs - fromMs > 730L * 86400000L
    }
}

/** Jumlah candle yang diharapkan pada [from, to] untuk interval TF (inklusif). */
fun expectedCandles(fromMs: Long, toMs: Long, tfMinutes: Int): Long {
    if (fromMs <= 0 || toMs <= fromMs || tfMinutes <= 0) return 0L
    return (toMs - fromMs) / (tfMinutes.toLong() * 60000L) + 1
}


fun utcHour(t: Long): Int = ((t / 3600000L) % 24L).toInt()
fun epochDay(t: Long): Long = Math.floorDiv(t, 86400000L)

private fun numD(v: Any?, dflt: Double): Double {
    if (v == null) return dflt
    if (v is Number) { val d = v.toDouble(); return if (d.isFinite()) d else dflt }
    val s = v.toString()
    if (s.isEmpty()) return dflt
    val d = s.replace(',', '.').toDoubleOrNull() ?: return dflt
    return if (d.isFinite()) d else dflt
}

private fun clampD(v: Double, a: Double, b: Double): Double = min(b, max(a, v))

// Normalisasi dari peta generik (t/o/h/l/c/v atau timestamp/open/... atau indeks array).
fun normalizeCandles(raw: List<Any?>): List<Candle> {
    val out = ArrayList<Candle>()
    val seen = HashSet<Long>()
    for (c in raw) {
        var t = 0L; var o = Double.NaN; var h = Double.NaN; var l = Double.NaN
        var cl = Double.NaN; var v = 0.0
        when (c) {
            is Map<*, *> -> {
                t = toMs(c["t"] ?: c["timestamp"])
                o = toD(c["o"] ?: c["open"]); h = toD(c["h"] ?: c["high"])
                l = toD(c["l"] ?: c["low"]); cl = toD(c["c"] ?: c["close"])
                v = toD(c["v"] ?: c["volume"], 0.0)
            }
            is List<*> -> {
                if (c.size < 5) continue
                t = toMs(c[0]); o = toD(c[1]); h = toD(c[2]); l = toD(c[3]); cl = toD(c[4])
                v = if (c.size > 5) toD(c[5], 0.0) else 0.0
            }
            else -> continue
        }
        if (t <= 0) continue
        if (!(o.isFinite() && h.isFinite() && l.isFinite() && cl.isFinite()))
            throw IllegalArgumentException("Data candle tidak valid (mengandung nilai non-finite/rusak).")
        if (h < maxOf(o, cl, l) - 1e-12 || l > minOf(o, cl, h) + 1e-12)
            throw IllegalArgumentException("Data candle tidak valid (high < max(open,close) atau low > min(open,close)).")
        if (!seen.add(t)) continue
        out.add(Candle(t, o, h, l, cl, if (v.isFinite() && v >= 0) v else 0.0))
    }
    out.sortBy { it.t }
    return out
}

private fun toMs(v: Any?): Long {
    if (v == null) return 0
    if (v is Number) return v.toLong()
    return v.toString().toDoubleOrNull()?.toLong() ?: 0
}

private fun toD(v: Any?, dflt: Double = Double.NaN): Double {
    if (v == null) return dflt
    if (v is Number) return v.toDouble()
    return v.toString().toDoubleOrNull() ?: dflt
}

fun filterByDate(candles: List<Candle>, startDate: Long, endDate: Long): List<Candle> =
    candles.filter { (!startDate.isPositive() || it.t >= startDate) && (!endDate.isPositive() || it.t <= endDate) }

/**
 * V17 (forensik periode): pisahkan data PEMANASAN indikator dari rentang TRANSAKSI.
 * Mengembalikan (prefix, inRange): hingga WARMUP candle sebelum startDate hanya
 * untuk riwayat indikator (tanpa transaksi); inRange = [startDate, endDate].
 * Tanpa startDate (>0): prefix kosong (perilaku lama). Aturan strategi/filter/
 * entry/exit tak berubah — hanya persiapan data.
 */
fun splitWarmup(full: List<Candle>, startDate: Long, endDate: Long, warmup: Int = WARMUP): Pair<List<Candle>, List<Candle>> {
    val inRange = filterByDate(full, startDate, endDate)
    if (!startDate.isPositive()) return emptyList<Candle>() to inRange
    val firstIn = full.indexOfFirst { it.t >= startDate }
    if (firstIn <= 0) return emptyList<Candle>() to inRange
    return full.subList(maxOf(0, firstIn - warmup), firstIn).toList() to inRange
}

private fun Long.isPositive(): Boolean = this > 0
