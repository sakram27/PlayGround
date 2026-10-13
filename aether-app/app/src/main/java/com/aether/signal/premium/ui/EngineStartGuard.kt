package com.aether.signal.premium.ui

import com.aether.signal.premium.data.BACKTEST_TIMEFRAMES
import com.aether.signal.premium.data.validateCustomPair
import com.aether.signal.premium.engine.BacktestParams
import com.aether.signal.premium.engine.Combo
import com.aether.signal.premium.engine.FILTER_DEFS
import com.aether.signal.premium.engine.FilterCfg
import com.aether.signal.premium.engine.MAX_OPEN_TRADES_MAX
import com.aether.signal.premium.engine.MAX_OPEN_TRADES_MIN
import com.aether.signal.premium.engine.STRATEGIES
import com.aether.signal.premium.engine.configFingerprint
import com.aether.signal.premium.engine.sanitizeParams
import com.aether.signal.premium.engine.tpMultsFromPct

// Tahap 3 — Guard integrasi Backtest → Start Engine (MURNI, teruji JVM).
//
// Empat konfigurasi dibedakan eksplisit:
//  1. snapshot backtest terakhir yang berhasil (EngineSnapshot),
//  2. global live yang sedang diedit (EngineGlobalsData),
//  3. pasangan Engine (bagian dari EngineGlobalsData.engPairs),
//  4. konfigurasi efektif Engine (sanitized BacktestParams + pairs + provider).
//
// Perbandingan memakai nilai EFEKTIF setelah sanitasi/normalisasi, bukan teks
// mentah. Penerapan snapshot bersifat atomik dengan rollback.

// ---------- Bundel global pembentuk BacktestParams (cermin App.*) ----------
data class EngineGlobalsData(
    val strategy: String = "ema_trend",
    val comboMode: String = "",
    val comboExtra: List<String> = emptyList(),
    val timeframe: String = "15m",
    val provider: String = "binance",
    val pair: String = "BTCUSDT",
    val mode: String = "single",
    val multiSel: List<String> = emptyList(),
    val universe: List<String> = emptyList(),
    val engPairs: List<String> = listOf("BTCUSDT", "ETHUSDT"),
    val capital: Double = 1000.0,
    val riskPct: Double = 1.0,
    val leverage: Int = 1,
    val feePct: Double = 0.05,
    val slipPct: Double = 0.02,
    val maxHolding: Int = 100,
    // Tahap 4: batas posisi (cermin V41, default 1, session-only).
    val maxOpenTrades: Int = 1,
    val slPct: Double = 1.0,
    val tpPct: Double = 2.0,
    val tp2Pct: Double = 2.5,
    val tp3Pct: Double = 3.5,
    val tpMode: Int = 1,
    val useAtr: Boolean = false,
    val atrSlMult: Double = 1.5,
    val atrTpMult: Double = 3.0,
    val btTrailOn: Boolean = false,
    val btTrailDistPct: Double = 1.0,
    val btTrailActPct: Double = 1.0,
    val filterOn: Map<String, Boolean> = emptyMap(),
    val fVol: Double = 1.0,
    val fAdx: Double = 20.0,
    val fRsiHi: Double = 72.0,
    val fRsiLo: Double = 28.0,
    val fAtrMin: Double = 0.3,
    val fAtrMax: Double = 5.0,
    val fSession: String = "all",
    val fCool: Double = 3.0,
    val fSr: Double = 0.3,
    val limit: Int = 500,
    val fromDate: Long = 0L,
    val toDate: Long = 0L
)

// ---------- Konstruksi FilterCfg (replika 1:1 App.activeFilterCfgs) ----------
fun activeFilterCfgsFrom(g: EngineGlobalsData): List<FilterCfg> {
    val on = FILTER_DEFS.map { it.id }.filter { g.filterOn[it] == true }
    if (on.isEmpty()) return emptyList()
    val sessMap = mapOf(
        "all" to listOf(0 to 24), "asia" to listOf(0 to 8),
        "london" to listOf(7 to 16), "ny" to listOf(13 to 21),
        "asia_london" to listOf(0 to 16), "london_ny" to listOf(7 to 21)
    )
    return on.map { name ->
        val prm = when (name) {
            "volume" -> mapOf("mult" to g.fVol)
            "atr_vol" -> mapOf("minPct" to g.fAtrMin)
            "adx" -> mapOf("min" to g.fAdx)
            "rsi" -> mapOf("longMax" to g.fRsiHi, "shortMin" to g.fRsiLo)
            "sr" -> mapOf("bufferPct" to g.fSr)
            "min_vol" -> mapOf("minPct" to g.fAtrMin)
            "max_vol" -> mapOf("maxPct" to g.fAtrMax)
            "cooldown" -> mapOf("bars" to g.fCool)
            else -> emptyMap()
        }
        FilterCfg(name, true, prm, if (name == "session") sessMap[g.fSession] else null)
    }
}

