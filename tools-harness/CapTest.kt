import com.aether.signal.premium.engine.BacktestParams
import com.aether.signal.premium.engine.PortfolioAsset
import com.aether.signal.premium.engine.configFingerprint
import com.aether.signal.premium.engine.reconcileResult
import com.aether.signal.premium.engine.runBacktest
import com.aether.signal.premium.engine.runPortfolioBacktest
import com.aether.signal.premium.engine.sanitizeMaxOpenTrades
import com.aether.signal.premium.engine.sanitizeParams
import com.aether.signal.premium.ui.buildParamsFrom
import com.aether.signal.premium.ui.freezeSession

var cpass = 0
var cfail = 0
fun cok(n: String, c: Boolean, x: String = "") {
    if (c) { cpass++; println("PASS $n $x") } else { cfail++; println("FAIL $n $x") }
}

val T0 = 1_700_000_000_000L
const val TFMS = 900000L

/** Bar sintetis deterministik: o=close sebelumnya, c=o*(1+step(i)). */
fun mkBars(n: Int, o0: Double, step: (Int) -> Double, vol: Double = 100.0): List<Any?> {
    val out = ArrayList<Any?>()
    var prev = o0
    for (i in 0 until n) {
        val o = prev
        val c = o * (1 + step(i))
        val h = maxOf(o, c) * 1.001
        val l = minOf(o, c) * 0.999
        out.add(mapOf("t" to (T0 + i * TFMS), "o" to o, "h" to h, "l" to l, "c" to c, "v" to vol))
        prev = c
    }
    return out
}

fun baseParams(cap: Int, extra: BacktestParams.() -> BacktestParams = { this }): BacktestParams {
    val p = BacktestParams(
        asset = "TST", timeframe = "15m", initialCapital = 1000.0,
        riskPerTrade = 0.01, leverage = 1, feePercent = 0.0, slippagePercent = 0.0,
        slPercent = 0.5, tpPercent = 10.0, maxHolding = 2000,
        strategy = "ema_trend", maxOpenTrades = cap
    )
    return p.extra()
}

fun main() {
    capTestCore()
    capTest5B()
    println("CAP-TEST done pass=$cpass fail=$cfail")
    if (cfail > 0) kotlin.system.exitProcess(1)
}

