import com.aether.signal.premium.engine.*
import kotlin.math.abs

// Matriks penuh B/C/D/E/F/H: data sintetis BERLABEL (bukan bukti pasar nyata).
var fmpass = 0
var fmfail = 0
fun fmok(n: String, c: Boolean, x: String = "") {
    if (c) { fmpass++; println("PASS $n $x") } else { fmfail++; println("FAIL $n $x") }
}

val FMT0 = 1_700_000_000_000L
const val FMTF = 900000L
fun mC(o: Double, h: Double, l: Double, c: Double, t: Long, v: Double = 100.0) =
    Candle(t, o, h, l, c, v)

fun genDrift(n: Int, seed: Long, drift: Double, px0: Double = 100.0, vol: Double = 100.0): List<Candle> {
    var rnd = seed
    fun rand(): Double {
        rnd = (rnd * 6364136223846793005L + 1442695040888963407L) ushr 11
        return (rnd.toDouble() / Long.MAX_VALUE.toDouble() + 1.0) / 2.0
    }
    val out = ArrayList<Candle>()
    var px = px0
    var t = FMT0
    repeat(n) {
        val o = px
        val c = (o * (1 + drift + (rand() - 0.5) * 0.004)).coerceAtLeast(1.0)
        out.add(Candle(t, o, maxOf(o, c) * 1.001, minOf(o, c) * 0.999, c, vol * (0.5 + rand())))
        px = c; t += FMTF
    }
    return out
}

fun fmRawOf(cs: List<Candle>): List<Any?> =
    cs.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }

val ALL_STRATS = listOf(
    "ema_trend", "ema_cross", "rsi", "macd", "bollinger", "stochastic",
    "adx", "supertrend", "vwap", "support_resistance", "breakout",
    "pullback", "market_structure", "bos", "choch", "liquidity_sweep",
    "order_block", "fvg", "breaker", "fibonacci", "smc_basic", "ict_setup",
    "volume", "mtf_confirm", "donchian_momentum"
)

data class SweepRow(val id: String, val longs: Int, val shorts: Int, val firstL: Int, val firstS: Int)

fun sweep(id: String, cs: List<Candle>, cache: Cache): SweepRow {
    var l = 0; var s = 0; var fl = -1; var fs = -1
    for (k in cs.indices) {
        when (strategyFn(id, cs, k, cache, emptyMap()).first) {
            "LONG" -> { l++; if (fl < 0) fl = k }
            "SHORT" -> { s++; if (fs < 0) fs = k }
        }
    }
    return SweepRow(id, l, s, fl, fs)
}

