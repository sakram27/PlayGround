package com.aether.signal.premium.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.aether.signal.premium.data.MARKET_TF_LABELS
import com.aether.signal.premium.data.MARKET_TIMEFRAMES
import com.aether.signal.premium.data.PROVIDER_IDS
import com.aether.signal.premium.data.PROVIDER_LABELS
import com.aether.signal.premium.data.YAHOO_UNIVERSE
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs
import com.aether.signal.premium.data.topPairsWithSource
import com.aether.signal.premium.engine.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MarketActivity : BaseActivity(R.id.nav_markets) {
    override val contentLayout = R.layout.activity_market
    private var prov = "binance"
    private var forex = false
    private var tf = "1h"
    private var query = ""
    private var rows: List<MktRow> = emptyList()

    data class MktRow(val sym: String, val prov: String, var price: String = "—", var chg: Double? = null, var sig: String = "…", var err: String? = null, var spark: List<Double> = emptyList())
    private var watchAdapter: WatchAdapter? = null

    override fun build() {
        setBar("Markets", "Watchlist profesional")
        prov = App.provider
        val seg = findViewById<MaterialButtonToggleGroup>(R.id.segCat)
        seg.check(if (forex) R.id.segForex else R.id.segCrypto)
        seg.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                forex = id == R.id.segForex
                if (forex) {
                    // Forex/metal hanya tersedia di Yahoo: cerminkan pada dropdown,
                    // jangan diam-diam memakai provider lain.
                    prov = "yahoo"
                    paintProv()
                    setStatus("Kategori Forex → provider Yahoo (Forex & Metal).")
                }
                paintProvStatus()
                load()
            }
        }
        paintProv()
        val tfView = findViewById<MaterialAutoCompleteTextView>(R.id.spTf)
        tfView.setSimpleItems(MARKET_TF_LABELS.toTypedArray())
        tfView.setText(MARKET_TF_LABELS[MARKET_TIMEFRAMES.indexOf(tf).coerceAtLeast(0)], false)
        // Penjamin sentuhan: ketuk field mana pun selalu membuka daftar (selain perilaku bawaan).
        tfView.setOnClickListener { tfView.showDropDown() }
        tfView.setOnItemClickListener { _, _, pos, _ ->
            tf = MARKET_TIMEFRAMES[pos]
            tfView.setText(MARKET_TF_LABELS[pos], false)
            setStatus("Timeframe ${MARKET_TF_LABELS[pos]} · memuat ulang…")
            load()
        }
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
            .addTextChangedListener { query = (it?.toString() ?: ""); paint() }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).vertical(this)
        findViewById<MaterialButton>(R.id.btnAllSig).setOnClickListener { openSignals() }
        paintSignals()
        paintProvStatus()
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener {
            findViewById<View>(R.id.btnRetry).visibility = View.GONE
            load()
        }
        intent.getStringExtra("focus")?.let {
            if (it.isNotEmpty()) {
                query = it
                findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search).setText(it)
            }
        }
        load()
    }

    private fun paintProv() {
        val v = findViewById<MaterialAutoCompleteTextView>(R.id.spProv)
        v.setSimpleItems(PROVIDER_LABELS.toTypedArray())
        v.setText(PROVIDER_LABELS[PROVIDER_IDS.indexOf(prov).coerceAtLeast(0)], false)
        v.setOnClickListener { v.showDropDown() }
        v.setOnItemClickListener { _, _, pos, _ ->
            prov = PROVIDER_IDS[pos]
            App.provider = prov
            App.prefs.edit().putString("provider", prov).apply()
            v.setText(PROVIDER_LABELS[pos], false)
            paintProvStatus()
            setStatus("Provider ${PROVIDER_LABELS[pos]} · memuat ulang…")
            load()
        }
    }

    private fun setStatus(m: String) {
        findViewById<TextView>(R.id.status).text = m
    }

    /** Status provider aktif: nama utuh + sumber data, tak bertumpuk (L1). */
    private fun paintProvStatus() {
        val pr = if (forex) "yahoo" else prov
        val name = PROVIDER_LABELS[PROVIDER_IDS.indexOf(pr).coerceAtLeast(0)]
        val src = when (pr) {
            "binance" -> "sumber: spot crypto"
            "bybit" -> "sumber: spot crypto"
            "yahoo" -> "sumber: Forex & Metal"
            else -> "sumber: offline (seeded)"
        }
        findViewById<TextView>(R.id.provStatus).text = "Aktif: $name · $src"
    }

    private fun load() {
        findViewById<View>(R.id.loading).visibility = View.VISIBLE
        findViewById<View>(R.id.btnRetry).visibility = View.GONE
        findViewById<TextView>(R.id.empty).visibility = View.GONE
        paintFallbackBanner(false, "")
        runBg {
            try {
                val pr = if (forex) "yahoo" else prov
                // Jujur soal sumber daftar (B): tandai bila ini daftar darurat.
                val (pairs, isFallback) = if (pr == "yahoo") {
                    YAHOO_UNIVERSE.keys.toList() to false
                } else {
                    topPairsWithSource(pr, 12)
                }
                rows = pairs.map { MktRow(it, pr) }
                runOnUiThread {
                    findViewById<View>(R.id.loading).visibility = View.GONE
                    if (isFallback) {
                        paintFallbackBanner(true,
                            "Mode terbatas: $pr tak terjangkau dari jaringan ini " +
                                "(umum: HTTP 451/403). Menampilkan 4 pair utama — " +
                                "harga tetap live per pair. Coba Yahoo atau Demo untuk daftar penuh.")
                    }
                    setStatus("Provider ${PROVIDER_LABELS[PROVIDER_IDS.indexOf(pr).coerceAtLeast(0)]} · TF $tf · memuat harga 0/${pairs.size}…")
                    paint()
                    fillPrices()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<View>(R.id.loading).visibility = View.GONE
                    findViewById<TextView>(R.id.empty).apply {
                        visibility = View.VISIBLE
                        text = "Gagal memuat daftar pair (${PROVIDER_LABELS[PROVIDER_IDS.indexOf(if (forex) "yahoo" else prov).coerceAtLeast(0)]}): ${e.message}"
                    }
                    setStatus("Gagal memuat daftar pair.")
                    findViewById<View>(R.id.btnRetry).visibility = View.VISIBLE
                }
            }
        }
    }

    private fun paintFallbackBanner(show: Boolean, msg: String) {
        findViewById<TextView>(R.id.fallbackBanner).apply {
            visibility = if (show) View.VISIBLE else View.GONE
            text = msg
        }
    }

    private fun fillPrices() {
        // Paralel 3 pekerja (B): 12 pair lambat jadi menit bila sekuensial + timeout.
        // Tiap baris diperbarui via notifyItemChanged — scroll & posisi terjaga (B5/B6).
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val total = rows.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        for (r in rows) {
            pool.execute {
                try {
                    val res = getCandles(r.prov, r.sym, tf, 200, 10000)
                    val cs = res.candles
                    val last = cs.last(); val ref = cs[maxOf(0, cs.size - 25)]
                    r.price = App.fmt(last.c, if (last.c > 1000) 2 else 4)
                    r.chg = (last.c - ref.c) / ref.c * 100
                    // Sparkline dari close historis yang SAMA (bukan data lain).
                    r.spark = cs.takeLast(60).map { it.c }
                    r.sig = try {
                        val norm = normalizeCandles(cs.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
                        val cache = buildCache(norm)
                        val dec = decideAt(norm, norm.size - 1, cache, BacktestParams(strategy = App.strategy))
                        if (!dec.passed) "NETRAL" else {
                            val flt = App.activeFilterCfgs()
                            if (flt.isNotEmpty() && !applyFilters(norm, norm.size - 1, cache, dec.direction, flt, FilterCtx(lastExit = -1000000000)).passed) "FILTER×" else dec.direction
                        }
                    } catch (e: Exception) { "NETRAL" }
                } catch (e: Exception) { r.err = (e.message ?: "error").take(120); r.spark = emptyList() }
                val d = done.incrementAndGet()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setStatus("Memuat harga $d/$total…")
                    // Perbarui baris ini saja pada adapter yang SAMA.
                    try {
                        (findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).adapter as? WatchAdapter)
                            ?.notifyRow(r.sym)
                    } catch (e: Exception) { refreshRows() }
                    if (d >= total) {
                        val ok = rows.count { it.err == null }
                        if (ok > 0) setStatus("$ok/$total live · $tf · klik = detail")
                        else {
                            setStatus("Semua pair gagal — coba Demo/Yahoo atau Coba lagi.")
                            findViewById<View>(R.id.btnRetry).visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
        pool.shutdown()
    }

    override fun onResume() {
        super.onResume()
        try { paintSignals() } catch (e: Exception) { /* layout belum siap */ }
    }

    private fun paintSignals() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.sigList)
        list.vertical(this)
        val items = App.signals.take(3).map {
            SigItem(it.dir, "${it.pair}  ${it.tf}", "${App.fmtDate(it.t)} · via ${it.src}", App.fmt(it.price, 4), it.pair)
        }
        list.adapter = SigAdapter(items)
        findViewById<TextView>(R.id.sigEmpty).apply {
            visibility = if (App.signals.isEmpty()) View.VISIBLE else View.GONE
            text = "Belum ada sinyal — jalankan backtest atau Start engine."
        }
    }

    private fun paint() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        val q = query.uppercase()
        val vis = rows.filter { q.isEmpty() || it.sym.contains(q) }
        findViewById<TextView>(R.id.empty).apply {
            visibility = if (vis.isEmpty() && rows.isNotEmpty()) View.VISIBLE else View.GONE
            text = "Tidak ada hasil."
        }
        val items = vis.map { WatchItem(it.sym, it.price, it.chg, it.sig, it.err, it.spark) }
        val cur = watchAdapter
        if (cur == null) {
            // SATU adapter untuk seumur layar (A11): tap buka detail pair yang benar (A9).
            watchAdapter = WatchAdapter(items) { item -> openRow(item.sym) }
            list.adapter = watchAdapter
        } else {
            cur.update(items)
        }
    }

    /** Bangun ulang item dari `rows` pada adapter yang SAMA (scroll terjaga). */
    private fun refreshRows() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        val q = query.uppercase()
        val items = rows.filter { q.isEmpty() || it.sym.contains(q) }
            .map { WatchItem(it.sym, it.price, it.chg, it.sig, it.err, it.spark) }
        val cur = watchAdapter
        if (cur == null) {
            watchAdapter = WatchAdapter(items) { item -> openRow(item.sym) }
            list.adapter = watchAdapter
        } else {
            cur.update(items)
        }
    }

    private fun openRow(sym: String) {
        val r = rows.find { it.sym == sym } ?: return
        if (r.err != null) {
            // Baris gagal tetap bisa disentuh: tampilkan alasan jujur, bukan diam.
            snack(this, "${r.sym}: ${r.err}")
            return
        }
        PairSheet(this, r.sym, r.prov, tf, r.price, r.chg, r.sig, r.spark,
                onBacktest = {
                    App.pair = r.sym; App.savePair(); App.provider = r.prov
                    App.prefs.edit().putString("provider", r.prov).apply()
                    startActivity(Intent(this, BacktestActivity::class.java))
                },
                onChart = {
                    App.pair = r.sym; App.savePair(); App.provider = r.prov
                    App.prefs.edit().putString("provider", r.prov).apply()
                    GraphActivity.open(this, r.sym, r.prov, tf)
                }).show()
    }
}