// ---------- Konstruksi BacktestParams (replika 1:1 App.buildParams) ----------
fun buildParamsFrom(g: EngineGlobalsData, asset: String): BacktestParams {
    val extra = g.comboExtra.toList()
    val combo = if (g.comboMode.isNotEmpty()) {
        val set = LinkedHashSet<String>()
        set.add(g.strategy); set.addAll(extra)
        if (set.size < 2) throw IllegalArgumentException("Combo butuh minimal 2 strategi.")
        Combo(g.comboMode, set.toList().take(5))
    } else null
    val (m2, m3) = tpMultsFromPct(g.tpPct, g.tp2Pct, g.tp3Pct)
    return BacktestParams(
        asset = asset, timeframe = g.timeframe, initialCapital = g.capital,
        riskPerTrade = g.riskPct / 100, leverage = g.leverage,
        feePercent = g.feePct / 100, slippagePercent = g.slipPct / 100,
        slPercent = g.slPct / 100, tpPercent = g.tpPct / 100, maxHolding = g.maxHolding,
        tpMode = g.tpMode, tp2Mult = m2, tp3Mult = m3,
        trailOn = g.btTrailOn, trailDistPct = g.btTrailDistPct / 100, trailActPct = g.btTrailActPct / 100,
        maxOpenTrades = g.maxOpenTrades,
        strategy = g.strategy, combo = combo, filters = activeFilterCfgsFrom(g),
        startDate = g.fromDate, endDate = g.toDate, useAtr = g.useAtr
    )
}

// ---------- Snapshot backtest terakhir (parameter efektif + asal global) ----------
data class EngineSnapshot(
    val globals: EngineGlobalsData,
    val params: BacktestParams, // sudah disanitasi saat snapshot dibuat
    val pairs: List<String>, // pair backtest (single = [asset])
    val provider: String,
    val limit: Int,
    val mode: String, // single | multi
    val at: Long,
    val status: String, // SUCCESS mewajibkan untuk diterapkan
    val valid: Boolean,
    val origin: String // backtest-single | backtest-multi
)

// ---------- Perbedaan konfigurasi efektif ----------
data class DriftItem(val field: String, val current: String, val reference: String)

fun trimNumD(v: Double): String {
    if (!v.isFinite()) return "—"
    var s = "%.6f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
    if (s.isEmpty() || s == "-0") s = "0"
    return s
}

private fun Double.eqD(o: Double): Boolean = this == o || (this.isNaN() && o.isNaN())

