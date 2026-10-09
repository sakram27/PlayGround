package com.aether.signal.premium.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
        val tfs = listOf("15m", "1h", "4h", "1d")
        seg.check(seg.getChildAt(tfs.indexOf(tf).coerceAtLeast(0)).id)
        for (i in 0 until seg.childCount) {
            seg.getChildAt(i).setOnClickListener {
                seg.check(seg.getChildAt(i).id)
                tf = tfs[i]
                setBar(sym, "$prov · $tf")
                load()
            }
        }
        setupChart()
        load()
    }

    private fun chart(): CombinedChart = findViewById(R.id.chart)

    private fun setupChart() {
        val c = chart()
        c.description.isEnabled = false
        c.setBackgroundColor(0xFF0B0E14.toInt())
        c.setDrawGridBackground(false)
        c.setPinchZoom(true)
        c.isDragEnabled = true
        c.setScaleEnabled(true)
        c.setAutoScaleMinMaxEnabled(true)
        c.legend.isEnabled = false
        c.axisLeft.isEnabled = false
        c.axisRight.apply {
            setDrawGridLines(true)
            gridColor = 0xFF232B36.toInt()
            textColor = 0xFF8B95A5.toInt()
            setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART)
        }
        c.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = 0xFF8B95A5.toInt()
            setAvoidFirstLastClipping(true)
        }
        c.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: com.github.mikephil.charting.data.Entry?, h: Highlight?) {}
            override fun onNothingSelected() {}
        })
    }

    private fun load() {
        findViewById<TextView>(R.id.status).text = "Mengambil $sym $tf…"
        runBg {
            try {
                val fr = getCandles(prov, sym, tf, 500)
                candles = fr.candles
                runOnUiThread {
                    paintChart()
                    val last = candles.last()
                    val ref = candles[maxOf(0, candles.size - 25)]
                    val ch = (last.c - ref.c) / ref.c * 100
                    findViewById<TextView>(R.id.sym).text = sym
                    findViewById<TextView>(R.id.price).apply {
                        text = "${App.fmt(last.c, if (last.c > 1000) 2 else 4)}  ${if (ch >= 0) "+" else ""}${App.fmt(ch)}%"
                        setTextColor(if (ch >= 0) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt())
                    }
                    findViewById<TextView>(R.id.status).text =
                        "${candles.size} candle · $prov · cache:${fr.meta.cacheUsed}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<TextView>(R.id.status).apply {
                        text = "Gagal: ${e.message}"
                        setTextColor(0xFFF6465D.toInt())
                    }
                }
            }
        }
    }

    private fun paintChart() {
        val c = chart()
        val cd = CombinedData()
        val ce = ArrayList<CandleEntry>()
        candles.forEachIndexed { i, k ->
            ce.add(CandleEntry(i.toFloat(), k.h.toFloat(), k.l.toFloat(), k.o.toFloat(), k.c.toFloat()))
        }
        val cs = CandleDataSet(ce, "OHLC").apply {
            setDrawValues(false)
            shadowColor = 0xFF8B95A5.toInt()
            decreasingColor = 0xFFF6465D.toInt()
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = 0xFF0ECB81.toInt()
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = 0xFF8B95A5.toInt()
            axisDependency = YAxis.AxisDependency.RIGHT
        }
        cd.setData(CandleData(cs))
        // volume
        var mv = 0.0
        for (k in candles) mv = maxOf(mv, k.v)
        if (mv > 0) {
            val be = ArrayList<BarEntry>()
            candles.forEachIndexed { i, k ->
                be.add(BarEntry(i.toFloat(), (k.v / mv * 15).toFloat()))
            }
            val bs = BarDataSet(be, "Vol").apply {
                setDrawValues(false)
                colors = candles.map { if (it.c >= it.o) 0x550ECB81.toInt() else 0x55F6465D.toInt() }
                axisDependency = YAxis.AxisDependency.RIGHT
            }
            cd.setData(BarData(bs).apply { barWidth = 0.7f })
        }
        // marker trade terakhir (bila cocok pair)
        val lr = App.result
        if (lr != null && lr.error == null && lr.asset == sym && lr.trades.isNotEmpty()) {
            val entries = ArrayList<com.github.mikephil.charting.data.Entry>()
            for (t in lr.trades.takeLast(120)) {
                val ei = candles.indexOfFirst { it.t == t.entryTime }
                val xi = candles.indexOfFirst { it.t == t.exitTime }
                if (ei >= 0) entries.add(com.github.mikephil.charting.data.Entry(ei.toFloat(), (if (t.direction == "LONG") candles[ei].l else candles[ei].h).toFloat()))
                if (xi >= 0) entries.add(com.github.mikephil.charting.data.Entry(xi.toFloat(), candles[xi].h.toFloat()))
            }
            val ss = ScatterDataSet(entries, "Trades").apply {
                setDrawValues(false)
                setScatterShape(com.github.mikephil.charting.charts.ScatterChart.ScatterShape.CIRCLE)
                color = 0xFF4C8DFF.toInt()
                scatterShapeSize = 14f
                axisDependency = YAxis.AxisDependency.RIGHT
            }
            cd.setData(ScatterData(ss))
            // garis SL/TP trade terakhir
            val last = lr.trades.last()
            c.axisRight.removeAllLimitLines()
            c.axisRight.addLimitLine(LimitLine(last.stopLoss.toFloat(), "SL").apply {
                lineColor = 0xFFF6465D.toInt(); lineWidth = 1.5f; enableDashedLine(8f, 6f, 0f)
                textColor = 0xFFF6465D.toInt(); textSize = 10f
            })
            c.axisRight.addLimitLine(LimitLine(last.takeProfit.toFloat(), "TP").apply {
                lineColor = 0xFF0ECB81.toInt(); lineWidth = 1.5f; enableDashedLine(8f, 6f, 0f)
                textColor = 0xFF0ECB81.toInt(); textSize = 10f
            })
        }
        val span = if (candles.size > 1) candles.last().t - candles.first().t else 0L
        val f = if (span > 86400000L * 2) SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        else SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val step = maxOf(1, candles.size / 5)
        val labels = ArrayList<String>()
        for (i in candles.indices) labels.add(if (i % step == 0) f.format(Date(candles[i].t)) else "")
        c.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        c.xAxis.granularity = 1f
        c.marker = OhlcMarker(this, candles)
        c.data = cd
        c.setVisibleXRangeMaximum(120f)
        c.moveViewToX((candles.size - 1).toFloat())
        c.animateX(400)
        c.invalidate()
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
