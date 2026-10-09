package com.aether.signal.premium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

// Grafik ringkas harga (C): garis tipis + gradient halus, data nyata saja.
// Ukuran TETAP via layout (mis. 84x40dp) agar kartu stabil (C10); tanpa data
// valid -> INVISIBLE (ruang dipertahankan), bukan tren palsu (C9).

class SparkView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var pts: List<Float> = emptyList()
    private var up: Boolean = true
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // D: garis tipis 1.25dp (titik awal desain, elegan di semua density).
        strokeWidth = 1.25f * ctx.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setData(raw: List<Double>) {
        val clean = sanitizeSpark(raw)
        if (clean.size < 2) {
            pts = emptyList()
            visibility = INVISIBLE
            invalidate()
            return
        }
        visibility = VISIBLE
        up = clean.last() >= clean.first()
        pts = clean
        val col = if (up) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt()
        line.color = col
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        if (pts.size < 2) return
        val pad = 4f * resources.displayMetrics.density
        val w = width - pad * 2
        val h = height - pad * 2
        if (w <= 0 || h <= 0) return
        val mn = pts.minOrNull() ?: return
        val mx = pts.maxOrNull() ?: return
        val span = (mx - mn).takeIf { it > 0 } ?: 1f
        fun px(i: Int) = pad + i.toFloat() / (pts.size - 1) * w
        fun py(v: Float) = pad + h - (v - mn) / span * h
        val path = Path()
        pts.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(px(i), py(v)) else path.lineTo(px(i), py(v))
        }
        cv.drawPath(path, line)
        // gradient halus di bawah garis (C3)
        val fillPath = Path(path)
        fillPath.lineTo(px(pts.size - 1), pad + h)
        fillPath.lineTo(px(0), pad + h)
        fillPath.close()
        val base = if (up) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt()
        // D: gradient tipis-halus (~18% -> transparan), mengikuti arah harga nyata.
        fill.shader = LinearGradient(
            0f, pad, 0f, pad + h,
            (0x2E000000 or (base and 0x00FFFFFF)),
            0x00000000,
            Shader.TileMode.CLAMP
        )
        cv.drawPath(fillPath, fill)
    }
}

