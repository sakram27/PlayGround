package com.aether.signal.premium.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.engine.Candle
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.util.*

class GraphActivity : BaseActivity(R.id.nav_markets) {
    override val contentLayout = R.layout.activity_graph
    override val showBack = true

    companion object {
        fun open(a: Activity, sym: String, prov: String, tf: String) {
            a.startActivity(Intent(a, GraphActivity::class.java).putExtra("s", sym).putExtra("p", prov).putExtra("t", tf))
        }
    }

    private var sym = ""; private var prov = ""; private var tf = "1h"
    private var candles: List<Candle> = emptyList()

    override fun build() {
        sym = intent.getStringExtra("s") ?: App.pair
        prov = intent.getStringExtra("p") ?: App.provider
        tf = intent.getStringExtra("t") ?: App.timeframe
        setBar(sym, "$prov · $tf")
        val seg = findViewById<MaterialButtonToggleGroup>(R.id.segTf)
        val tfs = listOf("5m", "15m", "1h", "4h", "1d")
        val tfIds = listOf(R.id.segTfM5, R.id.segTfM15, R.id.segTfH1, R.id.segTfH4, R.id.segTfD1)
        seg.check(tfIds[tfs.indexOf(tf).coerceAtLeast(0)])
        seg.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val i = tfIds.indexOf(id).coerceAtLeast(0)
                tf = tfs[i]
                setBar(sym, "$prov · $tf")
                load()
            }
        }
        setupChart()
        load()
    }

    private fun chart(): CombinedChart = findViewById(R.id.chart)

    private fun chartRoot(): View = findViewById<View>(R.id.chartOverlay)?.parent as View

    private fun setupChart() {
        ChartPaint.setup(chart(), OhlcMarker(this, emptyList()))
    }

    private fun load() {
        findViewById<TextView>(R.id.status).text = "Mengambil $sym $tf…"
        ChartPaint.overlay(chartRoot(), loading = true, msg = "Memuat $sym $tf…", showRetry = false)
        runBg {
            try {
                val fr = getCandles(prov, sym, tf, 500)
                candles = fr.candles
                runOnUiThread {
                    val lr = App.result
                    val drawn = ChartPaint.paint(chart(), candles,
                        if (lr != null && lr.error == null && lr.asset == sym) lr.trades else null) { OhlcMarker(this, it) }
                    if (!drawn) {
                        ChartPaint.overlay(chartRoot(), loading = false,
                            msg = "Data $sym tidak valid atau kosong.", showRetry = true) { load() }
                    } else {
                        ChartPaint.overlay(chartRoot(), loading = false, msg = null, showRetry = false)
                    }
                    val last = candles.last()
                    val ref = candles[maxOf(0, candles.size - 25)]
                    val ch = (last.c - ref.c) / ref.c * 100
                    findViewById<TextView>(R.id.sym).text = sym
                    try {
                        findViewById<ImageView>(R.id.pairIcon).setImageDrawable(PairIcons.iconFor(this, sym))
                    } catch (e: Exception) { /* ikon opsional */ }
                    findViewById<TextView>(R.id.price).apply {
                        text = "${App.fmt(last.c, if (last.c > 1000) 2 else 4)}  ${if (ch >= 0) "+" else ""}${App.fmt(ch)}%"
                        setTextColor(if (ch >= 0) 0xFF10B981.toInt() else 0xFFF87171.toInt())
                    }
                    findViewById<TextView>(R.id.status).text =
                        "${candles.size} candle · $prov · cache:${fr.meta.cacheUsed}"
                }
            } catch (e: Exception) {
                // J10: kegagalan tampil jujur + retry; chart lama dibersihkan agar tak menipu.
                candles = emptyList()
                runOnUiThread {
                    chart().clear(); chart().invalidate()
                    ChartPaint.overlay(chartRoot(), loading = false,
                        msg = "Gagal memuat $sym ($tf): ${e.message}", showRetry = true) { load() }
                    findViewById<TextView>(R.id.status).apply {
                        text = "Gagal: ${e.message}"
                        setTextColor(0xFFF87171.toInt())
                    }
                }
            }
        }
    }

    private fun paintChart() {
        // Didelegasikan ke ChartPaint (sumber tunggal). Dipertahankan sebagai
        // jembatan agar pemanggil lama tidak rusak.
        val lr = App.result
        ChartPaint.paint(chart(), candles,
            if (lr != null && lr.error == null && lr.asset == sym) lr.trades else null) { OhlcMarker(this, it) }
    }
}

class OhlcMarker(ctx: android.content.Context, private val candles: List<Candle>) :
    com.github.mikephil.charting.components.MarkerView(ctx, R.layout.marker_ohlc) {
    private val tv: TextView = findViewById(R.id.ohlc)
    override fun refreshContent(e: com.github.mikephil.charting.data.Entry?, h: Highlight?) {
        val i = e?.x?.toInt()?.coerceIn(0, candles.size - 1) ?: 0
        val c = candles[i]
        tv.text = "O${p(c.o)} H${p(c.h)} L${p(c.l)} C${p(c.c)}"
        super.refreshContent(e, h)
    }
    private fun p(v: Double): String = when {
        !v.isFinite() -> "—"; v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 100 -> String.format(Locale.US, "%.3f", v); else -> String.format(Locale.US, "%.4f", v)
    }
    override fun getOffset(): com.github.mikephil.charting.utils.MPPointF =
        com.github.mikephil.charting.utils.MPPointF((-(width / 2)).toFloat(), (-height).toFloat())
}
