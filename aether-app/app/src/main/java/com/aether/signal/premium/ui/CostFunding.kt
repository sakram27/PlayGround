package com.aether.signal.premium.ui

import com.aether.signal.premium.data.FundingEvent
import com.aether.signal.premium.engine.Candle
import com.aether.signal.premium.engine.Trade

// FITUR 4/6 (V20): sensitivitas biaya + funding — MURNI & teruji JVM.
// Sensitivitas dijalankan ulang via runBacktest (Activity, seperti ablation):
// di sini hanya definisi skenario + label asumsi. Funding diterapkan pasca-hoc
// pada trade nyata + candle nyata + event funding nyata (tanpa karangan).

// ---------- F4: skenario (fraksi, bukan persen) ----------
data class CostScenario(val fee: Double, val slip: Double, val label: String)

val FEE_SCENARIOS = listOf(0.0, 0.0005, 0.001, 0.002)
val SLIP_SCENARIOS = listOf(0.0, 0.0002, 0.0005, 0.001)

fun costLabel(fee: Double, slip: Double): String =
    "fee ${String.format(java.util.Locale.US, "%.3f", fee * 100)}% · slip ${String.format(java.util.Locale.US, "%.3f", slip * 100)}%"

fun costScenarios(): List<CostScenario> {
    val out = ArrayList<CostScenario>()
    for (f in FEE_SCENARIOS) for (s in SLIP_SCENARIOS) out.add(CostScenario(f, s, costLabel(f, s)))
    return out
}

const val COST_ASSUMPTION_NOTE =
    "Asumsi berlabel: slippage flat per sisi (spread/volume aktual tak tersedia); " +
        "bukan slippage aktual, bukan partial fill."

// ---------- F6: penerapan funding ----------
data class FundingApplication(
    val eventsUsed: Int,
    val eventsSkipped: Int,
    val totalFunding: Double,
    val netWithFunding: Double,
    val estimated: Boolean
)

/**
 * Terapkan funding ke trade nyata. Untuk tiap trade, tiap event dengan
 * entryTime < ft <= exitTime: biaya = qty × close-pada-event × rate.
 * LONG membayar rate positif (biaya +), SHORT menerima (biaya −).
 * close-pada-event = close candle terakhir dengan t ≤ ft (tanpa karangan;
 * event tanpa candle pendukung dilewati & dihitung).
 */
fun applyFunding(
    trades: List<Trade>,
    candles: List<Candle>,
    events: List<FundingEvent>,
    estimated: Boolean = false
): FundingApplication {
    var used = 0
    var skipped = 0
    var total = 0.0
    for (t in trades) {
        if (!t.qty.isFinite() || t.qty <= 0) continue
        for (e in events) {
            if (e.t <= t.entryTime || e.t > t.exitTime) continue
            if (!e.rate.isFinite()) continue
            var mark = Double.NaN
            for (c in candles) {
                if (c.t > e.t) break
                if (c.c.isFinite()) mark = c.c
            }
            if (!mark.isFinite() || mark <= 0) {
                skipped++
                continue
            }
            val signed = if (t.direction == "SHORT") -e.rate else e.rate
            val cost = t.qty * mark * signed
            if (!cost.isFinite()) {
                skipped++
                continue
            }
            total += cost
            used++
        }
    }
    val net = trades.sumOf { if (it.pnl.isFinite()) it.pnl else 0.0 } - total
    return FundingApplication(used, skipped, total, net, estimated)
}

/** Event sintetis tiap 8 jam UTC — HANYA untuk skenario estimasi manual (berlabel). */
fun syntheticFunding(fromT: Long, toT: Long, rate: Double): List<FundingEvent> {
    if (fromT <= 0 || toT <= fromT || !rate.isFinite()) return emptyList()
    val out = ArrayList<FundingEvent>()
    var t = (fromT / 28800000L + 1) * 28800000L
    var guard = 0
    while (t <= toT && guard++ < 5000) {
        out.add(FundingEvent(t, rate))
        t += 28800000L
    }
    return out
}
