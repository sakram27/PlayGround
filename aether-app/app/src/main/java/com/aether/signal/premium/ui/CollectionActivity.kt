package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.data.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

// FITUR 3 (V20): Arsip Data Historis gratis. Unduh bertahap (paginasi publik,
// jeda antar halaman, bisa dibatalkan) → gabung → simpan arsip + katalog
// (sumber/pair/TF/rentang/waktu). Jujur soal batas: halaman/page-cap, Yahoo
// tanpa paginasi, Demo ditolak, tak ada klaim berjalan 24 jam.
class CollectionActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_collection
    override val showBack = true

    private var monthsSel = 3
    @Volatile private var cancelled = false

    override fun build() {
        setBar("Arsip Data", "Unduh bertahap · gratis · katalog jujur")
        findViewById<TextInputEditText>(R.id.colPair).setText(App.pair)
        for ((id, m) in listOf(R.id.btnCol1 to 1, R.id.btnCol3 to 3, R.id.btnCol6 to 6, R.id.btnCol12 to 12)) {
            findViewById<View>(id).setOnClickListener { monthsSel = m; paintRange() }
        }
        findViewById<MaterialButton>(R.id.btnColTf).setOnClickListener { cycleTf() }
        findViewById<MaterialButton>(R.id.btnColDl).setOnClickListener { download() }
        paintRange()
        paintCatalog()
    }

    private fun t(id: Int): TextView = findViewById(id)
    private var tfSel = "1h"

    private fun cycleTf() {
        val opts = listOf("15m", "1h", "4h", "1d")
        tfSel = opts[(opts.indexOf(tfSel) + 1) % opts.size]
        findViewById<MaterialButton>(R.id.btnColTf).text = "TF: $tfSel"
        paintRange()
    }

    private fun rangeMs(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return now - monthsSel * 30L * 86400000L to now
    }

    private fun paintRange() {
        try {
            val (a, b) = rangeMs()
            t(R.id.colRange).text =
                "Rentang: ${fmtUtc(a)} → ${fmtUtc(b)} · TF $tfSel · maks $RANGE_MAX_PAGES halaman " +
                    "($RANGE_PAGE_LIMIT/halaman)."
            findViewById<MaterialButton>(R.id.btnColTf).text = "TF: $tfSel"
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun download() {
        val sym = findViewById<TextInputEditText>(R.id.colPair).text.toString().trim()
        if (sym.isEmpty()) {
            snack(this, "Isi pair dulu.")
            return
        }
        val prov = App.provider
        val (a, b) = rangeMs()
        cancelled = false
        val prog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Mengunduh…")
            .setMessage("Menyiapkan…")
            .setNegativeButton("Batal") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        runBg {
            try {
                val r = fetchRangeCandles(prov, sym, tfSel, a, b) { cancelled }
                val total = ArchiveStore.save(prov, sym, tfSel, r.candles)
                val cat = App.loadCatalog().filterNot {
                    it.provider == prov && it.symbol.equals(sym, true) && it.timeframe == tfSel
                }.toMutableList()
                if (r.candles.isNotEmpty()) {
                    cat.add(0, ArchiveInfo(prov, sym.uppercase(), tfSel,
                        r.candles.first().t, r.candles.last().t, total,
                        System.currentTimeMillis(), prov))
                }
                App.saveCatalog(cat)
                runOnUiThread {
                    try { prog.dismiss() } catch (e: Exception) { }
                    if (cancelled) snack(this, "Dibatalkan.")
                    else {
                        snack(this, "Tersimpan ${r.candles.size} candle " +
                            "(${r.pages} halaman${if (r.capped) ", cap tercapai" else ""}).")
                        paintCatalog()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    try { prog.dismiss() } catch (e: Exception) { }
                    snack(this, if (cancelled) "Dibatalkan." else "Gagal: ${e.message}")
                }
            }
        }
    }

    private fun paintCatalog() {
        try {
            val cat = App.loadCatalog()
            val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.colList)
            list.vertical(this)
            t(R.id.colEmpty).visibility = if (cat.isEmpty()) View.VISIBLE else View.GONE
            list.adapter = SigAdapter(cat.map {
                SigItem("▤", "${it.symbol} · ${it.timeframe} · ${it.provider}",
                    "${it.count} candle · ${fmtUtc(it.firstT)} → ${fmtUtc(it.lastT)}\n" +
                        "diambil ${App.fmtDate(it.fetchedAt)} · sumber ${it.source}",
                    "HAPUS")
            }, onClick = { pos ->
                val e = cat.getOrNull(pos) ?: return@SigAdapter
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Hapus arsip?")
                    .setMessage("${e.symbol} ${e.timeframe} (${e.provider}). Data backtest & cache tak tersentuh.")
                    .setPositiveButton("Hapus") { _, _ ->
                        ArchiveStore.delete(e.provider, e.symbol, e.timeframe)
                        App.saveCatalog(App.loadCatalog().filterNot {
                            it.provider == e.provider && it.symbol == e.symbol && it.timeframe == e.timeframe
                        })
                        paintCatalog()
                        snack(this, "Arsip dihapus.")
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            })
        } catch (e: Exception) { /* abaikan */ }
    }
}
