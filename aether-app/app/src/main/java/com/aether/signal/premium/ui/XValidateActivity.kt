package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.data.getCandles

// FITUR 5 (V20): Validasi silang Binance↔Bybit (simbol & kuotasi identik).
// Inner-join per timestamp; toleransi harga default 0,5%. Volume hanya
// dilaporkan (wajar beda antar exchange). Simbol tak sebanding / fetch gagal →
// status jujur, tanpa perbandingan palsu. Kecocokan ≠ bukti benar.
class XValidateActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_xval
    override val showBack = true

    private var tfSel = "15m"
    @Volatile private var cancelled = false

    override fun build() {
        setBar("Validasi Silang", "Binance vs Bybit · simbol sebanding")
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.xvPair).setText(App.pair)
        findViewById<View>(R.id.btnXvTf).setOnClickListener { cycleTf() }
        findViewById<View>(R.id.btnXvRun).setOnClickListener { run() }
        paintIdle()
    }

    private fun t(id: Int): TextView = findViewById(id)

    private fun cycleTf() {
        val opts = listOf("15m", "1h", "4h", "1d")
        tfSel = opts[(opts.indexOf(tfSel) + 1) % opts.size]
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnXvTf).text = "TF: $tfSel"
    }

    private fun paintIdle() {
        try {
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnXvTf).text = "TF: $tfSel"
            t(R.id.xvStatus).text = "Siap. Hanya simbol sebanding (mis. BTCUSDT di kedua exchange)."
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun run() {
        val sym = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.xvPair)
            .text.toString().trim()
        if (sym.isEmpty()) {
            snack(this, "Isi simbol dulu.")
            return
        }
        if (!comparableSymbols(sym, sym)) {
            snack(this, "Simbol tak valid.")
            return
        }
        cancelled = false
        t(R.id.xvStatus).text = "Mengambil kedua sumber…"
        runBg {
            try {
                if (cancelled) throw RuntimeException("Dibatalkan.")
                val a = getCandles("binance", sym, tfSel, 200)
                if (cancelled) throw RuntimeException("Dibatalkan.")
                val b = getCandles("bybit", sym, tfSel, 200)
                // Sebanding = normalisasi identik di kedua sisi.
                if (!comparableSymbols(a.meta.symbol, b.meta.symbol)) {
                    throw RuntimeException("Simbol tak sebanding lintas provider " +
                        "(${a.meta.symbol} vs ${b.meta.symbol}).")
                }
                val rep = compareCandles(a.candles, b.candles)
                runOnUiThread { paintReport(sym, rep) }
            } catch (e: Exception) {
                runOnUiThread {
                    t(R.id.xvStatus).text = if (cancelled) "Dibatalkan."
                    else "Validasi silang tidak dilakukan: ${e.message}"
                    findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.xvList).adapter =
                        SigAdapter(emptyList())
                }
            }
        }
    }

    private fun paintReport(sym: String, rep: XValReport) {
        try {
            t(R.id.xvStatus).text =
                "$sym · $tfSel: ${rep.matched}/${rep.compared} candle cocok " +
                    "(toleransi $XVAL_PRICE_TOL_PCT%) · divergensi maks close " +
                    "${String.format(java.util.Locale.US, "%.3f", rep.maxDivPct)}% " +
                    "pada ${if (rep.worstT > 0) fmtUtc(rep.worstT) else "—"}.\n" +
                    "OHLC maks: O ${String.format(java.util.Locale.US, "%.3f", rep.maxDivO)}% · " +
                    "H ${String.format(java.util.Locale.US, "%.3f", rep.maxDivH)}% · " +
                    "L ${String.format(java.util.Locale.US, "%.3f", rep.maxDivL)}%.\n" +
                    "Asumsi: spot vs spot, definisi candle sama (batas hari UTC). " +
                    "Kecocokan bukan bukti benar — hanya konsistensi antar sumber."
            val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.xvList)
            list.vertical(this)
            list.adapter = SigAdapter(rep.rows.map {
                SigItem(if (it.divPct <= XVAL_PRICE_TOL_PCT) "✓" else "!",
                    fmtUtc(it.t),
                    "A ${App.fmt(it.aClose, 4)} · B ${App.fmt(it.bClose, 4)}",
                    String.format(java.util.Locale.US, "%.3f%%", it.divPct))
            })
        } catch (e: Exception) { /* abaikan */ }
    }
}
