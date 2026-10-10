package com.aether.signal.premium.ui

import android.view.View
import android.widget.AdapterView
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.aether.signal.premium.data.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class DashboardActivity : BaseActivity(R.id.nav_home) {
    override val contentLayout = R.layout.activity_dashboard

    override fun build() {
        setBar("Aether Signal", "Terminal Sinyal Trading")
        paintHero()
        findViewById<MaterialButton>(R.id.btnEngine).setOnClickListener {
            if (App.engRunning) snack(this, BotEngine.stop()) else snack(this, BotEngine.start())
            // V28: setiap tick selesai → hero + card Kondisi Pasar diperbarui di
            // tempat, tanpa harus keluar tab Home lalu kembali.
            BotEngine.onTick = { runOnUiThread { paintHero(); paintRegime() } }
            paintHero()
            paintPairs()
            paintRegime()
        }
        findViewById<MaterialButton>(R.id.btnAllSig).setOnClickListener { openSignals() }
        findViewById<MaterialButton>(R.id.btnLab).setOnClickListener { navTo("lab") }
        // V28: kelola daftar pair Engine secara eksplisit (lihat resolveEnginePairs).
        findViewById<MaterialButton>(R.id.btnEngPairs).setOnClickListener { openEngPairSheet() }
        findViewById<MaterialButton>(R.id.btnEngSync).setOnClickListener {
            val src = if (App.mode == "multi") "multi (${App.multiSel.size} pair)" else "single (${App.pair})"
            confirm(this, "Salin dari Backtest",
                "Ganti daftar pair Engine dengan konfigurasi backtest $src?\nDaftar Engine saat ini: ${App.engPairs.joinToString(", ")}") {
                val used = App.syncEngPairsFromBacktest()
                paintPairs(); paintRegime()
                snack(this, "Pair Engine: ${used.joinToString(", ")}")
            }
        }
        paintSignals()
        paintCfg()
        paintPairs()
        paintRegime()
    }

    override fun onResume() {
        super.onResume()
        // Akar bug stale-Home: paintSignals() hanya dipanggil di build().
        // Daftar dibaca ulang dari App.signals setiap resume agar penghapusan
        // (dan sinyal baru) langsung tercermin tanpa pindah tab.
        try { paintHero() } catch (e: Exception) { /* layout belum siap */ }
        try { paintSignals() } catch (e: Exception) { /* abaikan */ }
        try { paintPairs() } catch (e: Exception) { /* abaikan */ }
        try { paintRegime() } catch (e: Exception) { /* abaikan */ }
    }

    private fun paintHero() {
        val wins = App.hist.count { it.result == "WIN" }
        val net = App.hist.sumOf { it.pnl }
        findViewById<android.widget.TextView>(R.id.heroNet).apply {
            text = App.fmtMoney(net)
            setTextColor(if (App.hist.isEmpty()) 0xFFF1F5F9.toInt() else if (net >= 0) 0xFF10B981.toInt() else 0xFFF87171.toInt())
        }
        findViewById<android.widget.TextView>(R.id.kSig).text = engineSignalsOnly(App.signals).size.toString()
        findViewById<android.widget.TextView>(R.id.kTr).text = App.hist.size.toString()
        findViewById<android.widget.TextView>(R.id.kWin).text =
            if (App.hist.isNotEmpty()) App.fmt(wins.toDouble() / App.hist.size * 100, 1) + "%" else "—"
        findViewById<android.widget.TextView>(R.id.kPos).text = BotEngine.positions.size.toString()
        findViewById<MaterialButton>(R.id.btnEngine).let { paintEngineButton(it, App.engRunning) }
        // V28: status berlabel — daftar pair yang dipantau + hitungan jujur.
        // RUNNING hanya klaim proses; bila semua pair gagal, tick mencatat 0 OK
        // dan teks menunjukkan "0/N pair data OK".
        findViewById<android.widget.TextView>(R.id.engStatus).text = when {
            MonitorService.running -> "Selalu Siaga · ${MonitorService.statusText()}"
            App.engRunning -> "RUN · ${BotEngine.lastOkPairs}/${BotEngine.lastTotalPairs} pair data OK\n" +
                "Dipantau: ${App.engPairs.joinToString(", ")}\n${BotEngine.lastScan}"
            else -> "Engine berhenti."
        }
    }

    /** V28: daftar pair Engine yang benar-benar dipakai tick (bukan tebakan). */
    private fun paintPairs() {
        findViewById<android.widget.TextView>(R.id.engPairs).text =
            if (App.engPairs.isEmpty()) "(kosong — pilih pair dulu)"
            else "${App.engPairs.size} pair: ${App.engPairs.joinToString(", ")}"
    }

    /** V28: ubah pair Engine manual (centang) — disimpan eksplisit via saveEngPairs. */
    private fun openEngPairSheet() {
        snack(this, "Memuat daftar pair…")
        runBg {
            val base = try {
                if (App.provider == "yahoo") YAHOO_UNIVERSE.keys.toList()
                else if (App.provider == "demo") listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT")
                else topPairs(App.provider, 50)
            } catch (e: Exception) { FALLBACK_PAIRS }
            val candidates = (LinkedHashSet(App.engPairs) + base).toList()
            runOnUiThread {
                val staged = LinkedHashSet(App.engPairs)
                val sh = BottomSheetDialog(this, R.style.SheetTheme)
                val root = layoutInflater.inflate(R.layout.sheet_multi, null)
                val search = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
                val list = root.findViewById<ListView>(R.id.list)
                list.fixScrollConflict()
                list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
                val cnt = root.findViewById<TextView>(R.id.count)
                fun render() {
                    val q = search.text.toString().uppercase()
                    val vis = candidates.filter { q.isEmpty() || it.contains(q) }
                    list.adapter = IconCheckAdapter(this, vis, false)
                    vis.forEachIndexed { i, s -> list.setItemChecked(i, staged.contains(s)) }
                    (list.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                    cnt.text = "${staged.size} pair engine dipilih"
                }
                list.onItemClickListener = AdapterView.OnItemClickListener { _, view, pos, _ ->
                    val q = search.text.toString().uppercase()
                    val vis = candidates.filter { q.isEmpty() || it.contains(q) }
                    val s = vis.getOrNull(pos) ?: return@OnItemClickListener
                    if (list.isItemChecked(pos)) staged.add(s) else staged.remove(s)
                    (view?.findViewById<android.widget.CheckedTextView>(R.id.check))?.isChecked =
                        list.isItemChecked(pos)
                    cnt.text = "${staged.size} pair engine dipilih"
                }
                search.addTextChangedListener { render() }
                render()
                root.findViewById<View>(R.id.btnAll).setOnClickListener {
                    val q = search.text.toString().uppercase()
                    candidates.filter { q.isEmpty() || it.contains(q) }.forEach { staged.add(it) }
                    render()
                }
                root.findViewById<View>(R.id.btnClear).setOnClickListener { staged.clear(); render() }
                root.findViewById<View>(R.id.btnTop5).setOnClickListener { candidates.take(5).forEach { staged.add(it) }; render() }
                root.findViewById<View>(R.id.btnApply).setOnClickListener {
                    if (staged.isEmpty()) { snack(this, "Pilih minimal 1 pair untuk Engine."); return@setOnClickListener }
                    App.engPairs = LinkedHashSet(staged); App.saveEngPairs()
                    sh.dismiss(); paintPairs(); paintRegime()
                    snack(this, "Pair Engine: ${App.engPairs.joinToString(", ")}")
                }
                sh.setContentView(root)
                sh.show()
            }
        }
    }

    private fun paintSignals() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.sigList)
        list.vertical(this)
        val vis = engineSignalsOnly(App.signals).take(4)
        val items = vis.map {
            SigItem(it.dir, "${it.pair}  ${it.tf}", "${App.fmtDate(it.t)} · via ${it.src}${signalRowExtra(it)}", App.fmt(it.price, 4), it.pair)
        }
        list.adapter = SigAdapter(items, onClick = { pos ->
            vis.getOrNull(pos)?.let { openSignalDetail(it.id) }
        })
        findViewById<android.widget.TextView>(R.id.sigEmpty).apply {
            visibility = if (vis.isEmpty()) View.VISIBLE else View.GONE
            text = "Belum ada sinyal Engine — tekan Start Engine."
        }
    }

    /** V16 F7 + V27: label regime dari cache engine + alasan terukur bila tak ada.
     *  Status dibedakan: menunggu / gagal ambil / data kurang / indikator gagal /
     *  label valid (+umur). Tak ada angka karangan — semua dari RegimeDiag tick. */
    private fun paintRegime() {
        try {
            val tv = findViewById<android.widget.TextView>(R.id.regimeList)
            if (App.engPairs.isEmpty()) {
                tv.text = "Pilih pair engine dulu."
                return
            }
            val now = System.currentTimeMillis()
            tv.text = App.engPairs.take(8).joinToString("\n") { sym ->
                regimeLine(sym, App.regimeDiag[sym], App.regime[sym], App.engRunning, now)
            }
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun paintCfg() {        val c = App.appliedCfg()
        findViewById<android.widget.TextView>(R.id.cfgMain).text =
            if (c == null) "NOT APPLIED — jalankan backtest atau Start engine."
            else "${c.strategyMode}: ${c.strategy}${if (c.combo.isNotEmpty()) " + " + c.combo.joinToString("+") else ""} · ${c.timeframe} · RR ${c.rr} · ${c.tpModeLabel}"
        val lb = App.lastBt()
        findViewById<android.widget.TextView>(R.id.cfgLast).text =
            if (lb == null) "Backtest terakhir: belum ada."
            else "Backtest: ${lb.mode} · ${lb.pairs.joinToString(",")} · ${lb.trades} trade · ${lb.status}"
    }
}
