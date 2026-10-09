package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.aether.signal.premium.engine.*
import com.google.android.material.button.MaterialButton

class LabActivity : BaseActivity(R.id.nav_lab) {
    override val contentLayout = R.layout.activity_lab
    private var query = ""

    override fun build() {
        setBar("Strategy Lab", "Strategi · Hyperopt · riset")
        findViewById<MaterialButton>(R.id.btnBacktest).setOnClickListener { navKeep("backtest") }
        findViewById<MaterialButton>(R.id.btnAi).setOnClickListener { navKeep("ai") }
        val lb = App.lastBt()
        findViewById<TextView>(R.id.lastBt).text =
            if (lb == null) "Belum ada backtest."
            else "Terakhir: ${lb.mode} · ${lb.pairs.joinToString(",")} · ${lb.trades} trade · ${lb.status}"
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
            .addTextChangedListener { query = (it?.toString() ?: ""); paintStrat() }
        paintStrat()
        findViewById<MaterialButton>(R.id.btnOpt).setOnClickListener { runOpt() }
    }

    private fun paintStrat() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.stratList)
        list.vertical(this)
        val items = strategyList()
            .filter { query.isEmpty() || it.name.lowercase().contains(query.lowercase()) || it.id.contains(query.lowercase()) }
            .map { StratItem(it.id, it.name, "${it.id} · ${it.desc}", App.strategy == it.id, App.comboExtra.contains(it.id) || defOn(it.id)) }
        list.adapter = StratAdapter(items, onUse = {
            App.strategy = it.id; App.saveStrategy()
            snack(this, "${it.id} dipakai di Backtest.")
            paintStrat()
        }, onCombo = { item, on ->
            if (on) App.comboExtra.add(item.id) else App.comboExtra.remove(item.id)
        })
    }

    private fun defOn(id: String): Boolean =
        App.comboExtra.contains(id) || setOf("ema_trend", "supertrend", "rsi", "macd", "breakout").contains(id)

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
                list.adapter = object : SigAdapter(
                    out.take(27).mapIndexed { i, o ->
                        SigItem(if (i == 0) "★" else "#${i + 1}",
                            "SL ${o.sl} · TP ${o.tp} · R ${o.risk}",
                            "Net ${App.fmtMoney(o.res.netProfit)} · PF ${App.fmtD(o.res.profitFactor)} · DD ${App.fmt(o.res.maxDrawdownPercent)}% · ${o.res.totalTrades} tr — klik = terapkan",
                            "")
                    }) {
                    override fun onBindViewHolder(h: H, i: Int) {
                        super.onBindViewHolder(h, i)
                        h.itemView.setOnClickListener {
                            if (i < 5) {
                                val o = out[i]
                                App.slPct = o.sl; App.tpPct = o.tp; App.riskPct = o.risk
                                snack(this@LabActivity, "Tersimpan (SL ${o.sl} TP ${o.tp} R ${o.risk}). Buka Backtest lalu Run.")
                            } else snack(this@LabActivity, "Hanya 5 terbaik yang bisa diterapkan.")
                        }
                    }
                }
            }
        }
    }
}
