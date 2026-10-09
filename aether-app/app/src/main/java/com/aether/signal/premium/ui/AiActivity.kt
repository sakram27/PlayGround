package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.ai.aiAnalyze
import com.google.android.material.button.MaterialButtonToggleGroup

class AiActivity : BaseActivity(R.id.nav_lab) {
    override val contentLayout = R.layout.activity_ai
    override val showBack = true
    private var depth = 0

    override fun build() {
        setBar("AI Analysis", "Interpreter lokal · bukan ramalan")
        val seg = findViewById<MaterialButtonToggleGroup>(R.id.segDepth)
        seg.check(seg.getChildAt(0).id)
        seg.addOnButtonCheckedListener { _, id, checked ->
            if (checked) depth = if (id == seg.getChildAt(1).id) 1 else 0
        }
        findViewById<View>(R.id.btnRun).setOnClickListener { analyze() }
    }

    private fun analyze() {
        findViewById<TextView>(R.id.status).text = "Menganalisis…"
        runBg {
            val sumLine = App.result?.let { "${it.asset} ${it.timeframe} ${it.strategy} Net ${App.fmtMoney(it.netProfit)}" } ?: ""
            val blocks = aiAnalyze(App.signals, App.hist, sumLine, depth == 1)
            runOnUiThread {
                findViewById<TextView>(R.id.context).text =
                    "Konteks: ${App.pair} · ${App.timeframe} · ${App.provider} · ${App.signals.size} sinyal, ${App.hist.size} trade — dari interpreter lokal, bukan model eksternal."
                val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.blocks)
                list.vertical(this)
                val flat = ArrayList<SigItem>()
                for (b in blocks) {
                    flat.add(SigItem("§", b.title, "", ""))
                    for ((tag, txt) in b.items) flat.add(SigItem(tag, txt, "", ""))
                }
                list.adapter = SigAdapter(flat)
                findViewById<TextView>(R.id.status).text =
                    "Selesai — ${App.signals.size} sinyal, ${App.hist.size} trade dibaca."
            }
        }
    }
}
