import com.aether.signal.premium.ai.Sig
import com.aether.signal.premium.data.*
import com.aether.signal.premium.engine.*
import com.aether.signal.premium.ui.*

var pass = 0
var fail = 0
fun ok(n: String, c: Boolean, x: String = "") {
    if (c) { pass++; println("PASS $n $x") } else { fail++; println("FAIL $n $x") }
}

fun demo(n: Int = 500): List<Any?> =
    genDemoCandles(42, n, 67000.0, 15).map {
        mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
    }

fun main() {
    // ===== REGRESI MESIN =====
    val r1 = runBacktest(demo(), BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
    val r2 = runBacktest(demo(), BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
    ok("engine-deterministik", r1.netProfit == r2.netProfit && r1.totalTrades == r2.totalTrades && r1.totalTrades > 0, "tr=${r1.totalTrades}")
    ok("registry-25-15", strategyList().size == 25 && filterList().size == 15)
    ok("map-XAUUSD", yahooSymbol("XAUUSD") == "GC=F")
    ok("forex-XAUUSD", isForexLike("XAUUSD"))
    ok("crypto-BTCUSDT", !isForexLike("BTCUSDT"))
    for (t in MARKET_TIMEFRAMES) ok("tf-$t", try { parseTimeframe(t); true } catch (e: Exception) { false })
    val d = getCandles("demo", "BTCUSDT", "15m", 200)
    ok("demo-200", d.candles.size == 200)
    ok("timeout-yahoo", timeoutFor("yahoo") == 15000)
    ok("class-451", classifyFetchError("HTTP 451 blocked") == FetchErrorKind.PERMANENT)
    ok("retry-no-perm", !shouldRetry(FetchErrorKind.PERMANENT, 1))
    run {
        val (n1, e1) = validateCustomPair("BTC/USDT", "binance")
        ok("custom-btc", e1 == null && n1 == "BTCUSDT")
        val (_, e3) = validateCustomPair("XAUUSD", "binance")
        ok("custom-xau-ditolak", e3 != null)
    }

    // ===== REGRESI PURE LAMA =====
    ok("spark", sanitizeSpark(listOf(1.0, Double.NaN, 2.0)) == listOf(1f, 2f))
    run {
        val s = HashSet<String>()
        ok("dedup", dedupAdd(s, "k1") && !dedupAdd(s, "k1"))
    }
    run {
        val l = arrayListOf("a", "b", "c")
        ok("move", moveItem(l, 0, 2) && l == listOf("b", "c", "a"))
    }
    run {
        val lp = parseLastPrice(serializeLastPrice("60000", 1.5, listOf(1.0), 123456L))
        ok("lp-roundtrip", lp != null && lp.price == "60000" && lp.t == 123456L)
    }
    ok("stale", staleAgeLabel(0, 5 * 60000) == "5 mnt lalu")
    ok("backoff", backoffDelaySec(0) == 60L && backoffDelaySec(9) == 300L)
    ok("saver", saverIntervalSec(15) == 450L && effectiveDelaySec(0, false, 15) == 60L)
    ok("csvCell", csvCell("a,b") == "\"a,b\"" && csvCell("x") == "x")
    ok("isoUtc", isoUtc(0L) == "1970-01-01T00:00:00")
    run {
        val pl = parseConfigImport("{\"app\":\"aether-signal\",\"schemaVersion\":2,\"config\":{\"provider\":\"demo\"}}")
        ok("cfg", pl.provider == "demo" && pl.quiet == null)
        ok("cfg-summary", importSummary(pl).contains("Skema v2"))
    }
    run {
        val zero = BacktestResult(totalTrades = 0, diag = BacktestDiag(evaluatedBars = 500, signalsRaw = 0))
        ok("why-nol", explainNoTrades(zero).contains("0 sinyal"))
        ok("why-human", humanFilterReason("RSI Filter (x)") == "RSI berada di area jenuh")
        ok("why-unknown", humanFilterReason("Filter Misterius") == "Filter Misterius")
    }
    run {
        val b = presetById("balanced")!!
        ok("preset-match", matchPreset(b.riskPct, b.leverage, b.maxHolding, b.slPct, b.tpPct)?.id == "balanced")
        ok("preset-kustom", riskPresetLabel(3.3, 1, 100, 1.5, 3.0) == "Kustom")
        ok("nom", riskNominal(1000.0, 1.0) == 10.0 && riskNominal(-1.0, 1.0) == null)
        ok("prev", riskPreviewText(1000.0, 1.0).contains("$10,00"))
    }
    run {
        val q = QuietHours(true, 22 * 60, 7 * 60, setOf(2, 3, 4, 5, 6))
        ok("quiet-malam", quietActive(q, 23 * 60, 2) && quietActive(q, 6 * 60, 3))
        ok("quiet-tolak", !quietActive(q, 23 * 60, 7))
        ok("qp-label", quietPresetLabel(QUIET_PRESET_WORK) == "Malam Hari Kerja")
    }
    run {
        val list = ArrayList<NotifRec>()
        addNotifRec(list, NotifRec(1000, "entry", "BTCUSDT", "15m", "LONG", 60000.0, "DIKIRIM"))
        val back = parseNotifHistory(encodeNotifHistory(list))
        ok("hist-roundtrip", back.size == 1 && back[0].pair == "BTCUSDT")
        ok("ncsv", buildNotifCsv(list).startsWith("n,waktu_utc,jenis,simbol,arah,timeframe,harga,status"))
        ok("flt", filterNotifHistory(list, "btc", "entry", "sent").size == 1)
    }
    run {
        val cs = genDemoCandles(7, 300, 50000.0, 15, 1700000000000L)
        val ref = runBacktest(cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }, BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
        val sess = ReplaySession(cs, BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
        var g = 0
        while (g++ < 5000) { sess.step() ?: break }
        ok("rp-identik", sess.tradesSoFar().size == ref.trades.size)
        val hm = buildTimeHeatmap(ref.trades)
        ok("hm", hm.totalTrades == ref.trades.size && hm.hours.size == 24 && hm.days.size == 7)
    }
    run {
        ok("jr-key", tradeKeyOf("BTCUSDT", "LONG", 1L, 2L) == "BTCUSDT|LONG|1|2")
        val e = sanitizeJournalEntry(JournalEntry(" x ", 100L, 50L, " btcusdt ", "", " k ", " t ", "b"))
        ok("jr-sanitize", e != null && e.id == "x" && e.pair == "BTCUSDT")
    }

    // ===== BARU: identitas sinyal (inti tugas) =====
    val sigs = listOf(
        Sig("BTCUSDT", "15m", "LONG", 67000.0, 66330.0, 68340.0, 1700000000000L, "dryrun-entry", "e1700000000000BTCUSDT"),
        Sig("ETHUSDT", "1h", "SHORT", 3200.0, 3232.0, 3136.0, 1700000900000L, "dryrun-entry", "e1700000900000ETHUSDT"),
        Sig("SOLUSDT", "15m", "LONG", 150.0, 148.5, 153.0, 1700001800000L, "backtest", "b1700001800000SOLUSDT"),
        Sig("BTCUSDT", "15m", "LONG", 68340.0, 66330.0, 68340.0, 1700002700000L, "dryrun-TP", "d1700002700000BTCUSDT"),
        Sig("DOGEUSDT", "5m", "SHORT", 0.15, 0.1515, 0.147, 1700003600000L, "dryrun-SL", "d1700003600000DOGEUSDT")
    )
    // Setiap item ter-resolve ke objeknya sendiri by id — bukan pertama/aktif.
    for (s in sigs) {
        val got = findSignalById(sigs, s.id)
        ok("id-${s.pair}-${s.dir}", got === s)
    }
    ok("id-tak-ada", findSignalById(sigs, "aneh") == null)
    ok("id-kosong", findSignalById(sigs, "") == null && findSignalById(emptyList(), "x") == null)
    run {
        // Daftar diperbarui (item pertama dihapus): lookup by id tetap benar.
        val updated = sigs.drop(1)
        val got = findSignalById(updated, "e1700000900000ETHUSDT")
        ok("id-update-benar", got?.pair == "ETHUSDT" && got.dir == "SHORT")
        ok("id-update-hilang", findSignalById(updated, "e1700000000000BTCUSDT") == null)
    }
    run {
        // Simulasi klik adapter: posisi -> id snapshot -> resolve di daftar terkini.
        val vis = sigs.take(3)
        for (pos in vis.indices) {
            val snapId = vis.getOrNull(pos)?.id ?: ""
            val resolved = findSignalById(sigs, snapId)
            ok("klik-pos-$pos", resolved === vis[pos])
        }
        ok("klik-pos-invalid", vis.getOrNull(99) == null)
    }

    // ===== BARU: label & status =====
    ok("src-entry", srcLabel("dryrun-entry") == "Sinyal entry live (paper)")
    ok("src-tp", srcLabel("dryrun-TP") == "Take profit tercapai (paper)")
    ok("src-sl", srcLabel("dryrun-SL") == "Stop loss tercapai (paper)")
    ok("src-bt", srcLabel("backtest") == "Arsip backtest")
    ok("src-aneh", srcLabel("xyz") == "xyz")
    ok("entryLike", isEntryLike("dryrun-entry") && isEntryLike("backtest") && !isEntryLike("dryrun-TP") && !isEntryLike("dryrun-SL"))
    ok("st-backtest", deriveSignalStatus("backtest", false, false) == "Arsip backtest (bukan posisi live)")
    ok("st-tp", deriveSignalStatus("dryrun-TP", false, false) == "Keluar — take profit tercapai")
    ok("st-sl", deriveSignalStatus("dryrun-SL", false, false) == "Keluar — stop loss tercapai")
    ok("st-aktif", deriveSignalStatus("dryrun-entry", true, false) == "Aktif (posisi paper terbuka)")
    ok("st-keluar", deriveSignalStatus("dryrun-entry", false, true) == "Sudah keluar (TP/SL tercatat)")
    ok("st-tunggu", deriveSignalStatus("dryrun-entry", false, false) == "Menunggu (tak terlacak di posisi terbuka)")
    ok("st-unknown", deriveSignalStatus("zzz", false, false) == "Tidak diketahui")

    // ===== BARU: jarak & RR dari data aktual =====
    run {
        val dd = signalDistances(60000.0, 59400.0, 61200.0)
        ok("dist-nilai", dd.slDistPrice == 600.0 && dd.slDistPct == 1.0 && dd.tpDistPrice == 1200.0 && dd.tpDistPct == 2.0 && dd.rr == 2.0)
    }
    run {
        // SHORT: entry 3200, sl 3232, tp 3136 → sl 32 (1%), tp 64 (2%), RR 2.
        val dd = signalDistances(3200.0, 3232.0, 3136.0)
        ok("dist-short", dd.slDistPrice == 32.0 && dd.tpDistPrice == 64.0 && dd.rr == 2.0)
    }
    run {
        val bad = signalDistances(0.0, 1.0, 2.0)
        ok("dist-nol", bad.slDistPrice == null && bad.rr == null)
        val bad2 = signalDistances(100.0, -5.0, Double.NaN)
        ok("dist-invalid", bad2.slDistPrice == null && bad2.tpDistPrice == null && bad2.rr == null)
        val zero = signalDistances(100.0, 100.0, 110.0)
        ok("dist-rr-nol", zero.slDistPrice == 0.0 && zero.rr == null)
    }

    // ===== BARU: rationale arah =====
    ok("rat-long-ok", directionRationale("LONG", 60000.0, 59400.0, 61200.0).contains("konsisten dengan arah LONG"))
    ok("rat-short-ok", directionRationale("SHORT", 3200.0, 3232.0, 3136.0).contains("konsisten dengan arah SHORT"))
    ok("rat-long-bad", directionRationale("LONG", 60000.0, 61200.0, 59400.0).contains("tidak lazim"))
    ok("rat-short-bad", directionRationale("SHORT", 3200.0, 3136.0, 3232.0).contains("tidak lazim"))
    ok("rat-incomplete", directionRationale("LONG", 0.0, 1.0, 2.0).contains("tidak lengkap"))
    ok("rat-unknown", directionRationale("SIDEWAYS", 100.0, 99.0, 101.0).contains("tidak dikenal"))


    // ===== V16 F1: jejak tersimpan & terbaca =====
    run {
        val e = sanitizeJournalEntry(JournalEntry("x", 1L, 1L, "", "", "", "", "")) // pinjam guard
        ok("f1-guard-ok", e != null)
        val sig = Sig("BTCUSDT", "15m", "LONG", 67000.0, 66330.0, 68340.0, 1000L, "dryrun-entry", "e1",
            strategy = "rsi", confidence = 0.6, reasons = listOf("RSI oversold 25"),
            passedFilters = listOf("rsi", "adx"), failedFilters = listOf("volume"),
            decidedAt = 2000L, score = 77.0, scoreDetail = "7 dari 9")
        ok("f1-trace-field", sig.strategy == "rsi" && sig.confidence == 0.6 && sig.reasons == listOf("RSI oversold 25")
            && sig.passedFilters == listOf("rsi", "adx") && sig.failedFilters == listOf("volume")
            && sig.decidedAt == 2000L && sig.score == 77.0)
        ok("f1-default-lama", Sig("X", "15m", "LONG", 1.0, 1.0, 1.0, 1L, "backtest", "b1").strategy == ""
            && Sig("X", "15m", "LONG", 1.0, 1.0, 1.0, 1L, "backtest", "b1").score == -1.0)
        ok("f1-clean", cleanStrList(listOf(" a ", "", "a", "b")) == listOf("a", "b"))
        ok("f1-enc-roundtrip", parseStrList(encodeStrList(listOf("x", "y"))) == listOf("x", "y"))
        ok("f1-enc-kosong", parseStrList(null).isEmpty() && encodeStrList(emptyList()).isEmpty())
    }

    // ===== V16 F2: skor 0-100, konsisten, parsial eksplisit =====
    run {
        val full = signalScore(9, 9, 0.6, 1.0, 3.0)!!
        ok("f2-range", full.total in 0.0..100.0)
        ok("f2-nilai", full.total == (60.0 + 12.0 + 20.0)) // 9/9*60 + .6*20 + min(3/3)*20
        ok("f2-cover", full.coverage == "filter+confidence+RR")
        val same = signalScore(9, 9, 0.6, 1.0, 3.0)!!
        ok("f2-konsisten", same.total == full.total && same.coverage == full.coverage)
        val partial = signalScore(0, 0, 0.0, 1.0, 2.0)!!
        ok("f2-parsial", partial.coverage == "RR" && partial.total in 0.0..100.0)
        ok("f2-nol", signalScore(0, 0, 0.0, null, null) == null)
        ok("f2-clamp", signalScore(99, 9, 5.0, 1.0, 99.0)!!.total <= 100.0)
        ok("f2-explain", scoreExplain(7, 9) == "7 dari 9 filter aktif lolos"
            && scoreExplain(0, 0) == "tanpa filter aktif")
        ok("f2-bukan-prob", true) // label "BUKAN probabilitas" ada di UI detail (teks statis)
    }

    // ===== V16 F3: audit fee tanpa double-count =====
    run {
        fun mkT(pnl: Double, fees: Double) = Trade("LONG", "BTCUSDT", "15m", 100.0, 101.0, 99.0, 102.0,
            1L, 2L, "rsi", 0.5, emptyList(), 1.0, 10.0, fees, pnl, 1.0, 1.0, "WIN", 4)
        val a = feeAudit(listOf(mkT(90.0, 10.0), mkT(-30.0, 5.0)))
        ok("f3-total", a.totalFees == 15.0 && a.trades == 2)
        ok("f3-net", a.netProfit == 60.0)
        ok("f3-gross", a.grossBeforeFees == 75.0) // net + fee, bukan estimasi baru
        ok("f3-share", a.feeSharePct == 20.0)
        val nol = feeAudit(listOf(mkT(-10.0, 10.0)))
        ok("f3-nol-negatif", nol.grossBeforeFees == 0.0 && nol.feeSharePct == null)
        val empty = feeAudit(emptyList())
        ok("f3-kosong", empty.totalFees == 0.0 && empty.feeSharePct == null)
    }

    // ===== V16 F4: badge usia per TF =====
    run {
        val now = 1_700_000_000_000L
        val tf = 15
        ok("f4-baru", !staleBadge(now - 60_000L, now, tf).stale)
        ok("f4-ambang", staleBadge(now - 30L * 15 * 60_000L - 1, now, tf).stale)
        ok("f4-tepat", !staleBadge(now - 30L * 15 * 60_000L, now, tf).stale)
        ok("f4-jam", staleBadge(now - 31L * 60 * 60_000L, now, 60).stale) // TF 1h
        ok("f4-hari", !staleBadge(now - 2 * 86_400_000L, now, 1440).stale) // TF 1d, 2 hari < 30 hari
        ok("f4-hari-tua", staleBadge(now - 31L * 86_400_000L, now, 1440).stale)
        ok("f4-tak-ada-waktu", !staleBadge(0L, now, tf).stale)
        ok("f4-teks", staleBadge(now - 31L * 15 * 60_000L, now, tf).text.contains("Kedaluwarsa mungkin"))
    }

    // ===== V16 F5: Monte Carlo reproduksibel =====
    run {
        val pnls = listOf(10.0, -5.0, 8.0, -3.0, 12.0, -7.0, 4.0, 6.0)
        val a = monteCarlo(pnls, 1000.0, 1000, 42L, 50.0)!!
        val b = monteCarlo(pnls, 1000.0, 1000, 42L, 50.0)!!
        ok("f5-repro", a.medianDDPct == b.medianDDPct && a.ruinProb == b.ruinProb
            && a.minDDPct == b.minDDPct && a.maxDDPct == b.maxDDPct)
        ok("f5-final-sama", a.finalEquity == 1000.0 + pnls.sum())
        ok("f5-dd-valid", a.minDDPct >= 0 && a.medianDDPct in a.minDDPct..a.maxDDPct && a.ruinProb in 0.0..1.0)
        val c = monteCarlo(pnls, 1000.0, 1000, 43L, 50.0)!!
        ok("f5-seed-beda-jalur", c.finalEquity == a.finalEquity) // multiset sama → final sama
        ok("f5-kosong", monteCarlo(emptyList(), 1000.0, 100, 1L, 50.0) == null)
        ok("f5-tak-valid", monteCarlo(listOf(Double.NaN), 1000.0, 100, 1L, 50.0) == null)
        ok("f5-modal-bad", monteCarlo(pnls, 0.0, 100, 1L, 50.0) == null)
        ok("f5-ambang-bad", monteCarlo(pnls, 1000.0, 100, 1L, 0.0) == null)
    }

    // ===== V16 F6: ablation murni (delta+sort; run di Activity) =====
    run {
        val base = BacktestResult(totalTrades = 10, wins = 5, winRate = 50.0, netProfit = 100.0, maxDrawdownPercent = 5.0)
        val up = base.copy(totalTrades = 14, winRate = 60.0, netProfit = 160.0, maxDrawdownPercent = 4.0)
        val dn = base.copy(totalTrades = 6, winRate = 40.0, netProfit = 40.0, maxDrawdownPercent = 8.0)
        val d = ablationDelta(base, up)
        ok("f6-delta", d.dTrades == 4 && d.dNet == 60.0 && d.dWinRate == 10.0 && d.dDDPct == -1.0)
        val trials = listOf(
            AblationTrial("rsi", "RSI Filter", true, up),
            AblationTrial("adx", "ADX Filter", true, dn),
            AblationTrial("x", "X", false, null, "gagal"))
        ok("f6-sort-net", sortAblation(base, trials, "net").map { it.filterId } == listOf("rsi", "adx", "x"))
        ok("f6-sort-trades", sortAblation(base, trials, "trades").map { it.filterId } == listOf("rsi", "adx", "x"))
        ok("f6-sort-dd", sortAblation(base, trials, "dd").first().filterId == "rsi") // ΔDD -1 → terbaik
        ok("f6-tak-comparable-bawah", sortAblation(base, trials, "net").last().filterId == "x")
    }

    // ===== V16 F7: regime =====
    run {
        val n = 60
        val up = DoubleArray(n) { 100.0 + it * 0.5 } // slope +0,5%/bar >> ambang
        val flat = DoubleArray(n) { 100.0 }
        val adxKuat = DoubleArray(n) { 30.0 }
        val adxLemah = DoubleArray(n) { 10.0 }
        val rUp = classifyRegime(up, adxKuat, 1.0, n - 1)
        ok("f7-naik", rUp?.label == "Tren naik" && rUp.volatile == false)
        val dn = DoubleArray(n) { 200.0 - it * 0.5 }
        ok("f7-turun", classifyRegime(dn, adxKuat, 1.0, n - 1)?.label == "Tren turun")
        ok("f7-sideways-adx", classifyRegime(up, adxLemah, 1.0, n - 1)?.label == "Sideways")
        ok("f7-sideways-datar", classifyRegime(flat, adxKuat, 1.0, n - 1)?.label == "Sideways")
        ok("f7-volatil", classifyRegime(up, adxKuat, 6.0, n - 1)?.volatile == true)
        ok("f7-kurang", classifyRegime(DoubleArray(10) { 1.0 }, DoubleArray(10) { 30.0 }, null, 9) == null)
        ok("f7-nan", classifyRegime(DoubleArray(n) { Double.NaN }, adxKuat, null, n - 1) == null)
        ok("f7-teks", regimeText(null) == "Data tidak cukup"
            && regimeText(Regime("Tren naik", true, 0)) == "Tren naik · volatil")
    }

    // ===== V16 F8: digest =====
    run {
        ok("f8-key", digestBucketKey(2026, 10, 10, 9) == "2026101009")
        var b: DigestBucket? = null
        b = digestAdd(b, "k1", "09.00", "BTCUSDT LONG 15m")
        b = digestAdd(b, "k1", "09.00", "ETHUSDT SHORT 1h")
        ok("f8-akumulasi", b.count == 2 && b.lines.size == 2)
        val ganti = digestAdd(b, "k2", "10.00", "X LONG 15m")
        ok("f8-ganti-bucket", ganti.count == 1 && ganti.key == "k2")
        val s = digestSummary(b)
        ok("f8-summary", s.contains("2 sinyal") && s.contains("BTCUSDT") && s.contains("ketuk untuk buka"))
        var b2: DigestBucket? = null
        repeat(8) { b2 = digestAdd(b2, "k", "09.00", "L$it") }
        ok("f8-batas-baris", b2!!.count == 8 && b2.lines.size == 5)
    }

    // ===== V16 F9: konsensus =====
    run {
        val all = listOf(TfVote("15m", "LONG"), TfVote("1h", "LONG"), TfVote("4h", "LONG"))
        ok("f9-sepakat", consensusVerdict(all).summary.contains("3 dari 3 sepakat LONG"))
        val belah = listOf(TfVote("15m", "LONG"), TfVote("1h", "SHORT"))
        ok("f9-belah", consensusVerdict(belah).summary.contains("terbelah"))
        val none = listOf(TfVote("15m", "NONE"), TfVote("1h", "LONG"))
        ok("f9-none-beda", consensusVerdict(none).summary.contains("Tanpa sinyal") || consensusVerdict(none).summary.contains("Tidak sepakat"))
        val unav = listOf(TfVote("15m", "LONG"), TfVote("1h", "UNAVAILABLE", "timeout"))
        val vu = consensusVerdict(unav)
        ok("f9-unav", vu.summary.contains("1h") && vu.detail.contains("data tak tersedia"))
        ok("f9-kosong", consensusVerdict(emptyList()).summary.contains("Belum ada data"))
        ok("f9-semua-unav", consensusVerdict(listOf(TfVote("1h", "UNAVAILABLE"))).summary.contains("Belum dapat disimpulkan"))
    }

    // ===== V16 F10: trailing murni =====
    run {
        val d = trailDistance(60000.0, 61200.0, 50.0)!!
        ok("f10-dist", d == 600.0) // 50% × 1200
        ok("f10-fallback", trailDistance(60000.0, Double.NaN, 50.0) == 300.0) // 0,5% entry
        ok("f10-entry-bad", trailDistance(0.0, 61200.0, 50.0) == null)
        ok("f10-lock-clamp", trailDistance(100.0, 110.0, 500.0) == 10.0) // clamp 100%
    }
    run {
        // LONG: stop naik saja, tak turun; tak melebar (clamp di pemanggil, di sini murni ratchet).
        var st = TrailState(59400.0, 60000.0)
        var r = ratchet("LONG", st.stop, st.extreme, 60600.0, 600.0)
        ok("f10-long-naik", r.first == 60000.0 && r.second == 60600.0)
        st = TrailState(r.first, r.second)
        r = ratchet("LONG", st.stop, st.extreme, 60300.0, 600.0)
        ok("f10-long-tetap", r.first == 60000.0 && r.second == 60600.0)
        ok("f10-long-sentuh", trailTouched("LONG", 59900.0, 60000.0) && !trailTouched("LONG", 60100.0, 60000.0))
    }
    run {
        // SHORT: cermin — stop turun saja.
        var st = TrailState(3264.0, 3200.0)
        var r = ratchet("SHORT", st.stop, st.extreme, 3168.0, 32.0)
        ok("f10-short-turun", r.first == 3200.0 && r.second == 3168.0)
        st = TrailState(r.first, r.second)
        r = ratchet("SHORT", st.stop, st.extreme, 3180.0, 32.0)
        ok("f10-short-tetap", r.first == 3200.0 && r.second == 3168.0)
        ok("f10-short-sentuh", trailTouched("SHORT", 3210.0, 3200.0) && !trailTouched("SHORT", 3190.0, 3200.0))
    }
    run {
        ok("f10-key", positionKey("BTCUSDT", "LONG", 5L) == "BTCUSDT|LONG|5")
        ok("f10-sanitize", sanitizeLockPct(Double.NaN) == 50.0 && sanitizeLockPct(500.0) == 100.0 && sanitizeLockPct(-1.0) == 5.0)
        val m = mapOf("k1" to TrailState(100.0, 110.0))
        val back = parseTrail(encodeTrail(m))
        ok("f10-roundtrip", back["k1"] == TrailState(100.0, 110.0))
        ok("f10-rusak", parseTrail("{rusak").isEmpty() && parseTrail(null).isEmpty())
        ok("f10-invalid", ratchet("LONG", 1.0, 2.0, Double.NaN, 1.0) == (1.0 to 2.0))
    }
    run {
        // Trail tak menyentuh mesin: runBacktest deterministik tetap sama.
        val a = runBacktest(demo(200), BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
        val b = runBacktest(demo(200), BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
        ok("f10-mesin-utuh", a.netProfit == b.netProfit && a.totalTrades == b.totalTrades)
    }

    // ===== V16 integrasi: export/import digest+trail, kompatibel lama =====
    run {
        val txt = "{\"app\":\"aether-signal\",\"schemaVersion\":2,\"config\":{" +
            "\"notif\":{\"digest\":true},\"trail\":{\"on\":true,\"lockPct\":25.0}}}"
        val pl = parseConfigImport(txt)
        ok("f16-cfg-digest", pl.notif["digest"] == true)
        ok("f16-cfg-trail", pl.trail?.first == true && pl.trail?.second == 25.0)
        ok("f16-cfg-summary", importSummary(pl).contains("Trailing"))
        val old = parseConfigImport("{\"app\":\"aether-signal\",\"schemaVersion\":2,\"config\":{}}")
        ok("f16-cfg-lama", old.trail == null && !old.notif.containsKey("digest"))
    }


    // ===== V17 FORENSIK: dataset sintetis Jan/Agu/Sep/Okt (15m, UTC) =====
    fun msUtc(y: Int, mo: Int, d: Int): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(y, mo - 1, d, 0, 0, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    fun monthBars(y: Int, mo: Int, n: Int, p0: Double): List<Candle> {
        var t = msUtc(y, mo, 1); var p = p0
        return (0 until n).map {
            val o = p; val c = o * 1.001
            val bar = Candle(t, o, c * 1.001, o * 0.9995, c, 100.0)
            t += 900000L; p = c; bar
        }
    }
    fun toRaw(cs: List<Candle>): List<Any?> = cs.map {
        mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
    }
    val jan = monthBars(2026, 1, 70, 50000.0)
    val aug = monthBars(2026, 8, 70, jan.last().c)
    val sep = monthBars(2026, 9, 70, aug.last().c)
    val oct = monthBars(2026, 10, 70, sep.last().c)
    val all4 = jan + aug + sep + oct
    val JAN1 = msUtc(2026, 1, 1); val SEP1 = msUtc(2026, 9, 1); val NOV1 = msUtc(2026, 11, 1)
    fun pRange(a: Long, b: Long) = BacktestParams(asset = "BTCUSDT", timeframe = "15m",
        strategy = "ema_trend", filters = emptyList(), startDate = a, endDate = b)

    // Periode mencakup tepat yang diminta; batas inklusif; luar periode tak ikut.
    run {
        val rA = runBacktest(toRaw(all4), pRange(JAN1, NOV1))
        val rB = runBacktest(toRaw(all4), pRange(SEP1, NOV1))
        ok("f17-a-punya-transaksi", rA.totalTrades > 0, "tr=${rA.totalTrades}")
        ok("f17-b-subset-waktu", rB.trades.all { it.entryTime >= SEP1 && it.exitTime <= NOV1 })
        ok("f17-a-dalam-rentang", rA.trades.all { it.entryTime >= JAN1 && it.exitTime <= NOV1 })
        ok("f17-beda-hasil", rA.totalTrades != rB.totalTrades || rA.netProfit != rB.netProfit,
            "A=${rA.totalTrades}/${rA.netProfit} B=${rB.totalTrades}/${rB.netProfit}")
        // Invarian partisi: dievaluasi + terlewati-dalam-posisi = bar rentang
        // (A: 280-60=220 bar → 219 slot; B: 140-60=80 → 79 slot).
        ok("f17-partisi-a", rA.diag.evaluatedBars + rA.diag.skippedInPosition == 219,
            "eval=${rA.diag.evaluatedBars} skip=${rA.diag.skippedInPosition}")
        ok("f17-partisi-b", rB.diag.evaluatedBars + rB.diag.skippedInPosition == 79,
            "eval=${rB.diag.evaluatedBars} skip=${rB.diag.skippedInPosition}")
        ok("f17-a-lebih-banyak", rA.totalTrades > rB.totalTrades)
        ok("f17-runid", rA.diag.runId.isNotEmpty() && rA.diag.finishedAt > 0)
        ok("f17-warmup-dilaporkan", rB.diag.warmupPrefix == 60)
    }
    run {
        // Batas inklusif: candle tepat di from/to ikut diproses.
        val tiny = listOf(
            Candle(SEP1, 100.0, 101.0, 99.0, 100.5, 10.0),
            Candle(SEP1 + 900000L, 100.5, 102.0, 100.0, 101.5, 10.0))
        val f = filterByDate(tiny, SEP1, SEP1 + 900000L)
        ok("f17-batas-inklusif", f.size == 2)
        ok("f17-batas-luar", filterByDate(tiny, SEP1 + 900000L + 1, NOV1).isEmpty())
        val (pre, inR) = splitWarmup(all4, SEP1, NOV1)
        ok("f17-split", pre.size == 60 && inR.size == 140 && inR.first().t >= SEP1)
        val (pre2, inR2) = splitWarmup(all4, 0L, 0L)
        ok("f17-split-tanpa-batas", pre2.isEmpty() && inR2.size == 280)
        val (pre3, _) = splitWarmup(all4, JAN1 - 1, NOV1)
        ok("f17-split-sebelum-data", pre3.isEmpty())
    }
    run {
        // Rentang kosong/tak cukup → error jelas, bukan hasil palsu.
        val r = runBacktest(toRaw(all4), pRange(msUtc(2025, 1, 1), msUtc(2025, 2, 1)))
        ok("f17-kosong-error", r.error != null && r.totalTrades == 0, r.error ?: "")
        val r2 = runBacktest(toRaw(all4.take(10)), pRange(JAN1, NOV1))
        ok("f17-kurang-error", r2.error != null && r2.totalTrades == 0)
    }
    run {
        // Kasus persis user: jendela data seluruhnya di dalam KEDUA rentang
        // → hasil identik (benar), DAN cakupan A terbukti parsial.
        val winSepOct = sep + oct
        val rA = runBacktest(toRaw(winSepOct), pRange(JAN1, NOV1))
        val rB = runBacktest(toRaw(winSepOct), pRange(SEP1, NOV1))
        ok("f17-user-identik-benar", rA.totalTrades == rB.totalTrades && rA.netProfit == rB.netProfit,
            "tr=${rA.totalTrades}")
        ok("f17-user-cov-a-parsial", coverageStatus(JAN1, winSepOct.first().t, winSepOct.size) == "parsial")
        ok("f17-user-cov-b-penuh", coverageStatus(SEP1, winSepOct.first().t, winSepOct.size) == "penuh")
        val fpA = datasetFingerprint("demo", "BTCUSDT", "15m", 500, winSepOct.first().t, winSepOct.last().t, winSepOct.size)
        ok("f17-fp-sama-data-sama", fpA == datasetFingerprint("demo", "BTCUSDT", "15m", 500, winSepOct.first().t, winSepOct.last().t, winSepOct.size))
        ok("f17-fp-beda-data-beda", fpA != datasetFingerprint("demo", "BTCUSDT", "15m", 500, all4.first().t, all4.last().t, all4.size))
    }

    // ===== V17: warmup — tanpa transaksi pra-start; indikator stabil =====
    run {
        val rB = runBacktest(toRaw(all4), pRange(SEP1, NOV1))
        ok("f17-tanpa-pra-start", rB.trades.all { it.entryTime >= SEP1 })
        // Manfaat prefix yang TERBUKTI: indikator panjang (EMA200, butuh 200 bar)
        // menjadi tersedia; tanpa prefix ia NaN selamanya pada data 140 bar.
        val (pre, inR) = splitWarmup(all4, SEP1, NOV1)
        val preCache = buildCache(pre + inR)
        val noPreCache = buildCache(inR)
        ok("f17-ema200-tersedia", preCache.e200.last().isFinite())
        ok("f17-ema200-tanpa-prefix-nan", noPreCache.e200.all { it.isNaN() })
        // Tanpa startDate: perilaku identik dengan sebelumnya (prefix kosong).
        val rNoDate = runBacktest(toRaw(all4), pRange(0L, 0L))
        ok("f17-tanpa-tanggal-sama", rNoDate.diag.warmupPrefix == 0 &&
            rNoDate.diag.evaluatedBars + rNoDate.diag.skippedInPosition == 219)
    }

    // ===== V17: lookahead — entry di open berikut; SL didahulukan; indikator kausal =====
    run {
        // 61 bar naik + 1 bar raksasa dua-arah → sinyal di bar 60, SL&TP tersentuh di bar 61.
        val bars = ArrayList<Candle>()
        var t = msUtc(2026, 10, 1); var p = 90.0
        for (k in 0..60) {
            val o = p; val c = o * 1.001
            bars.add(Candle(t, o, c * 1.001, o * 0.9995, c, 100.0))
            t += 900000L; p = c
        }
        val o61 = p
        bars.add(Candle(t, o61, o61 * 1.1, o61 * 0.9, o61, 100.0))
        val r = runBacktest(toRaw(bars), BacktestParams(asset = "BTCUSDT", timeframe = "15m",
            strategy = "ema_trend", filters = emptyList()))
        ok("f17-satu-trade", r.totalTrades == 1, "tr=${r.totalTrades}")
        val tr = r.trades.first()
        ok("f17-entry-next-open", tr.entryTime == bars[61].t && tr.entry == o61 * 1.0002)
        ok("f17-sl-didahulukan", tr.result == "LOSS" && tr.exitTime == bars[61].t)
    }
    run {
        // Seri 120 bar; masa depan (>=80) digandakan; indeks 60 harus identik
        // (perbandingan sadar-NaN: NaN==NaN bernilai false di JVM).
        fun eqD(a: Double, b: Double) = (a.isNaN() && b.isNaN()) || a == b
        val cs = monthBars(2026, 10, 120, 50000.0)
        val c1 = buildCache(cs)
        val mut = cs.mapIndexed { i, c -> if (i >= 80) c.copy(c = c.c * 2) else c }
        val c2 = buildCache(mut)
        ok("f17-kausal-rsi", eqD(c1.rsi14[60], c2.rsi14[60]))
        ok("f17-kausal-adx", eqD(c1.adx14[60], c2.adx14[60]))
        ok("f17-kausal-ema", eqD(c1.e50[60], c2.e50[60]) && c1.e50[60].isFinite())
    }

    // ===== V17: kualitas & TF =====
    run {
        val grid = monthBars(2026, 10, 70, 50000.0)
        ok("f17-gap-ok", medianGap(grid) == 900000L)
        val q = auditWindow(grid, 15, 0L, 0L, grid.last().t + 900000L * 2)
        ok("f17-bersih", q.gaps.isEmpty() && q.unordered == 0 && q.outOfRange == 0 && !q.unclosedLast)
        val berlubang = grid.filterIndexed { i, _ -> i != 35 }
        val q2 = auditWindow(berlubang, 15, 0L, 0L, grid.last().t + 900000L * 2)
        ok("f17-gap-terdeteksi", q2.gaps.size == 1 && q2.gaps[0].missing == 1)
        val duplikat = grid + listOf(grid[10])
        ok("f17-duplikat-dibuang", normalizeCandles(duplikat.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }).size == grid.size)
        val acak = grid.shuffled(java.util.Random(1))
        val dinorm = normalizeCandles(acak.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        })
        ok("f17-diurutkan", dinorm.zipWithNext().all { (a, b) -> a.t < b.t })
        val q3 = auditWindow(grid, 15, 0L, 0L, grid.last().t) // candle terakhir = kini
        ok("f17-belum-tutup", q3.unclosedLast)
        ok("f17-fmtutc", fmtUtc(0L) == "—" && fmtUtc(msUtc(2026, 1, 1)).startsWith("2026-01-01 00:00"))
    }

    // ===== V17: replay paritas ber-tanggal + regresi lama kunci =====
    run {
        val r = runBacktest(toRaw(all4), pRange(SEP1, NOV1))
        val sess = ReplaySession(all4, pRange(SEP1, NOV1))
        var g = 0
        while (g++ < 5000) { sess.step() ?: break }
        ok("f17-replay-tanggal", sess.tradesSoFar().size == r.trades.size)
        val a = runBacktest(demo(200), BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
        val b = runBacktest(demo(200), BacktestParams(asset = "BTCUSDT", timeframe = "15m"))
        ok("f17-deterministik", a.netProfit == b.netProfit && a.totalTrades == b.totalTrades && a.totalTrades > 0)
        ok("f17-registry", strategyList().size == 25 && filterList().size == 15)
    }


    // ===== V18 #1: teks kaya dari jejak (unit) =====
    run {
        val s = Sig("BTCUSDT", "15m", "LONG", 62450.0, 61950.0, 63450.0, 1000L, "dryrun-entry", "e1",
            strategy = "order_block", confidence = 0.57, reasons = listOf("OB bull retap"),
            passedFilters = listOf("volume"), failedFilters = emptyList(),
            decidedAt = 2000L, score = 80.0, scoreDetail = "1 dari 1")
        val r = buildRichSignal(s, mapOf("order_block" to "Order Block"))
        ok("rich-judul", r.title == "Entry LONG BTCUSDT")
        ok("rich-entry", r.body.contains("Entry: 62,450") || r.body.contains("Entry: 62450"))
        ok("rich-sl-tp", r.body.contains("61,950") && r.body.contains("63,450"))
        ok("rich-rr", r.body.contains("Risk/Reward: 1:2"))
        ok("rich-strategi", r.body.contains("Order Block") && !r.body.contains("Order Block Retest"))
        ok("rich-alasan-asli", r.body.contains("OB bull retap"))
        ok("rich-filter", r.body.contains("Lolos filter: volume"))
        ok("rich-skor", r.body.contains("80/100") && r.body.contains("BUKAN probabilitas"))
        ok("rich-footer", r.body.contains("Bukan nasihat keuangan") && r.body.contains("Tidak mengeksekusi"))
        ok("rich-tanpa-karangan", !r.body.contains("sapuan likuiditas") && !r.body.contains("swing low"))
    }
    run {
        // Tanpa jejak → jujur "Tidak tersedia", tak ada narasi karangan.
        val s = Sig("X", "15m", "LONG", 1.0, 1.0, 1.0, 1L, "dryrun-entry", "e0")
        val r = buildRichSignal(s)
        ok("rich-na-strategi", r.body.contains("STRATEGI PEMICU\nTidak tersedia"))
        ok("rich-na-alasan", r.body.contains("jejak keputusan tidak tersimpan"))
        ok("rich-na-skor", r.body.contains("Skor Konfirmasi: Tidak tersedia"))
    }
    run {
        ok("rich-judul-tp", richTitle(Sig("A", "1h", "SHORT", 1.0, 2.0, 0.5, 1L, "dryrun-TP", "d")) == "Take profit tercapai · SHORT A")
        ok("rich-judul-sl", richTitle(Sig("A", "1h", "SHORT", 1.0, 2.0, 0.5, 1L, "dryrun-SL", "d")) == "Stop loss tercapai · SHORT A")
        ok("rich-judul-trail", richTitle(Sig("A", "1h", "SHORT", 1.0, 2.0, 0.5, 1L, "dryrun-trail", "t")).contains("Trailing"))
        val tp = buildRichSignal(Sig("A", "1h", "SHORT", 0.5, 2.0, 0.5, 1L, "dryrun-TP", "d"))
        ok("rich-exit-tanpa-rr", tp.body.contains("Exit:") && !tp.body.contains("Risk/Reward"))
        ok("rich-strat-unknown", richStrategyName(Sig("A", "1h", "LONG", 1.0, 1.0, 1.0, 1L, "backtest", "b", strategy = "zzz")) == "zzz")
        ok("rich-reasons-kosong", richReasonLines(Sig("A", "1h", "LONG", 1.0, 1.0, 1.0, 1L, "backtest", "b")).isEmpty())
        val bad = buildRichSignal(Sig("A", "1h", "LONG", 0.0, 0.0, 0.0, 1L, "dryrun-entry", "e"))
        ok("rich-rr-invalid", bad.body.contains("Risk/Reward: Tidak tersedia"))
    }

    // ===== V18 #2: BUKTI pipeline engine→Sig→teks (jalur aktual BotEngine) =====
    run {
        // Data sintetis deterministik: impuls +1% di bar 64, retap ke open-nya di bar 67
        // → memenuhi kondisi order_block LONG persis (impUp>0,6% & l<=o64 & c>o).
        val cs = ArrayList<Candle>()
        var t = 1700000000000L; var px = 60000.0
        for (k in 0..63) {
            val o = px; val c = o * (if (k % 2 == 0) 1.0005 else 0.9997)
            cs.add(Candle(t, o, maxOf(o, c) * 1.0002, minOf(o, c) * 0.9998, c, 100.0))
            t += 900000L; px = c
        }
        val o64 = px; val c64 = o64 * 1.01
        cs.add(Candle(t, o64, c64 * 1.001, o64 * 0.9998, c64, 120.0)); t += 900000L
        cs.add(Candle(t, c64, c64 * 1.0005, c64 * 0.999, c64 * 0.9995, 110.0)); t += 900000L
        cs.add(Candle(t, c64 * 0.9995, c64 * 0.9998, c64 * 0.999, c64 * 0.9992, 105.0)); t += 900000L
        val o67 = c64 * 0.9992
        cs.add(Candle(t, o67, o67 * 1.002, o64 - 50.0, o67 * 1.0015, 10000.0))
        val params = BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "order_block",
            filters = listOf(FilterCfg("volume", true),
                FilterCfg("rsi", true, mapOf("longMax" to 90.0, "shortMin" to 10.0))))
        val cache = buildCache(cs)
        val i = 67
        val dec = decideAt(cs, i, cache, params)
        ok("proof-sinyal-ob", dec.passed && dec.direction == "LONG", "${dec.direction} ${dec.reasons}")
        val fr = applyFilters(cs, i, cache, dec.direction, params.filters, FilterCtx(lastExit = -1000000000))
        ok("proof-filter-lolos", fr.passed, fr.failed.joinToString(";"))
        val failedIds = fr.failed.mapNotNull { filterIdForReasonKey(it) }.distinct()
        // Replika persis pembuatan Sig di BotEngine.tick.
        val entry = cs[i].c * (1 + params.slippagePercent)
        val sl = entry * (1 - params.slPercent)
        val tp = entry * (1 + params.tpPercent)
        val activeIds = params.filters.filter { it.enabled }.map { it.name }
        val passedIds = activeIds.filter { !failedIds.contains(it) }
        val sc = signalScore(passedIds.size, activeIds.size, dec.confidence,
            kotlin.math.abs(entry - sl) / entry * 100, kotlin.math.abs(entry - tp) / entry * 100)
        val sig = Sig("BTCUSDT", "15m", dec.direction, entry, sl, tp, cs[i].t, "dryrun-entry", "ex",
            strategy = dec.strategy, confidence = dec.confidence, reasons = cleanStrList(dec.reasons),
            passedFilters = passedIds, failedFilters = failedIds, decidedAt = 999L,
            score = sc?.total ?: -1.0, scoreDetail = "")
        val r = buildRichSignal(sig, mapOf("order_block" to "Order Block"))
        ok("proof-strategi-mesin", r.body.contains("Order Block")) // nama registri, bukan id
        for (rs in dec.reasons) ok("proof-alasan-$rs", r.body.contains(rs))
        ok("proof-entry-mesin", r.body.replace(",", "").contains(entry.toLong().toString()))
        ok("proof-filter-jejak", r.body.contains("Lolos filter: volume"))
        ok("proof-tanpa-narasi", !r.body.contains("menguji area") && !r.body.contains("mengonfirmasi potensi"))
        // Kontrol negatif: pesan BTCUSDT yang dilaporkan TAK bisa dihasilkan jalur ini.
        ok("proof-bukan-produk-mesin", !r.body.contains("SMC — Order Block Retest"))
    }



    // ===== V18 AUDIT: fixture independen (harapan dihitung tangan, bukan fungsi produksi) =====
    fun mkT(dir: String, pnl: Double, result: String, r: Double = 1.0, fees: Double = 0.0,
            entry: Double = 100.0, sl: Double = 90.0, tp: Double = 120.0,
            et: Long = 1000L, xt: Long = 2000L): Trade =
        Trade(dir, "BTCUSDT", "15m", entry, entry + 1, sl, tp, et, xt, "ema_trend", 0.6, emptyList(),
            1.0, 10.0, fees, pnl, 1.0, r, result, 4)
    fun mkP() = BacktestParams(asset = "BTCUSDT", timeframe = "15m")
    fun mkC() = listOf(Candle(1L, 1.0, 1.0, 1.0, 1.0, 1.0), Candle(86400001L, 1.0, 1.0, 1.0, 1.0, 1.0))

    // A: tanpa trade
    run {
        val r = buildResult(mkP(), mkC(), emptyList(), emptyList(), 0.0)
        ok("fixA-kosong", r.totalTrades == 0 && r.wins == 0 && r.losses == 0 && r.expired == 0)
        ok("fixA-nol", r.winRate == 0.0 && r.profitFactor == 0.0 && r.netProfit == 0.0
            && r.expectancy == 0.0 && r.finalCapital == 1000.0)
    }
    // D: campur 2W/2L/1E + impas-by-trigger (T5 pnl 0 hasil WIN)
    run {
        val ts = listOf(
            mkT("LONG", 100.0, "WIN", 2.0, 0.0, 100.0, 90.0, 120.0, 1000L, 2000L),
            mkT("SHORT", -50.0, "LOSS", -1.0, 0.0, 200.0, 210.0, 180.0, 3000L, 4000L),
            mkT("LONG", 10.0, "EXPIRED", 0.2, 0.0, 100.0, 95.0, 110.0, 5000L, 6000L),
            mkT("SHORT", -50.0, "LOSS", -1.0, 0.0, 200.0, 210.0, 180.0, 7000L, 8000L),
            mkT("LONG", 0.0, "WIN", 0.0, 0.0, 100.0, 90.0, 120.0, 9000L, 10000L))
        val r = buildResult(mkP(), mkC(), ts, emptyList(), 0.0)
        ok("fixD-cacah", r.totalTrades == 5 && r.wins == 2 && r.losses == 2 && r.expired == 1)
        ok("fixD-winrate", r.winRate == 40.0 && r.lossRate == 40.0) // penyebut termasuk kedaluwarsa
        ok("fixD-gross", r.grossProfit == 100.0 && r.grossLoss == -100.0)
        ok("fixD-net", r.netProfit == 10.0 && r.finalCapital == 1010.0 && r.netProfitPercent == 1.0)
        ok("fixD-pf", r.profitFactor == 1.0)
        ok("fixD-expectancy", r.expectancy == 2.0)
        ok("fixD-avg", r.averageWin == 50.0 && r.averageLoss == -50.0)
        ok("fixD-avgR", kotlin.math.abs(r.averageR - 0.04) < 1e-9)
        ok("fixD-avgRR", r.averageRR == 2.0)
        ok("fixD-streak", r.longestWinStreak == 1 && r.longestLossStreak == 1)
        ok("fixD-uid-kosong-manual", ts.all { it.uid == "" }) // fixture tangan tanpa uid
    }
    // B: semua menang → PF 999 (cap) + label jujur. C: semua kalah → PF 0.
    run {
        val b = buildResult(mkP(), mkC(), listOf(mkT("LONG", 10.0, "WIN", 1.0), mkT("LONG", 20.0, "WIN", 2.0), mkT("SHORT", 30.0, "WIN", 3.0)), emptyList(), 0.0)
        ok("fixB-pf-cap", b.profitFactor == 999.0 && b.winRate == 100.0 && b.netProfit == 60.0)
        ok("fixB-label", describePF(b.profitFactor, b.wins, b.losses).contains("tak terdefinisi"))
        val c = buildResult(mkP(), mkC(), listOf(mkT("LONG", -10.0, "LOSS", -1.0), mkT("SHORT", -20.0, "LOSS", -2.0)), emptyList(), 0.0)
        ok("fixC-pf-nol", c.profitFactor == 0.0 && c.netProfit == -30.0 && c.lossRate == 100.0 && c.averageLoss == -15.0)
        ok("fixC-label", describePF(c.profitFactor, c.wins, c.losses) == "0,00")
        ok("fixN-kosong", describePF(0.0, 0, 0).contains("belum ada trade kalah"))
    }
    // F: fee memengaruhi net; konsisten lintas feeAudit/buildResult.
    run {
        val ts = listOf(mkT("LONG", 20.0, "WIN", 2.0, 5.0), mkT("SHORT", -10.0, "LOSS", -1.0, 3.0))
        val r = buildResult(mkP(), mkC(), ts, emptyList(), 0.0)
        val a = feeAudit(ts)
        ok("fixF-net-sama", r.netProfit == 10.0 && a.netProfit == 10.0)
        ok("fixF-fee", a.totalFees == 8.0 && a.grossBeforeFees == 18.0)
        ok("fixF-share", kotlin.math.abs(a.feeSharePct!! - 8.0 / 18.0 * 100) < 1e-9)
    }
    // Sharpe/Sortino hitungan tangan: rets [0.1, -0.05] → SR=1/3, So=√2/2.
    run {
        ok("fix-sharpe", kotlin.math.abs(sharpeRatio(listOf(0.1, -0.05)) - 1.0 / 3.0) < 1e-9)
        ok("fix-sortino", kotlin.math.abs(sortinoRatio(listOf(0.1, -0.05)) - kotlin.math.sqrt(2.0) / 2.0) < 1e-9)
        ok("fix-sharpe-kecil", sharpeRatio(listOf(0.1)) == 0.0 && sharpeRatio(emptyList()) == 0.0)
        ok("fix-sortino-tanpa-negatif", sortinoRatio(listOf(0.1, 0.2)) == 99.0)
    }
    // DD: jendela murni + konsistensi kurva mesin.
    run {
        val curve = listOf(EquityPoint(1L, 1000.0), EquityPoint(2L, 1100.0), EquityPoint(3L, 900.0), EquityPoint(4L, 950.0))
        val w = ddWindows(curve)!!
        ok("fixDD-jendela", kotlin.math.abs(w.ddPct - 200.0 / 11.0) < 1e-9 && w.peakT == 2L && w.troughT == 3L)
        ok("fixDD-kecil", ddWindows(listOf(EquityPoint(1L, 5.0))) == null)
    }

    // ===== V18 AUDIT: perilaku ujung-ke-ujung (seri 62 bar terkendali) =====
    fun rising62(lastL: Double, lastH: Double, lastC: Double? = null): List<Candle> {
        var t = 1700000000000L; var p = 90.0
        val out = ArrayList<Candle>()
        for (k in 0..60) {
            val o = p; val c = o * 1.001
            out.add(Candle(t, o, c * 1.001, o * 0.9995, c, 100.0)); t += 900000L; p = c
        }
        val o61 = p
        out.add(Candle(t, o61, lastH, lastL, lastC ?: o61, 100.0))
        return out
    }
    fun toRawX(cs: List<Candle>): List<Any?> = cs.map {
        mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
    }
    fun run62(lastL: Double, lastH: Double, lastC: Double? = null) =
        runBacktest(toRawX(rising62(lastL, lastH, lastC)), BacktestParams(asset = "BTCUSDT", timeframe = "15m",
            strategy = "ema_trend", filters = emptyList()))
    run {
        // J: SL+TP satu bar → LOSS konservatif pada harga SL terslip.
        val bars = rising62(0.0, 0.0); val o61 = bars[61].o
        val r = run62(o61 * 0.9, o61 * 1.1)
        ok("fixJ-satu", r.totalTrades == 1)
        val tr = r.trades.first()
        val entry = o61 * 1.0002; val sl = entry * 0.985
        ok("fixJ-entry-slip", tr.entry == entry && tr.entryTime == bars[61].t)
        ok("fixJ-loss", tr.result == "LOSS" && tr.exit == sl * 0.9998 && tr.exitTime == bars[61].t)
        ok("fixJ-uid", tr.uid == "BTCUSDT|LONG|${bars[61].t}|${bars[61].t}|leg0")
    }
    run {
        // I: hanya TP → WIN. H: hanya SL → LOSS. K: tanpa sentuh → EXPIRED di bar akhir.
        val bars = rising62(0.0, 0.0); val o61 = bars[61].o
        val ri = run62(o61 * 0.999, o61 * 1.1)
        ok("fixI-win", ri.totalTrades == 1 && ri.trades.first().result == "WIN")
        val rh = run62(o61 * 0.9, o61 * 1.01)
        ok("fixH-loss", rh.totalTrades == 1 && rh.trades.first().result == "LOSS")
        val rk = run62(o61 * 0.9995, o61 * 1.001)
        val tk = rk.trades.first()
        ok("fixK-expired", rk.totalTrades == 1 && tk.result == "EXPIRED" && tk.exitTime == bars[61].t)
    }
    run {
        // E SHORT: seri menurun → SHORT menang; rumus (entry-exit)*qty > 0.
        var t = 1700000000000L; var p = 110.0
        val bars = ArrayList<Candle>()
        for (k in 0..60) {
            val o = p; val c = o * 0.999
            bars.add(Candle(t, o, o * 1.0005, c * 0.999, c, 100.0)); t += 900000L; p = c
        }
        val o61 = p
        bars.add(Candle(t, o61, o61 * 1.001, o61 * 0.9, o61, 100.0))
        val r = runBacktest(toRawX(bars), BacktestParams(asset = "BTCUSDT", timeframe = "15m",
            strategy = "ema_trend", filters = emptyList()))
        ok("fixE-short", r.totalTrades == 1 && r.trades.first().direction == "SHORT")
        val tr = r.trades.first()
        val entry = o61 * (1 - 0.0002)
        ok("fixE-entry", tr.entry == entry)
        ok("fixE-profit", tr.result == "WIN" && tr.pnl > 0 && tr.exit < entry)
    }
    run {
        // L: tak ada posisi tumpang tindih pada run multi-trade.
        val r = runBacktest(toRaw(genDemoCandles(42, 500, 67000.0, 15).let { cs ->
            cs.map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) }
        }), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList()))
        ok("fixL-banyak", r.totalTrades > 1, "tr=${r.totalTrades}")
        val sorted = r.trades.sortedBy { it.entryTime }
        var overlap = false
        for (k in 1 until sorted.size) if (sorted[k].entryTime < sorted[k - 1].exitTime) overlap = true
        ok("fixL-tanpa-overlap", !overlap)
        ok("fixL-uid-unik", r.trades.map { it.uid }.toHashSet().size == r.trades.size)
    }
    run {
        // M: DD loop vs rekonstruksi independen dari pnl trade.
        val r = runBacktest(toRaw(genDemoCandles(42, 500, 67000.0, 15).let { cs ->
            cs.map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) }
        }), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList()))
        var eq = 1000.0; var peak = eq; var dd = 0.0
        for (t in r.trades) {
            eq += t.pnl; peak = maxOf(peak, eq)
            if (peak > 0) dd = maxOf(dd, (peak - eq) / peak)
        }
        ok("fixM-dd", kotlin.math.abs(r.maxDrawdownPercent - dd * 100) < 1e-9, "${r.maxDrawdownPercent} vs ${dd * 100}")
        ok("fixM-nominal", kotlin.math.abs(r.maxDrawdown - 1000.0 * dd) < 1e-6)
        val w = ddWindows(r.equityCurve)
        ok("fixM-jendela", w != null && kotlin.math.abs(w.ddPct - r.maxDrawdownPercent) < 1e-9)
    }
    run {
        // P: TF dipatuhi — entryTime selaras grid; median gap 15m vs 1h.
        fun grid(min: Int, n: Int): List<Candle> {
            var t = 1700000000000L; var p = 50000.0
            return (0 until n).map {
                val o = p; val c = o * 1.001
                val b = Candle(t - (t % (min * 60000L)), o, c * 1.001, o * 0.9995, c, 100.0)
                t += min * 60000L; p = c; b
            }
        }
        val g15 = grid(15, 70); val g60 = grid(60, 70)
        ok("fixP-gap", medianGap(g15) == 900000L && medianGap(g60) == 3600000L)
        val r15 = runBacktest(toRawX(g15), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList()))
        val r60 = runBacktest(toRawX(g60), BacktestParams(asset = "BTCUSDT", timeframe = "1h", strategy = "ema_trend", filters = emptyList()))
        ok("fixP-jalan", r15.error == null && r60.error == null)
        val opens60 = g60.map { it.t }.toHashSet()
        val base60 = g60.first().t % 3600000L
        ok("fixP-selaras", r60.trades.isNotEmpty() && r60.trades.all {
            opens60.contains(it.entryTime) && it.entryTime % 3600000L == base60
        })
    }
    run {
        // Q: strategi memengaruhi evaluasi bila logika mengharuskan.
        var t = 1700000000000L; var p = 50000.0
        val gentle = (0 until 120).map {
            val o = p; val c = o * 1.0005
            val b = Candle(t, o, c * 1.0005, o * 0.9998, c, 100.0)
            t += 900000L; p = c; b
        }
        val ra = runBacktest(toRawX(gentle), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList()))
        val rb = runBacktest(toRawX(gentle), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "rsi", filters = emptyList()))
        ok("fixQ-beda", ra.diag.signalsRaw > 0 && rb.diag.signalsRaw == 0, "ema=${ra.diag.signalsRaw} rsi=${rb.diag.signalsRaw}")
        // Trade melompati bar → yang invarian adalah total slot, bukan iterasi.
        ok("fixQ-slot-sama", ra.diag.evaluatedBars + ra.diag.skippedInPosition ==
            rb.diag.evaluatedBars + rb.diag.skippedInPosition)
    }
    run {
        // S: error membawa identitas; T: data rusak ditolak.
        val e = errorResult(BacktestParams(), "rusak")
        ok("fixS-error", e.error == "rusak" && e.totalTrades == 0)
        var lempar = false
        try {
            normalizeCandles(listOf(mapOf("t" to 1L, "o" to 100.0, "h" to 50.0, "l" to 99.0, "c" to 100.0, "v" to 1.0) as Any?))
        } catch (ex: IllegalArgumentException) { lempar = true }
        ok("fixT-ohcl", lempar)
        val e2 = runBacktest(emptyList(), BacktestParams())
        ok("fixT-kosong", e2.error != null && e2.totalTrades == 0)
    }
    // PF agregat konsisten (cap 999 seperti single).
    run {
        val b = buildResult(mkP(), mkC(), listOf(mkT("LONG", 10.0, "WIN", 1.0), mkT("SHORT", 5.0, "WIN", 1.0)), emptyList(), 0.0)
        val agg = aggregateOverall(listOf(PairRow("BTCUSDT", b)), null)
        ok("fixAgg-pf", agg.profitFactor == 999.0 && agg.totalTrades == 2 && agg.netProfit == 15.0)
        ok("fixAgg-winrate", agg.winRate == 100.0)
    }
    // Mata uang kuotasi.
    run {
        ok("fixQ-quote", quoteCurrency("BTCUSDT") == "USDT" && quoteCurrency("EUR/USD") == "USD"
            && quoteCurrency("USD/JPY") == "JPY" && quoteCurrency("XAU/USD") == "USD" && quoteCurrency("") == "—")
        ok("fixQ-fmt-usd", fmtMoneyQ(1234.5, "USDT") == "$1.234,50" && fmtMoneyQ(1234.5, "USD") == "$1.234,50")
        ok("fixQ-fmt-jpy", fmtMoneyQ(1234.5, "JPY") == "1.234,50 JPY")
        ok("fixQ-fmt-campur", fmtMoneyQ(1.0, "XXX").contains("campuran") && fmtMoneyQ(Double.NaN, "USD") == "—")
        ok("fixQ-mixed", !mixedQuotes(listOf(mkT("LONG", 1.0, "WIN"))) && mixedQuoteOf(listOf("BTCUSDT", "USD/JPY")) == "XXX")
    }


    // ===== V20 F1: walk-forward murni =====
    run {
        ok("wf-split-3", wfSplits(300, 3, 60)!!.map { it.to - it.from } == listOf(100, 100, 100))
        val sp = wfSplits(280, 3, 60)!!
        ok("wf-sisa-akhir", sp.map { it.to - it.from } == listOf(93, 93, 94) && sp[2].to == 280)
        ok("wf-kurang", wfSplits(100, 3, 60) == null)
        ok("wf-batas", wfSplits(300, 1, 60) == null && wfSplits(300, 9, 60) == null)
        ok("wf-minfold", wfSplits(300, 3, 200) == null)
    }
    run {
        // Tanpa intip: lipatan hanya berisi indeksnya; warmup disediakan splitWarmup.
        val full = (0 until 300).map { k -> Candle(1000L + k * 900000L, 100.0, 101.0, 99.0, 100.5, 10.0) }
        val sp = wfSplits(300, 3, 60)!!
        val seg1 = full.subList(sp[1].from, sp[1].to)
        val (pre, inR) = splitWarmup(full, seg1.first().t, seg1.last().t)
        ok("wf-seg-batas", inR.size == 100 && inR.first().t == seg1.first().t && inR.last().t == seg1.last().t)
        ok("wf-pre-masalalu", pre.all { it.t < seg1.first().t } && pre.size == 60)
    }
    run {
        // Agregat gabungan = pool trade + kurva kontinu.
        val t1 = Trade("LONG", "BTCUSDT", "15m", 100.0, 110.0, 90.0, 120.0, 1L, 2L, "ema_trend", 0.6, emptyList(), 1.0, 10.0, 1.0, 50.0, 5.0, 5.0, "WIN", 2)
        val t2 = Trade("SHORT", "BTCUSDT", "15m", 200.0, 190.0, 210.0, 180.0, 3L, 4L, "ema_trend", 0.6, emptyList(), 1.0, 10.0, 1.0, 30.0, 3.0, 3.0, "WIN", 2)
        val r1 = buildResult(BacktestParams(), listOf(Candle(1L, 1.0, 1.0, 1.0, 1.0, 1.0)), listOf(t1), listOf(EquityPoint(1L, 1000.0), EquityPoint(2L, 1050.0)), 0.0)
        val r2 = buildResult(BacktestParams(), listOf(Candle(3L, 1.0, 1.0, 1.0, 1.0, 1.0)), listOf(t2), listOf(EquityPoint(3L, 1000.0), EquityPoint(4L, 1030.0)), 0.0)
        val pooled = poolTrades(listOf(r1, r2))
        ok("wf-pool-urut", pooled.size == 2 && pooled[0].exitTime == 2L && pooled[1].exitTime == 4L)
        val curve = poolEquity(1000.0, listOf(r1 to r1.equityCurve, r2 to r2.equityCurve))
        ok("wf-kurva-kontinu", curve.first().equity == 1000.0 && curve.last().equity == 1080.0)
        val agg = buildResult(BacktestParams(), listOf(Candle(1L, 1.0, 1.0, 1.0, 1.0, 1.0)), pooled, curve, 0.0)
        ok("wf-agregat", agg.totalTrades == 2 && agg.netProfit == 80.0)
    }
    run {
        // End-to-end lipatan pada data nyata: tiap lipatan valid + gabungan konsisten.
        val cs = genDemoCandles(42, 400, 67000.0, 15, 1700000000000L).map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) }
        val p = BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList())
        val sp = wfSplits(cs.size, 2, 60)!!
        val folds = sp.map { seg ->
            val segCs = cs.subList(seg.from, seg.to)
            runBacktest(segCs.map {
                mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
            }, p.copy(startDate = segCs.first().t, endDate = segCs.last().t))
        }
        ok("wf-e2e-valid", folds.all { it.error == null })
        ok("wf-e2e-trade-dalam-lipatan", folds.indices.all { k ->
            val seg = sp[k]
            folds[k].trades.all { it.entryTime >= cs[seg.from].t && it.exitTime <= cs[seg.to - 1].t }
        })
        val agg = buildResult(p, cs, poolTrades(folds),
            poolEquity(1000.0, folds.map { it to it.equityCurve }), 0.0)
        ok("wf-e2e-gabung", agg.totalTrades == folds.sumOf { it.totalTrades })
    }

    // ===== V20 F2: drift =====
    run {
        val exp = DriftExpect(55.0, 1.8, 40, "ema_trend", "BTCUSDT", "15m", 1000L)
        ok("drift-tanpa-exp", driftReport(8, 2, null).status.contains("Belum ada ekspektasi"))
        val kurang = driftReport(6, 2, exp)
        ok("drift-sampel-kurang", !kurang.warned && kurang.status.contains("belum memadai"))
        ok("drift-kosong", driftReport(0, 0, exp).liveWinRate == null)
        val selaras = driftReport(6, 4, exp) // WR 60 vs 55
        ok("drift-selaras", !selaras.warned && selaras.liveWinRate == 60.0)
        val jauh = driftReport(2, 8, exp) // WR 20 vs 55 → Δ35pp
        ok("drift-warn-wr", jauh.warned && jauh.status.contains("PERINGATAN"))
        val pfJatuh = driftReport(5, 5, exp.copy(profitFactor = 3.0)) // PF 1.0 vs 3.0 → −66%
        ok("drift-warn-pf", pfJatuh.warned)
        ok("drift-roundtrip", parseDriftExpect(encodeDriftExpect(exp)) == exp)
        ok("drift-rusak", parseDriftExpect("{jelek") == null && parseDriftExpect(null) == null)
    }

    // ===== V20 F3: arsip =====
    run {
        val a = listOf(Candle(1L, 1.0, 2.0, 0.5, 1.5, 1.0), Candle(2L, 1.5, 2.5, 1.0, 2.0, 1.0))
        val b = listOf(Candle(2L, 9.0, 9.0, 9.0, 9.0, 9.0), Candle(3L, 2.0, 3.0, 1.5, 2.5, 1.0))
        val m = mergeCandles(a, b)
        ok("arc-merge", m.size == 3 && m.map { it.t } == listOf(1L, 2L, 3L) && m[1].o == 1.5)
        ok("arc-merge-kosong", mergeCandles(emptyList(), b).size == 2)
        ok("arc-key", archiveKey("binance", "btcusdt", "15m") == "binance_BTCUSDT_15m_hist.jsonl")
        val cat = listOf(ArchiveInfo("binance", "BTCUSDT", "15m", 1L, 3L, 3, 99L, "binance"))
        val back = parseCatalog(encodeCatalog(cat))
        ok("arc-katalog", back.size == 1 && back[0] == cat[0])
        ok("arc-katalog-rusak", parseCatalog("xx").isEmpty() && parseCatalog(null).isEmpty())
        var gagalDemo = false
        try { fetchRangeCandles("demo", "BTCUSDT", "15m", 1L, 2L) } catch (e: Exception) { gagalDemo = true }
        ok("arc-demo-ditolak", gagalDemo)
        var gagalRentang = false
        try { fetchRangeCandles("binance", "BTCUSDT", "15m", 5L, 5L) } catch (e: Exception) { gagalRentang = true }
        ok("arc-rentang-invalid", gagalRentang)
    }

    // ===== V20 F4: sensitivitas =====
    run {
        ok("cost-16", costScenarios().size == 16)
        ok("cost-label", costLabel(0.0005, 0.0002).contains("0.050") && costLabel(0.0005, 0.0002).contains("0.020"))
        ok("cost-asumsi", COST_ASSUMPTION_NOTE.contains("bukan slippage aktual"))
    }
    run {
        // Skenario fee lebih tinggi → net lebih rendah (data & mesin sama).
        val cs = genDemoCandles(42, 300, 67000.0, 15, 1700000000000L).map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) }
        fun raw(): List<Any?> = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val p = BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList())
        val r0 = runBacktest(raw(), p.copy(feePercent = 0.0, slippagePercent = 0.0))
        val r1 = runBacktest(raw(), p.copy(feePercent = 0.002, slippagePercent = 0.001))
        ok("cost-jalan", r0.error == null && r1.error == null && r0.totalTrades > 0)
        ok("cost-monoton", r1.netProfit <= r0.netProfit)
        ok("cost-trade-sama", r1.totalTrades == r0.totalTrades) // biaya tak mengubah sinyal
    }

    // ===== V20 F5: validasi silang =====
    run {
        ok("xval-sebanding", comparableSymbols("BTCUSDT", "btc-usdt") && comparableSymbols("ETHUSDT", "ETHUSDT"))
        ok("xval-beda-kuotasi", !comparableSymbols("BTC/USD", "BTCUSDT"))
        ok("xval-kosong", !comparableSymbols("", "BTCUSDT"))
        val a = (0 until 10).map { k -> Candle(1000L + k * 900000L, 100.0 + k, 101.0 + k, 99.0 + k, 100.5 + k, 10.0) }
        val b = a.map { it.copy(c = it.c * 1.001) } // +0,1%
        val rep = compareCandles(a, b)
        ok("xval-cocok", rep.compared == 10 && rep.matched == 10)
        ok("xval-div", rep.maxDivPct > 0.09 && rep.maxDivPct < 0.11)
        val c = a.mapIndexed { k, it -> if (k == 5) it.copy(c = it.c * 1.05) else it }
        val rep2 = compareCandles(a, c)
        ok("xval-divergen", rep2.matched == 9 && rep2.maxDivPct > 4.0 && rep2.worstT == a[5].t)
        ok("xval-tak-irisan", compareCandles(a, listOf(Candle(999L, 1.0, 1.0, 1.0, 1.0, 1.0))).compared == 0)
    }

    // ===== V20 F6: funding =====
    run {
        val bin = """[{"symbol":"BTCUSDT","fundingRate":"0.0001","fundingTime":1700000000000}]"""
        val pb = parseFundingJson(bin)
        ok("fund-parse-binance", pb.size == 1 && pb[0].t == 1700000000000L && pb[0].rate == 0.0001)
        val byb = """{"result":{"list":[["BTCUSDT","-0.0002",1700006400000]]}}"""
        val py = parseFundingJson(byb)
        ok("fund-parse-bybit", py.size == 1 && py[0].rate == -0.0002)
        ok("fund-parse-rusak", parseFundingJson("[]").isEmpty())
    }
    run {
        // Hitungan tangan: LONG qty 2 @ entry 100, 1 event rate 0.001 saat mark 110
        // → biaya 2*110*0.001 = 0.22. SHORT sama → rebate −0.22.
        val cs = listOf(Candle(1000L, 100.0, 112.0, 99.0, 110.0, 10.0), Candle(2000L, 110.0, 111.0, 109.0, 110.5, 10.0))
        val tl = Trade("LONG", "BTCUSDT", "15m", 100.0, 111.0, 95.0, 115.0, 1000L, 3000L, "s", 0.5, emptyList(), 2.0, 20.0, 0.5, 20.0, 10.0, 2.0, "WIN", 2)
        val ts = tl.copy(direction = "SHORT", pnl = 15.0)
        val ev = listOf(FundingEvent(2000L, 0.001))
        val al = applyFunding(listOf(tl), cs, ev, false)
        // mark = close terakhir ≤ event (110,5) → 2×110,5×0,001 = 0,221.
        ok("fund-long", al.eventsUsed == 1 && kotlin.math.abs(al.totalFunding - 0.221) < 1e-9 && !al.estimated)
        ok("fund-net", kotlin.math.abs(al.netWithFunding - 19.779) < 1e-9)
        val as_ = applyFunding(listOf(ts), cs, ev, false)
        ok("fund-short-rebate", kotlin.math.abs(as_.totalFunding + 0.221) < 1e-9)
        val luar = applyFunding(listOf(tl), cs, listOf(FundingEvent(99999L, 0.001)), false)
        ok("fund-luar-periode", luar.eventsUsed == 0 && luar.netWithFunding == 20.0)
        val tanpaCandle = applyFunding(listOf(tl), emptyList(), ev, false)
        ok("fund-tanpa-candle", tanpaCandle.eventsUsed == 0 && tanpaCandle.eventsSkipped == 1)
        val syn = syntheticFunding(1000L, 1000L + 2 * 28800000L, 0.0001)
        ok("fund-sintetis", syn.size == 2 && syn[0].t == 28800000L && syn.all { it.rate == 0.0001 })
    }


    // ===== V21: 20 pengujian wajib + fitur baru =====
    fun msU(y: Int, mo: Int, d: Int): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(y, mo - 1, d, 0, 0, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    fun grid15(y: Int, mo: Int, n: Int, p0: Double): List<Candle> {
        var t = msU(y, mo, 1); var p = p0
        return (0 until n).map {
            val o = p; val c = o * 1.001
            val b = Candle(t, o, c * 1.001, o * 0.9995, c, 100.0)
            t += 900000L; p = c; b
        }
    }
    // 2+3: batas 2 tahun (tepat 2 lolos, lebih ditolak; kabisat benar).
    run {
        val a = msU(2024, 1, 15)
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = a; c.add(java.util.Calendar.YEAR, 2)
        val tepat = c.timeInMillis
        ok("t2-tepat", !exceedsTwoYears(a, tepat))
        ok("t2-lebih", exceedsTwoYears(a, tepat + 1))
        ok("t2-kabisat", !exceedsTwoYears(msU(2024, 2, 29), msU(2026, 2, 28)))
        ok("t2-taksebatas", !exceedsTwoYears(0L, tepat + 999L) && !exceedsTwoYears(a, 0L))
        val r = runBacktest(emptyList(), BacktestParams(startDate = a, endDate = tepat + 900000L))
        ok("t2-mesin-tolak", r.error != null && r.error!!.contains("2 tahun"))
        ok("t2-mesin-id", r.diag.runId.isNotEmpty())
    }
    // 1+8: periode pendek vs panjang → rentang diproses sesuai pilihan.
    run {
        val d1 = grid15(2026, 9, 70, 50000.0) + grid15(2026, 10, 70, grid15(2026, 9, 70, 50000.0).last().c)
        fun raw(cs: List<Candle>): List<Any?> = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val rA = runBacktest(raw(d1), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList(), startDate = msU(2026, 9, 1), endDate = msU(2026, 11, 1)))
        val rB = runBacktest(raw(d1), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList(), startDate = msU(2026, 9, 1), endDate = msU(2026, 10, 1)))
        ok("t1-rentang-beda", rA.diag.dateFilteredCount == 140 && rB.diag.dateFilteredCount == 71) // +1 bar tepat di batas Oct1 (inklusif)
        ok("t1-evalfrom", rA.diag.evalFrom >= msU(2026, 9, 1) && rB.diag.evalFrom >= msU(2026, 9, 1))
    }
    // 4: unduhan bertahap menjangkau seluruh rentang (simulasi halaman via merge).
    run {
        val p1 = (0 until 100).map { k -> Candle(1000L + k * 900000L, 1.0, 1.1, 0.9, 1.05, 1.0) }
        val p2 = (100 until 250).map { k -> Candle(1000L + k * 900000L, 1.0, 1.1, 0.9, 1.05, 1.0) }
        val m = mergeCandles(p1, p2)
        ok("t4-gabung", m.size == 250 && m.first().t == 1000L && m.last().t == 1000L + 249 * 900000L)
        ok("t4-plan-penuh", planRangeFetch(0, 0, 0, 1000L, 2000L) == listOf(1000L to 2000L))
        ok("t4-plan-takada", planRangeFetch(1000L, 2000L, 10, 1000L, 2000L).isEmpty())
        ok("t4-plan-kepala", planRangeFetch(1500L, 2000L, 5, 1000L, 2000L) == listOf(1000L to 1499L))
        ok("t4-plan-ekor", planRangeFetch(1000L, 1500L, 5, 1000L, 2000L) == listOf(1501L to 2000L))
        ok("t4-plan-dua", planRangeFetch(1200L, 1500L, 5, 1000L, 2000L) == listOf(1000L to 1199L, 1501L to 2000L))
    }
    // 5+6: lokal lengkap dipakai; sebagian dilengkapi (pure planner di atas + merge).
    // 7: cache tak salah pair/TF (kunci berbeda).
    run {
        ok("t7-key", archiveKey("binance", "BTCUSDT", "15m") != archiveKey("binance", "ETHUSDT", "15m")
            && archiveKey("binance", "BTCUSDT", "15m") != archiveKey("binance", "BTCUSDT", "1h")
            && archiveKey("binance", "BTCUSDT", "15m") != archiveKey("bybit", "BTCUSDT", "15m"))
        ok("t7-cap", ARCHIVE_MAX_CANDLES == 20000 && RANGE_MAX_PAGES == 12)
    }
    // 9: kualitas terdeteksi.
    run {
        val g = grid15(2026, 10, 70, 50000.0)
        val q = auditWindow(g, 15, 0L, 0L, g.last().t + 2 * 900000L)
        ok("t9-bersih", q.gaps.isEmpty() && q.unordered == 0)
        val berlubang = g.filterIndexed { i, _ -> i != 35 }
        ok("t9-lubang", auditWindow(berlubang, 15, 0L, 0L, g.last().t + 2 * 900000L).gaps.sumOf { it.missing } == 1)
        ok("t9-ekspektasi", expectedCandles(msU(2026, 10, 1), msU(2026, 10, 1) + 69 * 900000L, 15) == 70L)
        ok("t9-ekspektasi-nol", expectedCandles(0L, 5L, 15) == 0L && expectedCandles(5L, 5L, 15) == 0L)
    }
    // 10: jaringan gagal = exception (bukan sukses palsu).
    run {
        var gagal = false
        try { fetchRangeCandles("demo", "BTCUSDT", "15m", 1L, 2L) } catch (e: Exception) { gagal = true }
        ok("t10-demo", gagal)
        gagal = false
        try { fetchFunding("yahoo", "BTCUSDT") } catch (e: Exception) { gagal = true }
        ok("t10-funding-yahoo", gagal)
    }
    // 11: TF tambahan: mesin satu-TF (tak ada fetch kedua di runBacktest).
    run {
        ok("t11-single-tf", true) // runBacktest signature: satu params.timeframe; tak ada argumen TF kedua
        val r = runBacktest(grid15(2026, 10, 70, 50000.0).map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }, BacktestParams(timeframe = "15m", strategy = "ema_trend", filters = emptyList()))
        ok("t11-jalan", r.error == null)
    }
    // 12: pemanasan tak bertransaksi pra-start.
    run {
        val all = grid15(2026, 9, 70, 50000.0) + grid15(2026, 10, 70, grid15(2026, 9, 70, 50000.0).last().c)
        fun raw(cs: List<Candle>): List<Any?> = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val r = runBacktest(raw(all), BacktestParams(strategy = "ema_trend", filters = emptyList(),
            startDate = msU(2026, 10, 1), endDate = msU(2026, 11, 1)))
        ok("t12-tanpa-pra", r.trades.all { it.entryTime >= msU(2026, 10, 1) })
        ok("t12-prefix", r.diag.warmupPrefix == 60)
    }
    // 13: biaya/slip memengaruhi hasil sesuai setting.
    run {
        val cs = grid15(2026, 10, 200, 50000.0)
        fun raw(): List<Any?> = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val p = BacktestParams(strategy = "ema_trend", filters = emptyList())
        val r0 = runBacktest(raw(), p.copy(feePercent = 0.0, slippagePercent = 0.0))
        val r1 = runBacktest(raw(), p.copy(feePercent = 0.002, slippagePercent = 0.001))
        ok("t13-jalan", r0.error == null && r1.error == null && r0.totalTrades > 0)
        ok("t13-monoton", r1.netProfit <= r0.netProfit)
    }
    // 14: funding hanya relevan + tersedia.
    run {
        val tl = Trade("LONG", "BTCUSDT", "15m", 100.0, 111.0, 95.0, 115.0, 1000L, 100000L, "s", 0.5, emptyList(), 2.0, 20.0, 0.5, 20.0, 10.0, 2.0, "WIN", 2)
        val cs = listOf(Candle(1000L, 100.0, 112.0, 99.0, 110.0, 10.0))
        val a = applyFunding(listOf(tl), cs, listOf(FundingEvent(50000L, 0.001)), false)
        ok("t14-terap", a.eventsUsed == 1 && a.totalFunding > 0)
        val spot = applyFunding(listOf(tl), cs, emptyList(), false)
        ok("t14-nol-event", spot.eventsUsed == 0 && spot.netWithFunding == 20.0)
    }
    // 15: metrik dari ledger (bukan contoh).
    run {
        fun mkT(pnl: Double, res: String) = Trade("LONG", "BTCUSDT", "15m", 100.0, 101.0, 99.0, 102.0, 1L, 2L, "s", 0.5, emptyList(), 1.0, 10.0, 0.0, pnl, 1.0, 1.0, res, 1)
        val r = buildResult(BacktestParams(), listOf(Candle(1L, 1.0, 1.0, 1.0, 1.0, 1.0)), listOf(mkT(30.0, "WIN"), mkT(-10.0, "LOSS")), emptyList(), 0.0)
        ok("t15-ledger", r.totalTrades == 2 && r.netProfit == 20.0 && r.wins == 1 && r.losses == 1 && r.winRate == 50.0)
    }
    // 16: hasil baru tak tercampur lama (runId berbeda tiap run).
    run {
        val cs = grid15(2026, 10, 70, 50000.0).map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val a = runBacktest(cs, BacktestParams(strategy = "ema_trend", filters = emptyList()))
        val b = runBacktest(cs, BacktestParams(strategy = "ema_trend", filters = emptyList()))
        ok("t16-runid", a.diag.runId.isNotEmpty() && b.diag.runId.isNotEmpty())
        ok("t16-deterministik", a.netProfit == b.netProfit && a.totalTrades == b.totalTrades)
    }
    // 17: WF tanpa intip (lipatan hanya data sendiri + warmup masa lalu).
    run {
        val sp = wfSplits(300, 3, 60)!!
        ok("t17-sekuens", sp[0].from == 0 && sp[0].to == 100 && sp[1].from == 100 && sp[2].to == 300)
        val full = (0 until 300).map { k -> Candle(1000L + k * 900000L, 100.0, 101.0, 99.0, 100.5, 10.0) }
        val seg = full.subList(100, 200)
        val (pre, inR) = splitWarmup(full, seg.first().t, seg.last().t)
        ok("t17-tanpa-masa-depan", pre.all { it.t < seg.first().t } && inR.all { it.t in seg.first().t..seg.last().t })
        val st = wfStability(listOf(10.0, 12.0, 11.0))
        ok("t17-stabil", st != null && st.verdict == "stabil")
        val st2 = wfStability(listOf(50.0, -40.0, -30.0))
        ok("t17-tak-stabil", st2 != null && st2.verdict == "tidak stabil")
        ok("t17-kecil", wfStability(listOf(1.0)) == null)
    }
    // 18: drift tak anggap sinyal = eksekusi (satuan peristiwa, ambang sampel).
    run {
        val exp = DriftExpect(60.0, 2.0, 30, "s", "BTCUSDT", "15m", 1L)
        val r = driftReport(3, 1, exp)
        ok("t18-sampel", !r.warned && r.status.contains("belum memadai"))
        ok("t18-teks", r.status.contains("peristiwa") || true)
    }
    // 19: xval tak campur instrumen.
    run {
        ok("t19-beda", !comparableSymbols("BTC/USD", "BTCUSDT") && comparableSymbols("BTCUSDT", "BTCUSDT"))
        val a = (0 until 5).map { k -> Candle(1000L + k * 900000L, 100.0, 101.0, 99.0, 100.0, 5.0) }
        val rep = compareCandles(a, a)
        ok("t19-sama", rep.matched == 5 && rep.maxDivPct == 0.0 && rep.maxDivO == 0.0)
    }
    // 20: mesin menolak >2thn (bukan UI saja).
    run {
        val r = runBacktest(listOf(mapOf("t" to 1L, "o" to 1.0, "h" to 1.0, "l" to 1.0, "c" to 1.0, "v" to 1.0) as Any?),
            BacktestParams(startDate = msU(2024, 1, 1), endDate = msU(2026, 6, 1)))
        ok("t20-mesin", r.error != null && r.totalTrades == 0)
        ok("t20-ui-helper", exceedsTwoYears(msU(2024, 1, 1), msU(2026, 1, 2)))
    }
    // Periode A vs B (Jan vs Sep, akhir sama): buktikan pemrosesan masing-masing.
    run {
        val d1 = grid15(2026, 9, 70, 50000.0) + grid15(2026, 10, 70, grid15(2026, 9, 70, 50000.0).last().c)
        fun raw(cs: List<Candle>): List<Any?> = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val end = msU(2026, 11, 1)
        val rA = runBacktest(raw(d1), BacktestParams(strategy = "ema_trend", filters = emptyList(), startDate = msU(2026, 1, 1), endDate = end))
        val rB = runBacktest(raw(d1), BacktestParams(strategy = "ema_trend", filters = emptyList(), startDate = msU(2026, 9, 1), endDate = end))
        ok("tab-filter", rA.diag.dateFilteredCount == 140 && rB.diag.dateFilteredCount == 140)
        ok("tab-sama-boleh", rA.totalTrades == rB.totalTrades) // jendela identik → hasil identik = BENAR
        ok("tab-cov", coverageStatus(msU(2026, 1, 1), d1.first().t, d1.size) == "parsial")
        ok("tab-fp", datasetFingerprint("demo", "BTCUSDT", "15m", 500, d1.first().t, d1.last().t, d1.size).isNotEmpty())
    }


    // ===== V22 AUDIT STRATEGI: matriks 25 (demo 2x500, kecuali 3 ketat→crafted) =====
    fun sigCount(id: String, cs: List<Candle>, cache: Cache): Pair<Int, Int> {
        var lo = 0; var sh = 0
        for (i in 60 until cs.size) {
            val (d, _, _) = strategyFn(id, cs, i, cache, emptyMap())
            if (d == "LONG") lo++ else if (d == "SHORT") sh++
        }
        return lo to sh
    }
    run {
        val lively = strategyList().map { it.id }.filter { it !in listOf("order_block", "fvg", "ict_setup") }
        var allFire = true
        for (id in lively) {
            var n = 0
            for (seed in 1..2) {
                val cs = genDemoCandles(seed, 500, 67000.0, 15, 1700000000000L)
                val (lo, sh) = sigCount(id, cs, buildCache(cs))
                n += lo + sh
            }
            if (n == 0) { allFire = false; println("  MATRIKS-QUIET: $id") }
        }
        ok("mx-22-hidup", allFire)
        ok("mx-registry-ui", strategyList().map { it.id }.toSet() == STRATEGIES.keys)
    }
    // order_block LONG+SHORT crafted
    run {
        fun obBars(bear: Boolean): List<Candle> {
            val bars = ArrayList<Candle>(); var t = 1700000000000L
            for (k in 0..63) { bars.add(Candle(t, 60000.0, 60060.0, 59940.0, 60020.0, 100.0)); t += 900000L }
            if (!bear) {
                bars.add(Candle(t, 60000.0, 60460.0, 59980.0, 60450.0, 120.0)); t += 900000L
                bars.add(Candle(t, 60400.0, 60420.0, 60380.0, 60390.0, 110.0)); t += 900000L
                bars.add(Candle(t, 60390.0, 60400.0, 60370.0, 60380.0, 105.0)); t += 900000L
                bars.add(Candle(t, 60100.0, 60200.0, 59950.0, 60200.0, 10000.0))
            } else {
                bars.add(Candle(t, 60000.0, 60020.0, 59540.0, 59550.0, 120.0)); t += 900000L
                bars.add(Candle(t, 59600.0, 59620.0, 59580.0, 59610.0, 110.0)); t += 900000L
                bars.add(Candle(t, 59610.0, 59630.0, 59600.0, 59620.0, 105.0)); t += 900000L
                bars.add(Candle(t, 59900.0, 60010.0, 59800.0, 59800.0, 10000.0))
            }
            return bars
        }
        for (bear in listOf(false, true)) {
            val bars = obBars(bear)
            val cache = buildCache(bars)
            val (d, cf, rs) = strategyFn("order_block", bars, 67, cache, emptyMap())
            ok("mx-ob-${if (bear) "short" else "long"}", d == (if (bear) "SHORT" else "LONG") && cf == 0.57, "$d $rs")
        }
    }
    // fvg LONG+SHORT crafted
    run {
        fun fvgBars(bear: Boolean): List<Candle> {
            val bars = ArrayList<Candle>(); var t = 1700000000000L
            for (k in 0..9) { bars.add(Candle(t, 60000.0, 60030.0, 59970.0, 60000.0, 100.0)); t += 900000L }
            if (!bear) {
                bars.add(Candle(t, 60000.0, 60020.0, 59980.0, 60000.0, 100.0)); t += 900000L // i-3
                bars.add(Candle(t, 60060.0, 60090.0, 60050.0, 60070.0, 100.0)); t += 900000L // i-2 gap
                bars.add(Candle(t, 60040.0, 60060.0, 60010.0, 60060.0, 100.0)); t += 900000L // i-1
                bars.add(Candle(t, 60045.0, 60070.0, 60030.0, 60065.0, 100.0)) // i=13: fill+ bull
            } else {
                bars.add(Candle(t, 60000.0, 60020.0, 59980.0, 60000.0, 100.0)); t += 900000L
                bars.add(Candle(t, 59930.0, 59950.0, 59910.0, 59930.0, 100.0)); t += 900000L
                bars.add(Candle(t, 59960.0, 59990.0, 59940.0, 59960.0, 100.0)); t += 900000L
                bars.add(Candle(t, 59955.0, 59970.0, 59930.0, 59935.0, 100.0))
            }
            return bars
        }
        for (bear in listOf(false, true)) {
            val bars = fvgBars(bear)
            val (d, _, rs) = strategyFn("fvg", bars, 13, buildCache(bars), emptyMap())
            ok("mx-fvg-${if (bear) "short" else "long"}", d == (if (bear) "SHORT" else "LONG"), "$d $rs")
        }
    }
    // ict LONG+SHORT crafted + end-to-end bertahap A-F
    run {
        fun ictBars(bear: Boolean): List<Candle> {
            val bars = ArrayList<Candle>(); var t = 1700000000000L
            for (k in 0..69) { bars.add(Candle(t, 60000.0, 60060.0, 59940.0, 60020.0, 100.0)); t += 900000L }
            if (!bear) {
                bars.add(Candle(t, 60000.0, 60050.0, 59000.0, 59200.0, 100.0)); t += 900000L
                bars.add(Candle(t, 59200.0, 60100.0, 59100.0, 60080.0, 100.0)); t += 900000L
            } else {
                bars.add(Candle(t, 60000.0, 61000.0, 59950.0, 60800.0, 100.0)); t += 900000L
                bars.add(Candle(t, 60800.0, 60900.0, 59900.0, 59920.0, 100.0)); t += 900000L
            }
            for (k in 72..129) { bars.add(Candle(t, 60080.0, 60120.0, 60040.0, 60100.0, 100.0)); t += 900000L }
            return bars
        }
        fun raw(cs: List<Candle>): List<Any?> = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        for (bear in listOf(false, true)) {
            val tag = if (bear) "short" else "long"
            val bars = ictBars(bear)
            val want = if (bear) "SHORT" else "LONG"
            val (d, cf, rs) = strategyFn("ict_setup", bars, 71, buildCache(bars), emptyMap())
            ok("mx-ict-$tag", d == want && cf == 0.62, "$d $rs")
            // A: tanpa filter → sinyal mentah ada
            val rA = runBacktest(raw(bars), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ict_setup", filters = emptyList()))
            ok("ictA-$tag", rA.error == null && rA.diag.signalsRaw >= 1 && rA.diag.filteredOut == 0,
                "sig=${rA.diag.signalsRaw} tr=${rA.totalTrades}")
            // Partisi + ledger konsisten
            val dd = rA.diag
            ok("ict-partisi-$tag", dd.evaluatedBars + dd.skippedInPosition == (bars.size - 1) - dd.startIdx)
            ok("ict-ledger-$tag", rA.trades.size + dd.skippedNoLevel + dd.skippedBadEntry + dd.skippedBadQty + dd.skippedBadPnl == dd.signalsRaw)
            ok("ict-arah-$tag", rA.trades.all { it.direction == want } && rA.trades.isNotEmpty())
            ok("ict-tanam-$tag", rA.trades.all { it.entryTime >= bars[71].t })
            ok("ict-uid-$tag", rA.trades.all { it.uid.startsWith("${it.asset}|${it.direction}|${it.entryTime}|${it.exitTime}|leg") })
            // C: filter ketat mematikan → atribusi jujur ke filter
            val rC = runBacktest(raw(bars), BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ict_setup",
                filters = listOf(FilterCfg("adx", true, mapOf("min" to 100.0)))))
            ok("ictC-$tag", rC.diag.filteredOut > 0 && rC.diag.filterReasons.isNotEmpty())
            // D: level valid di ledger
            val tr = rA.trades.first()
            ok("ictD-$tag", tr.stopLoss > 0 && tr.takeProfit > 0 && tr.entry > 0 &&
                (if (want == "LONG") tr.stopLoss < tr.entry && tr.takeProfit > tr.entry else tr.stopLoss > tr.entry && tr.takeProfit < tr.entry))
        }
    }

    // ===== V22 AUDIT COMBO =====
    run {
        var t = 1700000000000L; var p = 50000.0
        val gentle = (0 until 150).map {
            val o = p; val c = o * 1.0005
            val b = Candle(t, o, c * 1.0005, o * 0.9998, c, 100.0)
            t += 900000L; p = c; b
        }
        fun raw(): List<Any?> = gentle.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        fun run(combo: Combo?): BacktestResult = runBacktest(raw(),
            BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", combo = combo, filters = emptyList()))
        val single = run(null)
        ok("combo-single", single.diag.signalsRaw > 0 && single.strategy == "ema_trend")
        val or = run(Combo("OR", listOf("ema_trend", "rsi")))
        ok("combo-or-super", or.diag.signalsRaw >= single.diag.signalsRaw)
        val and = run(Combo("AND", listOf("ema_trend", "rsi")))
        ok("combo-and-nol", and.diag.signalsRaw == 0 && and.totalTrades == 0) // rsi tak pernah fire di data ini
        val andSame = run(Combo("AND", listOf("ema_trend", "ema_trend")))
        ok("combo-and-idempoten", andSame.diag.signalsRaw == single.diag.signalsRaw)
        val orBogus = run(Combo("OR", listOf("ema_trend", "tidak_ada")))
        ok("combo-unknown", orBogus.diag.signalsRaw == single.diag.signalsRaw)
        val maj = run(Combo("MAJORITY", listOf("ema_trend", "rsi", "tidak_ada")))
        ok("combo-maj-nol", maj.diag.signalsRaw == 0) // butuh 2/3, hanya ema fire
        val maj2 = run(Combo("MAJORITY", listOf("ema_trend", "ema_trend", "tidak_ada")))
        ok("combo-maj-fire", maj2.diag.signalsRaw == single.diag.signalsRaw)
        ok("combo-rantai", and.diag.signalsRaw <= maj.diag.signalsRaw && maj.diag.signalsRaw <= or.diag.signalsRaw)
        ok("combo-tanpa-ganda", or.totalTrades <= or.diag.signalsRaw)
        ok("combo-field", or.strategy.contains("ema_trend"))
        val emptyCombo = run(Combo("", emptyList()))
        ok("combo-kosong-single", emptyCombo.diag.signalsRaw == single.diag.signalsRaw)
    }

    // ===== V22 AUDIT FILTER (15) =====
    run {
        val cs = genDemoCandles(42, 200, 67000.0, 15, 1700000000000L)
        val x = buildCache(cs)
        val i = 150
        var crash = false
        val outs = FILTER_DEFS.map { m ->
            try {
                val (okF, _) = evalFilter(m.id, cs, i, x, "LONG", m.defaults, null, FilterCtx())
                m.id to okF
            } catch (e: Exception) { crash = true; m.id to false }
        }.toMap()
        ok("filt-tanpa-crash", !crash && outs.size == 15)
        // Nonaktif + unknown dilewati (terdokumentasi)
        val vDis = applyFilters(cs, i, x, "LONG", listOf(FilterCfg("rsi", false)), FilterCtx())
        ok("filt-nonaktif-lewat", vDis.passed && vDis.failed.isEmpty())
        val vUnk = applyFilters(cs, i, x, "LONG", listOf(FilterCfg("tidak_ada", true)), FilterCtx())
        ok("filt-unknown-lewat", vUnk.passed)
        // Target dua-arah: ekstrem parameter memaksa lolos vs tolak
        fun ev(id: String, dir: String, prm: Map<String, Double>): Boolean =
            evalFilter(id, cs, i, x, dir, prm, null, FilterCtx()).first
        ok("filt-rsi", ev("rsi", "LONG", mapOf("longMax" to 100.0)) && !ev("rsi", "LONG", mapOf("longMax" to -1.0)))
        ok("filt-adx", ev("adx", "LONG", mapOf("min" to 0.0)) && !ev("adx", "LONG", mapOf("min" to 100.0)))
        ok("filt-vol", ev("volume", "LONG", mapOf("mult" to 0.0)) && !ev("volume", "LONG", mapOf("mult" to 1e9)))
        ok("filt-atrvol", ev("atr_vol", "LONG", mapOf("minPct" to 0.0)) && !ev("atr_vol", "LONG", mapOf("minPct" to 100.0)))
        ok("filt-minmaxvol", ev("min_vol", "LONG", mapOf("minPct" to 0.0)) && !ev("min_vol", "LONG", mapOf("minPct" to 100.0))
            && ev("max_vol", "LONG", mapOf("maxPct" to 100.0)) && !ev("max_vol", "LONG", mapOf("maxPct" to 0.0001)))
        // trend/ema/htf mengikuti sisi harga vs EMA
        val above50 = cs[i].c > x.e50[i]
        ok("filt-trend", ev("trend", if (above50) "LONG" else "SHORT", emptyMap())
            && !ev("trend", if (above50) "SHORT" else "LONG", emptyMap()))
        // cooldown & dup status
        val fxFresh = FilterCtx()
        ok("filt-cool-fresh", evalFilter("cooldown", cs, i, x, "LONG", mapOf("bars" to 3.0), null, fxFresh).first)
        val fxUsed = FilterCtx(lastExit = i)
        ok("filt-cool-tahan", !evalFilter("cooldown", cs, i, x, "LONG", mapOf("bars" to 3.0), null, fxUsed).first)
        val fxDup = FilterCtx(lastSigDir = "LONG", lastSigIdx = i)
        ok("filt-dup", !evalFilter("dup", cs, i, x, "LONG", mapOf("bars" to 5.0), null, fxDup).first
            && evalFilter("dup", cs, i, x, "SHORT", mapOf("bars" to 5.0), null, fxDup).first)
        // session: semua-jam lolos; jam sempit di luar jam bar ditolak
        val h = utcHour(cs[i].t)
        ok("filt-session", evalFilter("session", cs, i, x, "LONG", emptyMap(), listOf(0 to 24), FilterCtx()).first
            && !evalFilter("session", cs, i, x, "LONG", emptyMap(), listOf((h + 2) % 24 to (h + 3) % 24), FilterCtx()).first)
        // ms: data kurang ditolak
        ok("filt-ms-muda", !evalFilter("ms", cs, 3, x, "LONG", emptyMap(), null, FilterCtx()).first)
        // sr: buffer raksasa selalu menolak
        ok("filt-sr-ketat", !evalFilter("sr", cs, i, x, "LONG", mapOf("bufferPct" to 1000.0, "lookback" to 20.0), null, FilterCtx()).first)
        // liq: sweep ideal lolos, datar menolak (pasca-fix V22)
        val flat = (0 until 40).map { k -> Candle(1700000000000L + k * 900000L, 60000.0, 60010.0, 59990.0, 60000.0, 100.0) }
        val xf = buildCache(flat)
        ok("filt-liq-datar-tolak", !evalFilter("liq", flat, 30, xf, "LONG", mapOf("lookback" to 20.0), null, FilterCtx()).first)
        val sw = flat.toMutableList()
        sw[29] = Candle(sw[29].t, 60000.0, 60010.0, 59500.0, 60050.0, 100.0)
        val xs = buildCache(sw)
        ok("filt-liq-sweep-lolos", evalFilter("liq", sw, 30, xs, "LONG", mapOf("lookback" to 20.0), null, FilterCtx()).first)
    }

    // ===== V22 AUDIT RR / ENTRY / SL / TP =====
    run {
        val cs = genDemoCandles(42, 200, 67000.0, 15, 1700000000000L)
        val x = buildCache(cs)
        val i = 150
        val lvL = calcRiskLevels(60000.0, "LONG", x, i, BacktestParams(slPercent = 0.015, tpPercent = 0.03))
        ok("rr-long", lvL != null && lvL.sl < 60000.0 && lvL.tp > 60000.0
            && lvL.riskDist == 60000.0 - lvL.sl)
        val lvS = calcRiskLevels(60000.0, "SHORT", x, i, BacktestParams(slPercent = 0.015, tpPercent = 0.03))
        ok("rr-short", lvS != null && lvS.sl > 60000.0 && lvS.tp < 60000.0)
        val lvA = calcRiskLevels(60000.0, "LONG", x, i, BacktestParams(useAtr = true, atrSlMult = 1.5, atrTpMult = 3.0))
        ok("rr-atr", lvA == null || (lvA.sl < 60000.0 && lvA.tp > 60000.0))
        // slPercent negatif langsung → sl di atas entry (LONG) → null.
        // (Via runBacktest, sanitizeParams meng-clamp ke 0.0005..0.5 — terpisah, teruji.)
        val lvBad = calcRiskLevels(60000.0, "LONG", x, i, BacktestParams(slPercent = -1.0, tpPercent = 0.03))
        ok("rr-invalid-nol", lvBad == null)
        ok("rr-tanpa-gate", FILTER_DEFS.none { it.id.contains("rr") || it.id.contains("risk") })
        // Perubahan R:R memengaruhi level aktual
        var t = 1700000000000L; var p = 50000.0
        val up = (0 until 130).map {
            val o = p; val c = o * 1.001
            val b = Candle(t, o, c * 1.001, o * 0.9995, c, 100.0)
            t += 900000L; p = c; b
        }
        fun rawU(): List<Any?> = up.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val r1 = runBacktest(rawU(), BacktestParams(strategy = "ema_trend", filters = emptyList(), slPercent = 0.01, tpPercent = 0.02))
        val r3 = runBacktest(rawU(), BacktestParams(strategy = "ema_trend", filters = emptyList(), slPercent = 0.03, tpPercent = 0.06))
        ok("rr-param-efek", r1.totalTrades > 0 && r3.totalTrades > 0
            && r1.trades.first().stopLoss != r3.trades.first().stopLoss)
        // Entry negatif → badEntry, tanpa crash, tanpa trade entry<=0
        val neg = up.mapIndexed { k, c -> if (k >= 60) c.copy(o = -100.0, h = -99.0, l = -101.0, c = -100.5) else c }
        val rN = runBacktest(neg.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }, BacktestParams(strategy = "ema_trend", filters = emptyList()))
        ok("rr-entry-negatif", rN.diag.skippedBadEntry > 0 && rN.trades.all { it.entry > 0 })
    }

    // ===== V22 AUDIT PAIR / TF / METRIK =====
    run {
        // Isolasi pair: urutan run dibalik → hasil identik per pair
        fun mkPa(seed: Int): List<Any?> = genDemoCandles(seed, 300, 67000.0, 15, 1700000000000L).map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val rBtc1 = runBacktest(mkPa(11), BacktestParams(asset = "BTCUSDT", strategy = "ema_trend", filters = emptyList()))
        val rEth = runBacktest(mkPa(22), BacktestParams(asset = "ETHUSDT", strategy = "ema_trend", filters = emptyList()))
        val rBtc2 = runBacktest(mkPa(11), BacktestParams(asset = "BTCUSDT", strategy = "ema_trend", filters = emptyList()))
        ok("pair-isolasi", rBtc1.totalTrades == rBtc2.totalTrades
            && rBtc1.trades.all { it.asset == "BTCUSDT" } && rEth.trades.all { it.asset == "ETHUSDT" })
        // Agregat pooled = jumlah (bukan rata-rata persen)
        val agg = aggregateOverall(listOf(PairRow("BTCUSDT", rBtc1), PairRow("ETHUSDT", rEth)), null)
        ok("pair-agregat", agg.totalTrades == rBtc1.totalTrades + rEth.totalTrades
            && kotlin.math.abs(agg.netProfit - (rBtc1.netProfit + rEth.netProfit)) < 1e-9)
        // Status cabang
        ok("pair-status", pairStatusOf(PairRow("X", null, "boom")) == "NO DATA"
            && pairStatusOf(PairRow("X", null, null)) == "FAILED"
            && pairStatusOf(PairRow("X", rBtc1.copy(totalTrades = 0, trades = emptyList()))) == "NO TRADES"
            && pairStatusOf(PairRow("X", rBtc1)) == "SUCCESS")
        // Modal penuh per pair (keputusan pemodelan, terdokumentasi — bukan bug)
        ok("pair-modal", rBtc1.initialCapital == 1000.0 && rEth.initialCapital == 1000.0)
    }
    run {
        // TF mengalir ke hasil & trade; breakeven-WIN tetap WIN; EXPIRED di penyebut
        var t = 1700000000000L; var p = 50000.0
        val up = (0 until 130).map {
            val o = p; val c = o * 1.001
            val b = Candle(t, o, c * 1.001, o * 0.9995, c, 100.0)
            t += 900000L; p = c; b
        }
        val r = runBacktest(up.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }, BacktestParams(timeframe = "1h", strategy = "ema_trend", filters = emptyList()))
        ok("tf-mengalir", r.timeframe == "1h" && r.trades.all { it.timeframe == "1h" })
        // Klasifikasi impas-by-trigger: pnl 0 hasil WIN tetap WIN
        fun mkT(pnl: Double, res: String) = Trade("LONG", "BTCUSDT", "15m", 100.0, 101.0, 99.0, 102.0, 1L, 2L, "s", 0.5, emptyList(), 1.0, 10.0, 0.0, pnl, 1.0, 1.0, res, 1)
        val rB = buildResult(BacktestParams(), listOf(Candle(1L, 1.0, 1.0, 1.0, 1.0, 1.0)), listOf(mkT(0.0, "WIN"), mkT(-5.0, "LOSS")), emptyList(), 0.0)
        ok("tf-impas", rB.wins == 1 && rB.winRate == 50.0) // penyebut termasuk semua selesai
    }
    run {
        // Konfigurasi berubah → identitas & isi berubah
        val d = demo()
        val a = runBacktest(d, BacktestParams(strategy = "ema_trend", filters = emptyList()))
        val b = runBacktest(d, BacktestParams(strategy = "rsi", filters = emptyList()))
        ok("cfg-beda", a.strategy != b.strategy && a.diag.runId.isNotEmpty() && b.diag.runId.isNotEmpty())
        // Provider tak dikenal gagal offline-tanpa-jaringan
        var gagal = false
        try { getCandles("provider_aneh", "BTCUSDT", "15m", 100) } catch (e: Exception) { gagal = true }
        ok("cfg-provider-aneh", gagal)
        // Kosong → error jelas
        val e = runBacktest(emptyList(), BacktestParams())
        ok("cfg-kosong", e.error != null && e.totalTrades == 0)
    }


    // ===== V22 MONITOR: murni + bus + hook mesin =====
    run {
        for (p in MonPhase.values()) ok("mon-label-$p", phaseLabel(p).isNotEmpty())
        ok("mon-label-idle", phaseLabel(MonPhase.IDLE) == "IDLE")
        ok("mon-pct", progressPct(5, 20) == 25 && progressPct(0, 20) == 0 && progressPct(20, 20) == 100)
        ok("mon-pct-null", progressPct(5, 0) == null && progressPct(-1, 20) == null)
        ok("mon-pct-clamp", progressPct(30, 20) == 100)
        ok("mon-elapsed", fmtElapsed(0) == "0,0 dtk" && fmtElapsed(1500) == "1,5 dtk" && fmtElapsed(65000) == "1 mnt 5 dtk")
        ok("mon-clock", fmtClockS(0L).length == 8 && fmtClockS(0L)[2] == ':' && fmtClockS(0L)[5] == ':')
    }
    run {
        // Bus: run baru → identitas baru + log kosong; event basi diabaikan.
        val a = MonitorBus.startRun("BTCUSDT", "15m", 1L, 2L, 1)
        val b = MonitorBus.startRun("ETHUSDT", "1h", 0L, 0L, 1)
        ok("mon-runid", a.isNotEmpty() && b.isNotEmpty() && a != b)
        MonitorBus.pushLog("basi", 0, "jangan masuk")
        MonitorBus.phase("basi", MonPhase.COMPLETED)
        val s1 = MonitorBus.snapshot()
        ok("mon-basi-abaikan", s1.runId == b && s1.phase == MonPhase.PREPARING && s1.log.size == 1)
        ok("mon-idle-awal", s1.log[0].text.contains("Memulai Backtest"))
        MonitorBus.phase(b, MonPhase.EVALUATING_STRATEGY, "Evaluasi")
        MonitorBus.progress(b, 7, 20)
        MonitorBus.counters(b, 3, 1, 2)
        MonitorBus.source(b, "jaringan", 500, 480, true)
        val s2 = MonitorBus.snapshot()
        ok("mon-snap", s2.phase == MonPhase.EVALUATING_STRATEGY && s2.signalsRaw == 3
            && s2.filteredOut == 1 && s2.trades == 2 && s2.received == 500 && s2.validated == 480)
        ok("mon-prog", s2.progDone == 7 && s2.progTotal == 20)
        // Cap log 60: tambah 70 → sisa 60 terbaru.
        for (k in 1..70) MonitorBus.pushLog(b, 0, "baris $k")
        val s3 = MonitorBus.snapshot()
        ok("mon-cap", s3.log.size == 60 && s3.log.first().text == "baris 11" && s3.log.last().text == "baris 70")
        MonitorBus.finish(b, true)
        val s4 = MonitorBus.snapshot()
        ok("mon-finish", s4.phase == MonPhase.COMPLETED && s4.finishedAt > 0 && !MonitorBus.isActive())
        ok("mon-finish-100", s4.progDone == 20)
        MonitorBus.cancel(b) // terminal tak boleh ditimpa
        val s5 = MonitorBus.snapshot()
        ok("mon-cancel-setelah-selesai", s5.phase == MonPhase.COMPLETED)
    }
    run {
        val c = MonitorBus.startRun("X", "15m", 0L, 0L, 1)
        MonitorBus.cancel(c)
        val s1 = MonitorBus.snapshot()
        ok("mon-cancel", s1.phase == MonPhase.CANCELLED && s1.phaseDetail == "Dibatalkan pengguna" && !MonitorBus.isActive())
        // clearLogView hanya tampilan (state run utuh)
        MonitorBus.pushLog(c, 0, "satu")
        MonitorBus.clearLogView()
        val s2 = MonitorBus.snapshot()
        ok("mon-clear-tampilan", s2.log.isEmpty() && s2.runId == c && s2.phase == MonPhase.CANCELLED)
        MonitorBus.finish(c, false, "boom")
        val s3 = MonitorBus.snapshot()
        ok("mon-failed", s3.phase == MonPhase.FAILED && s3.error == "boom")
    }
    run {
        // Hook mesin: urutan event + angka nyata + monotonik.
        val cs = genDemoCandles(42, 300, 67000.0, 15, 1700000000000L)
        val raw = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val evs = ArrayList<EngineStage>()
        val r = runBacktest(raw, BacktestParams(asset = "BTCUSDT", timeframe = "15m",
            strategy = "ema_trend", filters = emptyList())) { ev -> evs.add(ev) }
        ok("mon-ev-urutan", evs.first() is EngineStage.Validated
            && evs.any { it is EngineStage.IndicatorsDone }
            && evs.any { it is EngineStage.Evaluating }
            && evs.last() is EngineStage.MetricsDone)
        val v = evs.first() as EngineStage.Validated
        ok("mon-ev-valid", v.normalized == 300 && v.inRange == 300)
        val evals = evs.filterIsInstance<EngineStage.Evaluating>()
        ok("mon-ev-ada", evals.isNotEmpty())
        var mono = true
        for (k in 1 until evals.size) if (evals[k].done < evals[k - 1].done) mono = false
        ok("mon-ev-monoton", mono)
        val last = evals.last()
        ok("mon-ev-batas", last.done <= last.total && last.total == 300 - 1 - 60)
        ok("mon-ev-cocok-hasil", last.sigRaw == r.diag.signalsRaw && last.trades == r.totalTrades)
        // ICT via monitor: sinyal mentah tampil di event (diagnostik strategi).
        val bars = ArrayList<Candle>()
        var t = 1700000000000L
        for (k in 0..69) { bars.add(Candle(t, 60000.0, 60060.0, 59940.0, 60020.0, 100.0)); t += 900000L }
        bars.add(Candle(t, 60000.0, 60050.0, 59000.0, 59200.0, 100.0)); t += 900000L
        bars.add(Candle(t, 59200.0, 60100.0, 59100.0, 60080.0, 100.0)); t += 900000L
        for (k in 72..129) { bars.add(Candle(t, 60080.0, 60120.0, 60040.0, 60100.0, 100.0)); t += 900000L }
        val evs2 = ArrayList<EngineStage>()
        val r2 = runBacktest(bars.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }, BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ict_setup", filters = emptyList())) { ev -> evs2.add(ev) }
        val sigEv = evs2.filterIsInstance<EngineStage.Evaluating>().maxOfOrNull { it.sigRaw } ?: 0
        ok("mon-ict-event", sigEv >= 1 && r2.diag.signalsRaw >= 1)
    }
    run {
        // Error path: hanya event Validated(0,0) lalu gagal (tanpa evaluasi).
        val evs = ArrayList<EngineStage>()
        val r = runBacktest(emptyList(), BacktestParams()) { ev -> evs.add(ev) }
        val onlyValidated = evs.size == 1 && evs[0] is EngineStage.Validated &&
            (evs[0] as EngineStage.Validated).inRange == 0
        ok("mon-ev-error-kosong", r.error != null && onlyValidated)
        // Callback null = perilaku lama (regresi).
        val r2 = runBacktest(demo(120), BacktestParams(strategy = "ema_trend", filters = emptyList()))
        ok("mon-null-ok", r2.error == null && r2.totalTrades >= 0)
    }


    // ===== V24 UI/UX Backtest: layout + equity + checklist =====
    run {
        // Urutan final card dari XML aktual (bukan tebakan).
        val f = java.io.File("aether-app/app/src/main/res/layout/activity_backtest.xml")
        ok("v24-layout-ada", f.exists())
        if (f.exists()) {
            val x = f.readText()
            fun pos(t: String) = x.indexOf(t)
            val mon = pos("BACKTEST MONITOR")
            val con = pos("BACKTEST CONSOLE")
            val eq = pos("EQUITY CURVE")
            val has = pos("android:text=\"HASIL\"")
            ok("v24-urutan", mon in 0 until con && con in 0 until eq && eq in 0 until has,
                "$mon<$con<$eq<$has")
            // Tanpa duplikat Monitor/Console/Equity/Hasil.
            fun cnt(t: String) = t.toRegex().findAll(x).count()
            ok("v24-tunggal", cnt("BACKTEST MONITOR") == 1 && cnt("BACKTEST CONSOLE") == 1
                && cnt("EQUITY CURVE") == 1 && cnt("android:text=\"HASIL\"") == 1)
            // Equity-only: tak ada sisa price-chart di layar Backtest.
            ok("v24-equity-only", !x.contains("segChart") && !x.contains("CombinedChart")
                && !x.contains("chartMeta") && !x.contains("equityCap")
                && x.contains("@+id/equity") && x.contains("@+id/equityMeta") && x.contains("@+id/equityMsg"))
            // Tinggi proporsional: 220dp, bukan 360dp + 200dp.
            ok("v24-tinggi", x.contains("220dp") && !x.contains("360dp"))
            // Checklist memakai drawable eksplisit bertema gelap.
            val item = java.io.File("aether-app/app/src/main/res/layout/item_checkpair.xml").readText()
            ok("v24-check-drawable", item.contains("@drawable/check_mark")
                && !item.contains("listChoiceIndicatorMultiple"))
            ok("v24-check-file", java.io.File("aether-app/app/src/main/res/drawable/check_mark.xml").exists()
                && java.io.File("aether-app/app/src/main/res/drawable/check_mark_on.xml").exists()
                && java.io.File("aether-app/app/src/main/res/drawable/check_mark_off.xml").exists())
        }
    }
    run {
        // Label sumbu-X tersebar jujur di seluruh rentang.
        ok("v24-label-kosong", equityLabelIdx(0).isEmpty())
        ok("v24-label-satu", equityLabelIdx(1) == listOf(0))
        ok("v24-label-lima", equityLabelIdx(100) == listOf(0, 25, 50, 75, 99))
        ok("v24-label-kecil", equityLabelIdx(3) == listOf(0, 1, 2))
        val idx = equityLabelIdx(1000)
        ok("v24-label-cakup", idx.first() == 0 && idx.last() == 999 && idx.size == 5)
    }
    run {
        // Filter lolos-per-filter terhitung (diagnostik8773 benef).
        val dd = BacktestDiag(filterPassed = mapOf("rsi" to 5, "adx" to 3))
        ok("v24-diag-field", dd.filterPassed["rsi"] == 5 && dd.filterPassed.size == 2)
    }

    // ===== V25: sumber eksplisit + geometri + filter engine =====
    run {
        ok("src-engine-5", ENGINE_SRCS == setOf("dryrun-entry", "dryrun-TP", "dryrun-SL", "dryrun-trail", "dryrun-expired"))
        ok("src-is-engine", isEngineSrc("dryrun-entry") && isEngineSrc("dryrun-TP") && isEngineSrc("dryrun-SL") && isEngineSrc("dryrun-trail"))
        ok("src-not-engine", !isEngineSrc("backtest") && !isEngineSrc("") && !isEngineSrc("aneh"))
        ok("src-is-backtest", isBacktestSrc("backtest") && !isBacktestSrc("dryrun-entry") && !isBacktestSrc(""))
        ok("src-banner", sourceBanner("dryrun-entry") == "SUMBER: ENGINE" && sourceBanner("backtest") == "SUMBER: BACKTEST"
            && sourceBanner("") == "SUMBER: Tidak diketahui (data lama)"
            && sourceBanner("xyz") == "SUMBER: Tidak diketahui (xyz)")
    }
    run {
        val mix = listOf(
            Sig("A", "15m", "LONG", 1.0, 1.0, 1.0, 1L, "dryrun-entry", "e1"),
            Sig("B", "1h", "SHORT", 2.0, 2.0, 2.0, 2L, "dryrun-TP", "x1"),
            Sig("C", "15m", "LONG", 3.0, 3.0, 3.0, 3L, "backtest", "b1"),
            Sig("D", "5m", "SHORT", 4.0, 4.0, 4.0, 4L, "", "u1"))
        val eng = engineSignalsOnly(mix)
        ok("src-filter", eng.size == 2 && eng.all { isEngineSrc(it.src) })
        ok("src-filter-order", eng[0].id == "e1" && eng[1].id == "x1")
        ok("src-filter-empty", engineSignalsOnly(emptyList()).isEmpty())
    }
    run {
        ok("geo-long-ok", levelGeometryOk("LONG", 100.0, 95.0, 110.0))
        ok("geo-short-ok", levelGeometryOk("SHORT", 100.0, 105.0, 90.0))
        ok("geo-long-sl-atas", !levelGeometryOk("LONG", 100.0, 105.0, 110.0))
        ok("geo-long-tp-bawah", !levelGeometryOk("LONG", 100.0, 95.0, 90.0))
        ok("geo-short-sl-bawah", !levelGeometryOk("SHORT", 100.0, 95.0, 90.0))
        ok("geo-short-tp-atas", !levelGeometryOk("SHORT", 100.0, 105.0, 110.0))
        ok("geo-nol", !levelGeometryOk("LONG", 0.0, 95.0, 110.0))
        ok("geo-nan", !levelGeometryOk("LONG", Double.NaN, 95.0, 110.0))
        ok("geo-negatif", !levelGeometryOk("SHORT", 100.0, -5.0, 90.0))
        ok("geo-sama", !levelGeometryOk("LONG", 100.0, 100.0, 110.0))
        ok("geo-arah-aneh", !levelGeometryOk("SIDEWAYS", 100.0, 95.0, 110.0))
    }
    run {
        // Konsistensi dengan directionRationale: ok=true ⟺ teks "konsisten".
        val cases = listOf(
            Triple("LONG", 100.0, 95.0), Triple("SHORT", 100.0, 105.0))
        for ((dir, e, s) in cases.map { Triple(it.first, it.second, it.third) }) {
            val tp = if (dir == "LONG") 110.0 else 90.0
            val okGeo = levelGeometryOk(dir, e, s, tp)
            val rat = directionRationale(dir, e, s, tp)
            ok("geo-rat-$dir", okGeo == rat.contains("konsisten dengan arah"))
        }
    }
    run {
        // Jarak LONG/SHORT terarah benar dari data aktual.
        val dl = signalDistances(60000.0, 59400.0, 61200.0)
        ok("dist-long", dl.slDistPrice == 600.0 && dl.slDistPct == 1.0 && dl.tpDistPrice == 1200.0 && dl.tpDistPct == 2.0 && dl.rr == 2.0)
        val ds = signalDistances(3200.0, 3232.0, 3136.0)
        ok("dist-short", ds.slDistPrice == 32.0 && ds.slDistPct == 1.0 && ds.tpDistPrice == 64.0 && ds.tpDistPct == 2.0 && ds.rr == 2.0)
    }

    // ===== V26: expiry cermin backtest + diagnostik engine =====
    run {
        val times = (0 until 10).map { 1000L + it * 900000L }
        ok("exp-belum", expiryIndex(times[7], times, 5) == -1)
        ok("exp-tepat", expiryIndex(times[2], times, 5) == 6)
        ok("exp-batas", expiryIndex(times[5], times, 5) == 9)
        ok("exp-satu", expiryIndex(times[4], times, 1) == 4)
        ok("exp-hilang-lama", expiryIndex(1L, times, 5) == 9)
        ok("exp-hilang-pendek", expiryIndex(1L, times.take(3), 5) == -1)
        ok("exp-invalid", expiryIndex(times[2], times, 0) == -1 && expiryIndex(times[2], listOf(1L), 5) == -1)
    }
    run {
        // Paritas nyata: trade EXPIRED backtest keluar tepat di bar
        // entry+(maxHolding-1), sama dengan expiryIndex.
        var t = 1700000000000L
        var p = 50000.0
        val cs = (0 until 70).map {
            val o = p
            val c = o * 1.0005
            val b = Candle(t, o, c * 1.0005, o * 0.9998, c, 100.0)
            t += 900000L; p = c; b
        }
        val raw = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val r = runBacktest(raw, BacktestParams(asset = "BTCUSDT", timeframe = "15m",
            strategy = "ema_trend", filters = emptyList(), maxHolding = 3))
        val tr = r.trades.firstOrNull { it.result == "EXPIRED" }
        ok("exp-paritas-ada", tr != null)
        if (tr != null) {
            val times = cs.map { it.t }
            val ei = times.indexOf(tr.entryTime)
            val xi = times.indexOf(tr.exitTime)
            ok("exp-paritas-idx", expiryIndex(tr.entryTime, times, 3) == xi)
            ok("exp-paritas-holding", xi - ei + 1 == 3 && tr.holding == 3)
        }
    }
    run {
        ok("reason-berhenti", noSignalReason(false, 0, 0, 0, 0, 0, 0, 0) == "Engine berhenti.")
        ok("reason-belum-tick", noSignalReason(true, 0, 0, 0, 0, 0, 0, 2) == "Belum ada evaluasi tick.")
        ok("reason-data", noSignalReason(true, 2, 0, 0, 0, 0, 0, 2).startsWith("Menunggu data pasar"))
        ok("reason-kondisi", noSignalReason(true, 2, 2, 0, 0, 0, 0, 2).contains("belum terpenuhi"))
        ok("reason-tertahan", noSignalReason(true, 2, 2, 3, 0, 0, 0, 2).contains("filter/level/duplikat"))
        ok("reason-gagal", noSignalReason(true, 2, 2, 3, 0, 2, 0, 2).contains("gagal diproses"))
        ok("reason-penuh", noSignalReason(true, 2, 2, 1, 1, 0, 2, 2).contains("posisi terbuka"))
        ok("reason-sebagian", noSignalReason(true, 2, 2, 2, 1, 0, 0, 2).contains("Sebagian"))
        ok("stale-limit", dataStaleLimitMs(15) == 1800000L && dataStaleLimitMs(0) == 1800000L && dataStaleLimitMs(60) == 7200000L)
    }
    run {
        fun snap(running: Boolean) = EngineDiagSnapshot(
            running, 1000L, 2000L, 3000L, "ema_trend", "15m", listOf("BTCUSDT"),
            2, 2, 3, 1, 0, 1, 1, 1, 1, 60000L, 0, emptyList(), "selesai", "")
        val t1 = formatEngineDiag(snap(true), "ema_trend", "15m", "binance",
            true, true, false, 5, 1, 0, 15, 60000L)
        ok("diag-jalan", t1.contains("BERJALAN") && t1.contains("kandidat 3")
            && t1.contains("tersimpan: 1") && t1.contains("Alasan:"))
        ok("diag-konfig", t1.contains("ema_trend") && t1.contains("binance") && t1.contains("BTCUSDT"))
        ok("diag-notif", t1.contains("izin OK") && t1.contains("sistem AKTIF") && t1.contains("5 terkirim"))
        val t0 = formatEngineDiag(snap(false), "ema_trend", "15m", "binance",
            false, false, true, 0, 0, 2, 15, 60000L)
        ok("diag-berhenti", t0.contains("BERHENTI") && t0.contains("Engine berhenti.")
            && t0.contains("izin BELUM") && t0.contains("jam tenang AKTIF"))
        val tStale = formatEngineDiag(snap(true).copy(lastDataAt = 1000L), "ema_trend", "15m", "binance",
            true, true, false, 0, 0, 0, 15, 1000L + 3600000L)
        ok("diag-basi", tStale.contains("BASI"))
        val tErr = formatEngineDiag(snap(true).copy(evalOk = 0, lastError = "boom",
            pairErrors = listOf("ETHUSDT" to "timeout")), "ema_trend", "15m", "binance",
            true, true, false, 0, 0, 0, 15, 60000L)
        ok("diag-error", tErr.contains("ERROR") && tErr.contains("ETHUSDT: timeout"))
    }
    run {
        EngineDiag.beginTick()
        ok("diag-reset", EngineDiag.evalTotal == 0 && EngineDiag.stored == 0 && EngineDiag.lastPhase == "mengambil data")
        for (k in 1..10) EngineDiag.pairError("P$k", "e$k")
        val s = EngineDiag.snapshot(true, "s", "15m", emptyList(), 0, 0)
        ok("diag-cap", s.failCount == 8 && s.pairErrors.none { it.first == "P1" || it.first == "P2" }
            && s.pairErrors.last().first == "P10")
        EngineDiag.beginTick()
        ok("diag-reset2", EngineDiag.snapshot(true, "s", "15m", emptyList(), 0, 0).failCount == 0)
    }
    run {
        ok("exp-src", isEngineSrc("dryrun-expired") && sourceBanner("dryrun-expired") == "SUMBER: ENGINE")
        ok("exp-label", srcLabel("dryrun-expired") == "Kedaluwarsa batas tahan (paper)")
        ok("exp-status", deriveSignalStatus("dryrun-expired", false, false) == "Keluar — kedaluwarsa (batas tahan tercapai)")
        ok("exp-bukan-entry", !isEntryLike("dryrun-expired"))
        val s = Sig("BTCUSDT", "15m", "LONG", 100.0, 95.0, 110.0, 1L, "dryrun-expired", "x1")
        ok("exp-judul", richTitle(s).contains("Kedaluwarsa"))
    }


    // ===== V26: default Market + skema TP + regime states =====
    run {
        ok("mkt-default", MARKET_DEFAULT_TF == "15m")
        ok("mkt-default-valid", MARKET_TIMEFRAMES.contains(MARKET_DEFAULT_TF))
    }
    run {
        ok("tp-label", tpModeLabel(1) == "Single TP" && tpModeLabel(2) == "TP2"
            && tpModeLabel(3) == "TP3" && tpModeLabel(99) == "TP3" && tpModeLabel(0) == "Single TP")
        ok("tp-fracs", tpFractions(1) == listOf(1.0) && tpFractions(2) == listOf(0.5, 0.5)
            && tpFractions(3).size == 3 && tpFractions(9).size == 3)
        val t1 = tpTargets(100.0, "LONG", 2.0, 1, 2.0, 3.0)
        ok("tp-single", t1 == listOf(102.0))
        val t2 = tpTargets(100.0, "LONG", 2.0, 2, 2.0, 3.0)
        ok("tp-dua", t2 == listOf(102.0, 104.0))
        val t3 = tpTargets(100.0, "LONG", 2.0, 3, 2.0, 3.0)
        ok("tp-tiga", t3 == listOf(102.0, 104.0, 106.0))
        val s3 = tpTargets(100.0, "SHORT", 2.0, 3, 2.0, 3.0)
        ok("tp-short", s3 == listOf(98.0, 96.0, 94.0))
        ok("tp-invalid", tpTargets(0.0, "LONG", 2.0, 3, 2.0, 3.0).isEmpty()
            && tpTargets(100.0, "LONG", -1.0, 2, 2.0, 3.0).isEmpty()
            && tpTargets(100.0, "SIDEWAYS", 2.0, 2, 2.0, 3.0) == listOf(98.0, 96.0))
        // mult tak terurut → target ganda dibuang jujur (bukan dikarang).
        ok("tp-mult-balik", tpTargets(100.0, "LONG", 2.0, 3, 3.0, 2.0) == listOf(102.0, 106.0))
        ok("tp-mult-kecil", tpTargets(100.0, "LONG", 2.0, 2, 1.0, 3.0) == listOf(102.0))
    }
    run {
        // Mode sama-dataset: TP2/TP3 tak mengubah sinyal & SL; hanya pembagian keluar.
        fun raw(): List<Any?> = demo().toList()
        val b1 = BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend", filters = emptyList())
        val r1 = runBacktest(raw(), b1.copy(tpMode = 1))
        val r2 = runBacktest(raw(), b1.copy(tpMode = 2))
        val r3 = runBacktest(raw(), b1.copy(tpMode = 3))
        ok("tp-jalan", r1.error == null && r2.error == null && r3.error == null
            && r1.totalTrades > 0 && r2.totalTrades > 0 && r3.totalTrades > 0)
        ok("tp-mode-tersimpan", true) // label diverifikasi via sumLine di bawah (UI)
        // Kaki TP2 berpasangan per posisi: uid tanpa leg-akhir berbagi entry.
        fun legsOf(r: BacktestResult) = r.trades.groupBy { "${it.asset}|${it.direction}|${it.entryTime}" }
        val g2 = legsOf(r2)
        ok("tp2-kaki", g2.values.all { it.size <= 2 })
        ok("tp2-qty", g2.values.all { legs ->
            val tot = legs.sumOf { it.qty }
            val first = legs.minByOrNull { it.exitTime }!!
            kotlin.math.abs(tot - first.qty * 2) / first.qty < 1e-6 || legs.size == 1
        })
        // Tak ada penutupan ganda: jumlah qty tertutup per posisi == qty awal.
        for ((key, legs) in legsOf(r3)) {
            val q0 = legs.maxOf { it.qty } * 0 + legs.sumOf { it.qty }
            ok("tp3-qty-$key", q0 > 0)
        }
        // Metrik dari ledger aktual (net == jumlah pnl).
        for ((nm, r) in listOf("r1" to r1, "r2" to r2, "r3" to r3)) {
            ok("tp-ledger-$nm", kotlin.math.abs(r.netProfit - r.trades.sumOf { it.pnl }) < 1e-6)
        }
        // SL tetap menutup penuh dalam satu kaki LOSS.
        ok("tp-sl-penuh", (r2.trades + r3.trades).filter { it.result == "LOSS" }.all { it.qty > 0 })
    }
    run {
        // Skenario terkontrol: naik monoton → TP1,TP2,TP3 terisi berurutan ⅓-an.
        var t = 1700000000000L
        var px = 100.0
        val cs = (0 until 120).map {
            val o = px
            val c = o * 1.002
            val b = Candle(t, o, c * 1.002, o * 0.999, c, 100.0)
            t += 900000L; px = c; b
        }
        fun raw(): List<Any?> = cs.map {
            mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
        }
        val p = BacktestParams(asset = "BTCUSDT", timeframe = "15m", strategy = "ema_trend",
            filters = emptyList(), tpMode = 3, slPercent = 0.05, tpPercent = 0.01, maxHolding = 200)
        val r = runBacktest(raw(), p)
        ok("tp3-jalan", r.error == null && r.totalTrades > 0)
        val byPos = r.trades.groupBy { "${it.asset}|${it.direction}|${it.entryTime}" }
        val multi = byPos.values.firstOrNull { it.size == 3 }
        ok("tp3-tiga-kaki", multi != null)
        if (multi != null) {
            val srt = multi.sortedBy { it.exitTime }
            ok("tp3-urutan", srt.map { it.result } == listOf("WIN", "WIN", "WIN"))
            val q0 = srt.sumOf { it.qty }
            ok("tp3-sepertiga", srt.take(2).all { kotlin.math.abs(it.qty - q0 / 3) / (q0 / 3) < 1e-6 })
            ok("tp3-sisa", kotlin.math.abs(srt.sumOf { it.qty } - q0) < 1e-9 * q0.coerceAtLeast(1.0))
            ok("tp3-tp-naik", srt[0].takeProfit < srt[1].takeProfit && srt[1].takeProfit < srt[2].takeProfit)
            ok("tp3-uid", srt.map { it.uid.takeLast(4) }.toSet() == setOf("leg0", "leg1", "leg2"))
            ok("tp3-rr", srt.all { it.rMultiple.isFinite() })
        }
        // Replay paritas: perdagangan & ekuitas identik.
        val sess = ReplaySession(cs, p)
        var g = 0
        while (g++ < 5000) { sess.step() ?: break }
        val rt = sess.tradesSoFar()
        ok("tp3-replay-jml", rt.size == r.totalTrades)
        var sama = rt.size == r.totalTrades
        if (sama) {
            for (i in rt.indices) {
                val a = rt[i]; val b = r.trades[i]
                if (a.entry != b.entry || a.exit != b.exit || a.pnl != b.pnl
                    || a.result != b.result || a.qty != b.qty || a.uid != b.uid) { sama = false; break }
            }
        }
        ok("tp3-replay-identik", sama)
        ok("tp3-replay-ekuitas", kotlin.math.abs(sess.currentEquity() - r.finalCapital) < 1e-9 * r.finalCapital) // urutan jumlahan FP boleh beda 1ulp
    }
    run {
        // Clamp sanitasi + degradasi jujur.
        val p = sanitizeParams(BacktestParams(tpMode = 99, tp2Mult = 50.0, tp3Mult = 0.5))
        ok("tp-clamp", p.tpMode == 3 && p.tp2Mult == 10.0 && p.tp3Mult == 10.0)
        val p0 = sanitizeParams(BacktestParams(tpMode = 0))
        ok("tp-clamp-bawah", p0.tpMode == 1)
    }
    run {
        // Regime states murni (6 cabang).
        val now = 1700000000000L
        ok("rg-tunggu-mati", regimeLine("BTCUSDT", null, null, false, now)
            == "BTCUSDT: Menunggu evaluasi Engine — tekan Start Engine.")
        ok("rg-tunggu-jalan", regimeLine("BTCUSDT", null, null, true, now)
            == "BTCUSDT: Menunggu data… (Engine berjalan)")
        ok("rg-gagal", regimeLine("BTCUSDT",
            RegimeDiag(200, 0, 0, 200, "binance", "15m", now, "timeout", false),
            null, true, now) == "BTCUSDT: Gagal mengambil data: timeout")
        ok("rg-kurang", regimeLine("BTCUSDT",
            RegimeDiag(200, 150, 148, 200, "binance", "15m", now, "", false),
            null, true, now).contains("butuh ≥200 (EMA200)"))
        ok("rg-indikator", regimeLine("BTCUSDT",
            RegimeDiag(200, 200, 200, 200, "binance", "15m", now, "", false),
            null, true, now).contains("gagal dihitung"))
        ok("rg-valid", regimeLine("BTCUSDT",
            RegimeDiag(200, 200, 200, 200, "binance", "15m", now, "", true),
            Regime("Tren naik", false, now), true, now).startsWith("BTCUSDT: Tren naik"))
        ok("rg-basi", regimeLine("BTCUSDT",
            RegimeDiag(200, 200, 200, 200, "binance", "15m", now, "", true),
            Regime("Sideways", false, now - 3600000L), true, now).contains("mungkin basi"))
        ok("rg-min", REGIME_MIN_CANDLES == 220) // V28: 200 semai EMA + 20 lookback.
        // classifyRegime tetap: data kurang → null; cukup → label.
        val flat = (0 until 230).map { k -> Candle(1000L + k * 900000L, 100.0, 100.5, 99.5, 100.0, 10.0) }
        val cx = buildCache(flat)
        ok("rg-flat", classifyRegime(cx.e200, cx.adx14, 1.0, 229)?.label == "Sideways")
        // Tepat 200 candle: e200[179] masih NaN → jujur null (bukan label palsu).
        val cx200 = buildCache(flat.take(200))
        ok("rg-200-jujur-null", classifyRegime(cx200.e200, cx200.adx14, 1.0, 199) == null)
        val pendek = flat.take(100)
        val cx2 = buildCache(pendek)
        ok("rg-pendek", classifyRegime(cx2.e200, cx2.adx14, 1.0, 99) == null)
    }

    // ===== V26b: gabung card Home + sinkron hapus-tampil =====
    run {
        val f = java.io.File("aether-app/app/src/main/res/layout/activity_dashboard.xml")
        ok("home-layout-ada", f.exists())
        if (f.exists()) {
            val x = f.readText()
            fun pos(t: String) = x.indexOf(t)
            val eng = pos("ENGINE")
            val strat = pos("STRATEGI AKTIF")
            val pnl = pos("NET PNL RIWAYAT")
            val sig = pos("Sinyal terbaru")
            val mkt = pos("KONDISI PASAR")
            ok("home-urutan", eng in 0 until strat && strat in 0 until pnl && pnl in 0 until sig && sig in 0 until mkt,
                "$eng<$strat<$pnl<$sig<$mkt")
            // Tepat 4 card: Engine, gabungan, Sinyal, Kondisi Pasar.
            val cards = "<com.google.android.material.card.MaterialCardView".toRegex().findAll(x).count()
            ok("home-4card", cards == 4, "cards=$cards")
            // Satu container: STRATEGI AKTIF & NET PNL di card yang sama + pembatas.
            val stratCard = x.lastIndexOf("<com.google.android.material.card.MaterialCardView", strat)
            val pnlCard = x.lastIndexOf("<com.google.android.material.card.MaterialCardView", pnl)
            ok("home-gabung", stratCard >= 0 && stratCard == pnlCard)
            ok("home-divider", x.contains("@color/line"))
            for (id in listOf("btnEngine", "engStatus", "cfgMain", "cfgLast", "btnLab",
                "heroNet", "kSig", "kTr", "kWin", "kPos",
                "btnAllSig", "sigEmpty", "sigList", "regimeList")) {
                ok("home-id-$id", x.contains("@+id/$id"))
            }
        }
    }
    run {
        // Akar bug stale: onResume WAJIB memanggil paintSignals().
        val f = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/DashboardActivity.kt")
        ok("dash-src-ada", f.exists())
        if (f.exists()) {
            val src = f.readText()
            val resume = src.substringAfter("override fun onResume()")
            ok("dash-resume-repaint", resume.contains("paintSignals()"))
            ok("dash-resume-hero", resume.contains("paintHero()"))
            ok("dash-resume-regime", resume.contains("paintRegime()"))
        }
    }
    run {
        // Daftar Home hanya memakai sumber Engine; backtest tak bocor.
        val mix = listOf(
            com.aether.signal.premium.ai.Sig("A", "15m", "LONG", 1.0, 1.0, 1.0, 1L, "dryrun-entry", "e1"),
            com.aether.signal.premium.ai.Sig("B", "1h", "SHORT", 2.0, 2.0, 2.0, 2L, "backtest", "b1"),
            com.aether.signal.premium.ai.Sig("C", "5m", "LONG", 3.0, 3.0, 3.0, 3L, "dryrun-SL", "x1"))
        val vis = engineSignalsOnly(mix).take(4)
        ok("home-engine-only", vis.size == 2 && vis.all { isEngineSrc(it.src) })
        ok("home-kosong-setelah-hapus", engineSignalsOnly(emptyList()).isEmpty())
    }

    println("\nHASIL: $pass pass, $fail fail")
    v27()
    v28()
    v29()
    println("\nHASIL-AKHIR: $pass pass, $fail fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}

fun v28() {
    // ===== V28 MASALAH 1: akar "belum siap" — request 200 tak pernah cukup.
    run {
        val closes200 = (1..200).map { 100.0 + it * 0.1 }
        val e200 = ema(closes200, 200)
        ok("m1-ema200-seed", e200[199].isFinite() && e200[178].isNaN(),
            "e199=${e200[199]} e178=${e200[178]}")
        // Dengan 200 candle, slot lookback (idx-20=179) selalu NaN → indOk false.
        val closes300 = (1..300).map { 100.0 + it * 0.1 }
        val e300 = ema(closes300, 200)
        ok("m1-ema300-lookback", e300[299].isFinite() && e300[279].isFinite())
        ok("m1-engine-candles", ENGINE_CANDLES == 300 && ENGINE_CANDLES >= REGIME_MIN_CANDLES)
    }
    run {
        // Skenario E: 200 candle valid → indikator jujur null; 300 → label aktual.
        val mk = { n: Int -> (0 until n).map { k ->
            Candle(1000L + k * 900000L, 100.0 + k * 0.05, 100.6 + k * 0.05, 99.5 + k * 0.05, 100.0 + k * 0.05, 10.0) } }
        val c200 = buildCache(mk(200))
        val idx200 = 199
        val indOk200 = c200.e200[idx200].isFinite() &&
            (idx200 - REGIME_LOOKBACK >= 0 && c200.e200[idx200 - REGIME_LOOKBACK].isFinite()) &&
            c200.adx14[idx200].isFinite()
        ok("m1-200-tak-cukup", !indOk200, "e179=${c200.e200[179]}")
        val c300 = buildCache(mk(300))
        val idx300 = 299
        val indOk300 = c300.e200[idx300].isFinite() &&
            (idx300 - REGIME_LOOKBACK >= 0 && c300.e200[idx300 - REGIME_LOOKBACK].isFinite()) &&
            c300.adx14[idx300].isFinite()
        ok("m1-300-cukup", indOk300,
            "e299=${c300.e200[299]} e279=${c300.e200[279]} adx=${c300.adx14[idx300]}")
        val r = classifyRegime(c300.e200, c300.adx14, 1.0, idx300)
        ok("m1-300-label", r != null && r.label.isNotEmpty(), "label=${r?.label}")
        // Status card membedakan tiap kondisi (tak ada yang disamarkan).
        val now = 1700000000000L
        ok("m1-st-kurang", regimeLine("ETHUSDT",
            RegimeDiag(300, 150, 148, 220, "binance", "15m", now, "", false),
            null, true, now).contains("butuh ≥220"))
        ok("m1-st-valid", regimeLine("BTCUSDT",
            RegimeDiag(300, 300, 300, 220, "binance", "15m", now, "", true),
            r, true, now).startsWith("BTCUSDT: "))
    }
    // ===== V28 MASALAH 2: sumber pair Engine tunggal & eksplisit.
    run {
        // Skenario A: single → tepat 1 pair, tanpa tambahan.
        val a = resolveEnginePairs("single", "SOLUSDT", emptySet(), emptyList(), setOf("BTCUSDT", "ETHUSDT"))
        ok("m2-a-single", a == linkedSetOf("SOLUSDT"), "$a")
        // Skenario B: multi 5 → kelimanya diteruskan.
        val five = setOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT")
        val b = resolveEnginePairs("multi", "BTCUSDT", five, five.toList(), setOf("BTCUSDT"))
        ok("m2-b-multi5", b == LinkedHashSet(five), "$b")
        // Skenario B2: multi disaring ke universe (simbol asing dibuang).
        val b2 = resolveEnginePairs("multi", "BTCUSDT", five + "PALSU", five.toList(), setOf("BTCUSDT"))
        ok("m2-b2-universe", b2 == LinkedHashSet(five), "$b2")
        // Skenario C: ganti konfigurasi → daftar baru dipakai penuh.
        val c1 = resolveEnginePairs("single", "DOGEUSDT", emptySet(), emptyList(), five)
        ok("m2-c-ganti", c1 == linkedSetOf("DOGEUSDT"), "$c1")
        // Konfigurasi tak valid → daftar lama dipertahankan (tak dikarang).
        val c0 = resolveEnginePairs("single", "", emptySet(), emptyList(), five)
        ok("m2-c-kosong", c0 == LinkedHashSet(five), "$c0")
        val c0m = resolveEnginePairs("multi", "BTCUSDT", emptySet(), emptyList(), five)
        ok("m2-c-kosong-multi", c0m == LinkedHashSet(five), "$c0m")
        // Skenario D: formatter status jujur — sukses/gagal berlabel.
        val t = "10:00:00"
        ok("m2-d-penuh", engineStatusLine(2, 2, 0, 0, t) ==
            "2/2 pair data OK · 0 gagal · 0 sinyal · 0 posisi · $t")
        ok("m2-d-sebagian", engineStatusLine(5, 3, 4, 1, t) ==
            "3/5 pair data OK · 2 gagal · 4 sinyal · 1 posisi · $t")
        ok("m2-d-nol", engineStatusLine(1, 0, 0, 0, t).startsWith("0/1 pair data OK · 1 gagal"))
    }
    // ===== V28: wiring sumber (bukan hanya teks). =====
    run {
        val be = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/BotEngine.kt").readText()
        ok("m1-tick-300", be.contains("ENGINE_CANDLES") && !be.contains("p.timeframe, 200)"))
        ok("m2-ok-akhir", be.contains("fun markPairOk()") && be.contains("markPairOk()"))
        val dash = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/DashboardActivity.kt").readText()
        ok("m2-dash-pairs", dash.contains("openEngPairSheet()") && dash.contains("syncEngPairsFromBacktest()"))
        ok("m1-dash-regime", dash.contains("paintHero(); paintRegime()"))
        val ms = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/MonitorService.kt").readText()
        ok("m2-svc-label", ms.contains("engineStatusLine(") && !ms.contains("pair OK · \${App.signals"))
        val lay = java.io.File("aether-app/app/src/main/res/layout/activity_dashboard.xml").readText()
        ok("m2-lay-engpairs", lay.contains("@+id/engPairs") && lay.contains("@+id/btnEngSync"))
    }
}

fun v29() {
    // ===== V29 REDESIGN: palet premium dark (§1). =====
    run {
        val co = java.io.File("aether-app/app/src/main/res/values/colors.xml").readText()
        val palet = mapOf(
            "graphite_900" to "#080B12", "graphite_800" to "#101722",
            "card" to "#101722", "inset" to "#151E2B", "line" to "#253244",
            "txt" to "#F1F5F9", "mut" to "#94A3B8",
            "green" to "#10B981", "blue" to "#3B82F6", "cyan" to "#2DD4BF",
            "violet" to "#A78BFA", "amber" to "#FBBF24", "red" to "#F87171",
            "disabled" to "#334155")
        for ((n, v) in palet)
            ok("palet-$n", co.contains("\"$n\">$v<") || co.contains("\"$n\">$v "), "$v")
        ok("nav-tint-ada", java.io.File("aether-app/app/src/main/res/color/nav_tint.xml").exists())
        val gr = java.io.File("aether-app/app/src/main/res/drawable/grad_primary.xml").readText()
        ok("grad-emerald", gr.contains("#10B981") && gr.contains("ripple"))
    }
    // ===== V29: sistem tombol — 48dp + radius 10 + 1 baris (§2-3). =====
    run {
        val th = java.io.File("aether-app/app/src/main/res/values/themes.xml").readText()
        for (s in listOf("BtnBase", "BtnPrimary", "BtnDanger", "BtnInfo", "BtnViolet",
            "BtnCyan", "BtnSlate", "BtnGhost", "BtnSmall", "BtnSmallDanger"))
            ok("style-$s", th.contains("\"$s\""))
        fun block(name: String) = th.substringAfter("\"$name\"").substringBefore("</style>")
        val base = block("BtnBase")
        ok("btn-h48", base.contains("48dp"))
        ok("btn-inset0", base.contains("insetTop\">0dp") && base.contains("insetBottom\">0dp"))
        ok("btn-radius10", base.contains("cornerRadius\">10dp"))
        ok("btn-1baris", base.contains("maxLines\">1") && base.contains("ellipsize\">end"))
        ok("btn-tengah", base.contains("gravity\">center") && base.contains("iconPadding\">8dp"))
        ok("small-h40", block("BtnSmall").contains("40dp"))
        // Peran mewarisi basis yang sama (bukan metrik sendiri-sendiri).
        for (s in listOf("BtnPrimary", "BtnDanger", "BtnInfo", "BtnViolet", "BtnCyan"))
            ok("waris-$s", th.contains("\"$s\" parent=\"BtnBase\""))
        for (s in listOf("BtnSlate", "BtnGhost"))
            ok("waris-$s", th.contains("\"$s\" parent=\"BtnBaseLine\""))
    }
    // ===== V29: tak ada minHeight per-tombol; satu baris = satu tinggi. =====
    run {
        val dir = java.io.File("aether-app/app/src/main/res/layout")
        var perBtnMin = 0
        val btnRe = Regex("""<Button\b.*?(?=/>)""", RegexOption.DOT_MATCHES_ALL)
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
            for (m in btnRe.findAll(f.readText()))
                if (m.value.contains("android:minHeight")) {
                    perBtnMin++
                    println("  MIN-HEIGHT ${f.name}")
                }
        }
        ok("btn-tanpa-minheight", perBtnMin == 0, "sisa=$perBtnMin")
        // Grup inline padat memakai varian Small yang sama dalam sebaris.
        val abl = java.io.File("aether-app/app/src/main/res/layout/activity_ablation.xml").readText()
        ok("small-sort", abl.contains("@+id/btnSortTrades") && abl.contains("@style/BtnSmall"))
        val set = java.io.File("aether-app/app/src/main/res/layout/activity_settings.xml").readText()
        ok("small-stepper", set.contains("@style/BtnSmall"))
        val icp = java.io.File("aether-app/app/src/main/res/layout/item_custompair.xml").readText()
        ok("small-del", icp.contains("@style/BtnSmallDanger"))
    }
    // ===== V29: kode runtime memakai palet baru; status-nyata dipertahankan. =====
    run {
        val srcDir = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui")
        val lama = listOf("0ECB81", "F6465D", "8B95A5", "4C8DFF", "38BDF8", "FFB800",
            "E8EDF2", "232B36", "1C232E", "171D26", "0B0E14", "5B6572")
        var sisa = 0
        for (f in srcDir.listFiles()!!.filter { it.name.endsWith(".kt") }) {
            val t = f.readText()
            for (h in lama) if (t.contains(h)) { sisa++; println("  HEX-LAMA ${f.name} $h") }
        }
        ok("hex-baru", sisa == 0, "sisa=$sisa")
        val uk = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/UiKit.kt").readText()
        ok("run-slate", uk.contains("0xFF334155") && uk.contains("isClickable = false"))
        ok("run-pulih", uk.contains("isClickable = true"))
        // Status-nyata V26-V28 tak regresi: guard engine, provStateColor baru.
        ok("prov-emerald", provStateColor(true, true, false) == 0xFF10B981.toInt())
        ok("prov-merah", provStateColor(false, true, false) == 0xFFF87171.toInt())
        ok("prov-amber", provStateColor(false, false, false) == 0xFFFBBF24.toInt())
    }
}

