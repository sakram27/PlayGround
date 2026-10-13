package com.aether.signal.premium.engine

import kotlin.math.abs

// Mini JSON parser untuk harness uji (objek/array/string/angka/bool/null).
class JsonParser(val s: String) {
    var p = 0
    fun parse(): Any? {
        ws()
        return when (s[p]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { p += 4; true }
            'f' -> { p += 5; false }
            'n' -> { p += 4; null }
            else -> num()
        }
    }
    fun ws() { while (p < s.length && s[p].isWhitespace()) p++ }
    fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        p++
        ws()
        if (s[p] == '}') { p++; return m }
        while (true) {
            ws(); val k = str(); ws()
            p++ // :
            val v = parse(); m[k] = v
            ws()
            if (s[p] == ',') { p++; continue }
            p++ // }
            break
        }
        return m
    }
    fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        p++
        ws()
        if (s[p] == ']') { p++; return l }
        while (true) {
            l.add(parse()); ws()
            if (s[p] == ',') { p++; continue }
            p++
            break
        }
        return l
    }
    fun str(): String {
        p++
        val sb = StringBuilder()
        while (s[p] != '"') {
            if (s[p] == '\\') {
                p++
                when (s[p]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'u' -> { sb.append(s.substring(p + 1, p + 5).toInt(16).toChar()); p += 4 }
                    else -> sb.append(s[p])
                }
            } else sb.append(s[p])
            p++
        }
        p++
        return sb.toString()
    }
    fun num(): Double {
        val st = p
        while (p < s.length && s[p] in "-+0123456789.eE") p++
        return s.substring(st, p).toDouble()
    }
}