/** Bandingkan dua BacktestParams field-per-field (asset ikut dibanding di sini). */
fun diffParams(a: BacktestParams, b: BacktestParams): List<DriftItem> {
    val out = ArrayList<DriftItem>()
    fun num(field: String, x: Double, y: Double) {
        if (!x.eqD(y)) out.add(DriftItem(field, trimNumD(x), trimNumD(y)))
    }
    fun str(field: String, x: String, y: String) {
        if (x != y) out.add(DriftItem(field, x.ifEmpty { "—" }, y.ifEmpty { "—" }))
    }
    str("aset", a.asset, b.asset)
    str("timeframe", a.timeframe, b.timeframe)
    str("strategi", a.strategy, b.strategy)
    val ac = a.combo; val bc = b.combo
    if ((ac == null) != (bc == null) || ac?.mode != bc?.mode || ac?.strategies != bc?.strategies) {
        val f = { c: Combo? -> if (c == null) "tunggal" else "${c.mode.ifEmpty { "OR" }}:${c.strategies.joinToString("+")}" }
        out.add(DriftItem("kombinasi", f(ac), f(bc)))
    }
    if (a.strategyParams != b.strategyParams) {
        out.add(DriftItem("parameter strategi", a.strategyParams.toString(), b.strategyParams.toString()))
    }
    num("modal", a.initialCapital, b.initialCapital)
    num("risiko/trade", a.riskPerTrade, b.riskPerTrade)
    if (a.leverage != b.leverage) out.add(DriftItem("leverage", a.leverage.toString(), b.leverage.toString()))
    num("fee", a.feePercent, b.feePercent)
    num("slippage", a.slippagePercent, b.slippagePercent)
    num("stop loss", a.slPercent, b.slPercent)
    num("take profit", a.tpPercent, b.tpPercent)
    if (a.maxHolding != b.maxHolding) out.add(DriftItem("max holding", a.maxHolding.toString(), b.maxHolding.toString()))
    // Tahap 4: batas posisi bagian sidik efektif.
    if (a.maxOpenTrades != b.maxOpenTrades) out.add(DriftItem("posisi maks", a.maxOpenTrades.toString(), b.maxOpenTrades.toString()))
    if (a.tpMode != b.tpMode) out.add(DriftItem("mode TP", a.tpMode.toString(), b.tpMode.toString()))
    num("TP2 mult", a.tp2Mult, b.tp2Mult)
    num("TP3 mult", a.tp3Mult, b.tp3Mult)
    if (a.useAtr != b.useAtr) out.add(DriftItem("mode ATR", a.useAtr.toString(), b.useAtr.toString()))
    num("ATR SL mult", a.atrSlMult, b.atrSlMult)
    num("ATR TP mult", a.atrTpMult, b.atrTpMult)
    if (a.trailOn != b.trailOn) out.add(DriftItem("trailing", a.trailOn.toString(), b.trailOn.toString()))
    num("trail jarak", a.trailDistPct, b.trailDistPct)
    num("trail aktivasi", a.trailActPct, b.trailActPct)
    if (a.startDate != b.startDate || a.endDate != b.endDate) {
        out.add(DriftItem("rentang tanggal", "${a.startDate}→${a.endDate}", "${b.startDate}→${b.endDate}"))
    }
    // Filter: bandingkan per-id (aktif + parameter + sesi).
    val am = a.filters.filter { it.enabled }.associate { it.name to it }
    val bm = b.filters.filter { it.enabled }.associate { it.name to it }
    val ids = (am.keys + bm.keys).sorted()
    for (id in ids) {
        val x = am[id]; val y = bm[id]
        when {
            x == null -> out.add(DriftItem("filter $id", "mati", "aktif"))
            y == null -> out.add(DriftItem("filter $id", "aktif", "mati"))
            x.params != y.params || x.sessions != y.sessions ->
                out.add(DriftItem("filter $id", x.params.toString(), y.params.toString()))
        }
    }
    return out
}

/**
 * Bandingkan snapshot Engine saat ini vs referensi backtest.
 * Aset dinormalisasi (keduanya "*") — perbedaan pair dibanding terpisah.
 */
fun diffSnapshots(cur: EngineSnapshot, ref: EngineSnapshot): List<DriftItem> {
    val out = ArrayList<DriftItem>()
    // Normalisasi aset agar sidik strategi yang dibanding, bukan simbol.
    val cp = try { sanitizeParams(cur.params.copy(asset = "*")) } catch (e: Exception) { cur.params }
    val rp = try { sanitizeParams(ref.params.copy(asset = "*")) } catch (e: Exception) { ref.params }
    out.addAll(diffParams(cp, rp).filter { it.field != "aset" })
    if (cur.provider != ref.provider) {
        out.add(DriftItem("provider", cur.provider, ref.provider))
    }
    val cs = cur.pairs.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSortedSet()
    val rs = ref.pairs.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSortedSet()
    if (cs != rs) {
        val add = (rs - cs).sorted().joinToString(",").ifEmpty { "—" }
        val del = (cs - rs).sorted().joinToString(",").ifEmpty { "—" }
        out.add(DriftItem("pasangan", "${cs.size} pair${if (del != "—") " (−$del)" else ""}",
            "${rs.size} pair${if (add != "—") " (+$add)" else ""}"))
    }
    if (cur.mode != ref.mode) out.add(DriftItem("mode", cur.mode, ref.mode))
    if (cur.limit != ref.limit) out.add(DriftItem("limit", cur.limit.toString(), ref.limit.toString()))
    return out
}

