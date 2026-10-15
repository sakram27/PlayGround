package com.aether.signal.premium.ui

// Diagnostik Engine (V26): angka aktual per tick — bukan animasi, bukan contoh.
// BotEngine mengisi holder ini di setiap cabang tick; panel UI hanya membaca.
// Aman thread (bg tick → baca UI) via @Volatile + salinan snapshot.

/** Satu jepretan diagnostik untuk panel (semua field apa adanya). */
data class EngineDiagSnapshot(
    val running: Boolean,
    val startedAt: Long,
    val lastTickAt: Long,
    val lastDataAt: Long,
    val strategy: String,
    val timeframe: String,
    val pairs: List<String>,
    val evalTotal: Int,
    val evalOk: Int,
    val candidates: Int,
    val filteredOut: Int,
    val invalidLevels: Int,
    val dupHeld: Int,
    val stored: Int,
    val notifCalls: Int,
    val openPositions: Int,
    val oldestOpenAgeMs: Long,
    val failCount: Int,
    val pairErrors: List<Pair<String, String>>,
    val lastPhase: String,
    val lastError: String,
    // V32: jejak per tahap (kumulatif tick berjalan + rincian tick terakhir).
    val totalTicks: Int = 0,
    val candLong: Int = 0,
    val candShort: Int = 0,
    val blockedPosition: Int = 0,
    val filterTop: List<Pair<String, Int>> = emptyList(),
    val lastSignalAt: Long = 0L,
    // V36: sidik konfigurasi tick terakhir (jejak live→konfigurasi).
    val lastCfgFp: String = "",
    // Tahap 3: sesi beku startup vs global live.
    val sessionCfgFp: String = "",
    val sessionPairs: List<String> = emptyList(),
    val sessionStrategy: String = "",
    val sessionTimeframe: String = "",
    val liveCfgFp: String = "",
    val cfgDiverged: Boolean = false,
    // Tahap 4/G: vonis per pair terakhir.
    val pairVerdicts: List<PairVerdict> = emptyList(),
    // Tahap 5D: awal tick terakhir (lastTickAt = selesai).
    val tickStartMs: Long = 0L,
    // Bagian A: telemetri cap global + tick dilewat.
    val rejectedCap: Int = 0,
    val tickSkipped: Int = 0,
    // Bagian B: peringatan sesi lama (kosong = tidak ada).
    val staleNote: String = "",
    // Diagnostik kombo sesi: strategi utama/tambahan/mode terpisah.
    // Default kosong = sesi tunggal/lama; konstruksi posisional lama tetap kompil.
    val sessionComboMode: String = "",
    val sessionComboExtra: List<String> = emptyList()
)

