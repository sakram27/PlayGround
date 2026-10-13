package com.aether.signal.premium.ui

import android.app.Activity
import android.app.AlertDialog
import com.aether.signal.premium.engine.configFingerprint
import com.aether.signal.premium.engine.sanitizeParams

// Tahap 3 — Alur Start Engine (Android glue; keputusan murni di EngineStartGuard).
// Dipakai tombol Start di Dashboard + Signals agar perilaku identik.

/** Bangun snapshot konfigurasi Engine efektif saat ini (belum tentu referensi). */
fun currentEngineSnapshot(): EngineSnapshot? {
    return try {
        val g = App.captureGlobals()
        val asset = g.engPairs.firstOrNull()?.takeIf { it.isNotBlank() } ?: g.pair
        val p = sanitizeParams(buildParamsFrom(g, asset))
        EngineSnapshot(
            globals = g, params = p, pairs = g.engPairs.toList(),
            provider = g.provider, limit = g.limit, mode = g.mode,
            at = System.currentTimeMillis(), status = "LIVE", valid = true,
            origin = "engine-live"
        )
    } catch (e: Exception) {
        null
    }
}

private fun driftText(drift: List<DriftItem>): String {
    if (drift.isEmpty()) return "identik."
    return drift.take(12).joinToString("\n") { "• ${it.field}: ${it.current} → ${it.reference}" } +
        if (drift.size > 12) "\n• … +${drift.size - 12} perbedaan lain" else ""
}

/**
 * Titik masuk tunggal tombol Start Engine.
 * - Engine berjalan → Stop (toggle lama dipertahankan).
 * - Berhenti + drift vs snapshot backtest SUCCESS → dialog 3 pilihan.
 * - Selain itu → Start langsung dengan konfigurasi Engine saat ini.
 */
fun engineStartFlow(a: Activity, onDone: () -> Unit) {
    if (App.engRunning) {
        val msg = try {
            BotEngine.stop()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
        snack(a, msg)
        try {
            onDone()
        } catch (e: Exception) { /* abaikan */
        }
        return
    }
    val cur = currentEngineSnapshot()
    if (cur == null) {
        snack(a, "Config error: konfigurasi Engine saat ini tidak valid.")
        return
    }
    val ref = try {
        App.loadEngineRef()
    } catch (e: Exception) {
        null
    }
    val useRef = ref != null && ref.valid && ref.status == "SUCCESS"
    val drift = if (useRef) diffSnapshots(cur, ref!!) else emptyList()
    when (decideStartAction(false, if (useRef) ref else null, drift)) {
        StartDecision.START_DIRECT -> {
            val msg = try {
                BotEngine.start()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            snack(a, "$msg\n${BotEngine.sessionSummary()}")
            try {
                onDone()
            } catch (e: Exception) { /* abaikan */
            }
        }
        StartDecision.BLOCK_RUNNING -> {
            snack(a, "Engine berjalan — hentikan dulu bila ingin ganti konfigurasi.")
        }
        StartDecision.SHOW_DIALOG -> {
            val r = ref!!
            val refLine = "Backtest ${r.origin} · ${r.pairs.joinToString(",")} · " +
                "${r.params.strategy} · ${r.params.timeframe}"
            AlertDialog.Builder(a)
                .setTitle("Konfigurasi Engine berbeda dari Backtest")
                .setMessage(
                    "Engine akan memakai konfigurasi saat ini:\n" +
                        "${cur.params.strategy} · ${cur.params.timeframe} · " +
                        "${cur.pairs.joinToString(",")} · ${cur.provider}\n\n" +
                        "Referensi ($refLine):\n${driftText(drift)}\n\n" +
                        "Batas posisi maks hanya berlaku di simulasi backtest " +
                        "(Engine paper: 1 posisi per pair).\n\n" +
                        "Pilih: Batal (tidak ada yang berubah), " +
                        "pakai konfigurasi Engine saat ini, " +
                        "atau terapkan konfigurasi Backtest (bila valid)."
                )
                .setNegativeButton("Batal") { _, _ ->
                    snack(a, "Dibatalkan — Engine tidak dimulai, konfigurasi tidak berubah.")
                }
                .setNeutralButton("Pakai Engine") { _, _ ->
                    val msg = try {
                        BotEngine.start()
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    snack(a, "$msg\n${BotEngine.sessionSummary()}")
                    try {
                        onDone()
                    } catch (e: Exception) { /* abaikan */
                    }
                }
                .setPositiveButton("Terapkan Backtest") { _, _ ->
                    applyRefAndStart(a, r, onDone)
                }
                .show()
        }
    }
}

/** Terapkan snapshot backtest secara atomik lalu Start dengan fp terverifikasi. */
private fun applyRefAndStart(a: Activity, ref: EngineSnapshot, onDone: () -> Unit) {
    val store = AppGlobalsStore()
    val before = try {
        store.capture()
    } catch (e: Exception) {
        snack(a, "Gagal membaca konfigurasi aktif: ${e.message}")
        return
    }
    val res = try {
        applySnapshotToStore(store, ref)
    } catch (e: Exception) {
        ApplyVerdict(false, listOf("gagal menerapkan: ${e.message}"))
    }
    if (!res.ok) {
        snack(a, "Penerapan dibatalkan:\n" + res.reasons.joinToString("\n"))
        return
    }
    val expectedFp = try {
        val eff = effectiveGlobalsForApply(ref)
        configFingerprint(sanitizeParams(buildParamsFrom(eff, ref.params.asset)))
    } catch (e: Exception) {
        ""
    }
    val msg = try {
        BotEngine.start()
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
    // Verifikasi: sesi Engine harus memakai fingerprint yang baru diterapkan.
    val sessFp = try {
        BotEngine.activeSession?.fp ?: ""
    } catch (e: Exception) {
        ""
    }
    if (!App.engRunning || (expectedFp.isNotEmpty() && sessFp != expectedFp)) {
        try {
            if (App.engRunning) BotEngine.stop()
        } catch (e: Exception) { /* abaikan */
        }
        try {
            store.restore(before)
        } catch (e: Exception) { /* abaikan */
        }
        snack(a, "Start gagal/divergen (fp sesi $sessFp vs ekspektasi $expectedFp) — dikembalikan ke sebelumnya.")
        return
    }
    snack(a, "$msg\n${BotEngine.sessionSummary()}\nHasil backtest tidak dijamin terulang persis di live.")
    try {
        onDone()
    } catch (e: Exception) { /* abaikan */
    }
}
