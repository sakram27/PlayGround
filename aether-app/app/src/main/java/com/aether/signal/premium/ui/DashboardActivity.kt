package com.aether.signal.premium.ui

import android.content.Intent
import android.view.View
import com.aether.signal.premium.R
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs
import com.google.android.material.button.MaterialButton

class DashboardActivity : BaseActivity(R.id.nav_home) {
    override val contentLayout = R.layout.activity_dashboard

    override fun build() {
        setBar("Aether Signal", "Terminal Sinyal Trading")
        paintHero()
        findViewById<MaterialButton>(R.id.btnEngine).setOnClickListener {
            if (App.engRunning) BotEngine.stop() else snack(this, BotEngine.start())
            BotEngine.onTick = { runOnUiThread { paintHero() } }
            paintHero()
        }
        findViewById<MaterialButton>(R.id.btnAllMkt).setOnClickListener { navTo("markets") }
        findViewById<MaterialButton>(R.id.btnAllSig).setOnClickListener { navTo("signals") }
        findViewById<MaterialButton>(R.id.btnLab).setOnClickListener { navTo("lab") }
        paintSignals()
        paintCfg()
        loadMarket()
    }

    private fun paintHero() {
        val wins = App.hist.count { it.result == "WIN" }
        val net = App.hist.sumOf { it.pnl }
        findViewById<android.widget.TextView>(R.id.heroNet).apply {
            text = App.fmtMoney(net)
            setTextColor(if (App.hist.isEmpty()) 0xFFE8EDF2.toInt() else if (net >= 0) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt())
        }
        findViewById<android.widget.TextView>(R.id.kSig).text = App.signals.size.toString()
        findViewById<android.widget.TextView>(R.id.kTr).text = App.hist.size.toString()
        findViewById<android.widget.TextView>(R.id.kWin).text =
            if (App.hist.isNotEmpty()) App.fmt(wins.toDouble() / App.hist.size * 100, 1) + "%" else "—"
        findViewById<android.widget.TextView>(R.id.kPos).text = BotEngine.positions.size.toString()
        findViewById<MaterialButton>(R.id.btnEngine).text = if (App.engRunning) "Stop Engine" else "Start Engine"
        findViewById<android.widget.TextView>(R.id.engStatus).text =
            if (App.engRunning) "RUN · ${BotEngine.lastScan}" else "Engine berhenti."
    }

    private fun paintSignals() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.sigList)
        list.vertical(this)
        val items = App.signals.take(4).map {
            SigItem(it.dir, "${it.pair}  ${it.tf}", "${App.fmtDate(it.t)} · via ${it.src}", App.fmt(it.price, 4))
        }
        list.adapter = SigAdapter(items)
        findViewById<android.widget.TextView>(R.id.sigEmpty).apply {
            visibility = if (App.signals.isEmpty()) View.VISIBLE else View.GONE
            text = "Belum ada sinyal — Start engine atau jalankan backtest."
        }
    }

    private fun paintCfg() {
        val c = App.appliedCfg()
        findViewById<android.widget.TextView>(R.id.cfgMain).text =
            if (c == null) "NOT APPLIED — jalankan backtest atau Start engine."
            else "${c.strategyMode}: ${c.strategy}${if (c.combo.isNotEmpty()) " + " + c.combo.joinToString("+") else ""} · ${c.timeframe} · RR ${c.rr}"
        val lb = App.lastBt()
        findViewById<android.widget.TextView>(R.id.cfgLast).text =
            if (lb == null) "Backtest terakhir: belum ada."
            else "Backtest: ${lb.mode} · ${lb.pairs.joinToString(",")} · ${lb.trades} trade · ${lb.status}"
    }

    private fun loadMarket() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.mktList)
        list.vertical(this)
        val load = findViewById<View>(R.id.mktLoad)
        val msg = findViewById<android.widget.TextView>(R.id.mktMsg)
        load.visibility = View.VISIBLE
        msg.visibility = View.GONE
        runBg {
            try {
                var prov: String
                val pairs = try {
                    prov = App.provider
                    topPairs(App.provider, 5)
                } catch (e: Exception) {
                    prov = "demo"
                    topPairs("demo", 5)
                }
                val rows = ArrayList<WatchItem>()
                for (s in pairs) {
                    try {
                        val cs = getCandles(prov, s, "1h", 60).candles
                        val last = cs.last(); val ref = cs[maxOf(0, cs.size - 25)]
                        rows.add(WatchItem(s, App.fmt(last.c, if (last.c > 1000) 2 else 4), (last.c - ref.c) / ref.c * 100, "", null))
                    } catch (e: Exception) {
                        rows.add(WatchItem(s, "—", null, "NO DATA", e.message))
                    }
                }
                runOnUiThread {
                    load.visibility = View.GONE
                    list.adapter = WatchAdapter(rows) {
                        App.pair = it.sym; App.savePair()
                        startActivity(Intent(this, MarketActivity::class.java))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    load.visibility = View.GONE
                    msg.visibility = View.VISIBLE
                    msg.text = "Pasar gagal dimuat: ${e.message}"
                }
            }
        }
    }
}