object EngineDiag {
    @Volatile var startedAt: Long = 0L
    @Volatile var lastTickAt: Long = 0L
    // Tahap 5D: awal tick terakhir (lastTickAt = selesai). Membedakan
    // "tick macet di tengah" dari "tidak ada tick".
    @Volatile var tickStartMs: Long = 0L
    @Volatile var lastDataAt: Long = 0L
    @Volatile var evalTotal: Int = 0
    @Volatile var evalOk: Int = 0
    @Volatile var candidates: Int = 0
    @Volatile var filteredOut: Int = 0
    @Volatile var invalidLevels: Int = 0
    @Volatile var dupHeld: Int = 0
    @Volatile var stored: Int = 0
    @Volatile var notifCalls: Int = 0
    @Volatile var lastPhase: String = "belum berjalan"
    @Volatile var lastError: String = ""
    // V32: kumulatif sesi + rincian tick terakhir (tak direset per tick).
    @Volatile var totalTicks: Int = 0
    @Volatile var candLong: Int = 0
    @Volatile var candShort: Int = 0
    @Volatile var blockedPosition: Int = 0
    @Volatile var lastSignalAt: Long = 0L
    // V36: sidik konfigurasi tick terakhir (tak direset per tick).
    @Volatile var lastCfgFp: String = ""
    // Tahap 3: sesi beku + divergensi vs global live (tak direset per tick).
    @Volatile var sessionCfgFp: String = ""
    @Volatile var sessionPairs: List<String> = emptyList()
    @Volatile var sessionStrategy: String = ""
    @Volatile var sessionTimeframe: String = ""
    // Diagnostik kombo sesi (dicatat freezeSessionNow; dibersihkan clearSession).
    @Volatile var sessionComboMode: String = ""
    @Volatile var sessionComboExtra: List<String> = emptyList()
    @Volatile var liveCfgFp: String = ""
    @Volatile var cfgDiverged: Boolean = false
    // Bagian A: penolakan cap global + tick yang dilewat (anti-tumpang).
    // rejectedCap dihitung per tick (direset beginTick); tickSkipped kumulatif sesi.
    @Volatile var rejectedCap: Int = 0
    @Volatile var tickSkipped: Int = 0
    // Bagian B: peringatan sesi sebelumnya tak selesai (slate bersih eksplisit).
    @Volatile var staleNote: String = ""
    // Tahap 4/G + 5E: vonis terakhir per pair (sesi berjalan, tanpa persist).
    // Nama tahap mengikuti konvensi misi: NO_CANDIDATE | FILTERED |
    // INVALID_LEVEL | SIGNAL_CREATED | BLOCKED_POSITION | PROVIDER_ERROR |
    // INSUFFICIENT_DATA. Sisi notifikasi: NOTIFICATION_SUBMITTED | HELD |
    // FAILED (lihat notifStageOf di NotifyLogic.kt).
    private val verdicts: LinkedHashMap<String, PairVerdict> = LinkedHashMap()
    private val lock = Any()
    private val errors: LinkedHashMap<String, String> = LinkedHashMap()
    private val freasons: LinkedHashMap<String, Int> = LinkedHashMap()

    fun beginTick() {
        totalTicks++
        tickStartMs = System.currentTimeMillis()
        evalTotal = 0; evalOk = 0; candidates = 0; filteredOut = 0
        invalidLevels = 0; dupHeld = 0; stored = 0; notifCalls = 0
        candLong = 0; candShort = 0; blockedPosition = 0
        rejectedCap = 0
        lastPhase = "mengambil data"
        synchronized(lock) { errors.clear(); freasons.clear() }
    }

    /** Catat alasan penolakan filter satu kandidat (deduplikasi per tick). */
    fun noteFilterReject(reason: String) {
        val k = (reason ?: "filter").take(60)
        synchronized(lock) {
            freasons[k] = (freasons[k] ?: 0) + 1
            while (freasons.size > 8) freasons.remove(freasons.keys.first())
        }
    }

    /** Tandai satu rekaman engine tersimpan (entry/arsip exit) + waktunya. */
    fun noteStored() {
        stored++
        lastSignalAt = System.currentTimeMillis()
        // Bagian B: sesi baru sudah produktif → peringatan sesi lama kedaluwarsa.
        staleNote = ""
    }

    fun pairError(sym: String, msg: String) {
        val clean = (msg ?: "gagal").take(120)
        synchronized(lock) {
            errors.remove(sym)
            errors[sym] = clean
            while (errors.size > 8) errors.remove(errors.keys.first())
        }
        // Tahap 5E: kegagalan provider/data = PROVIDER_ERROR (detail menyertakan pesan).
        noteVerdict(sym, "PROVIDER_ERROR", clean)
    }

    /** Tahap 4/G: catat vonis evaluasi terakhir pair (maks 32 pair). */
    fun noteVerdict(sym: String, outcome: String, detail: String = "") {
        synchronized(lock) {
            verdicts.remove(sym)
            verdicts[sym] = PairVerdict(sym, outcome.take(24), detail.take(120), System.currentTimeMillis())
            while (verdicts.size > 32) verdicts.remove(verdicts.keys.first())
        }
    }

    fun clearVerdicts() {
        synchronized(lock) { verdicts.clear() }
    }

