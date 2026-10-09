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
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BacktestActivity : BaseActivity(R.id.nav_lab) {
    override val contentLayout = R.layout.activity_backtest
    override val showBack = true
    private var cancelled = false
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
        toggle(R.id.segChart, listOf(R.id.segChartPrice, R.id.segChartEquity), 0) { showEquity(it == 1) }
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
        findViewById<View>(R.id.btnPresetConservative).setOnClickListener { confirmPreset("conservative") }
        findViewById<View>(R.id.btnPresetBalanced).setOnClickListener { confirmPreset("balanced") }
        findViewById<View>(R.id.btnPresetAggressive).setOnClickListener { confirmPreset("aggressive") }
        bindNum(R.id.inCapital, App.capital.toString()) { App.capital = it.toDoubleOrNull() ?: 1000.0 }
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
        } catch (e: Exception) { /* layout belum siap */ }
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
                cnt.text = "${staged.size} pairs selected"
            }
            list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                val q = search.text.toString().uppercase()
                val vis = App.universe.filter { q.isEmpty() || it.contains(q) }
                val s = vis.getOrNull(pos) ?: return@OnItemClickListener
                if (list.isItemChecked(pos)) staged.add(s) else staged.remove(s)
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
        return true
    }

    private fun doSingle() {
        if (!dateRangeOk()) return
        cancelled = false
        ChartPaint.overlay(chartRoot(), loading = true, msg = "Menyiapkan backtest…", showRetry = false)
        setStatus("Menyiapkan…")
        runBg {
            val tAll = System.nanoTime()
            try {
                val p = App.buildParams(App.pair)
                setStatus("Mengambil data ${p.asset}…")
                val (candles, meta) = if (App.csv != null && App.csv!!.size >= 60)
                    App.csv!! to FetchMeta("csv", p.asset, p.timeframe, App.limit, App.csv!!.size, "csv-file", "CSV")
                else getCandles(App.provider, p.asset, p.timeframe, App.limit).let { it.candles to it.meta }
                if (cancelled) return@runBg
                App.candles = candles; App.params = p
                setStatus("Backtest ${candles.size}c…")
                val t0 = System.nanoTime()
                val res = runBacktest(candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }, p)
                val engMs = (System.nanoTime() - t0) / 1e6
                if (cancelled) return@runBg
                App.result = res
                val st = classifyResult(res)
                App.saveApplied("backtest-single", p, listOf(p.asset), st, res.error == null)
                App.saveLastBt("single", listOf(p.asset), p, App.limit, res.diag.dateFilteredCount, res.totalTrades, engMs.toLong(), ((System.nanoTime() - tAll) / 1e6).toLong(), st)
                if (res.error == null) {
                    App.addHist(res.trades)
                    res.trades.lastOrNull()?.let { lt ->
                        App.pushSignal(Sig(res.asset, res.timeframe, lt.direction, lt.entry, lt.stopLoss, lt.takeProfit, lt.entryTime, "backtest", "b${lt.entryTime}${res.asset}"))
                    }
                }
                runOnUiThread { paintQuick(); paintChart(); paintResult() }
                setStatus(if (res.error != null) "$st: ${res.error}" else "Selesai ${engMs.toLong()}ms · ${res.totalTrades} trade · Net ${App.fmtMoney(res.netProfit)} · cache:${meta.cacheUsed}", res.error != null)
            } catch (e: Exception) {
                setStatus("FAILED: ${e.message}", true)
            }
        }
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
        ChartPaint.overlay(chartRoot(), loading = true, msg = "Menyiapkan multi-pair…", showRetry = false)
        runBg {
            val tAll = System.nanoTime()
            val p: BacktestParams
            try { p = App.buildParams(sel.first()) } catch (e: Exception) { setStatus("FAILED: ${e.message}", true); return@runBg }
            if (App.csv != null && App.csv!!.size >= 60) setStatus("CSV diabaikan saat multi-pair.")
            val items = sel.map { Triple(it, "·", 0xFF8B95A5.toInt()) }.toMutableList()
            val rows = ArrayList<PairRow>()
            val cache = HashMap<String, Pair<List<Candle>, BacktestResult>>()
            var done = 0
            paintProg(items, done)
            for ((idx, sym) in sel.withIndex()) {
                if (cancelled) { items[idx] = Triple(sym, "·", 0xFF8B95A5.toInt()); paintProg(items, done); break }
                items[idx] = Triple(sym, "…", 0xFF4C8DFF.toInt())
                setStatus("Backtest $sym… (${done + 1}/${sel.size})")
                paintProg(items, done)
                try {
                    val fr = getCandles(App.provider, sym, p.timeframe, App.limit)
                    val t0 = System.nanoTime()
                    val res = runBacktest(fr.candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }, p.copy(asset = sym))
                    val engMs = (System.nanoTime() - t0) / 1e6
                    val res2 = res.copy(diag = res.diag.copy(limitRequested = App.limit, received = fr.meta.received, source = fr.meta.source, cacheUsed = fr.meta.cacheUsed))
                    cache[sym] = fr.candles to res2
                    rows.add(PairRow(sym, res2))
                    val st = pairStatusOf(PairRow(sym, res2))
                    items[idx] = Triple(sym, if (st == "SUCCESS" || st == "NO TRADES") "✓ $st · ${res2.totalTrades} tr · ${engMs.toLong()}ms" else "✕ $st",
                        if (st == "SUCCESS") 0xFF0ECB81.toInt() else if (st == "NO TRADES") 0xFFFFB800.toInt() else 0xFFF6465D.toInt())
                } catch (e: Exception) {
                    rows.add(PairRow(sym, null, e.message))
                    items[idx] = Triple(sym, "✕ NO DATA", 0xFFF6465D.toInt())
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
            if (first != null) { App.candles = cache[first.sym]!!.first; App.params = p.copy(asset = first.sym) }
            App.saveLastBt("multi", sel, p, App.limit, first?.res?.diag?.dateFilteredCount ?: 0, all.size, 0, ((System.nanoTime() - tAll) / 1e6).toLong(), overall)
            if (first?.res != null && first.res.error == null) {
                App.addHist(first.res.trades)
                first.res.trades.lastOrNull()?.let { lt ->
                    App.pushSignal(Sig(first.sym, p.timeframe, lt.direction, lt.entry, lt.stopLoss, lt.takeProfit, lt.entryTime, "backtest", "b${lt.entryTime}${first.sym}"))
                }
            }
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
        ChartPaint.setup(findViewById(R.id.chart), OhlcMarker(this@BacktestActivity, emptyList()))
        findViewById<LineChart>(R.id.equity).apply {
            description.isEnabled = false
            setBackgroundColor(0xFF0B0E14.toInt())
            legend.isEnabled = false
            axisLeft.isEnabled = false
            axisRight.textColor = 0xFF8B95A5.toInt()
            xAxis.isEnabled = false
        }
        ChartPaint.overlay(findViewById<View>(R.id.chartOverlay)?.parent as View,
            loading = false, msg = "Belum ada data — tekan Run Backtest.", showRetry = false)
    }

    private fun chartRoot(): View = findViewById<View>(R.id.chartOverlay)?.parent as View

    private fun paintChart() {
        val c = findViewById<CombinedChart>(R.id.chart)
        val candles = App.candles
        val r = App.result
        val drawn = ChartPaint.paint(c, candles,
            if (r != null && r.error == null) r.trades else null) { OhlcMarker(this, it) }
        if (!drawn) {
            ChartPaint.overlay(chartRoot(), loading = false,
                msg = if (r?.error != null) "${classifyResult(r)}: ${r.error}" else "Belum ada data — tekan Run Backtest.",
                showRetry = false)
        } else {
            refreshChartOverlay()
        }
        val last = candles.lastOrNull()
        findViewById<TextView>(R.id.chartMeta).text = if (last != null)
            "${App.params?.asset} · ${App.params?.timeframe} · ${candles.size}c · ${App.fmt(last.c, if (last.c > 1000) 2 else 4)}"
        else ""
        // equity
        if (App.multiRows.isNotEmpty()) {
            val cmb = buildCombinedEquity(App.multiRows, App.params?.initialCapital ?: 1000.0)
            paintEquity(cmb.curve.map { it.equity }, "Combined equity — ${cmb.n} trades")
        } else {
            val eq = App.result?.equityCurve ?: emptyList()
            paintEquity(eq.map { it.equity }, if (App.result != null && App.result?.error == null) "${App.result?.asset} equity" else "")
        }
    }

    private fun paintEquity(values: List<Double>, cap: String) {
        findViewById<TextView>(R.id.equityCap).text = cap.ifEmpty { "Equity — NO TRADES" }
        val v = findViewById<LineChart>(R.id.equity)
        val clean = values.filter { it.isFinite() }
        if (clean.isEmpty()) { v.clear(); v.invalidate(); return }
        // Ruang atas-bawah 15% + penjaga rentang nol (F1): garis tak menempel/potong.
        val (lo, hi) = equityPlotRange(clean)
        v.axisRight.apply {
            axisMinimum = lo
            axisMaximum = hi
            setDrawGridLines(true)
            gridColor = 0xFF232B36.toInt()
            setLabelCount(4, false)
        }
        val e = ArrayList<com.github.mikephil.charting.data.Entry>()
        clean.forEachIndexed { i, x -> e.add(com.github.mikephil.charting.data.Entry(i.toFloat(), x.toFloat())) }
        val ds = LineDataSet(e, "Equity").apply {
            setDrawValues(false); setDrawCircles(false)
            color = 0xFF0ECB81.toInt(); lineWidth = 2.5f
            setDrawFilled(true); fillColor = 0x330ECB81.toInt()
            mode = LineDataSet.Mode.CUBIC_BEZIER
            axisDependency = YAxis.AxisDependency.RIGHT
        }
        v.data = com.github.mikephil.charting.data.LineData(ds)
        v.notifyDataSetChanged()
        v.animateX(400)
        v.invalidate()
    }

    private fun showEquity(show: Boolean) {
        findViewById<CombinedChart>(R.id.chart).visibility = if (show) View.GONE else View.VISIBLE
        findViewById<LineChart>(R.id.equity).visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            ChartPaint.overlay(chartRoot(), loading = false, msg = null, showRetry = false)
        } else {
            refreshChartOverlay()
        }
    }

    /** Status overlay grafik harga sesuai data saat ini (tanpa menggambar ulang). */
    private fun refreshChartOverlay() {
        val r = App.result
        val hasData = App.candles.isNotEmpty()
        ChartPaint.overlay(chartRoot(), loading = false,
            msg = if (hasData) null
            else if (r?.error != null) "${classifyResult(r)}: ${r.error}"
            else "Belum ada data — tekan Run Backtest.",
            showRetry = false)
    }

    private fun paintResult() {
        val r = App.result
        if (r == null) {
            findViewById<TextView>(R.id.sumLine).text = "Belum ada hasil — tekan Run."
            findViewById<View>(R.id.whyBox).visibility = View.GONE
            return
        }
        if (r.error != null) {
            findViewById<TextView>(R.id.heroNet).text = classifyResult(r)
            findViewById<TextView>(R.id.heroNet).setTextColor(0xFFF6465D.toInt())
            findViewById<TextView>(R.id.heroSub).text = r.error
            findViewById<TextView>(R.id.sumLine).text = "${r.asset} · ${classifyResult(r)}"
            paintKv(emptyList()); paintPairs(emptyList()); paintTrades(); paintTech(r); paintWhy(r)
            return
        }
        val noTr = r.totalTrades <= 0
        findViewById<TextView>(R.id.heroNet).apply {
            text = App.fmtMoney(r.netProfit)
            setTextColor(if (noTr) 0xFFE8EDF2.toInt() else if (r.netProfit >= 0) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt())
        }
        findViewById<TextView>(R.id.heroSub).text =
            "${r.asset} ${r.timeframe} · ${r.strategy} · ${if (noTr) "NO TRADES" else "Win " + App.fmt(r.winRate, 1) + "%"} · PF ${App.fmtD(r.profitFactor)} · DD ${App.fmt(r.maxDrawdownPercent)}%"
        findViewById<TextView>(R.id.sumLine).text =
            "Net ${App.fmt(r.netProfitPercent)}% · Final ${App.fmtMoney(r.finalCapital)} · Expectancy ${App.fmtMoney(r.expectancy)}"
        paintKv(listOf(
            "Total Trades" to r.totalTrades.toString(),
            "Win Rate" to if (noTr) "NO TRADES" else App.fmt(r.winRate, 1) + "%",
            "Profit Factor" to App.fmtD(r.profitFactor),
            "Max Drawdown" to App.fmt(r.maxDrawdownPercent) + "%",
            "Avg Win" to App.fmtMoney(r.averageWin),
            "Avg Loss" to App.fmtMoney(r.averageLoss),
            "Tersaring filter" to r.filtered.toString(),
            "Exposure" to App.fmt(r.exposure, 1) + "%"
        ))
        paintPairs(App.multiRows)
        tradeRows = if (App.multiRows.isNotEmpty()) App.multiRows.filter { it.res?.error == null }.flatMap { it.res!!.trades }.sortedBy { it.exitTime } else r.trades
        tradePage = 0
        paintTrades()
        paintTech(r)
        paintWhy(r)
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
            "Net ${App.fmtMoney(agg.netProfit)} · PF ${App.fmtD(agg.profitFactor)} · DD ${App.fmt(agg.maxDD)}%", agg.totalTrades.toString()))
        rows.forEachIndexed { i, row ->
            val st = pairStatusOf(row)
            items.add(SigItem(
                if (st == "SUCCESS") "✓" else if (st == "NO TRADES") "○" else "✕",
                "${row.sym}  $st",
                if (row.res?.error == null && row.err == null)
                    "Net ${App.fmtMoney(row.res!!.netProfit)} · ${if (row.res.totalTrades > 0) App.fmt(row.res.winRate, 1) + "%" else "NO TRADES"} · ${row.res.totalTrades} tr — klik"
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
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tradeList).apply {
            vertical(this@BacktestActivity)
            adapter = SigAdapter(tradeRows.drop(tradePage * per).take(per).mapIndexed { k, t ->
                SigItem(t.direction, "${tradePage * per + k + 1}. ${t.asset}",
                    "${App.fmtT(t.exitTime)} · in ${App.fmt(t.entry, 4)} out ${App.fmt(t.exit, 4)} · R ${App.fmt(t.rMultiple)} · ${t.result}",
                    App.fmtMoney(t.pnl), t.asset)
            })
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
                "Dievaluasi" to d.evaluatedBars.toString(),
                "Signal" to d.signalsRaw.toString(),
                "Tersaring" to d.filteredOut.toString(),
                "Cache" to (d.cacheUsed ?: "—")
            ))
        }
    }

    /** FITUR 1: "Mengapa Tidak Ada Transaksi?" hanya saat nol transaksi (murni,
     *  dihitung dari diagnostik backtest yang sedang ditampilkan). */
    private fun paintWhy(r: BacktestResult) {
        try {
            val box = findViewById<View>(R.id.whyBox)
            val body = findViewById<TextView>(R.id.whyBody)
            val txt = explainNoTrades(r)
            if (txt.isEmpty()) { box.visibility = View.GONE; body.text = "" }
            else { box.visibility = View.VISIBLE; body.text = txt }
        } catch (e: Exception) { /* layout belum siap */ }
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