// ---------- Validasi snapshot untuk diterapkan ----------
data class ApplyVerdict(val ok: Boolean, val reasons: List<String>)

val KNOWN_PROVIDERS = setOf("binance", "bybit", "yahoo", "demo")

/** Batas rentang sama dengan sanitizeParams — di luar ini penerapan ditolak (bukan di-clamp diam-diam). */
fun checkRanges(p: BacktestParams): List<String> {
    val bad = ArrayList<String>()
    fun rng(f: String, v: Double, lo: Double, hi: Double) {
        if (!v.isFinite() || v < lo || v > hi) bad.add("$f=${trimNumD(v)} di luar [$lo, $hi]")
    }
    if (!p.initialCapital.isFinite() || p.initialCapital <= 0) bad.add("modal harus > 0")
    rng("risiko/trade", p.riskPerTrade, 0.001, 0.10)
    if (p.leverage < 1 || p.leverage > 100) bad.add("leverage=${p.leverage} di luar [1, 100]")
    rng("fee", p.feePercent, 0.0, 0.02)
    rng("slippage", p.slippagePercent, 0.0, 0.02)
    rng("stop loss", p.slPercent, 0.0005, 0.5)
    rng("take profit", p.tpPercent, 0.0005, 1.0)
    if (p.maxHolding < 1 || p.maxHolding > 2000) bad.add("maxHolding=${p.maxHolding} di luar [1, 2000]")
    // Tahap 4: batas posisi 1..10 (cermin sanitizeMaxOpenTrades; bukan clamp diam-diam).
    if (p.maxOpenTrades < MAX_OPEN_TRADES_MIN || p.maxOpenTrades > MAX_OPEN_TRADES_MAX) {
        bad.add("posisi maks=${p.maxOpenTrades} di luar [$MAX_OPEN_TRADES_MIN, $MAX_OPEN_TRADES_MAX]")
    }
    rng("ATR SL mult", p.atrSlMult, 0.2, 10.0)
    rng("ATR TP mult", p.atrTpMult, 0.2, 20.0)
    if (p.tpMode < 1 || p.tpMode > 3) bad.add("tpMode=${p.tpMode} di luar [1, 3]")
    rng("TP2 mult", p.tp2Mult, 1.0, 10.0)
    rng("TP3 mult", p.tp3Mult, 1.0, 10.0)
    if (p.tp3Mult < p.tp2Mult) bad.add("TP3 mult < TP2 mult")
    rng("trail jarak", p.trailDistPct, 0.0005, 0.5)
    rng("trail aktivasi", p.trailActPct, 0.0005, 0.5)
    if (p.startDate != 0L && p.endDate != 0L && p.endDate <= p.startDate) bad.add("rentang tanggal invalid")
    return bad
}

/**
 * Validasi penuh snapshot sebelum diterapkan. Gagal → seluruh penerapan
 * dibatalkan (tidak parsial). Alasan spesifik dikembalikan untuk dialog.
 */
