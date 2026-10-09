package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class SignalsActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_signals
    // Dibuka langsung (bukan tab): tombol kembali toolbar aktif agar bisa pulang.
    override val showBack = true
    private var mode = 0

    override fun build() {
        setBar("Signals", "Sinyal · posisi paper · riwayat")
        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText("Signals"))
        tabs.addTab(tabs.newTab().setText("Positions"))
        tabs.addTab(tabs.newTab().setText("History"))
        tabs.getTabAt(mode)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { mode = t.position; paint() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        paint()
    }

    private fun list() = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)

    private fun csvViews(show: Boolean) {
        findViewById<View>(R.id.btnCsv).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.spCsv).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun paint() {
        list().vertical(this)
        csvViews(false)
        findViewById<TextView>(R.id.hero).visibility = View.GONE
        findViewById<View>(R.id.loading).visibility = View.GONE
        findViewById<TextView>(R.id.empty).visibility = View.GONE
        val bMain = findViewById<MaterialButton>(R.id.btnMain)
        val bSecond = findViewById<MaterialButton>(R.id.btnSecond)
        when (mode) {
            0 -> {
                bMain.text = "Hapus sinyal"
                bMain.setOnClickListener { confirm(this, "Hapus", "Hapus semua sinyal?") { App.clearSignals(); paint() } }
                bSecond.text = "Ke Markets"
                bSecond.setOnClickListener { navTo("markets") }
                val items = App.signals.take(150).map {
                    SigItem(it.dir, "${it.pair}  ${it.tf}", "${App.fmtDate(it.t)} · via ${it.src} · SL ${App.fmt(it.sl, 4)} TP ${App.fmt(it.tp, 4)}", App.fmt(it.price, 4), it.pair)
                }
                list().adapter = SigAdapter(items)
                if (items.isEmpty()) findViewById<TextView>(R.id.empty).apply {
                    visibility = View.VISIBLE; text = "Belum ada sinyal — Start engine atau jalankan backtest."
                }
            }
            1 -> {
                bMain.text = if (App.engRunning) "Stop Engine" else "Start Engine"
                bMain.setOnClickListener {
                    if (App.engRunning) BotEngine.stop() else snack(this, BotEngine.start())
                    BotEngine.onTick = { runOnUiThread { paint() } }
                    paint()
                }
                bSecond.text = "Reset"
                bSecond.setOnClickListener {
                    BotEngine.positions.clear(); BotEngine.closed.clear(); BotEngine.equity = null; paint()
                }
                findViewById<TextView>(R.id.hero).apply {
                    visibility = View.VISIBLE
                    text = if (App.engRunning) "RUN · equity ${App.fmtMoney(BotEngine.equity ?: 0.0)} · net ${App.fmtMoney(BotEngine.netClosed())}"
                    else "Berhenti · equity ${App.fmtMoney(BotEngine.equity ?: 0.0)} · net ${App.fmtMoney(BotEngine.netClosed())}  — paper trading, bukan order sungguhan"
                }
                val items = ArrayList<SigItem>()
                if (BotEngine.positions.isNotEmpty()) {
                    items.add(SigItem("···", "Terbuka · ${BotEngine.positions.size}", "", ""))
                    BotEngine.positions.forEach { o ->
                        items.add(SigItem(o.dir, "${o.pair} @ ${App.fmt(o.entry, 4)}", "mark ${App.fmt(o.mark, 4)} · SL ${App.fmt(o.sl, 4)} TP ${App.fmt(o.tp, 4)}", App.fmtMoney(o.upl), o.pair))
                    }
                }
                if (BotEngine.closed.isNotEmpty()) {
                    items.add(SigItem("···", "Tertutup · ${BotEngine.closed.size}", "", ""))
                    BotEngine.closed.take(120).forEach { t ->
                        items.add(SigItem(t.dir, "${t.pair}  ${t.result}", App.fmtDate(t.exitT), App.fmtMoney(t.pnl), t.pair))
                    }
                }
                list().adapter = SigAdapter(items)
                if (items.isEmpty()) findViewById<TextView>(R.id.empty).apply {
                    visibility = View.VISIBLE; text = "Belum ada posisi paper."
                }
            }
            else -> {
                val wins = App.hist.count { it.result == "WIN" }
                val net = App.hist.sumOf { it.pnl }
                findViewById<TextView>(R.id.hero).apply {
                    visibility = View.VISIBLE
                    text = "Net ${App.fmtMoney(net)} · ${App.hist.size} trade · ${if (App.hist.isNotEmpty()) App.fmt(wins.toDouble() / App.hist.size * 100, 1) + "%" else "—"}"
                }
                bMain.text = "Hapus riwayat"
                bMain.setOnClickListener { confirm(this, "Hapus", "Hapus riwayat trade?") { App.clearHist(); paint() } }
                bSecond.text = "Ke Lab"
                bSecond.setOnClickListener { navTo("lab") }
                findViewById<MaterialButton>(R.id.btnCsv).setOnClickListener { exportHistCsv() }
                csvViews(true)
                val items = App.hist.take(150).map {
                    SigItem(it.direction, "${it.asset}  ${it.result}", App.fmtDate(it.exitTime), App.fmtMoney(it.pnl), it.asset)
                }
                list().adapter = SigAdapter(items)
                if (items.isEmpty()) findViewById<TextView>(R.id.empty).apply {
                    visibility = View.VISIBLE; text = "Riwayat kosong."
                }
            }
        }
    }

    /** P10: ekspor riwayat nyata ke CSV. Pilih lokasi (SAF); fallback bagikan. */
    private fun exportHistCsv() {
        if (App.hist.isEmpty()) {
            snack(this, "Riwayat kosong — tidak ada yang diekspor.")
            return
        }
        pendingCsv = buildHistCsv(App.hist)
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_TITLE, "aether-riwayat.csv")
            }
            startActivityForResult(i, 3301)
        } catch (e: Exception) {
            shareCsvFallback()
        }
    }

    private var pendingCsv: String? = null

    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 3301 || res != RESULT_OK || data?.data == null) return
        try {
            contentResolver.openOutputStream(data.data!!)!!.bufferedWriter().use { it.write(pendingCsv ?: "") }
            snack(this, "CSV tersimpan di lokasi pilihan.")
        } catch (e: Exception) { snack(this, "Gagal menyimpan CSV: ${e.message}") }
        pendingCsv = null
    }

    private fun shareCsvFallback() {
        try {
            val dir = java.io.File(filesDir, "shared").apply { mkdirs() }
            val f = java.io.File(dir, "aether-riwayat.csv")
            f.writeText(pendingCsv ?: "")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            val sh = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(sh, "Bagikan CSV"))
        } catch (e: Exception) { snack(this, "Export gagal: ${e.message}") }
        pendingCsv = null
    }
}
