package com.aether.signal.premium.engine

// P6: audit konsistensi sinyal live vs backtest — TANPA mengubah mesin.
// Memakai jalur keputusan yang SAMA (decideAt + applyFilters).
// Catatan jujur: backtest mengevaluasi dengan konteks filter yang BERKEMBANG
// (cooldown/dup mengingat sinyal & exit sebelumnya), sedangkan keputusan live
// dihitung dengan konteks segar. Karena itu verdict juga membandingkan
// komputasi konteks-segar vs konteks-segar (harus selalu cocok — kode sama),
// lalu membandingkannya dengan trade yang benar-benar diambil.

enum class AuditVerdict { MATCH, MISMATCH, UNCOMPARABLE }

data class AuditReport(
    val verdict: AuditVerdict,
    val reason: String,
    val asset: String,
    val timeframe: String,
    val strategy: String,
    val barTime: Long,
    val liveDirection: String?,
    val tradeDirection: String?,
    val candlesUsed: Int
)

/**
 * @param candles data yang SAMA (sudah dinormalisasi pemanggil bila perlu).
 * @param params konfigurasi yang SAMA dengan backtest yang dibandingkan.
 * @param result hasil backtest atas candles+params tersebut.
 */
fun auditLiveVsBacktest(
    rawCandles: List<Any?>,
    params: BacktestParams,
    result: BacktestResult
): AuditReport {
    val tag = Triple(params.asset, params.timeframe, labelOf(params))
    if (result.error != null) {
        return AuditReport(AuditVerdict.UNCOMPARABLE,
            "Hasil backtest mengandung error, tidak ada keputusan pembanding.",
            tag.first, tag.second, tag.third, 0, null, null, 0)
    }
    val candles = try { normalizeCandles(rawCandles) } catch (e: Exception) {
        return AuditReport(AuditVerdict.UNCOMPARABLE,
            "Data candle tidak valid: ${e.message}", tag.first, tag.second, tag.third, 0, null, null, 0)
    }
    if (candles.size < MIN_CANDLES + 1) {
        return AuditReport(AuditVerdict.UNCOMPARABLE,
            "Butuh minimal ${MIN_CANDLES + 1} candle agar ada bar tertutup untuk diaudit (dapat ${candles.size}).",
            tag.first, tag.second, tag.third, 0, null, null, candles.size)
    }
    // Bar tertutup terakhir (bar paling akhir bisa jadi belum tutup di live).
    val idx = candles.size - 2
    val cache = buildCache(candles)
    val live = try {
        val dec = decideAt(candles, idx, cache, params)
        if (!dec.passed) null
        else {
            val fr = applyFilters(candles, idx, cache, dec.direction,
                params.filters.filter { it.enabled }, FilterCtx(lastExit = -1000000000))
            if (!fr.passed) null else dec.direction
        }
    } catch (e: Exception) {
        return AuditReport(AuditVerdict.UNCOMPARABLE,
            "Keputusan live gagal dihitung: ${e.message}", tag.first, tag.second, tag.third,
            candles[idx].t, null, null, candles.size)
    }
    // Trade backtest yang entry-nya berasal dari sinyal bar idx (entry di open idx+1).
    val entryT = candles[idx + 1].t
    val trades = result.trades.filter { it.entryTime == entryT }
    val tradeDir = trades.firstOrNull()?.direction
    return when {
        live == null && tradeDir == null -> AuditReport(AuditVerdict.MATCH,
            "Cocok: mesin live dan backtest sama-sama tidak memberi sinyal pada bar ${fmtBar(candles[idx].t)}.",
            tag.first, tag.second, tag.third, candles[idx].t, null, null, candles.size)
        live != null && live == tradeDir -> AuditReport(AuditVerdict.MATCH,
            "Cocok: keduanya memberi sinyal $live pada bar ${fmtBar(candles[idx].t)}.",
            tag.first, tag.second, tag.third, candles[idx].t, live, tradeDir, candles.size)
        else -> AuditReport(AuditVerdict.MISMATCH,
            "Berbeda pada bar ${fmtBar(candles[idx].t)} (live=${live ?: "diam"}, backtest=${tradeDir ?: "diam"}). " +
                "Penyebab umum: konteks filter backtest yang berkembang (cooldown/duplikat dari sinyal " +
                "sebelumnya) atau posisi masih terbuka — bukan perbedaan rumus, karena keduanya memakai decideAt yang sama.",
            tag.first, tag.second, tag.third, candles[idx].t, live, tradeDir, candles.size)
    }
}

private fun labelOf(p: BacktestParams): String {
    val c = p.combo
    return if (c == null || c.strategies.isEmpty()) p.strategy
    else c.strategies.joinToString("+") + "(${c.mode.ifEmpty { "OR" }})"
}

private fun fmtBar(t: Long): String {
    return try {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = t
        String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d UTC",
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE))
    } catch (e: Exception) { t.toString() }
}
