package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.engine.*

// FITUR 1 (V20): Walk-Forward bergulir. Tiap lipatan = runBacktest penuh atas
// jendela yang SAMA dengan startDate/endDate lipatan itu (warmup hanya dari
// data pra-lipatan via splitWarmup — masa depan tak pernah dipakai, parameter
// identik semua lipatan). bg + batal + progres. Agregat = pool trade.
class WalkForwardActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_walkfwd
    override val showBack = true

    private var folds = 3
    private var reports: List<FoldReport> = emptyList()
    @Volatile private var cancelled = false

    override fun build() {
        setBar("Walk-Forward", "Lipatan berurutan · tanpa intip masa depan")
        for ((id, n) in listOf(R.id.btnWf2 to 2, R.id.btnWf3 to 3, R.id.btnWf4 to 4)) {
            findViewById<View>(id).setOnClickListener { folds = n; paintFoldBtns(); paintIdle() }
        }
        findViewById<View>(R.id.btnWfRun).setOnClickListener { runAll() }
        paintFoldBtns()
        paintIdle()
    }

    private fun t(id: Int): TextView = findViewById(id)

    private fun paintFoldBtns() {
        try {
            t(R.id.wfFolds).text = "Lipatan: $folds"
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun snapshot(): Triple<BacktestParams, List<Candle>, List<Candle>>? {
        val p = App.params ?: return null
        val win = App.candles
        if (win.isEmpty()) return null
        val inRange = filterByDate(win, p.startDate, p.endDate)
        if (inRange.isEmpty()) return null
        return Triple(p, win, inRange)
    }

    private fun paintIdle() {
        val s = snapshot()
        if (s == null) {
            t(R.id.wfStatus).text = "Data tidak cukup — jalankan backtest dulu."
            return
        }
        val (_, _, inRange) = s
        val splits = wfSplits(inRange.size, folds, MIN_CANDLES)
        t(R.id.wfStatus).text = if (splits == null) {
            "Data (${inRange.size} candle) tak cukup untuk $folds lipatan " +
                "(butuh ≥${folds * MIN_CANDLES}). Kurangi lipatan."
        } else {
            "${inRange.size} candle → $folds lipatan " +
                "(${splits.joinToString(" · ") { "${it.to - it.from}c" }}). " +
                "Tiap lipatan: parameter identik, warmup hanya dari masa lalu."
        }
        paintResults()
    }

    private fun runAll() {
        val s = snapshot() ?: return
        val (p0, win, inRange) = s
        val splits = wfSplits(inRange.size, folds, MIN_CANDLES)
        if (splits == null) {
            snack(this, "Data tak cukup untuk $folds lipatan.")
            return
        }
        cancelled = false
        val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Walk-Forward…")
            .setMessage("Menyiapkan…")
            .setNegativeButton("Batal") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        val raw = win.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        runBg {
            val out = ArrayList<FoldReport>()
            for (sp in splits) {
                if (cancelled) break
                runOnUiThread {
                    try { prog.setMessage("Lipatan ${sp.index + 1}/${splits.size}…") } catch (e: Exception) { }
                }
                try {
                    val seg = inRange.subList(sp.from, sp.to)
                    // startDate/endDate lipatan → warmup otomatis dari pra-lipatan.
                    val p2 = p0.copy(startDate = seg.first().t, endDate = seg.last().t)
                    val res = runBacktest(raw, p2)
                    out.add(FoldReport(sp.index, seg.first().t, seg.last().t, seg.size, res))
                } catch (e: Exception) {
                    val seg = inRange.subList(sp.from, sp.to)
                    out.add(FoldReport(sp.index, seg.first().t, seg.last().t, seg.size,
                        errorResult(p0, "Lipatan gagal: ${e.message}")))
                }
            }
            runOnUiThread {
                try { prog.dismiss() } catch (e: Exception) { }
                reports = out
                if (cancelled) snack(this, "Dibatalkan setelah ${out.size} lipatan.")
                paintResults()
            }
        }
    }

    /** V21: indikator kestabilan (deskriptif; BUKAN uji stasioneritas formal). */
    private fun wfStabilityLine(nets: List<Double>): String {
        val st = wfStability(nets) ?: return "Stabilitas: tak dapat dinilai (<2 lipatan valid).\n"
        return "Stabilitas antar lipatan: ${st.verdict} " +
            "(${App.fmt(st.profitablePct, 0)}% lipatan profit). " +
            "Bukan uji stasioneritas formal (ADF/KPSS butuh pustaka statistik).\n"
    }

    private fun paintResults() {
        try {
            val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.wfList)
            list.vertical(this)
            if (reports.isEmpty()) {
                list.adapter = SigAdapter(emptyList())
                t(R.id.wfPooled).text = ""
                return
            }
            val items = ArrayList<SigItem>()
            for (fr in reports) {
                val r = fr.res
                if (r.error != null) {
                    items.add(SigItem("✕", "Lipatan ${fr.index + 1}: gagal", (r.error ?: "").take(60), ""))
                } else {
                    items.add(SigItem(if (r.netProfit >= 0) "▲" else "▼",
                        "Lipatan ${fr.index + 1} · ${r.totalTrades} tr",
                        "${fmtUtc(fr.fromT)} → ${fmtUtc(fr.toT)} · Win ${App.fmt(r.winRate, 1)}% · " +
                            "PF ${App.fmtD(r.profitFactor)} · DD ${App.fmt(r.maxDrawdownPercent, 1)}%",
                        App.fmtMoney(r.netProfit)))
                }
            }
            list.adapter = SigAdapter(items)
            // Gabungan: pool trade + kurva gabungan (definisi poolEquity).
            val ok = reports.map { it.res }.filter { it.error == null }
            if (ok.isEmpty()) {
                t(R.id.wfPooled).text = "Tidak ada lipatan berhasil — gabungan tak dapat dihitung."
                return
            }
            val pooled = poolTrades(ok)
            val curve = poolEquity(App.params?.initialCapital ?: 1000.0, ok.map { it to it.equityCurve })
            val maxDdf = ddWindows(curve)?.ddPct?.div(100) ?: 0.0
            val inRange = filterByDate(App.candles, App.params?.startDate ?: 0, App.params?.endDate ?: 0)
            val agg = buildResult(App.params ?: BacktestParams(), inRange, pooled, curve, maxDdf)
            t(R.id.wfPooled).text = "GABUNGAN (pool trade, ${ok.size} lipatan): " +
                "${agg.totalTrades} trade · Win ${App.fmt(agg.winRate, 1)}% · " +
                "PF ${App.fmtD(agg.profitFactor)} · Net ${App.fmtMoney(agg.netProfit)} · " +
                "DD ${App.fmt(agg.maxDrawdownPercent, 1)}%.\n" +
                wfStabilityLine(ok.map { it.netProfit }) +
                "Bukan jaminan masa depan — hanya konsistensi historis antar periode."
        } catch (e: Exception) { /* abaikan */ }
    }
}