@Suppress("UNCHECKED_CAST")
fun runEquiv(vectorsPath: String): Int {
    val root = JsonParser(java.io.File(vectorsPath).readText()).parse() as Map<String, Any?>
    val candlesRaw = (root["candles"] as List<Any?>).map { it as Map<String, Any?> }
    val candles: List<Any?> = candlesRaw.map { m ->
        mapOf("t" to (m["t"] as Double).toLong(), "o" to m["o"] as Double, "h" to m["h"] as Double,
            "l" to m["l"] as Double, "c" to m["c"] as Double, "v" to m["v"] as Double)
    }
    var pass = 0; var fail = 0
    fun chk(name: String, cond: Boolean, extra: String = "") {
        if (cond) { pass++; println("PASS $name $extra") } else { fail++; println("FAIL $name $extra") }
    }
    fun dbl(m: Map<String, Any?>, k: String): Double = (m[k] as? Double) ?: Double.NaN
    fun close(a: Double, b: Double, tol: Double = 1e-9): Boolean {
        if (a.isNaN() && b.isNaN()) return true
        if (!a.isFinite() || !b.isFinite()) return a == b
        if (a == 0.0 && b == 0.0) return true
        return abs(a - b) <= tol * maxOf(1.0, abs(a), abs(b))
    }
    val cases = root["cases"] as List<Any?>
    for (cc in cases) {
        val c = cc as Map<String, Any?>
        val name = c["name"] as String
        val pm = c["params"] as Map<String, Any?>
        val exp = c["result"] as Map<String, Any?>
        val filters = ((pm["filters"] as? List<Any?>) ?: emptyList<Any?>()).map { f ->
            val fm = f as Map<String, Any?>
            val prm = ((fm["params"] as? Map<String, Any?>) ?: emptyMap()).mapValues { (it.value as Double) }
            FilterCfg(fm["name"] as String, (fm["enabled"] as? Boolean) ?: true, prm)
        }
        val combo = (pm["combo"] as? Map<String, Any?>)?.let {
            Combo(it["mode"] as String, (it["strategies"] as List<Any?>).map { s -> s as String })
        }
        val params = BacktestParams(
            asset = pm["asset"] as String, timeframe = pm["timeframe"] as String,
            strategy = (pm["strategy"] as? String) ?: "ema_trend",
            combo = combo, filters = filters,
            startDate = ((pm["startDate"] as? Double) ?: 0.0).toLong(),
            endDate = ((pm["endDate"] as? Double) ?: 0.0).toLong(),
            useAtr = (pm["useAtr"] as? Boolean) ?: false
        )
        val src = if (name == "e-short") candles.take(30) else candles
        val t0 = System.nanoTime()
        val got = runBacktest(src, params)
        val ms = (System.nanoTime() - t0) / 1e6
        val expErr = exp["error"] as? String
        chk("$name/error", (got.error ?: null) == expErr, "err=${got.error} ms=${"%.1f".format(ms)}")
        if (expErr == null && got.error == null) {
            val keys = listOf("totalTrades", "wins", "losses", "expired", "filtered", "longestWinStreak", "longestLossStreak")
            for (k in keys) chk("$name/$k", gotInt(got, k) == (exp[k] as Double).toInt(), "got=${gotInt(got, k)} exp=${(exp[k] as Double).toInt()}")
            val dk = listOf("winRate", "lossRate", "grossProfit", "grossLoss", "netProfit", "netProfitPercent", "profitFactor", "expectancy", "averageWin", "averageLoss", "averageR", "averageRR", "maxDrawdown", "maxDrawdownPercent", "sharpe", "sortino", "calmar", "cagr", "exposure", "avgHolding", "initialCapital", "finalCapital")
            for (k in dk) chk("$name/$k", close(gotDbl(got, k), dbl(exp, k)), "got=${gotDbl(got, k)} exp=${dbl(exp, k)}")
            chk("$name/strategy", got.strategy == exp["strategy"], got.strategy)
            val et = exp["trades"] as List<Any?>
            chk("$name/ntrades", got.trades.size == et.size, "${got.trades.size} vs ${et.size}")
            var tOk = true
            for (idx in got.trades.indices) {
                if (idx >= et.size) { tOk = false; break }
                val a = got.trades[idx]; val b = et[idx] as Map<String, Any?>
                tOk = tOk && a.direction == b["direction"] && a.result == b["result"] &&
                    close(a.entry, dbl(b, "entry")) && close(a.exit, dbl(b, "exit")) &&
                    close(a.pnl, dbl(b, "pnl")) && close(a.rMultiple, dbl(b, "rMultiple")) &&
                    a.entryTime == (b["entryTime"] as Double).toLong() && a.exitTime == (b["exitTime"] as Double).toLong()
                if (!tOk) { println("  trade $idx mismatch: $a vs $b"); break }
            }
            chk("$name/trades", tOk)
            val ee = exp["equity"] as List<Any?>
            var eOk = got.equityCurve.size == ee.size
            if (eOk) for (idx in got.equityCurve.indices) {
                val b = ee[idx] as Map<String, Any?>
                if (got.equityCurve[idx].t != (b["t"] as Double).toLong() || !close(got.equityCurve[idx].equity, dbl(b, "equity"))) { eOk = false; break }
            }
            chk("$name/equity", eOk, "${got.equityCurve.size} pts")
            val ed = exp["diag"] as Map<String, Any?>
            val gd = got.diag
            chk("$name/diag", gd.evaluatedBars == (ed["evaluatedBars"] as Double).toInt() &&
                gd.signalsRaw == (ed["signalsRaw"] as Double).toInt() &&
                gd.filteredOut == (ed["filteredOut"] as Double).toInt() &&
                gd.dateFilteredCount == (ed["dateFilteredCount"] as Double).toInt(),
                "eval=${gd.evaluatedBars} sig=${gd.signalsRaw} filt=${gd.filteredOut}")
        }
    }
    println("\nEQUIV: $pass pass, $fail fail")
    return fail
}

private fun gotInt(r: BacktestResult, k: String): Int = when (k) {
    "totalTrades" -> r.totalTrades; "wins" -> r.wins; "losses" -> r.losses; "expired" -> r.expired
    "filtered" -> r.filtered; "longestWinStreak" -> r.longestWinStreak; "longestLossStreak" -> r.longestLossStreak
    else -> -1
}

private fun gotDbl(r: BacktestResult, k: String): Double = when (k) {
    "winRate" -> r.winRate; "lossRate" -> r.lossRate; "grossProfit" -> r.grossProfit; "grossLoss" -> r.grossLoss
    "netProfit" -> r.netProfit; "netProfitPercent" -> r.netProfitPercent; "profitFactor" -> r.profitFactor
    "expectancy" -> r.expectancy; "averageWin" -> r.averageWin; "averageLoss" -> r.averageLoss
    "averageR" -> r.averageR; "averageRR" -> r.averageRR; "maxDrawdown" -> r.maxDrawdown
    "maxDrawdownPercent" -> r.maxDrawdownPercent; "sharpe" -> r.sharpe; "sortino" -> r.sortino
    "calmar" -> r.calmar; "cagr" -> r.cagr; "exposure" -> r.exposure; "avgHolding" -> r.avgHolding
    "initialCapital" -> r.initialCapital; "finalCapital" -> r.finalCapital
    else -> Double.NaN
}

fun main(args: Array<String>) {
    val code = runEquiv(args.getOrElse(0) { "/tmp/equiv_vectors.json" })
    kotlin.system.exitProcess(if (code == 0) 0 else 1)
}
