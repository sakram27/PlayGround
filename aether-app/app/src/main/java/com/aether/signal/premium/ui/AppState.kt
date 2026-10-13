package com.aether.signal.premium.ui

import android.content.Context
import android.content.SharedPreferences
import com.aether.signal.premium.ai.Sig
import com.aether.signal.premium.data.CandleDiskCache
import com.aether.signal.premium.data.FetchErrorKind
import com.aether.signal.premium.data.FetchMeta
import com.aether.signal.premium.data.HEALTH_RING
import com.aether.signal.premium.data.HealthSample
import com.aether.signal.premium.data.ProviderStats
import com.aether.signal.premium.data.decodeHealth
import com.aether.signal.premium.data.encodeHealth
import com.aether.signal.premium.data.summarizeHealth
import com.aether.signal.premium.engine.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// Cermin state app.js: runtime + persistensi SharedPreferences + snapshot terapan.

data class AppliedCfg(
    val origin: String, val at: Long, val strategy: String, val comboMode: String,
    val combo: List<String>, val strategyMode: String,
    val activeFilters: List<String>, val disabledFilters: List<String>,
    val rr: String, val slLabel: String, val tpLabel: String,
    val timeframe: String, val pairs: List<String>, val status: String, val valid: Boolean,
    val tpModeLabel: String = "Single TP"
)

data class LastBt(
    val mode: String, val pairs: List<String>, val strategy: String, val timeframe: String,
    val limit: Int, val from: Long, val to: Long, val candlesProcessed: Int,
    val trades: Int, val engineMs: Long, val totalMs: Long, val status: String, val at: Long
)

object App {
    lateinit var prefs: SharedPreferences
        private set

    var pair: String = "BTCUSDT"
    var mode: String = "single" // single | multi
    var multiSel: LinkedHashSet<String> = LinkedHashSet()
    var universe: List<String> = emptyList()
    var strategy: String = "ema_trend"
    var presetCustom: Boolean = false
    var comboMode: String = "" // "" | OR | AND | MAJORITY
    var comboExtra: LinkedHashSet<String> = LinkedHashSet()
    // C: watchlist kustom + batas jumlah tampil (persist, C7).
    var customPairs: LinkedHashSet<String> = LinkedHashSet()
    var watchLimit: Int = DEFAULT_WATCH_LIMIT
    var capital: Double = 1000.0
    var riskPct: Double = 1.0
    var leverage: Int = 1
    var feePct: Double = 0.05
    var slipPct: Double = 0.02
    var maxHolding: Int = 100
    // V31: bawaan SL 1%, TP 2% (was 1.5/3.0). Session-only: hanya dipakai bila
    // belum ada pengaturan tersimpan; nilai tersimpan (impor) tak ditimpa.
    var slPct: Double = 1.0
    var tpPct: Double = 2.0
    // V31: input TP2/TP3 dalam PERSEN absolut (2.5 = 2,5%, bukan ×2,5 dan
    // bukan 0,025). Dikonversi ke kelipatan via tpMultsFromPct di buildParams.
    var tp2Pct: Double = 2.5
    var tp3Pct: Double = 3.5
    // V31: trailing stop BACKTEST (terpisah dari trailing virtual paper).
    // Mati default; konfigurasi lama (tanpa kunci ini) tetap mati.
    var btTrailOn: Boolean = false
    var btTrailDistPct: Double = 1.0
    var btTrailActPct: Double = 1.0
    // V26: skema TP (1 = Single TP, 2 = TP2, 3 = TP3). Session-only seperti
    // parameter risiko lain (tak dipersist/diimpor — konsisten dgn slPct).
    var tpMode: Int = 1
    // Mode TP yang dipakai run terakhir (untuk label hasil; bukan konfigurasi aktif).
    var lastTpMode: Int = 1
    // V34: penanda hasil Hyperopt terakhir yang diterapkan (session-only,
    // seperti parameter risiko). Null = parameter manual/bawaan.
    var lastHyperopt: Triple<Double, Double, Double>? = null
    var lastHyperoptAt: Long = 0L
    // V36: basis konfigurasi saat optimasi berjalan (kunci V36). Bila basis
    // berubah sesudahnya, hasil dianggap basi → peringatan di ringkasan.
    var lastHyperoptBasis: String = ""
    var lastHyperoptBasisDesc: String = ""
    fun hyperoptSourceLine(): String? {
        val h = lastHyperopt ?: return null
        return "Hyperopt terapan (SL ${h.first}% · TP ${h.second}% · Risiko ${h.third}%)"
    }
    /**
     * V36: true bila basis konfigurasi [p] masih sama dengan basis saat
     * Hyperopt berjalan (atau tak ada hasil terapan). Murni, teruji.
     */
    fun hyperoptBasisFresh(p: BacktestParams): Boolean {
        if (lastHyperopt == null || lastHyperoptBasis.isEmpty()) return true
        return hyperoptBasisKey(p) == lastHyperoptBasis
    }
    var useAtr: Boolean = false
    var provider: String = "binance"
    var timeframe: String = "15m"
    var limit: Int = 500
    var fromDate: Long = 0
    var toDate: Long = 0
    var filterOn: MutableMap<String, Boolean> = HashMap()
    var fVol = 1.0; var fAdx = 20.0; var fRsiHi = 72.0; var fRsiLo = 28.0
    var fAtrMin = 0.3; var fAtrMax = 5.0; var fSession = "all"; var fCool = 3.0; var fSr = 0.3

    var signals: ArrayList<Sig> = ArrayList()
    // Arsip simulasi backtest + data lama tak terklasifikasi (terpisah dari Engine).
    var backtestSignals: ArrayList<Sig> = ArrayList()
    var legacySignals: ArrayList<Sig> = ArrayList()
    var hist: ArrayList<Trade> = ArrayList()
    var candles: List<Candle> = emptyList()
    var params: BacktestParams? = null
    var result: BacktestResult? = null
    // V17: snapshot meta fetch milik run terakhir (provider/symbol/TF/limit/sumber
    // saat data diambil — bukan nilai dropdown saat ini yang bisa sudah berubah).
    var lastFetchMeta: FetchMeta? = null
    // V21: waktu pengambilan run terakhir (untuk panel validasi).
    var lastFetchAt: Long = 0L
    var csv: List<Candle>? = null
    var multiCache: Map<String, Pair<List<Candle>, BacktestResult>> = emptyMap()
    var multiRows: List<PairRow> = emptyList()

