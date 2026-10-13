package com.aether.signal.premium.ui

import android.content.Intent
import android.widget.*
import com.aether.signal.premium.ai.Sig
import com.aether.signal.premium.data.*
import com.aether.signal.premium.engine.*
import com.aether.signal.premium.ui.charts.CandleChartView
import com.aether.signal.premium.ui.charts.EquityView
import java.text.SimpleDateFormat
import java.util.*

class BacktestActivity : BaseActivity("lab") {
    override fun subtitle() = ""
    private var status: TextView? = null
    private var quickMeta: TextView? = null
    private var quickBox: LinearLayout? = null
    private var progBox: LinearLayout? = null
    private var progCount: TextView? = null
    private var chartView: CandleChartView? = null
    private var equityView: EquityView? = null
    private var equityCap: TextView? = null
    private var chartMeta: TextView? = null
    private var cancelled = false
    private var tradePage = 0
    private var tradeRows: List<Trade> = emptyList()
    private var tradeTable: LinearLayout? = null
    private var tradePageTv: TextView? = null
    private var sumBox: LinearLayout? = null
    private var kpiBox: LinearLayout? = null
    private var overallBox: LinearLayout? = null
    private var pairsBox: LinearLayout? = null
    private var techBox: LinearLayout? = null
    private var chipsBox: LinearLayout? = null
    private var staged: LinkedHashSet<String> = LinkedHashSet()

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        appBar.showBack(true)
        // KONSOL: aksi utama di atas (hierarchy: aksi → hasil)
        val q = panel(this); body.addView(q)
        eyebrow(q, "Backtest console")
        quickMeta = TextView(this).apply { setTextColor(C.MUT); textSize = T.SMALL; typeface = android.graphics.Typeface.MONOSPACE }
        q.addView(quickMeta)
        quickBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        q.addView(quickBox)
        val seg = SegmentedControl(this)
        q.addView(seg)
        seg.setOptions(listOf("Single Pair", "Multi Pair"), if (App.mode == "multi") 1 else 0) {
            App.mode = if (it == 1) "multi" else "single"; recreate()
        }
        val run = row(q)
        val rb = tbtn(this, "▶  Run Backtest", 0) { if (App.mode == "multi") doMulti() else doSingle() }
        rb.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        run.addView(rb)
        run.addView(smBtn(this, "Batal") { cancelled = true })
        status = statusTv(this); q.addView(status)
        progCount = TextView(this).apply { setTextColor(C.TXT); textSize = T.SMALL; typeface = android.graphics.Typeface.MONOSPACE }
        q.addView(progCount)
        progBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        q.addView(progBox)
        refreshQuick()
        // KONFIGURASI (grup collapsible, default strategi+data terbuka)
        section(body, "strategy", "Strategi", true, { App.strategy }) { box ->
            box.addView(fieldLabel(this, "Strategi utama"))
            val ids = STRATEGIES.keys.toList()
            val dd = DropDown(this)
            box.addView(dd)
            dd.setOptions(ids.map { STRATEGIES[it]!!.first }, ids.indexOf(App.strategy).coerceAtLeast(0)) {
                App.strategy = ids[it]; App.saveStrategy(); recreate()
            }
            box.addView(fieldLabel(this, "Preset"))
            val ps = SegmentedControl(this); box.addView(ps)
            ps.setOptions(listOf("Default", "Custom"), if (App.presetCustom) 1 else 0) { App.presetCustom = it == 1; recreate() }
            val cr = row(box)
            cr.addView(fieldWrap("Modal (USDT)", App.capital.toString()) { App.capital = it.toDoubleOrNull() ?: 1000.0 })
            cr.addView(fieldWrap("Risiko %", App.riskPct.toString()) { App.riskPct = it.toDoubleOrNull() ?: 1.0 })
            if (App.presetCustom) {
                box.addView(fieldLabel(this, "Mode combo"))
                val modes = listOf("Single", "OR", "AND", "MAJORITY")
                val ms = SegmentedControl(this); box.addView(ms)
                ms.setOptions(modes, modes.indexOf(if (App.comboMode.isEmpty()) "Single" else App.comboMode).coerceAtLeast(0)) {
                    App.comboMode = if (it == 0) "" else modes[it]; recreate()
                }
                if (App.comboMode.isNotEmpty()) {
                    desc(box, "Strategi 2+ — minimal 1 tambahan.")
                    for (m in strategyList().filter { it.id != App.strategy }) {
                        val cb = CheckBox(this).apply { text = m.name; isChecked = App.comboExtra.contains(m.id); setTextColor(C.MUT); textSize = T.SMALL }
                        cb.setOnCheckedChangeListener { _, on -> if (on) App.comboExtra.add(m.id) else App.comboExtra.remove(m.id) }
                        box.addView(cb)
                    }
                }
            }
        }
        section(body, "data", "Data & Pasangan", true, {
            if (App.mode == "multi") "${App.multiSel.size} pairs · ${App.timeframe}" else "${App.pair} · ${App.timeframe}"
        }) { box ->
            if (App.mode == "single") {
                box.addView(fieldLabel(this, "Pair"))
                box.addView(tbtn(this, App.pair + "  ▾", 1) { pickSinglePair() })
            } else {
                box.addView(fieldLabel(this, "Pairs — ${App.multiSel.size} selected"))
                chipsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(chipsBox)
                paintChips()
                val br = row(box)
                val sb = tbtn(this, "Select Pairs", 0) { openMultiSheet() }
                sb.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                br.addView(sb)
                br.addView(smBtn(this, "Reload") { reloadUniverse(null) })
            }
            val gr = row(box)
            gr.addView(dropWrap("Provider", listOf("binance", "bybit", "yahoo", "demo"), listOf("binance", "bybit", "yahoo", "demo").indexOf(App.provider)) {
                App.provider = listOf("binance", "bybit", "yahoo", "demo")[it]; App.universe = emptyList(); recreate()
            })
            gr.addView(dropWrap("Timeframe", listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w"), listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w").indexOf(App.timeframe).coerceAtLeast(0)) {
                App.timeframe = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w")[it]; recreate()
            })
            box.addView(fieldLabel(this, "Candle"))
            val lims = listOf(200, 500, 1000)
            val ls = SegmentedControl(this); box.addView(ls)
            ls.setOptions(lims.map { it.toString() }, lims.indexOf(App.limit).coerceAtLeast(0)) { App.limit = lims[it] }
        }
        section(body, "filters", "Filter", false, { "${App.filterOn.count { it.value }} aktif" }) { box ->
            val pr = row(box)
            pr.addView(smBtn(this, "Longgar") { applyPreset(listOf("trend", "volume")); recreate() })
            pr.addView(smBtn(this, "Seimbang") { applyPreset(listOf("trend", "htf", "volume", "adx", "rsi", "cooldown")); recreate() })
            pr.addView(smBtn(this, "Ketat") { applyPreset(FILTER_DEFS.map { it.id }); recreate() })
            for ((g, ids) in listOf(
                "Structure" to listOf("ms", "sr"), "Liquidity" to listOf("liq"),
                "Order Flow" to listOf("cooldown", "dup"), "Trend" to listOf("trend", "ema", "htf"),
                "Momentum" to listOf("adx", "rsi"), "Volume" to listOf("volume", "atr_vol", "min_vol", "max_vol"),
                "Risk" to listOf("session")
            )) {
                val on = ids.count { App.filterOn[it] == true }
                section(box, "fg_$g", g, false, { "$on/${ids.size}" }) { gb ->
                    for (id in ids) {
                        val cb = CheckBox(this).apply {
                            text = FILTER_DEFS.find { it.id == id }?.name ?: id
                            isChecked = App.filterOn[id] == true; setTextColor(C.MUT); textSize = T.SMALL
                        }
                        cb.setOnCheckedChangeListener { _, v -> App.filterOn[id] = v; recreate() }
                        gb.addView(cb)
                    }
                }
            }
            val nr = row(box)
            nr.addView(fieldWrap("Vol ×", App.fVol.toString()) { App.fVol = it.toDoubleOrNull() ?: 1.0 })
            nr.addView(fieldWrap("ADX", App.fAdx.toString()) { App.fAdx = it.toDoubleOrNull() ?: 20.0 })
            val nr2 = row(box)
            nr2.addView(fieldWrap("RSI L≤", App.fRsiHi.toString()) { App.fRsiHi = it.toDoubleOrNull() ?: 72.0 })
            nr2.addView(fieldWrap("RSI S≥", App.fRsiLo.toString()) { App.fRsiLo = it.toDoubleOrNull() ?: 28.0 })
            val nr3 = row(box)
            nr3.addView(fieldWrap("ATRmin", App.fAtrMin.toString()) { App.fAtrMin = it.toDoubleOrNull() ?: 0.3 })
            nr3.addView(fieldWrap("ATRmax", App.fAtrMax.toString()) { App.fAtrMax = it.toDoubleOrNull() ?: 5.0 })
            box.addView(fieldLabel(this, "Sesi UTC"))
            val sess = listOf("all", "asia", "london", "ny", "asia_london", "london_ny")
            val sd = DropDown(this); box.addView(sd)
            sd.setOptions(sess, sess.indexOf(App.fSession).coerceAtLeast(0)) { App.fSession = sess[it] }
            val nr4 = row(box)
            nr4.addView(fieldWrap("Cooldown", App.fCool.toString()) { App.fCool = it.toDoubleOrNull() ?: 3.0 })
            nr4.addView(fieldWrap("SR buf", App.fSr.toString()) { App.fSr = it.toDoubleOrNull() ?: 0.3 })
        }
        section(body, "risk", "Risiko & Biaya", false, { "SL ${App.slPct}% · TP ${App.tpPct}%" }) { box ->
            val r1 = row(box)
            r1.addView(fieldWrap("Leverage", App.leverage.toString()) { App.leverage = it.toIntOrNull() ?: 1 })
            r1.addView(fieldWrap("Fee %", App.feePct.toString()) { App.feePct = it.toDoubleOrNull() ?: 0.05 })
            val r2 = row(box)
            r2.addView(fieldWrap("Slip %", App.slipPct.toString()) { App.slipPct = it.toDoubleOrNull() ?: 0.02 })
            r2.addView(fieldWrap("Max hold", App.maxHolding.toString()) { App.maxHolding = it.toIntOrNull() ?: 100 })
            val r3 = row(box)
            r3.addView(fieldWrap("SL %", App.slPct.toString()) { App.slPct = it.toDoubleOrNull() ?: 1.5 })
            r3.addView(fieldWrap("TP %", App.tpPct.toString()) { App.tpPct = it.toDoubleOrNull() ?: 3.0 })
            box.addView(fieldLabel(this, "Level ATR?"))
            val us = SegmentedControl(this); box.addView(us)
            us.setOptions(listOf("SL/TP %", "ATR 14"), if (App.useAtr) 1 else 0) { App.useAtr = it == 1 }
        }
        section(body, "advanced", "Lanjutan", false, { if (App.csv != null) "CSV ${App.csv!!.size}c" else "Tanggal · CSV" }) { box ->
            box.addView(fieldLabel(this, "Dari (yyyy-MM-dd, kosong = awal)"))
            box.addView(dateInput(if (App.fromDate > 0) dateStr(App.fromDate) else "") { App.fromDate = it })
            box.addView(fieldLabel(this, "Sampai (kosong = akhir)"))
            box.addView(dateInput(if (App.toDate > 0) dateStr(App.toDate) else "") { App.toDate = it })
            val cr = row(box)
            cr.addView(smBtn(this, "Import CSV") { pickCsv() })
            cr.addView(smBtn(this, "Hapus") { App.csv = null; toast(this, "CSV dihapus."); recreate() })
            desc(box, if (App.csv != null) "CSV aktif (${App.csv!!.size}c) — single-pair saja." else "CSV tidak aktif.")
        }
        // GRAFIK
        section(body, "chart", "Grafik", true, { "" }) { box ->
            chartMeta = TextView(this).apply { setTextColor(C.DIM); textSize = T.CAP; typeface = android.graphics.Typeface.MONOSPACE }
            box.addView(chartMeta)
            val tr = row(box)
            val bP = smBtn(this, "Price") { chartView?.visibility = android.view.View.VISIBLE; equityView?.visibility = android.view.View.GONE }
            val bE = smBtn(this, "Equity") { chartView?.visibility = android.view.View.GONE; equityView?.visibility = android.view.View.VISIBLE }
            tr.addView(bP); tr.addView(bE)
            chartView = CandleChartView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(this, 360)) }
            box.addView(chartView)
            equityCap = TextView(this).apply { setTextColor(C.MUT); textSize = T.CAP }
            box.addView(equityCap)
            equityView = EquityView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(this, 210)); visibility = android.view.View.GONE }
            box.addView(equityView)
            refreshChart()
        }
        // HASIL: hero dulu, detail kemudian
        section(body, "result", "Hasil", true, { "" }) { box ->
            sumBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(sumBox)
            kpiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(kpiBox)
            overallBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(overallBox)
            pairsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(pairsBox)
            val pr = row(box)
            pr.addView(smBtn(this, "‹") { tradePage = maxOf(0, tradePage - 1); paintTrades() })
            tradePageTv = TextView(this).apply { setTextColor(C.MUT); textSize = T.SMALL; typeface = android.graphics.Typeface.MONOSPACE }
            pr.addView(tradePageTv)
            pr.addView(smBtn(this, "›") { tradePage++; paintTrades() })
            tradeTable = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(tradeTable)
            techBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(techBox)
            refreshResult()
        }
    }

    private fun fieldWrap(label: String, value: String, onEdit: (String) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        box.addView(fieldLabel(this, label))
        val e = inputNum(this, value)
        e.setOnFocusChangeListener { _, has -> if (!has) onEdit(e.text.toString()) }
        box.addView(e)
        return box
    }

    private fun dropWrap(label: String, opts: List<String>, sel: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        box.addView(fieldLabel(this, label))
        val d = DropDown(this)
        box.addView(d)
        d.setOptions(opts, sel, onPick)
        return box
    }

    private fun dateInput(value: String, onEdit: (Long) -> Unit): EditText {
        val e = EditText(this).apply { setText(value); hint = "yyyy-MM-dd"; setTextColor(C.TXT); setHintTextColor(C.DIM); textSize = T.BODY }
        e.setOnFocusChangeListener { _, has ->
            if (!has) {
                val s = e.text.toString().trim()
                onEdit(if (s.isEmpty()) 0 else try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
                } catch (ex: Exception) { toast(this, "Format tanggal salah."); 0 })
            }
        }
        return e
    }

    private fun dateStr(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ts))
    private fun applyPreset(ids: List<String>) {
        for (id in FILTER_DEFS.map { it.id }) App.filterOn[id] = ids.contains(id)
    }

    private fun pickSinglePair() {
        toast(this, "Memuat daftar pair…")
        Thread {
            val pairs = try {
                if (App.provider == "yahoo") YAHOO_UNIVERSE.keys.toList() else topPairs(App.provider, 50)
            } catch (e: Exception) { listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT") }
            runOnUiThread {
                val sh = Sheet(this)
                sh.title("Pilih Pair")
                val b = sh.content()
                val search = EditText(this).apply { hint = "Cari…"; setTextColor(C.TXT); setHintTextColor(C.DIM) }
                b.addView(search)
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                b.addView(list)
                fun render() {
                    list.removeAllViews()
                    val q = search.text.toString().uppercase()
                    for (s in pairs.filter { q.isEmpty() || it.contains(q) }.take(60)) {
                        val r = InstrumentRow(this)
                        r.bind(s, "", null, if (s == App.pair) "✓ aktif" else "")
                        r.onTap = { App.pair = s; App.savePair(); sh.dismiss(); recreate() }
                        list.addView(r)
                    }
                }
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) = render()
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                render()
                sh.show()
            }
        }.start()
    }

    private fun reloadUniverse(after: (() -> Unit)?) {
        toast(this, "Memuat universe…")
        Thread {
            try {
                App.universe = if (App.provider == "yahoo") YAHOO_UNIVERSE.keys.toList()
                else if (App.provider == "demo") listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT")
                else topPairs(App.provider, 50)
            } catch (e: Exception) { runOnUiThread { toast(this, "Gagal: ${e.message}") }; return@Thread }
            runOnUiThread { after?.invoke(); paintChips() }
        }.start()
    }

    private fun paintChips() {
        val box = chipsBox ?: return
        box.removeAllViews()
        if (App.multiSel.isEmpty()) {
            box.addView(TextView(this).apply { text = "Belum ada pair — tekan Select Pairs."; setTextColor(C.DIM); textSize = T.SMALL })
            return
        }
        for (s in App.multiSel) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { setColor(C.CARD2); cornerRadius = dp(this@BacktestActivity, 16).toFloat() }
                setPadding(dp(this, 10), dp(this, 5), dp(this, 4), dp(this, 5))
            }
            chip.addView(TextView(this).apply { text = s; setTextColor(C.TXT); textSize = T.SMALL; typeface = android.graphics.Typeface.MONOSPACE })
            chip.addView(TextView(this).apply {
                text = "  ×"; setTextColor(C.MUT); textSize = 15f; setPadding(dp(this, 2), 0, dp(this, 2), 0)
                setOnClickListener { App.multiSel.remove(s); App.saveMulti(); paintChips() }
            })
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(0, 0, dp(this, 6), dp(this, 6))
            chip.layoutParams = lp
            box.addView(chip)
        }
    }

    private fun openMultiSheet() {
        val ensure = { done: () -> Unit -> if (App.universe.isEmpty()) reloadUniverse(done) else done() }
        ensure {
            staged = LinkedHashSet(App.multiSel)
            val sh = Sheet(this)
            sh.title("Select Pairs")
            val b = sh.content()
            val search = EditText(this).apply { hint = "Search pair…"; setTextColor(C.TXT); setHintTextColor(C.DIM) }
            b.addView(search)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val cnt = TextView(this).apply { setTextColor(C.MUT); textSize = T.SMALL }
            fun renderList() {
                list.removeAllViews()
                val q = search.text.toString().uppercase()
                for (s in App.universe.filter { q.isEmpty() || it.contains(q) }) {
                    val cb = CheckBox(this).apply { text = s; isChecked = staged.contains(s); setTextColor(C.TXT); textSize = T.BODY }
                    cb.setOnCheckedChangeListener { _, on -> if (on) staged.add(s) else staged.remove(s); cnt.text = "${staged.size} pairs selected" }
                    list.addView(cb)
                }
                cnt.text = "${staged.size} pairs selected"
            }
            val tools = row(b)
            tools.addView(smBtn(this, "All") {
                val q = search.text.toString().uppercase()
                App.universe.filter { q.isEmpty() || it.contains(q) }.forEach { staged.add(it) }
                renderList()
            })
            tools.addView(smBtn(this, "Clear") { staged.clear(); renderList() })
            tools.addView(smBtn(this, "Top 5") { App.universe.take(5).forEach { staged.add(it) }; renderList() })
            b.addView(list)
            b.addView(cnt)
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = renderList()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            renderList()
            val fr = row(b)
            val ab = tbtn(this, "APPLY", 0) { App.multiSel = LinkedHashSet(staged); App.saveMulti(); sh.dismiss(); paintChips() }
            ab.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            fr.addView(ab)
            fr.addView(smBtn(this, "Tutup") { sh.dismiss() })
            sh.show()
        }
    }

    private fun pickCsv() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 41)
        } catch (e: Exception) { toast(this, "Tidak ada file picker: ${e.message}") }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 41 && res == RESULT_OK && data?.data != null) {
            try {
                val txt = contentResolver.openInputStream(data.data!!)!!.bufferedReader().readText()
                App.csv = parseCSV(txt)
                toast(this, "CSV: ${App.csv!!.size} candle.")
            } catch (e: Exception) { App.csv = null; toast(this, "CSV error: ${e.message}") }
            recreate()
        }
    }

    private fun setStatus(m: String, err: Boolean = false) {
        runOnUiThread { status?.text = m; status?.setTextColor(if (err) C.RED else C.MUT) }
    }

    private fun doSingle() {
        cancelled = false
        setStatus("Menyiapkan…")
        Thread {
            val tAll = System.nanoTime()
            try {
                val p = App.buildParams(App.pair)
                val limitReq = App.limit
                setStatus("Mengambil data ${p.asset}…")
                val (candles, meta) = if (App.csv != null && App.csv!!.size >= 60)
                    App.csv!! to FetchMeta("csv", p.asset, p.timeframe, limitReq, App.csv!!.size, "csv-file", "CSV")
                else getCandles(App.provider, p.asset, p.timeframe, limitReq).let { it.candles to it.meta }
                if (cancelled) return@Thread
                App.candles = candles; App.params = p
                setStatus("Backtest ${candles.size}c…")
                val t0 = System.nanoTime()
                val res = runBacktest(candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }, p)
                val engMs = (System.nanoTime() - t0) / 1e6
                if (cancelled) return@Thread
                App.result = res
                val st = classifyResult(res)
                App.saveApplied("backtest-single", p, listOf(p.asset), st, res.error == null)
                App.saveLastBt("single", listOf(p.asset), p, limitReq, res.diag.dateFilteredCount, res.totalTrades, engMs.toLong(), ((System.nanoTime() - tAll) / 1e6).toLong(), st)
                if (res.error == null) {
                    App.addHist(res.trades)
                    res.trades.lastOrNull()?.let { lt ->
                        App.pushSignal(Sig(res.asset, res.timeframe, lt.direction, lt.entry, lt.stopLoss, lt.takeProfit, lt.entryTime, "backtest", "b${lt.entryTime}${res.asset}"))
                    }
                }
                runOnUiThread { refreshQuick(); refreshChart(); refreshResult() }
                setStatus(if (res.error != null) "$st: ${res.error}" else "Selesai ${engMs.toLong()}ms · ${res.totalTrades} trade · Net ${App.fmtMoney(res.netProfit)} · cache:${meta.cacheUsed}", res.error != null)
            } catch (e: Exception) {
                setStatus("FAILED: ${e.message}", true)
            }
        }.start()
    }

    private fun paintProg(items: List<Triple<String, String, String>>, done: Int) {
        runOnUiThread {
            progCount?.text = "$done / ${items.size} completed"
            val box = progBox ?: return@runOnUiThread
            box.removeAllViews()
            for ((sym, icon, note) in items) {
                box.addView(TextView(this).apply {
                    text = "$icon  $sym  ·  $note"; setTextColor(C.MUT); textSize = T.SMALL; typeface = android.graphics.Typeface.MONOSPACE
                })
            }
        }
    }

    private fun doMulti() {
        if (App.universe.isEmpty()) { reloadUniverse { doMulti() }; return }
        val sel = App.universe.filter { App.multiSel.contains(it) } + App.multiSel.filter { !App.universe.contains(it) }
        if (sel.isEmpty()) { setStatus("Please select at least one pair.", true); return }
        cancelled = false
        Thread {
            val tAll = System.nanoTime()
            val p: BacktestParams
            try { p = App.buildParams(sel.first()) } catch (e: Exception) { setStatus("FAILED: ${e.message}", true); return@Thread }
            if (App.csv != null && App.csv!!.size >= 60) setStatus("CSV diabaikan saat multi-pair.")
            val items = sel.map { Triple(it, "·", "waiting") }.toMutableList()
            val rows = ArrayList<PairRow>()
            val cache = HashMap<String, Pair<List<Candle>, BacktestResult>>()
            var done = 0
            paintProg(items, done)
            for ((idx, sym) in sel.withIndex()) {
                if (cancelled) { items[idx] = Triple(sym, "·", "dibatalkan"); paintProg(items, done); break }
                items[idx] = Triple(sym, "…", "processing")
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
                    items[idx] = Triple(sym, if (st == "SUCCESS" || st == "NO TRADES") "✓" else "✕", "$st · ${res2.totalTrades} tr · ${engMs.toLong()}ms")
                } catch (e: Exception) {
                    rows.add(PairRow(sym, null, e.message))
                    items[idx] = Triple(sym, "✕", "NO DATA")
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
            if (first != null && first.res != null && first.res.error == null) {
                val fres = first.res
                App.addHist(fres.trades)
                fres.trades.lastOrNull()?.let { lt ->
                    App.pushSignal(Sig(first.sym, p.timeframe, lt.direction, lt.entry, lt.stopLoss, lt.takeProfit, lt.entryTime, "backtest", "b${lt.entryTime}${first.sym}"))
                }
            }
            runOnUiThread { refreshQuick(); refreshChart(); refreshResult() }
            setStatus("$overall: $okN of ${rows.size} pairs processed successfully", okN == 0)
        }.start()
    }

    private fun refreshQuick() {
        val s = App.strategy
        quickMeta?.text = (if (App.mode == "multi") "MULTI (${App.multiSel.size})" else App.pair) + " · ${App.timeframe} · $s"
        val box = quickBox ?: return
        box.removeAllViews()
        val r = App.result
        if (r == null || r.error != null) {
            box.addView(TextView(this).apply { text = r?.error ?: "Belum ada hasil — tekan Run."; setTextColor(C.MUT); textSize = T.SMALL })
            return
        }
        val noTr = r.totalTrades <= 0
        metricHero(box, "Net profit", App.fmtMoney(r.netProfit), if (noTr) null else r.netProfit >= 0, listOf(
            "Trades" to r.totalTrades.toString(),
            "Win" to if (noTr) "NO TRADES" else App.fmt(r.winRate, 1) + "%",
            "PF" to App.fmtD(r.profitFactor),
            "MaxDD" to App.fmt(r.maxDrawdownPercent) + "%"
        ))
    }

    private fun refreshChart() {
        val cv = chartView ?: return
        cv.candles = App.candles
        cv.trades = App.result?.trades ?: emptyList()
        cv.invalidate()
        val last = App.candles.lastOrNull()
        chartMeta?.text = if (last != null) "${App.params?.asset} · ${App.params?.timeframe} · ${App.candles.size}c · ${App.fmt(last.c, if (last.c > 1000) 2 else 4)}" else ""
        if (App.multiRows.isNotEmpty()) {
            val cmb = buildCombinedEquity(App.multiRows, App.params?.initialCapital ?: 1000.0)
            equityView?.curve = cmb.curve
            equityCap?.text = if (cmb.n > 0) "Combined equity — ${cmb.n} trades" else "Combined equity — NO TRADES"
        } else {
            equityView?.curve = App.result?.equityCurve ?: emptyList()
            equityCap?.text = if (App.result != null && App.result?.error == null) "${App.result?.asset} equity" else ""
        }
        equityView?.invalidate()
    }

    private fun refreshResult() {
        val r = App.result
        sumBox?.removeAllViews(); kpiBox?.removeAllViews(); overallBox?.removeAllViews(); pairsBox?.removeAllViews()
        if (r == null) {
            sumBox?.addView(stateBox(this, "empty", "Belum ada hasil — tekan Run Backtest."))
            paintTrades(); paintTech(null, null)
            return
        }
        if (r.error != null) {
            sumBox?.addView(TextView(this).apply { text = "${r.asset} · ${classifyResult(r)} · ${r.error}"; setTextColor(C.RED); textSize = T.BODY })
            paintTrades(); paintTech(r, null)
            return
        }
        val noTr = r.totalTrades <= 0
        metricHero(sumBox!!, "${r.asset} ${r.timeframe} · ${r.strategy}", App.fmtMoney(r.netProfit) + "  (${App.fmt(r.netProfitPercent)}%)", if (noTr) null else r.netProfit >= 0, listOf(
            "Win" to if (noTr) "NO TRADES" else App.fmt(r.winRate, 1) + "%",
            "PF" to App.fmtD(r.profitFactor),
            "Trades" to r.totalTrades.toString(),
            "MaxDD" to App.fmt(r.maxDrawdownPercent) + "%"
        ))
        eyebrow(overallBox!!, "Rincian")
        table(overallBox!!, listOf("Metrik", "Nilai"), listOf(
            listOf("Win / Loss / Exp", "${r.wins} / ${r.losses} / ${r.expired}"),
            listOf("Tersaring filter", "${r.filtered}"),
            listOf("Gross profit", App.fmtMoney(r.grossProfit)),
            listOf("Gross loss", App.fmtMoney(r.grossLoss)),
            listOf("Avg win", App.fmtMoney(r.averageWin)),
            listOf("Avg loss", App.fmtMoney(r.averageLoss)),
            listOf("Expectancy", App.fmtMoney(r.expectancy)),
            listOf("Avg R", App.fmt(r.averageR) + "R"),
            listOf("Final", App.fmtMoney(r.finalCapital))
        ))
        if (App.multiRows.isNotEmpty()) {
            val rows = App.multiRows
            val agg = aggregateOverall(rows, buildCombinedEquity(rows, App.params?.initialCapital ?: 1000.0).maxDDPct)
            val okN = rows.count { it.res?.error == null }
            eyebrow(pairsBox!!, "Overall — $okN of ${rows.size}")
            metricHero(pairsBox!!, "Net gabungan", App.fmtMoney(agg.netProfit), if (agg.totalTrades == 0) null else agg.netProfit >= 0, listOf(
                "Trades" to agg.totalTrades.toString(),
                "Win" to if (agg.totalTrades == 0) "NO TRADES" else App.fmt(agg.winRate, 1) + "%",
                "PF" to App.fmtD(agg.profitFactor),
                "MaxDD" to App.fmt(agg.maxDD) + "%"
            ))
            eyebrow(pairsBox!!, "Per pair — klik untuk rincian")
            for (row in rows) {
                val st = pairStatusOf(row)
                val t = TextView(this).apply {
                    text = if (row.res?.error == null && row.err == null)
                        "${row.sym}\n${App.fmtMoney(row.res!!.netProfit)} · ${if (row.res.totalTrades > 0) App.fmt(row.res.winRate, 1) + "%" else "NO TRADES"} · ${row.res.totalTrades} tr"
                    else "${row.sym}\n$st · ${(row.err ?: row.res?.error ?: "").take(50)}"
                    setTextColor(if (st == "SUCCESS") C.GREEN else if (st == "NO TRADES") C.AMBER else C.RED)
                    textSize = T.SMALL; typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, dp(this, 8), 0, dp(this, 8))
                }
                val hit = App.multiCache[row.sym]
                if (hit != null && row.res?.error == null) t.setOnClickListener {
                    App.candles = hit.first; App.params = App.params?.copy(asset = row.sym); App.result = hit.second
                    refreshChart(); refreshResult()
                }
                pairsBox!!.addView(t)
                pairsBox!!.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 1); setBackgroundColor(C.LINE) })
            }
        }
        tradeRows = if (App.multiRows.isNotEmpty()) App.multiRows.filter { it.res?.error == null }.flatMap { it.res!!.trades }.sortedBy { it.exitTime } else r.trades
        tradePage = 0
        paintTrades()
        paintTech(r, null)
    }

    private fun paintTrades() {
        val box = tradeTable ?: return
        box.removeAllViews()
        eyebrow(box, "Transaksi · ${tradeRows.size}")
        val per = 25
        val pages = maxOf(1, (tradeRows.size + per - 1) / per)
        tradePage = tradePage.coerceIn(0, pages - 1)
        tradePageTv?.text = "${tradePage + 1} / $pages"
        table(box, listOf("#", "Pair", "Waktu", "Arah", "PnL", "Hasil"),
            tradeRows.drop(tradePage * per).take(per).mapIndexed { k, t ->
                listOf((tradePage * per + k + 1).toString(), t.asset, App.fmtT(t.exitTime), t.direction, App.fmtMoney(t.pnl), t.result)
            }, listOf(0.5f, 1.2f, 1f, 0.8f, 1f, 0.8f))
    }

    private fun paintTech(r: BacktestResult?, meta: FetchMeta?) {
        val box = techBox ?: return
        box.removeAllViews()
        eyebrow(box, "Diagnostik")
        if (r == null) { box.addView(stateBox(this, "empty", "Belum ada diagnostik.")); return }
        val d = r.diag
        table(box, listOf("Ukuran", "Nilai"), listOf(
            listOf("Status", classifyResult(r)),
            listOf("Strategi", r.strategy),
            listOf("Dimuat", "${d.dateFilteredCount}"),
            listOf("Dievaluasi", "${d.evaluatedBars}"),
            listOf("Signal", "${d.signalsRaw}"),
            listOf("Tersaring", "${d.filteredOut}"),
            listOf("Sumber", meta?.source ?: d.note ?: "—"),
            listOf("Cache", meta?.cacheUsed ?: "—")
        ))
    }
}
