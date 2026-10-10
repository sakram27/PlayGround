package com.aether.signal.premium.ui

import android.content.Intent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.aether.signal.premium.ai.Sig
import com.aether.signal.premium.data.*
import com.aether.signal.premium.engine.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class BacktestActivity : BaseActivity(R.id.nav_lab) {
    override val contentLayout = R.layout.activity_backtest
    override val showBack = true
    private var cancelled = false
    // V22 monitor: polling ringan 400ms, berhenti di onDestroy (anti-bocor).
    private var monitorOpen = false
    private var lastMonSeq: Long = -1
    private val monHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val monPoll = object : Runnable {
        override fun run() {
            try { paintMonitor() } catch (e: Exception) { /* abaikan */ }
            monHandler.postDelayed(this, 400)
        }
    }
    private var tradePage = 0
    private var tradeRows: List<Trade> = emptyList()
    private var staged: LinkedHashSet<String> = LinkedHashSet()

    override fun build() {
        setBar("Backtest Console", "Kuantitatif · engine terverifikasi")
        wireStatic()
        // P3: dibuka dari Market → simbol+TF mengikuti pilihan (sudah divalidasi
        // pengirim; verifikasi ulang di sini). Tampilkan ringkasan, TANPA autorun.
        if (intent.getBooleanExtra("fromMarket", false)) {
            val a = intent.getStringExtra("asset") ?: ""
            val t = intent.getStringExtra("timeframe") ?: ""
            if (a.isNotEmpty()) { App.pair = a; App.savePair() }
            if (BACKTEST_TIMEFRAMES.contains(t)) App.timeframe = t
            refreshAll()
            setStatus("Dari Market: ${App.pair} · ${App.timeframe} · ${App.strategy} — periksa konfigurasi lalu tekan Jalankan.", false)
        } else refreshAll()
    }

    override fun onDestroy() {
        try { monHandler.removeCallbacks(monPoll) } catch (e: Exception) { /* abaikan */ }
        super.onDestroy()
    }

    // ---------- wiring statis (sekali) ----------
    private fun wireStatic() {
        toggle(R.id.segMode, listOf(R.id.segSingle, R.id.segMulti), if (App.mode == "multi") 1 else 0) {
            App.mode = if (it == 1) "multi" else "single"; refreshAll()
        }
        findViewById<View>(R.id.btnRun).setOnClickListener { if (App.mode == "multi") doMulti() else doSingle() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { cancelled = true }
        toggle(R.id.segPreset, listOf(R.id.segPresetDefault, R.id.segPresetCustom), if (App.presetCustom) 1 else 0) {
            App.presetCustom = it == 1; refreshAll()
        }
        toggle(R.id.segCombo, listOf(R.id.segComboSingle, R.id.segComboOr, R.id.segComboAnd, R.id.segComboMaj), comboIdx()) {
            App.comboMode = listOf("", "OR", "AND", "MAJORITY")[it]; refreshAll()
        }
        val lims = listOf(200, 500, 1000)
        val limIds = listOf(R.id.segLimit200, R.id.segLimit500, R.id.segLimit1000)
        val segL = findViewById<MaterialButtonToggleGroup>(R.id.segLimit)
        segL.check(limIds[lims.indexOf(App.limit).coerceAtLeast(0)])
        segL.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val i = limIds.indexOf(id)
                if (i >= 0) App.limit = lims[i]
            }
        }
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inFrom).apply {
            setText(if (App.fromDate > 0) dateDisplay(App.fromDate) else "")
            setOnClickListener { openDatePicker(true) }
        }
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inTo).apply {
            setText(if (App.toDate > 0) dateDisplay(App.toDate) else "")
            setOnClickListener { openDatePicker(false) }
        }
        findViewById<View>(R.id.btnDateClear).setOnClickListener {
            App.fromDate = 0; App.toDate = 0
            paintDates()
            snack(this, "Rentang tanggal dibersihkan (memakai seluruh data).")
        }
        findViewById<TextView>(R.id.csvStatus).text =
            if (App.csv != null) "CSV aktif (${App.csv!!.size}c) — single-pair saja." else "CSV tidak aktif."
        findViewById<View>(R.id.btnCsv).setOnClickListener { pickCsv() }
        findViewById<View>(R.id.btnCsvClear).setOnClickListener { App.csv = null; snack(this, "CSV dihapus."); paintQuick() }
        auto(R.id.spStrategy, STRATEGIES.keys.map { STRATEGIES[it]!!.first }, STRATEGIES.keys.indexOf(App.strategy).coerceAtLeast(0)) {
            App.strategy = STRATEGIES.keys.toList()[it]; App.saveStrategy(); refreshAll()
        }
        auto(R.id.spProv, listOf("binance", "bybit", "yahoo", "demo"), listOf("binance", "bybit", "yahoo", "demo").indexOf(App.provider).coerceAtLeast(0)) {
            App.provider = listOf("binance", "bybit", "yahoo", "demo")[it]; App.universe = emptyList(); refreshAll()
        }
        auto(R.id.spTf, listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w"),
            listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w").indexOf(App.timeframe).coerceAtLeast(0)) {
            App.timeframe = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w")[it]; refreshAll()
        }
        segLabels(R.id.segMode, listOf("Single Pair", "Multi Pair"))
        // candle segmented dibuat dinamis (butuh recreate aman) → pakai 3 tombol biasa:
        findViewById<View>(R.id.btnPair).setOnClickListener { pickSinglePair() }
        findViewById<View>(R.id.btnSelect).setOnClickListener { openMultiSheet() }
        findViewById<View>(R.id.btnReload).setOnClickListener { reloadUniverse { refreshAll() } }
        findViewById<View>(R.id.presetLoose).setOnClickListener { applyPreset(listOf("trend", "volume")); paintFilters() }
        findViewById<View>(R.id.presetBal).setOnClickListener { applyPreset(listOf("trend", "htf", "volume", "adx", "rsi", "cooldown")); paintFilters() }
        findViewById<View>(R.id.presetStrict).setOnClickListener { applyPreset(FILTER_DEFS.map { it.id }); paintFilters() }
        findViewById<View>(R.id.btnFilterAll).setOnClickListener { applyPreset(FILTER_DEFS.map { it.id }); paintFilters() }
        findViewById<View>(R.id.btnFilterClear).setOnClickListener { applyPreset(emptyList()); paintFilters() }
        findViewById<View>(R.id.tradePrev).setOnClickListener { tradePage = maxOf(0, tradePage - 1); paintTrades() }
        findViewById<View>(R.id.tradeNext).setOnClickListener { tradePage++; paintTrades() }
        findViewById<View>(R.id.tradeCsv).setOnClickListener { exportTrades() }
        findViewById<View>(R.id.btnAudit).setOnClickListener { runAudit() }
        findViewById<View>(R.id.btnDiagToggle).setOnClickListener { toggleDiag() }
        findViewById<View>(R.id.btnMonToggle).setOnClickListener { toggleMonitor() }
        findViewById<View>(R.id.btnMonClearLog).setOnClickListener {
            MonitorBus.clearLogView()
            lastMonSeq = -1
            try { paintMonitor() } catch (e: Exception) { /* abaikan */ }
        }
        monHandler.post(monPoll)
        findViewById<View>(R.id.btnReplay).setOnClickListener { openReplay() }
        findViewById<View>(R.id.btnMonteCarlo).setOnClickListener { openMonteCarlo() }
        findViewById<View>(R.id.btnAblation).setOnClickListener { openAblation() }
        findViewById<View>(R.id.btnConsensus).setOnClickListener { openConsensus() }
        findViewById<View>(R.id.btnWalkFwd).setOnClickListener { openWalkForward() }
        findViewById<View>(R.id.btnPresetConservative).setOnClickListener { confirmPreset("conservative") }
        findViewById<View>(R.id.btnPresetBalanced).setOnClickListener { confirmPreset("balanced") }
        findViewById<View>(R.id.btnPresetAggressive).setOnClickListener { confirmPreset("aggressive") }
        bindNum(R.id.inCapital, App.capital.toString()) { App.capital = it.toDoubleOrNull() ?: 1000.0; paintRiskPreset() }
        bindNum(R.id.inRisk, App.riskPct.toString()) { App.riskPct = it.toDoubleOrNull() ?: 1.0; paintRiskPreset() }
        bindNum(R.id.inSl, App.slPct.toString()) { App.slPct = it.toDoubleOrNull() ?: 1.5; paintRiskPreset() }
        bindNum(R.id.inTp, App.tpPct.toString()) { App.tpPct = it.toDoubleOrNull() ?: 3.0; paintRiskPreset() }
        findViewById<SwitchMaterial>(R.id.swAtr).apply {
            isChecked = App.useAtr
            setOnCheckedChangeListener { _, on -> App.useAtr = on }
        }
        findViewById<View>(R.id.btnRiskReset).setOnClickListener { resetRisk(); paintConfig() }
        setupCharts()
    }

    private fun toggle(id: Int, ids: List<Int>, sel: Int, onPick: (Int) -> Unit) {
        val g = findViewById<MaterialButtonToggleGroup>(id)
        g.check(ids[sel.coerceIn(ids.indices)])
        g.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                val i = ids.indexOf(checkedId)
                if (i >= 0) onPick(i)
            }
        }
    }

    private fun segLabels(id: Int, labels: List<String>) {
        val g = findViewById<MaterialButtonToggleGroup>(id)
        for (i in 0 until minOf(g.childCount, labels.size)) {
            (g.getChildAt(i) as? android.widget.Button)?.text = labels[i]
        }
    }

    private fun auto(id: Int, opts: List<String>, sel: Int, onPick: (Int) -> Unit) {
        findViewById<MaterialAutoCompleteTextView>(id).apply {
            setSimpleItems(opts.toTypedArray())
            setText(opts[sel.coerceIn(opts.indices)], false)
            // Penjamin sentuhan: ketuk selalu membuka daftar + ikon panah pada layout.
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, pos, _ -> onPick(pos) }
        }
    }

    private fun bindNum(id: Int, value: String, onEdit: (String) -> Unit) {
        findViewById<TextInputEditText>(id).apply {
            setText(value)
            setOnFocusChangeListener { _, has -> if (!has) onEdit(text.toString()) }
        }
    }

    private fun comboIdx() = when (App.comboMode) { "OR" -> 1; "AND" -> 2; "MAJORITY" -> 3; else -> 0 }

    /** Kembalikan parameter risiko & biaya ke bawaan (tanpa menyentuh strategi/filter/pair). */
    private fun resetRisk() {
        App.capital = 1000.0; App.riskPct = 1.0; App.leverage = 1
        App.feePct = 0.05; App.slipPct = 0.02; App.maxHolding = 100
        App.slPct = 1.5; App.tpPct = 3.0; App.useAtr = false
        snack(this, "Parameter risiko dikembalikan ke bawaan.")
    }

    /** FITUR 2: preset risiko. Ringkasan nilai ditampilkan DULU; baru diterapkan bila disetujui.
     *  Tidak ada perubahan diam-diam saat halaman dibuka. */
    private fun confirmPreset(id: String) {
        val p = presetById(id) ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Terapkan preset ${p.name}?")
            .setMessage(p.tagline + "\n\nNilai: " + riskPresetSummary(p) +
                "\n\nMengubah risiko juga mengubah hasil backtest. Anda tetap dapat menyunting manual setelahnya.")
            .setPositiveButton("Terapkan") { _, _ -> applyRiskPreset(p) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyRiskPreset(p: RiskPreset) {
        App.riskPct = p.riskPct; App.leverage = p.leverage; App.maxHolding = p.maxHolding
        App.slPct = p.slPct; App.tpPct = p.tpPct
        paintConfig()
        snack(this, "Preset ${p.name} diterapkan. Tekan Jalankan untuk menghitung ulang.")
    }

    /** Status preset jujur: "Kustom" bila nilai aktif tak sama dengan preset mana pun. */
    private fun paintRiskPreset() {
        try {
            val name = riskPresetLabel(App.riskPct, App.leverage, App.maxHolding, App.slPct, App.tpPct)
            val detail = "Risiko ${App.riskPct}% · Leverage ${App.leverage}× · Maks tahan ${App.maxHolding} candle · SL ${App.slPct}% · TP ${App.tpPct}%"
            findViewById<TextView>(R.id.riskPresetStatus).text = "Preset aktif: $name" +
                (if (name == "Kustom") " (nilai manual tidak sama dengan preset mana pun)." else ".") +
                "\n$detail"
            // V13: pratinjau nominal — hanya membaca nilai, tak mengubah konfigurasi.
            findViewById<TextView>(R.id.riskPreview).text = riskPreviewText(App.capital, App.riskPct)
        } catch (e: Exception) { /* layout belum siap */ }
    }

    // ---------- V22 BACKTEST PROCESS MONITOR (poll snapshot MonitorBus) ----------
    private fun toggleMonitor() {
        monitorOpen = !monitorOpen
        try {
            findViewById<View>(R.id.monDetail).visibility = if (monitorOpen) View.VISIBLE else View.GONE
            (findViewById<View>(R.id.btnMonToggle) as? android.widget.Button)?.text =
                if (monitorOpen) "▴" else "▾"
            lastMonSeq = -1
            paintMonitor()
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun paintMonitor() {
        val snap: MonitorSnapshot
        try {
            snap = MonitorBus.snapshot()
        } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        val elapsed = if (snap.startedAt > 0) {
            fmtElapsed((if (snap.finishedAt > 0) snap.finishedAt else now) - snap.startedAt)
        } else "—"
        val pct = if (snap.progDone != null && snap.progTotal != null) {
            progressPct(snap.progDone, snap.progTotal)?.let { " · $it%" } ?: ""
        } else ""
        val pairs = if (snap.pairsTotal > 1) " · pair ${snap.pairsDone}/${snap.pairsTotal}" else ""
        try {
            val st = findViewById<TextView>(R.id.monStatus)
            st.text = phaseLabel(snap.phase)
            st.setTextColor((when (snap.phase) {
                MonPhase.COMPLETED -> 0xFF0ECB81
                MonPhase.FAILED -> 0xFFF6465D
                MonPhase.CANCELLED -> 0xFFFFB800
                MonPhase.IDLE -> 0xFF8B95A5
                else -> 0xFF4C8DFF
            }).toInt())
            findViewById<TextView>(R.id.monSummary).text = when (snap.phase) {
                MonPhase.IDLE -> "Menunggu Backtest."
                else -> "${snap.phaseDetail.ifEmpty { phaseLabel(snap.phase) }}$pct$pairs · $elapsed"
            }
        } catch (e: Exception) { return }
        if (!monitorOpen) return
        if (snap.eventSeq == lastMonSeq) {
            // Waktu berjalan tetap diperbarui via monSummary di atas.
            return
        }
        lastMonSeq = snap.eventSeq
        try {
            val rows = ArrayList<Pair<String, String>>()
            if (snap.runId.isNotEmpty()) {
                rows.add("Run" to snap.runId)
                if (snap.asset.isNotEmpty()) rows.add("Aset" to "${snap.asset} · ${snap.timeframe}")
                if (snap.reqFrom > 0 || snap.reqTo > 0) rows.add("Periode" to
                    "${if (snap.reqFrom > 0) fmtUtc(snap.reqFrom) else "awal"} → " +
                        if (snap.reqTo > 0) fmtUtc(snap.reqTo) else "kini")
                if (snap.source.isNotEmpty()) rows.add("Sumber" to snap.source)
                if (snap.received > 0 || snap.validated > 0)
                    rows.add("Candle" to "terima ${snap.received} · valid ${snap.validated}" +
                        (snap.needDownload?.let { if (it) " · perlu unduh" else " · lokal cukup" } ?: ""))
                rows.add("Sinyal" to "mentah ${snap.signalsRaw} · tersaring ${snap.filteredOut}")
                rows.add("Ledger" to "${snap.trades} transaksi")
                if (snap.error.isNotEmpty()) rows.add("Error" to snap.error.take(120))
                rows.add("Update" to fmtClockS(now))
            }
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.monFacts).apply {
                vertical(this@BacktestActivity)
                adapter = KvAdapter(rows)
            }
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.monLog).apply {
                vertical(this@BacktestActivity)
                adapter = LogAdapter(snap.log) { t -> fmtClockS(t) }
            }
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun toggleDiag() {
        val box = findViewById<View>(R.id.diagBox)
        val open = box.visibility == View.VISIBLE
        box.visibility = if (open) View.GONE else View.VISIBLE
        (findViewById<View>(R.id.btnDiagToggle) as? android.widget.Button)?.text =
            if (open) "Buka diagnostik & alasan filter ▾" else "Tutup diagnostik & alasan filter ▴"
    }

    // ---------- refresh tampilan ----------
    private fun refreshAll() {
        paintQuick(); paintConfig(); paintFilters(); paintChart(); paintResult()
    }

    private fun setStatus(m: String, err: Boolean = false) {
        runOnUiThread {
            findViewById<TextView>(R.id.status).apply {
                text = m
                setTextColor(if (err) 0xFFF6465D.toInt() else 0xFF8B95A5.toInt())
            }
        }
    }

    private fun paintConfig() {
        findViewById<View>(R.id.btnPair).apply {
            (this as? android.widget.Button)?.text = App.pair + "  ▾"
        }
        val single = App.mode == "single"
        findViewById<View>(R.id.singleBox).visibility = if (single) View.VISIBLE else View.GONE
        findViewById<View>(R.id.multiBox).visibility = if (single) View.GONE else View.VISIBLE
        findViewById<View>(R.id.comboBox).visibility = if (App.presetCustom) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.multiCount).text =
            if (App.universe.isEmpty()) "Universe belum dimuat — tekan Reload."
            else "${App.multiSel.size} of ${App.universe.size} selected"
        val chips = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chips)
        chips.removeAllViews()
        for (s in App.multiSel) {
            val c = Chip(this).apply {
                text = s
                isCloseIconVisible = true
                try {
                    chipIcon = PairIcons.iconFor(this@BacktestActivity, s)
                    chipIconSize = 20 * resources.displayMetrics.density
                } catch (e: Exception) { /* ikon opsional */ }
                setOnCloseIconClickListener { App.multiSel.remove(s); App.saveMulti(); paintConfig() }
            }
            chips.addView(c)
        }
        // combo list: ListView native multi-pilih + Pilih/Hapus Semua + count
        val cl = findViewById<ListView>(R.id.comboList)
        cl.fixScrollConflict()
        val comboIds = strategyList().filter { it.id != App.strategy }.map { it.id }
        val comboLabels = comboIds.map { id ->
            val m = strategyList().find { it.id == id }!!
            "${m.name} (${m.id})"
        }
        bindMultiChoice(cl, comboLabels,
            BooleanArray(comboIds.size) { App.comboExtra.contains(comboIds[it]) }) { pos, on ->
            val id = comboIds[pos]
            if (on) App.comboExtra.add(id) else App.comboExtra.remove(id)
            App.saveCombo()
            paintComboCount()
        }
        paintComboCount()
        findViewById<View>(R.id.btnComboAll).setOnClickListener {
            App.comboExtra.addAll(comboIds); App.saveCombo(); paintConfig()
        }
        findViewById<View>(R.id.btnComboClear).setOnClickListener {
            App.comboExtra.clear(); App.saveCombo(); paintConfig()
        }
        findViewById<TextInputEditText>(R.id.inCapital).setText(App.capital.toString())
        findViewById<TextInputEditText>(R.id.inRisk).setText(App.riskPct.toString())
        findViewById<TextInputEditText>(R.id.inSl).setText(App.slPct.toString())
        findViewById<TextInputEditText>(R.id.inTp).setText(App.tpPct.toString())
        paintRiskPreset()
    }

    private fun paintComboCount() {
        findViewById<TextView>(R.id.comboCount).text = "${App.comboExtra.size} dipilih"
    }

    private var filterRefresh: (() -> Unit)? = null

    private fun paintFilters() {
        val rows = ArrayList<CheckRow>()
        for ((g, ids) in listOf(
            "Structure" to listOf("ms", "sr"), "Liquidity" to listOf("liq"),
            "Order Flow" to listOf("cooldown", "dup"), "Trend" to listOf("trend", "ema", "htf"),
            "Momentum" to listOf("adx", "rsi"), "Volume" to listOf("volume", "atr_vol", "min_vol", "max_vol"),
            "Risk" to listOf("session")
        )) {
            rows.add(CheckRow.Header(g))
            for (id in ids) rows.add(CheckRow.Item(id, FILTER_DEFS.find { it.id == id }?.name ?: id))
        }
        val list = findViewById<ListView>(R.id.filterList)
        list.fixScrollConflict()
        filterRefresh = bindGroupedMulti(list, rows, { App.filterOn[it] == true }) { key, on ->
            App.filterOn[key] = on
            App.saveFilters()
            paintFilterCount()
        }
        paintFilterCount()
    }

    private fun paintFilterCount() {
        val n = App.filterOn.count { it.value }
        findViewById<TextView>(R.id.filterHead).text = "FILTER · $n aktif"
        findViewById<TextView>(R.id.filterCount).text = "$n dipilih"
    }

    private fun applyPreset(ids: List<String>) {
        for (id in FILTER_DEFS.map { it.id }) App.filterOn[id] = ids.contains(id)
        App.saveFilters()
    }

    // ---------- pair pickers ----------
    private fun pickSinglePair() {
        snack(this, "Memuat daftar pair…")
        runBg {
            val pairs = try {
                if (App.provider == "yahoo") YAHOO_UNIVERSE.keys.toList() else topPairs(App.provider, 50)
            } catch (e: Exception) { listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT") }
            runOnUiThread {
                val sh = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.SheetTheme)
                val v = layoutInflater.inflate(R.layout.sheet_pair, null)
                v.findViewById<TextView>(R.id.sym).text = "Pilih Pair"
                v.findViewById<TextView>(R.id.meta).text = "${pairs.size} tersedia · ${App.provider} · ketuk = pakai"
                v.findViewById<View>(R.id.btnBacktest).visibility = View.GONE
                v.findViewById<View>(R.id.btnChart).visibility = View.GONE
                val list = ListView(this)
                list.choiceMode = ListView.CHOICE_MODE_SINGLE
                (v as android.view.ViewGroup).addView(list)
                val shown = pairs.take(60)
                list.adapter = IconCheckAdapter(this, shown, true)
                list.setItemChecked(shown.indexOf(App.pair).coerceAtLeast(0), true)
                (list.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                    App.pair = shown[pos]; App.savePair(); sh.dismiss(); paintConfig(); paintQuick()
                }
                sh.setContentView(v)
                sh.show()
            }
        }
    }

    private fun reloadUniverse(after: (() -> Unit)?) {
        snack(this, "Memuat universe…")
        runBg {
            try {
                App.universe = if (App.provider == "yahoo") YAHOO_UNIVERSE.keys.toList()
                else if (App.provider == "demo") listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT")
                else topPairs(App.provider, 50)
            } catch (e: Exception) { runOnUiThread { snack(this, "Gagal: ${e.message}") }; return@runBg }
            runOnUiThread { after?.invoke(); paintConfig() }
        }
    }

    private fun openMultiSheet() {
        val ensure = { done: () -> Unit -> if (App.universe.isEmpty()) reloadUniverse(done) else done() }
        ensure {
            staged = LinkedHashSet(App.multiSel)
            val sh = BottomSheetDialog(this, R.style.SheetTheme)
            val root = layoutInflater.inflate(R.layout.sheet_multi, null)
            val search = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
            val list = root.findViewById<ListView>(R.id.list)
            list.fixScrollConflict()
            list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            val cnt = root.findViewById<TextView>(R.id.count)
            fun render() {
                val q = search.text.toString().uppercase()
                val vis = App.universe.filter { q.isEmpty() || it.contains(q) }
                list.adapter = IconCheckAdapter(this, vis, false)
                vis.forEachIndexed { i, s -> list.setItemChecked(i, staged.contains(s)) }
                // Sinkron visual: ListView tak me-rebind baris saat setItemChecked,
                // dan root baris bukan Checkable — paksa gambar ulang.
                (list.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                cnt.text = "${staged.size} pairs selected"
            }
            list.onItemClickListener = AdapterView.OnItemClickListener { _, view, pos, _ ->
                val q = search.text.toString().uppercase()
                val vis = App.universe.filter { q.isEmpty() || it.contains(q) }
                val s = vis.getOrNull(pos) ?: return@OnItemClickListener
                if (list.isItemChecked(pos)) staged.add(s) else staged.remove(s)
                // Perbarui centang baris ini langsung (tanpa tunggu rebind).
                (view?.findViewById<android.widget.CheckedTextView>(R.id.check))?.isChecked =
                    list.isItemChecked(pos)
                cnt.text = "${staged.size} pairs selected"
            }
            search.addTextChangedListener { render() }
            render()
            root.findViewById<View>(R.id.btnAll).setOnClickListener {
                val q = search.text.toString().uppercase()
                App.universe.filter { q.isEmpty() || it.contains(q) }.forEach { staged.add(it) }
                render()
            }
            root.findViewById<View>(R.id.btnClear).setOnClickListener { staged.clear(); render() }
            root.findViewById<View>(R.id.btnTop5).setOnClickListener { App.universe.take(5).forEach { staged.add(it) }; render() }
            root.findViewById<View>(R.id.btnApply).setOnClickListener {
                App.multiSel = LinkedHashSet(staged); App.saveMulti(); sh.dismiss(); paintConfig(); paintQuick()
            }
            sh.setContentView(root)
            sh.show()
        }
    }

    // ---------- RUN ----------
    private fun pickCsv() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 41)
        } catch (e: Exception) { snack(this, "Tidak ada file picker: ${e.message}") }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 41 && res == RESULT_OK && data?.data != null) {
            try {
                val txt = contentResolver.openInputStream(data.data!!)!!.bufferedReader().readText()
                App.csv = parseCSV(txt)
                snack(this, "CSV: ${App.csv!!.size} candle.")
            } catch (e: Exception) { App.csv = null; snack(this, "CSV error: ${e.message}") }
            paintQuick()
        }
    }

    /** Pemilih tanggal native (I): kalender dialog, bukan ketikan manual. */
    private fun openDatePicker(isFrom: Boolean) {
        val cur = if (isFrom) App.fromDate else App.toDate
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        if (cur > 0) cal.timeInMillis = cur
        android.app.DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val picked = cal.timeInMillis
            val other = if (isFrom) App.toDate else App.fromDate
            // Validasi rentang (I5): tolak diam-diam dilarang — beri pesan jelas.
            if (other > 0 && ((isFrom && picked > other) || (!isFrom && picked < other))) {
                snack(this, "Rentang tidak valid: tanggal mulai tidak boleh melewati tanggal akhir.")
                return@DatePickerDialog
            }
            if (isFrom) App.fromDate = picked else App.toDate = picked
            paintDates()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun paintDates() {
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inFrom)
            .setText(if (App.fromDate > 0) dateDisplay(App.fromDate) else "")
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inTo)
            .setText(if (App.toDate > 0) dateDisplay(App.toDate) else "")
    }

    private fun dateDisplay(ts: Long): String =
        SimpleDateFormat("dd MMM yyyy", Locale("id")).format(Date(ts))

    private fun dateStr(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ts))

    /** Penjaga lapis kedua saat Run: rentang invalid tidak boleh lolos diam-diam. */
    private fun dateRangeOk(): Boolean {
        if (!App.isDateRangeValid(App.fromDate, App.toDate)) {
            setStatus("Rentang tanggal tidak valid: tanggal mulai melewati tanggal akhir.", true)
            return false
        }
        // V21: batas 2 tahun ditolak SEBELUM unduh (jangan dipotong diam-diam).
        if (exceedsTwoYears(App.fromDate, App.toDate)) {
            setStatus("Rentang melebihi 2 tahun kalender. Pilih periode maksimal 2 tahun.", true)
            return false
        }
        return true
    }

    private fun doSingle() {
        if (!dateRangeOk()) return
        cancelled = false
        equityBusy("Menyiapkan backtest…")
        setStatus("Menyiapkan…")
        runBg {
            val tAll = System.nanoTime()
            var stage = "menyiapkan"
            var pErr: BacktestParams? = null
            var monId = ""
            try {
                val p = App.buildParams(App.pair)
                pErr = p
                monId = MonitorBus.startRun(p.asset, p.timeframe, p.startDate, p.endDate, 1)
                stage = "mengambil data"
                MonitorBus.phase(monId, MonPhase.CHECKING_CACHE, "Memeriksa data lokal")
                MonitorBus.pushLog(monId, 0, "Memeriksa data ${p.asset} · ${p.timeframe}")
                setStatus("Mengambil data ${p.asset}…")
                // V21 (freqtrade): bila periode dipilih → pastikan cakupan rentang
                // (arsip lokal + unduh yang hilang + validasi). Tanpa periode →
                // jalur N-terakhir seperti sebelumnya.
                val (candles, meta) = if (App.csv != null && App.csv!!.size >= 60)
                    App.csv!! to FetchMeta("csv", p.asset, p.timeframe, App.limit, App.csv!!.size, "csv-file", "CSV")
                else if (p.startDate > 0 || p.endDate > 0) fetchDated(p)
                else getCandles(App.provider, p.asset, p.timeframe, App.limit).let { it.candles to it.meta }
                if (cancelled) { MonitorBus.cancel(monId); setStatus("Dibatalkan."); return@runBg }
                reportFetch(monId, meta, candles.size)
                App.candles = candles; App.params = p; App.lastFetchMeta = meta; App.lastFetchAt = System.currentTimeMillis()
                setStatus("Backtest ${candles.size}c…")
                val t0 = System.nanoTime()
                stage = "simulasi"
                MonitorBus.phase(monId, MonPhase.VALIDATING_DATA, "Validasi + indikator")
                val res = runBacktest(candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }, p) { ev ->
                    onEngineStage(monId, ev)
                }
                val engMs = (System.nanoTime() - t0) / 1e6
                if (cancelled) { MonitorBus.cancel(monId); setStatus("Dibatalkan."); return@runBg }
                stage = "menyimpan"
                App.result = res
                val st = classifyResult(res)
                App.saveApplied("backtest-single", p, listOf(p.asset), st, res.error == null)
                App.saveLastBt("single", listOf(p.asset), p, App.limit, res.diag.dateFilteredCount, res.totalTrades, engMs.toLong(), ((System.nanoTime() - tAll) / 1e6).toLong(), st)
                if (res.error == null) {
                    // V20 F2: ekspektasi drift dari hasil sukses (bukan angka lama).
                    App.saveDriftExpect(DriftExpect(res.winRate, res.profitFactor, res.totalTrades,
                        res.strategy, res.asset, res.timeframe, System.currentTimeMillis()))
                    App.addHist(res.trades)
                    res.trades.lastOrNull()?.let { lt ->
                        App.pushSignal(Sig(res.asset, res.timeframe, lt.direction, lt.entry, lt.stopLoss, lt.takeProfit, lt.entryTime, "backtest", "b${lt.entryTime}${res.asset}",
                            strategy = res.strategy, decidedAt = System.currentTimeMillis()))
                    }
                }
                if (res.error != null) {
                    MonitorBus.finish(monId, false, res.error ?: "gagal")
                    MonitorBus.pushLog(monId, 2, "Gagal: ${res.error}")
                } else {
                    MonitorBus.counters(monId, res.diag.signalsRaw, res.diag.filteredOut, res.totalTrades)
                    MonitorBus.finish(monId, true)
                    MonitorBus.pushLog(monId, 0,
                        "Selesai: ${res.totalTrades} trade · ${res.diag.signalsRaw} sinyal · ${res.diag.filteredOut} tersaring")
                }
                runOnUiThread { paintQuick(); paintChart(); paintResult() }
                setStatus(if (res.error != null) "$st: ${res.error}" else "Selesai ${engMs.toLong()}ms · ${res.totalTrades} trade · Net ${mQ(res.netProfit, p.asset)} · cache:${meta.cacheUsed}", res.error != null)
            } catch (e: Exception) {
                // V21: gagal = hasil error eksplisit (tahap tercatat), bukan hasil lama.
                val msg = if (stage == "mengambil data") "Pengunduhan gagal: ${e.message}"
                else "Backtest gagal ($stage): ${e.message}"
                setStatus("FAILED: $msg", true)
                if (monId.isNotEmpty()) {
                    MonitorBus.finish(monId, false, msg)
                    MonitorBus.pushLog(monId, 2, "FAILED: $msg")
                }
                try {
                    if (stage != "menyimpan") {
                        App.result = errorResult(pErr ?: BacktestParams(), msg)
                        runOnUiThread { try { paintResult() } catch (_: Exception) { } }
                    }
                } catch (_: Exception) { /* jangan crash handler */ }
            }
        }
    }

    /**
     * V22: teruskan event mesin → MonitorBus (dipanggil dari bg thread; aman).
     * Hanya tahap + angka aktual; tanpa timer, tanpa karangan.
     */
    private fun onEngineStage(monId: String, ev: EngineStage) {
        when (ev) {
            is EngineStage.Validated -> {
                MonitorBus.source(monId, "", ev.normalized, ev.inRange, null)
                MonitorBus.pushLog(monId, 0, "Validasi data: ${ev.normalized} bar → ${ev.inRange} dalam periode")
            }
            EngineStage.IndicatorsDone ->
                MonitorBus.phase(monId, MonPhase.CALCULATING_INDICATORS, "Indikator selesai")
            is EngineStage.Evaluating -> {
                MonitorBus.phase(monId, MonPhase.EVALUATING_STRATEGY, "Evaluasi strategi")
                MonitorBus.progress(monId, ev.done, ev.total)
                MonitorBus.counters(monId, ev.sigRaw, ev.filtered, ev.trades)
            }
            EngineStage.MetricsDone ->
                MonitorBus.phase(monId, MonPhase.CALCULATING_METRICS, "Hitung metrik")
        }
    }

    /** V22: laporkan asal data aktual (bukan klaim unduh). */
    private fun reportFetch(monId: String, meta: FetchMeta, n: Int) {
        val cu = meta.cacheUsed
        val hasNet = cu == "none" || cu == "jaringan" || cu.contains("jaringan")
        val hasLocal = cu == "memory" || cu.startsWith("disk") || cu.contains("arsip") ||
            cu.startsWith("csv") || cu == "csv-file"
        when {
            hasNet && hasLocal -> {
                MonitorBus.phase(monId, MonPhase.DOWNLOADING_DATA, "Arsip dilengkapi")
                MonitorBus.pushLog(monId, 0, "Arsip lokal dilengkapi via jaringan ($cu): $n candle")
                MonitorBus.source(monId, meta.source, n, 0, true)
            }
            hasNet -> {
                MonitorBus.phase(monId, MonPhase.DOWNLOADING_DATA, "Data diterima")
                MonitorBus.pushLog(monId, 0, "Diunduh $n candle (${meta.source})")
                MonitorBus.source(monId, meta.source, n, 0, true)
            }
            else -> {
                MonitorBus.pushLog(monId, 0, "Data lokal digunakan ($cu): $n candle, tanpa unduh")
                MonitorBus.source(monId, meta.source, n, n, false)
            }
        }
    }

    /** V21: alur freqtrade untuk run berperiode (gagal unduh = gagal jujur). */
    private fun fetchDated(p: BacktestParams): Pair<List<Candle>, FetchMeta> {
        val tfMin = try { parseTimeframe(p.timeframe) } catch (e: Exception) { 15 }
        val to = if (p.endDate > 0) p.endDate else System.currentTimeMillis()
        val from = if (p.startDate > 0) p.startDate else to - App.limit.toLong() * tfMin * 60000L
        val ens = ensureDatedCandles(App.provider, p.asset, p.timeframe, from, to, WARMUP,
            { cancelled },
            { msg -> setStatus(msg) })
        val meta = FetchMeta(App.provider, p.asset, p.timeframe, App.limit,
            ens.candles.size, ens.source, "${ens.source} · ${ens.pages} hlm" +
                (if (ens.capped) " · cap" else "") + " · ${App.fmtDate(ens.fetchedAt)}")
        return ens.candles to meta
    }

    private fun paintProg(items: List<Triple<String, String, Int>>, done: Int = -1) {
        runOnUiThread {
            findViewById<TextView>(R.id.progCount).text = "${if (done >= 0) done else items.count { it.third == 0xFF0ECB81.toInt() || it.third == 0xFFF6465D.toInt() }} / ${items.size}"
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.progList).apply {
                vertical(this@BacktestActivity)
                adapter = ProgAdapter(items)
            }
        }
    }

    private fun doMulti() {
        if (!dateRangeOk()) return
        if (App.universe.isEmpty()) { reloadUniverse { doMulti() }; return }
        val sel = App.universe.filter { App.multiSel.contains(it) } + App.multiSel.filter { !App.universe.contains(it) }
        if (sel.isEmpty()) { setStatus("Please select at least one pair.", true); return }
        cancelled = false
        equityBusy("Menyiapkan multi-pair…")
        runBg {
            val tAll = System.nanoTime()
            val p: BacktestParams
            try { p = App.buildParams(sel.first()) } catch (e: Exception) {
                setStatus("FAILED: ${e.message}", true)
                try {
                    App.result = errorResult(BacktestParams(), "Backtest gagal (konfigurasi): ${e.message}")
                    runOnUiThread { try { paintResult() } catch (_: Exception) { } }
                } catch (_: Exception) { /* abaikan */ }
                return@runBg
            }
            if (App.csv != null && App.csv!!.size >= 60) setStatus("CSV diabaikan saat multi-pair.")
            val monId = MonitorBus.startRun(sel.joinToString("+"), p.timeframe, p.startDate, p.endDate, sel.size)
            MonitorBus.phase(monId, MonPhase.CHECKING_CACHE, "Multi-pair: ${sel.size} pair")
            MonitorBus.pushLog(monId, 0, "Memulai Backtest multi (${sel.size} pair)")
            val items = sel.map { Triple(it, "·", 0xFF8B95A5.toInt()) }.toMutableList()
            val rows = ArrayList<PairRow>()
            val cache = HashMap<String, Pair<List<Candle>, BacktestResult>>()
            val metas = HashMap<String, FetchMeta>()
            var done = 0
            paintProg(items, done)
            for ((idx, sym) in sel.withIndex()) {
                if (cancelled) {
                    items[idx] = Triple(sym, "·", 0xFF8B95A5.toInt()); paintProg(items, done)
                    MonitorBus.cancel(monId); setStatus("Dibatalkan.")
                    break
                }
                MonitorBus.pairProgress(monId, done, sym)
                items[idx] = Triple(sym, "…", 0xFF4C8DFF.toInt())
                setStatus("Backtest $sym… (${done + 1}/${sel.size})")
                paintProg(items, done)
                try {
                    val fr = if (p.startDate > 0 || p.endDate > 0) {
                        // V21: rentang per pair (parameter disalin per aset).
                        val to = if (p.endDate > 0) p.endDate else System.currentTimeMillis()
                        val tfMin = try { parseTimeframe(p.timeframe) } catch (e: Exception) { 15 }
                        val from = if (p.startDate > 0) p.startDate else to - App.limit.toLong() * tfMin * 60000L
                        val ens = ensureDatedCandles(App.provider, sym, p.timeframe, from, to, WARMUP,
                            { cancelled }, { msg -> setStatus("$sym: $msg") })
                        val meta = FetchMeta(App.provider, sym, p.timeframe, App.limit,
                            ens.candles.size, ens.source, "${ens.source} · ${ens.pages} hlm" +
                                (if (ens.capped) " · cap" else ""))
                        FetchResult(ens.candles, meta)
                    } else getCandles(App.provider, sym, p.timeframe, App.limit)
                    reportFetch(monId, fr.meta, fr.candles.size)
                    val t0 = System.nanoTime()
                    MonitorBus.phase(monId, MonPhase.VALIDATING_DATA, "Validasi $sym")
                    val res = runBacktest(fr.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }, p.copy(asset = sym)) { ev ->
                        onEngineStage(monId, ev)
                    }
                    if (res.error != null) {
                        MonitorBus.pushLog(monId, 1, "$sym gagal: ${res.error}")
                    } else {
                        MonitorBus.counters(monId, res.diag.signalsRaw, res.diag.filteredOut, res.totalTrades)
                        MonitorBus.pushLog(monId, 0, "$sym: ${res.totalTrades} trade · ${res.diag.signalsRaw} sinyal")
                    }
                    val engMs = (System.nanoTime() - t0) / 1e6
                    val res2 = res.copy(diag = res.diag.copy(limitRequested = App.limit, received = fr.meta.received, source = fr.meta.source, cacheUsed = fr.meta.cacheUsed))
                    cache[sym] = fr.candles to res2
                    metas[sym] = fr.meta
                    rows.add(PairRow(sym, res2))
                    val st = pairStatusOf(PairRow(sym, res2))
                    items[idx] = Triple(sym, if (st == "SUCCESS" || st == "NO TRADES") "✓ $st · ${res2.totalTrades} tr · ${engMs.toLong()}ms" else "✕ $st",
                        if (st == "SUCCESS") 0xFF0ECB81.toInt() else if (st == "NO TRADES") 0xFFFFB800.toInt() else 0xFFF6465D.toInt())
                } catch (e: Exception) {
                    rows.add(PairRow(sym, null, e.message))
                    items[idx] = Triple(sym, "✕ NO DATA", 0xFFF6465D.toInt())
                    MonitorBus.pushLog(monId, 2, "$sym error: ${e.message}")
                }
                done++
                paintProg(items, done)
            }
            rows.sortByDescending { it.res?.netProfit ?: Double.NEGATIVE_INFINITY }
            App.multiCache = cache; App.multiRows = rows
            val okN = rows.count { it.res != null && it.res.error == null }
            val overall = if (okN == rows.size) "SUCCESS" else if (okN > 0) "PARTIAL SUCCESS" else "FAILED"
            App.saveApplied("backtest-multi", p, sel, overall, okN > 0)
            val all = rows.filter { it.res?.error == null }.flatMap { it.res!!.trades }.sortedBy { it.exitTime }
            val first = rows.firstOrNull { it.res?.error == null }
            App.result = first?.res
            tradeRows = all
            if (first != null) { App.candles = cache[first.sym]!!.first; App.params = p.copy(asset = first.sym); App.lastFetchMeta = metas[first.sym]; App.lastFetchAt = System.currentTimeMillis() }
            App.saveLastBt("multi", sel, p, App.limit, first?.res?.diag?.dateFilteredCount ?: 0, all.size, 0, ((System.nanoTime() - tAll) / 1e6).toLong(), overall)
            if (first?.res != null && first.res.error == null) {
                // V20 F2: ekspektasi drift = agregat gabungan multi-pair.
                val aggM = aggregateOverall(rows, buildCombinedEquity(rows, p.initialCapital).maxDDPct)
                App.saveDriftExpect(DriftExpect(aggM.winRate, aggM.profitFactor, aggM.totalTrades,
                    p.strategy, sel.joinToString("+"), p.timeframe, System.currentTimeMillis()))
                App.addHist(first.res.trades)
                first.res.trades.lastOrNull()?.let { lt ->
                    App.pushSignal(Sig(first.sym, p.timeframe, lt.direction, lt.entry, lt.stopLoss, lt.takeProfit, lt.entryTime, "backtest", "b${lt.entryTime}${first.sym}",
                        strategy = first.res.strategy, decidedAt = System.currentTimeMillis()))
                }
            }
            if (cancelled) {
                MonitorBus.cancel(monId)
            } else if (overall == "FAILED") {
                MonitorBus.finish(monId, false, "Semua pair gagal")
            } else {
                MonitorBus.counters(monId, rows.sumOf { it.res?.diag?.signalsRaw ?: 0 },
                    rows.sumOf { it.res?.diag?.filteredOut ?: 0 }, all.size)
                MonitorBus.finish(monId, true)
                MonitorBus.pushLog(monId, 0, "Selesai multi: $okN/${rows.size} pair · ${all.size} trade")
            }
            MonitorBus.pairProgress(monId, done, "")
            runOnUiThread { paintQuick(); paintChart(); paintResult() }
            setStatus("$overall: $okN of ${rows.size} pairs processed successfully", okN == 0)
        }
    }

    // ---------- paint hasil ----------
    private fun paintQuick() {
        findViewById<TextView>(R.id.quickMeta).text =
            (if (App.mode == "multi") "MULTI (${App.multiSel.size})" else App.pair) + " · ${App.timeframe} · ${App.strategy}"
    }

    private fun setupCharts() {
        findViewById<LineChart>(R.id.equity).apply {
            description.isEnabled = false
            setBackgroundColor(0xFF0B0E14.toInt())
            legend.isEnabled = false
            axisLeft.isEnabled = false
            axisRight.textColor = 0xFF8B95A5.toInt()
            xAxis.textColor = 0xFF5B6572.toInt()
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
        }
    }

    /** Status sibuk pada kartu equity (pengganti overlay chart harga). */
    private fun equityBusy(msg: String) {
        runOnUiThread {
            try {
                findViewById<TextView>(R.id.equityMsg).apply {
                    visibility = View.VISIBLE; text = msg
                }
            } catch (e: Exception) { /* abaikan */ }
        }
    }

    /** Equity curve dari hasil aktual (bukan contoh). Titik = (waktu, ekuitas). */
    private fun paintChart() {
        val r = App.result
        if (App.multiRows.isNotEmpty()) {
            val cmb = buildCombinedEquity(App.multiRows, App.params?.initialCapital ?: 1000.0)
            paintEquityCurve(cmb.curve, App.params?.initialCapital ?: 1000.0,
                "Gabungan ${cmb.n} transaksi")
        } else {
            val eq = r?.equityCurve ?: emptyList()
            paintEquityCurve(eq, r?.initialCapital ?: (App.params?.initialCapital ?: 1000.0),
                if (r != null && r.error == null) r.asset else "")
        }
    }

    private fun paintEquityCurve(
        points: List<com.aether.signal.premium.engine.EquityPoint>,
        initialCapital: Double,
        label: String
    ) {
        val msg = findViewById<TextView>(R.id.equityMsg)
        val meta = findViewById<TextView>(R.id.equityMeta)
        val v = findViewById<LineChart>(R.id.equity)
        val clean = points.filter { it.equity.isFinite() && it.t > 0 }
        val res = App.result
        if (clean.size < 2) {
            v.clear(); v.invalidate()
            meta.text = ""
            msg.visibility = View.VISIBLE
            msg.text = when {
                res?.error != null -> "${classifyResult(res)}: ${res.error}"
                App.multiRows.isNotEmpty() || res != null -> "Belum ada titik ekuitas valid."
                else -> "Jalankan backtest untuk melihat equity curve."
            }
            return
        }
        msg.visibility = View.GONE
        val finalEq = clean.last().equity
        meta.text = "Modal ${App.fmtMoney(initialCapital)} → ${App.fmtMoney(finalEq)}" +
            (if (label.isNotEmpty()) " · $label" else "") +
            "\n${App.fmtT(clean.first().t)} → ${App.fmtT(clean.last().t)} · ${clean.size} titik"
        val (lo, hi) = equityPlotRange(clean.map { it.equity })
        v.axisRight.apply {
            axisMinimum = lo
            axisMaximum = hi
            setDrawGridLines(true)
            gridColor = 0xFF232B36.toInt()
            setLabelCount(4, false)
        }
        // Sumbu waktu jujur: 5 label tanggal dari timestamp aktual.
        val idx = listOf(0, clean.size / 4, clean.size / 2, clean.size * 3 / 4, clean.size - 1).distinct()
        v.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(idx.map {
                App.fmtT(clean[it.coerceIn(clean.indices)].t)
            })
            setLabelCount(idx.size, true)
        }
        val e = ArrayList<com.github.mikephil.charting.data.Entry>()
        clean.forEachIndexed { i, pt -> e.add(com.github.mikephil.charting.data.Entry(i.toFloat(), pt.equity.toFloat())) }
        val up = finalEq >= initialCapital
        val ds = LineDataSet(e, "Equity").apply {
            setDrawValues(false); setDrawCircles(false)
            color = (if (up) 0xFF0ECB81 else 0xFFF6465D).toInt(); lineWidth = 2.5f
            setDrawFilled(true); fillColor = (if (up) 0x330ECB81 else 0x33F6465D).toInt()
            mode = LineDataSet.Mode.CUBIC_BEZIER
            axisDependency = YAxis.AxisDependency.RIGHT
        }
        v.data = com.github.mikephil.charting.data.LineData(ds)
        v.notifyDataSetChanged()
        v.invalidate()
    }

    /** V18: format uang mengikuti kuotasi pair (USDT/USD → $; lain → kode). */
    private fun mQ(v: Double, asset: String): String = fmtMoneyQ(v, quoteCurrency(asset))

    private fun paintResult() {
        val r = App.result
        if (r == null) {
            findViewById<TextView>(R.id.sumLine).text = "Belum ada hasil — tekan Run."
            findViewById<View>(R.id.whyBox).visibility = View.GONE
            findViewById<View>(R.id.btnReplay).visibility = View.GONE
            paintHeat(emptyList())
            paintFee(emptyList())
            return
        }
        if (r.error != null) {
            findViewById<TextView>(R.id.heroNet).text = classifyResult(r)
            findViewById<TextView>(R.id.heroNet).setTextColor(0xFFF6465D.toInt())
            findViewById<TextView>(R.id.heroSub).text = r.error
            findViewById<TextView>(R.id.sumLine).text = "${r.asset} · ${classifyResult(r)}"
            findViewById<View>(R.id.btnReplay).visibility = View.GONE
            paintKv(emptyList()); paintPairs(emptyList()); paintTrades(); paintTech(r); paintWhy(r)
            paintHeat(emptyList())
            paintFee(emptyList())
            return
        }
        val noTr = r.totalTrades <= 0
        findViewById<TextView>(R.id.heroNet).apply {
            text = mQ(r.netProfit, r.asset)
            setTextColor(if (noTr) 0xFFE8EDF2.toInt() else if (r.netProfit >= 0) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt())
        }
        findViewById<TextView>(R.id.heroSub).text =
            "${r.asset} ${r.timeframe} · ${r.strategy} · ${if (noTr) "NO TRADES" else "Win " + App.fmt(r.winRate, 1) + "%"} · PF ${App.fmtD(r.profitFactor)} · DD ${App.fmt(r.maxDrawdownPercent)}%"
        findViewById<TextView>(R.id.sumLine).text =
            "Net ${App.fmt(r.netProfitPercent)}% · Final ${mQ(r.finalCapital, r.asset)} · Expectancy ${mQ(r.expectancy, r.asset)}"
        paintKv(listOf(
            "Total Trades" to r.totalTrades.toString(),
            "Win Rate" to if (noTr) "NO TRADES" else App.fmt(r.winRate, 1) + "%",
            "Profit Factor" to App.fmtD(r.profitFactor),
            "Max Drawdown" to App.fmt(r.maxDrawdownPercent) + "%",
            "Avg Win" to mQ(r.averageWin, r.asset),
            "Avg Loss" to mQ(r.averageLoss, r.asset),
            "Tersaring filter" to r.filtered.toString(),
            "Exposure" to App.fmt(r.exposure, 1) + "%"
        ))
        paintPairs(App.multiRows)
        tradeRows = if (App.multiRows.isNotEmpty()) App.multiRows.filter { it.res?.error == null }.flatMap { it.res!!.trades }.sortedBy { it.exitTime } else r.trades
        tradePage = 0
        paintTrades()
        paintTech(r)
        paintWhy(r)
        paintHeat(tradeRows)
        paintFee(tradeRows)
        paintReplayBtn()
    }

    /** V14 F1: tombol replay hanya bila ada data + parameter hasil run terakhir. */
    private fun paintReplayBtn() {
        try {
            val ok = App.result?.error == null && App.params != null && App.candles.isNotEmpty()
            findViewById<View>(R.id.btnReplay).visibility = if (ok) View.VISIBLE else View.GONE
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun openReplay() {
        if (App.result?.error != null || App.params == null || App.candles.isEmpty()) {
            snack(this, "Data historis tidak cukup untuk replay — jalankan backtest dulu.")
            return
        }
        // Replay membaca App.candles + App.params apa adanya (tanpa request jaringan).
        startActivity(Intent(this, ReplayActivity::class.java))
    }

    // ---------- V16 F3: kartu biaya (dari transaksi yang sama dengan laporan) ----------
    private fun paintFee(trades: List<Trade>) {
        try {
            val tv = findViewById<TextView>(R.id.feeLine)
            if (trades.isEmpty()) {
                tv.text = "Belum ada transaksi — biaya $0."
                return
            }
            val a = feeAudit(trades)
            val fq = if (mixedQuotes(trades)) "XXX" else quoteCurrency(trades.firstOrNull()?.asset ?: App.result?.asset ?: "")
            tv.text = "Total fee ${mQ(a.totalFees, fq)} · " +
                "laba kotor (sblm fee) ${mQ(a.grossBeforeFees, fq)} · " +
                "laba bersih ${mQ(a.netProfit, fq)}" +
                (if (a.feeSharePct != null) " · fee memakan ${App.fmt(a.feeSharePct, 1)}% laba kotor"
                else " · porsi fee tak terdefinisi (laba kotor ≤ 0)") +
                "\nFee mesin = fee entry + fee exit per transaksi; laba bersih sudah bersih fee (tak dikurangi 2×)."
        } catch (e: Exception) { /* abaikan */ }
    }

    // ---------- V16 F5: Monte Carlo (bg + batal, tanpa jaringan) ----------
    @Volatile private var mcCancelled = false

    private fun openMonteCarlo() {
        val r = App.result
        if (r == null || r.error != null || tradeRows.isEmpty()) {
            snack(this, "Butuh hasil backtest bertransaksi — jalankan backtest dulu.")
            return
        }
        val pnls = tradeRows.map { it.pnl }
        val capital = r.initialCapital
        mcCancelled = false
        val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Monte Carlo…")
            .setMessage("Mengocok urutan ${pnls.size} transaksi × 1000 simulasi…")
            .setNegativeButton("Batal") { _, _ -> mcCancelled = true }
            .setCancelable(false)
            .show()
        runBg {
            try {
                val res = monteCarlo(pnls, capital, 1000, 20261010L, 50.0)
                runOnUiThread {
                    try { prog.dismiss() } catch (e: Exception) { }
                    if (mcCancelled) snack(this, "Monte Carlo dibatalkan.")
                    else if (res == null) snack(this, "Monte Carlo tak dapat dihitung (data tak valid).")
                    else showMcResult(res)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    try { prog.dismiss() } catch (e: Exception) { }
                    snack(this, "Monte Carlo gagal: ${e.message}")
                }
            }
        }
    }

    private fun showMcResult(m: MCResult) {
        val mq: (Double) -> String = { v -> mQ(v, App.result?.asset ?: "") }
        val msg = "Modal awal ${mq(m.initialCapital)} · ${m.n} transaksi · " +
            "${m.sims} simulasi (seed ${m.seed}).\n" +
            "Ekuitas akhir (sama di semua simulasi — urutan diacak, multiset pnl tetap): ${mq(m.finalEquity)}.\n" +
            "MaxDD jalur: median ${App.fmt(m.medianDDPct, 1)}% · rentang ${App.fmt(m.minDDPct, 1)}–${App.fmt(m.maxDDPct, 1)}%.\n" +
            "Peluang menyentuh −${App.fmt(m.ruinDrawdownPct, 0)}% dari modal: " +
            "${App.fmt(m.ruinProb * 100, 1)}% (${m.ruinCount}/${m.sims} jalur).\n\n" +
            "Menguji ketidakpastian URUTAN, bukan meramal masa depan. " +
            "Sangat bergantung sampel historis & asumsi acak. Satu angka ruin bukan kepastian statistik."
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Hasil Monte Carlo")
            .setMessage(msg)
            .setPositiveButton("Tutup", null)
            .show()
    }

    // ---------- V16 F6/F9: launcher (data snapshot pair tampil saat ini) ----------
    private fun analysisReady(): Boolean {
        if (App.result?.error != null || App.params == null || App.candles.isEmpty()) {
            snack(this, "Data tidak cukup — jalankan backtest dulu.")
            return false
        }
        return true
    }

    private fun openAblation() {
        if (!analysisReady()) return
        startActivity(Intent(this, AblationActivity::class.java))
    }

    private fun openConsensus() {
        if (!analysisReady()) return
        startActivity(Intent(this, ConsensusActivity::class.java))
    }

    private fun openWalkForward() {
        if (!analysisReady()) return
        startActivity(Intent(this, WalkForwardActivity::class.java))
    }

    private fun paintKv(rows: List<Pair<String, String>>) {
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.kvList).apply {
            vertical(this@BacktestActivity)
            adapter = KvAdapter(rows)
        }
    }

    private fun paintPairs(rows: List<PairRow>) {
        findViewById<TextView>(R.id.pairsHead).visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pairsList)
        list.vertical(this)
        if (rows.isEmpty()) { list.adapter = SigAdapter(emptyList()); return }
        val agg = aggregateOverall(rows, buildCombinedEquity(rows, App.params?.initialCapital ?: 1000.0).maxDDPct)
        val okN = rows.count { it.res?.error == null }
        val items = ArrayList<SigItem>()
        items.add(SigItem("Σ", "Overall — $okN of ${rows.size}",
            "Net ${mQ(agg.netProfit, mixedQuoteOf(rows.map { it.sym }))} · PF ${App.fmtD(agg.profitFactor)} · DD ${App.fmt(agg.maxDD)}%", agg.totalTrades.toString()))
        rows.forEachIndexed { i, row ->
            val st = pairStatusOf(row)
            items.add(SigItem(
                if (st == "SUCCESS") "✓" else if (st == "NO TRADES") "○" else "✕",
                "${row.sym}  $st",
                if (row.res?.error == null && row.err == null)
                    "Net ${mQ(row.res!!.netProfit, row.sym)} · ${if (row.res.totalTrades > 0) App.fmt(row.res.winRate, 1) + "%" else "NO TRADES"} · ${row.res.totalTrades} tr — klik"
                else (row.err ?: row.res?.error ?: "").take(60),
                "", row.sym))
        }
        list.adapter = SigAdapter(items, onClick = { pos ->
            if (pos == 0) return@SigAdapter
            val row = rows[pos - 1]
            val hit = App.multiCache[row.sym] ?: return@SigAdapter
            if (row.res?.error != null) return@SigAdapter
            App.candles = hit.first; App.params = App.params?.copy(asset = row.sym); App.result = hit.second
            paintChart(); paintResult()
        })
    }

    private fun paintTrades() {
        val per = 25
        val pages = maxOf(1, (tradeRows.size + per - 1) / per)
        tradePage = tradePage.coerceIn(0, pages - 1)
        findViewById<TextView>(R.id.tradePage).text = "Transaksi ${tradeRows.size} · ${tradePage + 1} / $pages"
        val page = tradeRows.drop(tradePage * per).take(per)
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tradeList).apply {
            vertical(this@BacktestActivity)
            adapter = SigAdapter(page.mapIndexed { k, t ->
                SigItem(t.direction, "${tradePage * per + k + 1}. ${t.asset}",
                    "${App.fmtT(t.exitTime)} · in ${App.fmt(t.entry, 4)} out ${App.fmt(t.exit, 4)} · R ${App.fmt(t.rMultiple)} · ${t.result}",
                    mQ(t.pnl, t.asset), t.asset)
            }, onClick = { pos -> if (pos in page.indices) openTradeDetail(page[pos]) })
        }
    }

    /** P6: audit konsistensi live vs backtest — memakai mesin yang sama, tanpa
     *  mengubahnya. Input + konfigurasi dicatat pada verdict. */
    private fun runAudit() {
        val v = findViewById<TextView>(R.id.auditVerdict)
        val r = App.result
        val p = App.params
        if (r == null || p == null || App.candles.isEmpty()) {
            v.text = "Tidak dapat dibandingkan: jalankan backtest dulu."
            v.setTextColor(0xFF8B95A5.toInt())
            return
        }
        v.text = "Menghitung…"
        runBg {
            val rep = try {
                auditLiveVsBacktest(
                    App.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? },
                    p, r)
            } catch (e: Exception) {
                AuditReport(AuditVerdict.UNCOMPARABLE, "Audit gagal: ${e.message}",
                    p.asset, p.timeframe, p.strategy, 0, null, null, App.candles.size)
            }
            runOnUiThread {
                val col = when (rep.verdict) {
                    AuditVerdict.MATCH -> 0xFF0ECB81.toInt()
                    AuditVerdict.MISMATCH -> 0xFFF6465D.toInt()
                    AuditVerdict.UNCOMPARABLE -> 0xFF8B95A5.toInt()
                }
                v.text = "${rep.verdict}: ${rep.reason}\n" +
                    "Input: ${rep.asset} · ${rep.timeframe} · ${rep.strategy} · ${rep.candlesUsed} candle"
                v.setTextColor(col)
            }
        }
    }

    private fun paintTech(r: BacktestResult) {        val d = r.diag
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.techList).apply {
            vertical(this@BacktestActivity)
            adapter = KvAdapter(listOf(
                "Status" to classifyResult(r),
                "Strategi" to r.strategy,
                "Dimuat" to d.dateFilteredCount.toString(),
                "Pemanasan (pra-start)" to d.warmupPrefix.toString(),
                "Dievaluasi" to d.evaluatedBars.toString(),
                "Signal" to d.signalsRaw.toString(),
                "Tersaring" to d.filteredOut.toString(),
                "Lolos/filter" to (d.filterPassed.entries.sortedByDescending { it.value }.take(3)
                    .joinToString(" · ") { "${it.key}:${it.value}" }.ifEmpty { "—" }),
                "Run" to if (d.runId.isNotEmpty()) "${d.runId} · ${App.fmtDate(d.finishedAt)}" else "—",
                "Cache" to (d.cacheUsed ?: "—")
            ))
        }
        paintDataAudit(r)
    }

    /**
     * V17 (forensik): panel Validasi Data — periode diminta vs aktual diproses,
     * fingerprint dataset, kualitas jendela, status VALID/PERINGATAN/GAGAL.
     * Dihitung dari jendela fetch + params + hasil run tampil saat ini.
     */
    private fun paintDataAudit(r: BacktestResult) {
        try {
            val head = findViewById<TextView>(R.id.auditHead)
            val body = findViewById<TextView>(R.id.auditBody)
            if (r.error != null) {
                val msg = r.error ?: ""
                val st = when {
                    msg.startsWith("Pengunduhan gagal") -> "Pengunduhan gagal"
                    msg.contains("minimal") || msg.contains("tidak cukup") || msg.contains("Data tidak cukup") -> "Data tidak mencukupi"
                    msg.contains("2 tahun") -> "Data tidak mencukupi"
                    else -> "Backtest gagal"
                }
                head.text = "Validasi Data Backtest: $st — $msg"
                head.setTextColor(0xFFF6465D.toInt())
                body.text = (if (r.diag.runId.isNotEmpty())
                    "Run ${r.diag.runId} · selesai ${fmtUtc(r.diag.finishedAt)}.\n" else "") +
                    "Hasil lama (bila masih tampil) BUKAN hasil run ini.\n" +
                    "Strategi: ${r.strategy} · Pair: ${r.asset} · TF: ${r.timeframe}"
                return
            }
            val p = App.params
            val win = App.candles
            val meta = App.lastFetchMeta
            val prov = meta?.provider ?: App.provider
            val sym = meta?.symbol ?: (p?.asset ?: r.asset)
            val tf = p?.timeframe ?: r.timeframe
            val lim = meta?.limitRequested ?: App.limit
            val tfMin = try { parseTimeframe(tf) } catch (e: Exception) { 15 }
            val reqFrom = p?.startDate ?: 0L
            val reqTo = p?.endDate ?: 0L
            val inRange = filterByDate(win, reqFrom, reqTo)
            val q = auditWindow(win, tfMin, reqFrom, reqTo, System.currentTimeMillis())
            val fp = if (win.isEmpty()) "tanpa data"
            else datasetFingerprint(prov, sym, tf, lim, q.firstT, q.lastT, q.count)
            val d = r.diag
            val skippedOther = d.skippedNoLevel + d.skippedBadEntry + d.skippedBadQty + d.skippedBadPnl
            val warns = ArrayList<String>()
            val cov = coverageStatus(reqFrom, q.firstT, q.count)
            if (cov == "parsial") warns.add(
                "Cakupan parsial: diminta sejak ${fmtUtc(reqFrom)}, data tersedia sejak ${fmtUtc(q.firstT)}. " +
                    "Periode sebelum itu TIDAK diproses (bukan dianggap berhasil).")
            if (q.gaps.isNotEmpty()) warns.add(
                "${q.gaps.size} celah interval (${q.gaps.sumOf { it.missing }} candle hilang); " +
                    "contoh setelah ${fmtUtc(q.gaps.first().afterT)}.")
            if (q.unclosedLast) warns.add("Candle terakhir belum tentu tutup (lebih muda dari 1 interval).")
            if (q.medianGapMs != q.expectedGapMs && q.count >= 2) warns.add(
                "Interval aktual median ${q.medianGapMs / 60000} mnt ≠ $tfMin mnt label $tf " +
                    "(provider dapat mengembalikan granularitas berbeda).")
            if (d.evaluatedBars == 0) warns.add("Mesin tidak mengevaluasi satu bar pun.")
            val status = if (warns.isEmpty()) "Valid" else "Valid dengan peringatan"
            head.text = "Validasi Data Backtest: $status" +
                (if (warns.isEmpty()) "" else " — ${warns.first()}") +
                "\n(Buka diagnostik untuk rincian. Waktu panel UTC; input tanggal midnight UTC.)"
            head.setTextColor((if (warns.isEmpty()) 0xFF0ECB81 else 0xFFFFB800).toInt())
            val sb = StringBuilder()
            sb.append("Run ${d.runId.ifEmpty { "—" }} · selesai ${if (d.finishedAt > 0) fmtUtc(d.finishedAt) else "—"}\n")
            sb.append("Pair $sym · TF $tf · provider $prov · limit $lim · sumber ${meta?.source ?: d.source ?: "—"}\n")
            sb.append("Diminta: ${if (reqFrom > 0) fmtUtc(reqFrom) else "tanpa batas"} → " +
                "${if (reqTo > 0) fmtUtc(reqTo) else "terkini"}\n")
            sb.append("Diproses: ${if (inRange.isEmpty()) "—" else fmtUtc(inRange.first().t) + " → " + fmtUtc(inRange.last().t)}\n")
            sb.append("Candle: tersedia ${q.count} · di periode ${inRange.size} · " +
                "pemanasan ${d.warmupPrefix} · dievaluasi ${d.evaluatedBars} · " +
                "terlewati-dalam-posisi ${d.skippedInPosition} · gagal-hitung $skippedOther\n")
            sb.append("Transaksi: ${r.totalTrades} (entry pertama " +
                "${r.trades.minOfOrNull { it.entryTime }?.let { fmtUtc(it) } ?: "—"} · " +
                "exit terakhir ${r.trades.maxOfOrNull { it.exitTime }?.let { fmtUtc(it) } ?: "—"})\n")
            sb.append("Dataset: $fp\n")
            sb.append("Kualitas: duplikat/diac inconsisten ${q.unordered} · celah ${q.gaps.size} · " +
                "di luar periode ${q.outOfRange} · terakhir-belum-tutup ${if (q.unclosedLast) "ya" else "tidak"}\n")
            val expN = if (reqFrom > 0 && reqTo > reqFrom) expectedCandles(reqFrom, reqTo, tfMin) else -1L
            sb.append("Ekspektasi: ${if (expN >= 0) "$expN candle utuh" else "— (tanpa batas tanggal)"} · " +
                "diterima ${q.count} (jendela${if (d.warmupPrefix > 0) " termasuk ${d.warmupPrefix} pemanasan" else ""})\n")
            val cu = meta?.cacheUsed ?: "—"
            val asal = when {
                cu.contains("arsip") && cu.contains("jaringan") -> "data lokal + data baru diunduh"
                cu.contains("arsip") -> "data lokal"
                cu == "jaringan" || cu == "none" -> "data baru diunduh"
                cu == "csv" -> "berkas CSV pengguna"
                cu == "memory" -> "memori sesi"
                cu.startsWith("disk") -> "cache disk sesi lalu"
                else -> cu
            }
            sb.append("Sumber: $asal · diambil/diubah ${if (App.lastFetchAt > 0) fmtUtc(App.lastFetchAt) else "—"}\n")
            sb.append("TF tambahan: tidak ada (mesin satu-TF; mtf_confirm/htf memakai seri $tf)\n")
            sb.append("Simulasi: ${classifyResult(r)} · run ${d.runId.ifEmpty { "—" }}\n")
            // V18: metrik & asumsi — definisi eksplisit agar angka tak disalahbaca.
            val quote = mixedQuoteOf(r.trades.map { it.asset }.ifEmpty { listOf(sym) })
            val ddw = ddWindows(r.equityCurve)
            sb.append("Mata uang pelaporan: $quote" +
                (if (quote == "XXX") " (PERINGATAN: agregat lintas mata uang, tak dikonsolidasi)" else "") + "\n")
            sb.append("Win ${App.fmt(r.winRate, 1)}% dari ${r.totalTrades} selesai " +
                "(menang ${r.wins} · kalah ${r.losses} · kedaluwarsa ${r.expired}; penyebut termasuk kedaluwarsa)\n")
            sb.append("PF: ${describePF(r.profitFactor, r.wins, r.losses)}" +
                (if (r.losses == 0 && r.wins > 0) " (nilai mesin 999/∞)" else "") + "\n")
            sb.append("Klasifikasi exit: WIN/LOSS/EXPIRED by pemicu (tanpa kategori impas)\n")
            sb.append("MaxDD ${App.fmt(r.maxDrawdownPercent, 1)}% dari kurva titik-tutup" +
                (if (ddw != null && ddw.ddPct > 0) " · puncak ${fmtUtc(ddw.peakT)} → lembah ${fmtUtc(ddw.troughT)}" else "") + "\n")
            sb.append("Posisi terbuka akhir periode: 0 (mesin selalu menutup EXPIRED di bar terakhir)\n")
            sb.append("Asumsi: tanpa funding fee (valid untuk spot) · slippage ±kedua sisi · " +
                "SL didahulukan bila satu candle menyentuh SL+TP · satu posisi dalam satu waktu\n")
            if (warns.size > 1) {
                sb.append("Peringatan lain:\n")
                for (w in warns.drop(1)) sb.append("• $w\n")
            }
            body.text = sb.toString()
        } catch (e: Exception) {
            try { findViewById<TextView>(R.id.auditHead).text = "Validasi Data: tak dapat dihitung (${e.message})" } catch (_: Exception) { }
        }
    }

    /** FITUR 1: "Mengapa Tidak Ada Transaksi?" hanya saat nol transaksi (murni,
     *  dihitung dari diagnostik backtest yang sedang ditampilkan). */
    private fun paintWhy(r: BacktestResult) {
        try {
            val box = findViewById<View>(R.id.whyBox)
            val body = findViewById<TextView>(R.id.whyBody)
            val btn = findViewById<View>(R.id.btnWhyFilter)
            val btnWi = findViewById<View>(R.id.btnWhatIf)
            val txt = explainNoTrades(r)
            if (txt.isEmpty()) {
                box.visibility = View.GONE; body.text = ""
                btn.visibility = View.GONE; btnWi.visibility = View.GONE
                return
            }
            box.visibility = View.VISIBLE; body.text = txt
            // V13 F2: tombol hanya tampil bila penyebab utama TERPETAKAN ke id filter.
            val fid = primaryFilterId(r.diag.filterReasons)
            if (fid == null) {
                btn.visibility = View.GONE
            } else {
                val fname = FILTER_DEFS.find { it.id == fid }?.name ?: fid
                (btn as? android.widget.Button)?.text = "Lihat Filter: $fname"
                btn.visibility = View.VISIBLE
                btn.setOnClickListener { jumpToFilter() }
            }
            // V14 F2: simulasi what-if — hanya bila filter penyebab AKTIF di params kini.
            val p = App.params
            val fidActive = fid != null && p != null &&
                p.filters.any { it.name == fid && it.enabled }
            if (fidActive) {
                btnWi.visibility = View.VISIBLE
                btnWi.setOnClickListener { confirmWhatIf(fid!!) }
            } else {
                btnWi.visibility = View.GONE
            }
        } catch (e: Exception) { /* layout belum siap */ }
    }

    /**
     * V13 F2: gulir ke kartu FILTER + sorot sementara. Hanya visual:
     * tak mengubah nilai, tak melonggarkan filter, tak menjalankan ulang backtest.
     */
    private fun jumpToFilter() {
        val r = App.result
        val fid = r?.let { primaryFilterId(it.diag.filterReasons) }
        if (fid == null) {
            snack(this, "Penyebab filter tidak dapat dipetakan pada hasil ini.")
            return
        }
        val fname = FILTER_DEFS.find { it.id == fid }?.name ?: fid
        try {
            val card = findViewById<MaterialCardView>(R.id.filterCard)
            val scroll = findViewById<android.widget.ScrollView>(R.id.backtestScroll)
            scroll.post { scroll.smoothScrollTo(0, card.top) }
            val density = resources.displayMetrics.density
            card.strokeWidth = (2 * density).toInt()
            card.strokeColor = androidx.core.content.ContextCompat.getColor(this, R.color.amber)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { card.strokeWidth = 0 } catch (e: Exception) { /* abaikan */ }
            }, 2500)
            snack(this, "Filter \"$fname\" disorot — nilai tidak diubah.")
        } catch (e: Exception) {
            snack(this, "Tidak dapat menuju kartu filter: ${e.message}")
        }
    }

    // ---------- V14 F2: what-if (simulasi tanpa satu filter) ----------
    @Volatile private var whatIfCancelled = false

    private fun confirmWhatIf(fid: String) {
        val fname = FILTER_DEFS.find { it.id == fid }?.name ?: fid
        val p = App.params
        if (App.result == null || p == null || App.candles.isEmpty()) {
            snack(this, "Data tidak cukup untuk simulasi — jalankan backtest dulu.")
            return
        }
        if (!p.filters.any { it.name == fid && it.enabled }) {
            snack(this, "Filter \"$fname\" tidak aktif pada konfigurasi saat ini — simulasi akan identik.")
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Simulasikan tanpa \"$fname\"?")
            .setMessage("Backtest dijalankan ulang dengan data, pair, timeframe, periode, modal, " +
                "biaya, dan aturan keluar yang SAMA — hanya filter \"$fname\" yang dinonaktifkan.\n\n" +
                "Konfigurasi aktif TIDAK diubah. Hasil adalah simulasi historis, bukan jaminan kinerja masa depan.")
            .setPositiveButton("Jalankan") { _, _ -> runWhatIf(fid) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runWhatIf(fid: String) {
        val fname = FILTER_DEFS.find { it.id == fid }?.name ?: fid
        val p0 = App.params ?: return
        val before = App.result ?: return
        val candles0 = App.candles.toList()
        whatIfCancelled = false
        val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Simulasi what-if…")
            .setMessage("Menjalankan ulang mesin backtest tanpa filter \"$fname\"…")
            .setNegativeButton("Batal") { _, _ -> whatIfCancelled = true }
            .setCancelable(false)
            .show()
        runBg {
            try {
                // Salinan parameter: hanya filter terpilih yang dimatikan. Aktif tak tersentuh.
                val p2 = p0.copy(filters = p0.filters.map { if (it.name == fid) it.copy(enabled = false) else it })
                if (whatIfCancelled) {
                    runOnUiThread { try { prog.dismiss() } catch (e: Exception) { }; snack(this, "Simulasi dibatalkan.") }
                    return@runBg
                }
                val raw = candles0.map {
                    mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
                }
                val after = runBacktest(raw, p2)
                runOnUiThread {
                    try { prog.dismiss() } catch (e: Exception) { }
                    if (whatIfCancelled) snack(this, "Simulasi dibatalkan.")
                    else showWhatIfResult(fname, before, after)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    try { prog.dismiss() } catch (e: Exception) { }
                    snack(this, "Simulasi gagal: ${e.message}")
                }
            }
        }
    }

    /** Tabel berdampingan + selisih, definisi metrik sama di kedua kolom. */
    private fun showWhatIfResult(fname: String, before: BacktestResult, after: BacktestResult) {
        if (after.error != null) {
            snack(this, "Simulasi error: ${after.error}")
            return
        }
        fun row(label: String, a: String, b: String) = "$label\n  Awal: $a\n  Tanpa $fname: $b"
        val dTr = after.totalTrades - before.totalTrades
        val dNet = after.netProfit - before.netProfit
        val msg = StringBuilder()
        msg.append(row("Transaksi", "${before.totalTrades}", "${after.totalTrades}"))
        msg.append("\n").append(row("Win rate", "${App.fmt(before.winRate, 1)}%", "${App.fmt(after.winRate, 1)}%"))
        msg.append("\n").append(row("Profit factor", App.fmtD(before.profitFactor), App.fmtD(after.profitFactor)))
        msg.append("\n").append(row("Net", mQ(before.netProfit, before.asset), mQ(after.netProfit, after.asset)))
        msg.append("\n").append(row("Max DD", "${App.fmt(before.maxDrawdownPercent)}%", "${App.fmt(after.maxDrawdownPercent)}%"))
        msg.append("\n\nSelisih: ${if (dTr >= 0) "+" else ""}$dTr transaksi · " +
            "${if (dNet >= 0) "+" else ""}${mQ(dNet, before.asset)} net.")
        msg.append("\n\nSimulasi historis atas data yang sama — bukan jaminan kinerja masa depan. " +
            "Konfigurasi aktif tidak berubah.")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Hasil tanpa \"$fname\"")
            .setMessage(msg.toString())
            .setPositiveButton("Tutup", null)
            .show()
    }

    // ---------- V14 F4: peta waktu ----------
    private fun paintHeat(trades: List<Trade>) {
        try {
            val hq = if (mixedQuotes(trades)) "XXX"
            else quoteCurrency(trades.firstOrNull()?.asset ?: App.result?.asset ?: "")
            val hm = buildTimeHeatmap(trades)
            val sum = findViewById<TextView>(R.id.heatSummary)
            val hoursBox = findViewById<android.widget.LinearLayout>(R.id.heatHours)
            val daysBox = findViewById<android.widget.LinearLayout>(R.id.heatDays)
            hoursBox.removeAllViews(); daysBox.removeAllViews()
            if (hm.totalTrades == 0) {
                sum.text = "Belum ada transaksi — peta waktu kosong."
                return
            }
            val bh = bestBucket(hm.hours); val wh = worstBucket(hm.hours)
            val bd = bestBucket(hm.days); val wd = worstBucket(hm.days)
            sum.text = "Jam terbaik ${bh?.label ?: "—"} (${bh?.let { heatStat(it, hq) } ?: ""}) · " +
                "terburuk ${wh?.label ?: "—"} (${wh?.let { heatStat(it, hq) } ?: ""})\n" +
                "Hari terbaik ${bd?.label ?: "—"} (${bd?.let { heatStat(it, hq) } ?: ""}) · " +
                "terburuk ${wd?.label ?: "—"} (${wd?.let { heatStat(it, hq) } ?: ""})"
            val maxAbs = hm.hours.maxOf { abs(it.net) }.takeIf { it > 0 } ?: 1.0
            for (row in 0 until 4) {
                val lr = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                for (c in 0 until 6) lr.addView(heatCell(hm.hours[row * 6 + c], maxAbs, hq))
                hoursBox.addView(lr)
            }
            val maxAbsD = hm.days.maxOf { abs(it.net) }.takeIf { it > 0 } ?: 1.0
            for (b in hm.days) daysBox.addView(heatCell(b, maxAbsD, hq))
        } catch (e: Exception) { /* layout belum siap */ }
    }

    private fun heatStat(b: TimeBucket, q: String): String =
        "${b.count} trade · ${mQ(b.net, q)} · ${b.winRate?.let { App.fmt(it, 1) + "%" } ?: "—"}"

    private fun heatCell(b: TimeBucket, maxAbs: Double, q: String): TextView {
        val tv = TextView(this)
        val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(2, 2, 2, 2)
        tv.layoutParams = lp
        tv.gravity = android.view.Gravity.CENTER
        tv.setPadding(2, 8, 2, 8)
        tv.textSize = 10f
        tv.text = "${b.label}\n${if (b.count == 0) "—" else mQ(b.net, q)}"
        tv.setTextColor(0xFFE8EDF2.toInt())
        val intensity = if (b.count == 0 || maxAbs <= 0) 0
        else ((abs(b.net) / maxAbs * 120).toInt() + 40).coerceIn(40, 160)
        tv.setBackgroundColor(when {
            b.count == 0 -> 0xFF171D26.toInt()
            b.net > 0 -> (intensity shl 24) or 0x000ECB81
            b.net < 0 -> (intensity shl 24) or 0x00F6465D
            else -> 0xFF1C232E.toInt()
        })
        tv.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(if (b.label.length <= 2 && b.label.all { it.isDigit() }) "Jam ${b.label} (UTC)" else b.label)
                .setMessage(if (b.count == 0) "Tidak ada transaksi keluar pada kelompok ini."
                else "Transaksi: ${b.count}\nNet: ${mQ(b.net, q)}\nWin: ${b.winRate?.let { App.fmt(it, 1) + "%" } ?: "—"}")
                .setPositiveButton("Tutup", null)
                .show()
        }
        return tv
    }

    // ---------- V14 F8: detail transaksi + catatan terkait ----------
    private fun openTradeDetail(t: Trade) {
        val key = tradeKeyOf(t)
        val notes = notesForTrade(App.journal, key)
        val msg = StringBuilder()
        msg.append("${t.direction} ${t.asset}${if (t.timeframe.isNotEmpty()) " · ${t.timeframe}" else ""}\n")
        msg.append("Masuk ${App.fmtT(t.entryTime)} @ ${App.fmt(t.entry, 4)}\n")
        msg.append("Keluar ${App.fmtT(t.exitTime)} @ ${App.fmt(t.exit, 4)}\n")
        msg.append("SL ${App.fmt(t.stopLoss, 4)} · TP ${App.fmt(t.takeProfit, 4)}\n")
        msg.append("R ${App.fmt(t.rMultiple)} · ${t.result} · PnL ${mQ(t.pnl, t.asset)}\n\n")
        if (notes.isEmpty()) {
            msg.append("Belum ada catatan jurnal untuk transaksi ini.")
        } else {
            msg.append("Catatan jurnal (${notes.size}):\n")
            for (n in notes.take(5)) {
                msg.append("• ${(if (n.title.isNotEmpty()) n.title else "(tanpa judul)").take(60)}")
                if (n.body.isNotEmpty()) msg.append(" — ${n.body.take(80)}")
                msg.append("\n")
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Detail transaksi")
            .setMessage(msg.toString())
            .setPositiveButton("Tambah catatan") { _, _ -> openJournalPrefilled(t) }
            .setNeutralButton("Buka Jurnal") { _, _ -> openJournalPrefilled(null) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun openJournalPrefilled(t: Trade?) {
        val i = Intent(this, JournalActivity::class.java)
        if (t != null) {
            i.putExtra("prefill_pair", t.asset)
            i.putExtra("prefill_tradeKey", tradeKeyOf(t))
        }
        startActivity(i)
    }

    private fun exportTrades() {
        if (tradeRows.isEmpty()) { snack(this, "Tidak ada transaksi."); return }
        try {
            val dir = File(filesDir, "shared").apply { mkdirs() }
            val f = File(dir, "trades.csv")
            f.writeText("n,pair,time,direction,entry,exit,profit,r,result,strategy,timeframe\n" +
                tradeRows.mapIndexed { i, t ->
                    "${i + 1},${t.asset},${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t.exitTime))},${t.direction},${t.entry},${t.exit},${t.pnl},${t.rMultiple},${t.result},${t.strategy},${t.timeframe}"
                }.joinToString("\n"))
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val sh = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sh, "Bagikan CSV"))
        } catch (e: Exception) { snack(this, "Export gagal: ${e.message}") }
    }
}
