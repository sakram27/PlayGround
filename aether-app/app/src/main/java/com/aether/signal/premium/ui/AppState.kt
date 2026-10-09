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
    val timeframe: String, val pairs: List<String>, val status: String, val valid: Boolean
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
    var slPct: Double = 1.5
    var tpPct: Double = 3.0
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
    var hist: ArrayList<Trade> = ArrayList()
    var candles: List<Candle> = emptyList()
    var params: BacktestParams? = null
    var result: BacktestResult? = null
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
        loadSignals(); loadHist(); loadJournal()
    }

    fun savePair() = prefs.edit().putString("pair", pair).apply()    fun saveMulti() = prefs.edit().putStringSet("multipair", LinkedHashSet(multiSel)).apply()
    fun saveStrategy() = prefs.edit().putString("strategy", strategy).apply()
    fun saveEngPairs() = prefs.edit().putStringSet("engpairs", LinkedHashSet(engPairs)).apply()
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

    fun buildParams(asset: String): BacktestParams {
        val extra = comboExtra.toList()
        val combo = if (comboMode.isNotEmpty()) {
            val set = LinkedHashSet<String>()
            set.add(strategy); set.addAll(extra)
            if (set.size < 2) throw IllegalArgumentException("Combo butuh minimal 2 strategi.")
            Combo(comboMode, set.toList().take(5))
        } else null
        return BacktestParams(
            asset = asset, timeframe = timeframe, initialCapital = capital,
            riskPerTrade = riskPct / 100, leverage = leverage,
            feePercent = feePct / 100, slippagePercent = slipPct / 100,
            slPercent = slPct / 100, tpPercent = tpPct / 100, maxHolding = maxHolding,
            strategy = strategy, combo = combo, filters = activeFilterCfgs(),
            startDate = fromDate, endDate = toDate, useAtr = useAtr
        )
    }

    // ---------- signals/history persist ----------
    fun pushSignal(s: Sig) {
        if (signals.any { it.pair == s.pair && it.tf == s.tf && it.t == s.t && it.dir == s.dir }) return
        signals.add(0, s)
        if (signals.size > 200) signals = ArrayList(signals.take(200))
        saveSignals()
    }

    private fun saveSignals() {
        val a = JSONArray()
        for (s in signals) a.put(JSONObject().put("pair", s.pair).put("tf", s.tf).put("dir", s.dir)
            .put("price", s.price).put("sl", s.sl).put("tp", s.tp).put("t", s.t).put("src", s.src).put("id", s.id))
        prefs.edit().putString("signals", a.toString()).apply()
    }

    private fun loadSignals() {
        try {
            val a = JSONArray(prefs.getString("signals", "[]"))
            signals = ArrayList((0 until a.length()).map { k ->
                val o = a.getJSONObject(k)
                Sig(o.getString("pair"), o.optString("tf"), o.getString("dir"), o.getDouble("price"),
                    o.getDouble("sl"), o.getDouble("tp"), o.getLong("t"), o.optString("src"), o.getString("id"))
            })
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
                o.optString("sl"), o.optString("tp"), o.optString("timeframe"), ja("pairs"), o.optString("status"), o.optBoolean("valid"))
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

    // ---------- format ----------
    fun fmt(v: Double, d: Int = 2): String {
        if (!v.isFinite()) return "—"
        return String.format(Locale("id", "ID"), "%,.${d}f", v)
    }
    fun fmtD(v: Double): String = if (!v.isFinite()) "—" else if (v >= 999) "∞" else fmt(v)
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
