import com.aether.signal.premium.engine.*

var ipass = 0
var ifail = 0
fun iok(n: String, c: Boolean, x: String = "") {
    if (c) { ipass++; println("PASS $n $x") } else { ifail++; println("FAIL $n $x") }
}

val IT0 = 1_700_000_000_000L
const val ITF = 900000L

fun flatPlusSweepAt(boundary: Int, n: Int = 300): List<Any?> {
    val out = ArrayList<Any?>()
    var t = IT0
    repeat(n) { i ->
        if (i == boundary) {
            out.add(mapOf("t" to t, "o" to 100.0, "h" to 100.6, "l" to 98.0, "c" to 100.5, "v" to 100.0))
        } else {
            out.add(mapOf("t" to t, "o" to 100.0, "h" to 100.1, "l" to 99.9, "c" to 100.0, "v" to 100.0))
        }
        t += ITF
    }
    return out
}

fun main() {
    // F-01a: pola valid TEPAT di bar batas kini fire via pemanggilan langsung.
    run {
        val cs = ArrayList<Candle>()
        var t = IT0
        repeat(20) { cs.add(Candle(t, 100.0, 100.1, 99.9, 100.0, 100.0)); t += ITF }
        cs.add(Candle(t, 100.0, 100.6, 98.0, 100.5, 100.0)) // indeks 20
        val cache = buildCache(cs)
        iok("f01-sweep-bar20-fires",
            strategyFn("liquidity_sweep", cs, 20, cache, emptyMap()).first == "LONG")
    }

    // F-01b: run backtest standar TIDAK PERNAH mengevaluasi bar batas
    // (startIdx = max(prefix+WARMUP=60, 2)). Pola satu-satunya di bar 20
    // menghasilkan NOL trade baik sebelum maupun sesudah fix -> delta historis 0.
    run {
        val p = sanitizeParams(BacktestParams(
            asset = "TST", timeframe = "15m", strategy = "liquidity_sweep",
            maxOpenTrades = 1))
        val r = runBacktest(flatPlusSweepAt(20), p)
        iok("f01-run-ok", r.error == null, "trades=${r.totalTrades}")
        iok("f01-boundary-tidak-dievaluasi",
            r.totalTrades == 0 && r.diag.evaluatedBars > 0,
            "trades=${r.totalTrades} eval=${r.diag.evaluatedBars} startIdx=${r.diag.startIdx}")
        iok("f01-startIdx-60", r.diag.startIdx == 60, "startIdx=${r.diag.startIdx}")
    }

    // F-01c: seri pendek (<62 bar) ditolak MIN_CANDLES — tidak ada jalur
    // produksi yang mengevaluasi bar batas. Fix murni hygiene + pin regresi.
    run {
        val p = sanitizeParams(BacktestParams(
            asset = "TST", timeframe = "15m", strategy = "liquidity_sweep"))
        val r = runBacktest(flatPlusSweepAt(20, 61), p)
        iok("f01-seri-pendek-ditolak", r.error != null,
            "err=${r.error?.take(50)}")
    }

    // F-01d: fvg — gap di bar 0/1, entry tepat di bar 3 (batas lama).
    // Guard lama (i<4) memaksa NONE; guard baru (i<3) mengevaluasi -> LONG.
    run {
        val cs = listOf(
            Candle(IT0, 100.0, 100.2, 99.8, 100.0, 100.0),
            Candle(IT0 + ITF, 101.0, 101.5, 100.8, 101.2, 100.0),
            Candle(IT0 + 2 * ITF, 101.2, 101.3, 100.3, 100.4, 100.0),
            Candle(IT0 + 3 * ITF, 100.4, 100.9, 100.2, 100.8, 100.0)
        )
        val got = strategyFn("fvg", cs, 3, buildCache(cs), emptyMap()).first
        iok("f01-fvg-bar3-fires", got == "LONG", "got=$got (guard lama: NONE)")
    }

    println("F01-IMPACT done pass=$ipass fail=$ifail")
    if (ifail > 0) kotlin.system.exitProcess(1)
}
