package com.aether.signal.premium.ui

import android.widget.LinearLayout
import android.widget.TextView
import com.aether.signal.premium.ai.aiAnalyze

class AiActivity : BaseActivity("lab") {
    override fun subtitle() = ""
    private var depth = 1
    private var box: LinearLayout? = null
    private var status: TextView? = null

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        appBar.showBack(true)
        val p = panel(this); body.addView(p)
        eyebrow(p, "Asisten analis — baca data engine, bukan ramalan")
        val seg = SegmentedControl(this)
        p.addView(seg)
        seg.setOptions(listOf("Ringkas", "Mendalam"), depth) { depth = it }
        p.addView(tbtn(this, "Jalankan Analisis", 0) { analyze() })
        status = statusTv(this); p.addView(status)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(box)
        desc(p, "Sinyal edukasi. Bukan nasihat keuangan. Tidak mengeksekusi order.")
    }

    private fun analyze() {
        status?.text = "Menganalisis…"
        Thread {
            val sumLine = App.result?.let { "${it.asset} ${it.timeframe} ${it.strategy} Net ${App.fmtMoney(it.netProfit)}" } ?: ""
            val blocks = aiAnalyze(App.signals, App.hist, sumLine, depth == 1)
            val prov = App.provider
            runOnUiThread {
                val b = box ?: return@runOnUiThread
                b.removeAllViews()
                desc(b, "Konteks: ${App.pair} · ${App.timeframe} · provider $prov · ${App.signals.size} sinyal, ${App.hist.size} trade. Analisis di bawah berasal dari interpreter lokal atas data engine — bukan model AI eksternal.")
                for (blk in blocks) {
                    val bp = TerminalCard(this)
                    title(bp, blk.title)
                    for ((tag, txt) in blk.items) {
                        val tagC = TextView(this).apply {
                            text = tag; textSize = T.CAP; typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(when (tag) { "DATA" -> C.MUT; "ANALYSIS" -> C.GREEN; else -> C.AMBER })
                        }
                        bp.addView(tagC)
                        bp.addView(TextView(this).apply { text = txt; setTextColor(C.TXT); textSize = T.BODY; setPadding(0, 0, 0, dp(this, 6)) })
                    }
                    b.addView(bp)
                }
                status?.text = "Selesai — ${App.signals.size} sinyal, ${App.hist.size} trade dibaca."
            }
        }.start()
    }
}
