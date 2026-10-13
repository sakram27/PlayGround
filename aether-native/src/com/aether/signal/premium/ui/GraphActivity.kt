package com.aether.signal.premium.ui

import android.app.Activity
import android.content.Intent
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.engine.normalizeCandles
import com.aether.signal.premium.ui.charts.CandleChartView

class GraphActivity : BaseActivity("markets") {
    companion object {
        fun open(a: Activity, sym: String, prov: String, tf: String) {
            a.startActivity(Intent(a, GraphActivity::class.java).putExtra("s", sym).putExtra("p", prov).putExtra("t", tf))
        }
    }

    private var sym = ""; private var prov = ""; private var tf = "1h"
    private var headPrice: TextView? = null
    private var chart: CandleChartView? = null
    private var status: TextView? = null

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        sym = intent.getStringExtra("s") ?: App.pair
        prov = intent.getStringExtra("p") ?: App.provider
        tf = intent.getStringExtra("t") ?: App.timeframe
        appBar.showBack(true)
        // HEADER HARGA ala TradingView
        val h = panel(this); body.addView(h)
        val r = row(h)
        r.addView(TextView(this).apply { text = sym; textSize = 20f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        headPrice = TextView(this).apply { text = "…"; textSize = 20f; typeface = android.graphics.Typeface.MONOSPACE; setTextColor(C.TXT); gravity = android.view.Gravity.END }
        r.addView(headPrice)
        status = statusTv(this); h.addView(status)
        // TIMEFRAME
        val t = panel(this); body.addView(t)
        eyebrow(t, "Timeframe")
        val seg = SegmentedControl(this)
        t.addView(seg)
        val tfs = listOf("15m", "1h", "4h", "1d")
        seg.setOptions(tfs, tfs.indexOf(tf).coerceAtLeast(0)) { tf = tfs[it]; load() }
        // GRAFIK LUAS
        val g = panel(this); body.addView(g)
        chart = CandleChartView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(this, 380)) }
        g.addView(chart)
        val or = row(g)
        for ((label, get, set) in listOf(
            Triple("Vol", { chart?.showVol == true }, { v: Boolean -> chart?.showVol = v }),
            Triple("E20", { chart?.showE20 == true }, { v: Boolean -> chart?.showE20 = v }),
            Triple("E50", { chart?.showE50 == true }, { v: Boolean -> chart?.showE50 = v }),
            Triple("E200", { chart?.showE200 == true }, { v: Boolean -> chart?.showE200 = v })
        )) {
            val cb = CheckBox(this).apply { text = label; isChecked = get(); setTextColor(C.MUT); textSize = T.SMALL }
            cb.setOnCheckedChangeListener { _, v -> set(v); chart?.invalidate() }
            or.addView(cb)
        }
        desc(g, "Sentuh grafik = crosshair + OHLC. Pinch-zoom belum didukung pada versi ini.")
        load()
    }

    private fun load() {
        status?.text = "Mengambil $sym $tf…"
        chart?.candles = emptyList(); chart?.invalidate()
        Thread {
            try {
                val fr = getCandles(prov, sym, tf, 500)
                runOnUiThread {
                    chart?.candles = fr.candles
                    val lr = App.result
                    chart?.trades = if (lr != null && lr.error == null && lr.asset == sym) lr.trades else emptyList()
                    chart?.invalidate()
                    val last = fr.candles.last()
                    val ref = fr.candles[maxOf(0, fr.candles.size - 25)]
                    val ch = (last.c - ref.c) / ref.c * 100
                    headPrice?.text = "${App.fmt(last.c, if (last.c > 1000) 2 else 4)}  ${if (ch >= 0) "+" else ""}${App.fmt(ch)}%"
                    headPrice?.setTextColor(if (ch >= 0) C.GREEN else C.RED)
                    status?.text = "${fr.candles.size} candle · $prov · cache:${fr.meta.cacheUsed}"
                }
            } catch (e: Exception) {
                runOnUiThread { status?.text = "Gagal: ${e.message}"; status?.setTextColor(C.RED) }
            }
        }.start()
    }
}