    fun snapshot(
        running: Boolean, strategy: String, timeframe: String,
        pairs: List<String>, openPositions: Int, oldestOpenAgeMs: Long
    ): EngineDiagSnapshot {
        val errs = synchronized(lock) { errors.toList() }
        val ftop = synchronized(lock) { freasons.toList().sortedByDescending { it.second }.take(3) }
        val pv = synchronized(lock) { verdicts.values.toList() }
        return EngineDiagSnapshot(
            running, startedAt, lastTickAt, lastDataAt, strategy, timeframe, pairs,
            evalTotal, evalOk, candidates, filteredOut, invalidLevels, dupHeld,
            stored, notifCalls, openPositions, oldestOpenAgeMs,
            errs.size, errs, lastPhase, lastError,
            totalTicks, candLong, candShort, blockedPosition, ftop, lastSignalAt, lastCfgFp,
            sessionCfgFp, sessionPairs, sessionStrategy, sessionTimeframe, liveCfgFp, cfgDiverged,
            pv, tickStartMs, rejectedCap, tickSkipped, staleNote, sessionComboMode, sessionComboExtra)
    }
}

/** Tahap 4/G: vonis evaluasi terakhir satu pair (diagnostik sesi). */
data class PairVerdict(
    val sym: String,
    val outcome: String,
    val detail: String = "",
    val at: Long = 0L
)

/**
 * Bagian E: kesehatan data per pair (murni, teruji JVM).
 * SEHAT = diterima penuh & cukup untuk indikator; PARSIAL = ada tapi kurang
 * dari yang diminta; KURANG = di bawah minimum indikator; GAGAL = kosong.
 * Tak mengubah aturan strategi — hanya label diagnostik.
 */
fun pairDataHealth(requested: Int, received: Int, valid: Int, minRequired: Int): String = when {
    received <= 0 || valid <= 0 -> "GAGAL"
    valid < minRequired -> "KURANG($valid/$minRequired)"
    received < requested -> "PARSIAL($valid/$requested)"
    else -> "SEHAT"
}

/**
 * Bagian B: keputusan peringatan slate-bersih (murni, teruji JVM).
 * [prevFlag] = "N|atMs" yang ditulis start/stop sebelumnya; null = tak ada.
 * Peringatan HANYA bila flag ada DAN posisi saat ini kosong (= sesi lama
 * berhenti/crash dengan posisi yang kini hilang). Kontinuitas (posisi masih
 * di memori, proses sama) → null. Flag rusak → null (jangan menuduh).
 */
fun staleSessionNote(prevFlag: String?, positionsEmpty: Boolean): String? {
    if (!positionsEmpty) return null
    val f = prevFlag?.takeIf { it.contains("|") } ?: return null
    val n = f.substringBefore("|").toIntOrNull() ?: return null
    if (n <= 0) return null
    val at = f.substringAfter("|", "").toLongOrNull() ?: 0L
    val whenS = if (at > 0) {
        try {
            val fmt = java.text.SimpleDateFormat("dd-MM HH:mm", java.util.Locale("id", "ID"))
            fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
            fmt.format(java.util.Date(at))
        } catch (e: Exception) {
            "waktu tak diketahui"
        }
    } else "waktu tak diketahui"
    return "Sesi sebelumnya berhenti dengan $n posisi paper ($whenS) — " +
        "tidak dipantau lagi; riwayat sinyal tetap tersimpan."
}

// Batas usia data per TF agar label "data basi" jujur (2× interval wajar).
fun dataStaleLimitMs(tfMinutes: Int): Long {
    val tf = if (tfMinutes > 0) tfMinutes else 15
    return tf.toLong() * 2 * 60000L
}

/**
 * Indeks bar keluar kedaluwarsa cermin backtest.
 * Backtest: sinyal di bar i, entry di open i+1, scan s.d. lastJ=min(size-1,i+maxHolding)
 * → exit di lastJ = bar entry + (maxHolding-1).
 * -1 bila jendela belum mencakup bar tersebut (belum jatuh tempo) atau tak valid.
 * Bila bar entry hilang dari jendela: seluruh jendela dianggap sudah ditahan.
 * Murni & teruji; BotEngine memakai fungsi ini apa adanya.
 */