    var engPairs: LinkedHashSet<String> = LinkedHashSet(listOf("BTCUSDT", "ETHUSDT"))
    var engRunning: Boolean = false
    // P7: mode hemat baterai (persist).
    var batterySaver: Boolean = false

    fun init(ctx: Context) {
        if (this::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("aether", Context.MODE_PRIVATE)
        try { CandleDiskCache.attach(java.io.File(ctx.filesDir, "candles")) } catch (e: Exception) { /* cache opsional */ }
        pair = prefs.getString("pair", "BTCUSDT") ?: "BTCUSDT"
        multiSel = LinkedHashSet(prefs.getStringSet("multipair", emptySet()) ?: emptySet())
        strategy = prefs.getString("strategy", "ema_trend") ?: "ema_trend"
        provider = prefs.getString("provider", "binance") ?: "binance"
        engPairs = LinkedHashSet(prefs.getStringSet("engpairs", setOf("BTCUSDT", "ETHUSDT")) ?: emptySet())
        comboExtra = LinkedHashSet(prefs.getStringSet("comboextra", emptySet()) ?: emptySet())
        customPairs = LinkedHashSet(prefs.getStringSet("custompairs", emptySet()) ?: emptySet())
        watchLimit = prefs.getInt("watchlimit", DEFAULT_WATCH_LIMIT).coerceIn(1, MAX_WATCH_PAIRS)
        batterySaver = prefs.getBoolean("batterySaver", false)
        val savedFilters = prefs.getStringSet("filteron", null)
        if (savedFilters != null) {
            for (id in FILTER_DEFS.map { it.id }) filterOn[id] = savedFilters.contains(id)
        }
        loadSignals(); loadHist(); loadJournal(); loadRegime()
    }

    fun savePair() = prefs.edit().putString("pair", pair).apply()    fun saveMulti() = prefs.edit().putStringSet("multipair", LinkedHashSet(multiSel)).apply()
    fun saveStrategy() = prefs.edit().putString("strategy", strategy).apply()
    fun saveEngPairs() = prefs.edit().putStringSet("engpairs", LinkedHashSet(engPairs)).apply()
    // V28: sinkronisasi EKSPLISIT pair Backtest → Engine. Hanya dipanggil dari
    // tombol "Salin dari Backtest" (dengan konfirmasi) — tidak pernah diam-diam.
    // Mengembalikan daftar yang dipakai; kosong = konfigurasi tak valid sehingga
    // daftar engine saat ini dipertahankan.
    fun syncEngPairsFromBacktest(): LinkedHashSet<String> {
        engPairs = resolveEnginePairs(mode, pair, multiSel, universe, engPairs)
        saveEngPairs()
        return LinkedHashSet(engPairs)
    }
    fun saveCombo() = prefs.edit().putStringSet("comboextra", LinkedHashSet(comboExtra)).apply()
    fun saveCustomPairs() = prefs.edit().putStringSet("custompairs", LinkedHashSet(customPairs)).apply()
    fun saveWatchLimit() = prefs.edit().putInt("watchlimit", watchLimit.coerceIn(1, MAX_WATCH_PAIRS)).apply()
    fun saveBatterySaver() = prefs.edit().putBoolean("batterySaver", batterySaver).apply()
    fun resetWatchlist() {
        customPairs.clear(); saveCustomPairs()
        watchLimit = DEFAULT_WATCH_LIMIT; saveWatchLimit()
    }

    // P8: ring kesehatan provider (persist JSON, maks 20 sampel/provider).
    private var healthCache: MutableMap<String, MutableList<HealthSample>>? = null
    private fun healthAll(): MutableMap<String, MutableList<HealthSample>> {
        var h = healthCache
        if (h == null) {
            h = decodeHealth(prefs.getString("provider_health", null))
                .mapValues { it.value.toMutableList() }.toMutableMap()
            healthCache = h
        }
        return h
    }
    fun recordHealth(provider: String, ok: Boolean, latencyMs: Long, kind: FetchErrorKind?, msg: String) {
        try {
            val h = healthAll()
            val list = h.getOrPut(provider) { ArrayList() }
            list.add(HealthSample(ok, latencyMs, kind, msg.take(120), System.currentTimeMillis()))
            while (list.size > HEALTH_RING) list.removeAt(0)
            prefs.edit().putString("provider_health", encodeHealth(h)).apply()
        } catch (e: Exception) { /* telemetri tak boleh crash */ }
    }
    fun healthStats(provider: String): ProviderStats =
        try { summarizeHealth(healthAll()[provider] ?: emptyList()) } catch (e: Exception) {
            ProviderStats(false, false, 0, 0, 0, 0, null, "")
        }
    fun lastHealthSuccess(provider: String): Long =
        try { healthAll()[provider]?.lastOrNull { it.ok }?.at ?: 0L } catch (e: Exception) { 0L }

    // A7: cache harga terakhir per pair (bedakan live vs cache di UI).
    private fun lpKey(prov: String, sym: String, tf: String) = "lp_${prov}_${sym}_$tf"
    fun saveLastPrice(prov: String, sym: String, tf: String, price: String, chg: Double?, spark: List<Double>) {
        prefs.edit().putString(lpKey(prov, sym, tf),
            serializeLastPrice(price, chg, spark, System.currentTimeMillis())).apply()
    }
    fun getLastPrice(prov: String, sym: String, tf: String): LastPrice? =
        parseLastPrice(prefs.getString(lpKey(prov, sym, tf), null))
    fun saveFilters() = prefs.edit().putStringSet(
        "filteron",
        FILTER_DEFS.map { it.id }.filter { filterOn[it] == true }.toSet()
    ).apply()

    fun secOpen(key: String, def: Boolean): Boolean = prefs.getBoolean("labsec_$key", def)
    fun setSecOpen(key: String, open: Boolean) = prefs.edit().putBoolean("labsec_$key", open).apply()

    // ---------- filter → FilterCfg (cermin readFilters app.js) ----------
    fun activeFilterCfgs(): List<FilterCfg> {
        val on = FILTER_DEFS.map { it.id }.filter { filterOn[it] == true }
        if (on.isEmpty()) return emptyList()
        val sessMap = mapOf("all" to listOf(0 to 24), "asia" to listOf(0 to 8), "london" to listOf(7 to 16), "ny" to listOf(13 to 21), "asia_london" to listOf(0 to 16), "london_ny" to listOf(7 to 21))
        return on.map { name ->
            val prm = when (name) {
                "volume" -> mapOf("mult" to fVol)
                "atr_vol" -> mapOf("minPct" to fAtrMin)
                "adx" -> mapOf("min" to fAdx)
                "rsi" -> mapOf("longMax" to fRsiHi, "shortMin" to fRsiLo)
                "sr" -> mapOf("bufferPct" to fSr)
                "min_vol" -> mapOf("minPct" to fAtrMin)
                "max_vol" -> mapOf("maxPct" to fAtrMax)
                "cooldown" -> mapOf("bars" to fCool)
                else -> emptyMap()
            }
            FilterCfg(name, true, prm, if (name == "session") sessMap[fSession] else null)
        }
    }

    fun buildParams(asset: String): BacktestParams = buildParamsFrom(captureGlobals(), asset)

    /** Baca seluruh global pembentuk parameter ke bundel murni (Tahap 3). */
    fun captureGlobals(): EngineGlobalsData = EngineGlobalsData(
        strategy = strategy, comboMode = comboMode, comboExtra = comboExtra.toList(),
        timeframe = timeframe, provider = provider, pair = pair, mode = mode,
        multiSel = multiSel.toList(), universe = universe.toList(), engPairs = engPairs.toList(),
        capital = capital, riskPct = riskPct, leverage = leverage,
        feePct = feePct, slipPct = slipPct, maxHolding = maxHolding,
        slPct = slPct, tpPct = tpPct, tp2Pct = tp2Pct, tp3Pct = tp3Pct, tpMode = tpMode,
        useAtr = useAtr, atrSlMult = 1.5, atrTpMult = 3.0,
        btTrailOn = btTrailOn, btTrailDistPct = btTrailDistPct, btTrailActPct = btTrailActPct,
        filterOn = FILTER_DEFS.map { it.id }.associateWith { filterOn[it] == true },
        fVol = fVol, fAdx = fAdx, fRsiHi = fRsiHi, fRsiLo = fRsiLo,
        fAtrMin = fAtrMin, fAtrMax = fAtrMax, fSession = fSession, fCool = fCool, fSr = fSr,
        limit = limit, fromDate = fromDate, toDate = toDate
    )

    // ---------- signals/history persist ----------
    // INVARIAN SUMBER (fix pencampuran Engine vs Backtest):
    // - signals: HANYA sinyal Engine (dryrun-*). Card/notifikasi Engine membaca ini.
    // - backtestSignals: HANYA arsip simulasi backtest (detail hasil backtest).
    // - legacySignals: data lama yang sumbernya tak dapat ditentukan (tidak dihitung
    //   sebagai sinyal Engine, tidak dihapus).
    fun pushSignal(s: Sig) {
        // Rute defensif by sumber eksplisit: tak ada jalur yang bisa memasukkan
        // sinyal backtest ke daftar Engine meski pemanggil salah memakai fungsi ini.
        if (isBacktestSrc(s.src)) {
            pushBacktestSignal(s)
            return
        }
        if (!isEngineSrc(s.src)) {
            pushLegacySignal(s)
            return
        }
        if (signals.any { it.pair == s.pair && it.tf == s.tf && it.t == s.t && it.dir == s.dir }) return
        signals.add(0, s)
        if (signals.size > 200) signals = ArrayList(signals.take(200))
        saveSignals()
    }

    fun pushBacktestSignal(s: Sig) {
        val v = if (isBacktestSrc(s.src)) s else s.copy(src = SRC_BACKTEST)
        if (backtestSignals.any { it.id == v.id }) return
        backtestSignals.add(0, v)
        if (backtestSignals.size > 200) backtestSignals = ArrayList(backtestSignals.take(200))
        saveBacktestSignals()
    }

    private fun pushLegacySignal(s: Sig) {
        if (legacySignals.any { it.id == s.id && s.id.isNotEmpty() }) return
        legacySignals.add(0, s)
        if (legacySignals.size > 200) legacySignals = ArrayList(legacySignals.take(200))
        saveLegacySignals()
    }

    private fun sigToJson(s: Sig): JSONObject = JSONObject().put("pair", s.pair).put("tf", s.tf).put("dir", s.dir)
        .put("price", s.price).put("sl", s.sl).put("tp", s.tp).put("t", s.t).put("src", s.src).put("id", s.id)
        .put("strategy", s.strategy).put("confidence", s.confidence)
        .put("reasons", encodeStrList(s.reasons)).put("passedFilters", encodeStrList(s.passedFilters))
        .put("failedFilters", encodeStrList(s.failedFilters)).put("decidedAt", s.decidedAt)
        .put("score", s.score).put("scoreDetail", s.scoreDetail)

    private fun sigFromJson(o: JSONObject): Sig = Sig(o.getString("pair"), o.optString("tf"), o.getString("dir"), o.getDouble("price"),
        o.getDouble("sl"), o.getDouble("tp"), o.getLong("t"), o.optString("src"), o.getString("id"),
        o.optString("strategy", ""), o.optDouble("confidence", 0.0),
        parseStrList(o.optString("reasons", "")), parseStrList(o.optString("passedFilters", "")),
        parseStrList(o.optString("failedFilters", "")), o.optLong("decidedAt", 0L),
        o.optDouble("score", -1.0), o.optString("scoreDetail", ""))

    private fun saveSignals() {
        val a = JSONArray()
        for (s in signals) a.put(sigToJson(s))
        prefs.edit().putString("signals", a.toString()).apply()
    }

    private fun saveBacktestSignals() {
        val a = JSONArray()
        for (s in backtestSignals) a.put(sigToJson(s))
        prefs.edit().putString("backtest_signals", a.toString()).apply()
    }

    private fun saveLegacySignals() {
        val a = JSONArray()
        for (s in legacySignals) a.put(sigToJson(s))
        prefs.edit().putString("legacy_signals", a.toString()).apply()
    }

    private fun loadSignals() {
        try {
            // Migrasi sekali jalan: kunci lama "signals" bisa berisi campuran era
            // sebelum pemisahan → partisi by sumber eksplisit, tanpa menebak.
            val a = JSONArray(prefs.getString("signals", "[]"))
            val eng = ArrayList<Sig>()
            val bt = ArrayList<Sig>()
            val leg = ArrayList<Sig>()
            for (k in 0 until a.length()) {
                val s = sigFromJson(a.getJSONObject(k))
                when {
                    isBacktestSrc(s.src) -> bt.add(s)
                    isEngineSrc(s.src) -> eng.add(s)
                    else -> leg.add(s)
                }
            }
            // Gabung simpanan terpisah era baru (dedup by id).
            fun merge(base: ArrayList<Sig>, key: String) {
                try {
                    val b = JSONArray(prefs.getString(key, "[]"))
                    for (k in 0 until b.length()) {
                        val s = sigFromJson(b.getJSONObject(k))
                        if (base.none { it.id == s.id && s.id.isNotEmpty() }) base.add(s)
                    }
                } catch (e: Exception) { /* abaikan blok rusak */ }
            }
            merge(bt, "backtest_signals")
            merge(leg, "legacy_signals")
            signals = ArrayList(eng.take(200))
            backtestSignals = ArrayList(bt.take(200))
            legacySignals = ArrayList(leg.take(200))
            // Tulis balik terpartisi agar kunci lama tak lagi campuran.
            saveSignals(); saveBacktestSignals(); saveLegacySignals()
        } catch (e: Exception) { signals = ArrayList() }
    }

    fun addHist(trades: List<Trade>) {
        val seen = hist.map { "${it.asset}|${it.entryTime}|${it.exitTime}|${it.direction}" }.toHashSet()
        val fresh = trades.filter {
            val k = "${it.asset}|${it.entryTime}|${it.exitTime}|${it.direction}"
            if (seen.contains(k)) false else { seen.add(k); true }
        }
        hist = ArrayList((fresh + hist).take(500))
        val a = JSONArray()
        for (t in hist) a.put(JSONObject().put("asset", t.asset).put("direction", t.direction)
            .put("entry", t.entry).put("exit", t.exit).put("pnl", t.pnl).put("result", t.result)
            .put("entryTime", t.entryTime).put("exitTime", t.exitTime))
        prefs.edit().putString("hist", a.toString()).apply()
    }

    private fun loadHist() {
        try {
            val a = JSONArray(prefs.getString("hist", "[]"))
            hist = ArrayList((0 until a.length()).map { k ->
                val o = a.getJSONObject(k)
                Trade(o.getString("direction"), o.getString("asset"), "", o.getDouble("entry"), o.getDouble("exit"),
                    0.0, 0.0, o.getLong("entryTime"), o.getLong("exitTime"), "", 0.0, emptyList(),
                    0.0, 0.0, 0.0, o.getDouble("pnl"), 0.0, 0.0, o.getString("result"), 0)
            })
        } catch (e: Exception) { hist = ArrayList() }
    }

    fun clearSignals() { signals = ArrayList(); prefs.edit().remove("signals").apply() }
    fun clearHist() { hist = ArrayList(); prefs.edit().remove("hist").apply() }

    // ---------- jurnal trading lokal (F8, persist "journal_v1", kunci TERPISAH
    // dari signals/hist/notifikasi → pembersihan riwayat tak menyentuhnya) ----------
    var journal: ArrayList<JournalEntry> = ArrayList()

    private fun saveJournal() {
        try { prefs.edit().putString("journal_v1", encodeJournal(journal)).apply() } catch (e: Exception) { /* abaikan */ }
    }

    private fun loadJournal() {
        try { journal = ArrayList(parseJournal(prefs.getString("journal_v1", "[]"))) }
        catch (e: Exception) { journal = ArrayList() }
    }

    /** Tambah entri baru (id unik bila kosong). Mengembalikan entri tersimpan. */
    fun addJournal(e: JournalEntry): JournalEntry {
        val withId = if (e.id.isBlank()) e.copy(id = "j${System.currentTimeMillis()}_${(0..9999).random()}") else e
        val clean = sanitizeJournalEntry(withId) ?: withId
        journal.add(0, clean)
        while (journal.size > JOURNAL_CAP) journal.removeAt(journal.size - 1)
        saveJournal()
        return clean
    }

    /** Ubah entri by id (jaga createdAt & id). false bila id tak ditemukan. */
    fun updateJournal(id: String, e: JournalEntry): Boolean {
        val i = journal.indexOfFirst { it.id == id }
        if (i < 0) return false
        val old = journal[i]
        val clean = sanitizeJournalEntry(e.copy(id = id, createdAt = old.createdAt,
            updatedAt = System.currentTimeMillis())) ?: return false
        journal[i] = clean
        saveJournal()
        return true
    }

    /** Hapus HANYA entri ber-id tersebut. false bila tak ditemukan. */
    fun deleteJournal(id: String): Boolean {
        val i = journal.indexOfFirst { it.id == id }
        if (i < 0) return false
        journal.removeAt(i)
        saveJournal()
        return true
    }

    /** Terapkan hasil merge impor (lokal menang atas duplikat — lihat mergeJournal). */
    fun applyJournalMerge(merged: List<JournalEntry>) {
        journal = ArrayList(merged.take(JOURNAL_CAP))
        saveJournal()
    }

    // ---------- V16 F7: regime pasar per pair (ditulis BotEngine dari cache yang
    // sudah diambil; tanpa request tambahan). Persist ringan agar selamat restart. ----------
    var regime: MutableMap<String, Regime> = LinkedHashMap()
    // V27: diagnostik per-pair (ditulis tiap tick; persist agar selamat restart).
    var regimeDiag: MutableMap<String, RegimeDiag> = LinkedHashMap()

    fun saveRegime() {
        try {
            val o = JSONObject()
            for ((k, v) in regime) {
                o.put(k, JSONObject().put("label", v.label).put("volatile", v.volatile).put("at", v.at))
            }
            prefs.edit().putString("regime_v1", o.toString()).apply()
        } catch (e: Exception) { /* abaikan */ }
    }

    fun loadRegime() {
        try {
            val o = JSONObject(prefs.getString("regime_v1", "{}") ?: "{}")
            val keys = o.keys()
            val m = LinkedHashMap<String, Regime>()
            while (keys.hasNext()) {
                val k = keys.next()
                val j = o.optJSONObject(k) ?: continue
                m[k] = Regime(j.optString("label", ""), j.optBoolean("volatile", false), j.optLong("at", 0))
            }
            regime = m
        } catch (e: Exception) { regime = LinkedHashMap() }
        try {
            val o = JSONObject(prefs.getString("regime_diag_v1", "{}") ?: "{}")
            val keys = o.keys()
            val m = LinkedHashMap<String, RegimeDiag>()
            while (keys.hasNext()) {
                val k = keys.next()
                val j = o.optJSONObject(k) ?: continue
                m[k] = RegimeDiag(
                    j.optInt("requested", 0), j.optInt("received", 0), j.optInt("valid", 0),
                    j.optInt("minRequired", REGIME_MIN_CANDLES),
                    j.optString("provider", ""), j.optString("timeframe", ""),
                    j.optLong("updatedAt", 0), j.optString("error", ""),
                    j.optBoolean("indicatorOk", false))
            }
            regimeDiag = m
        } catch (e: Exception) { regimeDiag = LinkedHashMap() }
    }

    fun saveRegimeDiag() {
        try {
            val o = JSONObject()
            for ((k, v) in regimeDiag) {
                o.put(k, JSONObject().put("requested", v.requested).put("received", v.received)
                    .put("valid", v.valid).put("minRequired", v.minRequired)
                    .put("provider", v.provider).put("timeframe", v.timeframe)
                    .put("updatedAt", v.updatedAt).put("error", v.error)
                    .put("indicatorOk", v.indicatorOk))
            }
            prefs.edit().putString("regime_diag_v1", o.toString()).apply()
        } catch (e: Exception) { /* abaikan */ }
    }

    // ---------- V16 F10: state trailing stop paper (kunci stabil pair|dir|entryT).
    // Terpisah dari mesin strategi/backtest; posisi paper sendiri tetap di BotEngine. ----------
    private var trailCache: MutableMap<String, TrailState>? = null

    fun trailAll(): MutableMap<String, TrailState> {
        var t = trailCache
        if (t == null) {
            t = parseTrail(prefs.getString("trail_v1", null))
            trailCache = t
        }
        return t
    }

    fun saveTrail() {
        try { prefs.edit().putString("trail_v1", encodeTrail(trailAll())).apply() } catch (e: Exception) { /* abaikan */ }
    }

    fun clearTrail(key: String) {
        if (trailAll().remove(key) != null) saveTrail()
    }

    // ---------- V16 F10: pengaturan trailing (default MATI) + F8 digest (default MATI) ----------
    var trailOn: Boolean
        get() = prefs.getBoolean("trail_on", false)
        set(v) = prefs.edit().putBoolean("trail_on", v).apply()
    var trailLockPct: Double
        get() = sanitizeLockPct(prefs.getFloat("trail_lock", 50f).toDouble())
        set(v) = prefs.edit().putFloat("trail_lock", sanitizeLockPct(v).toFloat()).apply()

    // ---------- snapshot terapan + backtest terakhir ----------
    fun saveApplied(origin: String, p: BacktestParams, pairs: List<String>, status: String, valid: Boolean) {
        val allIds = FILTER_DEFS.map { it.id }
        val act = p.filters.filter { it.enabled }.map { it.name }
        val rr = if (p.slPercent > 0 && p.tpPercent > 0) "1:" + trimNum(p.tpPercent / p.slPercent) else "—"
        val o = JSONObject()
            .put("origin", origin).put("at", System.currentTimeMillis())
            .put("strategy", p.strategy).put("comboMode", p.combo?.mode ?: "")
            .put("combo", JSONArray(p.combo?.strategies ?: emptyList<String>()))
            .put("strategyMode", if (!p.combo?.strategies.isNullOrEmpty()) "Combined Strategy (${p.combo.mode.ifEmpty { "OR" }})" else "Single Strategy")
            .put("active", JSONArray(act)).put("disabled", JSONArray(allIds.filter { !act.contains(it) }))
            .put("rr", rr)
            .put("sl", if (p.useAtr) "ATR 14 × ${p.atrSlMult} / × ${p.atrTpMult}" else "${trimNum(p.slPercent * 100)}%")
            .put("tp", if (p.useAtr) "ATR 14 × ${p.atrTpMult}" else "${trimNum(p.tpPercent * 100)}%")
            .put("tpMode", tpModeLabel(p.tpMode))
            .put("timeframe", p.timeframe).put("pairs", JSONArray(pairs))
            .put("status", status).put("valid", valid)
        prefs.edit().putString("applied_cfg", o.toString()).apply()
    }

    fun appliedCfg(): AppliedCfg? {
        try {
            val s = prefs.getString("applied_cfg", null) ?: return null
            val o = JSONObject(s)
            fun ja(k: String): List<String> { val a = o.optJSONArray(k) ?: return emptyList(); return (0 until a.length()).map { a.getString(it) } }
            return AppliedCfg(o.getString("origin"), o.getLong("at"), o.getString("strategy"), o.optString("comboMode"),
                ja("combo"), o.optString("strategyMode"), ja("active"), ja("disabled"), o.optString("rr"),
                o.optString("sl"), o.optString("tp"), o.optString("timeframe"), ja("pairs"), o.optString("status"), o.optBoolean("valid"),
                o.optString("tpMode", "Single TP"))
        } catch (e: Exception) { return null }
    }

    fun saveLastBt(mode: String, pairs: List<String>, p: BacktestParams?, limitReq: Int, candlesProcessed: Int, trades: Int, engineMs: Long, totalMs: Long, status: String) {
        val o = JSONObject().put("mode", mode).put("pairs", JSONArray(pairs))
            .put("strategy", p?.strategy ?: "").put("timeframe", p?.timeframe ?: "")
            .put("limit", limitReq).put("from", p?.startDate ?: 0).put("to", p?.endDate ?: 0)
            .put("candles", candlesProcessed).put("trades", trades)
            .put("engineMs", engineMs).put("totalMs", totalMs).put("status", status)
            .put("at", System.currentTimeMillis())
        prefs.edit().putString("last_bt", o.toString()).apply()
    }

    fun lastBt(): LastBt? {
        try {
            val s = prefs.getString("last_bt", null) ?: return null
            val o = JSONObject(s)
            val a = o.optJSONArray("pairs") ?: JSONArray()
            return LastBt(o.getString("mode"), (0 until a.length()).map { a.getString(it) },
                o.optString("strategy"), o.optString("timeframe"), o.optInt("limit"), o.optLong("from"), o.optLong("to"),
                o.optInt("candles"), o.optInt("trades"), o.optLong("engineMs"), o.optLong("totalMs"), o.optString("status"), o.optLong("at"))
        } catch (e: Exception) { return null }
    }

    // ---------- Tahap 3: snapshot referensi Engine (parameter efektif penuh) ----------
    // PERSISTENSI (jujur): snapshot ini disimpan di prefs ("engine_ref_v1") sehingga
    // selamat restart. Sebaliknya sebagian besar global numerik (capital, riskPct,
    // leverage, fee/slip, sl/tp, tpMode, trail, timeframe, limit, tanggal) adalah
    // SESSION-ONLY — kembali ke bawaan saat aplikasi dimulai ulang. Persist yang
    // ada: pair, multipair, strategy, provider, engpairs, comboextra, custompairs,
    // watchlimit, batterySaver, filteron, trail_on/lock. Akibatnya setelah restart,
    // dialog drift wajar muncul (global live = bawaan vs snapshot terakhir).
    fun saveEngineRef(s: EngineSnapshot) {
        try {
            prefs.edit().putString("engine_ref_v1", encodeEngineSnapshot(s)).apply()
        } catch (e: Exception) { /* abaikan */ }
    }

    fun loadEngineRef(): EngineSnapshot? {
        return try {
            val raw = prefs.getString("engine_ref_v1", null) ?: return null
            decodeEngineSnapshot(raw)
        } catch (e: Exception) { null }
    }

    fun clearEngineRef() {
        try { prefs.edit().remove("engine_ref_v1").apply() } catch (e: Exception) { /* abaikan */ }
    }

    // ---------- V20 F2: ekspektasi drift + katalog arsip ----------
    fun saveDriftExpect(e: DriftExpect) {
        try { prefs.edit().putString("drift_expect", encodeDriftExpect(e)).apply() } catch (ex: Exception) { /* abaikan */ }
    }

    fun loadDriftExpect(): DriftExpect? =
        try { parseDriftExpect(prefs.getString("drift_expect", null)) } catch (e: Exception) { null }

    fun saveCatalog(list: List<com.aether.signal.premium.data.ArchiveInfo>) {
        try { prefs.edit().putString("hist_catalog", com.aether.signal.premium.data.encodeCatalog(list)).apply() } catch (e: Exception) { /* abaikan */ }
    }

    fun loadCatalog(): MutableList<com.aether.signal.premium.data.ArchiveInfo> =
        try { com.aether.signal.premium.data.parseCatalog(prefs.getString("hist_catalog", null)) } catch (e: Exception) { ArrayList() }

    // ---------- format ----------
    fun fmt(v: Double, d: Int = 2): String {
        if (!v.isFinite()) return "—"
        return String.format(Locale("id", "ID"), "%,.${d}f", v)
    }
    fun fmtD(v: Double): String = if (v.isNaN()) "—" else if (v.isInfinite() || v >= 999) "∞" else fmt(v)
    fun fmtMoney(v: Double): String {
        if (!v.isFinite()) return "—"
        return (if (v < 0) "-" else "") + "$" + fmt(abs(v))
    }
    fun fmtT(ts: Long): String {
        if (ts <= 0) return "—"
        val f = SimpleDateFormat("dd-MM HH:mm", Locale("id", "ID"))
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ts))
    }
    fun fmtDate(ts: Long): String {
        if (ts <= 0) return "—"
        val f = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID"))
        return f.format(Date(ts))
    }
    private fun trimNum(v: Double): String {
        if (!v.isFinite()) return "—"
        var s = String.format(Locale.US, "%.2f", v)
        s = s.trimEnd('0').trimEnd('.')
        return s
    }

    /** Validasi murni rentang tanggal backtest (I5/I6): 0 = tak dibatasi. */
    fun isDateRangeValid(from: Long, to: Long): Boolean =
        from <= 0 || to <= 0 || from <= to
}

