package com.aether.signal.premium.ui

import com.aether.signal.premium.engine.Trade
import com.aether.signal.premium.engine.epochDay
import com.aether.signal.premium.engine.utcHour

// FITUR 4 (V14): agregasi profitabilitas per jam & hari — MURNI & teruji JVM.
// Sumber: daftar Trade nyata mesin. Dikelompokkan by JAM KELUAR (bukan entry;
// entry tak disimpulkan) dalam UTC — zona waktu dinyatakan eksplisit di UI.
// WinRate mengikuti definisi buildResult (menang/total*100, EXPIRED ikut penyebut).
// Bucket kosong: count=0, net=0.0, winRate=null (UI tampil "—", tanpa /0).

/** Agregat satu bucket waktu. */
data class TimeBucket(
    val label: String,
    val count: Int,
    val net: Double,
    val winRate: Double?
)

/** Hasil agregasi: 24 jam (00–23) + 7 hari (Sen–Min). */
data class TimeHeatmap(
    val hours: List<TimeBucket>,
    val days: List<TimeBucket>,
    val totalNet: Double,
    val totalTrades: Int
)

private val DAY_LABELS = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

/** 1970-01-01 = Kamis. Peta epochDay → indeks Senin(0)..Minggu(6). */
fun dayOfWeekMon0(ts: Long): Int = (((epochDay(ts) + 3) % 7) + 7).toInt() % 7

private fun bucketOf(label: String, ts: List<Trade>): TimeBucket {
    if (ts.isEmpty()) return TimeBucket(label, 0, 0.0, null)
    val wins = ts.count { it.result == "WIN" }
    return TimeBucket(label, ts.size, ts.sumOf { it.pnl }, wins.toDouble() / ts.size * 100)
}

/** Bangun heatmap dari transaksi sumber. Total net selalu = Σ pnl sumber. */
fun buildTimeHeatmap(trades: List<Trade>): TimeHeatmap {
    val hourGroups = Array(24) { ArrayList<Trade>() }
    val dayGroups = Array(7) { ArrayList<Trade>() }
    for (t in trades) {
        val h = utcHour(t.exitTime)
        if (h in 0..23) hourGroups[h].add(t)
        val d = dayOfWeekMon0(t.exitTime)
        dayGroups[d].add(t)
    }
    val hours = hourGroups.mapIndexed { h, ts ->
        bucketOf(String.format("%02d", h), ts)
    }
    val days = dayGroups.mapIndexed { d, ts ->
        bucketOf(DAY_LABELS[d], ts)
    }
    return TimeHeatmap(hours, days, trades.sumOf { it.pnl }, trades.size)
}

/** Bucket terisi terbaik/terburuk (by net); null bila tak ada transaksi. */
fun bestBucket(buckets: List<TimeBucket>): TimeBucket? =
    buckets.filter { it.count > 0 }.maxByOrNull { it.net }

fun worstBucket(buckets: List<TimeBucket>): TimeBucket? =
    buckets.filter { it.count > 0 }.minByOrNull { it.net }