fun expiryIndex(entryT: Long, times: List<Long>, maxHolding: Int): Int {
    if (maxHolding < 1 || times.size < 2) return -1
    val entryIdx = times.indexOfFirst { it == entryT }
    if (entryIdx < 0) {
        return if (times.size >= maxHolding) times.size - 1 else -1
    }
    val xi = entryIdx + maxHolding - 1
    return if (xi <= times.size - 1) xi else -1
}

private fun ageText(atMs: Long, nowMs: Long): String {
    if (atMs <= 0) return "belum pernah"
    return durText((nowMs - atMs).coerceAtLeast(0))
}

private fun durText(ms: Long): String {
    val d = ms.coerceAtLeast(0)
    if (d < 60000) return "${d / 1000} dtk"
    if (d < 3600000) return "${d / 60000} mnt"
    return "${d / 3600000} jam"
}

/**
 * V33: bar mana yang dievaluasi tick ini (murni, teruji).
 * Selalu bar tertutup terakhir (size-2); bar berjalan (size-1) HANYA bila
 * plausibly sudah tutup (umur >= interval TF) — volumenya parsial dan
 * indikatornya tak stabil, sehingga evaluasi membabi-buta mensistematiskan
 * kegagalan filter volume (divergensi vs backtest yang tak pernah menilai
 * bar berjalan). Tanpa look-ahead: hanya memakai waktu dinding + waktu bar.
 */
fun engineEvalIndices(size: Int, lastT: Long, nowMs: Long, tfMs: Long): List<Int> {
    if (size < 2) return emptyList()
    val out = ArrayList<Int>(2)
    out.add(size - 2)
    val tf = if (tfMs > 0) tfMs else 900000L
    if (size >= 1 && lastT > 0 && nowMs - lastT >= tf) out.add(size - 1)
    return out
}

/**
 * Teks panel diagnostik dari angka aktual. Semua input eksplisit (murni, teruji);
 * Activity hanya mengumpulkan nilainya. Tak ada angka karangan.
 */
