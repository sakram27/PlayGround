import com.aether.signal.premium.engine.*
import com.aether.signal.premium.ui.App
import com.aether.signal.premium.ui.multiRunStatus
import com.aether.signal.premium.ui.regimeBadgeColor
import com.aether.signal.premium.ui.regimeRow
import com.aether.signal.premium.ui.Regime
import com.aether.signal.premium.ui.RegimeDiag

var fpass = 0
var ffail = 0
fun fok(n: String, c: Boolean, x: String = "") {
    if (c) { fpass++; println("PASS $n $x") } else { ffail++; println("FAIL $n $x") }
}

fun fdemo(seed: Int, n: Int, px: Double): List<Any?> =
    genDemoCandles(seed, n, px, 15).map {
        mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any?
    }

fun main() {
    // ===== fmtPx: harga normal tak berubah, mikro terbaca, nol sah tetap "0" =====
    fok("px-normal", App.fmtPx(67000.0) == App.fmt(67000.0, 4), App.fmtPx(67000.0))
    fok("px-normal-kecil", App.fmtPx(1.6155) == App.fmt(1.6155, 4), App.fmtPx(1.6155))
    fok("px-batas", App.fmtPx(0.01) == App.fmt(0.01, 4), App.fmtPx(0.01))
    fok("px-pepe", App.fmtPx(0.00001234) == "0,00001234", App.fmtPx(0.00001234))
    fok("px-shib", App.fmtPx(0.0000215) == "0,0000215", App.fmtPx(0.0000215))
    fok("px-negatif-mikro", App.fmtPx(-0.0000032) == "-0,0000032", App.fmtPx(-0.0000032))
    fok("px-nol", App.fmtPx(0.0) == "0", App.fmtPx(0.0))
    fok("px-nan", App.fmtPx(Double.NaN) == "—", App.fmtPx(Double.NaN))
    fok("px-inf", App.fmtPx(Double.POSITIVE_INFINITY) == "—")
    fok("px-negatif-normal", App.fmtPx(-12.5) == App.fmt(-12.5, 4))

    // ===== multiRunStatus: counts + SUCCESS/PARTIAL + snapshot =====
    run {
        val s = multiRunStatus(11, 11, 11, emptyList(), "SUCCESS", true)
        fok("st-success", s.startsWith("SUCCESS:") && s.contains("dipilih 11")
            && s.contains("berhasil 11") && s.contains("gagal 0")
            && s.contains("snapshot Engine tersimpan"), s)
    }
    run {
        val s = multiRunStatus(11, 10, 9, listOf("PEPEUSDT"), "PARTIAL SUCCESS", false)
        fok("st-partial", s.startsWith("PARTIAL SUCCESS:") && s.contains("dipilih 11")
            && s.contains("diproses 10") && s.contains("berhasil 9")
            && s.contains("gagal 1 (PEPEUSDT)")
            && s.contains("snapshot Engine TIDAK disimpan (PARTIAL SUCCESS)"), s)
    }
    run {
        val s = multiRunStatus(5, 5, 0,
            listOf("A", "B", "C", "D", "E"), "FAILED", false)
        fok("st-failed", s.startsWith("FAILED:") && s.contains("berhasil 0")
            && s.contains("gagal 5 (A, B, C, D, E)")
            && s.contains("TIDAK disimpan (FAILED)"), s)
    }

    // ===== Konsistensi sumber kurva vs ringkasan (data demo, deterministik) =====
    run {
        val pairs = listOf("BTCUSDT" to 67000.0, "ETHUSDT" to 3500.0)
        val p0 = BacktestParams(asset = "BTCUSDT", timeframe = "15m",
            strategy = "ema_trend", maxOpenTrades = 1)
        val perPair = pairs.map { (s, px) ->
            val r = runBacktest(fdemo(42, 500, px), sanitizeParams(p0.copy(asset = s)))
            s to r
        }
        val standaloneSum = perPair.sumOf { it.second.totalTrades }
        val cmb = buildCombinedEquity(
            perPair.map { PairRow(it.first, it.second) }, 1000.0)
        fok("src-combined-eq-standalone", cmb.n == standaloneSum, "n=${cmb.n}")
        val pf = runPortfolioBacktest(
            pairs.map { (s, px) -> PortfolioAsset(s, fdemo(42, 500, px)) },
            sanitizeParams(p0.copy(maxOpenTrades = 1)))
        fok("src-pf-ok", pf.result.error == null && pf.excluded.isEmpty(),
            "tr=${pf.result.totalTrades}")
        fok("src-pf-reconcile", reconcileResult(pf.result).isEmpty(),
            reconcileResult(pf.result).joinToString(";"))
        // Mekanisme 70-vs-67: selisih standalone-vs-portofolio dijelaskan
        // oleh penolakan cap global (dilaporkan, bukan diasumsikan).
        fok("src-gap-akun", standaloneSum - pf.result.totalTrades >= 0,
            "gap=${standaloneSum - pf.result.totalTrades} rejected=${pf.result.diag.rejectedByLimit}")
        val lastEq = pf.result.equityCurve.lastOrNull()?.equity ?: Double.NaN
        fok("src-pf-curve", pf.result.equityCurve.isNotEmpty()
            && kotlin.math.abs(lastEq - pf.result.finalCapital) < 1e-6,
            "pts=${pf.result.equityCurve.size}")
    }

    // ===== Validasi rentang tanggal (logika yang dipakai dateRangeOk) =====
    fok("dt-valid", App.isDateRangeValid(1000L, 2000L))
    fok("dt-sama", App.isDateRangeValid(1000L, 1000L))
    fok("dt-kosong", App.isDateRangeValid(0L, 0L) && App.isDateRangeValid(0L, 5000L))
    fok("dt-balik", !App.isDateRangeValid(2000L, 1000L))
    fok("dt-2thn", exceedsTwoYears(1754000000000L, 1754000000000L + 800L * 86400000L)
        && !exceedsTwoYears(1754000000000L, 1754000000000L + 700L * 86400000L))

    // ===== Badge regime (design system): warna + cabang sama dengan regimeLine =====
    fok("rg-warna-naik", regimeBadgeColor("Tren naik") == 0xFF10B981.toInt())
    fok("rg-warna-turun", regimeBadgeColor("Tren turun") == 0xFFF87171.toInt())
    fok("rg-warna-sideways", regimeBadgeColor("Sideways") == 0xFF94A3B8.toInt())
    run {
        val now = 1_700_000_000_000L
        val r0 = regimeRow("BTCUSDT", null, null, false, now)
        fok("rg-tunggu-mati", r0.badge == "Belum jalan" && r0.sub.contains("Start Engine"), "$r0")
        val r1 = regimeRow("BTCUSDT", null, null, true, now)
        fok("rg-tunggu-jalan", r1.badge == "Menunggu", "$r1")
        val r2 = regimeRow("BTCUSDT",
            RegimeDiag(300, 300, 300, 220, "binance", "15m", now, "timeout", false, "none"),
            null, true, now)
        fok("rg-gagal", r2.badge == "Gagal" && r2.sub.contains("timeout"), "$r2")
        val r3 = regimeRow("BTCUSDT",
            RegimeDiag(300, 100, 100, 220, "binance", "15m", now, "", false, "none"),
            null, true, now)
        fok("rg-kurang", r3.badge == "Kurang data" && r3.sub.contains("100"), "$r3")
        val r4 = regimeRow("BTCUSDT",
            RegimeDiag(300, 300, 300, 220, "binance", "15m", now, "", true, "none"),
            Regime("Tren naik", false, now), true, now)
        fok("rg-naik", r4.badge == "Tren naik" && regimeBadgeColor(r4.badge) == 0xFF10B981.toInt(), "$r4")
        val r5 = regimeRow("ETHUSDT",
            RegimeDiag(300, 300, 300, 220, "binance", "15m", now - 45 * 60000, "", true, "disk"),
            Regime("Sideways", true, now - 45 * 60000), true, now)
        fok("rg-basi", r5.badge == "Sideways" && r5.sub.contains("45 mnt") && r5.sub.contains("disk"), "$r5")
    }

    println("FORENSIC-FIX done pass=$fpass fail=$ffail")
    if (ffail > 0) kotlin.system.exitProcess(1)
}
