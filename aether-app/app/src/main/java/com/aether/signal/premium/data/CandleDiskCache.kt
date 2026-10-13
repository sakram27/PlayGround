package com.aether.signal.premium.data

import com.aether.signal.premium.engine.Candle
import java.io.File

// P5: cache candle lokal (grafik tetap tampil saat offline).
// - Kunci: provider|simbol|timeframe. Batas: 40 berkas × 1000 candle, LRU.
// - Candle belum tutup dibuang saat baca (lebih muda dari 1×TF).
// - Inti MURNI (teruji JVM); objek tipis memakai direktori aplikasi (tanpa izin khusus).

const val DISK_CACHE_MAX_FILES = 40
const val DISK_CACHE_MAX_CANDLES = 1000

fun diskKey(provider: String, symbol: String, timeframe: String): String {
    val s = "${provider}_${symbol.uppercase()}_$timeframe"
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return "$s.jsonl"
}

/** Serialisasi murni: baris "t,o,h,l,c,v". */
fun serializeCandles(candles: List<Candle>): String =
    candles.takeLast(DISK_CACHE_MAX_CANDLES).joinToString("\n") {
        "${it.t},${it.o},${it.h},${it.l},${it.c},${it.v}"
    }

/** Parsing murni: lewati baris rusak, dedup timestamp, urut waktu. */
fun parseCandles(text: String): List<Candle> {
    val seen = HashSet<Long>()
    val out = ArrayList<Candle>()
    for (line in text.split("\n")) {
        val p = line.trim().split(",")
        if (p.size < 6) continue
        try {
            val t = p[0].toLong()
            if (t <= 0 || !seen.add(t)) continue
            val o = p[1].toDouble(); val h = p[2].toDouble(); val l = p[3].toDouble()
            val c = p[4].toDouble(); val v = p[5].toDouble().let { if (it.isFinite() && it >= 0) it else 0.0 }
            if (!o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite()) continue
            out.add(Candle(t, o, h, l, c, v))
        } catch (e: Exception) { continue }
    }
    out.sortBy { it.t }
    return out
}

/** Buang candle yang belum tutup (lebih muda dari 1×TF) — murni, teruji. */
fun dropUnclosed(candles: List<Candle>, tfMinutes: Int, nowMs: Long): List<Candle> {
    if (candles.isEmpty() || tfMinutes <= 0) return candles
    val cutoff = nowMs - tfMinutes * 60_000L
    return if (candles.last().t > cutoff) candles.dropLast(1) else candles
}

object CandleDiskCache {
    var dir: File? = null

    fun attach(d: File) {
        dir = d
    }

    fun save(provider: String, symbol: String, timeframe: String, candles: List<Candle>) {
        val base = dir ?: return
        try {
            if (!base.exists()) base.mkdirs()
            val f = File(base, diskKey(provider, symbol, timeframe))
            // Dedup + urut sebelum simpan; batasi ukuran.
            val clean = parseCandles(serializeCandles(candles))
            f.writeText(serializeCandles(clean))
            prune(base)
        } catch (e: Exception) { /* cache tak boleh crash */ }
    }

    /** @return Pair(candleBersih, waktuSimpanMs) atau null bila tak layak (min 60). */
    fun load(provider: String, symbol: String, timeframe: String, tfMinutes: Int, nowMs: Long = System.currentTimeMillis()): Pair<List<Candle>, Long>? {
        val base = dir ?: return null
        return try {
            val f = File(base, diskKey(provider, symbol, timeframe))
            if (!f.exists()) return null
            val savedAt = f.lastModified()
            var list = parseCandles(f.readText())
            list = dropUnclosed(list, tfMinutes, nowMs)
            if (list.size < 60) return null
            // Sentuh untuk LRU.
            try { f.setLastModified(nowMs) } catch (e: Exception) { /* abaikan */ }
            list to savedAt
        } catch (e: Exception) { null }
    }

    fun prune(base: File) {
        try {
            val files = base.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedBy { it.lastModified() } ?: return
            var drop = files.size - DISK_CACHE_MAX_FILES
            var i = 0
            while (drop > 0 && i < files.size) {
                try { files[i].delete() } catch (e: Exception) { /* abaikan */ }
                drop--; i++
            }
        } catch (e: Exception) { /* abaikan */ }
    }

    fun count(): Int = try {
        dir?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.size ?: 0
    } catch (e: Exception) { 0 }
}