fun formatEngineDiag(
    snap: EngineDiagSnapshot,
    strategy: String, timeframe: String, provider: String,
    notifPermOk: Boolean, notifSystemOk: Boolean, quietOn: Boolean,
    histSent: Int, histHeld: Int, histFailed: Int,
    tfMinutes: Int, nowMs: Long
): String {
    val b = StringBuilder()
    // V32: loop macet terdeteksi dari umur tick (bukan klaim sehat karena hidup).
    // Interval terjadwal 90 dtk; >5 mnt tanpa tick selesai = tidak merespons.
    // UI-1: status tunggal dari engineStatusLabel (sumber yang sama dipakai
    // kartu ringkas Dashboard/Positions); teks tampilan lama dipertahankan
    // agar panel detail tak berubah arti (DATA BELUM SIAP = TERDEGRADASI).
    val state = when (engineStatusLabel(snap, nowMs)) {
        "BERHENTI" -> "BERHENTI"
        "TIDAK MERESPON" -> "TIDAK MERESPON (loop macet?)"
        "ERROR" -> "ERROR"
        "DATA BELUM SIAP" -> "TERDEGRADASI (semua pair gagal)"
        else -> "BERJALAN"
    }
    b.append("Status: $state")
    if (snap.running && snap.startedAt > 0) b.append(" · mulai ${ageText(snap.startedAt, nowMs)} lalu")
    b.append("\nTick terakhir: ${ageText(snap.lastTickAt, nowMs)}")
    // Tahap 5D: durasi tick terakhir bila keduanya tersedia.
    if (snap.tickStartMs > 0 && snap.lastTickAt >= snap.tickStartMs) {
        b.append(" (mulai ${ageText(snap.tickStartMs, nowMs)} · durasi ${(snap.lastTickAt - snap.tickStartMs) / 1000} dtk)")
    } else if (snap.tickStartMs > 0 && snap.running) {
        b.append(" (dimulai ${ageText(snap.tickStartMs, nowMs)} · BELUM SELESAI)")
    }
    val dataAge = if (snap.lastDataAt > 0) ageText(snap.lastDataAt, nowMs) else "belum ada"
    b.append(" · data terakhir: $dataAge")
    if (snap.lastDataAt > 0 && nowMs - snap.lastDataAt > dataStaleLimitMs(tfMinutes)) {
        b.append(" (BASI untuk $timeframe)")
    }
    b.append("\nJalan: ${strategy.ifEmpty { "—" }} · $timeframe · $provider")
    b.append("\nPair: ${if (snap.pairs.isEmpty()) "belum dipilih" else snap.pairs.joinToString(", ")}")
    b.append("\nTick: ${snap.evalOk}/${snap.evalTotal} pair OK")
    b.append(" · kandidat ${snap.candidates} (LONG ${snap.candLong}/SHORT ${snap.candShort})")
    b.append(" · lolos ${snap.candidates - snap.filteredOut}")
    b.append("\nTotal tick sesi: ${snap.totalTicks}")
    b.append(" · ditahan posisi: ${snap.blockedPosition}")
    if (snap.filterTop.isNotEmpty()) {
        b.append("\nPenolakan filter teratas: " +
            snap.filterTop.joinToString(" · ") { "${it.first}×${it.second}" })
    }
    b.append("\nSinyal/rekaman terakhir: ${ageText(snap.lastSignalAt, nowMs)}")
    if (snap.lastCfgFp.isNotEmpty()) b.append(" · sidik konfigurasi: ${snap.lastCfgFp.take(24)}…")
    if (snap.sessionCfgFp.isNotEmpty()) {
        b.append("\nSesi Engine: ${snap.sessionStrategy} · ${snap.sessionTimeframe} · " +
            "${snap.sessionPairs.size} pair · fp ${snap.sessionCfgFp.take(16)}")
        // Kombo sesi terpisah: utama + tambahan + mode (bukan hanya nama utama).
        if (snap.sessionComboMode.isNotEmpty() && snap.sessionComboExtra.isNotEmpty()) {
            b.append("\nStrategi utama: ${snap.sessionStrategy.ifEmpty { "—" }}" +
                " · tambahan: ${snap.sessionComboExtra.joinToString("+")}" +
                " · mode: ${snap.sessionComboMode}")
        }
        if (snap.cfgDiverged) {
            b.append(" · DIVERGEN: global disunting saat berjalan (sesi beku tetap dipakai)")
        }
    }
    b.append("\nLevel tak valid: ${snap.invalidLevels} · duplikat ditahan: ${snap.dupHeld}")
    b.append(" · tersimpan: ${snap.stored} · notif dipanggil: ${snap.notifCalls}")
    // Bagian A: telemetri cap global + tick dilewat.
    if (snap.rejectedCap > 0 || snap.tickSkipped > 0) {
        b.append("\nCap global: ${snap.rejectedCap} entry ditolak (penuh) · tick dilewat: ${snap.tickSkipped}")
    }
    // Bagian B: peringatan sesi lama tak selesai.
    if (snap.staleNote.isNotEmpty()) {
        b.append("\nPeringatan sesi: ${snap.staleNote}")
    }
    b.append("\nNotifikasi: izin ${if (notifPermOk) "OK" else "BELUM"} · sistem " +
        "${if (notifSystemOk) "AKTIF" else "MATI"} · jam tenang ${if (quietOn) "AKTIF" else "mati"}")
    b.append("\nRiwayat kirim: $histSent terkirim · $histHeld ditahan · $histFailed gagal")
    if (snap.openPositions > 0) {
        b.append("\nPosisi terbuka: ${snap.openPositions} (tertua ${durText(snap.oldestOpenAgeMs)}" +
            " — menahan entry baru pair tsb)")
    } else {
        b.append("\nPosisi terbuka: tidak ada")
    }
    if (snap.pairErrors.isNotEmpty()) {
        b.append("\nGagal: " + snap.pairErrors.take(3).joinToString(" · ") { "${it.first}: ${it.second}" })
    }
    // Tahap 4/G: vonis per pair. Detail penuh (maks 120 char) agar BAR
    // waktu candle yang dievaluasi tetap terlihat; bukan dump tak terbatas
    // (maks 12 pair).
    if (snap.pairVerdicts.isNotEmpty()) {
        b.append("\nPer pair: " + snap.pairVerdicts.take(12).joinToString(" · ") { v ->
            v.sym + "=" + v.outcome + if (v.detail.isNotEmpty()) "(${v.detail.take(120)})" else ""
        })
    }
    if (snap.lastPhase.isNotEmpty()) b.append("\nTahap: ${snap.lastPhase}")
    b.append("\nAlasan: " + noSignalReason(
        snap.running, snap.evalTotal, snap.evalOk, snap.candidates,
        snap.stored, snap.failCount, snap.openPositions, snap.pairs.size,
        snap.lastTickAt, nowMs, snap.blockedPosition))
    b.append("\nCatatan: bar tertutup terakhir dievaluasi tiap tick (90 dtk); " +
        "bar berjalan hanya bila plausibly tutup; sinyal baru memakai harga live saat ini.")
    return b.toString()
}

