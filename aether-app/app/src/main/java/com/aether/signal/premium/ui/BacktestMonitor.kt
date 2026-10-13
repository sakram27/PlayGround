package com.aether.signal.premium.ui

// BACKTEST PROCESS MONITOR (V22): status & log AKTUAL proses backtest.
// Prinsip: tanpa timer, tanpa angka karangan. Semua field hanya diisi dari
// event nyata (fetch selesai, callback mesin, hasil run). Event runId lama
// diabaikan (anti-campur). Thread-safe (bg thread mesin → poll UI).

enum class MonPhase {
    IDLE, PREPARING, CHECKING_CACHE, DOWNLOADING_DATA, VALIDATING_DATA,
    CALCULATING_INDICATORS, EVALUATING_STRATEGY, CALCULATING_METRICS,
    COMPLETED, FAILED, CANCELLED
}

/** Label Indonesia untuk UI. */
fun phaseLabel(p: MonPhase): String = when (p) {
    MonPhase.IDLE -> "IDLE"
    MonPhase.PREPARING -> "PREPARING"
    MonPhase.CHECKING_CACHE -> "CHECKING_CACHE"
    MonPhase.DOWNLOADING_DATA -> "DOWNLOADING_DATA"
    MonPhase.VALIDATING_DATA -> "VALIDATING_DATA"
    MonPhase.CALCULATING_INDICATORS -> "CALCULATING_INDICATORS"
    MonPhase.EVALUATING_STRATEGY -> "EVALUATING_STRATEGY"
    MonPhase.CALCULATING_METRICS -> "CALCULATING_METRICS"
    MonPhase.COMPLETED -> "COMPLETED"
    MonPhase.FAILED -> "FAILED"
    MonPhase.CANCELLED -> "CANCELLED"
}

data class MonLogLine(val t: Long, val kind: Int, val text: String) // kind: 0 info, 1 warn, 2 err

const val MON_LOG_CAP = 60

data class MonitorSnapshot(
    val runId: String,
    val phase: MonPhase,
    val phaseDetail: String,
    val progDone: Int?,
    val progTotal: Int?,
    val startedAt: Long,
    val finishedAt: Long,
    val asset: String,
    val timeframe: String,
    val reqFrom: Long,
    val reqTo: Long,
    val source: String,
    val received: Int,
    val validated: Int,
    val needDownload: Boolean?,
    val signalsRaw: Int,
    val filteredOut: Int,
    val trades: Int,
    val pairsDone: Int,
    val pairsTotal: Int,
    val error: String,
    val log: List<MonLogLine>,
    val eventSeq: Long
)

/** Persentase 0..100 bila total diketahui & > 0; null bila tak dapat dihitung. */
fun progressPct(done: Int, total: Int): Int? {
    if (total <= 0 || done < 0) return null
    return (done.coerceAtMost(total) * 100 / total)
}