fun capTestCore() {
    // B4/C4: validasi rentang 1..10, tanpa clamp diam-diam di luar fungsi ini
    // (sanitize jujur: luar rentang → batas + dilaporkan via diff di guard).
    run {
        cok("c04-rentang", sanitizeMaxOpenTrades(1) == 1 && sanitizeMaxOpenTrades(10) == 10 &&
            sanitizeMaxOpenTrades(0) == 1 && sanitizeMaxOpenTrades(11) == 10 &&
            sanitizeMaxOpenTrades(-5) == 1 && sanitizeMaxOpenTrades("3") == 3 &&
            sanitizeMaxOpenTrades("x") == 1 && sanitizeMaxOpenTrades(Double.NaN) == 1 &&
            sanitizeMaxOpenTrades(null) == 1 && sanitizeMaxOpenTrades(2.9) == 2)
        cok("c04-sanitize-params", sanitizeParams(BacktestParams(maxOpenTrades = 99)).maxOpenTrades == 10 &&
            sanitizeParams(BacktestParams(maxOpenTrades = -2)).maxOpenTrades == 1)
    }
    // Data naik stabil: tiap bar pasca-warmup = sinyal LONG, SL/TP tak tersentuh.
    val rise = mkBars(400, 100.0, { 0.001 })
    // C1: cap 1 → maks 1 bersamaan; 1 trade; sisanya ditolak batas.
    run {
        val r = runBacktest(rise, baseParams(1))
        cok("c01-cap1-observed", r.error == null && r.diag.maxOpenObserved == 1 &&
            r.diag.maxOpenTrades == 1, "obs=${r.diag.maxOpenObserved} tr=${r.totalTrades}")
        cok("c01-cap1-trades", r.totalTrades == 1 && r.trades.all { it.result == "EXPIRED" })
        cok("c01-cap1-rejected", r.diag.rejectedByLimit == r.diag.evaluatedBars - r.diag.signalsRaw &&
            r.diag.signalsRaw == 1 && r.diag.rejectedByLimit > 0,
            "sig=${r.diag.signalsRaw} rej=${r.diag.rejectedByLimit} eval=${r.diag.evaluatedBars}")
    }
    // C2/C3: cap 2/3 → observasi tepat, trade = cap.
    run {
        val r2 = runBacktest(rise, baseParams(2))
        val r3 = runBacktest(rise, baseParams(3))
        cok("c02-cap2", r2.error == null && r2.diag.maxOpenObserved == 2 && r2.totalTrades == 2 &&
            r2.diag.signalsRaw == 2, "obs=${r2.diag.maxOpenObserved} tr=${r2.totalTrades}")
        cok("c03-cap3", r3.error == null && r3.diag.maxOpenObserved == 3 && r3.totalTrades == 3 &&
            r3.diag.signalsRaw == 3, "obs=${r3.diag.maxOpenObserved} tr=${r3.totalTrades}")
    }
    // C12/C18-single: uid unik + rekonsiliasi bersih.
    run {
        val r = runBacktest(rise, baseParams(3))
        cok("c12-uid-unik", r.trades.map { it.uid }.toHashSet().size == r.trades.size)
        cok("c12-reconcile", reconcileResult(r).isEmpty(), reconcileResult(r).joinToString(";"))
    }
    // C9: TP dan SL.
    run {
        // Naik lalu lompat +50%: TP 20% tersentuh → WIN.
        val tp = mkBars(120, 100.0, { i -> if (i == 100) 0.5 else 0.001 })
        val rtp = runBacktest(tp, baseParams(1) { copy(tpPercent = 0.2, maxHolding = 2000) })
        cok("c09-tp-win", rtp.error == null && rtp.trades.any { it.result == "WIN" && it.reasons.any { s -> s.contains("leg TP") } },
            "tr=${rtp.totalTrades} res=${rtp.trades.map { it.result }}")
        // Naik lalu crash -60%: SL 50% tersentuh → LOSS berlabel SL.
        val sl = mkBars(120, 100.0, { i -> if (i == 100) -0.6 else 0.001 })
        val rsl = runBacktest(sl, baseParams(1) { copy(slPercent = 0.5, tpPercent = 10.0) })
        cok("c09-sl-loss", rsl.error == null && rsl.trades.any { it.result == "LOSS" && it.reasons.any { s -> s.contains("leg SL") } },
            "tr=${rsl.totalTrades} res=${rsl.trades.map { it.result }}")
    }
    // C10: kedaluwarsa maxHolding (holding == batas).
    run {
        val r = runBacktest(mkBars(200, 100.0, { 0.001 }), baseParams(1) { copy(maxHolding = 10) })
        cok("c10-expired", r.error == null && r.trades.any { it.result == "EXPIRED" && it.holding == 10 },
            "hold=${r.trades.map { it.holding to it.result }}")
    }
    // C11: fee+slippage dihitung tepat sekali.
    run {
        val p = baseParams(1) { copy(feePercent = 0.001, slippagePercent = 0.001, maxHolding = 10) }
        val r = runBacktest(mkBars(200, 100.0, { 0.001 }), p)
        val t = r.trades.firstOrNull()
        var okFee = false
        var okNet = false
        if (t != null) {
            val expFee = (t.entry * t.qty + t.exit * t.qty) * 0.001
            okFee = kotlin.math.abs(t.fees - expFee) < 1e-9
            val gross = (t.exit - t.entry) * t.qty
            okNet = kotlin.math.abs(t.pnl - (gross - t.fees)) < 1e-9
        }
        cok("c11-fee-sekali", r.error == null && okFee && okNet,
            "fee=${t?.fees} pnl=${t?.pnl}")
    }
    // C13: modal & ukuran posisi valid saat bersamaan.
    run {
        val r = runBacktest(rise, baseParams(3))
        cok("c13-qty-valid", r.error == null && r.trades.all { it.qty.isFinite() && it.qty > 0 } &&
            r.equityCurve.all { it.equity.isFinite() } &&
            kotlin.math.abs(r.finalCapital - (r.initialCapital + r.netProfit)) < 1e-6)
    }
    // C14: tanpa transaksi (datar total → strategi NONE).
    run {
        val r = runBacktest(mkBars(200, 100.0, { 0.0 }), baseParams(2))
        cok("c14-tanpa-trade", r.error == null && r.totalTrades == 0 &&
            r.diag.maxOpenObserved == 0 && r.diag.rejectedByLimit == 0,
            "tr=${r.totalTrades} sig=${r.diag.signalsRaw}")
    }
    // C15: tepat satu transaksi (1 sinyal di ekor data).
    run {
        val one = mkBars(70, 100.0, { i -> if (i == 68) 0.05 else 0.0 })
        val r = runBacktest(one, baseParams(3))
        cok("c15-satu-trade", r.error == null && r.totalTrades == 1,
            "tr=${r.totalTrades} sig=${r.diag.signalsRaw}")
    }
    // C7/closedHere: maxHolding=2 → tutup & sinyal bar sama dilewat (skip = #tutup).
    // Tepi: tutup pada bar terakhir (bukan slot evaluasi) tak menambah skip.
    run {
        val r = runBacktest(mkBars(200, 100.0, { 0.001 }), baseParams(1) { copy(maxHolding = 2) })
        val totalSlots = (200 - 1) - r.diag.startIdx
        cok("c07-closedhere-skip", r.error == null && r.totalTrades > 1 &&
            (r.diag.skippedInPosition == r.totalTrades || r.diag.skippedInPosition == r.totalTrades - 1) &&
            r.diag.evaluatedBars + r.diag.skippedInPosition == totalSlots &&
            // Bar tengah-holding dievaluasi lalu ditolak cap (dicatat, bukan hilang).
            r.diag.rejectedByLimit == r.diag.evaluatedBars - r.diag.signalsRaw &&
            r.trades.all { it.result == "EXPIRED" } &&
            r.trades.all { it.holding == 2 || it == r.trades.last() },
            "tr=${r.totalTrades} eval=${r.diag.evaluatedBars} skip=${r.diag.skippedInPosition}")
    }
    // C5: dua pair sinyal timestamp sama, cap 1 GLOBAL → maks 1 bersamaan.
    run {
        val assets = listOf(
            PortfolioAsset("AAA", mkBars(300, 100.0, { 0.001 })),
            PortfolioAsset("BBB", mkBars(300, 50.0, { 0.001 }))
        )
        val pr = runPortfolioBacktest(assets, baseParams(1))
        val r = pr.result
        cok("c05-global-cap1", r.error == null && pr.excluded.isEmpty() &&
            r.diag.maxOpenTrades == 1 && r.diag.maxOpenObserved == 1,
            "obs=${r.diag.maxOpenObserved} tr=${r.totalTrades} rej=${r.diag.rejectedByLimit}")
        cok("c05-tak-ganda", reconcileResult(r).isEmpty() &&
            r.trades.groupBy { it.asset }.values.sumOf { it.size } == r.totalTrades &&
            kotlin.math.abs(r.trades.sumOf { it.pnl } - r.netProfit) < 1e-6,
            "perPair=${r.trades.groupBy { it.asset }.mapValues { it.value.size }}")
    }
    // C6: dua posisi tutup timestamp sama (maxHolding sama, buka bar sama-berdekatan).
    run {
        val assets = listOf(
            PortfolioAsset("AAA", mkBars(200, 100.0, { 0.001 })),
            PortfolioAsset("BBB", mkBars(200, 50.0, { 0.001 }))
        )
        val pr = runPortfolioBacktest(assets, baseParams(2) { copy(maxHolding = 5) })
        val r = pr.result
        val byExit = r.trades.groupBy { it.exitTime }
        cok("c06-tutup-bersama", r.error == null && r.diag.maxOpenObserved == 2 &&
            byExit.values.any { it.size == 2 } &&
            r.trades.count { it.result == "EXPIRED" } >= 2,
            "obs=${r.diag.maxOpenObserved} tr=${r.totalTrades}")
    }
    // C8: posisi terbuka tetap dihitung (observasi mencakup yang belum tutup).
    run {
        // Cap 3, holding panjang: pada satu titik 3 posisi hidup bersamaan.
        val r = runBacktest(rise, baseParams(3))
        cok("c08-terbuka-dihitung", r.diag.maxOpenObserved == 3 && r.totalTrades == 3)
    }
    // C16: holding berbeda (campuran TP + expired).
    run {
        val mix = mkBars(300, 100.0, { i ->
            when {
                i == 150 -> 0.5
                else -> 0.001
            }
        })
        val r = runBacktest(mix, baseParams(3) { copy(tpPercent = 0.2) })
        val kinds = r.trades.map { it.result }.toSet()
        cok("c16-holding-beda", r.error == null && r.trades.map { it.holding }.toSet().size > 1 &&
            kinds.size > 1, "kinds=$kinds")
    }
    // C17: multi global cap 2 → tak pernah >2 lintas pair; agregat konsisten.
    run {
        val assets = listOf(
            PortfolioAsset("AAA", mkBars(300, 100.0, { 0.001 })),
            PortfolioAsset("BBB", mkBars(300, 50.0, { 0.001 })),
            PortfolioAsset("CCC", mkBars(300, 200.0, { 0.001 }))
        )
        val pr = runPortfolioBacktest(assets, baseParams(2))
        val r = pr.result
        cok("c17-global-cap2", r.error == null && r.diag.maxOpenObserved <= 2 &&
            r.diag.maxOpenObserved == 2 && pr.included.size == 3,
            "obs=${r.diag.maxOpenObserved} tr=${r.totalTrades} rej=${r.diag.rejectedByLimit}")
        cok("c17-agregat", reconcileResult(r).isEmpty() &&
            r.trades.groupBy { it.asset }.values.sumOf { it.size } == r.totalTrades)
    }
    // D: rantai backtest → snapshot → apply → sesi fp (level logika).
    run {
        val r = runBacktest(mkBars(300, 100.0, { 0.001 }),
            BacktestParams(asset = "BTCUSDT", timeframe = "15m", maxOpenTrades = 2))
        val g = com.aether.signal.premium.ui.EngineGlobalsData(
            strategy = "ema_trend", timeframe = "15m", provider = "binance",
            pair = "BTCUSDT", mode = "single", engPairs = listOf("BTCUSDT"))
        val rebuilt = sanitizeParams(buildParamsFrom(g, "BTCUSDT"))
        val refFp = configFingerprint(rebuilt)
        val sess = freezeSession(g, listOf("BTCUSDT"), 777L)
        cok("d-rantai-fp", r.error == null && sess.fp == refFp &&
            sess.params.maxOpenTrades == 1,
            "sessFp=${sess.fp.take(20)}")
    }
    // D-codec: snapshot v2 roundtrip; v1/berkas rusak ditolak eksplisit.
    run {
        val g = com.aether.signal.premium.ui.EngineGlobalsData(
            strategy = "macd", timeframe = "1h", provider = "binance",
            pair = "ETHUSDT", mode = "single", engPairs = listOf("ETHUSDT"),
            maxOpenTrades = 3, filterOn = mapOf("adx" to true))
        val p = sanitizeParams(buildParamsFrom(g, "ETHUSDT"))
        val snap = com.aether.signal.premium.ui.EngineSnapshot(
            g, p, listOf("ETHUSDT"), "binance", 500, "single",
            123456789L, "SUCCESS", true, "backtest-single")
        val enc = com.aether.signal.premium.ui.encodeEngineSnapshot(snap)
        val dec = com.aether.signal.premium.ui.decodeEngineSnapshot(enc)
        cok("d-codec-v2", dec == snap && dec?.params?.maxOpenTrades == 3 &&
            dec?.globals?.maxOpenTrades == 3, "len=${enc.length}")
        cok("d-codec-v1-ditolak",
            com.aether.signal.premium.ui.decodeEngineSnapshot(enc.replace("\"v\":2", "\"v\":1")) == null &&
            com.aether.signal.premium.ui.decodeEngineSnapshot("bukan-json") == null &&
            com.aether.signal.premium.ui.decodeEngineSnapshot(enc.replace("\"pairs\":[\"ETHUSDT\"]", "\"pairs\":[]")) == null)
    }
}