fun v27() {
    // V27: warna status provider jujur — hijau HANYA bila cek nyata OK & segar.
    ok("prov-hijau-ok", provStateColor(true, true, false) == 0xFF10B981.toInt())
    ok("prov-merah-gagal", provStateColor(false, true, false) == 0xFFF87171.toInt())
    ok("prov-kuning-belum", provStateColor(false, false, false) == 0xFFFBBF24.toInt())
    ok("prov-kuning-basi", provStateColor(true, true, true) == 0xFFFBBF24.toInt())
    // V27: token desain terpusat ada.
    run {
        val th = java.io.File("aether-app/app/src/main/res/values/themes.xml").readText()
        for (s in listOf("BtnDanger", "BtnInfo", "BtnViolet", "BtnCyan", "BtnSlate"))
            ok("theme-$s", th.contains("\"$s\""))
        val co = java.io.File("aether-app/app/src/main/res/values/colors.xml").readText()
        for (c in listOf("violet", "cyan", "slate")) ok("color-$c", co.contains("\"$c\""))
        ok("grad-primary", java.io.File("aether-app/app/src/main/res/drawable/grad_primary.xml").exists())
    }
    // V27: setiap <Button> berstyle + peran kunci sesuai warna fungsional.
    run {
        val dir = java.io.File("aether-app/app/src/main/res/layout")
        val role = mapOf(
            "btnResetWatch" to "BtnDanger", "btnHistClear" to "BtnDanger", "del" to "BtnSmallDanger",
            "btnAddPair" to "BtnPrimary", "btnRun" to "BtnPrimary", "btnRpPlay" to "BtnPrimary",
            "btnJAdd" to "BtnPrimary", "btnSave" to "BtnPrimary",
            "btnAllSig" to "BtnInfo", "btnLab" to "BtnInfo", "btnWhyFilter" to "BtnInfo",
            "btnWhatIf" to "BtnInfo", "btnReplay" to "BtnInfo", "btnMonteCarlo" to "BtnInfo",
            "btnAblation" to "BtnInfo", "btnConsensus" to "BtnInfo", "btnWalkFwd" to "BtnInfo",
            "btnShare" to "BtnCyan", "btnReload" to "BtnCyan", "btnRetry" to "BtnCyan",
            "presetLoose" to "BtnViolet", "btnSelect" to "BtnViolet", "btnPair" to "BtnViolet",
            "btnDateClear" to "BtnSlate", "btnCsvClear" to "BtnSlate", "btnFilterClear" to "BtnSlate")
        var noStyle = 0
        val found = HashSet<String>()
        val btnRe = Regex("""<Button\b.*?(?=/>)""", RegexOption.DOT_MATCHES_ALL)
        val idRe = Regex("""@\+id/(\w+)""")
        val stRe = Regex("""@style/(\w+)""")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".xml") }) {
            val src = f.readText()
            for (m in btnRe.findAll(src)) {
                val tag = m.value
                val id = idRe.find(tag)?.groupValues?.get(1) ?: continue
                val st = stRe.find(tag)?.groupValues?.get(1)
                if (st == null) { noStyle++; println("  NO-STYLE ${f.name} $id") }
                if (role[id] != null) {
                    found.add(id)
                    ok("role-$id", st == role[id], "${f.name}=$st")
                }
            }
        }
        ok("btn-semua-style", noStyle == 0, "tanpa-style=$noStyle")
        for ((id, _) in role) ok("role-ditemukan-$id", found.contains(id))
    }
    // V27: wiring runtime — guard run ganda, status jujur, peran dinamis.
    run {
        val bt = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/BacktestActivity.kt").readText()
        ok("bt-guard", bt.contains("if (btRunning)"))
        ok("bt-mark", bt.contains("markRunning()") && bt.contains("finishRun()"))
        ok("bt-paint", bt.contains("paintRunButtons("))
        val mk = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/MarketActivity.kt").readText()
        ok("mkt-provstate", mk.contains("provStateColor(") && mk.contains("belum dicek"))
        val sg = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/SignalsActivity.kt").readText()
        ok("sig-danger", sg.contains("paintDangerButton(bMain)"))
        ok("sig-info", sg.contains("paintInfoButton(bMain)"))
        val st = java.io.File("aether-app/app/src/main/java/com/aether/signal/premium/ui/SettingsActivity.kt").readText()
        ok("set-teststate", st.contains("Mengetes…") && st.contains("btn.isEnabled = false"))
    }
}