/**
 * Alasan tak-ada-sinyal dari angka aktual (prioritas penyebab paling operasional).
 * Murni & teruji JVM; UI hanya menampilkan teksnya.
 */
fun noSignalReason(
    running: Boolean, evalTotal: Int, evalOk: Int, candidates: Int,
    stored: Int, failCount: Int, openPositions: Int, pairsTotal: Int,
    lastTickAt: Long = 0L, nowMs: Long = 0L, blockedPosition: Int = 0
): String = when {
    !running -> "Engine berhenti."
    lastTickAt > 0 && nowMs - lastTickAt > 5 * 60 * 1000L ->
        "Loop tidak merespons >5 menit — coba Stop lalu Start Engine."
    evalTotal == 0 -> "Belum ada evaluasi tick."
    evalOk == 0 -> "Menunggu data pasar (semua $evalTotal pair gagal diambil)."
    candidates == 0 && blockedPosition > 0 && openPositions >= pairsTotal && pairsTotal > 0 ->
        "Semua pair memiliki posisi terbuka (posisi menahan entry baru)."
    candidates == 0 -> "Kondisi entry strategi belum terpenuhi pada bar tertutup/berjalan terakhir."
    stored == 0 && failCount == 0 ->
        "Kandidat ada tetapi belum menjadi sinyal (filter/level/duplikat) — lihat penghitung."
    stored == 0 ->
        "Kandidat ada tetapi belum menjadi sinyal; $failCount pair gagal diproses."
    openPositions >= pairsTotal && pairsTotal > 0 ->
        "Semua pair memiliki posisi terbuka (posisi menahan entry baru)."
    else -> "Sebagian sinyal tersimpan; sisanya tertahan filter/level/duplikat."
}

// ---------- UI-1: status ringkas tunggal + salinan diagnostik ----------
// Satu-satunya penentu label status Engine — dipakai kartu ringkas Dashboard
// dan tab Positions; formatEngineDiag memetakan label ini ke teks tampilannya
// sehingga tak ada dua sumber status yang bertentangan. Murni & teruji JVM.
fun engineStatusLabel(snap: EngineDiagSnapshot, nowMs: Long): String = when {
    !snap.running -> "BERHENTI"
    snap.lastTickAt > 0 && nowMs - snap.lastTickAt > 5 * 60 * 1000L -> "TIDAK MERESPON"
    snap.lastError.isNotEmpty() && snap.evalOk == 0 -> "ERROR"
    snap.evalTotal > 0 && snap.evalOk == 0 -> "DATA BELUM SIAP"
    snap.stored == 0 -> "BERJALAN TANPA SINYAL"
    else -> "BERJALAN"
}

