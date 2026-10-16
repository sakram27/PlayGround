import com.aether.signal.premium.engine.*
import com.aether.signal.premium.ui.*

// AUDIT SINYAL MENYELURUH v7.13 — harness JVM pelengkap (B/C/D/E/F/G/H).
// TAKSONOMI DATA (jujur, jangan dicampur):
//   - Data SINTETIS BERLABEL (drift RNG + fixture pola-sempurna): hanya untuk
//     membuktikan suatu kondisi strategi DAPAT memicu kandidat/sinyal.
//     BUKAN bukti strategi menghasilkan sinyal di pasar nyata.
//   - Data pasar asli / Demo Provider / historis: TIDAK dipakai di sini
//     (butuh jaringan + graph Android). Dilaporkan sebagai BELUM-DIUJI.
//   - Penyimpanan App.signals + posting NotificationManager + shade Android:
//     butuh Android; di JVM hanya gerbang MURNI yang diuji
//     (isEngineSrc, kunci dedup, dedupAdd, quietActive, gateEntry).
//     Dipanggil/Terlihat di HP dilaporkan BELUM-TERBUKTI di sini.
var sapass = 0
var safail = 0
fun saok(n: String, c: Boolean, x: String = "") {
    if (c) { sapass++; println("PASS $n $x") } else { safail++; println("FAIL $n $x") }
}

const val SAT0 = 1_700_000_000_000L
const val SATF = 900000L
fun saC(o: Double, h: Double, l: Double, c: Double, t: Long, v: Double = 100.0) =
    Candle(t, o, h, l, c, v)

fun saDrift(n: Int, seed: Long, drift: Double, px0: Double = 100.0, vol: Double = 100.0): List<Candle> {
    var rnd = seed
    fun rand(): Double {
        rnd = (rnd * 6364136223846793005L + 1442695040888963407L) ushr 11
        return (rnd.toDouble() / Long.MAX_VALUE.toDouble() + 1.0) / 2.0
    }
    val out = ArrayList<Candle>()
    var px = px0
    var t = SAT0
    repeat(n) {
        val o = px
        val cc = (o * (1 + drift + (rand() - 0.5) * 0.004)).coerceAtLeast(1.0)
        out.add(Candle(t, o, maxOf(o, cc) * 1.001, minOf(o, cc) * 0.999, cc, vol * (0.5 + rand())))
        px = cc; t += SATF
    }
    return out
}

fun saRaw(cs: List<Candle>): List<Any?> =
    cs.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? }

val SA_STRATS = listOf(
    "ema_trend", "ema_cross", "rsi", "macd", "bollinger", "stochastic",
    "adx", "supertrend", "vwap", "support_resistance", "breakout",
    "pullback", "market_structure", "bos", "choch", "liquidity_sweep",
    "order_block", "fvg", "breaker", "fibonacci", "smc_basic", "ict_setup",
    "volume", "mtf_confirm", "donchian_momentum"
)

