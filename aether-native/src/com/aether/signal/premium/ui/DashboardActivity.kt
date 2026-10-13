package com.aether.signal.premium.ui

import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs

class DashboardActivity : BaseActivity("home") {
    override fun subtitle() = ""
    private var status: TextView? = null
    private var mktBox: LinearLayout? = null
    private var mktState: LinearLayout? = null

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        appBar.setFeed(null, "menghubungkan…")
        appBar.showBack(false)
        // RINGKASAN TERMINAL
        val hero = panel(this); body.addView(hero)
        eyebrow(hero, "Ringkasan terminal")
        val wins = App.hist.count { it.result == "WIN" }
        val net = App.hist.sumOf { it.pnl }
        metricHero(hero, "Net PnL riwayat", App.fmtMoney(net), if (App.hist.isEmpty()) null else net >= 0, listOf(
            "Sinyal" to App.signals.size.toString(),
            "Trades" to App.hist.size.toString(),
            "Win" to if (App.hist.isNotEmpty()) App.fmt(wins.toDouble() / App.hist.size * 100, 1) + "%" else "—",
            "Posisi" to BotEngine.positions.size.toString()
        ))
        val er = row(hero)
        er.addView(tbtn(this, if (App.engRunning) "Stop Engine" else "Start Engine", 0) {
            if (App.engRunning) BotEngine.stop() else toast(this, BotEngine.start())
            BotEngine.onTick = { runOnUiThread { recreate() } }
            recreate()
        })
        status = statusTv(this)
        status!!.text = if (App.engRunning) "RUN · ${BotEngine.lastScan}" else "Engine berhenti."
        hero.addView(status)
        // PASAR
        val mk = panel(this); body.addView(mk)
        val hr = row(mk)
        hr.addView(TextView(this).apply { text = "Pasar"; textSize = T.TITLE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        hr.addView(smBtn(this, "Semua →") { navTo("markets") })
        mktState = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mk.addView(mktState)
        mktBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mk.addView(mktBox)
        loadMarketStrip()
        // SINYAL TERBARU
        val sg = panel(this); body.addView(sg)
        val sr = row(sg)
        sr.addView(TextView(this).apply { text = "Sinyal terbaru"; textSize = T.TITLE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        sr.addView(smBtn(this, "Semua →") { navTo("signals") })
        if (App.signals.isEmpty()) sg.addView(stateBox(this, "empty", "Belum ada sinyal — Start engine atau jalankan backtest."))
        for (s in App.signals.take(4)) {
            val t = TextView(this).apply {
                text = "${s.dir}  ${s.pair}  ${s.tf}   ${App.fmt(s.price, 4)}"
                setTextColor(if (s.dir == "LONG") C.GREEN else C.RED)
                textSize = T.BODY; typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dp(this, 7), 0, dp(this, 7))
            }
            sg.addView(t)
            sg.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 1); setBackgroundColor(C.LINE) })
        }
        // KONFIGURASI AKTIF (ringkas)
        val cf = panel(this); body.addView(cf)
        val cr = row(cf)
        cr.addView(TextView(this).apply { text = "Strategi aktif"; textSize = T.TITLE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        cr.addView(smBtn(this, "Lab →") { navTo("lab") })
        val c = App.appliedCfg()
        if (c == null) desc(cf, "NOT APPLIED — jalankan backtest atau Start engine.")
        else {
            desc(cf, "${c.strategyMode}: ${c.strategy}${if (c.combo.isNotEmpty()) " + " + c.combo.joinToString("+") else ""} · ${c.timeframe} · RR ${c.rr}")
            desc(cf, "Filter aktif: ${if (c.activeFilters.isNotEmpty()) c.activeFilters.joinToString(", ") else "—"} · Pairs: ${c.pairs.joinToString(", ")} · ${c.status}")
        }
        val lb = App.lastBt()
        if (lb != null) desc(cf, "Backtest terakhir: ${lb.mode} · ${lb.pairs.joinToString(",")} · ${lb.trades} trade · ${lb.status}")
    }

    private fun loadMarketStrip() {
        mktState?.removeAllViews()
        mktState?.addView(stateBox(this, "loading", "Memuat pasar…"))
        appBar.setFeed(null, "memuat…")
        Thread {
            try {
                val pairs = try { topPairs(App.provider, 5) } catch (e: Exception) { topPairs("demo", 5) }
                val rows = ArrayList<Triple<String, String, Double?>>()
                for (s in pairs) {
                    try {
                        val cs = getCandles(if (pairs == topPairs("demo", 5)) "demo" else App.provider, s, "1h", 60).candles
                        val last = cs.last(); val ref = cs[maxOf(0, cs.size - 25)]
                        rows.add(Triple(s, App.fmt(last.c, if (last.c > 1000) 2 else 4), (last.c - ref.c) / ref.c * 100))
                    } catch (e: Exception) {
                        rows.add(Triple(s, "—", null))
                    }
                }
                runOnUiThread {
                    mktState?.removeAllViews()
                    mktBox?.removeAllViews()
                    val ok = rows.count { it.third != null }
                    appBar.setFeed(ok > 0, if (ok > 0) "$ok/5 live" else "offline")
                    for ((s, pr, ch) in rows) {
                        val r = InstrumentRow(this)
                        r.bind(s, pr, ch, "NO DATA")
                        r.onTap = {
                            App.pair = s; App.savePair()
                            startActivity(android.content.Intent(this, MarketActivity::class.java).putExtra("focus", s))
                        }
                        mktBox?.addView(r)
                        mktBox?.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 1); setBackgroundColor(C.LINE) })
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    mktState?.removeAllViews()
                    mktState?.addView(stateBox(this, "error", "Pasar gagal dimuat: ${e.message}") { loadMarketStrip() })
                    appBar.setFeed(false, "gagal")
                }
            }
        }.start()
    }
}
