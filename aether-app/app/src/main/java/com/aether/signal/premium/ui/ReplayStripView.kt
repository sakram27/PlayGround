package com.aether.signal.premium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

// Strip harga native sederhana untuk replay (tanpa pustaka grafik):
// polyline N harga penutupan terakhir + penanda entry/exit + kursor.

// kind: "entry" | "win" | "loss" | "expired"
data class StripMarker(val idx: Int, val kind: String)

class ReplayStripView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {
    private var closes: List<Double> = emptyList()
    private var markers: List<StripMarker> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8EDF2.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB800.toInt(); strokeWidth = 3f
    }
    private val gridPaint = Paint().apply { color = 0xFF232B36.toInt(); strokeWidth = 1f }

    fun setData(closes: List<Double>, markers: List<StripMarker>) {
        this.closes = closes.filter { it.isFinite() }
        this.markers = markers
        invalidate()
    }

    private fun markerPaint(kind: String): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (kind) {
            "entry" -> 0xFF4C8DFF.toInt()
            "win" -> 0xFF0ECB81.toInt()
            "loss" -> 0xFFF6465D.toInt()
            else -> 0xFF8B95A5.toInt()
        }
        style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        c.drawLine(0f, h / 2, w, h / 2, gridPaint)
        val cs = closes
        if (cs.size < 2) return
        var mn = cs[0]; var mx = cs[0]
        for (v in cs) { if (v < mn) mn = v; if (v > mx) mx = v }
        if (!(mx > mn)) { mx = mn + 1.0 }
        val n = cs.size
        fun x(i: Int) = if (n == 1) w / 2 else i.toFloat() / (n - 1) * w
        fun y(v: Double) = (h - 12) - ((v - mn) / (mx - mn) * (h - 24)).toFloat()
        var px = x(0); var py = y(cs[0])
        for (i in 1 until n) {
            val nx = x(i); val ny = y(cs[i])
            c.drawLine(px, py, nx, ny, linePaint)
            px = nx; py = ny
        }
        // Kursor = bar terakhir (paling kanan).
        c.drawLine(w - 2, 0f, w - 2, h, cursorPaint)
        for (m in markers) {
            if (m.idx !in cs.indices) continue
            c.drawCircle(x(m.idx), y(cs[m.idx]), 9f, markerPaint(m.kind))
        }
    }
}
