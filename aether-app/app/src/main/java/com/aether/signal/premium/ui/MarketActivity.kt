package com.aether.signal.premium.ui

import android.content.Intent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.aether.signal.premium.data.YAHOO_UNIVERSE
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs
import com.aether.signal.premium.engine.*
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MarketActivity : BaseActivity(R.id.nav_markets) {
    override val contentLayout = R.layout.activity_market
    private var prov = "binance"
    private var forex = false
    private var tf = "1h"
    private var query = ""
    private var rows: List<MktRow> = emptyList()

    data class MktRow(val sym: String, val prov: String, var price: String = "—", var chg: Double? = null, var sig: String = "…", var err: String? = null)

    override fun build() {
        setBar("Markets", "Watchlist profesional")
        prov = App.provider
        val seg = findViewById<MaterialButtonToggleGroup>(R.id.segCat)
        seg.check(if (forex) R.id.segForex else R.id.segCrypto)
        seg.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                forex = id == R.id.segForex
                load()
            }
        }
        val provs = listOf("binance", "bybit", "yahoo", "demo")
        findViewById<MaterialAutoCompleteTextView>(R.id.spProv).apply {
            setSimpleItems(provs.toTypedArray())
            setText(prov, false)
            setOnItemClickListener { _, _, pos, _ -> prov = provs[pos]; load() }
        }
        val tfs = listOf("15m", "1h", "4h", "1d")
        findViewById<MaterialAutoCompleteTextView>(R.id.spTf).apply {
            setSimpleItems(tfs.toTypedArray())
            setText(tf, false)
            setOnItemClickListener { _, _, pos, _ -> tf = tfs[pos]; load() }
        }
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
            .addTextChangedListener { query = (it?.toString() ?: ""); paint() }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).vertical(this)
        intent.getStringExtra("focus")?.let {
            if (it.isNotEmpty()) {
                query = it
                findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search).setText(it)
            }
        }
        load()
    }

    private fun setStatus(m: String) {
        findViewById<TextView>(R.id.status).text = m
    }

    private fun load() {
        findViewById<View>(R.id.loading).visibility = View.VISIBLE
        findViewById<TextView>(R.id.empty).visibility = View.GONE
        runBg {
            try {
                val pr = if (forex) "yahoo" else prov
                val pairs = if (pr == "yahoo") YAHOO_UNIVERSE.keys.toList() else topPairs(pr, 12)
                rows = pairs.map { MktRow(it, pr) }
                runOnUiThread {
                    findViewById<View>(R.id.loading).visibility = View.GONE
                    setStatus("Memuat harga 0/${pairs.size}…")
                    paint()
                    fillPrices()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<View>(R.id.loading).visibility = View.GONE
                    findViewById<TextView>(R.id.empty).apply {
                        visibility = View.VISIBLE
                        text = "Gagal: ${e.message}. Coba Demo atau Yahoo."
                    }
                    setStatus("Gagal.")
                }
            }
        }
    }

    private fun fillPrices() {
        runBg {
            var done = 0
            for (r in rows) {
                try {
                    val res = getCandles(r.prov, r.sym, tf, 200)
                    val cs = res.candles
                    val last = cs.last(); val ref = cs[maxOf(0, cs.size - 25)]
                    r.price = App.fmt(last.c, if (last.c > 1000) 2 else 4)
                    r.chg = (last.c - ref.c) / ref.c * 100
                    r.sig = try {
                        val norm = normalizeCandles(cs.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
                        val cache = buildCache(norm)
                        val dec = decideAt(norm, norm.size - 1, cache, BacktestParams(strategy = App.strategy))
                        if (!dec.passed) "NETRAL" else {
                            val flt = App.activeFilterCfgs()
                            if (flt.isNotEmpty() && !applyFilters(norm, norm.size - 1, cache, dec.direction, flt, FilterCtx(lastExit = -1000000000)).passed) "FILTER×" else dec.direction
                        }
                    } catch (e: Exception) { "NETRAL" }
                } catch (e: Exception) { r.err = (e.message ?: "error").take(80) }
                done++
                val d = done
                runOnUiThread { setStatus("Memuat harga $d/${rows.size}…"); paint() }
            }
            val ok = rows.count { it.err == null }
            runOnUiThread { setStatus(if (ok > 0) "$ok/${rows.size} live · klik = detail" else "Semua gagal — coba Demo/Yahoo.") }
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
        list.adapter = WatchAdapter(vis.map { WatchItem(it.sym, it.price, it.chg, it.sig, it.err) }) { item ->
            val r = rows.find { it.sym == item.sym } ?: return@WatchAdapter
            if (r.err != null) return@WatchAdapter
            PairSheet(this, r.sym, r.prov, tf, r.price, r.chg, r.sig,
                onBacktest = {
                    App.pair = r.sym; App.savePair(); App.provider = r.prov
                    startActivity(Intent(this, BacktestActivity::class.java))
                },
                onChart = {
                    App.pair = r.sym; App.savePair(); App.provider = r.prov
                    GraphActivity.open(this, r.sym, r.prov, tf)
                }).show()
        }
    }
}
