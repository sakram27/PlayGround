package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.data.fetchFunding
import com.aether.signal.premium.data.fetchFundingRange
import com.aether.signal.premium.engine.*

// FITUR 4+6 (V20): Sensitivitas biaya + funding. Sensitivitas = runBacktest ulang
// per skenario fee×slip (mesin aktual, bg + batal). Funding = laporan pasca-hoc
// atas trade nyata (data aktual BUKAN estimasi; skenario manual berlabel tegas).
// Tak ada yang mengubah backtest tersimpan.
class CostAnalysisActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_cost
    override val showBack = true

    @Volatile private var cancelled = false

    override fun build() {
        setBar("Biaya & Funding", "Sensitivitas + laporan, bukan ubahan")
        findViewById<View>(R.id.btnCostRun).setOnClickListener { runSensitivity() }
        findViewById<View>(R.id.btnFundFetch).setOnClickListener { runFunding(false) }
        for ((id, r) in listOf(R.id.btnFund001 to 0.0001, R.id.btnFund005 to 0.0005, R.id.btnFund01 to 0.001)) {
            findViewById<View>(id).setOnClickListener { runFunding(true, r) }
        }
        paintIdle()
    }

    private fun t(id: Int): TextView = findViewById(id)

    private fun snapshot(): Triple<BacktestParams, List<Candle>, List<Trade>>? {
        val p = App.params ?: return null
        if (App.candles.isEmpty() || App.result?.error != null) return null
        val trades = App.result?.trades ?: emptyList()
        if (trades.isEmpty()) return null
        return Triple(p, App.candles.toList(), trades)
    }

    private fun paintIdle() {
        val s = snapshot()
        t(R.id.costStatus).text = if (s == null) {
            "Butuh hasil backtest bertransaksi — jalankan backtest dulu."
        } else {
            "Siap: ${s.first.asset} ${s.first.timeframe} · ${s.third.size} trade. " +
                "16 skenario fee×slip. $COST_ASSUMPTION_NOTE"
        }
    }

    private fun runSensitivity() {
        val s = snapshot() ?: return
        val (p0, win, _) = s
        cancelled = false
        val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Sensitivitas…")
            .setMessage("Menyiapkan…")
            .setNegativeButton("Batal") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        val raw = win.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val scenarios = ArrayList(costScenarios())
        try {
            val cf = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inCostFee)
                .text.toString().trim().replace(',', '.').toDoubleOrNull()
            val cs = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inCostSlip)
                .text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (cf != null && cs != null && cf.isFinite() && cs.isFinite()
                && cf in 0.0..2.0 && cs in 0.0..2.0) {
                val custom = CostScenario(cf / 100, cs / 100, "kustom " + costLabel(cf / 100, cs / 100))
                if (scenarios.none { it.fee == custom.fee && it.slip == custom.slip }) {
                    scenarios.add(custom)
                }
            } else if ((cf != null || cs != null)) {
                snack(this, "Skenario kustom diabaikan (isi fee & slip 0–2%).")
            }
        } catch (e: Exception) { /* input opsional */ }
        runBg {
            val rows = ArrayList<Triple<CostScenario, BacktestResult?, String?>>()
            for ((i, sc) in scenarios.withIndex()) {
                if (cancelled) break
                runOnUiThread {
                    try { prog.setMessage("Skenario ${i + 1}/${scenarios.size}: ${sc.label}…") } catch (e: Exception) { }
                }
                try {
                    val r = runBacktest(raw, p0.copy(feePercent = sc.fee, slippagePercent = sc.slip))
                    rows.add(Triple(sc, if (r.error == null) r else null, r.error))
                } catch (e: Exception) {
                    rows.add(Triple(sc, null, e.message))
                }
            }
            runOnUiThread {
                try { prog.dismiss() } catch (e: Exception) { }
                if (cancelled) snack(this, "Dibatalkan setelah ${rows.size} skenario.")
                paintTable(rows, p0, scenarios.size)
            }
        }
    }

    private fun paintTable(rows: List<Triple<CostScenario, BacktestResult?, String?>>, p0: BacktestParams, total: Int) {
        try {
            val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.costList)
            list.vertical(this)
            val q = quoteCurrency(p0.asset)
            list.adapter = SigAdapter(rows.map { (sc, r, err) ->
                if (r == null) {
                    SigItem("✕", sc.label, (err ?: "gagal").take(60), "—")
                } else {
                    val base = sc.fee == p0.feePercent && sc.slip == p0.slippagePercent
                    SigItem(if (base) "●" else (if (r.netProfit >= 0) "▲" else "▼"),
                        (if (base) "[aktif] " else "") + sc.label,
                        "${r.totalTrades} tr · Win ${App.fmt(r.winRate, 1)}% · " +
                            "PF ${App.fmtD(r.profitFactor)} · DD ${App.fmt(r.maxDrawdownPercent, 1)}%",
                        fmtMoneyQ(r.netProfit, q))
                }
            })
            t(R.id.costStatus).text =
                "Selesai ${rows.size}/$total skenario (● = konfigurasi aktif). $COST_ASSUMPTION_NOTE"
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun runFunding(estimated: Boolean, rate: Double = 0.0) {
        val s = snapshot() ?: return
        val (p0, win, trades) = s
        val deriv = try {
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swDeriv).isChecked
        } catch (e: Exception) { false }
        if (!deriv && !estimated) {
            t(R.id.fundStatus).text = "Funding tidak diterapkan (asumsi spot — sakelar derivatif mati). " +
                "Aktifkan sakelar bila posisi adalah perpetual futures."
            return
        }
        t(R.id.fundStatus).text = "Mengambil funding…"
        runBg {
            try {
                val asset = trades.firstOrNull()?.asset ?: p0.asset
                val prov = App.provider
                val res: FundingAppResult
                if (!estimated) {
                    val from = trades.minOf { it.entryTime }
                    val to = trades.maxOf { it.exitTime }
                    val ev = fetchFundingRange(prov, asset, from, to)
                    if (ev.isEmpty()) throw RuntimeException("Sumber mengembalikan 0 event funding untuk $asset pada periode trade.")
                    val a = applyFunding(trades, win, ev, false)
                    res = FundingAppResult(asset, ev.size, a, "aktual ($prov, ${ev.size} event, periode trade)")
                } else {
                    val from = trades.minOf { it.entryTime }
                    val to = trades.maxOf { it.exitTime }
                    val ev = syntheticFunding(from, to, rate)
                    val a = applyFunding(trades, win, ev, true)
                    res = FundingAppResult(asset, ev.size, a,
                        "ESTIMASI MANUAL rate ${rate * 100}%/8j — bukan data aktual")
                }
                runOnUiThread { paintFunding(res) }
            } catch (e: Exception) {
                runOnUiThread {
                    t(R.id.fundStatus).text =
                        "Funding tak tersedia: ${e.message} — hasil backtest belum mencakup funding " +
                            "(asumsi spot). Estimasi manual tetap bisa dicoba di bawah."
                }
            }
        }
    }

    private data class FundingAppResult(
        val asset: String, val events: Int,
        val app: FundingApplication, val kind: String
    )

    private fun paintFunding(r: FundingAppResult) {
        try {
            val q = quoteCurrency(r.asset)
            t(R.id.fundStatus).text =
                "Funding ${r.asset} [${r.kind}]: ${r.events} event relevan tercatat, " +
                    "${r.app.eventsUsed} terpakai · ${r.app.eventsSkipped} dilewati (tanpa candle pendukung).\n" +
                    "Total funding ${fmtMoneyQ(r.app.totalFunding, q)} · " +
                    "net + funding ${fmtMoneyQ(r.app.netWithFunding, q)}.\n" +
                    "Aturan: LONG membayar rate positif (tiap 8 jam posisi terbuka); SHORT sebaliknya. " +
                    "Backtest tersimpan tak diubah."
        } catch (e: Exception) { /* abaikan */ }
    }
}