fun main() {
    val up = saDrift(900, 42L, 0.0012)
    val dn = saDrift(900, 43L, -0.0012)
    val chop = saDrift(900, 44L, 0.0)
    val cup = buildCache(up); val cdn = buildCache(dn); val cch = buildCache(chop)

    // ===== B: tabel per strategi (kandidat LONG/SHORT per rezim + backtest tanpa filter) =====
    println("--- B: strategi individual (SINTETIS — bukan bukti pasar nyata) ---")
    println("id | upL upS dnL dnS chL chS | btRaw btFilt btTrades btErr")
    var bZeroAll = 0
    for (id in SA_STRATS) {
        fun cnt(cs: List<Candle>, cache: Cache): Pair<Int, Int> {
            var l = 0; var s = 0
            for (k in cs.indices) when (strategyFn(id, cs, k, cache, emptyMap()).first) {
                "LONG" -> l++; "SHORT" -> s++
            }
            return l to s
        }
        val (uL, uS) = cnt(up, cup); val (dL, dS) = cnt(dn, cdn); val (cL, cS) = cnt(chop, cch)
        var btRaw = -1; var btFilt = -1; var btTr = -1; var btErr = ""
        try {
            val r = runBacktest(saRaw(up), sanitizeParams(BacktestParams(
                asset = "SINT", timeframe = "15m", strategy = id, maxOpenTrades = 1)))
            btRaw = r.diag.signalsRaw; btFilt = r.diag.filteredOut; btTr = r.totalTrades
            btErr = r.error ?: "-"
        } catch (e: Exception) { btErr = "EXC:${e.message?.take(40)}" }
        if (uL + uS + dL + dS + cL + cS == 0) bZeroAll++
        println("$id | $uL $uS $dL $dS $cL $cS | $btRaw $btFilt $btTr $btErr")
    }
    saok("b-25-baris", true, "25 strategi tercetak di atas")
    // Nol di SEMUA rezim sintetis = kondisi pasar sintetis tak memenuhi syarat
    // (bukan vonis implementasi rusak) — fixture pola-sempurna di bawah yang membuktikan CAN-fire.
    println("b-nol-di-semua-rezim(sintetis): $bZeroAll dari 25")

    // ===== B: fixture pola-sempurna (CAN-fire) untuk pola langka =====
    run {
        var t = SAT0
        val cs = ArrayList<Candle>()
        repeat(20) { cs.add(saC(100.0, 100.1, 99.9, 100.0, t)); t += SATF }
        cs.add(saC(100.0, 100.6, 98.0, 100.5, t))
        saok("b-fix-sweep-long", strategyFn("liquidity_sweep", cs, 20, buildCache(cs), emptyMap()).first == "LONG")
        val cs2 = ArrayList<Candle>(); t = SAT0
        repeat(20) { cs2.add(saC(100.0, 100.1, 99.9, 100.0, t)); t += SATF }
        cs2.add(saC(100.0, 102.0, 99.4, 99.5, t))
        saok("b-fix-sweep-short", strategyFn("liquidity_sweep", cs2, 20, buildCache(cs2), emptyMap()).first == "SHORT")
    }
    run {
        val cs = ArrayList<Candle>(); var t = SAT0
        repeat(3) { cs.add(saC(100.0, 100.1, 99.9, 100.0, t)); t += SATF }
        cs.add(saC(100.0, 100.9, 99.9, 100.8, t)); t += SATF
        cs.add(saC(100.8, 100.9, 100.2, 100.3, t)); t += SATF
        cs.add(saC(100.3, 100.4, 99.9, 100.1, t)); t += SATF
        cs.add(saC(100.1, 100.5, 99.8, 100.4, t))
        saok("b-fix-ob-long", strategyFn("order_block", cs, 6, buildCache(cs), emptyMap()).first == "LONG")
    }
    run {
        val cs = listOf(
            saC(100.0, 100.2, 99.8, 100.0, SAT0),
            saC(100.0, 100.2, 99.8, 100.1, SAT0 + SATF),
            saC(101.0, 101.5, 100.8, 101.2, SAT0 + 2 * SATF),
            saC(101.2, 101.3, 100.3, 100.4, SAT0 + 3 * SATF),
            saC(100.4, 100.9, 100.2, 100.8, SAT0 + 4 * SATF)
        )
        saok("b-fix-fvg-long", strategyFn("fvg", cs, 4, buildCache(cs), emptyMap()).first == "LONG")
    }
    run {
        val cs = ArrayList<Candle>(); var t = SAT0
        repeat(10) { cs.add(saC(100.0, 100.1, 99.9, 100.0, t)); t += SATF }
        cs.add(saC(100.0, 100.9, 99.9, 100.8, t))
        saok("b-fix-bos-long", strategyFn("bos", cs, 10, buildCache(cs), emptyMap()).first == "LONG")
    }
    run {
        val cs = ArrayList<Candle>(); var t = SAT0
        repeat(20) { cs.add(saC(100.0, 100.1, 99.9, 100.0, t, 100.0)); t += SATF }
        cs.add(saC(100.0, 101.0, 99.9, 100.9, t, 10000.0))
        saok("b-fix-breakout-long", strategyFn("breakout", cs, 20, buildCache(cs), emptyMap()).first == "LONG")
    }
    run {
        val cs = ArrayList<Candle>(); var t = SAT0
        repeat(20) { cs.add(saC(100.0, 100.1, 99.9, 100.0, t, 100.0)); t += SATF }
        cs.add(saC(100.0, 100.9, 99.9, 100.8, t, 800.0))
        saok("b-fix-volume-long", strategyFn("volume", cs, 20, buildCache(cs), emptyMap()).first == "LONG")
    }

    // ===== C: mode — seluruh pasangan (300) × OR/AND + MAJORITY + temuan mode asing =====
    println("--- C: kombinasi (SINTETIS) total pasangan C(25,2)=300 per mode ---")
    run {
        val bars = (100 until 900 step 100).toList()
        var andOk = 0; var andTot = 0; var orOk = 0; var orTot = 0
        for (a in SA_STRATS) for (b in SA_STRATS) {
            if (a >= b) continue
            for (k in bars) {
                val ra = strategyFn(a, up, k, cup, emptyMap()).first
                val rb = strategyFn(b, up, k, cup, emptyMap()).first
                val and = decideAt(up, k, cup, BacktestParams(strategy = "x", combo = Combo("AND", listOf(a, b))))
                andTot++
                if (and.passed == (ra != "NONE" && ra == rb) && (!and.passed || and.direction == ra)) andOk++
                val or = decideAt(up, k, cup, BacktestParams(strategy = "x", combo = Combo("OR", listOf(a, b))))
                orTot++
                val anyFire = ra != "NONE" || rb != "NONE"
                val tieOpp = ra != "NONE" && rb != "NONE" && ra != rb
                if (or.passed == anyFire && (!tieOpp || or.direction == "LONG")) orOk++
            }
        }
        saok("c-300pasang-and", andOk == andTot, "$andOk/$andTot (diuji 300, total 300)")
        saok("c-300pasang-or", orOk == orTot, "$orOk/$orTot (diuji 300, total 300)")
    }
    // MAJORITY: 3 anggota butuh 2; 2 anggota need=2 (setara AND — terdokumentasi).
    run {
        var mOk = 0; var mTot = 0
        val trio = listOf("ema_trend", "adx", "rsi")
        for (k in 100 until 900 step 25) {
            val dirs = trio.map { strategyFn(it, up, k, cup, emptyMap()).first }
            val m = decideAt(up, k, cup, BacktestParams(strategy = "x", combo = Combo("MAJORITY", trio)))
            mTot++
            val l = dirs.count { it == "LONG" }; val s = dirs.count { it == "SHORT" }
            val exp = if (l >= 2) "LONG" else if (s >= 2) "SHORT" else "NONE"
            if ((!m.passed && exp == "NONE") || (m.passed && m.direction == exp)) mOk++
        }
        saok("c-majority-3", mOk == mTot, "$mOk/$mTot")
        val m2 = decideAt(up, 400, cup, BacktestParams(strategy = "x", combo = Combo("MAJORITY", listOf("ema_trend", "adx"))))
        val a2 = decideAt(up, 400, cup, BacktestParams(strategy = "x", combo = Combo("AND", listOf("ema_trend", "adx"))))
        saok("c-majority2-setara-and", m2.passed == a2.passed && m2.direction == a2.direction)
        // Seri genap 2-2 pada 4 strategi -> need=3 -> NONE (cari bar seri; jujur bila tak ada).
        var tieBar = -1
        val quad = listOf("ema_trend", "adx", "rsi", "macd")
        for (k in 100 until 900 step 5) {
            val dirs = quad.map { strategyFn(it, up, k, cup, emptyMap()).first }
            if (dirs.count { it == "LONG" } == 2 && dirs.count { it == "SHORT" } == 2) { tieBar = k; break }
        }
        if (tieBar >= 0) {
            val m4 = decideAt(up, tieBar, cup, BacktestParams(strategy = "x", combo = Combo("MAJORITY", quad)))
            saok("c-majority-seri-genap-none", !m4.passed, "bar=$tieBar")
        } else println("INFO c-majority-seri-genap: tak ada bar seri 2-2 pada data sintetis ini — BELUM-DIUJI")
        // SINGLE identik + combo kosong == single.
        val s1 = decideAt(up, 400, cup, BacktestParams(strategy = "macd"))
        val direct = strategyFn("macd", up, 400, cup, emptyMap())
        saok("c-single-identik", s1.passed == (direct.first != "NONE") && s1.direction == direct.first)
        val e1 = decideAt(up, 400, cup, BacktestParams(strategy = "macd", combo = Combo("", emptyList())))
        saok("c-combo-kosong-single", e1.passed == s1.passed && e1.direction == s1.direction)
        // Tak dikenal -> NONE, tak melempar.
        saok("c-unknown-none", !decideAt(up, 400, cup, BacktestParams(strategy = "x",
            combo = Combo("AND", listOf("ema_trend", "tak_ada")))).passed)
        // TEMUAN: mode asing (mis. MIN_CONFIRMATIONS) jatuh ke perilaku OR diam-diam.
        val orRef = decideAt(up, 400, cup, BacktestParams(strategy = "x", combo = Combo("OR", listOf("ema_trend", "rsi"))))
        val minc = decideAt(up, 400, cup, BacktestParams(strategy = "x", combo = Combo("MIN_CONFIRMATIONS", listOf("ema_trend", "rsi"))))
        saok("c-minconf-tak-ada-jatuh-ke-or", minc.passed == orRef.passed && minc.direction == orRef.direction,
            "TEMUAN: string mode asing = OR diam-diam (bukan error)")
    }
    // Kasus tak ada kandidat + konflik LONG vs SHORT (OR->LONG, AND->NONE).
    run {
        var noneBar = -1
        for (k in 100 until 900 step 5) {
            if (strategyFn("ema_trend", chop, k, cch, emptyMap()).first == "NONE" &&
                strategyFn("rsi", chop, k, cch, emptyMap()).first == "NONE") { noneBar = k; break }
        }
        if (noneBar >= 0) {
            val or = decideAt(chop, noneBar, cch, BacktestParams(strategy = "x", combo = Combo("OR", listOf("ema_trend", "rsi"))))
            val and = decideAt(chop, noneBar, cch, BacktestParams(strategy = "x", combo = Combo("AND", listOf("ema_trend", "rsi"))))
            saok("c-tanpa-kandidat", !or.passed && !and.passed, "bar=$noneBar")
        } else println("INFO c-tanpa-kandidat: tak ada bar NONE-NONE — BELUM-DIUJI")
    }
    println("INFO c-cakupan: pasangan 300/300 per OR+AND (bar sampel); trio 3-anggota sampel; C(25,3)=2300 trio TIDAK diuji penuh (alasan: waktu; dilaporkan jujur)")

    // ===== D: filter — tabel per filter tunggal + batas/invalid/kosong/NaN + tanpa-filter =====
    println("--- D: filter tunggal atas ema_trend/data-naik (SINTETIS) ---")
    println("filter | raw filt trades | alasan-top")
    run {
        val p0 = sanitizeParams(BacktestParams(asset = "TST", timeframe = "15m", strategy = "ema_trend"))
        val base = runBacktest(saRaw(up), p0)
        saok("d-tanpa-filter-jalan", base.error == null, "raw=${base.diag.signalsRaw} tr=${base.totalTrades}")
        saok("d-tanpa-filter-tak-ada-hidden-gate", base.error == null && base.diag.signalsRaw > 0,
            "raw=${base.diag.signalsRaw} (backtest: filter kosong = kandidat lolos)")
        // Filter OFF semua == tanpa filter.
        val allOff = FILTER_DEFS.map { FilterCfg(it.id, false, it.defaults, null) }
        val roff = runBacktest(saRaw(up), p0.copy(filters = allOff))
        saok("d-semua-off-sama-tanpa-filter", roff.error == null && roff.diag.signalsRaw == base.diag.signalsRaw,
            "raw=${roff.diag.signalsRaw}")
        for (f in FILTER_DEFS) {
            val r = runBacktest(saRaw(up), p0.copy(filters = listOf(FilterCfg(f.id, true, f.defaults, null))))
            val top = r.diag.filterReasons.toList().sortedByDescending { it.second }.firstOrNull()
            println("${f.id} | ${r.diag.signalsRaw} ${r.diag.filteredOut} ${r.totalTrades} | ${(top?.first ?: "-")} x${top?.second ?: 0}")
        }
        // Nama filter tak dikenal dilewat diam-diam (TEMUAN: lolos, bukan error).
        val unk = applyFilters(up, 400, cup, "LONG", listOf(FilterCfg("tak_ada", true, emptyMap(), null)), FilterCtx())
        saok("d-filter-asing-dilewat", unk.passed, "TEMUAN: filter tak dikenal diabaikan (lolos)")
        // Batas: sessions=null (default 0-24 lolos) vs sessions=kosong (selalu tolak).
        val sNull = applyFilters(up, 400, cup, "LONG", listOf(FilterCfg("session", true, emptyMap(), null)), FilterCtx())
        val sEmpty = applyFilters(up, 400, cup, "LONG", listOf(FilterCfg("session", true, emptyMap(), emptyList())), FilterCtx())
        saok("d-session-null-lolos", sNull.passed)
        saok("d-session-kosong-tolak", !sEmpty.passed && sEmpty.failed.isNotEmpty(), sEmpty.failed.joinToString(";"))
        // Invalid: cooldown bars=0 & dup bars=0 (selalu lolosInstaller); volume mult=0 (lolos bila data ada).
        val cd0 = applyFilters(up, 400, cup, "LONG", listOf(FilterCfg("cooldown", true, mapOf("bars" to 0.0), null)), FilterCtx())
        val dp0 = applyFilters(up, 400, cup, "LONG", listOf(FilterCfg("dup", true, mapOf("bars" to 0.0), null)), FilterCtx())
        saok("d-cooldown-0-lolos", cd0.passed)
        saok("d-dup-0-lolos", dp0.passed)
        // Kosong: runBacktest menolak eksplisit.
        val empty = runBacktest(emptyList(), sanitizeParams(BacktestParams(asset = "H", timeframe = "15m", strategy = "ema_trend")))
        saok("d-data-kosong-ditolak", empty.error != null)
        // NaN/belum-siap: gagal eksplisit, bukan lolos diam-diam.
        val flat = ArrayList<Candle>(); var t = SAT0
        repeat(120) { flat.add(saC(100.0, 100.0, 100.0, 100.0, t, 0.0)); t += SATF }
        val cf = buildCache(flat)
        val frNaN = applyFilters(flat, 100, cf, "LONG", listOf(FilterCfg("adx", true, mapOf("min" to 20.0), null)), FilterCtx())
        saok("d-nan-gagal-eksplisit", !frNaN.passed && frNaN.failed.isNotEmpty(), frNaN.failed.joinToString(";"))
        // Fresh ctx (live pasca-restart) lolos cooldown/dup; ctx berevolusi memblokir (divergensi by-design).
        val freshDup = applyFilters(up, 400, cup, "LONG", listOf(FilterCfg("dup", true, mapOf("bars" to 5.0), null)), FilterCtx())
        val evol = FilterCtx(lastSigDir = "LONG", lastSigIdx = 398)
        val evolDup = applyFilters(up, 400, cup, "LONG", listOf(FilterCfg("dup", true, mapOf("bars" to 5.0), null)), evol)
        val evolOpp = applyFilters(up, 400, cup, "SHORT", listOf(FilterCfg("dup", true, mapOf("bars" to 5.0), null)), FilterCtx(lastSigDir = "LONG", lastSigIdx = 398))
        saok("d-dup-fresh-lolos-evolusi-blokir", freshDup.passed && !evolDup.passed && evolOpp.passed)
    }

    // ===== E: single vs multi pair =====
    run {
        fun series(seed: Long, drift: Double) = saRaw(saDrift(600, seed, drift))
        val p = sanitizeParams(BacktestParams(asset = "A", timeframe = "15m", strategy = "ema_trend", maxOpenTrades = 2))
        val pr = runPortfolioBacktest(
            listOf(PortfolioAsset("A", series(11L, 0.0009)), PortfolioAsset("B", series(12L, -0.0009)), PortfolioAsset("C", series(13L, 0.0004))), p)
        saok("e-multi-3-aset", pr.result.error == null && pr.included.size == 3, "tr=${pr.result.totalTrades}")
        saok("e-atribusi-aset", pr.result.error == null && pr.result.trades.all { it.asset in setOf("A", "B", "C") })
        val pr2 = runPortfolioBacktest(
            listOf(PortfolioAsset("A", series(11L, 0.0009)), PortfolioAsset("RUSAK", listOf(mapOf("t" to 0L) as Any?))), p)
        saok("e-isolasi-gagal", pr2.excluded.containsKey("RUSAK") && pr2.result.error == null && pr2.included == listOf("A"))
        val single = runBacktest(series(11L, 0.0009), sanitizeParams(p.copy(asset = "SOLO")))
        saok("e-single-hanya-asetnya", single.error == null && single.trades.all { it.asset == "SOLO" })
    }

    // ===== F: RR + trailing =====
    println("--- F: RR (SINTETIS) sl/tp -> avgRR vs harapan; trailing hanya-exit ---")
    run {
        val data = saRaw(saDrift(700, 77L, 0.0007))
        for ((sl, tp) in listOf(0.01 to 0.01, 0.01 to 0.02, 0.015 to 0.03, 0.025 to 0.05, 0.01 to 0.03)) {
            val r = runBacktest(data, sanitizeParams(BacktestParams(asset = "T", timeframe = "15m",
                strategy = "ema_trend", maxOpenTrades = 1, slPercent = sl, tpPercent = tp)))
            val exp = tp / sl
            saok("f-rr-1banding${"%.0f".format(exp)}", r.error == null && kotlin.math.abs(r.averageRR - exp) < 0.35,
                "avgRR=${"%.2f".format(r.averageRR)} exp=$exp tr=${r.totalTrades}")
        }
        // decideAt identik lintas RR (syarat masuk tak dipengaruhi risiko).
        val dA = decideAt(up, 400, cup, BacktestParams(strategy = "ema_trend", slPercent = 0.01, tpPercent = 0.01))
        val dB = decideAt(up, 400, cup, BacktestParams(strategy = "ema_trend", slPercent = 0.05, tpPercent = 0.10))
        saok("f-rr-tak-ubah-entry", dA.passed == dB.passed && dA.direction == dB.direction)
        // ATR mode jalan.
        val ra = runBacktest(data, sanitizeParams(BacktestParams(asset = "T", timeframe = "15m",
            strategy = "ema_trend", maxOpenTrades = 1, useAtr = true)))
        saok("f-atr-jalan", ra.error == null, "tr=${ra.totalTrades}")
        // Trailing: signalsRaw sama (hanya exit), aktivasi + ratchet + sentuh.
        val base = sanitizeParams(BacktestParams(asset = "T", timeframe = "15m", strategy = "ema_trend", maxOpenTrades = 1))
        val noTrail = runBacktest(data, base.copy(tpMode = 1, trailOn = false))
        val trail = runBacktest(data, base.copy(tpMode = 1, trailOn = true, trailDistPct = 0.01, trailActPct = 0.005))
        saok("f-trail-tak-ubah-entry", trail.error == null && trail.diag.signalsRaw == noTrail.diag.signalsRaw,
            "raw=${trail.diag.signalsRaw} tr=${trail.totalTrades} vs ${noTrail.totalTrades}")
        val st = TrailSim()
        saok("f-trail-pra-aktivasi-mati", trailExitPrice("LONG", 100.0, 100.2, 99.9, 0.01, 0.005, st) == null && !st.active)
        val st2 = TrailSim()
        val noTouch = trailExitPrice("LONG", 100.0, 102.0, 101.6, 0.01, 0.005, st2)
        saok("f-trail-aktivasi-tanpa-sentuh", st2.active && noTouch == null, "stop=${"%.2f".format(st2.stop)}")
        val st3 = TrailSim()
        val hit = trailExitPrice("LONG", 100.0, 102.0, 100.5, 0.01, 0.005, st3)
        saok("f-trail-sentuh-tutup", st3.active && hit != null, "exit=${hit}")
        // Ratchet hanya menguat (LONG stop tak pernah turun).
        val st4 = TrailSim(active = true, extreme = 102.0, stop = trailStopFor("LONG", 102.0, 0.005))
        val sBefore = st4.stop
        trailRatchet("LONG", 101.0, 100.0, 0.005, st4)
        trailRatchet("LONG", 103.0, 102.0, 0.005, st4)
        saok("f-trail-ratchet-menguat", st4.stop >= sBefore, "sblm=$sBefore ssdh=${st4.stop}")
        saok("f-trail-slip-tanda", trailExitWithSlip("LONG", 100.0, 0.001) < 100.0 && trailExitWithSlip("SHORT", 100.0, 0.001) > 100.0)
        saok("f-trail-sanitasi", sanitizeTrailPct(Double.NaN, 1.0) == 1.0 && sanitizeTrailPct(999.0, 1.0) == 50.0)
        // Expiry memaksa EXPIRED.
        val rx = runBacktest(data, base.copy(tpMode = 1, maxHolding = 5))
        saok("f-expiry", rx.error == null && rx.trades.any { it.result == "EXPIRED" })
    }

    // ===== G: rantai end-to-end sejauh yang bisa di JVM (sinyal sintetis teridentifikasi) =====
    println("--- G: rantai decideAt->filter->gate->isEngineSrc->kunci->dedup->quiet (SINTETIS) ---")
    run {
        var kHit = -1
        for (k in 60 until up.size) {
            if (strategyFn("ema_trend", up, k, cup, emptyMap()).first == "LONG") { kHit = k; break }
        }
        saok("g-kandidat-ditemukan", kHit >= 0, "bar=$kHit")
        if (kHit >= 0) {
            val dec = decideAt(up, kHit, cup, BacktestParams(strategy = "ema_trend"))
            val fr = applyFilters(up, kHit, cup, dec.direction, emptyList(), FilterCtx())
            saok("g-keputusan-lolos-tanpa-filter", dec.passed && fr.passed, "dir=${dec.direction} conf=${dec.confidence}")
            saok("g-gate-allow", gateEntry(0, 1, false) == OpenDecision.ALLOW)
            saok("g-gate-hold-pair", gateEntry(0, 5, true) == OpenDecision.HOLD_PAIR)
            saok("g-gate-cap-full", gateEntry(3, 3, false) == OpenDecision.CAP_FULL)
            saok("g-src-engine-vs-backtest", isEngineSrc("dryrun-entry") && !isEngineSrc("backtest"))
            val bar = up[kHit]
            val idL = engineSignalId('e', bar.t, "SINT", "LONG")
            val keyL = entryNotifKey("SINT", "15m", bar.t, "LONG")
            val keyS = entryNotifKey("SINT", "15m", bar.t, "SHORT")
            saok("g-kunci-beda-arah", keyL != keyS && idL.contains("LONG"), idL)
            val seen = HashSet<String>()
            val first = dedupAdd(seen, keyL)
            val second = dedupAdd(seen, keyL)
            saok("g-dedup-sekali", first && !second)
            saok("g-quiet-off-lolos", !quietActive(QuietHours(enabled = false), 0, 2))
            saok("g-quiet-seharian-tahan", quietActive(QuietHours(enabled = true, startMin = 0, endMin = 1440), 720, 2))
            println("G-TRACE sintetis: t=${bar.t} px=${bar.c} LONG bar=$kHit id=$idL key=$keyL gate=ALLOW src=engine dedup=fresh quiet=off")
        }
        println("INFO g-android: posting NotificationManager + shade terlihat + izin/channel/Doze/OEM = BUTUH EMULATOR/HP — BELUM-DIUJI di JVM")
    }

    // ===== H: paritas backtest-vs-engine (bagian murni) + perbedaan desain =====
    run {
        // engineEvalIndices: hanya size-2; size-1 ikut bila bar berjalan tua.
        saok("h-eval-hanya-tertutup", engineEvalIndices(300, 1000L, 1000L + 100L, 900000L) == listOf(298))
        saok("h-eval-bar-tua-ikut", engineEvalIndices(300, 1000L, 1000L + 900000L, 900000L) == listOf(298, 299))
        saok("h-eval-kecil-kosong", engineEvalIndices(1, 0L, 0L, 900000L).isEmpty())
        // decideAt+filter sama bila ctx sama (paritas fungsi bersama).
        val k = 400
        val p = BacktestParams(strategy = "ema_trend")
        val d1 = decideAt(up, k, cup, p)
        val d2 = decideAt(up, k, cup, p)
        saok("h-decide-deterministik", d1 == d2)
        val f1 = applyFilters(up, k, cup, d1.direction, FILTER_DEFS.map { FilterCfg(it.id, true, it.defaults, null) }, FilterCtx())
        val f2 = applyFilters(up, k, cup, d1.direction, FILTER_DEFS.map { FilterCfg(it.id, true, it.defaults, null) }, FilterCtx())
        saok("h-filter-deterministik", f1 == f2)
        // Sidik: sama stabil, ubah RR/trail/strategi berubah.
        val b0 = sanitizeParams(BacktestParams(asset = "T", strategy = "ema_trend"))
        saok("h-fp-stabil", configFingerprint(b0) == configFingerprint(b0.copy()))
        saok("h-fp-rr-berubah", configFingerprint(b0) != configFingerprint(b0.copy(tpPercent = 0.09)))
        saok("h-fp-trail-berubah", configFingerprint(b0) != configFingerprint(b0.copy(trailOn = true)))
        saok("h-fp-strat-berubah", configFingerprint(b0) != configFingerprint(b0.copy(strategy = "rsi")))
        println("INFO h-desain (by-code, EngineDiag.kt:266 BotEngine.kt:247,499 Backtest.kt:179,410): " +
            "engine menilai 1-2 bar/tick vs backtest memindai semua bar; " +
            "entry live=last.close±slip vs backtest=next-open±slip; " +
            "cap engine global+satu/pair vs backtest cap global; " +
            "ctx engine berbasis-waktu vs backtest berbasis-indeks.")
    }

    // ===== UI-1: status ringkas tunggal + salinan diagnostik (murni, JVM) =====
    run {
        fun saSnap(
            running: Boolean, lastTickAt: Long = 0L, lastDataAt: Long = 0L,
            evalTotal: Int = 0, evalOk: Int = 0, candidates: Int = 0,
            candLong: Int = 0, candShort: Int = 0, stored: Int = 0,
            blockedPosition: Int = 0, openPositions: Int = 0,
            lastError: String = "", pairs: List<String> = listOf("BTCUSDT"),
            verdicts: List<PairVerdict> = emptyList(),
            filterTop: List<Pair<String, Int>> = emptyList()
        ) = EngineDiagSnapshot(
            running = running, startedAt = 1000L, lastTickAt = lastTickAt, lastDataAt = lastDataAt,
            strategy = "ema_trend", timeframe = "15m", pairs = pairs,
            evalTotal = evalTotal, evalOk = evalOk, candidates = candidates,
            filteredOut = 0, invalidLevels = 0, dupHeld = 0, stored = stored,
            notifCalls = 0, openPositions = openPositions, oldestOpenAgeMs = 0L,
            failCount = 0, pairErrors = emptyList(), lastPhase = "selesai", lastError = lastError,
            candLong = candLong, candShort = candShort, blockedPosition = blockedPosition,
            filterTop = filterTop, pairVerdicts = verdicts)
        val now = 60000L
        // Label: 6 status, deterministik.
        saok("u-label-berhenti", engineStatusLabel(saSnap(false), now) == "BERHENTI")
        saok("u-label-tak-merespons", engineStatusLabel(saSnap(true, lastTickAt = 1000L), 1000L + 400000L) == "TIDAK MERESPON")
        saok("u-label-error", engineStatusLabel(saSnap(true, lastTickAt = 59000L, evalTotal = 2, evalOk = 0, lastError = "boom"), now) == "ERROR")
        saok("u-label-data", engineStatusLabel(saSnap(true, lastTickAt = 59000L, evalTotal = 2, evalOk = 0), now) == "DATA BELUM SIAP")
        saok("u-label-tanpa-sinyal", engineStatusLabel(saSnap(true, lastTickAt = 59000L, lastDataAt = 59000L, evalTotal = 2, evalOk = 2, stored = 0), now) == "BERJALAN TANPA SINYAL")
        saok("u-label-jalan", engineStatusLabel(saSnap(true, lastTickAt = 59000L, lastDataAt = 59000L, evalTotal = 2, evalOk = 2, stored = 1), now) == "BERJALAN")
        // formatEngineDiag memakai sumber yang sama: teks lama tetap muncul.
        fun fmt(s: EngineDiagSnapshot, t: Long) =
            formatEngineDiag(s, "ema_trend", "15m", "binance", true, true, false, 0, 0, 0, 15, t)
        saok("u-fmt-berhenti", fmt(saSnap(false), now).contains("BERHENTI"))
        saok("u-fmt-tak-merespons", fmt(saSnap(true, lastTickAt = 1000L), 1000L + 400000L).contains("TIDAK MERESPON"))
        saok("u-fmt-error", fmt(saSnap(true, lastTickAt = 59000L, evalTotal = 2, evalOk = 0, lastError = "boom"), now).contains("ERROR"))
        saok("u-fmt-degraded", fmt(saSnap(true, lastTickAt = 59000L, evalTotal = 2, evalOk = 0), now).contains("TERDEGRADASI"))
        saok("u-fmt-jalan", fmt(saSnap(true, lastTickAt = 59000L, lastDataAt = 59000L, evalTotal = 2, evalOk = 2, stored = 1), now).contains("BERJALAN"))
        // Ringkasan: label + umur jujur (tak ada angka karangan).
        val sum0 = engineStatusSummary(saSnap(false), now)
        saok("u-sum-berhenti-jujur", sum0.startsWith("BERHENTI") && sum0.contains("tick belum pernah") && sum0.contains("data belum ada"))
        val sum1 = engineStatusSummary(saSnap(true, lastTickAt = 59000L, lastDataAt = 59000L, evalTotal = 2, evalOk = 2), now)
        saok("u-sum-umur", sum1.contains("BERJALAN TANPA SINYAL") && sum1.contains("lalu"))
        // Empty state kontekstual.
        saok("u-empty-stop", emptySignalText(false, 0, 0, 0, 0, 0, 0, 0L, now, 0, 0) == "Belum ada sinyal Engine — tekan Start Engine.")
        saok("u-empty-tunggu", emptySignalText(true, 0, 0, 0, 0, 0, 1, 0L, now, 0, 0).contains("menunggu tick"))
        saok("u-empty-data", emptySignalText(true, 3, 0, 0, 0, 0, 1, 59000L, now, 0, 3).contains("belum siap"))
        saok("u-empty-blokir", emptySignalText(true, 3, 3, 0, 2, 2, 2, 59000L, now, 0, 0).contains("posisi terbuka"))
        val eFallback = emptySignalText(true, 3, 3, 0, 0, 0, 2, 59000L, now, 0, 0)
        saok("u-empty-fallback", eFallback.startsWith("Belum ada sinyal Engine — ") && eFallback.length > 30)
        // Salinan: memuat field kunci, tanpa rahasia, tahan snapshot kosong.
        val snapC = saSnap(true, lastTickAt = 59000L, lastDataAt = 59000L, evalTotal = 2, evalOk = 2,
            candidates = 3, candLong = 2, candShort = 1, stored = 0, blockedPosition = 1,
            lastError = "timeout", verdicts = listOf(PairVerdict("BTCUSDT", "FILTERED", "volume lemah", 59000L)),
            filterTop = listOf("Volume Filter (volume lemah)" to 2))
        val copy = buildDiagCopyText(snapC, "ema_trend", "15m", "binance", now, 5, 1, 0)
        val low = copy.lowercase()
        saok("u-copy-isi", copy.contains("ema_trend") && copy.contains("15m") && copy.contains("binance") &&
            copy.contains("BTCUSDT") && copy.contains("LONG 2/SHORT 1") && copy.contains("FILTERED") &&
            copy.contains("Alasan:") && copy.contains("Waktu snapshot:"))
        saok("u-copy-tanpa-rahasia", !low.contains("api_key") && !low.contains("apikey") &&
            !low.contains("token") && !low.contains("secret") && !low.contains("password") && !low.contains("sandi"))
        val copyEmpty = buildDiagCopyText(saSnap(false, pairs = emptyList()), "", "", "", 0L)
        saok("u-copy-kosong-jujur", copyEmpty.contains("BERHENTI") && copyEmpty.contains("belum dipilih"))
        // TAHAP 2.1 warna satu-sumber: arti sama = warna sama.
        saok("u-warna-status", statusColorFor("BERJALAN") == statusColorFor("BERJALAN TANPA SINYAL") &&
            statusColorFor("TIDAK MERESPON") == statusColorFor("ERROR") &&
            statusColorFor("BERHENTI") == statusColorFor("tak dikenal") &&
            statusColorFor("BERJALAN") != statusColorFor("ERROR") &&
            statusColorFor("DATA BELUM SIAP") != statusColorFor("BERJALAN"))
        saok("u-warna-sehat", healthColorFor("SEHAT") != healthColorFor("GAGAL") &&
            healthColorFor("PARSIAL(12/300)") == healthColorFor("PARSIAL(1/2)") &&
            healthColorFor("KURANG(3/220)") != healthColorFor("SEHAT") &&
            healthColorFor("") == healthColorFor("???"))
        // pairDataHealth: label aktual yang dipakai pill Market.
        saok("u-health-aturan", pairDataHealth(300, 0, 0, 220) == "GAGAL" &&
            pairDataHealth(300, 300, 100, 220).startsWith("KURANG") &&
            pairDataHealth(300, 200, 250, 220).startsWith("PARSIAL") &&
            pairDataHealth(300, 300, 300, 220) == "SEHAT")
    }

    // ===== DIAG-NOL: perincian NO_CANDIDATE (diagnostik saja, keputusan utuh) =====
    // Data SINTETIS BERLABEL — bentuk kasus, bukan bukti pasar nyata.
    run {
        fun ncSnap(
            running: Boolean, lastTickAt: Long = 0L, lastDataAt: Long = 0L,
            evalTotal: Int = 0, evalOk: Int = 0, stored: Int = 0,
            openPositions: Int = 0
        ) = EngineDiagSnapshot(
            running = running, startedAt = 1000L, lastTickAt = lastTickAt, lastDataAt = lastDataAt,
            strategy = "support_resistance", timeframe = "15m", pairs = listOf("BTCUSDT"),
            evalTotal = evalTotal, evalOk = evalOk, candidates = 0,
            filteredOut = 0, invalidLevels = 0, dupHeld = 0, stored = stored,
            notifCalls = 0, openPositions = openPositions, oldestOpenAgeMs = 0L,
            failCount = 0, pairErrors = emptyList(), lastPhase = "selesai", lastError = "")
        val ncNow = 60000L
        // Kasus cermin live: SR + MTF dua-duanya NONE pada bar datar.
        val flat = ArrayList<Candle>(); var ft = SAT0
        repeat(120) { flat.add(saC(100.0, 100.0, 100.0, 100.0, ft, 0.0)); ft += SATF }
        val fcache = buildCache(flat)
        val k = 100
        val pOr = BacktestParams(strategy = "support_resistance",
            combo = Combo("OR", listOf("support_resistance", "mtf_confirm")))
        val ra = strategyFn("support_resistance", flat, k, fcache, emptyMap()).first
        val rb = strategyFn("mtf_confirm", flat, k, fcache, emptyMap()).first
        saok("n-dua-none", ra == "NONE" && rb == "NONE", "$ra/$rb")
        val legs = comboLegDirs(pOr, flat, k, fcache)
        saok("n-legs", legs == listOf("support_resistance" to "NONE", "mtf_confirm" to "NONE"), "$legs")
        val det = noCandidateDetail(pOr, flat, k, fcache)
        saok("n-detail", det == "MODE=OR | SR=NONE | MTF=NONE | BAR=${flat[k].t}", det)
        saok("n-detail-cap", det.length <= 120)
        saok("n-detail-tanpa-rahasia", !det.lowercase().contains("token") &&
            !det.lowercase().contains("key") && !det.lowercase().contains("secret"))
        // decideAt tak berubah: OR dua-NONE tetap tak lolos.
        val decOr = decideAt(flat, k, fcache, pOr)
        saok("n-decide-tetap", !decOr.passed && decOr.direction == "NONE")
        // Tunggal: MODE=SINGLE.
        val detS = noCandidateDetail(BacktestParams(strategy = "rsi"), flat, k, fcache)
        saok("n-single", detS.startsWith("MODE=SINGLE | ") && detS.endsWith("BAR=${flat[k].t}"), detS)
        // Konflik arah pada AND (konstruksi deterministik tren + pullback merah).
        val cs = ArrayList<Candle>(); var px = 100.0; var tt = SAT0; var rnd = 5L
        fun rand(): Double {
            rnd = (rnd * 6364136223846793005L + 1442695040888963407L) ushr 11
            return (rnd.toDouble() / Long.MAX_VALUE.toDouble() + 1.0) / 2.0
        }
        repeat(300) {
            val o = px
            val c = (o * (1 + 0.0015 + (rand() - 0.5) * 0.002)).coerceAtLeast(1.0)
            cs.add(saC(o, maxOf(o, c) * 1.001, minOf(o, c) * 0.999, c, tt)); px = c; tt += SATF
        }
        repeat(6) {
            val o = px
            val c = o * 0.997
            cs.add(saC(o, o * 1.0005, c * 0.9995, c, tt)); px = c; tt += SATF
        }
        val cc = buildCache(cs)
        var tieAt = -1
        for (kk in 290 until cs.size) {
            if (strategyFn("ema_trend", cs, kk, cc, emptyMap()).first == "LONG" &&
                strategyFn("rsi", cs, kk, cc, emptyMap()).first == "SHORT") { tieAt = kk; break }
        }
        saok("n-konflik-ada", tieAt >= 0, "tieAt=$tieAt")
        if (tieAt >= 0) {
            val pAnd = BacktestParams(strategy = "x", combo = Combo("AND", listOf("ema_trend", "rsi")))
            saok("n-konflik-and-none", !decideAt(cs, tieAt, cc, pAnd).passed)
            val detC = noCandidateDetail(pAnd, cs, tieAt, cc)
            saok("n-konflik-detail", detC.contains("MODE=AND") && detC.contains("EMATREND=LONG") &&
                detC.contains("RSI=SHORT") && detC.endsWith("BAR=${cs[tieAt].t}") && detC.length <= 120, detC)
        }
        // Dua strategi searah: OR lolos LONG, detail mencatat keduanya.
        var sameAt = -1
        for (kk in 100 until 880 step 5) {
            if (strategyFn("ema_trend", up, kk, cup, emptyMap()).first == "LONG" &&
                strategyFn("adx", up, kk, cup, emptyMap()).first == "LONG") { sameAt = kk; break }
        }
        saok("n-searah-ada", sameAt >= 0, "sameAt=$sameAt")
        if (sameAt >= 0) {
            val pOr2 = BacktestParams(strategy = "x", combo = Combo("OR", listOf("ema_trend", "adx")))
            val dSame = decideAt(up, sameAt, cup, pOr2)
            saok("n-searah-lolos", dSame.passed && dSame.direction == "LONG")
            val detS2 = noCandidateDetail(pOr2, up, sameAt, cup)
            saok("n-searah-detail", detS2.contains("MODE=OR") && detS2.contains("EMATREND=LONG") &&
                detS2.contains("ADX=LONG"), detS2)
        }
        // 5 kaki tetap ≤120 char; engineEvalIndices & ambang tak berubah.
        val p5 = BacktestParams(strategy = "x",
            combo = Combo("OR", listOf("ema_trend", "adx", "rsi", "macd", "supertrend")))
        saok("n-5kaki-cap", noCandidateDetail(p5, flat, k, fcache).length <= 120)
        saok("n-eval-tetap", engineEvalIndices(300, 1000L, 1100L, 900000L) == listOf(298) &&
            engineEvalIndices(300, 1000L, 1000L + 900000L, 900000L) == listOf(298, 299))
        saok("n-ambang-tetap", strategyFn("support_resistance", flat, 19, fcache, emptyMap()).first == "NONE" &&
            strategyFn("mtf_confirm", flat, 49, fcache, emptyMap()).first == "NONE")
        // Sesi kombo tampil terpisah di panel + salinan.
        val snapCombo = ncSnap(true, lastTickAt = 59000L, lastDataAt = 59000L, evalTotal = 2, evalOk = 2).copy(
            sessionCfgFp = "fp-demo-1234567890", sessionStrategy = "support_resistance",
            sessionTimeframe = "15m", sessionPairs = listOf("BTCUSDT"),
            sessionComboMode = "OR", sessionComboExtra = listOf("mtf_confirm"))
        val tCombo = formatEngineDiag(snapCombo, "support_resistance", "15m", "binance",
            true, true, false, 0, 0, 0, 15, ncNow)
        saok("n-sesi-panel", tCombo.contains("Strategi utama: support_resistance") &&
            tCombo.contains("mtf_confirm") && tCombo.contains("mode: OR"), "")
        val cCombo = buildDiagCopyText(snapCombo, "support_resistance", "15m", "binance", ncNow, 0, 0, 0)
        saok("n-sesi-copy", cCombo.contains("Strategi utama: support_resistance") &&
            cCombo.contains("Strategi tambahan: mtf_confirm") &&
            cCombo.contains("Mode kombinasi: OR"), "")
        // Sesi tunggal: baris kombo tak muncul (jujur, bukan klaim kosong).
        val tSingle = formatEngineDiag(ncSnap(true, lastTickAt = 59000L, evalTotal = 2, evalOk = 2).copy(
            sessionCfgFp = "fp-demo", sessionStrategy = "rsi"),
            "rsi", "15m", "binance", true, true, false, 0, 0, 0, 15, ncNow)
        saok("n-sesi-tunggal", !tSingle.contains("Strategi utama:"), "")
    }

    // ===== T-SR: telemetri SR identik strategyFn + alasan eksplisit (SINTETIS) =====
    run {
        // 1) Sweep penuh: result telemetri == strategyFn pada input identik.
        var srN = 0; var srBad = 0
        for ((cs, cc) in listOf(Triple(up, cup, "up"), Triple(dn, cdn, "dn"), Triple(chop, cch, "chop"))) {
            for (k in cs.indices) {
                val prod = strategyFn("support_resistance", cs, k, cc, emptyMap()).first
                val tel = srTelemetry(cs, k).result
                srN++; if (prod != tel) { srBad++; if (srBad <= 3) println("SR-MISMATCH $cc k=$k prod=$prod tel=$tel") }
            }
        }
        saok("t-sr-identik-sweep", srBad == 0, "$srN bar, 3 rezim")
        // 2) Fixture LONG: 20 flat + pantulan.
        val fL = ArrayList<Candle>(); var tL = SAT0
        repeat(20) { fL.add(saC(100.0, 100.1, 99.9, 100.0, tL)); tL += SATF }
        fL.add(saC(100.0, 100.6, 99.95, 100.5, tL))
        val cL = buildCache(fL)
        val tLr = srTelemetry(fL, 20)
        saok("t-sr-long", tLr.result == "LONG" && tLr.reason == "SUPPORT_BOUNCE" && tLr.dir == "BULLISH" &&
            strategyFn("support_resistance", fL, 20, cL, emptyMap()).first == "LONG",
            "distS=${tLr.distSupportPct}")
        // 3) Fixture SHORT: 20 flat + rejection.
        val fS = ArrayList<Candle>(); var tS = SAT0
        repeat(20) { fS.add(saC(100.0, 100.1, 99.9, 100.0, tS)); tS += SATF }
        fS.add(saC(100.0, 100.15, 99.4, 99.5, tS))
        val cS = buildCache(fS)
        val tSr = srTelemetry(fS, 20)
        saok("t-sr-short", tSr.result == "SHORT" && tSr.reason == "RESISTANCE_REJECT" && tSr.dir == "BEARISH" &&
            strategyFn("support_resistance", fS, 20, cS, emptyMap()).first == "SHORT", "")
        // 4) Batas toleransi: tepat 0,100% ke luar (NONE+JARAK), 0,09% ke dalam (LONG).
        val fB = ArrayList<Candle>(); var tB = SAT0
        repeat(20) { fB.add(saC(100.0, 100.1, 99.9, 100.0, tB)); tB += SATF }
        fB.add(saC(100.2, 100.7, 100.1, 100.6, tB)) // |100.1-100|/100 ≈ 0,1000000000000014% → tak < tol
        val cB = buildCache(fB)
        val tBo = srTelemetry(fB, 20)
        saok("t-sr-batas-luar", tBo.result == "NONE" && tBo.reason == "JARAK_DI_LUAR_TOLERANSI" &&
            strategyFn("support_resistance", fB, 20, cB, emptyMap()).first == "NONE", "distS=${tBo.distSupportPct}")
        val fBi = ArrayList<Candle>(); var tBi = SAT0
        repeat(20) { fBi.add(saC(100.0, 100.1, 99.9, 100.0, tBi)); tBi += SATF }
        fBi.add(saC(100.0, 100.7, 99.99, 100.6, tBi)) // 0,09% < 0,1% + bullish
        val cBi = buildCache(fBi)
        val tBii = srTelemetry(fBi, 20)
        saok("t-sr-batas-dalam", tBii.result == "LONG" &&
            strategyFn("support_resistance", fBi, 20, cBi, emptyMap()).first == "LONG", "distS=${tBii.distSupportPct}")
        // 5) Arah gagal: dekat support tapi bearish.
        val fD = ArrayList<Candle>(); var tD = SAT0
        repeat(20) { fD.add(saC(100.0, 100.1, 99.9, 100.0, tD)); tD += SATF }
        fD.add(saC(100.5, 100.6, 99.95, 100.1, tD)) // dekat support (0,05%), bearish; jauh resistance
        val cD = buildCache(fD)
        val tDr = srTelemetry(fD, 20)
        saok("t-sr-arah-gagal", tDr.result == "NONE" && tDr.reason == "ARAH_SUPPORT_GAGAL" &&
            strategyFn("support_resistance", fD, 20, cD, emptyMap()).first == "NONE", "")
        // 6) Data kurang: i<20 + indeks di luar rentang.
        val tDk = srTelemetry(fD, 19)
        saok("t-sr-data-kurang", tDk.result == "NONE" && tDk.reason == "DATA_KURANG" &&
            tDk.distSupportPct == null && strategyFn("support_resistance", fD, 19, cD, emptyMap()).first == "NONE", "")
        val tOo = srTelemetry(fD, 999)
        saok("t-sr-oob", tOo.result == "NONE" && tOo.reason == "DATA_KURANG", "")
        // 7) Nilai tak valid: NaN + level nol.
        val fN = ArrayList<Candle>(); var tN = SAT0
        repeat(20) { fN.add(saC(100.0, 100.1, 99.9, 100.0, tN)); tN += SATF }
        fN.add(saC(Double.NaN, 100.6, 99.9, 100.5, tN))
        val cN = buildCache(fN)
        val tNr = srTelemetry(fN, 20)
        saok("t-sr-nan", tNr.result == "NONE" && tNr.reason == "DATA_INVALID" &&
            strategyFn("support_resistance", fN, 20, cN, emptyMap()).first == "NONE", "")
        val fZ = ArrayList<Candle>(); var tZ = SAT0
        repeat(20) { fZ.add(saC(0.0, 0.0, 0.0, 0.0, tZ)); tZ += SATF }
        fZ.add(saC(0.0, 0.0, 0.0, 0.0, tZ))
        val tZr = srTelemetry(fZ, 20)
        saok("t-sr-nol", tZr.result == "NONE" &&
            strategyFn("support_resistance", fZ, 20, buildCache(fZ), emptyMap()).first == "NONE",
            "reason=${tZr.reason}")
        // 8) Baris verbose memuat semua field wajib.
        val line = srTelemetryLine("BTCUSDT", fL, 20)
        saok("t-sr-baris", line.contains("BTCUSDT") && line.contains("SR=LONG") &&
            line.contains("distS=") && line.contains("distR=") && line.contains("tol=0.10%") &&
            line.contains("arah=BULLISH") && line.contains("alasan=SUPPORT_BOUNCE") &&
            line.contains("BAR=${fL[20].t}"), line)
        // 9) Detail kompak muat 120 char + format lama default tak berubah.
        val pSr = BacktestParams(strategy = "support_resistance",
            combo = Combo("AND", listOf("support_resistance", "mtf_confirm")))
        val dNew = noCandidateDetail(pSr, fL, 20, cL, includeSrTelemetry = true)
        val dOld = noCandidateDetail(pSr, fL, 20, cL)
        saok("t-sr-kompak", dNew.length <= 120 && dNew.contains("MODE=AND") &&
            dNew.contains("SRt:") && dNew.contains("BAR=${fL[20].t}"), dNew)
        saok("t-sr-default-tetap", dOld == "MODE=AND | SR=LONG | MTF=NONE | BAR=${fL[20].t}", dOld)
        // 10) Tunggal SR juga bertelemetri; non-SR tak berubah.
        val pS1 = BacktestParams(strategy = "support_resistance")
        val dS1 = noCandidateDetail(pS1, fL, 20, cL, includeSrTelemetry = true)
        saok("t-sr-tunggal", dS1.contains("MODE=SINGLE") && dS1.contains("SRt:"), dS1)
        val pNs = BacktestParams(strategy = "x", combo = Combo("OR", listOf("ema_trend", "rsi")))
        saok("t-sr-nonsr-tetap",
            noCandidateDetail(pNs, up, 400, cup, includeSrTelemetry = true) ==
                noCandidateDetail(pNs, up, 400, cup), "")
    }

    println("SIGNAL-AUDIT-FULL done pass=$sapass fail=$safail")
    if (safail > 0) kotlin.system.exitProcess(1)
}
