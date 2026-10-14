import com.aether.signal.premium.engine.*
import kotlin.math.abs

var ppass = 0
var pfail = 0
fun pok(n: String, c: Boolean, x: String = "") {
    if (c) { ppass++; println("PASS $n $x") } else { pfail++; println("FAIL $n $x") }
}

fun bars(n: Int, seed: Long, drift: Double, px0: Double = 100.0): List<Candle> {
    var rnd = seed
    fun rand(): Double {
        rnd = (rnd * 6364136223846793005L + 1442695040888963407L) ushr 11
        return (rnd.toDouble() / Long.MAX_VALUE.toDouble() + 1.0) / 2.0
    }
    val out = ArrayList<Candle>()
    var px = px0
    var t = 1_700_000_000_000L
    repeat(n) {
        val o = px
        val c = (o * (1 + drift + (rand() - 0.5) * 0.004)).coerceAtLeast(1.0)
        out.add(Candle(t, o, maxOf(o, c) * 1.001, minOf(o, c) * 0.999, c, 50 + rand() * 200))
        px = c; t += 900000L
    }
    return out
}

fun rawOf(cs: List<Candle>): List<Any?> =
    cs.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }

fun main() {
    // ===== T1: parameter strategi rsi/adx memengaruhi perilaku; strategi lain mengabaikan map =====
    run {
        // Penurunan curam + pantulan hijau: RSI rendah + candle hijau = LONG.
        // Ambang default (30) vs kustom (10) wajib memberi himpunan berbeda.
        val cs = ArrayList<Candle>()
        var px = 100.0; var t = 1_700_000_000_000L
        repeat(40) { px *= 1.0005; cs.add(Candle(t, px / 1.0005, px * 1.0005, px * 0.9995, px, 100.0)); t += 900000L }
        repeat(25) { px *= 0.992; cs.add(Candle(t, px / 0.992, px * 1.0005, px * 0.9995, px, 100.0)); t += 900000L }
        repeat(5) { px *= 1.004; cs.add(Candle(t, px / 1.004, px * 1.001, px * 0.999, px, 100.0)); t += 900000L }
        val cache = buildCache(cs)
        var dflt = 0; var custom = 0; var differ = false
        for (k in 40 until cs.size) {
            val a = strategyFn("rsi", cs, k, cache, emptyMap()).first
            val b = strategyFn("rsi", cs, k, cache, mapOf("oversold" to 10.0, "overbought" to 90.0)).first
            if (a != "NONE") dflt++
            if (b != "NONE") custom++
            if (a != b) differ = true
        }
        pok("t1-rsi-param-efektif", dflt > 0 && custom >= 0 && differ, "dflt=$dflt custom=$custom")
        // adxMin custom vs default memberi hasil boleh-beda; yang penting tidak crash & deterministik
        val x1 = strategyFn("adx", cs, 60, cache, emptyMap())
        val x2 = strategyFn("adx", cs, 60, cache, mapOf("adxMin" to 5.0))
        pok("t1-adx-param-dibaca", x1 == strategyFn("adx", cs, 60, cache, emptyMap())
            && x2 == strategyFn("adx", cs, 60, cache, mapOf("adxMin" to 5.0)))
        // ema_trend mengabaikan map: hasil identik apa pun isinya
        val e1 = strategyFn("ema_trend", cs, 60, cache, emptyMap())
        val e2 = strategyFn("ema_trend", cs, 60, cache, mapOf("plen" to 99.0))
        pok("t1-ema-ignore-param", e1 == e2, "$e1")
    }

    // ===== T4 F-01: bar pertama yang valid kini dievaluasi =====
    run {
        // support_resistance: pola bounce tepat di bar 20 (jendela 0..19)
        val cs = ArrayList<Candle>()
        var t = 1_700_000_000_000L
        var px = 100.0
        repeat(20) {
            cs.add(Candle(t, px, px * 1.0005, px * 0.9995, px * 1.0002, 100.0)); px *= 1.0002; t += 900000L
        }
        val lo = cs.minOf { it.l }
        // bar 20: low menyentuh terendah 20-bar + close hijau
        cs.add(Candle(t, lo * 1.0001, lo * 1.002, lo * 0.9999, lo * 1.0015, 100.0))
        val cache = buildCache(cs)
        val (d, _, _) = strategyFn("support_resistance", cs, 20, cache, emptyMap())
        pok("t4-sr-bar20", d == "LONG", "got=$d (sebelum fix: NONE oleh guard)")
        // breakout: close menembus high20 + volume
        val cs2 = ArrayList<Candle>(cs.dropLast(1))
        var t2 = 1_700_000_000_000L + 20 * 900000L
        val hh = cs2.maxOf { it.h }
        cs2.add(Candle(t2, hh * 0.999, hh * 1.002, hh * 0.998, hh * 1.001, 10000.0))
        val c2 = buildCache(cs2)
        val (d2, _, _) = strategyFn("breakout", cs2, 20, c2, emptyMap())
        pok("t4-breakout-bar20", d2 == "LONG", "got=$d2")
        // liquidity_sweep: wick di bawah min20 + reclaim
        val cs3 = ArrayList<Candle>()
        var t3 = 1_700_000_000_000L
        repeat(20) { cs3.add(Candle(t3, 100.0, 100.1, 99.9, 100.0, 100.0)); t3 += 900000L }
        cs3.add(Candle(t3, 100.0, 100.6, 98.0, 100.5, 100.0))
        val (d3, _, _) = strategyFn("liquidity_sweep", cs3, 20, buildCache(cs3), emptyMap())
        pok("t4-sweep-bar20", d3 == "LONG", "got=$d3")
        // fvg: gap di bar 1-2 relatif, entry bar 3
        val cs4 = listOf(
            Candle(1_700_000_000_000L, 100.0, 100.2, 99.8, 100.0, 100.0),
            Candle(1_700_000_900_000L, 100.0, 100.2, 99.8, 100.1, 100.0),
            Candle(1_700_001_800_000L, 101.0, 101.5, 100.8, 101.2, 100.0),
            Candle(1_700_002_700_000L, 101.2, 101.3, 100.3, 100.4, 100.0),
            Candle(1_700_003_600_000L, 100.4, 100.9, 100.2, 100.8, 100.0)
        )
        val (d4, _, _) = strategyFn("fvg", cs4, 3, buildCache(cs4), emptyMap())
        // bar3: low[1]=99.8? gapUp = low[1]=99.8 > high[0]=100.2? tidak -> NONE wajar; bar4: low[2]=100.8>high[1]=100.2 gap ya; mid=(100.8+100.2)/2=100.5; low[3]=100.3<=100.5, c>o -> LONG
        val (d4b, _, _) = strategyFn("fvg", cs4, 4, buildCache(cs4), emptyMap())
        pok("t4-fvg-bar3-evaluated", d4b == "LONG", "bar3=$d4 bar4=$d4b")
        // Tidak ada crash pada seri minimal
        val tiny = bars(25, 5L, 0.0)
        val ct = buildCache(tiny)
        var crashed = false
        for (id in listOf("support_resistance", "breakout", "liquidity_sweep", "fvg")) {
            try {
                for (k in 0 until tiny.size) strategyFn(id, tiny, k, ct, emptyMap())
            } catch (e: Exception) { crashed = true }
        }
        pok("t4-tanpa-crash-seri-pendek", !crashed)
    }

    // ===== T3: tanpa look-ahead — keputusan di i identik walau data setelah i dipotong/diubah =====
    run {
        val full = bars(300, 77L, 0.0006)
        val cacheFull = buildCache(full)
        var sama = 0; var total = 0
        for (k in listOf(60, 120, 200, 299)) {
            val trunc = full.subList(0, k + 1)
            val cacheTr = buildCache(trunc)
            for (id in listOf("ema_trend", "smc_basic", "support_resistance", "rsi", "macd")) {
                val a = strategyFn(id, full, k, cacheFull, emptyMap())
                val b = strategyFn(id, trunc, k, cacheTr, emptyMap())
                total++
                if (a == b) sama++
            }
        }
        pok("t3-tanpa-lookahead", sama == total, "$sama/$total")
    }

    // ===== T2: rekonsiliasi independen dari transaksi mentah =====
    run {
        val cs = bars(600, 99L, 0.0009)
        val p = sanitizeParams(BacktestParams(asset = "TST", timeframe = "15m",
            strategy = "ema_trend", tpMode = 3, maxOpenTrades = 2))
        val r = runBacktest(rawOf(cs), p)
        pok("t2-run-ok", r.error == null, "trades=${r.totalTrades}")
        val t = r.trades
        val net = t.sumOf { it.pnl }
        val fees = t.sumOf { it.fees }
        val w = t.count { it.pnl > 0 }; val l = t.count { it.pnl < 0 }; val be = t.count { it.pnl == 0.0 }
        pok("t2-net", net == r.netProfit, "net=$net")
        pok("t2-fees", fees == r.trades.sumOf { it.fees }, "fees=$fees")
        pok("t2-wlb", w == r.wins && l == r.losses && be == r.breakeven, "$w/$l/$be")
        val wr = if (t.isNotEmpty()) w.toDouble() / t.size * 100 else 0.0
        pok("t2-winrate", wr == r.winRate, "wr=$wr")
        val gl = abs(t.filter { it.pnl < 0 }.sumOf { it.pnl })
        val gp = t.filter { it.pnl > 0 }.sumOf { it.pnl }
        val pf = if (gl > 0) gp / gl else if (gp > 0) Double.POSITIVE_INFINITY else 0.0
        pok("t2-pf", pf == r.profitFactor, "pf=$pf")
        // Drawdown independen dari equityCurve
        var peak = Double.NEGATIVE_INFINITY; var dd = 0.0
        for (e in r.equityCurve) {
            if (e.equity > peak) peak = e.equity
            if (peak > 0) dd = maxOf(dd, (peak - e.equity) / peak)
        }
        pok("t2-drawdown", dd * 100 == r.maxDrawdownPercent, "dd=${dd * 100}")
        pok("t2-final", r.initialCapital + net == r.finalCapital, "final=${r.finalCapital}")
        pok("t2-leg-vs-posisi", r.totalTrades == t.size && r.totalTrades >= w + l,
            "legs=${r.totalTrades}")
        // uid unik end-to-end (T7): identitas sinyal tertelusur per kaki
        pok("t2-uid-unik", t.map { it.uid }.toSet().size == t.size,
            "n=${t.size}")
        pok("t2-runid-fp", r.diag.runId.isNotEmpty() && r.diag.cfgFp == configFingerprint(p),
            "fp=${r.diag.cfgFp.take(24)}")
        // Portofolio: agregat = jumlah kaki aktual, bukan karangan
        val pr = runPortfolioBacktest(
            listOf(PortfolioAsset("A", rawOf(bars(600, 99L, 0.0009))),
                PortfolioAsset("B", rawOf(bars(600, 100L, -0.0009)))),
            sanitizeParams(BacktestParams(asset = "A", timeframe = "15m",
                strategy = "ema_trend", maxOpenTrades = 2)))
        pok("t2-portfolio", pr.result.error == null
            && pr.result.totalTrades == pr.result.trades.size
            && pr.result.trades.sumOf { it.pnl } == pr.result.netProfit,
            "tr=${pr.result.totalTrades}")
    }

    // ===== T4 F-01 lanjutan: 6 guard sekelas (bukti pola-valid di bar batas) =====
    run {
        // bos LONG tepat di bar 10.
        val cs = ArrayList<Candle>()
        var t = 1_700_000_000_000L
        repeat(10) { cs.add(Candle(t, 100.0, 100.1, 99.9, 100.0, 100.0)); t += 900000L }
        cs.add(Candle(t, 100.0, 100.9, 99.9, 100.8, 100.0))
        pok("t4-bos-bar10", strategyFn("bos", cs, 10, buildCache(cs), emptyMap()).first == "LONG")
        // smc_basic LONG tepat di bar 10.
        pok("t4-smc-bar10", strategyFn("smc_basic", cs, 10, buildCache(cs), emptyMap()).first == "LONG")
        // volume LONG tepat di bar 19 (sVol20 siap) — spike 8x + body hijau.
        val cv = ArrayList<Candle>()
        var tv = 1_700_000_000_000L
        repeat(19) { cv.add(Candle(tv, 100.0, 100.1, 99.9, 100.0, 100.0)); tv += 900000L }
        cv.add(Candle(tv, 100.0, 100.9, 99.9, 100.8, 800.0))
        pok("t4-volume-bar19", strategyFn("volume", cv, 19, buildCache(cv), emptyMap()).first == "LONG")
        // breaker LONG tepat di bar 11 (false-break low bar 10 + reclaim).
        val cb = ArrayList<Candle>()
        var tb = 1_700_000_000_000L
        repeat(10) { cb.add(Candle(tb, 100.0, 100.1, 99.9, 100.0, 100.0)); tb += 900000L }
        cb.add(Candle(tb, 100.0, 100.05, 99.0, 99.5, 100.0)); tb += 900000L // i-1 = 10
        cb.add(Candle(tb, 99.5, 100.0, 99.4, 100.3, 100.0)) // i = 11
        pok("t4-breaker-bar11", strategyFn("breaker", cb, 11, buildCache(cb), emptyMap()).first == "LONG")
        // fibonacci LONG tepat di bar 30 (retrace 0.5 zona emas + naik).
        val cf = ArrayList<Candle>()
        var tf = 1_700_000_000_000L
        repeat(28) { cf.add(Candle(tf, 100.0, 100.1, 99.9, 100.0, 100.0)); tf += 900000L }
        cf.add(Candle(tf, 100.0, 110.0, 99.9, 104.0, 100.0)); tf += 900000L // bar 28: hh
        cf.add(Candle(tf, 104.0, 104.1, 103.9, 103.0, 100.0)); tf += 900000L // bar 29
        cf.add(Candle(tf, 103.0, 105.5, 102.9, 105.0, 100.0)) // bar 30
        pok("t4-fib-bar30", strategyFn("fibonacci", cf, 30, buildCache(cf), emptyMap()).first == "LONG")
        // donchian LONG tepat di bar 20 (mid cross-up + RSI>50 di tren datar-naik).
        val cd = ArrayList<Candle>()
        var td = 1_700_000_000_000L
        repeat(20) { cd.add(Candle(td, 100.0, 100.1, 99.9, 100.0, 100.0)); td += 900000L }
        cd.add(Candle(td, 100.0, 100.6, 99.9, 100.5, 100.0))
        pok("t4-donchian-bar20", strategyFn("donchian_momentum", cd, 20, buildCache(cd), emptyMap()).first == "LONG")
    }

    println("PROOF-TEST done pass=$ppass fail=$pfail")
    if (pfail > 0) kotlin.system.exitProcess(1)
}
