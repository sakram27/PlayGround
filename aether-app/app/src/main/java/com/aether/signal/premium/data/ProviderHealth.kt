package com.aether.signal.premium.data

import org.json.JSONArray
import org.json.JSONObject

// P8: kesehatan provider — inti MURNI (teruji JVM); persistensi di AppState.
// Tidak ada polling otomatis; sampel dicatat dari fetch nyata + tombol "Uji".

data class HealthSample(
    val ok: Boolean,
    val latencyMs: Long,
    val kind: FetchErrorKind?,
    val msg: String,
    val at: Long
)

data class ProviderStats(
    val checked: Boolean,
    val lastOk: Boolean,
    val lastLatencyMs: Long,
    val lastAt: Long,
    val success: Int,
    val failed: Int,
    val lastKind: FetchErrorKind?,
    val lastMsg: String
)

const val HEALTH_RING = 20

fun summarizeHealth(samples: List<HealthSample>): ProviderStats {
    if (samples.isEmpty()) return ProviderStats(false, false, 0, 0, 0, 0, null, "")
    val last = samples.last()
    return ProviderStats(
        checked = true,
        lastOk = last.ok,
        lastLatencyMs = last.latencyMs,
        lastAt = last.at,
        success = samples.count { it.ok },
        failed = samples.count { !it.ok },
        lastKind = samples.lastOrNull { !it.ok }?.kind,
        lastMsg = samples.lastOrNull { !it.ok }?.msg ?: ""
    )
}

fun encodeHealth(all: Map<String, List<HealthSample>>): String {
    val o = JSONObject()
    for ((p, list) in all) {
        val a = JSONArray()
        for (s in list.takeLast(HEALTH_RING)) {
            a.put(JSONObject().put("ok", s.ok).put("lat", s.latencyMs)
                .put("kind", s.kind?.name ?: "").put("msg", s.msg.take(120)).put("at", s.at))
        }
        o.put(p, a)
    }
    return o.toString()
}

fun decodeHealth(raw: String?): Map<String, List<HealthSample>> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return try {
        val o = JSONObject(raw)
        val out = HashMap<String, List<HealthSample>>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            out[k] = (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                val kn = s.optString("kind", "")
                HealthSample(s.optBoolean("ok"), s.optLong("lat"), if (kn.isEmpty()) null else FetchErrorKind.valueOf(kn),
                    s.optString("msg", ""), s.optLong("at"))
            }
        }
        out
    } catch (e: Exception) { emptyMap() }
}

/** Apakah provider mendukung simbol (P8, murni). Demo menerima semua format valid. */
fun providerSupports(provider: String, sym: String): Boolean = when (provider) {
    "binance", "bybit" -> !isForexLike(sym)
    "yahoo" -> yahooSymbol(sym) != null
    "demo" -> sym.trim().isNotEmpty()
    else -> false
}

/** Data dianggap kedaluwarsa bila tak ada sukses, atau sukses terakhir lebih tua
 *  dari maks(10 mnt, 2× TF). Murni, teruji. */
fun isDataStale(lastSuccessMs: Long, nowMs: Long, tfMinutes: Int): Boolean {
    if (lastSuccessMs <= 0) return true
    val limit = maxOf(10 * 60_000L, tfMinutes * 2L * 60_000L)
    return nowMs - lastSuccessMs > limit
}
