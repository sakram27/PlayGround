package com.freqtrade.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.freqtrade.mobile.data.Candle

@Composable
fun CandleChart(candles: List<Candle>, modifier: Modifier = Modifier) {
    if (candles.isEmpty()) return
    val data = candles.takeLast(60)
    Canvas(modifier = modifier.height(220.dp).fillMaxWidth()) {
        val min = data.minOf { it.low }
        val max = data.maxOf { it.high }
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val w = size.width / data.size
        data.forEachIndexed { i, c ->
            fun y(p: Double) = size.height - ((p - min) / range * size.height).toFloat()
            val cx = (i * w + w / 2).toFloat()
            val up = c.close >= c.open
            val col = if (up) Color(0xFF26A69A) else Color(0xFFEF5350)
            drawLine(col, Offset(cx, y(c.high)), Offset(cx, y(c.low)), strokeWidth = 3f)
            val top = y(maxOf(c.open, c.close)); val bot = y(minOf(c.open, c.close))
            drawRect(col, topLeft = Offset(cx - w / 4, top), size = androidx.compose.ui.geometry.Size(w / 2, (bot - top).coerceAtLeast(2f)))
        }
    }
}

@Composable
fun ProfitBar(profits: List<Double>, modifier: Modifier = Modifier) {
    if (profits.isEmpty()) return
    Canvas(modifier = modifier.height(80.dp).fillMaxWidth()) {
        val max = (profits.map { kotlin.math.abs(it) }.maxOrNull() ?: 1.0).coerceAtLeast(0.01)
        val bw = size.width / profits.size
        profits.forEachIndexed { i, p ->
            val h = (kotlin.math.abs(p) / max * size.height / 2).toFloat()
            val col = if (p >= 0) Color(0xFF26A69A) else Color(0xFFEF5350)
            val x = (i * bw).toFloat()
            val y0 = size.height / 2
            val y1 = if (p >= 0) y0 - h else y0 + h
            drawRect(col, topLeft = Offset(x + 1, minOf(y0, y1)), size = androidx.compose.ui.geometry.Size((bw - 2).toFloat().coerceAtLeast(1f), kotlin.math.abs(y0 - y1).coerceAtLeast(2f)))
        }
        drawLine(Color.Gray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
    }
}