// ---------- Tahap 3: adapter store global (Android) + serialisasi snapshot ----------
class AppGlobalsStore : EngineGlobalsStore {
    override fun capture(): EngineGlobalsData = App.captureGlobals()
    override fun restore(d: EngineGlobalsData) {
        App.strategy = d.strategy; App.saveStrategy()
        App.comboMode = d.comboMode
        App.comboExtra = LinkedHashSet(d.comboExtra); App.saveCombo()
        App.timeframe = d.timeframe
        App.provider = d.provider
        App.pair = d.pair; App.savePair()
        App.mode = d.mode
        App.multiSel = LinkedHashSet(d.multiSel); App.saveMulti()
        App.universe = d.universe
        App.engPairs = LinkedHashSet(d.engPairs); App.saveEngPairs()
        App.capital = d.capital
        App.riskPct = d.riskPct
        App.leverage = d.leverage
        App.feePct = d.feePct
        App.slipPct = d.slipPct
        App.maxHolding = d.maxHolding
        App.slPct = d.slPct
        App.tpPct = d.tpPct
        App.tp2Pct = d.tp2Pct
        App.tp3Pct = d.tp3Pct
        App.tpMode = d.tpMode
        App.useAtr = d.useAtr
        App.btTrailOn = d.btTrailOn
        App.btTrailDistPct = d.btTrailDistPct
        App.btTrailActPct = d.btTrailActPct
        App.filterOn = HashMap<String, Boolean>().also { m ->
            for ((k, v) in d.filterOn) m[k] = v
        }
        App.saveFilters()
        App.fVol = d.fVol; App.fAdx = d.fAdx; App.fRsiHi = d.fRsiHi; App.fRsiLo = d.fRsiLo
        App.fAtrMin = d.fAtrMin; App.fAtrMax = d.fAtrMax; App.fSession = d.fSession
        App.fCool = d.fCool; App.fSr = d.fSr
        App.limit = d.limit
        App.fromDate = d.fromDate; App.toDate = d.toDate
    }
}

