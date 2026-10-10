package com.aether.signal.premium.ui

import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.aether.signal.premium.engine.*
import com.google.android.material.button.MaterialButton

class LabActivity : BaseActivity(R.id.nav_lab) {
    override val contentLayout = R.layout.activity_lab
    private var query = ""
    private var visible: List<StrategyMeta> = emptyList()

    override fun build() {
        setBar("Strategy Lab", "Strategi · Hyperopt · riset")
        findViewById<MaterialButton>(R.id.btnBacktest).setOnClickListener { navKeep("backtest") }
        findViewById<MaterialButton>(R.id.btnAi).setOnClickListener { navKeep("ai") }
        findViewById<MaterialButton>(R.id.btnJournal).setOnClickListener {
            startActivity(android.content.Intent(this, JournalActivity::class.java))
        }
        // V20: riset lanjut gratis (tanpa server/langganan).
        findViewById<MaterialButton>(R.id.btnArchive).setOnClickListener {
            startActivity(android.content.Intent(this, CollectionActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnCost).setOnClickListener {
            startActivity(android.content.Intent(this, CostAnalysisActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnXVal).setOnClickListener {
            startActivity(android.content.Intent(this, XValidateActivity::class.java))
        }
        val lb = App.lastBt()
        findViewById<TextView>(R.id.lastBt).text =
            if (lb == null) "Belum ada backtest."
            else "Terakhir: ${lb.mode} · ${lb.pairs.joinToString(",")} · ${lb.trades} trade · ${lb.status}"
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
            .addTextChangedListener { query = (it?.toString() ?: ""); paintStrat() }
        findViewById<MaterialButton>(R.id.btnCombo).setOnClickListener { openComboSheet() }
        paintStrat()
        paintComboCount()
        findViewById<MaterialButton>(R.id.btnOpt).setOnClickListener { runOpt() }
    }

    private fun current(): List<StrategyMeta> =
        strategyList().filter {
            query.isEmpty() || it.name.lowercase().contains(query.lowercase()) || it.id.contains(query.lowercase())
        }

    private fun paintStrat() {
        visible = current()
        val lv = findViewById<ListView>(R.id.stratList)
        lv.fixScrollConflict()
        bindSingleChoice(lv, visible.map { "${it.name} (${it.id})" },
            visible.indexOfFirst { it.id == App.strategy }.coerceAtLeast(0)) { pos ->
            val m = visible[pos]
            App.strategy = m.id; App.saveStrategy()
            snack(this, "${m.id} dipakai di Backtest.")
        }
    }

    private fun paintComboCount() {
        findViewById<TextView>(R.id.comboCount).text = "${App.comboExtra.size} combo dipilih"
    }

    private fun openComboSheet() {
        val ids = strategyList().filter { it.id != App.strategy }.map { it.id }
        val labels = ids.map { id ->
            val m = strategyList().find { it.id == id }!!
            "${m.name} (${m.id})"
        }
        showMultiCheckSheet("Strategi combo", labels, App.comboExtra.mapNotNull { id ->
            val m = strategyList().find { it.id == id && it.id != App.strategy }
            m?.let { "${it.name} (${it.id})" }
        }.toSet(), "Cari strategi…", null) { sel ->
            App.comboExtra = LinkedHashSet(sel.map { it.substringAfter("(").removeSuffix(")") })
            App.saveCombo()
            paintComboCount()
            snack(this, "${App.comboExtra.size} strategi combo tersimpan.")
        }
    }

    private fun runOpt() {
        val st = findViewById<TextView>(R.id.optStatus)
        val candles = App.candles
        val base = App.params
        if (candles.size < 60 || base == null) {
            st.text = "Jalankan backtest dulu."
            snack(this, "Jalankan backtest dulu.")
            return
        }
        st.text = "Mengoptimasi 27 kombinasi…"
        findViewById<MaterialButton>(R.id.btnOpt).isEnabled = false
        runBg {
            val maps = candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }
            val out = hyperoptSearch(maps, base)
            runOnUiThread {
                findViewById<MaterialButton>(R.id.btnOpt).isEnabled = true
                st.text = "Selesai: ${out.size} kombinasi. Terbaik ≠ aktif — terapkan manual."
                val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.optList)
                list.vertical(this)
                list.adapter = SigAdapter(
                    out.take(27).mapIndexed { i, o ->
                        SigItem(if (i == 0) "★" else "#${i + 1}",
                            "SL ${o.sl} · TP ${o.tp} · R ${o.risk}",
                            "Net ${App.fmtMoney(o.res.netProfit)} · PF ${App.fmtD(o.res.profitFactor)} · DD ${App.fmt(o.res.maxDrawdownPercent)}% · ${o.res.totalTrades} tr — klik = terapkan",
                            "")
                    },
                    onClick = { i ->
                        if (i < 5) {
                            val o = out[i]
                            App.slPct = o.sl; App.tpPct = o.tp; App.riskPct = o.risk
                            snack(this@LabActivity, "Tersimpan (SL ${o.sl} TP ${o.tp} R ${o.risk}). Buka Backtest lalu Run.")
                        } else snack(this@LabActivity, "Hanya 5 terbaik yang bisa diterapkan.")
                    })
            }
        }
    }
}
