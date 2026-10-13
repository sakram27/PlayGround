package com.aether.signal.premium.ui.charts

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.aether.signal.premium.engine.Candle
import com.aether.signal.premium.engine.EquityPoint
import com.aether.signal.premium.engine.Trade
import com.aether.signal.premium.engine.ema
import com.aether.signal.premium.ui.C
import com.aether.signal.premium.ui.dpC
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// Grafik terminal: candle proporsional + sumbu harga/waktu + EMA + volume +
// marker + SL/TP + tag harga terakhir + crosshair + legenda. Data nyata saja.

class CandleChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var candles: List<Candle> = emptyList()
    var trades: List<Trade> = emptyList()
    var showVol = true; var showE20 = true; var showE50 = true; var showE200 = false
    var touchX = -1f

    private val upP = Paint().apply { color = C.GREEN; style = Paint.Style.FILL }
    private val dnP = Paint().apply { color = C.RED; style = Paint.Style.FILL }
    private val gridP = Paint().apply { color = C.LINE; strokeWidth = 1f }
    private val axP = Paint().apply { color = C.MUT; textSize = 20f; typeface = Typeface.MONOSPACE }
    private val e20P = Paint().apply { color = 0xFF4C8DFF.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE }
    private val e50P = Paint().apply { color = C.GREEN; strokeWidth = 2.5f; style = Paint.Style.STROKE }
    private val e200P = Paint().apply { color = 0xFF9A8CF0.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE }
    private val tfFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val tfFmtD = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        touchX = e.x
        invalidate()
        return true
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat(); val h = height.toFloat()
        cv.drawColor(0xFF0B0E14.toInt())
        if (candles.isEmpty()) {
            val p = Paint().apply { color = C.DIM; textSize = 24f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
            cv.drawText("NO MARKET DATA", w / 2, h / 2, p)
            return
        }
        val axisW = dpC(context, 56).toFloat()
        val axisH = dpC(context, 22).toFloat()
        val padT = dpC(context, 8).toFloat()
        val cw = w - axisW; val ch = h - axisH - padT
        val volH = ch * 0.15f
        val priceH = ch - volH
        var mn = Double.POSITIVE_INFINITY; var mx = Double.NEGATIVE_INFINITY
        for (c in candles) { mn = min(mn, c.l); mx = max(mx, c.h) }
        val pad = (mx - mn) * 0.07 + 1e-9
        mn -= pad; mx += pad
        val n1 = (candles.size - 1).coerceAtLeast(1)
        fun px(i: Int) = (i.toFloat() / n1) * (cw - 8) + 4
        fun py(p: Double) = (padT + priceH - (p - mn) / (mx - mn) * priceH).toFloat()
        // grid + label sumbu harga
        for (g in 0..4) {
            val y = padT + priceH * g / 4
            cv.drawLine(0f, y, cw, y, gridP)
            val pv = mx - (mx - mn) * g / 4
            cv.drawText(fmtP(pv), cw + 6, y + 7, axP)
        }
        // label sumbu waktu
        for (k in 0..4) {
            val i = (k * (candles.size - 1) / 4).coerceIn(0, candles.size - 1)
            val t = candles[i].t
            val span = candles.last().t - candles.first().t
            cv.drawText(if (span > 86400000L * 2) tfFmtD.format(Date(t)) else tfFmt.format(Date(t)), px(i) - 30, h - 6, axP)
        }
        // volume
        var mv = 0.0
        for (c in candles) mv = max(mv, c.v)
        if (showVol && mv > 0) {
            upP.alpha = 110; dnP.alpha = 110
            for (i in candles.indices) {
                val c = candles[i]
                cv.drawRect(px(i) - 2, padT + priceH + volH - (c.v / mv * volH).toFloat(), px(i) + 2, padT + priceH + volH, if (c.c >= c.o) upP else dnP)
            }
            upP.alpha = 255; dnP.alpha = 255
        }
        // EMA
        val closes = candles.map { it.c }
        fun emaLine(period: Int, paint: Paint) {
            val e = ema(closes, period)
            var started = false
            val path = Path()
            for (i in candles.indices) {
                if (e[i].isNaN()) continue
                if (!started) { path.moveTo(px(i), py(e[i])); started = true } else path.lineTo(px(i), py(e[i]))
            }
            cv.drawPath(path, paint)
        }
        if (showE20) emaLine(20, e20P)
        if (showE50) emaLine(50, e50P)
        if (showE200) emaLine(200, e200P)
        // candle
        val bw = max(2.5f, (cw / candles.size) * 0.62f)
        for (i in candles.indices) {
            val c = candles[i]
            val p = if (c.c >= c.o) upP else dnP
            cv.drawRect(px(i) - bw / 2, py(max(c.o, c.c)), px(i) + bw / 2, py(min(c.o, c.c)), p)
            cv.drawLine(px(i), py(c.h), px(i), py(c.l), p)
        }
        // marker trade (120 terakhir)
        val mp = Paint().apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        for (t in trades.takeLast(120)) {
            val ei = candles.indexOfFirst { it.t == t.entryTime }
            val xi = candles.indexOfFirst { it.t == t.exitTime }
            if (ei >= 0) {
                mp.color = if (t.direction == "LONG") C.GREEN else C.RED
                cv.drawText(if (t.direction == "LONG") "▲" else "▼", px(ei), py(if (t.direction == "LONG") candles[ei].l else candles[ei].h) + (if (t.direction == "LONG") 24 else -10), mp)
            }
            if (xi >= 0) {
                mp.color = if (t.result == "WIN") 0xFF4C8DFF.toInt() else C.AMBER
                cv.drawText(if (t.result == "WIN") "●" else "✕", px(xi), py(candles[xi].h) - 10, mp)
            }
        }
        // SL/TP trade terakhir
        val last = trades.lastOrNull()
        if (last != null) {
            val slP = Paint().apply { color = C.RED; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f) }
            val tpP = Paint().apply { color = C.GREEN; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f) }
            cv.drawLine(0f, py(last.stopLoss), cw, py(last.stopLoss), slP)
            cv.drawLine(0f, py(last.takeProfit), cw, py(last.takeProfit), tpP)
            cv.drawText("SL", cw + 6, py(last.stopLoss) + 7, axP.apply { color = C.RED })
            cv.drawText("TP", cw + 6, py(last.takeProfit) + 7, axP.apply { color = C.GREEN })
            axP.color = C.MUT
        }
        // tag harga terakhir (pill)
        val lc = candles.last()
        val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (lc.c >= lc.o) C.GREEN else C.RED }
        val tagT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF06110D.toInt(); textSize = 20f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        val ty = py(lc.c)
        cv.drawRoundRect(cw + 2, ty - 16, w - 2, ty + 16, 8f, 8f, tagP)
        cv.drawText(fmtP(lc.c), cw + (w - cw) / 2, ty + 7, tagT)
        // legenda EMA
        var lx = 10f
        for ((nm, on, col) in listOf(Triple("EMA20", showE20, 0xFF4C8DFF.toInt()), Triple("EMA50", showE50, C.GREEN), Triple("EMA200", showE200, 0xFF9A8CF0.toInt()))) {
            if (!on) continue
            val lp = Paint().apply { color = col; strokeWidth = 4f }
            cv.drawLine(lx, padT + 8, lx + 26, padT + 8, lp)
            cv.drawText(nm, lx + 30, padT + 15, axP)
            lx += 30 + axP.measureText(nm) + 22
        }
        // crosshair + OHLC
        if (touchX in 0f..cw) {
            val idx = ((touchX - 4) / (cw - 8) * (candles.size - 1)).toInt().coerceIn(0, candles.size - 1)
            val cp = Paint().apply { color = 0xFF46566E.toInt(); strokeWidth = 1.5f }
            cv.drawLine(px(idx), padT, px(idx), padT + ch, cp)
            val c = candles[idx]
            val bg = Paint().apply { color = 0xE6171D26.toInt() }
            cv.drawRect(6f, h - axisH - 26, 560f.coerceAtMost(w - 6), h - 4, bg)
            cv.drawText("O${fmtP(c.o)}  H${fmtP(c.h)}  L${fmtP(c.l)}  C${fmtP(c.c)}", 12f, h - 10, axP)
        }
    }

    private fun fmtP(v: Double): String = when {
        !v.isFinite() -> "—"
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 100 -> String.format(Locale.US, "%.3f", v)
        else -> String.format(Locale.US, "%.4f", v)
    }
}

