package com.aether.signal.premium.ui

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aether.signal.premium.data.YAHOO_UNIVERSE
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs
import com.aether.signal.premium.engine.*

class MarketActivity : BaseActivity("markets") {
    override fun subtitle() = ""
    private var status: TextView? = null
    private var listBox: LinearLayout? = null
    private var stateBox2: LinearLayout? = null
    private var rows: List<MktRow> = emptyList()
    private var prov = "binance"; private var cat = "Crypto Top"; private var tf = "1h"
    private var query = ""

    data class MktRow(val sym: String, val prov: String, var price: String = "—", var chg: Double? = null, var sig: String = "…", var err: String? = null)

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        appBar.showBack(false)
        val focus = intent.getStringExtra("focus") ?: ""
        // KONTROL RINGKAS
        val ctl = panel(this); body.addView(ctl)
        eyebrow(ctl, "Universe & timeframe")
        val seg = SegmentedControl(this)
        ctl.addView(seg)
        seg.setOptions(listOf("Crypto", "Forex"), if (cat.startsWith("Forex")) 1 else 0) {
            cat = if (it == 0) "Crypto Top" else "Forex & XAU"; load()
        }
        val r = row(ctl)
        val pl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        pl.addView(fieldLabel(this, "Provider"))
        pl.addView(spinner(this, listOf("binance", "bybit", "yahoo", "demo"), listOf("binance", "bybit", "yahoo", "demo").indexOf(prov).coerceAtLeast(0)) { prov = listOf("binance", "bybit", "yahoo", "demo")[it]; load() })
        r.addView(pl)
        val tl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        tl.addView(fieldLabel(this, "Timeframe"))
        tl.addView(spinner(this, listOf("15m", "1h", "4h", "1d"), listOf("15m", "1h", "4h", "1d").indexOf(tf).coerceAtLeast(0)) { tf = listOf("15m", "1h", "4h", "1d")[it]; load() })
        r.addView(tl)
        val q = EditText(this).apply { hint = "Cari pair…"; setTextColor(C.TXT); setHintTextColor(C.DIM); textSize = T.BODY }
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { query = s.toString(); paint() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        ctl.addView(q)
        if (focus.isNotEmpty()) { query = focus; q.setText(focus) }
        status = statusTv(this); ctl.addView(status)
        // DAFTAR RINGKAS
        val lp = panel(this); body.addView(lp)
        eyebrow(lp, "Watchlist")
        stateBox2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lp.addView(stateBox2)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lp.addView(listBox)
        load()
    }

    private fun load() {
        stateBox2?.removeAllViews()
        stateBox2?.addView(stateBox(this, "loading", "Memuat daftar…"))
        listBox?.removeAllViews()
        Thread {
            try {
                val pr = if (cat.startsWith("Forex")) "yahoo" else prov
                val pairs = if (pr == "yahoo") YAHOO_UNIVERSE.keys.toList() else topPairs(pr, 12)
                rows = pairs.map { MktRow(it, pr) }
                runOnUiThread {
                    stateBox2?.removeAllViews()
                    status?.text = "Memuat harga 0/${pairs.size}…"
                    paint()
                    fillPrices()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    stateBox2?.removeAllViews()
                    stateBox2?.addView(stateBox(this, "error", "Gagal: ${e.message}. Coba Demo atau Yahoo.") { load() })
                    status?.text = "Gagal."
                }
            }
        }.start()
    }

    private fun fillPrices() {
        Thread {
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
                runOnUiThread { status?.text = "Memuat harga $d/${rows.size}…"; paint() }
            }
            val ok = rows.count { it.err == null }
            runOnUiThread { status?.text = if (ok > 0) "$ok/${rows.size} live · klik baris = detail" else "Semua gagal — coba Demo/Yahoo." }
        }.start()
    }

    private fun paint() {
        val box = listBox ?: return
        box.removeAllViews()
        val q = query.uppercase()
        val vis = rows.filter { q.isEmpty() || it.sym.contains(q) }
        if (vis.isEmpty() && rows.isNotEmpty()) { box.addView(stateBox(this, "empty", "Tidak ada hasil.")); return }
        for (r in vis) {
            val row = InstrumentRow(this)
            row.bind(r.sym, if (r.err != null) "—" else r.price, r.chg, r.err ?: r.sig)
            row.onTap = {
                if (r.err == null) showDetail(r)
            }
            box.addView(row)
            box.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 1); setBackgroundColor(C.LINE) })
        }
    }

    private fun showDetail(r: MktRow) {
        val sh = Sheet(this)
        sh.title(r.sym)
        val b = sh.content()
        desc(b, "Harga ${r.price} · ${if (r.chg != null) App.fmt(r.chg!!) + "%" else "—"} · sinyal ${r.sig} · ${r.prov} ${tf}")
        b.addView(tbtn(this, "Pakai di Backtest", 0) {
            App.pair = r.sym; App.savePair(); App.provider = r.prov; sh.dismiss(); navKeep("backtest")
        })
        b.addView(tbtn(this, "Buka Grafik", 1) {
            sh.dismiss()
            App.pair = r.sym; App.savePair(); App.provider = r.prov
            GraphActivity.open(this, r.sym, r.prov, tf)
        })
        sh.show()
    }
}
