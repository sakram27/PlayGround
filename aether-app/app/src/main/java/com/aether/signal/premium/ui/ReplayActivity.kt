package com.aether.signal.premium.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.google.android.material.button.MaterialButton

// FITUR 1 (V14): replay backtest per candle. Membaca App.candles + App.params
// apa adanya (hasil run terakhir) — TANPA request jaringan. Keputusan memakai
// mesin yang sama (ReplaySession meniru loop runBacktest). Jeda/lanjut/mundur/
// maju/reset tak menciptakan duplikat (sesi deterministik + reset penuh).
class ReplayActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_replay
    override val showBack = true

    private var session: ReplaySession? = null
    private var playing = false
    private var t2i: Map<Long, Int> = emptyMap()
    private val handler = Handler(Looper.getMainLooper())
    private val log = ArrayList<String>()
    private val playTick = object : Runnable {
        override fun run() {
            if (!playing) return
            val s = session ?: return
            val f = s.step()
            if (f == null) {
                playing = false
                paintPlayBtn()
                pushLog("Selesai — ${s.doneSteps()} bar, ${s.tradesSoFar().size} transaksi.")
                paint()
                return
            }
            noteFrame(f)
            paint()
            handler.postDelayed(this, 600)
        }
    }

    override fun build() {
        setBar("Replay Backtest", "Per candle · mesin yang sama")
        val p = App.params
        if (p == null || App.candles.isEmpty()) {
            findViewById<TextView>(R.id.rpStatus).text =
                "Data historis tidak tersedia — jalankan backtest dulu, lalu buka replay dari halaman hasil."
            setControlsEnabled(false)
            return
        }
        try {
            session = ReplaySession(App.candles, p)
        } catch (e: Exception) {
            session = null
        }
        val s = session
        if (s == null || s.error != null) {
            findViewById<TextView>(R.id.rpStatus).text =
                s?.error ?: "Gagal menyiapkan replay: ${"data tidak valid."}"
            setControlsEnabled(false)
            return
        }
        findViewById<TextView>(R.id.rpTitle).text =
            "${p.asset} · ${p.timeframe} · ${p.strategy} · ${s.totalSteps} bar evaluasi"
        t2i = s.candles.mapIndexed { i, c -> c.t to i }.toMap()
        findViewById<MaterialButton>(R.id.btnRpReset).setOnClickListener { doReset() }
        findViewById<MaterialButton>(R.id.btnRpBack).setOnClickListener { doBack() }
        findViewById<MaterialButton>(R.id.btnRpPlay).setOnClickListener { togglePlay() }
        findViewById<MaterialButton>(R.id.btnRpStep).setOnClickListener { doStep() }
        paint()
    }

    override fun onDestroy() {
        playing = false
        try { handler.removeCallbacks(playTick) } catch (e: Exception) { /* abaikan */ }
        super.onDestroy()
    }

    private fun setControlsEnabled(on: Boolean) {
        for (id in listOf(R.id.btnRpReset, R.id.btnRpBack, R.id.btnRpPlay, R.id.btnRpStep)) {
            try { findViewById<View>(id).isEnabled = on } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun doReset() {
        playing = false
        handler.removeCallbacks(playTick)
        session?.reset()
        log.clear()
        pushLog("Di-reset. Tekan Maju/Putar.")
        paintPlayBtn()
        paint()
    }

    private fun doBack() {
        val wasPlaying = playing
        playing = false
        handler.removeCallbacks(playTick)
        val s = session ?: return
        s.back(1)
        rebuildLog()
        if (wasPlaying) { /* tetap jeda setelah mundur manual */ }
        paintPlayBtn()
        paint()
    }

    private fun doStep() {
        playing = false
        handler.removeCallbacks(playTick)
        val s = session ?: return
        val f = s.step()
        if (f == null) {
            pushLog("Selesai — ${s.doneSteps()} bar, ${s.tradesSoFar().size} transaksi.")
        } else noteFrame(f)
        paintPlayBtn()
        paint()
    }

    private fun togglePlay() {
        val s = session ?: return
        if (playing) {
            playing = false
            handler.removeCallbacks(playTick)
        } else {
            if (s.isFinished()) {
                snack(this, "Replay selesai — tekan Reset untuk mengulang.")
                return
            }
            playing = true
            handler.post(playTick)
        }
        paintPlayBtn()
    }

    private fun paintPlayBtn() {
        try {
            findViewById<MaterialButton>(R.id.btnRpPlay).text = if (playing) "⏸ Jeda" else "▶ Putar"
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun pushLog(s: String) {
        log.add(0, s)
        while (log.size > 40) log.removeAt(log.size - 1)
    }

    /** Bangun ulang log dari frame terakhir (dipakai setelah mundur). */
    private fun rebuildLog() {
        log.clear()
        val s = session ?: return
        val f = s.lastFrame()
        if (f == null) pushLog("Di-reset. Tekan Maju/Putar.")
        else pushLog("Mundur ke bar ${f.idx + 1}/${f.total} · ${s.tradesSoFar().size} transaksi sejauh ini.")
    }

    private fun noteFrame(f: ReplayFrame) {
        val tag = "Bar ${f.idx + 1}/${f.total}"
        if (f.justOpened != null) {
            val o = f.justOpened
            pushLog("$tag · ENTRY ${o.dir} @ ${App.fmt(o.entry, 4)} · SL ${App.fmt(o.sl, 4)} · TP ${App.fmt(o.tp, 4)}")
        }
        if (f.closed != null) {
            val t = f.closed
            pushLog("$tag · EXIT ${t.result} ${App.fmtMoney(t.pnl)} (R ${App.fmt(t.rMultiple)})")
        } else if (f.signalDir != null && f.pendingEntry) {
            pushLog("$tag · SINYAL ${f.signalDir} — filter LOLOS, entry bar berikut")
        } else if (f.signalDir != null && f.filterPassed == false) {
            val why = f.filterFailed.firstOrNull()?.let { humanFilterReason(it) } ?: "ditolak filter"
            pushLog("$tag · SINYAL ${f.signalDir} — DITOLAK ($why)")
        }
    }

    private fun indText(v: Double, d: Int = 2): String =
        if (!v.isFinite()) "—" else App.fmt(v, d)

    private fun paint() {
        val s = session ?: return
        try {
            val f = s.lastFrame()
            val shown = minOf(s.doneSteps(), s.totalSteps)
            findViewById<TextView>(R.id.rpStatus).text =
                if (s.isFinished()) "Selesai · ${s.doneSteps()}/${s.totalSteps} bar · ${s.tradesSoFar().size} transaksi · Ekuitas ${App.fmtMoney(s.currentEquity())}"
                else "Bar $shown/${s.totalSteps} · Ekuitas ${App.fmtMoney(s.currentEquity())} · ${s.tradesSoFar().size} transaksi"
            if (f == null) {
                findViewById<TextView>(R.id.rpBar).text = "Belum mulai — tekan Maju atau Putar."
                findViewById<TextView>(R.id.rpTime).text = ""
                findViewById<TextView>(R.id.rpOhlc).text = ""
                findViewById<TextView>(R.id.rpInd).text = ""
                findViewById<TextView>(R.id.rpDecision).text = ""
                findViewById<TextView>(R.id.rpPosition).text = ""
                findViewById<ReplayStripView>(R.id.rpStrip).setData(emptyList(), emptyList())
            } else {
                val c = f.candle
                findViewById<TextView>(R.id.rpBar).text = "Bar ${f.idx + 1}/${f.total}"
                findViewById<TextView>(R.id.rpTime).text = App.fmtT(c.t)
                findViewById<TextView>(R.id.rpOhlc).text =
                    "O ${App.fmt(c.o, 4)} · H ${App.fmt(c.h, 4)} · L ${App.fmt(c.l, 4)} · C ${App.fmt(c.c, 4)}"
                val ind = s.indAt(f.idx)
                findViewById<TextView>(R.id.rpInd).text =
                    "EMA50 ${indText(ind.ema50)} · RSI ${indText(ind.rsi14, 1)} · ADX ${indText(ind.adx14, 1)} · ATR ${indText(ind.atr14, 4)}"
                findViewById<TextView>(R.id.rpDecision).text = describe(f)
                val o = f.open
                findViewById<TextView>(R.id.rpPosition).text = if (o == null) "Posisi: — (datar)"
                else "Posisi ${o.dir}: entry ${App.fmt(o.entry, 4)} · SL ${App.fmt(o.sl, 4)} · TP ${App.fmt(o.tp, 4)} · qty ${App.fmt(o.qty, 4)}"
                paintStrip(f)
            }
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rpLog).apply {
                vertical(this@ReplayActivity)
                adapter = SigAdapter(log.map { SigItem("•", it, "", "") })
            }
        } catch (e: Exception) { /* layout belum siap */ }
    }

    private fun describe(f: ReplayFrame): String = when {
        f.justOpened != null -> "ENTRY dieksekusi @ open bar ini."
        f.closed != null -> "EXIT ${f.closed.result} @ ${App.fmt(f.closed.exit, 4)}."
        f.signalDir != null && f.pendingEntry -> "SINYAL ${f.signalDir} — filter LOLOS. Entry dijadwalkan @ open bar berikut."
        f.signalDir != null && f.filterPassed == false -> {
            val tops = f.filterFailed.take(2).joinToString("; ") { humanFilterReason(it) }
            "SINYAL ${f.signalDir} — DITOLAK filter: $tops."
        }
        f.signalDir != null -> "SINYAL ${f.signalDir} — dilewati (level/ukuran tak valid)."
        f.evaluated -> "Datar — tak ada sinyal strategi pada bar ini."
        else -> "Bar dilewati (dalam posisi / belum dievaluasi)."
    }

    private fun paintStrip(f: ReplayFrame) {
        try {
            val s = session ?: return
            val cs = s.candles
            val from = maxOf(0, f.idx - 59)
            val closes = cs.subList(from, f.idx + 1).map { it.c }
            val marks = ArrayList<StripMarker>()
            for (t in s.tradesSoFar()) {
                val ei = t2i[t.entryTime]
                val xi = t2i[t.exitTime]
                if (ei != null && ei in from..f.idx) marks.add(StripMarker(ei - from, "entry"))
                if (xi != null && xi in from..f.idx) marks.add(StripMarker(xi - from,
                    when (t.result) { "WIN" -> "win"; "LOSS" -> "loss"; else -> "expired" }))
            }
            findViewById<ReplayStripView>(R.id.rpStrip).setData(closes, marks)
        } catch (e: Exception) { /* abaikan */ }
    }
}