private fun encodeFilterCfg(f: FilterCfg): JSONObject {
    val o = JSONObject()
    o.put("name", f.name).put("enabled", f.enabled)
    val pm = JSONObject()
    for ((k, v) in f.params) pm.put(k, v)
    o.put("params", pm)
    if (f.sessions != null) {
        val sa = JSONArray()
        for ((a, b) in f.sessions) sa.put(JSONObject().put("a", a).put("b", b))
        o.put("sessions", sa)
    }
    return o
}

private fun decodeFilterCfg(o: JSONObject): FilterCfg {
    val pm = o.optJSONObject("params") ?: JSONObject()
    val keys = pm.keys()
    val map = HashMap<String, Double>()
    while (keys.hasNext()) {
        val k = keys.next()
        map[k] = pm.optDouble(k, Double.NaN)
    }
    var sess: List<Pair<Int, Int>>? = null
    val sa = o.optJSONArray("sessions")
    if (sa != null) {
        sess = (0 until sa.length()).map {
            val so = sa.getJSONObject(it)
            so.getInt("a") to so.getInt("b")
        }
    }
    return FilterCfg(o.getString("name"), o.optBoolean("enabled", true), map, sess)
}

private fun encodeParams(p: BacktestParams): JSONObject {
    val o = JSONObject()
    o.put("asset", p.asset).put("timeframe", p.timeframe)
        .put("initialCapital", p.initialCapital).put("riskPerTrade", p.riskPerTrade)
        .put("leverage", p.leverage).put("feePercent", p.feePercent)
        .put("slippagePercent", p.slippagePercent).put("slPercent", p.slPercent)
        .put("tpPercent", p.tpPercent).put("maxHolding", p.maxHolding)
        .put("strategy", p.strategy)
    val sp = JSONObject()
    for ((k, v) in p.strategyParams) sp.put(k, v)
    o.put("strategyParams", sp)
    if (p.combo != null) {
        o.put("combo", JSONObject().put("mode", p.combo.mode)
            .put("strategies", JSONArray(p.combo.strategies)))
    }
    o.put("startDate", p.startDate).put("endDate", p.endDate).put("useAtr", p.useAtr)
        .put("atrSlMult", p.atrSlMult).put("atrTpMult", p.atrTpMult)
        .put("tpMode", p.tpMode).put("tp2Mult", p.tp2Mult).put("tp3Mult", p.tp3Mult)
        .put("trailOn", p.trailOn).put("trailDistPct", p.trailDistPct)
        .put("trailActPct", p.trailActPct)
    val fa = JSONArray()
    for (f in p.filters) fa.put(encodeFilterCfg(f))
    o.put("filters", fa)
    return o
}

