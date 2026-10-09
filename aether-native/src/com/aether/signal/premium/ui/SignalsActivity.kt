package com.aether.signal.premium.ui

import android.widget.LinearLayout
import android.widget.TextView

class SignalsActivity : BaseActivity("signals") {
    override fun subtitle() = ""
    private var mode = "signals"

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        appBar.showBack(false)
        // SUB-TAB native
        val seg = SegmentedControl(this)
        body.addView(seg)
        seg.setOptions(listOf("Signals", "Positions", "History"), listOf("signals", "dryrun", "riwayat").indexOf(mode).coerceAtLeast(0)) {
            mode = listOf("signals", "dryrun", "riwayat")[it]
            recreate()
        }
        val sp = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(this, 8)) }
        body.addView(sp)
        when (mode) {
            "signals" -> buildSignals(body)
            "dryrun" -> buildDry(body)
            else -> buildHist(body)
        }
    }

    private fun sigRow(parent: LinearLayout, dir: String, main: String, sub: String, right: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dp(this, 9), 0, dp(this, 9)) }
        val badge = TextView(this).apply {
            text = dir; textSize = T.CAP; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (dir == "LONG") C.GREEN else C.RED)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (dir == "LONG") 0xFF0A2E22.toInt() else 0xFF3A1E22.toInt())
                cornerRadius = dp(this@SignalsActivity, 7).toFloat()
            }
            setPadding(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 4))
        }
        r.addView(badge)
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setPadding(dp(this, 10), 0, 0, 0) }
        mid.addView(TextView(this).apply { text = main; setTextColor(C.TXT); textSize = T.BODY; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        mid.addView(TextView(this).apply { text = sub; setTextColor(C.DIM); textSize = T.CAP; typeface = android.graphics.Typeface.MONOSPACE })
        r.addView(mid)
        r.addView(TextView(this).apply { text = right; setTextColor(C.TXT); textSize = T.SMALL; typeface = android.graphics.Typeface.MONOSPACE; gravity = android.view.Gravity.END })
        parent.addView(r)
        parent.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 1); setBackgroundColor(C.LINE) })
    }

    private fun buildSignals(body: LinearLayout) {
        val p = panel(this); body.addView(p)
        val hr = row(p)
        hr.addView(TextView(this).apply { text = "Signals · ${App.signals.size}"; textSize = T.TITLE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        hr.addView(smBtn(this, "Hapus") { confirm(this, "Hapus", "Hapus semua sinyal?") { App.clearSignals(); recreate() } })
        if (App.signals.isEmpty()) { p.addView(stateBox(this, "empty", "Belum ada sinyal.")); return }
        for (s in App.signals.take(150)) sigRow(p, s.dir, "${s.pair}  ${s.tf}", "${App.fmtDate(s.t)} · via ${s.src}", App.fmt(s.price, 4))
    }

    private fun buildDry(body: LinearLayout) {
        val p = panel(this); body.addView(p)
        eyebrow(p, "Paper trading — bukan order sungguhan")
        desc(p, if (App.engRunning) "RUN · equity ${App.fmtMoney(BotEngine.equity ?: 0.0)} · net ${App.fmtMoney(BotEngine.netClosed())}" else "Berhenti · equity ${App.fmtMoney(BotEngine.equity ?: 0.0)} · net ${App.fmtMoney(BotEngine.netClosed())}")
        title(p, "Terbuka · ${BotEngine.positions.size}")
        table(p, listOf("Pair", "Arah", "Entry", "Mark", "PnL"),
            BotEngine.positions.map { o -> listOf(o.pair, o.dir, App.fmt(o.entry, 4), App.fmt(o.mark, 4), App.fmtMoney(o.upl)) })
        title(p, "Tertutup · ${BotEngine.closed.size}")
        for (t in BotEngine.closed.take(120)) sigRow(p, t.dir, "${t.pair}  ${t.result}", App.fmtDate(t.exitT), App.fmtMoney(t.pnl))
        if (BotEngine.closed.isEmpty()) desc(p, "Belum ada trade tertutup.")
        val r = row(p)
        r.addView(tbtn(this, if (App.engRunning) "Stop Engine" else "Start Engine", 0) {
            if (App.engRunning) BotEngine.stop() else toast(this, BotEngine.start())
            BotEngine.onTick = { runOnUiThread { recreate() } }
            recreate()
        })
        r.addView(smBtn(this, "Reset") {
            BotEngine.positions.clear(); BotEngine.closed.clear(); BotEngine.equity = null; recreate()
        })
    }

    private fun buildHist(body: LinearLayout) {
        val p = panel(this); body.addView(p)
        val wins = App.hist.count { it.result == "WIN" }
        val net = App.hist.sumOf { it.pnl }
        metricHero(p, "Net riwayat", App.fmtMoney(net), if (App.hist.isEmpty()) null else net >= 0, listOf(
            "Trades" to App.hist.size.toString(),
            "Win" to if (App.hist.isNotEmpty()) App.fmt(wins.toDouble() / App.hist.size * 100, 1) + "%" else "—"
        ))
        val hr = row(p)
        hr.addView(TextView(this).apply { text = "Riwayat tersimpan"; textSize = T.HEAD; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        hr.addView(smBtn(this, "Hapus") { confirm(this, "Hapus", "Hapus riwayat trade?") { App.clearHist(); recreate() } })
        if (App.hist.isEmpty()) { p.addView(stateBox(this, "empty", "Kosong.")); return }
        for (t in App.hist.take(150)) sigRow(p, t.direction, "${t.asset}", "${App.fmtDate(t.exitTime)} · ${t.result}", App.fmtMoney(t.pnl))
    }
}