class EquityView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var curve: List<EquityPoint> = emptyList()
    private val lineP = Paint().apply { color = C.GREEN; strokeWidth = 3.5f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val fillP = Paint().apply { color = 0x330ECB81.toInt(); style = Paint.Style.FILL }
    private val ddP = Paint().apply { color = 0xB3F6465D.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val txtP = Paint().apply { color = C.MUT; textSize = 20f; typeface = Typeface.MONOSPACE }
    private val gridP = Paint().apply { color = C.LINE; strokeWidth = 1f }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat(); val h = height.toFloat()
        cv.drawColor(0xFF0B0E14.toInt())
        if (curve.isEmpty()) {
            txtP.textAlign = Paint.Align.CENTER
            cv.drawText("NO TRADES — equity datar di modal awal", w / 2, h / 2, txtP)
            return
        }
        val axisW = dpC(context, 52).toFloat()
        val cw = w - axisW
        var mn = Double.POSITIVE_INFINITY; var mx = Double.NEGATIVE_INFINITY
        for (e in curve) { mn = min(mn, e.equity); mx = max(mx, e.equity) }
        val pad = (mx - mn) * 0.1 + 1e-9
        mn -= pad; mx += pad
        fun px(i: Int) = i.toFloat() / (curve.size - 1).coerceAtLeast(1) * (cw - 6) + 3
        fun py(v: Double) = (8 + (h - 16) - (v - mn) / (mx - mn) * (h - 16)).toFloat()
        for (g in 0..3) {
            val y = 8 + (h - 16) * g / 3
            cv.drawLine(0f, y, cw, y, gridP)
            cv.drawText(String.format(Locale.US, "%.0f", mx - (mx - mn) * g / 3), cw + 5, y + 6, txtP)
        }
        val path = Path(); val fill = Path()
        curve.forEachIndexed { i, e ->
            if (i == 0) { path.moveTo(px(i), py(e.equity)); fill.moveTo(px(i), h); fill.lineTo(px(i), py(e.equity)) }
            else { path.lineTo(px(i), py(e.equity)); fill.lineTo(px(i), py(e.equity)) }
        }
        fill.lineTo(px(curve.size - 1), h); fill.close()
        cv.drawPath(fill, fillP)
        cv.drawPath(path, lineP)
        var peak = Double.NEGATIVE_INFINITY
        val dd = Path()
        var started = false
        curve.forEachIndexed { i, e ->
            peak = max(peak, e.equity)
            val v = if (peak > 0) (e.equity - peak) / peak * 100 else 0.0
            val y = h - 6 - ((v + 15).coerceIn(0.0, 30.0) / 30 * (h * 0.22)).toFloat()
            if (!started) { dd.moveTo(px(i), y); started = true } else dd.lineTo(px(i), y)
        }
        cv.drawPath(dd, ddP)
        val last = curve.last()
        txtP.color = if (last.equity >= curve.first().equity) C.GREEN else C.RED
        cv.drawText(String.format(Locale.US, "%.2f", last.equity), cw + 5, py(last.equity) + 6, txtP)
        txtP.color = C.MUT
    }
}