/** Satu baris status kartu: label + umur tick + umur data. Jujur bila kosong ("belum pernah"/"belum ada"). */
fun engineStatusSummary(snap: EngineDiagSnapshot, nowMs: Long): String {
    val tick = if (snap.lastTickAt > 0) "tick ${ageText(snap.lastTickAt, nowMs)} lalu" else "tick belum pernah"
    val data = if (snap.lastDataAt > 0) "data ${ageText(snap.lastDataAt, nowMs)} lalu" else "data belum ada"
    return "${engineStatusLabel(snap, nowMs)} · $tick · $data"
}

/** Teks empty-state "belum ada sinyal" dari angka aktual — dipakai Signals + Dashboard. */
fun emptySignalText(
    running: Boolean, evalTotal: Int, evalOk: Int, candidates: Int,
    blockedPosition: Int, openPositions: Int, pairsTotal: Int,
    lastTickAt: Long, nowMs: Long, stored: Int, failCount: Int
): String = when {
    !running -> "Belum ada sinyal Engine — tekan Start Engine."
    evalTotal == 0 -> "Belum ada sinyal Engine — engine berjalan, menunggu tick evaluasi pertama."
    evalOk == 0 -> "Belum ada sinyal Engine — data pasar belum siap (semua pair gagal diambil). Periksa provider/koneksi, lalu lihat tab Positions."
    candidates == 0 && blockedPosition > 0 && openPositions >= pairsTotal && pairsTotal > 0 ->
        "Belum ada sinyal Engine — semua pair memiliki posisi terbuka yang menahan entry baru."
    else -> "Belum ada sinyal Engine — " + noSignalReason(
        running, evalTotal, evalOk, candidates, stored, failCount,
        openPositions, pairsTotal, lastTickAt, nowMs, blockedPosition
    ).replaceFirstChar { it.lowercase() }
}

/** Teks salinan diagnostik: snapshot aktual saja, tanpa rahasia (snapshot tak memuat kunci/token). */
fun buildDiagCopyText(
    snap: EngineDiagSnapshot, strategy: String, timeframe: String, provider: String, nowMs: Long,
    notifSent: Int = -1, notifHeld: Int = -1, notifFailed: Int = -1
): String {
    fun age(t: Long) = if (t > 0) "${ageText(t, nowMs)} lalu" else "belum pernah"
    fun cnt(v: Int) = if (v >= 0) v.toString() else "tak tersedia"
    val wall = try {
        val f = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", java.util.Locale("id", "ID"))
        f.timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        f.format(java.util.Date(nowMs)) + " WIB"
    } catch (e: Exception) { "epoch $nowMs" }
    val b = StringBuilder()
    b.append("Aether Signal — Diagnostik Engine\n")
    b.append("Waktu snapshot: $wall (epoch $nowMs)\n")
    b.append("Status: ${engineStatusLabel(snap, nowMs)}\n")
    b.append("Tick terakhir: ${age(snap.lastTickAt)} · Data terakhir: ${age(snap.lastDataAt)}\n")
    b.append("Strategi: ${strategy.ifEmpty { "—" }} · Timeframe: ${timeframe.ifEmpty { "—" }} · Provider: ${provider.ifEmpty { "—" }}\n")
    b.append("Pair: ${if (snap.pairs.isEmpty()) "belum dipilih" else snap.pairs.joinToString(", ")}\n")
    b.append("Evaluasi: ${snap.evalOk}/${snap.evalTotal} pair OK · tick sesi: ${snap.totalTicks}\n")
    b.append("Kandidat: ${snap.candidates} (LONG ${snap.candLong}/SHORT ${snap.candShort}) · Ditolak filter: ${snap.filteredOut}\n")
    if (snap.filterTop.isNotEmpty())
        b.append("Top penolakan: " + snap.filterTop.joinToString(" · ") { "${it.first}×${it.second}" } + "\n")
    b.append("Level tak valid: ${snap.invalidLevels} · Diblokir posisi: ${snap.blockedPosition} · Cap penuh: ${snap.rejectedCap} · Duplikat ditahan: ${snap.dupHeld}\n")
    b.append("Tersimpan: ${snap.stored} · Notifikasi dipanggil: ${snap.notifCalls}\n")
    b.append("Riwayat kirim: ${cnt(notifSent)} terkirim · ${cnt(notifHeld)} ditahan · ${cnt(notifFailed)} gagal\n")
    if (snap.sessionStrategy.isNotEmpty() || snap.sessionPairs.isNotEmpty())
        b.append("Sesi: ${snap.sessionStrategy.ifEmpty { "—" }} · ${snap.sessionTimeframe.ifEmpty { "—" }} · ${snap.sessionPairs.size} pair" +
            (if (snap.cfgDiverged) " · DIVERGEN dari konfigurasi live" else "") + "\n")
    if (snap.sessionComboMode.isNotEmpty() && snap.sessionComboExtra.isNotEmpty())
        b.append("Strategi utama: ${snap.sessionStrategy.ifEmpty { "—" }} · Strategi tambahan: " +
            "${snap.sessionComboExtra.joinToString(", ")} · Mode kombinasi: ${snap.sessionComboMode}\n")
    if (snap.openPositions > 0)
        b.append("Posisi terbuka: ${snap.openPositions}\n")
    if (snap.pairErrors.isNotEmpty())
        b.append("Gagal: " + snap.pairErrors.take(8).joinToString(" · ") { "${it.first}: ${it.second}" } + "\n")
    if (snap.pairVerdicts.isNotEmpty())
        b.append("Per pair: " + snap.pairVerdicts.take(12).joinToString(" · ") { v ->
            v.sym + "=" + v.outcome + if (v.detail.isNotEmpty()) "(${v.detail.take(120)})" else ""
        } + "\n")
    if (snap.lastPhase.isNotEmpty()) b.append("Tahap: ${snap.lastPhase}\n")
    if (snap.lastError.isNotEmpty()) b.append("Error: ${snap.lastError}\n")
    b.append("Alasan: " + noSignalReason(
        snap.running, snap.evalTotal, snap.evalOk, snap.candidates, snap.stored,
        snap.failCount, snap.openPositions, snap.pairs.size, snap.lastTickAt, nowMs, snap.blockedPosition))
    return b.toString()
}