private fun decodeParams(o: JSONObject): BacktestParams {
    for (k in listOf("asset", "timeframe", "strategy")) {
        if (!o.has(k)) throw IllegalArgumentException("params kehilangan $k")
    }
    val spo = o.optJSONObject("strategyParams") ?: JSONObject()
    val sk = spo.keys()
    val spm = HashMap<String, Double>()
    while (sk.hasNext()) {
        val k = sk.next()
        spm[k] = spo.optDouble(k, Double.NaN)
    }
    var combo: Combo? = null
    val co = o.optJSONObject("combo")
    if (co != null) {
        val sa = co.optJSONArray("strategies") ?: JSONArray()
        combo = Combo(co.optString("mode", ""), (0 until sa.length()).map { sa.getString(it) })
    }
    val fa = o.optJSONArray("filters") ?: JSONArray()
    val filters = (0 until fa.length()).map { decodeFilterCfg(fa.getJSONObject(it)) }
    return BacktestParams(
        asset = o.getString("asset"), timeframe = o.getString("timeframe"),
        initialCapital = o.optDouble("initialCapital", Double.NaN),
        riskPerTrade = o.optDouble("riskPerTrade", Double.NaN),
        leverage = o.optInt("leverage", -1),
        feePercent = o.optDouble("feePercent", Double.NaN),
        slippagePercent = o.optDouble("slippagePercent", Double.NaN),
        slPercent = o.optDouble("slPercent", Double.NaN),
        tpPercent = o.optDouble("tpPercent", Double.NaN),
        maxHolding = o.optInt("maxHolding", -1),
        strategy = o.getString("strategy"), strategyParams = spm, combo = combo,
        startDate = o.optLong("startDate", 0L), endDate = o.optLong("endDate", 0L),
        useAtr = o.optBoolean("useAtr", false),
        atrSlMult = o.optDouble("atrSlMult", Double.NaN),
        atrTpMult = o.optDouble("atrTpMult", Double.NaN),
        tpMode = o.optInt("tpMode", -1),
        tp2Mult = o.optDouble("tp2Mult", Double.NaN),
        tp3Mult = o.optDouble("tp3Mult", Double.NaN),
        trailOn = o.optBoolean("trailOn", false),
        trailDistPct = o.optDouble("trailDistPct", Double.NaN),
        trailActPct = o.optDouble("trailActPct", Double.NaN),
        filters = filters
    )
}

