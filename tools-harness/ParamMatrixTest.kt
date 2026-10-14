import com.aether.signal.premium.engine.*

var mpass = 0
var mfail = 0
fun mok(n: String, c: Boolean, x: String = "") {
    if (c) { mpass++; println("PASS $n $x") } else { mfail++; println("FAIL $n $x") }
}

val STRATEGY_IDS = listOf(
    "ema_trend", "ema_cross", "rsi", "macd", "bollinger", "stochastic",
    "adx", "supertrend", "vwap", "support_resistance", "breakout",
    "pullback", "market_structure", "bos", "choch", "liquidity_sweep",
    "order_block", "fvg", "breaker", "fibonacci", "smc_basic", "ict_setup",
    "volume", "mtf_confirm", "donchian_momentum"
)

fun trendData(n: Int = 400, seed: Long = 21L): Pair<List<Candle>, Cache> {
    var rnd = seed
    fun rand(): Double {
        rnd = (rnd * 6364136223846793005L + 1442695040888963407L) ushr 11
        return (rnd.toDouble() / Long.MAX_VALUE.toDouble() + 1.0) / 2.0
    }
    val cs = ArrayList<Candle>()
    var px = 100.0
    var t = 1_700_000_000_000L
    repeat(n) {
        val o = px
        val c = (o * (1 + 0.0012 + (rand() - 0.5) * 0.004)).coerceAtLeast(1.0)
        cs.add(Candle(t, o, maxOf(o, c) * 1.001, minOf(o, c) * 0.999, c, 50 + rand() * 200))
        px = c; t += 900000L
    }
    return cs to buildCache(cs)
}

fun main() {
    val (cs, cache) = trendData()
    val garbage = mapOf("plen" to 99.0, "foo" to 1.0, "bar" to 2.0)

    // 1) Kunci sampah tidak memengaruhi strategi apa pun (25/25 identik).
    var ignoreN = 0
    val affected = ArrayList<String>()
    for (id in STRATEGY_IDS) {
        var same = true
        for (k in 50 until cs.size) {
            if (strategyFn(id, cs, k, cache, emptyMap()) !=
                strategyFn(id, cs, k, cache, garbage)
            ) { same = false; break }
        }
        if (same) ignoreN++ else affected.add(id)
    }
    mok("m-garbage-diabaikan-semua", ignoreN == 25, "ignore=$ignoreN/25 affected=$affected")

    // 2) Kunci TERDOKUMENTASI memengaruhi hanya rsi & adx.
    // rsi: penurunan curam + pantulan (ambang 30 vs 10 memberi himpunan beda).
    val (rs, rcache) = run {
        val cs = ArrayList<Candle>()
        var px = 100.0
        var t = 1_700_000_000_000L
        repeat(40) { px *= 1.0005; cs.add(Candle(t, px / 1.0005, px * 1.0005, px * 0.9995, px, 100.0)); t += 900000L }
        repeat(25) { px *= 0.992; cs.add(Candle(t, px / 0.992, px * 1.0005, px * 0.9995, px, 100.0)); t += 900000L }
        repeat(5) { px *= 1.004; cs.add(Candle(t, px / 1.004, px * 1.001, px * 0.999, px, 100.0)); t += 900000L }
        cs to buildCache(cs)
    }
    var rsiDiffers = false
    for (k in 40 until rs.size) {
        if (strategyFn("rsi", rs, k, rcache, emptyMap()) !=
            strategyFn("rsi", rs, k, rcache, mapOf("oversold" to 10.0, "overbought" to 90.0))
        ) { rsiDiffers = true; break }
    }
    // adx: ambang 101 tak-tercapai vs default 25 (ADX jenuh tepat 100 pada
    // tren kuat tetap lolos ambang 100 — karena itu dipakai 101).
    var adxDiffers = false
    for (k in 50 until cs.size) {
        if (strategyFn("adx", cs, k, cache, emptyMap()) !=
            strategyFn("adx", cs, k, cache, mapOf("adxMin" to 101.0))
        ) { adxDiffers = true; break }
    }
    mok("m-rsi-membaca-ambang", rsiDiffers)
    mok("m-adx-membaca-ambang", adxDiffers)

    // 3) 23 strategi lain: bahkan kunci "masuk akal" pun diabaikan.
    val plausible = mapOf("oversold" to 10.0, "overbought" to 90.0, "adxMin" to 100.0,
        "period" to 99.0, "length" to 99.0, "mult" to 9.0)
    var ignorePlausible = 0
    val leaked = ArrayList<String>()
    for (id in STRATEGY_IDS - setOf("rsi", "adx")) {
        var same = true
        for (k in 50 until cs.size) {
            if (strategyFn(id, cs, k, cache, emptyMap()) !=
                strategyFn(id, cs, k, cache, plausible)
            ) { same = false; break }
        }
        if (same) ignorePlausible++ else leaked.add(id)
    }
    mok("m-23-abaikan-param", ignorePlausible == 23, "ignore=$ignorePlausible/23 leaked=$leaked")

    println("PARAM-MATRIX done pass=$mpass fail=$mfail")
    if (mfail > 0) kotlin.system.exitProcess(1)
}
