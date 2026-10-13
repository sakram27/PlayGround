package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.engine.Candle
import com.aether.signal.premium.engine.Trade
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import java.text.SimpleDateFormat
import java.util.*

// Pelukis chart tunggal untuk Graph + Backtest (J): satu sumber kebenaran.
// - Candle HIJAU naik / MERAH turun (J2), sumbu harga kanan + waktu bawah (J3).
// - Volume pada sumbu KIRI tersendiri (0–20, tanpa label) agar terlihat —
//   temuan audit: bar 0–15 pada sumbu harga ribuan = invisible.
// - Overlay loading/kosong/gagal + retry dikendalikan pemanggil (J6/J7).
// - TIDAK PERNAH membuat candle palsu: data kosong → return false (J8).

object ChartPaint {
    fun setup(c: CombinedChart, marker: com.github.mikephil.charting.components.MarkerView?) {
        c.description.isEnabled = false
        c.setBackgroundColor(0xFF080B12.toInt())
        c.setDrawGridBackground(false)
        c.setPinchZoom(true)
        c.isDragEnabled = true
        c.setScaleEnabled(true)
        c.setAutoScaleMinMaxEnabled(true)
        c.legend.isEnabled = false
        // Sumbu kiri KHUSUS volume: aktif tapi tanpa label/grid/garis.
        c.axisLeft.apply {
            isEnabled = true
            setDrawLabels(false); setDrawGridLines(false); setDrawAxisLine(false)
            axisMinimum = 0f; axisMaximum = 20f
        }
        c.axisRight.apply {
            setDrawGridLines(true); gridColor = 0xFF253244.toInt()
            textColor = 0xFF94A3B8.toInt(); setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART)
        }
        c.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM; setDrawGridLines(false)
            textColor = 0xFF94A3B8.toInt(); setAvoidFirstLastClipping(true)
        }
        if (marker != null) c.marker = marker
    }

    /** @return true bila ada candle digambar; false bila data kosong (pemanggil tampilkan overlay). */
    fun paint(
        c: CombinedChart,
        candles: List<Candle>,
        trades: List<Trade>?,
        markerOf: (List<Candle>) -> com.github.mikephil.charting.components.MarkerView
    ): Boolean {
        if (candles.isEmpty()) {
            c.clear(); c.invalidate()
            return false
        }
        // Validasi jujur (J): candle non-finite = data rusak, jangan digambar.
        if (candles.any { !(it.o.isFinite() && it.h.isFinite() && it.l.isFinite() && it.c.isFinite()) }) {
            c.clear(); c.invalidate()
            return false
        }
        val cd = CombinedData()
        val ce = ArrayList<CandleEntry>()
        candles.forEachIndexed { i, k ->
            ce.add(CandleEntry(i.toFloat(), k.h.toFloat(), k.l.toFloat(), k.o.toFloat(), k.c.toFloat()))
        }
        val cs = CandleDataSet(ce, "OHLC").apply {
            setDrawValues(false)
            shadowColor = 0xFF94A3B8.toInt()
            decreasingColor = 0xFFF87171.toInt()
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = 0xFF10B981.toInt()
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = 0xFF94A3B8.toInt()
            axisDependency = YAxis.AxisDependency.RIGHT
        }
        cd.setData(CandleData(cs))
        var mv = 0.0
        for (k in candles) mv = maxOf(mv, k.v)
        if (mv > 0) {
            val be = ArrayList<BarEntry>()
            candles.forEachIndexed { i, k -> be.add(BarEntry(i.toFloat(), (k.v / mv * 15).toFloat())) }
            val bs = BarDataSet(be, "Vol").apply {
                setDrawValues(false)
                colors = candles.map { if (it.c >= it.o) 0x5510B981.toInt() else 0x55F87171.toInt() }
                axisDependency = YAxis.AxisDependency.LEFT
            }
            cd.setData(BarData(bs).apply { barWidth = 0.7f })
        }
        if (!trades.isNullOrEmpty()) {
            val sc = ArrayList<com.github.mikephil.charting.data.Entry>()
            for (t in trades.takeLast(120)) {
                val ei = candles.indexOfFirst { it.t == t.entryTime }
                val xi = candles.indexOfFirst { it.t == t.exitTime }
                if (ei >= 0) sc.add(com.github.mikephil.charting.data.Entry(ei.toFloat(), (if (t.direction == "LONG") candles[ei].l else candles[ei].h).toFloat()))
                if (xi >= 0) sc.add(com.github.mikephil.charting.data.Entry(xi.toFloat(), candles[xi].h.toFloat()))
            }
            if (sc.isNotEmpty()) {
                val ss = ScatterDataSet(sc, "Trades").apply {
                    setDrawValues(false)
                    setScatterShape(com.github.mikephil.charting.charts.ScatterChart.ScatterShape.CIRCLE)
                    color = 0xFF3B82F6.toInt(); scatterShapeSize = 14f
                    axisDependency = YAxis.AxisDependency.RIGHT
                }
                cd.setData(ScatterData(ss))
            }
            val last = trades.last()
            c.axisRight.removeAllLimitLines()
            c.axisRight.addLimitLine(LimitLine(last.stopLoss.toFloat(), "SL").apply {
                lineColor = 0xFFF87171.toInt(); lineWidth = 1.5f; enableDashedLine(8f, 6f, 0f)
                textColor = 0xFFF87171.toInt(); textSize = 10f
            })
            c.axisRight.addLimitLine(LimitLine(last.takeProfit.toFloat(), "TP").apply {
                lineColor = 0xFF10B981.toInt(); lineWidth = 1.5f; enableDashedLine(8f, 6f, 0f)
                textColor = 0xFF10B981.toInt(); textSize = 10f
            })
        } else {
            c.axisRight.removeAllLimitLines()
        }
        val span = if (candles.size > 1) candles.last().t - candles.first().t else 0L
        val f = if (span > 86400000L * 2) SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        else SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val step = maxOf(1, candles.size / 5)
        val labels = ArrayList<String>()
        for (i in candles.indices) labels.add(if (i % step == 0) f.format(Date(candles[i].t)) else "")
        c.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        c.xAxis.granularity = 1f
        c.marker = markerOf(candles)
        c.data = cd
        c.setVisibleXRangeMaximum(120f)
        c.moveViewToX((candles.size - 1).toFloat())
        c.animateX(400)
        c.invalidate()
        return true
    }

    /** Overlay di atas chart: loading spinner / pesan + tombol retry opsional. */    fun overlay(root: View, loading: Boolean, msg: String?, showRetry: Boolean, onRetry: (() -> Unit)? = null) {
        val box = root.findViewById<View>(R.id.chartOverlay) ?: return
        val spinner = root.findViewById<View>(R.id.chartLoading)
        val tv = root.findViewById<TextView>(R.id.chartMsg)
        val retry = root.findViewById<View>(R.id.chartRetry)
        val idle = !loading && msg == null
        box.visibility = if (idle) View.GONE else View.VISIBLE
        spinner?.visibility = if (loading) View.VISIBLE else View.GONE
        if (msg != null) {
            tv?.visibility = View.VISIBLE
            tv?.text = msg
            tv?.setTextColor(if (showRetry) 0xFFF87171.toInt() else 0xFF94A3B8.toInt())
        } else tv?.visibility = View.GONE
        if (retry != null) {
            retry.visibility = if (!loading && showRetry) View.VISIBLE else View.GONE
            retry.setOnClickListener { try { onRetry?.invoke() } catch (e: Exception) { /* abaikan */ } }
        }
    }
}