private fun encodeGlobals(g: EngineGlobalsData): JSONObject {
    val o = JSONObject()
    o.put("strategy", g.strategy).put("comboMode", g.comboMode)
        .put("comboExtra", JSONArray(g.comboExtra))
        .put("timeframe", g.timeframe).put("provider", g.provider)
        .put("pair", g.pair).put("mode", g.mode)
        .put("multiSel", JSONArray(g.multiSel)).put("universe", JSONArray(g.universe))
        .put("engPairs", JSONArray(g.engPairs))
        .put("capital", g.capital).put("riskPct", g.riskPct).put("leverage", g.leverage)
        .put("feePct", g.feePct).put("slipPct", g.slipPct).put("maxHolding", g.maxHolding)
        .put("slPct", g.slPct).put("tpPct", g.tpPct)
        .put("tp2Pct", g.tp2Pct).put("tp3Pct", g.tp3Pct).put("tpMode", g.tpMode)
        .put("useAtr", g.useAtr).put("atrSlMult", g.atrSlMult).put("atrTpMult", g.atrTpMult)
        .put("btTrailOn", g.btTrailOn)
        .put("btTrailDistPct", g.btTrailDistPct).put("btTrailActPct", g.btTrailActPct)
    val fo = JSONObject()
    for ((k, v) in g.filterOn) fo.put(k, v)
    o.put("filterOn", fo)
    o.put("fVol", g.fVol).put("fAdx", g.fAdx).put("fRsiHi", g.fRsiHi).put("fRsiLo", g.fRsiLo)
        .put("fAtrMin", g.fAtrMin).put("fAtrMax", g.fAtrMax).put("fSession", g.fSession)
        .put("fCool", g.fCool).put("fSr", g.fSr)
        .put("limit", g.limit).put("fromDate", g.fromDate).put("toDate", g.toDate)
    return o
}

