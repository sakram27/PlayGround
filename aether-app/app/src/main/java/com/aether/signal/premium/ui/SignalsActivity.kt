package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.engine.parseTimeframe
import com.aether.signal.premium.engine.isWinTrade
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class SignalsActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_signals
    // Dibuka langsung (bukan tab): tombol kembali toolbar aktif agar bisa pulang.
    override val showBack = true
    private var mode = 0
    /** UI-1: diagnostik lengkap Positions default ringkas; dibuka via "Lihat detail". */
    private var diagExpanded = false

    companion object {
        /** FLAGSHIP: buka langsung ke tab tertentu (0 Signals · 1 Positions · 2 History · 3 Drift). */
        fun openTab(a: android.app.Activity, tab: Int) {
            try {
                a.startActivity(android.content.Intent(a, SignalsActivity::class.java).putExtra("tab", tab))
            } catch (e: Exception) { /* abaikan; pemanggil menampilkan snack sendiri */ }
        }
    }

    override fun build() {
        setBar("Signals", "Sinyal · posisi paper · riwayat")
        try { mode = intent.getIntExtra("tab", 0).coerceIn(0, 3) } catch (e: Exception) { mode = 0 }
        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText("Signals"))
        tabs.addTab(tabs.newTab().setText("Positions"))
        tabs.addTab(tabs.newTab().setText("History"))
        tabs.addTab(tabs.newTab().setText("Drift"))
        tabs.getTabAt(mode)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            // FLAGSHIP: transisi pudar singkat antar tab (hormati setelan sistem).
            override fun onTabSelected(t: TabLayout.Tab) { val m = t.position; fadeSwap(list()) { mode = m; paint() } }
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
        try { findViewById<TextView>(R.id.engDiag).visibility = View.GONE } catch (e: Exception) { /* abaikan */ }
        findViewById<View>(R.id.loading).visibility = View.GONE
        findViewById<TextView>(R.id.empty).visibility = View.GONE
        val bMain = findViewById<MaterialButton>(R.id.btnMain)
        val bSecond = findViewById<MaterialButton>(R.id.btnSecond)
        when (mode) {
            0 -> {
                bMain.text = "Hapus sinyal"
                paintDangerButton(bMain)
                bMain.setOnClickListener { confirm(this, "Hapus", "Hapus semua sinyal?") { App.clearSignals(); paint() } }
                bSecond.text = "Ke Markets"
                bSecond.setOnClickListener { navTo("markets") }
                // Snapshot + adapter dibuat bersamaan; klik memakai id stabil
                // (dicari ulang di daftar terkini), bukan posisi sebagai identitas.
                // Hanya sinyal Engine; data lama tak terklasifikasi ditandai terpisah.
                val vis = engineSignalsOnly(App.signals).take(150)
                val items = ArrayList(vis.map {
                    SigItem(it.dir, "${it.pair}  ${it.tf}", "${App.fmtDate(it.t)} · via ${it.src} · SL ${App.fmt(it.sl, 4)} TP ${App.fmt(it.tp, 4)}${signalRowExtra(it)}", App.fmt(it.price, 4), it.pair)
                })
                val targets = ArrayList(vis.map { it.id })
                // TAHAP 2.4 header tab: jumlah + sumber (baris info, bukan sinyal).
                if (vis.isNotEmpty()) {
                    items.add(0, SigItem("◉", "Sinyal Engine · ${vis.size} ditampilkan",
                        "Sumber: Engine live (bukan backtest) · maks 150 terbaru", ""))
                    targets.add(0, "")
                }
                if (App.legacySignals.isNotEmpty()) {
                    items.add(SigItem("···", "${App.legacySignals.size} data lama belum terklasifikasi",
                        "Tidak dihitung sebagai sinyal Engine.", ""))
                    targets.add("")
                }
                list().adapter = SigAdapter(items, onClick = { pos ->
                    val id = targets.getOrNull(pos) ?: ""
                    if (id.isEmpty()) snack(this, "Baris info — bukan sinyal Engine.")
                    else openSignalDetail(id)
                })
                if (items.isEmpty()) findViewById<TextView>(R.id.empty).apply {
                    // UI-1: empty state kontekstual dari angka aktual.
                    visibility = View.VISIBLE
                    text = try {
                        val s0 = EngineDiag.snapshot(App.engRunning, App.strategy, App.timeframe,
                            App.engPairs.toList(), BotEngine.positions.size, 0L)
                        val now0 = System.currentTimeMillis()
                        emptySignalText(App.engRunning, s0.evalTotal, s0.evalOk, s0.candidates,
                            s0.blockedPosition, s0.openPositions, s0.pairs.size, s0.lastTickAt, now0, s0.stored, s0.failCount)
                    } catch (e: Exception) { "Belum ada sinyal Engine — tekan Start Engine." }
                }
            }
            1 -> {
                bMain.setOnClickListener {
                    // Tahap 3: Start melalui guard (drift backtest vs engine + sesi beku).
                    engineStartFlow(this) { paint() }
                    BotEngine.onTick = { runOnUiThread { paint() } }
                    paint()
                }
                paintEngineButton(bMain, App.engRunning)
                bSecond.text = "Reset"
                bSecond.setOnClickListener {
                    BotEngine.positions.clear(); BotEngine.closed.clear(); BotEngine.equity = null; paint()
                }
                findViewById<TextView>(R.id.hero).apply {
                    visibility = View.GONE
                }
                // V35 FIX scroll Positions: hero (RUN/equity) + panel diagnostik
                // yang dulu TextView fixed DI LUAR RecyclerView kini menjadi
                // baris header DI DALAM RecyclerView. Akar bug: gesture dari
                // area atas tak menggulir apa-apa (view di luar scroll), dan
                // panel diagnostik yang panjang menekan tinggi list. Kini satu
                // area scroll utuh dari atas sampai bawah; recycling tetap.
                val heroText = if (App.engRunning) "RUN · equity ${App.fmtMoney(BotEngine.equity ?: 0.0)} · net ${App.fmtMoney(BotEngine.netClosed())}"
                    else "Berhenti · equity ${App.fmtMoney(BotEngine.equity ?: 0.0)} · net ${App.fmtMoney(BotEngine.netClosed())}"
                val items = ArrayList<SigItem>()
                // Target sejajar baris: null untuk baris grup/info (bukan sinyal).
                val targets = ArrayList<com.aether.signal.premium.ai.Sig?>()
                // Aksi khusus baris UI-1 (posisi → aksi): "copy"/"toggle".
                val rowActions = HashMap<Int, String>()
                items.add(SigItem("···", heroText, "Paper trading — bukan order sungguhan", ""))
                targets.add(null)
                // UI-1: ringkasan selalu terlihat (status + alasan + penghitung),
                // detail lengkap di balik toggle. Satu sumber: currentDiag().
                val (snap1, diagText, histN) = currentDiag()
                val now1 = System.currentTimeMillis()
                val reason1 = noSignalReason(App.engRunning, snap1.evalTotal, snap1.evalOk,
                    snap1.candidates, snap1.stored, snap1.failCount, snap1.openPositions,
                    snap1.pairs.size, snap1.lastTickAt, now1, snap1.blockedPosition)
                items.add(SigItem("◉", engineStatusSummary(snap1, now1), "Alasan: $reason1", ""))
                targets.add(null)
                items.add(SigItem("···",
                    "Kandidat ${snap1.candidates} (LONG ${snap1.candLong}/SHORT ${snap1.candShort}) · tersimpan ${snap1.stored} · notif ${snap1.notifCalls}",
                    "Ditolak ${snap1.filteredOut} · blokir ${snap1.blockedPosition} · cap ${snap1.rejectedCap} · dup ${snap1.dupHeld}", ""))
                targets.add(null)
                items.add(SigItem("⧉", "Salin diagnostik", "Salin snapshot aktual ke clipboard", ""))
                rowActions[items.size - 1] = "copy"
                targets.add(null)
                val detailLines = diagText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                items.add(SigItem(if (diagExpanded) "▴" else "▾",
                    if (diagExpanded) "Sembunyikan detail" else "Lihat detail (${detailLines.size} baris)",
                    "Diagnostik lengkap tick terakhir — tanpa nilai palsu", ""))
                rowActions[items.size - 1] = "toggle"
                targets.add(null)
                if (diagExpanded) {
                    for (t in detailLines) {
                        items.add(SigItem("···", t, "", ""))
                        targets.add(null)
                    }
                }
                if (BotEngine.positions.isNotEmpty()) {
                    items.add(SigItem("···", "Terbuka · ${BotEngine.positions.size}", "", ""))
                    targets.add(null)
                    BotEngine.positions.forEach { o ->
                        items.add(SigItem(o.dir, "${o.pair} @ ${App.fmt(o.entry, 4)}", "mark ${App.fmt(o.mark, 4)} · SL ${App.fmt(o.sl, 4)} TP ${App.fmt(o.tp, 4)}", App.fmtMoney(o.upl), o.pair))
                        targets.add(App.signals.find { it.src == "dryrun-entry" && it.pair == o.pair && it.dir == o.dir && it.t == o.entryT })
                    }
                }
                if (BotEngine.closed.isNotEmpty()) {
                    items.add(SigItem("···", "Tertutup · ${BotEngine.closed.size}", "", ""))
                    targets.add(null)
                    BotEngine.closed.take(120).forEach { t ->
                        items.add(SigItem(t.dir, "${t.pair}  ${t.result}", App.fmtDate(t.exitT), App.fmtMoney(t.pnl), t.pair))
                        targets.add(App.signals.find {
                            (it.src == "dryrun-TP" || it.src == "dryrun-SL" || it.src == "dryrun-trail" || it.src == "dryrun-expired") &&
                                it.pair == t.pair && it.dir == t.dir && it.t == t.exitT
                        })
                    }
                }
                list().adapter = SigAdapter(items, onClick = { pos ->
                    when (rowActions[pos]) {
                        "copy" -> copyDiag(snap1, histN)
                        // TAHAP 2.6 transisi pudar singkat (hormati setelan sistem).
                        "toggle" -> { diagExpanded = !diagExpanded; fadeSwap(list()) { paint() } }
                        else -> {
                            val s = targets.getOrNull(pos)
                            if (s == null) snack(this, "Baris grup / info / arsip tanpa sinyal tersimpan.")
                            else openSignalDetail(s.id)
                        }
                    }
                }, wrapMain = true)
                if (BotEngine.positions.isEmpty() && BotEngine.closed.isEmpty()) {
                    // Header ringkasan selalu ada di atas, jadi baris info ini
                    // ikut tergulir (empty view fixed tak dipakai di mode ini).
                    items.add(SigItem("···", "Belum ada posisi paper.", "", ""))
                    targets.add(null)
                    (list().adapter as? SigAdapter)?.let {
                        it.items = items
                        try { it.notifyDataSetChanged() } catch (_: Exception) { }
                    }
                }
            }
            2 -> { // History
                val wins = App.hist.count { isWinTrade(it) }
                val net = App.hist.sumOf { it.pnl }
                findViewById<TextView>(R.id.hero).apply {
                    visibility = View.VISIBLE
                    text = "Net ${App.fmtMoney(net)} · ${App.hist.size} trade · ${if (App.hist.isNotEmpty()) App.fmt(wins.toDouble() / App.hist.size * 100, 1) + "%" else "—"}"
                }
                bMain.text = "Hapus riwayat"
                paintDangerButton(bMain)
                bMain.setOnClickListener { confirm(this, "Hapus", "Hapus riwayat trade?") { App.clearHist(); paint() } }
                bSecond.text = "Ke Lab"
                bSecond.setOnClickListener { navTo("lab") }
                findViewById<MaterialButton>(R.id.btnCsv).setOnClickListener { exportHistCsv() }
                csvViews(true)
                val visH = App.hist.take(150)
                val items = visH.map {
                    SigItem(it.direction, "${it.asset}  ${it.result}", App.fmtDate(it.exitTime), App.fmtMoney(it.pnl), it.asset)
                }
                list().adapter = SigAdapter(items, onClick = { pos ->
                    // Transaksi backtest → arsip di store backtest (bukan daftar Engine).
                    val t = visH.getOrNull(pos)
                    val s = t?.let { tr ->
                        // ID arsip = engineSignalId('b', entryTime, asset, direction);
                        // cocokkan persis termasuk arah (tanpa arah tak pernah cocok).
                        App.backtestSignals.find { it.src == "backtest" && it.id == "b${tr.entryTime}${tr.asset}${tr.direction}" }
                            ?: App.backtestSignals.find { it.src == "backtest" && it.id.startsWith("b${tr.entryTime}${tr.asset}") }
                    }
                    if (s == null) snack(this, "Tidak ada sinyal arsip untuk transaksi ini.")
                    else openSignalDetail(s.id)
                })
                if (items.isEmpty()) findViewById<TextView>(R.id.empty).apply {
                    visibility = View.VISIBLE; text = "Riwayat kosong."
                }
            }
            else -> { // Drift (mode 3): live TP/SL vs ekspektasi backtest
                bMain.text = "Perbarui"
                paintInfoButton(bMain)
                bMain.setOnClickListener { paint() }
                bSecond.text = "Ke Lab"
                bSecond.setOnClickListener { navTo("lab") }
                paintDrift()
            }
        }
    }

    /** V20 F2: kartu drift — menang = sinyal TP, kalah = sinyal SL (peristiwa, bukan eksekusi). */
    private fun paintDrift() {
        val tps = App.signals.filter { it.src == "dryrun-TP" }
        val sls = App.signals.filter { it.src == "dryrun-SL" }
        val exp = App.loadDriftExpect()
        val rep = driftReport(tps.size, sls.size, exp)
        findViewById<TextView>(R.id.hero).apply {
            visibility = View.VISIBLE
            text = if (rep.liveWinRate != null)
                "Live ${rep.liveTrades} peristiwa · Win ${App.fmt(rep.liveWinRate, 1)}%"
            else "Live ${rep.liveTrades} peristiwa"
        }
        val lines = ArrayList<SigItem>()
        lines.add(SigItem(if (rep.warned) "⚠" else "✓", rep.status, "", ""))
        if (exp != null) {
            lines.add(SigItem("≡",
                "Ekspektasi: WR ${App.fmt(exp.winRate, 1)}% · PF ${App.fmtD(exp.profitFactor)} · ${exp.trades} trade",
                "${exp.strategy} · ${exp.asset} ${exp.timeframe} · ${App.fmtDate(exp.at)}", ""))
        } else {
            lines.add(SigItem("···", "Belum ada ekspektasi tersimpan.", "Jalankan backtest sukses untuk mencatatnya.", ""))
        }
        // V21: kelompok per strategi + status sinyal (selesai/aktif/menunggu/arsip).
        val byStrat = (tps + sls).groupBy { if (it.strategy.isNotEmpty()) it.strategy else "tak diketahui" }
            .toList().sortedByDescending { it.second.size }.take(5)
        for ((strat, ss) in byStrat) {
            val w = ss.count { it.src == "dryrun-TP" }
            lines.add(SigItem("◈", "$strat: $w/${ss.size} TP",
                "dari ${ss.size} peristiwa selesai", ""))
        }
        val openN = App.signals.count { it.src == "dryrun-entry" }
        val arpN = App.backtestSignals.size
        lines.add(SigItem("···",
            "Live: ${tps.size} TP (menang) · ${sls.size} SL (kalah) · $openN entry (aktif/menunggu) · $arpN arsip",
            "Ambang: n≥$DRIFT_MIN_SAMPLE, |ΔWR|>${DRIFT_WR_WARN_PP.toInt()}pp atau PF turun >${DRIFT_PF_DROP_PCT.toInt()}%. " +
                "Bukan bukti eksekusi; monitor berhenti bila aplikasi mati.", ""))
        list().adapter = SigAdapter(lines)
    }

    /** Jepretan + teks + hitungan riwayat notifikasi untuk tab Positions (satu sumber).
     *  Isi = angka aktual tick terakhir + notifikasi. */
    private fun currentDiag(): Triple<EngineDiagSnapshot, String, Triple<Int, Int, Int>> {
        val now = System.currentTimeMillis()
        val tfMin = try { parseTimeframe(App.timeframe) } catch (e: Exception) { 15 }
        val oldest = BotEngine.positions.minOfOrNull { it.entryT }?.let { now - it } ?: 0L
        val snap = EngineDiag.snapshot(
            running = App.engRunning, strategy = App.strategy, timeframe = App.timeframe,
            pairs = App.engPairs.toList(), openPositions = BotEngine.positions.size,
            oldestOpenAgeMs = oldest)
        val hist = try { NotifBus.history() } catch (e: Exception) { emptyList() }
        val sent = hist.count { it.status == "DIKIRIM" }
        val held = hist.count { it.status == "DITAHAN" }
        val failed = hist.count { it.status == "GAGAL" || it.status == "GAGAL_IZIN" }
        val text = try {
            formatEngineDiag(
                snap, App.strategy, App.timeframe, App.provider,
                !NotifBus.needsRuntimePermission(), NotifBus.systemEnabled(),
                try { NotifBus.quietHours().enabled } catch (e: Exception) { false },
                sent, held, failed, tfMin, now)
        } catch (e: Exception) {
            ""
        }
        return Triple(snap, text, Triple(sent, held, failed))
    }

    /** UI-1: salin snapshot diagnostik aktual ke clipboard + konfirmasi ringan. Tanpa rahasia. */
    private fun copyDiag(snap: EngineDiagSnapshot, histN: Triple<Int, Int, Int>) {
        try {
            val now = System.currentTimeMillis()
            val text = buildDiagCopyText(snap, App.strategy, App.timeframe, App.provider, now,
                histN.first, histN.second, histN.third)
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("aether-diagnostik", text))
            snack(this, "Diagnostik disalin (${text.length} karakter).")
        } catch (e: Exception) {
            snack(this, "Gagal menyalin: ${e.message}")
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