fun validateSnapshotForApply(s: EngineSnapshot): ApplyVerdict {
    val no = ArrayList<String>()
    if (!s.valid) no.add("snapshot tidak valid (bukan hasil backtest berhasil)")
    if (s.status != "SUCCESS") no.add("status snapshot '${s.status}' — hanya SUCCESS yang dapat diterapkan")
    if (s.pairs.isEmpty()) no.add("snapshot tidak menyimpan pasangan")
    if (s.provider !in KNOWN_PROVIDERS) no.add("provider '${s.provider}' tidak dikenal")
    if (s.limit < 60) no.add("limit ${s.limit} di bawah minimum 60 candle")
    if (no.isNotEmpty()) return ApplyVerdict(false, no)
    // Strategi + parameter strategi.
    if (!STRATEGIES.containsKey(s.params.strategy)) {
        no.add("strategi '${s.params.strategy}' tidak dikenal aplikasi ini")
    }
    val combo = s.params.combo
    if (combo != null) {
        if (combo.strategies.size < 2) no.add("kombinasi butuh minimal 2 strategi")
        for (id in combo.strategies) {
            if (!STRATEGIES.containsKey(id)) no.add("strategi kombinasi '$id' tidak dikenal")
        }
    }
    if (s.params.strategyParams.isNotEmpty()) {
        no.add("parameter strategi kustom tidak dapat dipetakan (${s.params.strategyParams.size} kunci)")
    }
    // Filter: id harus dikenal (sanitasi mesin membuang yang tak dikenal diam-diam).
    for (f in s.params.filters) {
        if (FILTER_DEFS.none { it.id == f.name }) no.add("filter '${f.name}' tidak dikenal")
    }
    // Pengali ATR: tak ada field global di aplikasi → harus bawaan.
    if (s.params.atrSlMult != 1.5 || s.params.atrTpMult != 3.0) {
        no.add("pengali ATR (${trimNumD(s.params.atrSlMult)}/${trimNumD(s.params.atrTpMult)}) tak dapat dipetakan")
    }
    // Timeframe: harus didukung backtest + dapat diparsing mesin.
    try {
        com.aether.signal.premium.engine.parseTimeframe(s.params.timeframe)
    } catch (e: Exception) {
        no.add("timeframe '${s.params.timeframe}' tidak valid")
    }
    if (s.params.timeframe !in BACKTEST_TIMEFRAMES) {
        no.add("timeframe '${s.params.timeframe}' di luar ${BACKTEST_TIMEFRAMES.joinToString(",")}")
    }
    // Pasangan: normalisasi + validasi per provider. Satu gagal → tolak semua.
    val badPairs = ArrayList<String>()
    for (raw in s.pairs) {
        val (_, err) = try {
            validateCustomPair(raw, s.provider)
        } catch (e: Exception) {
            "" to (e.message ?: "gagal validasi")
        }
        if (err != null) badPairs.add("$raw ($err)")
    }
    if (badPairs.isNotEmpty()) {
        no.add("pasangan tak didukung provider ${s.provider}: ${badPairs.joinToString("; ")}")
    }
    // Rentang numerik + kestabilan sanitasi (sanitasi tak boleh mengubah nilai).
    no.addAll(checkRanges(s.params))
    try {
        val re = sanitizeParams(s.params)
        val changed = diffParams(re, s.params)
        if (changed.isNotEmpty()) {
            no.add("sanitasi mengubah nilai: " + changed.joinToString("; ") { "${it.field}: ${it.reference}→${it.current}" })
        }
    } catch (e: Exception) {
        no.add("sanitasi menolak parameter: ${e.message}")
    }
    return ApplyVerdict(no.isEmpty(), no)
}

// ---------- Penyimpanan global (diimplementasi sisi Android + Fake uji) ----------
interface EngineGlobalsStore {
    fun capture(): EngineGlobalsData
    fun restore(d: EngineGlobalsData)
}

/**
 * Global efektif untuk diterapkan: salinan global snapshot dengan identitas
 * pair/mode/universe dipetakan ke pair backtest (tanpa menghilangkan pair
 * diam-diam — pair sudah divalidasi per provider di validateSnapshotForApply).
 */
fun effectiveGlobalsForApply(s: EngineSnapshot): EngineGlobalsData {
    val clean = s.pairs.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    return if (s.mode == "multi") {
        s.globals.copy(
            mode = "multi",
            multiSel = clean,
            universe = (s.globals.universe + clean).distinct(),
            engPairs = clean
        )
    } else {
        s.globals.copy(
            mode = "single",
            pair = clean.first(),
            engPairs = clean
        )
    }
}

/**
 * Terapkan snapshot ke store secara atomik: siapkan kandidat → validasi ulang
 * pasca-sanitasi → terapkan → verifikasi rebuild. Gagal di titik mana pun →
 * kembalikan state sebelumnya.
 */