private fun decodeGlobals(o: JSONObject): EngineGlobalsData {
    fun ja(k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }
    val foo = o.optJSONObject("filterOn") ?: JSONObject()
    val fk = foo.keys()
    val fmap = HashMap<String, Boolean>()
    while (fk.hasNext()) {
        val k = fk.next()
        fmap[k] = foo.optBoolean(k, false)
    }
    for (k in listOf("strategy", "timeframe", "provider", "pair", "mode")) {
        if (!o.has(k)) throw IllegalArgumentException("globals kehilangan $k")
    }
    return EngineGlobalsData(
        strategy = o.getString("strategy"), comboMode = o.optString("comboMode", ""),
        comboExtra = ja("comboExtra"), timeframe = o.getString("timeframe"),
        provider = o.getString("provider"), pair = o.getString("pair"),
        mode = o.getString("mode"), multiSel = ja("multiSel"),
        universe = ja("universe"), engPairs = ja("engPairs"),
        capital = o.optDouble("capital", Double.NaN), riskPct = o.optDouble("riskPct", Double.NaN),
        leverage = o.optInt("leverage", -1), feePct = o.optDouble("feePct", Double.NaN),
        slipPct = o.optDouble("slipPct", Double.NaN), maxHolding = o.optInt("maxHolding", -1),
        slPct = o.optDouble("slPct", Double.NaN), tpPct = o.optDouble("tpPct", Double.NaN),
        tp2Pct = o.optDouble("tp2Pct", Double.NaN), tp3Pct = o.optDouble("tp3Pct", Double.NaN),
        tpMode = o.optInt("tpMode", -1), useAtr = o.optBoolean("useAtr", false),
        atrSlMult = o.optDouble("atrSlMult", Double.NaN),
        atrTpMult = o.optDouble("atrTpMult", Double.NaN),
        btTrailOn = o.optBoolean("btTrailOn", false),
        btTrailDistPct = o.optDouble("btTrailDistPct", Double.NaN),
        btTrailActPct = o.optDouble("btTrailActPct", Double.NaN),
        filterOn = fmap, fVol = o.optDouble("fVol", Double.NaN),
        fAdx = o.optDouble("fAdx", Double.NaN), fRsiHi = o.optDouble("fRsiHi", Double.NaN),
        fRsiLo = o.optDouble("fRsiLo", Double.NaN),
        fAtrMin = o.optDouble("fAtrMin", Double.NaN),
        fAtrMax = o.optDouble("fAtrMax", Double.NaN),
        fSession = o.optString("fSession", "all"),
        fCool = o.optDouble("fCool", Double.NaN), fSr = o.optDouble("fSr", Double.NaN),
        limit = o.optInt("limit", -1),
        fromDate = o.optLong("fromDate", 0L), toDate = o.optLong("toDate", 0L)
    )
}

