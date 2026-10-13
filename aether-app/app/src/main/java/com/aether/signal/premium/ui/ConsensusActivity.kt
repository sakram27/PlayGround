package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.data.BACKTEST_TIMEFRAMES
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.engine.*

// FITUR 9 (V16): Konsensus Multi-Timeframe. Strategi yang SAMA di 3 TF;
// arah strategi MENTAH (filter tak diterapkan — dinyatakan eksplisit).
// Dievaluasi pada candle TERTUTUP terakhir (idx size-2) agar konsisten historis.
// Cache dipakai dulu (getCandles), batas 300 candle/TF, bg + batal.
// Hanya informasi: tak memblokir sinyal, tak mengubah konfigurasi.
class ConsensusActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_consensus
    override val showBack = true

    private var tfSet: List<String> = listOf("15m", "1h", "4h")
    private var votes: List<TfVote> = emptyList()
    @Volatile private var cancelled = false

    override fun build() {
        setBar("Konsensus Multi-TF", "Arah strategi mentah per TF")
        paintTfButtons()
        for ((id, set) in listOf(
            R.id.btnTfA to listOf("15m", "1h", "4h"),
            R.id.btnTfB to listOf("5m", "15m", "1h"),
            R.id.btnTfC to listOf("1h", "4h", "1d"))) {
            findViewById<View>(id).setOnClickListener { tfSet = set; votes = emptyList(); paintTfButtons(); paint() }
        }
        findViewById<View>(R.id.btnCsRun).setOnClickListener { runAll() }
        paint()
    }

    private fun t(id: Int): TextView = findViewById(id)

    private fun paintTfButtons() {
        try {
            t(R.id.csTfNow).text = "TF: ${tfSet.joinToString(" · ")}"
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun runAll() {
        val p0 = App.params
        if (p0 == null || App.candles.isEmpty()) {
            snack(this, "Data tidak cukup — jalankan backtest dulu.")
            return
        }
        val asset = p0.asset
        cancelled = false
        val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Konsensus…")
            .setMessage("Menyiapkan…")
            .setNegativeButton("Batal") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        runBg {
            val out = ArrayList<TfVote>()
            for ((i, tf) in tfSet.withIndex()) {
                if (cancelled) break
                runOnUiThread {
                    try { prog.setMessage("Menghitung $tf (${i + 1}/${tfSet.size})…") } catch (e: Exception) { }
                }
                try {
                    if (!BACKTEST_TIMEFRAMES.contains(tf)) {
                        out.add(TfVote(tf, VOTE_UNAVAILABLE, "TF tak didukung"))
                        continue
                    }
                    val fr = getCandles(App.provider, asset, tf, 300)
                    var cs = normalizeCandles(fr.candles.map {
                        mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
                    })
                    cs = filterByDate(cs, p0.startDate, p0.endDate)
                    if (cs.size < MIN_CANDLES) {
                        out.add(TfVote(tf, VOTE_UNAVAILABLE, "data belum cukup (${cs.size}c)"))
                        continue
                    }
                    val cache = buildCache(cs)
                    val idx = cs.size - 2 // candle tertutup terakhir
                    val dec = decideAt(cs, idx, cache, p0.copy(timeframe = tf))
                    out.add(TfVote(tf,
                        if (!dec.passed) VOTE_NONE else if (dec.direction == "SHORT") VOTE_SHORT else VOTE_LONG,
                        "tutup ${App.fmtT(cs[idx].t)}"))
                } catch (e: Exception) {
                    out.add(TfVote(tf, VOTE_UNAVAILABLE, (e.message ?: "gagal").take(60)))
                }
            }
            runOnUiThread {
                try { prog.dismiss() } catch (e: Exception) { }
                votes = out
                if (cancelled) snack(this, "Dibatalkan.")
                paint()
            }
        }
    }

    private fun paint() {
        try {
            if (votes.isEmpty()) {
                t(R.id.csSummary).text = "Belum dihitung — tekan Jalankan."
                t(R.id.csDetail).text = ""
                return
            }
            val v = consensusVerdict(votes)
            t(R.id.csSummary).text = v.summary
            t(R.id.csDetail).text = v.detail +
                "\n\nArah strategi mentah pada candle tertutup (filter tak diterapkan). " +
                "Hanya informasi pendukung — bukan jaminan, tak memblokir sinyal utama."
        } catch (e: Exception) { /* abaikan */ }
    }
}