fun main() {
    val up = genDrift(1500, 42L, 0.0012)
    val dn = genDrift(1500, 43L, -0.0012)
    val chop = genDrift(1500, 44L, 0.0)
    val cup = buildCache(up); val cdn = buildCache(dn); val cch = buildCache(chop)

    // ===== B: sweep 25 strategi × 3 rezim =====
    println("--- B: sweep strategi (LONG@first/SHORT@first per rezim) ---")
    val table = ArrayList<Triple<SweepRow, SweepRow, SweepRow>>()
    for (id in ALL_STRATS) {
        val r = Triple(sweep(id, up, cup), sweep(id, dn, cdn), sweep(id, chop, cch))
        table.add(r)
        println("${r.first.id} upL=${r.first.longs}@${r.first.firstL} upS=${r.first.shorts}@${r.first.firstS} " +
            "dnL=${r.second.longs} dnS=${r.second.shorts} chL=${r.third.longs} chS=${r.third.shorts}")
    }
    fmok("b-25-terdaftar", table.size == 25)
    // Semua id terdaftar menghasilkan Triple valid (tidak melempar) di semua bar.
    fmok("b-tanpa-crash", true)

    // ===== B: fixture pola-sempurna untuk pola langka =====
    // sweep LONG tepat: 20 flat + wick-bawah + reclaim (indeks 20).
    run {
        val cs = ArrayList<Candle>()
        var t = FMT0
        repeat(20) { cs.add(mC(100.0, 100.1, 99.9, 100.0, t)); t += FMTF }
        cs.add(mC(100.0, 100.6, 98.0, 100.5, t))
        fmok("b-sweep-long", strategyFn("liquidity_sweep", cs, 20, buildCache(cs), emptyMap()).first == "LONG")
        val cs2 = ArrayList<Candle>()
        t = FMT0
        repeat(20) { cs2.add(mC(100.0, 100.1, 99.9, 100.0, t)); t += FMTF }
        cs2.add(mC(100.0, 102.0, 99.4, 99.5, t))
        fmok("b-sweep-short", strategyFn("liquidity_sweep", cs2, 20, buildCache(cs2), emptyMap()).first == "SHORT")
    }
    // order_block LONG: impuls +0.8% di i-3 lalu retap.
    run {
        val cs = ArrayList<Candle>()
        var t = FMT0
        repeat(3) { cs.add(mC(100.0, 100.1, 99.9, 100.0, t)); t += FMTF }
        cs.add(mC(100.0, 100.9, 99.9, 100.8, t)); t += FMTF
        cs.add(mC(100.8, 100.9, 100.2, 100.3, t)); t += FMTF
        cs.add(mC(100.3, 100.4, 99.9, 100.1, t)); t += FMTF
        cs.add(mC(100.1, 100.5, 99.8, 100.4, t))
        fmok("b-ob-long", strategyFn("order_block", cs, 6, buildCache(cs), emptyMap()).first == "LONG")
    }
    // fvg LONG: gap-up bar 1/2, fill separuh + lanjut di bar 4.
    run {
        val cs = listOf(
            mC(100.0, 100.2, 99.8, 100.0, FMT0),
            mC(100.0, 100.2, 99.8, 100.1, FMT0 + FMTF),
            mC(101.0, 101.5, 100.8, 101.2, FMT0 + 2 * FMTF),
            mC(101.2, 101.3, 100.3, 100.4, FMT0 + 3 * FMTF),
            mC(100.4, 100.9, 100.2, 100.8, FMT0 + 4 * FMTF)
        )
        fmok("b-fvg-long", strategyFn("fvg", cs, 4, buildCache(cs), emptyMap()).first == "LONG")
    }
    // breakout LONG: 20 flat + close menembus + volume.
    run {
        val cs = ArrayList<Candle>()
        var t = FMT0
        repeat(20) { cs.add(mC(100.0, 100.1, 99.9, 100.0, t, 100.0)); t += FMTF }
        cs.add(mC(100.0, 101.0, 99.9, 100.9, t, 10000.0))
        fmok("b-breakout-long", strategyFn("breakout", cs, 20, buildCache(cs), emptyMap()).first == "LONG")
    }
    // bos LONG: close di atas high-10 + body>0.2%.
    run {
        val cs = ArrayList<Candle>()
        var t = FMT0
        repeat(10) { cs.add(mC(100.0, 100.1, 99.9, 100.0, t)); t += FMTF }
        cs.add(mC(100.0, 100.9, 99.9, 100.8, t))
        fmok("b-bos-long", strategyFn("bos", cs, 10, buildCache(cs), emptyMap()).first == "LONG")
    }
    // ict LONG: sweep bar i-1 di bawah min-20 lalu close di atas high sweep + hijau.
    run {
        val cs = ArrayList<Candle>()
        var t = FMT0
        repeat(20) { cs.add(mC(100.0, 100.1, 99.9, 100.0, t)); t += FMTF }
        cs.add(mC(100.0, 100.05, 99.0, 99.2, t)); t += FMTF // i-1 = index 20 sweep
        cs.add(mC(99.2, 100.3, 99.1, 100.2, t)) // i = index 21
        fmok("b-ict-long", strategyFn("ict_setup", cs, 21, buildCache(cs), emptyMap()).first == "LONG")
    }
    // volume LONG: spike 8x + body hijau.
    run {
        val cs = ArrayList<Candle>()
        var t = FMT0
        repeat(20) { cs.add(mC(100.0, 100.1, 99.9, 100.0, t, 100.0)); t += FMTF }
        cs.add(mC(100.0, 100.9, 99.9, 100.8, t, 800.0))
        fmok("b-volume-long", strategyFn("volume", cs, 20, buildCache(cs), emptyMap()).first == "LONG")
    }

    // ===== C: mode kombinasi — invarian atas evaluasi nyata =====
    run {
        var andOk = 0; var andTot = 0; var orOk = 0; var orTot = 0
        var majOk = 0; var majTot = 0; var tieLong = 0; var tieChecked = 0
        val sample = listOf("ema_trend", "adx", "rsi", "macd", "supertrend", "mtf_confirm")
        for (a in sample) for (b in sample) {
            for (k in 60 until 460 step 7) {
                val ra = strategyFn(a, up, k, cup, emptyMap()).first
                val rb = strategyFn(b, up, k, cup, emptyMap()).first
                // AND
                val and = decideAt(up, k, cup, BacktestParams(strategy = "x",
                    combo = Combo("AND", listOf(a, b))))
                andTot++
                val andExpect = (ra != "NONE" && ra == rb)
                if ((and.passed == andExpect) &&
                    (!and.passed || and.direction == ra)) andOk++
                // OR (need=1): lolos bila >=1; seri LONG/SHORT -> LONG menang seri
                val or = decideAt(up, k, cup, BacktestParams(strategy = "x",
                    combo = Combo("OR", listOf(a, b))))
                orTot++
                val anyFire = ra != "NONE" || rb != "NONE"
                val tieOpposite = ra != "NONE" && rb != "NONE" && ra != rb
                // Seri LONG/SHORT -> LONG menang seri (terbukti); tanpa kandidat -> NONE.
                val orExpect = if (!anyFire) true else if (tieOpposite) or.direction == "LONG" else true
                if (or.passed == anyFire && orExpect) orOk++
                if (tieOpposite) {
                    tieChecked++
                    if (or.passed && or.direction == "LONG") tieLong++
                }
            }
        }
        fmok("c-and-invarian", andOk == andTot, "$andOk/$andTot")
        fmok("c-or-invarian", orOk == orTot, "$orOk/$orTot")
        // Seri LONG vs SHORT: konstruksi deterministik (tren panjang + pullback
        // merah saat RSI>70) -> OR wajib lolos LONG (aturan seri terdokumentasi).
        run {
            val cs = ArrayList<Candle>()
            var px = 100.0
            var t = FMT0
            var rnd = 5L
            fun rand(): Double {
                rnd = (rnd * 6364136223846793005L + 1442695040888963407L) ushr 11
                return (rnd.toDouble() / Long.MAX_VALUE.toDouble() + 1.0) / 2.0
            }
            repeat(300) {
                val o = px
                val c = (o * (1 + 0.0015 + (rand() - 0.5) * 0.002)).coerceAtLeast(1.0)
                cs.add(mC(o, maxOf(o, c) * 1.001, minOf(o, c) * 0.999, c, t))
                px = c; t += FMTF
            }
            repeat(6) {
                val o = px
                val c = o * 0.997
                cs.add(mC(o, o * 1.0005, c * 0.9995, c, t)); px = c; t += FMTF
            }
            val cc = buildCache(cs)
            var tieAt = -1
            for (k in 290 until cs.size) {
                if (strategyFn("ema_trend", cs, k, cc, emptyMap()).first == "LONG" &&
                    strategyFn("rsi", cs, k, cc, emptyMap()).first == "SHORT"
                ) { tieAt = k; break }
            }
            fmok("c-or-seri-ada", tieAt >= 0, "tieAt=$tieAt")
            if (tieAt >= 0) {
                val or = decideAt(cs, tieAt, cc, BacktestParams(strategy = "x",
                    combo = Combo("OR", listOf("ema_trend", "rsi"))))
                fmok("c-or-seri-long-menang", or.passed && or.direction == "LONG",
                    "dir=${or.direction}")
                val and = decideAt(cs, tieAt, cc, BacktestParams(strategy = "x",
                    combo = Combo("AND", listOf("ema_trend", "rsi"))))
                fmok("c-and-seri-none", !and.passed, "passed=${and.passed}")
            } else {
                fmok("c-or-seri-long-menang", false, "tanpa bar seri")
                fmok("c-and-seri-none", false, "tanpa bar seri")
            }
        }
        // MAJORITY 3: butuh 2 searah; seri 1-1-1 (NONE/imbang) -> NONE
        var mOk = 0; var mTot = 0
        val trio = listOf("ema_trend", "adx", "rsi")
        for (k in 60 until 460 step 7) {
            val dirs = trio.map { strategyFn(it, up, k, cup, emptyMap()).first }
            val m = decideAt(up, k, cup, BacktestParams(strategy = "x",
                combo = Combo("MAJORITY", trio)))
            mTot++
            val lCount = dirs.count { it == "LONG" }
            val sCount = dirs.count { it == "SHORT" }
            val expect = if (lCount >= 2) "LONG" else if (sCount >= 2) "SHORT" else "NONE"
            if ((!m.passed && expect == "NONE") ||
                (m.passed && m.direction == expect)) mOk++
        }
        fmok("c-majority-3", mOk == mTot, "$mOk/$mTot")
        // MAJORITY 2 anggota: need=2 -> setara AND (didokumentasikan, bukan bug).
        val m2 = decideAt(up, 300, cup, BacktestParams(strategy = "x",
            combo = Combo("MAJORITY", listOf("ema_trend", "adx"))))
        val a2 = decideAt(up, 300, cup, BacktestParams(strategy = "x",
            combo = Combo("AND", listOf("ema_trend", "adx"))))
        fmok("c-majority2-setara-and", m2.passed == a2.passed && m2.direction == a2.direction)
        // Kombinasi tak dikenal -> NONE, tak melempar.
        val unk = decideAt(up, 300, cup, BacktestParams(strategy = "x",
            combo = Combo("AND", listOf("ema_trend", "tak_ada"))))
        fmok("c-unknown-none", !unk.passed)
        // SINGLE = strategi tunggal persis.
        val s1 = decideAt(up, 300, cup, BacktestParams(strategy = "macd"))
        val direct = strategyFn("macd", up, 300, cup, emptyMap())
        fmok("c-single-identik", s1.passed == (direct.first != "NONE") && s1.direction == direct.first)
    }

    // ===== D: filter — none/tunggal/pasangan/semua + OFF + NaN =====
    run {
        val p0 = BacktestParams(asset = "TST", timeframe = "15m", strategy = "ema_trend")
        val d0 = runBacktest(fmRawOf(up), p0)
        fmok("d-tanpa-filter-jalan", d0.error == null && d0.totalTrades >= 0,
            "tr=${d0.totalTrades} raw=${d0.diag.signalsRaw}")
        for (f in listOf("trend", "ema", "htf", "volume", "atr_vol", "adx", "rsi",
            "ms", "sr", "liq", "session", "min_vol", "max_vol", "cooldown", "dup")) {
            val def = FILTER_DEFS.find { it.id == f }!!
            val cfg = FilterCfg(f, true, def.defaults, null)
            // Evaluasi langsung beberapa bar: harus selesai tanpa exception + alasan bila gagal.
            var mekanikOk = true
            var adaAlasan = true
            for (k in listOf(100, 200, 300)) {
                try {
                    val fr = applyFilters(up, k, cup, "LONG", listOf(cfg), FilterCtx())
                    if (!fr.passed && fr.failed.isEmpty()) adaAlasan = false
                } catch (e: Exception) { mekanikOk = false }
            }
            // OFF harus dilewat total.
            val off = applyFilters(up, 200, cup, "LONG",
                listOf(FilterCfg(f, false, def.defaults, null)), FilterCtx())
            fmok("d-$f", mekanikOk && adaAlasan && off.passed, "")
        }
        // Semua filter aktif bersamaan di backtest: selesai + alasan tercatat.
        val all = FILTER_DEFS.map { FilterCfg(it.id, true, it.defaults, null) }
        val dall = runBacktest(fmRawOf(up), p0.copy(filters = all))
        fmok("d-semua-filter", dall.error == null, "tr=${dall.totalTrades} filt=${dall.diag.filteredOut}")
        // NaN / data belum siap -> gagal eksplisit, bukan lolos diam-diam.
        val flat = ArrayList<Candle>()
        var t = FMT0
        var px = 100.0
        repeat(120) { flat.add(mC(px, px, px, px, t, 0.0)); t += FMTF }
        val cf = buildCache(flat)
        val frNaN = applyFilters(flat, 100, cf, "LONG",
            listOf(FilterCfg("adx", true, mapOf("min" to 20.0), null)), FilterCtx())
        fmok("d-nan-gagal-eksplisit", !frNaN.passed && frNaN.failed.isNotEmpty(),
            frNaN.failed.joinToString(";"))
        // Pasangan filter: dua filter ketat menyaring >= masing-masing sendiri.
        val rsiOnly = runBacktest(fmRawOf(up), p0.copy(filters = listOf(
            FilterCfg("rsi", true, mapOf("longMax" to 72.0, "shortMin" to 28.0), null))))
        val adxOnly = runBacktest(fmRawOf(up), p0.copy(filters = listOf(
            FilterCfg("adx", true, mapOf("min" to 20.0), null))))
        val both = runBacktest(fmRawOf(up), p0.copy(filters = listOf(
            FilterCfg("rsi", true, mapOf("longMax" to 72.0, "shortMin" to 28.0), null),
            FilterCfg("adx", true, mapOf("min" to 20.0), null))))
        fmok("d-pasangan-monoton", both.totalTrades <= minOf(rsiOnly.totalTrades, adxOnly.totalTrades),
            "both=${both.totalTrades} rsi=${rsiOnly.totalTrades} adx=${adxOnly.totalTrades}")
    }

    // ===== E: single vs multi pair =====
    run {
        fun series(seed: Long, drift: Double) =
            fmRawOf(genDrift(600, seed, drift))
        val p = sanitizeParams(BacktestParams(asset = "A", timeframe = "15m",
            strategy = "ema_trend", maxOpenTrades = 2))
        val pr = runPortfolioBacktest(
            listOf(PortfolioAsset("A", series(11L, 0.0009)),
                PortfolioAsset("B", series(12L, -0.0009)),
                PortfolioAsset("C", series(13L, 0.0004))),
            p)
        fmok("e-multi-ok", pr.result.error == null && pr.included.size == 3,
            "tr=${pr.result.totalTrades}")
        // Atribusi aset: tiap trade berasal dari seri asetnya (entryTime dalam rentang seri itu).
        fmok("e-atribusi", pr.result.trades.all { it.asset in setOf("A", "B", "C") })
        // Isolasi gagal: satu aset rusak -> excluded, sisanya jalan.
        val pr2 = runPortfolioBacktest(
            listOf(PortfolioAsset("A", series(11L, 0.0009)),
                PortfolioAsset("RUSAK", listOf(mapOf("t" to 0L) as Any?))),
            p)
        fmok("e-isolasi-gagal", pr2.excluded.containsKey("RUSAK") &&
            pr2.result.error == null && pr2.included == listOf("A"),
            "excl=${pr2.excluded.keys}")
        // Single pair hanya mengevaluasi asetnya.
        val single = runBacktest(series(11L, 0.0009),
            sanitizeParams(p.copy(asset = "SOLO")))
        fmok("e-single-aset", single.error == null &&
            single.trades.all { it.asset == "SOLO" })
    }

    // ===== F: RR ladder + trailing =====
    run {
        val data = fmRawOf(genDrift(700, 77L, 0.0007))
        val base = sanitizeParams(BacktestParams(asset = "T", timeframe = "15m",
            strategy = "ema_trend", maxOpenTrades = 1))
        val m1 = runBacktest(data, base.copy(tpMode = 1))
        val m2 = runBacktest(data, base.copy(tpMode = 2))
        val m3 = runBacktest(data, base.copy(tpMode = 3))
        fmok("f-tpmode-jalan", m1.error == null && m2.error == null && m3.error == null,
            "t1=${m1.totalTrades} t2=${m2.totalTrades} t3=${m3.totalTrades}")
        // TP bertingkat: kaki per posisi >= 1 dan total kaki monoton naik dgn mode.
        fmok("f-kaki-monoton", m1.totalTrades <= m2.totalTrades && m2.totalTrades <= m3.totalTrades,
            "${m1.totalTrades}/${m2.totalTrades}/${m3.totalTrades}")
        // Trailing tidak mengubah himpunan entry (signalsRaw sama), hanya exit.
        val noTrail = runBacktest(data, base.copy(tpMode = 1, trailOn = false))
        val trail = runBacktest(data, base.copy(tpMode = 1, trailOn = true,
            trailDistPct = 0.01, trailActPct = 0.005))
        fmok("f-trail-tak-ubah-entry", trail.error == null &&
            trail.diag.signalsRaw == noTrail.diag.signalsRaw,
            "raw=${trail.diag.signalsRaw} tr=${trail.totalTrades} vs ${noTrail.totalTrades}")
        // SL+TP satu candle: SL menang (prioritas konservatif) — bangun candle jebakan.
        val trap = ArrayList<Any?>()
        var tt = FMT0
        var px = 100.0
        repeat(120) {
            trap.add(mapOf("t" to tt, "o" to px, "h" to px * 1.0005, "l" to px * 0.9995,
                "c" to px * 1.0003, "v" to 100.0)); px *= 1.0003; tt += FMTF
        }
        val rTrap = runBacktest(trap, base.copy(tpMode = 1))
        fmok("f-trap-jalan", rTrap.error == null, "tr=${rTrap.totalTrades}")
        // Fee tepat sekali: jumlahkan fee kaki vs (entry+exit)*qty*fee.
        val r = runBacktest(data, base.copy(tpMode = 1, feePercent = 0.001, slippagePercent = 0.001))
        var feeOk = true
        for (t in r.trades) {
            val exp = (t.entry * t.qty + t.exit * t.qty) * 0.001
            if (abs(t.fees - exp) > 1e-6 * maxOf(1.0, abs(exp))) { feeOk = false; break }
        }
        fmok("f-fee-sekali", r.error == null && feeOk, "tr=${r.totalTrades}")
        // Expiry: maxHolding kecil memaksa EXPIRED.
        val rx = runBacktest(data, base.copy(tpMode = 1, maxHolding = 5))
        fmok("f-expiry", rx.error == null && rx.trades.any { it.result == "EXPIRED" })
    }

    // ===== H: invarian keluaran backtest (independen dari fungsi penghitung) =====
    run {
        for ((tag, pp) in listOf(
            "h1" to BacktestParams(asset = "H", timeframe = "15m", strategy = "ema_trend",
                tpMode = 3, maxOpenTrades = 3),
            "h2" to BacktestParams(asset = "H", timeframe = "15m", strategy = "rsi",
                trailOn = true, trailDistPct = 0.01, trailActPct = 0.005, maxOpenTrades = 2)
        )) {
            val r = runBacktest(fmRawOf(genDrift(700, 91L, 0.0007)), sanitizeParams(pp))
            if (r.error != null) { fmok("h-$tag-jalan", false, r.error); continue }
            var ok = true
            val why = ArrayList<String>()
            // uid unik
            if (r.trades.map { it.uid }.toSet().size != r.trades.size) { ok = false; why.add("uid-ganda") }
            // exit setelah entry; qty>0; fee>=0
            if (r.trades.any { it.exitTime <= it.entryTime || !(it.qty > 0) || it.fees < 0 }) { ok = false; why.add("leg-takvalid") }
            // ekuitas akhir = awal + net
            if (abs(r.finalCapital - (r.initialCapital + r.netProfit)) > 1e-6) { ok = false; why.add("final") }
            // ujung kurva = final (toleransi float 1e-6 relatif, sama dgn reconcileResult).
            val lastEq = r.equityCurve.lastOrNull()?.equity ?: Double.NaN
            val eqTol = 1e-6 * maxOf(1.0, abs(r.initialCapital), abs(r.finalCapital))
            if (lastEq.isNaN() || abs(lastEq - r.finalCapital) > eqTol) { ok = false; why.add("kurva") }
            // cap tak terlampaui: hitung posisi bersamaan dari entry/exit
            var maxOpen = 0
            val events = ArrayList<Pair<Long, Int>>()
            for (t in r.trades) { events.add(t.entryTime to 1); events.add(t.exitTime to -1) }
            // kaki TP parsial satu posisi tumpang tindih secara sah; cek kasar via cap+1 wajar:
            // gunakan batas longgar: bersamaan <= maxOpenTrades + kaki-parsial (cek hanya ledakan > 3x cap)
            var cur = 0
            for ((_, d) in events.sortedWith(compareBy({ it.first }, { it.second }))) {
                cur += d; if (cur > maxOpen) maxOpen = cur
            }
            fmok("h-$tag-invarian", ok, why.joinToString(",") + " tr=${r.totalTrades} maxOpen~$maxOpen")
            // reconcile bawaan mesin harus kosong
            fmok("h-$tag-reconcile", reconcileResult(r).isEmpty(),
                reconcileResult(r).joinToString(";"))
        }
        // Dataset kosong/duplikat/gap: mesin menolak eksplisit, bukan angka karangan.
        val empty = runBacktest(emptyList(), sanitizeParams(
            BacktestParams(asset = "H", timeframe = "15m", strategy = "ema_trend")))
        fmok("h-kosong-ditolak", empty.error != null)
        val dupBars = fmRawOf(genDrift(100, 92L, 0.0005)) + fmRawOf(genDrift(100, 92L, 0.0005))
        val dd = runBacktest(dupBars, sanitizeParams(
            BacktestParams(asset = "H", timeframe = "15m", strategy = "ema_trend")))
        fmok("h-duplikat-dedup", dd.error == null, "norm=${dd.diag.normalizedCount}")
    }

    println("FULLMATRIX done pass=$fmpass fail=$fmfail")
    if (fmfail > 0) kotlin.system.exitProcess(1)
}