// ---------- Tahap 5B: konsistensi temporal portofolio ----------
fun capTest5B() {
    // B-1: candle hilang (gap 10 bar di tengah) → tetap jalan, rekonsiliasi bersih.
    run {
        val full = mkBars(300, 100.0, { 0.001 })
        val gap = full.filterIndexed { i, _ -> i < 140 || i >= 150 }
        val pr = runPortfolioBacktest(
            listOf(PortfolioAsset("AAA", full), PortfolioAsset("BBB", gap)),
            baseParams(2))
        val r = pr.result
        cok("b01-gap", r.error == null && pr.excluded.isEmpty() && reconcileResult(r).isEmpty(),
            "tr=${r.totalTrades} obs=${r.diag.maxOpenObserved}")
    }
    // B-2: timestamp beda antar pair (geser setengah interval) → deterministik.
    run {
        val a = mkBars(300, 100.0, { 0.001 })
        val b = mkBars(300, 50.0, { 0.001 }).map { m ->
            @Suppress("UNCHECKED_CAST")
            val mm = (m as Map<String, Any?>).toMutableMap()
            mm["t"] = (mm["t"] as Long) + TFMS / 2
            mm as Any?
        }
        val assets = listOf(PortfolioAsset("AAA", a), PortfolioAsset("BBB", b))
        val r1 = runPortfolioBacktest(assets, baseParams(2)).result
        val r2 = runPortfolioBacktest(assets, baseParams(2)).result
        cok("b02-ts-beda-deterministik", r1.error == null &&
            r1.totalTrades == r2.totalTrades && r1.netProfit == r2.netProfit &&
            r1.diag.maxOpenObserved <= 2 && reconcileResult(r1).isEmpty(),
            "tr=${r1.totalTrades} obs=${r1.diag.maxOpenObserved}")
    }
    // B-3: histori berakhir beda (BBB berhenti 50 bar lebih awal).
    run {
        val a = mkBars(300, 100.0, { 0.001 })
        val b = mkBars(250, 50.0, { 0.001 })
        val pr = runPortfolioBacktest(
            listOf(PortfolioAsset("AAA", a), PortfolioAsset("BBB", b)), baseParams(2))
        val r = pr.result
        val perSym = r.trades.groupBy { it.asset }.mapValues { it.value.size }
        cok("b03-akhir-beda", r.error == null && pr.excluded.isEmpty() &&
            (perSym["AAA"] ?: 0) > 0 && (perSym["BBB"] ?: 0) > 0 &&
            reconcileResult(r).isEmpty(), "perSym=$perSym")
    }
    // B-4: satu aset kosong → excluded eksplisit, sisanya jalan.
    run {
        val pr = runPortfolioBacktest(
            listOf(
                PortfolioAsset("AAA", mkBars(300, 100.0, { 0.001 })),
                PortfolioAsset("EMPTY", emptyList())),
            baseParams(2))
        cok("b04-excluded", pr.result.error == null && pr.excluded.containsKey("EMPTY") &&
            pr.included == listOf("AAA") && pr.result.totalTrades > 0,
            "excl=${pr.excluded}")
    }
    // B-5: ekuitas bersama konsisten — sizing posisi memakai ekuitas berjalan.
    run {
        val pr = runPortfolioBacktest(
            listOf(
                PortfolioAsset("AAA", mkBars(300, 100.0, { 0.001 })),
                PortfolioAsset("BBB", mkBars(300, 50.0, { 0.001 }))),
            baseParams(3))
        val r = pr.result
        val lastEq = r.equityCurve.filter { it.equity.isFinite() }.lastOrNull()?.equity
        cok("b05-ekuitas", r.error == null && lastEq != null &&
            kotlin.math.abs(lastEq - r.finalCapital) < 1e-6 &&
            kotlin.math.abs(r.finalCapital - (r.initialCapital + r.netProfit)) < 1e-6 &&
            r.equityCurve.zipWithNext { x, y -> y.t >= x.t }.all { it },
            "final=${r.finalCapital} net=${r.netProfit}")
    }
}