/** Jam lokal HH:MM:SS untuk log (contoh format "10:21:03"). murni, teruji. */
fun fmtClockS(ts: Long): String {
    return try {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ts
        String.format(java.util.Locale.US, "%02d:%02d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND))
    } catch (e: Exception) { "--:--:--" }
}

/** Durasi manusiawi: "1,5 dtk" / "1 mnt 5 dtk". murni, teruji. */
fun fmtElapsed(ms: Long): String {
    val s = (ms.coerceAtLeast(0)) / 1000.0
    if (s < 60) return String.format(java.util.Locale.US, "%.1f", s).replace('.', ',') + " dtk"
    val m = (s / 60).toInt()
    val r = (s % 60).toInt()
    return "$m mnt $r dtk"
}

object MonitorBus {
    private val lock = Any()
    private var seq: Long = 0
    private var st = MonitorSnapshot(
        "", MonPhase.IDLE, "", null, null, 0, 0,
        "", "", 0, 0, "", 0, 0, null, 0, 0, 0, 0, 0, "", emptyList(), 0
    )

    fun snapshot(): MonitorSnapshot = synchronized(lock) { st.copy(log = st.log.toList()) }

    fun isActive(): Boolean = synchronized(lock) {
        st.runId.isNotEmpty() && st.phase != MonPhase.COMPLETED &&
            st.phase != MonPhase.FAILED && st.phase != MonPhase.CANCELLED && st.phase != MonPhase.IDLE
    }

    /** Run baru: identitas baru (ms + counter, unik walau 1 ms) + log dikosongkan. */
    fun startRun(asset: String, timeframe: String, reqFrom: Long, reqTo: Long, pairsTotal: Int): String {
        val id: String
        synchronized(lock) {
            seq++
            id = "m${System.currentTimeMillis()}_${seq}"
        }
        synchronized(lock) {
            seq++
            st = MonitorSnapshot(id, MonPhase.PREPARING, "Menyiapkan parameter", null, null,
                System.currentTimeMillis(), 0, asset, timeframe, reqFrom, reqTo,
                "", 0, 0, null, 0, 0, 0, 0, pairsTotal, "", emptyList(), seq)
            pushLog(id, 0, "Memulai Backtest ($asset · $timeframe)")
        }
        return id
    }

    private fun edit(runId: String, fn: (MonitorSnapshot) -> MonitorSnapshot): Boolean {
        synchronized(lock) {
            if (st.runId != runId) return false // event basi: abaikan
            seq++
            st = fn(st).copy(eventSeq = seq)
            return true
        }
    }

    fun phase(runId: String, p: MonPhase, detail: String = "") {
        edit(runId) { it.copy(phase = p, phaseDetail = detail) }
    }

    fun progress(runId: String, done: Int, total: Int) {
        edit(runId) { it.copy(progDone = done, progTotal = total) }
    }

    fun clearProgress(runId: String) {
        edit(runId) { it.copy(progDone = null, progTotal = null) }
    }

    fun counters(runId: String, signalsRaw: Int, filteredOut: Int, trades: Int) {
        edit(runId) { it.copy(signalsRaw = signalsRaw, filteredOut = filteredOut, trades = trades) }
    }

    fun source(runId: String, source: String, received: Int, validated: Int, needDownload: Boolean?) {
        edit(runId) {
            it.copy(source = source, received = received, validated = validated, needDownload = needDownload)
        }
    }

    fun pairProgress(runId: String, donePairs: Int, currentSym: String) {
        edit(runId) { it.copy(pairsDone = donePairs, asset = currentSym) }
    }

    fun pushLog(runId: String, kind: Int, text: String) {
        synchronized(lock) {
            if (st.runId != runId) return
            seq++
            val lines = (st.log + MonLogLine(System.currentTimeMillis(), kind, text))
                .takeLast(MON_LOG_CAP)
            st = st.copy(log = lines, eventSeq = seq)
        }
    }

    fun clearLogView() {
        synchronized(lock) {
            seq++
            st = st.copy(log = emptyList(), eventSeq = seq)
        }
    }

    fun finish(runId: String, ok: Boolean, error: String = "") {
        edit(runId) {
            it.copy(
                phase = if (ok) MonPhase.COMPLETED else MonPhase.FAILED,
                phaseDetail = if (ok) "Selesai" else error.take(160),
                error = error.take(300), finishedAt = System.currentTimeMillis(),
                progDone = if (ok) it.progTotal else it.progDone
            )
        }
    }

    fun cancel(runId: String) {
        edit(runId) {
            // Status terminal (selesai/gagal) tak boleh ditimpa batal.
            if (it.phase == MonPhase.COMPLETED || it.phase == MonPhase.FAILED) it
            else it.copy(phase = MonPhase.CANCELLED, phaseDetail = "Dibatalkan pengguna",
                finishedAt = System.currentTimeMillis())
        }
    }
}
