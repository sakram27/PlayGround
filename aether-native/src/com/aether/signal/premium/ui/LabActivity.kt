package com.aether.signal.premium.ui

import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aether.signal.premium.engine.*

class LabActivity : BaseActivity("lab") {
    override fun subtitle() = ""
    private var query = ""

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        appBar.showBack(false)
        // KARTU AKSI: Backtest Console + AI Analysis (alur Lab → layar penuh, Back kembali)
        val go = panel(this); body.addView(go)
        eyebrow(go, "Ruang kerja")
        val r = row(go)
        val b1 = tbtn(this, "Backtest Console →", 0) { navKeep("backtest") }
        b1.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val b2 = tbtn(this, "AI Analysis →", 1) { navKeep("ai") }
        b2.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        r.addView(b1); r.addView(b2)
        val lb = App.lastBt()
        if (lb != null) desc(go, "Terakhir: ${lb.mode} · ${lb.pairs.joinToString(",")} · ${lb.trades} trade · ${lb.status}")
        // STRATEGI
        section(body, "lab_strat", "Strategi", true, { "${STRATEGIES.size} bawaan" }) { box ->
            val q = EditText(this).apply { hint = "Cari strategi…"; setTextColor(C.TXT); setHintTextColor(C.DIM); textSize = T.BODY }
            q.setOnEditorActionListener { _, _, _ -> query = q.text.toString(); recreate(); true }
            box.addView(q)
            for (m in strategyList().filter { query.isEmpty() || it.name.lowercase().contains(query.lowercase()) || it.id.contains(query.lowercase()) }) {
                val active = App.strategy == m.id
                val card = TerminalCard(this)
                card.addView(TextView(this).apply {
                    text = m.name; textSize = T.HEAD; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (active) C.GREEN else C.TXT)
                })
                card.addView(TextView(this).apply { text = "${m.id} · ${m.desc}"; setTextColor(C.MUT); textSize = T.SMALL })
                val rr = row(card)
                rr.addView(smBtn(this, if (active) "✓ Dipakai" else "Pakai") {
                    App.strategy = m.id; App.saveStrategy(); toast(this, "${m.id} dipakai di Backtest."); recreate()
                })
                val cb = CheckBox(this).apply {
                    text = "combo"; isChecked = App.comboExtra.contains(m.id) || defOn(m.id); setTextColor(C.MUT); textSize = T.SMALL
                }
                cb.setOnCheckedChangeListener { _, on -> if (on) App.comboExtra.add(m.id) else App.comboExtra.remove(m.id) }
                rr.addView(cb)
                box.addView(card)
            }
        }
        // HYPEROPT
        section(body, "lab_opt", "Hyperopt", true, { "27 kombinasi" }) { box ->
            desc(box, "Grid-search SL × TP × risiko pada data terakhir. Terbaik ≠ aktif — terapkan manual.")
            val st = statusTv(this); box.addView(st)
            box.addView(tbtn(this, "Jalankan Optimasi", 0) {
                val candles = App.candles
                val base = App.params
                if (candles.size < 60 || base == null) { st.text = "Jalankan backtest dulu."; return@tbtn }
                st.text = "Mengoptimasi 27 kombinasi…"
                Thread {
                    val maps = candles.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }
                    val out = hyperoptSearch(maps, base)
                    runOnUiThread {
                        st.text = "Selesai: ${out.size} kombinasi."
                        table(box, listOf("#", "SL", "TP", "R", "Net", "PF", "DD"),
                            out.mapIndexed { i, o ->
                                listOf((i + 1).toString(), o.sl.toString(), o.tp.toString(), o.risk.toString(),
                                    App.fmtMoney(o.res.netProfit), App.fmtD(o.res.profitFactor), App.fmt(o.res.maxDrawdownPercent))
                            })
                        out.take(5).forEachIndexed { i, o ->
                            box.addView(smBtn(this, "Terapkan #${i + 1} → SL ${o.sl} TP ${o.tp} R ${o.risk}") {
                                App.slPct = o.sl; App.tpPct = o.tp; App.riskPct = o.risk
                                toast(this, "Tersimpan. Buka Backtest Console lalu Run.")
                            })
                        }
                    }
                }.start()
            })
        }
    }

    private fun defOn(id: String): Boolean =
        App.comboExtra.contains(id) || setOf("ema_trend", "supertrend", "rsi", "macd", "breakout").contains(id)
}