private fun encodeEngineSnapshot(s: EngineSnapshot): String {
    val o = JSONObject()
    o.put("v", 1)
    o.put("globals", encodeGlobals(s.globals))
    o.put("params", encodeParams(s.params))
    o.put("pairs", JSONArray(s.pairs))
    o.put("provider", s.provider).put("limit", s.limit).put("mode", s.mode)
    o.put("at", s.at).put("status", s.status).put("valid", s.valid)
    o.put("origin", s.origin)
    return o.toString()
}

private fun decodeEngineSnapshot(raw: String): EngineSnapshot? {
    try {
        val o = JSONObject(raw)
        if (o.optInt("v", 0) != 1) return null
        val go = o.optJSONObject("globals") ?: return null
        val po = o.optJSONObject("params") ?: return null
        val pa = o.optJSONArray("pairs") ?: return null
        val pairs = (0 until pa.length()).map { pa.getString(it) }
        if (pairs.isEmpty()) return null
        return EngineSnapshot(
            globals = decodeGlobals(go), params = decodeParams(po), pairs = pairs,
            provider = o.optString("provider", ""), limit = o.optInt("limit", -1),
            mode = o.optString("mode", ""), at = o.optLong("at", 0L),
            status = o.optString("status", ""), valid = o.optBoolean("valid", false),
            origin = o.optString("origin", "")
        )
    } catch (e: Exception) {
        return null
    }
}
