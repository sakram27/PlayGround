package com.aether.signal.premium.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.ai.Sig
import com.aether.signal.premium.engine.parseTimeframe
import com.aether.signal.premium.engine.strategyList

/** Ekstra baris daftar: skor + badge usia (sumber: jejak tersimpan). */
fun signalRowExtra(s: Sig): String {
    val parts = ArrayList<String>()
    if (s.score >= 0) parts.add("skor ${App.fmt(s.score, 0)}")
    try {
        val tfm = try { parseTimeframe(s.tf) } catch (e: Exception) { 15 }
        val b = staleBadge(s.t, System.currentTimeMillis(), tfm)
        parts.add(if (b.stale) "⏳ ${b.text}" else b.text)
    } catch (e: Exception) { /* abaikan */ }
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
}

// Halaman detail sinyal (native, Classic Views). Identitas via extra "signal_id"
// yang dicari ulang di App.signals (Engine) lalu App.backtestSignals (arsip) —
// bukan posisi daftar. Semua nilai dari objek Sig tersimpan; yang tidak
// tersimpan dinyatakan "Tidak tersedia".

fun Context.openSignalDetail(signalId: String) {
    // Cari di kedua store (Engine + arsip backtest); id stabil, bukan posisi.
    val s = findSignalById(App.signals, signalId) ?: findSignalById(App.backtestSignals, signalId)
    if (s == null) {
        snack(this as? android.app.Activity
            ?: return, "Sinyal tidak ditemukan (mungkin sudah dihapus).")
        return
    }
    startActivity(Intent(this, SignalDetailActivity::class.java).apply {
        putExtra("signal_id", s.id)
    })
    if (this is android.app.Activity) {
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out)
    }
}

class SignalDetailActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_signal_detail
    override val showBack = true

    override fun build() {
        setBar("Detail Sinyal", "Data aktual tersimpan")
        paint()
    }

    override fun onResume() {
        super.onResume()
        try { paint() } catch (e: Exception) { /* layout belum siap */ }
    }

    private fun t(id: Int): TextView = findViewById(id)

    private fun paint() {
        val id = intent.getStringExtra("signal_id") ?: ""
        val s = findSignalById(App.signals, id) ?: findSignalById(App.backtestSignals, id)
        if (s == null) {
            findViewById<View>(R.id.dContent).visibility = View.GONE
            findViewById<View>(R.id.dMissing).visibility = View.VISIBLE
            return
        }
        findViewById<View>(R.id.dContent).visibility = View.VISIBLE
        findViewById<View>(R.id.dMissing).visibility = View.GONE
        paintHead(s)
        paintPrices(s)
        paintAnalysis(s)
        paintDataStatus(s)
    }

    // ---- 1. Pair, arah, status ----
    private fun paintHead(s: Sig) {
        t(R.id.dPair).text = s.pair.ifEmpty { "—" }
        t(R.id.dDir).apply {
            text = s.dir.ifEmpty { "—" }
            setTextColor((if (s.dir == "LONG") 0xFF10B981 else 0xFFF87171).toInt())
        }
        val st = statusOf(s)
        t(R.id.dStatus).text = st
        t(R.id.dStatus).setTextColor(
            (if (st.startsWith("Aktif") || st.startsWith("Keluar")) 0xFF10B981 else 0xFF94A3B8).toInt())
        t(R.id.dSrc).text = "${sourceBanner(s.src)} · ${srcLabel(s.src)}"
        val tfm = try { parseTimeframe(s.tf) } catch (e: Exception) { 15 }
        val badge = staleBadge(s.t, System.currentTimeMillis(), tfm)
        t(R.id.dTf).text = "Timeframe: ${s.tf.ifEmpty { "—" }}" +
            if (badge.stale) " · ⏳ ${badge.text}" else ""
        t(R.id.dTime).text = "Waktu sinyal: ${if (s.t > 0) App.fmtDate(s.t) else "Tidak tersedia"}"
    }

    private fun statusOf(s: Sig): String {
        val isOpen = BotEngine.positions.any { it.pair == s.pair && it.dir == s.dir && it.entryT == s.t }
        val laterExit = App.signals.any {
            (it.src == "dryrun-TP" || it.src == "dryrun-SL" || it.src == "dryrun-trail" || it.src == "dryrun-expired") &&
                it.pair == s.pair && it.dir == s.dir && it.t > s.t
        }
        return deriveSignalStatus(s.src, isOpen, laterExit)
    }

    // ---- 2-4. Harga entry, SL/TP, jarak, RR, harga live ----
    private fun paintPrices(s: Sig) {
        val entryLike = isEntryLike(s.src)
        t(R.id.dPriceLabel).text = if (entryLike) "Harga entry (tersimpan)" else "Harga keluar / exit (tersimpan)"
        t(R.id.dPrice).text = App.fmt(s.price, 4)
        t(R.id.dEntryExtra).text = "Entry tambahan: Belum tersedia (strategi hanya menyimpan satu level entry)."
        // Peringatan arah eksplisit — nilai TIDAK diubah diam-diam.
        val geoOk = levelGeometryOk(s.dir, s.price, s.sl, s.tp)
        t(R.id.dSl).text = "SL  ${App.fmt(s.sl, 4)}"
        t(R.id.dTp1).text = "TP  ${App.fmt(s.tp, 4)}"
        t(R.id.dTpMulti).text = "TP2 / TP3: Tidak tersedia (strategi hanya menyimpan satu target)."

        if (entryLike) {
            val d = signalDistances(s.price, s.sl, s.tp)
            val slTxt = if (d.slDistPrice != null) "${App.fmt(d.slDistPrice, 4)} (${App.fmt(d.slDistPct ?: 0.0)}%)" else "Tidak tersedia"
            val tpTxt = if (d.tpDistPrice != null) "${App.fmt(d.tpDistPrice, 4)} (${App.fmt(d.tpDistPct ?: 0.0)}%)" else "Tidak tersedia"
            t(R.id.dDist).text = "Jarak entry→SL: $slTxt\nJarak entry→TP: $tpTxt"
            t(R.id.dRr).text = if (d.rr != null && d.rr.isFinite())
                "Rasio potensi untung : risiko (dari nilai tersimpan): 1 : ${App.fmt(d.rr, 2)}"
            else "Rasio potensi untung : risiko: Tidak tersedia (jarak SL nol/tak valid)."
        } else {
            t(R.id.dDist).text = "Jarak entry tidak dihitung untuk peristiwa keluar (harga di atas adalah harga exit, bukan entry historis)."
            t(R.id.dRr).text = "Rasio potensi untung : risiko: Tidak tersedia untuk peristiwa keluar."
        }

        // Harga pasar terkini — jelas BUKAN entry, lengkap dengan umur data.
        val lp = try { App.getLastPrice(App.provider, s.pair, s.tf) } catch (e: Exception) { null }
        if (lp == null) {
            t(R.id.dLive).text = "Tidak tersedia"
            t(R.id.dLiveTime).text = "Belum ada cache harga untuk ${s.pair} ${s.tf} via ${App.provider}."
        } else {
            t(R.id.dLive).text = lp.price
            t(R.id.dLiveTime).text =
                "Diperbarui ${staleAgeLabel(lp.t, System.currentTimeMillis())} · via ${App.provider} ${s.tf}."
        }
    }

    // ---- 5. Alasan & konfirmasi (V16 F1/F2: jejak tersimpan) ----
    private fun paintAnalysis(s: Sig) {
        val hasTrace = s.strategy.isNotEmpty() || s.reasons.isNotEmpty() ||
            s.passedFilters.isNotEmpty() || s.failedFilters.isNotEmpty() || s.decidedAt > 0
        t(R.id.dStrategy).text = if (s.strategy.isNotEmpty()) "Strategi pembuat: ${s.strategy}"
        else "Strategi pembuat: Tidak tersedia (sinyal lama / bukan dari mesin keputusan). " +
            if (s.src == "backtest") "Lihat nama strategi pada hasil backtest." else ""
        t(R.id.dReasons).text = when {
            s.reasons.isNotEmpty() -> "Alasan mesin: " + s.reasons.joinToString(" · ")
            s.src == "dryrun-entry" -> "Alasan: keputusan strategi live lolos seluruh filter aktif, lalu posisi paper dibuka pada harga tercatat di atas."
            s.src == "dryrun-TP" -> "Alasan: harga menyentuh level take profit posisi paper (dihitung mesin, bukan tebakan)."
            s.src == "dryrun-SL" -> "Alasan: harga menyentuh level stop loss posisi paper (dihitung mesin, bukan tebakan)."
            s.src == "dryrun-trail" -> "Alasan: ${s.reasons.firstOrNull() ?: "trailing stop virtual tersentuh"}."
            s.src == "backtest" -> "Alasan: transaksi terakhir hasil backtest pada data historis (bukan sinyal live)."
            else -> "Alasan: Tidak tersedia untuk sumber \"${s.src}\"."
        }
        val confTxt = if (s.confidence > 0 && s.confidence <= 1.0)
            "\nConfidence mesin saat dibuat: ${App.fmt(s.confidence, 2)}." else ""
        val filtTxt = if (s.passedFilters.isNotEmpty() || s.failedFilters.isNotEmpty())
            "\nFilter lolos: ${if (s.passedFilters.isEmpty()) "—" else s.passedFilters.joinToString(", ")}" +
                "\nFilter menolak: ${if (s.failedFilters.isEmpty()) "—" else s.failedFilters.joinToString(", ")}" +
                if (s.decidedAt > 0) "\nDiputuskan: ${App.fmtDate(s.decidedAt)}." else ""
        else if (hasTrace) ""
        else "\nFilter & indikator saat dibuat: Tidak tersedia (tidak disimpan bersama sinyal ini)."
        // V16 F2: skor + penjelasan + penegas bukan probabilitas.
        val scoreTxt = if (s.score >= 0)
            "\nSkor Konfirmasi: ${App.fmt(s.score, 0)}/100" +
                (if (s.scoreDetail.isNotEmpty()) " (${s.scoreDetail})" else "") +
                " — tingkat konfirmasi menurut formula aplikasi, BUKAN probabilitas menang/profit."
        else "\nSkor Konfirmasi: Tidak dapat dihitung (data pembentuk tak tersedia)."
        t(R.id.dRationale).text = "Arah ${s.dir.ifEmpty { "—" }}: " +
            (if (!isEntryLike(s.src)) "peristiwa keluar mengikuti arah posisi paper yang ditutup."
            else if (!levelGeometryOk(s.dir, s.price, s.sl, s.tp)) "⚠ PERINGATAN: geometri level tidak lazim untuk arah ini — " +
                "nilai ditampilkan apa adanya, tidak dikoreksi. " +
                directionRationale(s.dir, s.price, s.sl, s.tp)
            else directionRationale(s.dir, s.price, s.sl, s.tp))
        t(R.id.dFilters).text = (confTxt + filtTxt + scoreTxt).trim().ifEmpty {
            "Tidak ada jejak tambahan tersimpan untuk sinyal ini."
        }
    }

    // ---- 6. Status data ----
    private fun paintDataStatus(s: Sig) {
        t(R.id.dEntryStatus).text = "Status entry: ${statusOf(s)}"
        t(R.id.dAge).text = if (s.t > 0)
            "Umur sinyal: ${staleAgeLabel(s.t, System.currentTimeMillis())} · terakhir diperbarui: ${App.fmtDate(s.t)}"
        else "Waktu sinyal tidak tercatat."
        // V18 #2: jejak verifikasi — identitas stabil + sumber tiap baris teks kaya.
        t(R.id.dVerify).text =
            "id: ${s.id.ifEmpty { "—" }}\n" +
                "src: ${s.src.ifEmpty { "—" }}\n" +
                "strategi tersimpan: ${s.strategy.ifEmpty { "—" }} · confidence: " +
                (if (s.confidence > 0) App.fmt(s.confidence, 2) else "—") + "\n" +
                "alasan tersimpan: ${s.reasons.size} · filter lolos/ditolak: " +
                "${s.passedFilters.size}/${s.failedFilters.size}\n" +
                "skor: ${if (s.score >= 0) App.fmt(s.score, 0) else "—"}" +
                (if (s.scoreDetail.isNotEmpty()) " (${s.scoreDetail})" else "")
        findViewById<View>(R.id.btnShare).setOnClickListener { shareRich(s) }
    }

    /** V18 #1: bagikan teks kaya — satu sumber dengan notifikasi & riwayat. */
    private fun shareRich(s: Sig) {
        try {
            val names = try { strategyList().associate { it.id to it.name } } catch (e: Exception) { emptyMap() }
            val rich = buildRichSignal(s, names)
            val sh = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, rich.title)
                putExtra(Intent.EXTRA_TEXT, "${rich.title}\n\n${rich.body}")
            }
            startActivity(Intent.createChooser(sh, "Bagikan sinyal"))
        } catch (e: Exception) {
            snack(this, "Gagal membagikan: ${e.message}")
        }
    }
}