fun applySnapshotToStore(store: EngineGlobalsStore, snap: EngineSnapshot): ApplyVerdict {
    val pre = validateSnapshotForApply(snap)
    if (!pre.ok) return pre
    val before = try {
        store.capture()
    } catch (e: Exception) {
        return ApplyVerdict(false, listOf("gagal membaca konfigurasi aktif: ${e.message}"))
    }
    val eff = try {
        effectiveGlobalsForApply(snap)
    } catch (e: Exception) {
        return ApplyVerdict(false, listOf("gagal memetakan pasangan: ${e.message}"))
    }
    if (eff.engPairs.isEmpty()) {
        return ApplyVerdict(false, listOf("pemetaan menghasilkan daftar pair Engine kosong"))
    }
    try {
        store.restore(eff)
    } catch (e: Exception) {
        return ApplyVerdict(false, listOf("gagal menulis konfigurasi: ${e.message} (aktif tidak berubah)"))
    }
    try {
        // Verifikasi: rebuild dari global terapan harus sama persis dengan snapshot
        // (aset disamakan; multi-pair memakai copy per pair saat runtime).
        val rebuiltSameAsset = try {
            sanitizeParams(buildParamsFrom(eff, snap.params.asset))
        } catch (e: Exception) {
            throw IllegalStateException("rebuild gagal: ${e.message}")
        }
        val changed = diffParams(rebuiltSameAsset, snap.params)
        if (changed.isNotEmpty()) {
            try {
                store.restore(before)
            } catch (_: Exception) {
            }
            return ApplyVerdict(false, listOf(
                "verifikasi pasca-terap gagal: " +
                    changed.joinToString("; ") { "${it.field}: ${it.reference}→${it.current}" } +
                    " (dikembalikan ke sebelumnya)"))
        }
        return ApplyVerdict(true, emptyList())
    } catch (e: Exception) {
        try {
            store.restore(before)
        } catch (_: Exception) {
        }
        return ApplyVerdict(false, listOf("verifikasi gagal: ${e.message} (dikembalikan ke sebelumnya)"))
    }
}

// ---------- Gerbang entry Engine live (Bagian A, murni, teruji JVM) ----------

/** Keputusan gerbang entry satu pair pada Engine live. */
enum class OpenDecision { ALLOW, HOLD_PAIR, CAP_FULL }

/**
 * Satu-satunya pemeriksaan batas entry Engine: pair sudah punya posisi
 * (maks 1/pair, perilaku lama) ATAU total posisi >= cap global sesi beku.
 * cap<=0 diperlakukan sebagai 1 (defensif; sanitasi normal 1..10).
 */
fun gateEntry(openCount: Int, cap: Int, pairHasOpen: Boolean): OpenDecision = when {
    pairHasOpen -> OpenDecision.HOLD_PAIR
    openCount >= cap.coerceAtLeast(1) -> OpenDecision.CAP_FULL
    else -> OpenDecision.ALLOW
}

/**
 * Gerbang anti-tumpang-tindih tick (Bagian A.6/C.2, murni, teruji JVM).
 * Dua scheduler (BotEngine 90 dtk + MonitorService adaptif) berjalan di
 * thread berbeda; tanpa ini dua tick dapat mengevaluasi & membuka posisi
 * bersamaan (posisi ganda, ConcurrentModificationException pada daftar).
 */
class TickGate {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true = pemanggil memiliki giliran tick; false = tick lain berjalan. */
    fun tryBegin(): Boolean = busy.compareAndSet(false, true)

    fun end() {
        busy.set(false)
    }
}

// ---------- Keputusan Start ----------
enum class StartDecision { START_DIRECT, SHOW_DIALOG, BLOCK_RUNNING }

/** Murni: tentukan aksi tombol Start dari status Engine + referensi + drift. */
fun decideStartAction(engRunning: Boolean, ref: EngineSnapshot?, drift: List<DriftItem>): StartDecision =
    when {
        engRunning -> StartDecision.BLOCK_RUNNING
        ref != null && ref.valid && ref.status == "SUCCESS" && drift.isNotEmpty() ->
            StartDecision.SHOW_DIALOG
        else -> StartDecision.START_DIRECT
    }

// ---------- Sesi Engine beku ( toleran: Engine memakai snapshot startup) ----------
data class EngineSession(
    val params: BacktestParams, // sanitized
    val pairs: List<String>,
    val provider: String,
    val fp: String,
    val startedAt: Long
)

/** Bekukan sesi dari global live. Melempar bila konfigurasi invalid. */
fun freezeSession(g: EngineGlobalsData, pairs: List<String>, nowMs: Long): EngineSession {
    val first = pairs.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Pilih pair dulu.")
    val p = sanitizeParams(buildParamsFrom(g, first))
    return EngineSession(p, pairs.toList(), g.provider, configFingerprint(p), nowMs)
}

/** Sidik efektif global live (untuk deteksi divergensi vs sesi). */
fun liveFingerprint(g: EngineGlobalsData): String = try {
    configFingerprint(sanitizeParams(buildParamsFrom(g, g.engPairs.firstOrNull() ?: g.pair)))
} catch (e: Exception) {
    ""
}