// ---------- TAHAP 2.1: warna status satu-sumber (angka ARGB, murni, teruji) ----------
// Aturan: arti sama = warna sama di seluruh layar; warna tak pernah berdiri
// sendiri (selalu ada teks/ikon). Bukan logika trading — hanya presentasi.

/** Warna titik status Engine dari label engineStatusLabel(). */
fun statusColorFor(label: String): Int = when (label) {
    "BERJALAN", "BERJALAN TANPA SINYAL" -> 0xFF10B981.toInt() // emerald: proses hidup
    "DATA BELUM SIAP" -> 0xFFFBBF24.toInt() // amber: peringatan/belum siap
    "TIDAK MERESPON", "ERROR" -> 0xFFF87171.toInt() // merah: risiko/gagal
    else -> 0xFF94A3B8.toInt() // abu-abu: berhenti/netral/tak dikenal
}

/** Warna label kesehatan data dari pairDataHealth() ("SEHAT"/"PARSIAL(...)"/...). */
fun healthColorFor(health: String): Int = when {
    health.startsWith("SEHAT") -> 0xFF10B981.toInt() // emerald
    health.startsWith("PARSIAL") -> 0xFF2DD4BF.toInt() // teal: data ada, sebagian
    health.startsWith("KURANG") -> 0xFFFBBF24.toInt() // amber: di bawah minimum
    health.startsWith("GAGAL") -> 0xFFF87171.toInt() // merah: kosong
    else -> 0xFF94A3B8.toInt() // abu-abu: tak ada data ("—"/tak dikenal)
}

/** Warna accent bar arah sinyal (visual saja; teks LONG/SHORT tetap utama). */
fun dirColorFor(dir: String): Int = when (dir) {
    "LONG" -> 0xFF10B981.toInt() // emerald
    "SHORT" -> 0xFFF87171.toInt() // merah
    else -> 0xFF253244.toInt() // netral: baris info/diagnostik/arsip
}
