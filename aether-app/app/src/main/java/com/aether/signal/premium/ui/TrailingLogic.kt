package com.aether.signal.premium.ui

import org.json.JSONObject

// FITUR 10 (V16): trailing stop virtual paper — matematika MURNI & teruji JVM.
// Terisolasi PENUH dari mesin strategi/backtest/sinyal: hanya dibaca/ditulis
// oleh BotEngine pada posisi paper. Aturan eksplisit (didokumentasikan di UI):
//   dist = lockPct% × |TP−entry| (tanpa TP valid → 0,5% entry, dinyatakan).
//   LONG: high=max(high,mark); stop=max(stop, high−dist); stop tak pernah turun;
//     stop awal = SL awal (risiko tak melebar); sentuh (mark≤stop) → tutup.
//   SHORT: cermin (low=min; stop=min(stop, low+dist); sentuh mark≥stop).
// State per posisi (kunci stabil pair|dir|entryT) dipersist agar selamat restart.

data class TrailCfg(val enabled: Boolean = false, val lockPct: Double = 50.0)

fun sanitizeLockPct(v: Double): Double =
    if (!v.isFinite()) 50.0 else v.coerceIn(5.0, 100.0)

data class TrailState(val stop: Double, val extreme: Double)

fun positionKey(pair: String, dir: String, entryT: Long): String = "$pair|$dir|$entryT"

/** Jarak trailing; null bila entry tak valid. */
fun trailDistance(entry: Double, tp: Double, lockPct: Double): Double? {
    if (!entry.isFinite() || entry <= 0) return null
    val lp = sanitizeLockPct(lockPct)
    return if (tp.isFinite() && tp > 0) {
        val d = kotlin.math.abs(tp - entry) * lp / 100.0
        if (d.isFinite() && d > 0) d else null
    } else {
        entry * 0.005 // fallback terdokumentasi: tanpa TP valid
    }
}

/**
 * Ratchet satu langkah. Mengembalikan (stopBaru, extremeBaru).
 * LONG: extreme naik saja; stop naik saja. SHORT: cermin.
 */
fun ratchet(dir: String, stop: Double, extreme: Double, mark: Double, dist: Double): Pair<Double, Double> {
    if (!mark.isFinite() || !dist.isFinite() || dist <= 0) return stop to extreme
    return if (dir == "LONG") {
        val hi = maxOf(extreme, mark)
        maxOf(stop, hi - dist) to hi
    } else {
        val lo = if (extreme.isFinite()) minOf(extreme, mark) else mark
        minOf(stop, lo + dist) to lo
    }
}

fun trailTouched(dir: String, mark: Double, stop: Double): Boolean {
    if (!mark.isFinite() || !stop.isFinite()) return false
    return if (dir == "LONG") mark <= stop else mark >= stop
}

fun encodeTrail(all: Map<String, TrailState>): String {
    val o = JSONObject()
    for ((k, v) in all) {
        if (v.stop.isFinite() && v.extreme.isFinite()) {
            o.put(k, JSONObject().put("stop", v.stop).put("extreme", v.extreme))
        }
    }
    return o.toString()
}

fun parseTrail(raw: String?): MutableMap<String, TrailState> {
    val out = LinkedHashMap<String, TrailState>()
    if (raw.isNullOrEmpty()) return out
    try {
        val o = JSONObject(raw)
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val j = o.optJSONObject(k) ?: continue
            val s = j.optDouble("stop", Double.NaN)
            val e = j.optDouble("extreme", Double.NaN)
            if (s.isFinite() && s > 0 && e.isFinite()) out[k] = TrailState(s, e)
        }
    } catch (e: Exception) { /* rusak → kosong */ }
    return out
}
