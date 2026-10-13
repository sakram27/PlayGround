package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.engine.*

// FITUR 6 (V16): Analisis Dampak Filter. Snapshot params+data pair tampil saat
// ini; satu backtest pembanding per filter aktif (hanya filter itu dimatikan).
// Mesin aktual, bg + batal + progres. Konfigurasi aktif TAK tersentuh.
class AblationActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_ablation
    override val showBack = true

    private var base: BacktestResult? = null
    private var trials: List<AblationTrial> = emptyList()
    private var sortBy = "net"
    @Volatile private var cancelled = false

    override fun build() {
        setBar("Dampak Filter", "Satu filter dimatikan per percobaan")
        findViewById<View>(R.id.btnAblRun).setOnClickListener { runAll() }
        for ((id, key) in listOf(R.id.btnSortTrades to "trades", R.id.btnSortNet to "net", R.id.btnSortDd to "dd")) {
            findViewById<View>(id).setOnClickListener { sortBy = key; paint() }
        }
        paintIdle()
    }

    private fun t(id: Int): TextView = findViewById(id)

    private fun paintIdle() {
        val p = App.params
        val r = App.result
        if (p == null || r == null || r.error != null || App.candles.isEmpty()) {
            t(R.id.ablStatus).text = "Data tidak cukup — jalankan backtest dulu."
            return
        }
        val act = p.filters.filter { it.enabled }
        t(R.id.ablStatus).text =
            "${p.asset} · ${p.timeframe} · ${r.totalTrades} trade · ${act.size} filter aktif. " +
                "Tekan Jalankan: baseline + ${act.size} percobaan memakai data & parameter identik."
        paint()
    }

    private fun runAll() {
        val p0 = App.params ?: return
        val r0 = App.result ?: return
        if (r0.error != null || App.candles.isEmpty()) {
            snack(this, "Data tidak cukup.")
            return
        }
        val act = p0.filters.filter { it.enabled }
        if (act.isEmpty()) {
            snack(this, "Tidak ada filter aktif untuk diuji.")
            return
        }
        base = r0
        trials = emptyList()
        cancelled = false
        val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Ablation…")
            .setMessage("Menyiapkan…")
            .setNegativeButton("Batal") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        val candles0 = App.candles.toList()
        val raw = candles0.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        runBg {
            val out = ArrayList<AblationTrial>()
            for ((i, f) in act.withIndex()) {
                if (cancelled) break
                val fname = FILTER_DEFS.find { it.id == f.name }?.name ?: f.name
                runOnUiThread {
                    try { prog.setMessage("Percobaan ${i + 1}/${act.size}: tanpa \"$fname\"…") } catch (e: Exception) { }
                }
                try {
                    val p2 = p0.copy(filters = p0.filters.map { if (it.name == f.name) it.copy(enabled = false) else it })
                    val res = runBacktest(raw, p2)
                    out.add(if (res.error != null) AblationTrial(f.name, fname, false, null, res.error ?: "error")
                    else AblationTrial(f.name, fname, true, res))
                } catch (e: Exception) {
                    out.add(AblationTrial(f.name, fname, false, null, e.message ?: "gagal"))
                }
            }
            runOnUiThread {
                try { prog.dismiss() } catch (e: Exception) { }
                trials = out
                if (cancelled) snack(this, "Dibatalkan setelah ${out.size} percobaan.")
                paint()
            }
        }
    }

    private fun paint() {
        try {
            val b = base ?: return
            val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.ablList)
            list.vertical(this)
            val items = ArrayList<SigItem>()
            items.add(SigItem("≡", "Baseline (${b.totalTrades} tr · ${App.fmtMoney(b.netProfit)})",
                "Win ${App.fmt(b.winRate, 1)}% · PF ${App.fmtD(b.profitFactor)} · DD ${App.fmt(b.maxDrawdownPercent)}%", ""))
            val shown = sortAblation(b, trials, sortBy)
            t(R.id.ablCount).text =
                "Urut: ${if (sortBy == "net") "Δ net" else if (sortBy == "dd") "Δ DD" else "Δ transaksi"} · ${shown.size} percobaan."
            for (tr in shown) {
                if (!tr.comparable || tr.res == null) {
                    items.add(SigItem("?", tr.filterName, "Tak dapat dibandingkan: ${tr.note.take(60)}", "—"))
                } else {
                    val d = ablationDelta(b, tr.res)
                    val sign = if (d.dNet >= 0) "+" else ""
                    items.add(SigItem(if (d.dNet >= 0) "▲" else "▼", tr.filterName,
                        "${tr.res.totalTrades} tr (${if (d.dTrades >= 0) "+" else ""}${d.dTrades}) · " +
                            "Win ${App.fmt(tr.res.winRate, 1)}% · PF ${App.fmtD(tr.res.profitFactor)}",
                        "$sign${App.fmtMoney(d.dNet)}"))
                }
            }
            list.adapter = SigAdapter(items, onClick = { pos ->
                if (pos == 0) showBaseline(b)
                else {
                    val tr = shown.getOrNull(pos - 1) ?: return@SigAdapter
                    showTrial(b, tr)
                }
            })
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun showBaseline(b: BacktestResult) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Baseline (konfigurasi aktif)")
            .setMessage("Transaksi: ${b.totalTrades}\nWin: ${App.fmt(b.winRate, 1)}%\n" +
                "PF: ${App.fmtD(b.profitFactor)}\nNet: ${App.fmtMoney(b.netProfit)}\n" +
                "MaxDD: ${App.fmt(b.maxDrawdownPercent)}%")
            .setPositiveButton("Tutup", null).show()
    }

    private fun showTrial(b: BacktestResult, tr: AblationTrial) {
        val r = tr.res
        if (!tr.comparable || r == null) {
            snack(this, "\"${tr.filterName}\" tak dapat dibandingkan: ${tr.note}")
            return
        }
        val d = ablationDelta(b, r)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Tanpa \"${tr.filterName}\"")
            .setMessage("Transaksi: ${r.totalTrades} (Δ ${if (d.dTrades >= 0) "+" else ""}${d.dTrades})\n" +
                "Win: ${App.fmt(r.winRate, 1)}% (Δ ${App.fmt(d.dWinRate, 1)}%)\n" +
                "PF: ${App.fmtD(r.profitFactor)}\nNet: ${App.fmtMoney(r.netProfit)} " +
                "(Δ ${if (d.dNet >= 0) "+" else ""}${App.fmtMoney(d.dNet)})\n" +
                "MaxDD: ${App.fmt(r.maxDrawdownPercent)}% (Δ ${App.fmt(d.dDDPct, 1)}%)\n\n" +
                "Simulasi historis; filter lebih banyak transaksi belum tentu lebih baik. " +
                "Konfigurasi aktif tidak diubah.")
            .setPositiveButton("Tutup", null).show()
    }
}
