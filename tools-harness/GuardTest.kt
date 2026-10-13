import com.aether.signal.premium.engine.FilterCfg
import com.aether.signal.premium.engine.configFingerprint
import com.aether.signal.premium.engine.sanitizeParams
import com.aether.signal.premium.ui.EngineGlobalsData
import com.aether.signal.premium.ui.EngineGlobalsStore
import com.aether.signal.premium.ui.EngineSnapshot
import com.aether.signal.premium.ui.StartDecision
import com.aether.signal.premium.ui.applySnapshotToStore
import com.aether.signal.premium.ui.buildParamsFrom
import com.aether.signal.premium.ui.decideStartAction
import com.aether.signal.premium.ui.diffSnapshots
import com.aether.signal.premium.ui.effectiveGlobalsForApply
import com.aether.signal.premium.ui.freezeSession
import com.aether.signal.premium.data.fetchUniverse
import com.aether.signal.premium.ui.notifStageOf
import com.aether.signal.premium.data.validateCustomPair
import com.aether.signal.premium.ui.validateSnapshotForApply

var gpass = 0
var gfail = 0
fun gok(n: String, c: Boolean, x: String = "") {
    if (c) { gpass++; println("PASS $n $x") } else { gfail++; println("FAIL $n $x") }
}

class FakeGlobals(var d: EngineGlobalsData, var failOnRestore: Boolean = false) : EngineGlobalsStore {
    override fun capture(): EngineGlobalsData = d
    override fun restore(x: EngineGlobalsData) {
        if (failOnRestore) throw RuntimeException("injeksi-gagal-tulis")
        d = x
    }
}

fun baseGlobals(): EngineGlobalsData = EngineGlobalsData(
    strategy = "ema_trend", timeframe = "15m", provider = "binance",
    pair = "BTCUSDT", mode = "single", engPairs = listOf("BTCUSDT"),
    limit = 500
)

fun snapOf(
    g: EngineGlobalsData, pairs: List<String>, provider: String = "binance",
    mode: String = "single", status: String = "SUCCESS", valid: Boolean = true,
    origin: String = "backtest-single"
): EngineSnapshot {
    val p = sanitizeParams(buildParamsFrom(g, pairs.first()))
    return EngineSnapshot(g, p, pairs, provider, g.limit, mode, 123456789L, status, valid, origin)
}

fun main() {
    // 1. Sama → tanpa dialog.
    run {
        val g = baseGlobals()
        val cur = snapOf(g, listOf("BTCUSDT")).copy(origin = "engine-live", status = "LIVE")
        val ref = snapOf(g, listOf("BTCUSDT"))
        val drift = diffSnapshots(cur, ref)
        gok("t3-01-sama-tanpa-dialog", drift.isEmpty() &&
            decideStartAction(false, ref, drift) == StartDecision.START_DIRECT,
            "drift=${drift.size}")
    }
    // 2. Strategi berbeda → drift terdeteksi.
    run {
        val g = baseGlobals()
        val cur = snapOf(g, listOf("BTCUSDT")).copy(origin = "engine-live", status = "LIVE")
        val ref = snapOf(g.copy(strategy = "macd"), listOf("BTCUSDT"))
        val drift = diffSnapshots(cur, ref)
        gok("t3-02-drift-strategi", drift.any { it.field == "strategi" } &&
            decideStartAction(false, ref, drift) == StartDecision.SHOW_DIALOG,
            drift.joinToString(";") { "${it.field}:${it.current}->${it.reference}" })
    }
    // 3. Filter berbeda → drift terdeteksi.
    run {
        val g = baseGlobals()
        val cur = snapOf(g, listOf("BTCUSDT")).copy(origin = "engine-live", status = "LIVE")
        val ref = snapOf(g.copy(filterOn = mapOf("trend" to true)), listOf("BTCUSDT"))
        val drift = diffSnapshots(cur, ref)
        gok("t3-03-drift-filter", drift.any { it.field == "filter trend" } &&
            decideStartAction(false, ref, drift) == StartDecision.SHOW_DIALOG,
            drift.joinToString(";") { it.field })
    }
    // 4. Timeframe berbeda → drift terdeteksi.
    run {
        val g = baseGlobals()
        val cur = snapOf(g, listOf("BTCUSDT")).copy(origin = "engine-live", status = "LIVE")
        val ref = snapOf(g.copy(timeframe = "1h"), listOf("BTCUSDT"))
        val drift = diffSnapshots(cur, ref)
        gok("t3-04-drift-timeframe", drift.any { it.field == "timeframe" } &&
            decideStartAction(false, ref, drift) == StartDecision.SHOW_DIALOG,
            drift.joinToString(";") { "${it.field}:${it.current}->${it.reference}" })
    }
    // 5. Pasangan berbeda → drift terdeteksi.
    run {
        val g = baseGlobals()
        val cur = snapOf(g, listOf("BTCUSDT")).copy(origin = "engine-live", status = "LIVE")
        val ref = snapOf(g, listOf("BTCUSDT", "ETHUSDT"), mode = "multi")
        val drift = diffSnapshots(cur, ref)
        gok("t3-05-drift-pairs", drift.any { it.field == "pasangan" } &&
            decideStartAction(false, ref, drift) == StartDecision.SHOW_DIALOG,
            drift.joinToString(";") { "${it.field}:${it.current}->${it.reference}" })
    }
    // 6. Batal → tidak ada mutasi, Engine tak dimulai (keputusan DIALOG + tak ada tulis).
    run {
        val store = FakeGlobals(baseGlobals())
        val before = store.capture()
        val ref = snapOf(baseGlobals().copy(strategy = "macd"), listOf("BTCUSDT"))
        val drift = diffSnapshots(
            snapOf(baseGlobals(), listOf("BTCUSDT")).copy(origin = "engine-live", status = "LIVE"), ref)
        val dec = decideStartAction(false, ref, drift)
        // Simulasi Batal: tidak memanggil apply/start → state identik.
        gok("t3-06-batal-tanpa-mutasi", dec == StartDecision.SHOW_DIALOG && store.capture() == before)
    }
    // 7. Pakai konfigurasi Engine saat ini → sesi beku = rebuild global aktif.
    run {
        val g = baseGlobals().copy(strategy = "rsi", tpMode = 2)
        val sess = freezeSession(g, listOf("BTCUSDT"), 999L)
        val expect = configFingerprint(sanitizeParams(buildParamsFrom(g, "BTCUSDT")))
        gok("t3-07-sesi-sama-dengan-live", sess.fp == expect && sess.pairs == listOf("BTCUSDT") &&
            sess.provider == "binance", "fp=${sess.fp.take(12)}")
    }
    // 8. Terapkan backtest valid (multi) → global + fp sesuai snapshot.
    run {
        val store = FakeGlobals(baseGlobals())
        val rg = baseGlobals().copy(
            strategy = "macd", timeframe = "1h", filterOn = mapOf("adx" to true, "volume" to true))
        val ref = snapOf(rg, listOf("BTCUSDT", "ETHUSDT"), mode = "multi", origin = "backtest-multi")
        val res = applySnapshotToStore(store, ref)
        val rebuiltFp = try {
            configFingerprint(sanitizeParams(buildParamsFrom(
                effectiveGlobalsForApply(ref), ref.params.asset)))
        } catch (e: Exception) { "ERR" }
        gok("t3-08-apply-multi-valid", res.ok && store.d.strategy == "macd" &&
            store.d.timeframe == "1h" && store.d.mode == "multi" &&
            store.d.engPairs == listOf("BTCUSDT", "ETHUSDT") &&
            store.d.multiSel == listOf("BTCUSDT", "ETHUSDT") &&
            rebuiltFp == configFingerprint(ref.params),
            "fp=${rebuiltFp.take(12)} vs ${configFingerprint(ref.params).take(12)}")
    }
    // 9a. Strategi tak dikenal → ditolak tanpa perubahan.
    run {
        val store = FakeGlobals(baseGlobals())
        val before = store.capture()
        val g = baseGlobals()
        val bad = snapOf(g, listOf("BTCUSDT")).copy(
            params = sanitizeParams(buildParamsFrom(g, "BTCUSDT")).copy(strategy = "alien_x"))
        val res = applySnapshotToStore(store, bad)
        gok("t3-09a-tolak-strategi-asing", !res.ok &&
            res.reasons.any { it.contains("alien_x") } && store.capture() == before,
            res.reasons.joinToString(";"))
    }
    // 9b. Filter tak dikenal → ditolak tanpa perubahan.
    run {
        val store = FakeGlobals(baseGlobals())
        val before = store.capture()
        val g = baseGlobals()
        val p = sanitizeParams(buildParamsFrom(g, "BTCUSDT"))
        val bad = snapOf(g, listOf("BTCUSDT")).copy(
            params = p.copy(filters = p.filters + FilterCfg("alien_f", true, emptyMap(), null)))
        val res = applySnapshotToStore(store, bad)
        gok("t3-09b-tolak-filter-asing", !res.ok &&
            res.reasons.any { it.contains("alien_f") } && store.capture() == before,
            res.reasons.joinToString(";"))
    }
    // 10. Pair tak didukung provider → ditolak, tak dibuang diam-diam.
    run {
        val store = FakeGlobals(baseGlobals())
        val before = store.capture()
        val g = baseGlobals()
        val bad = snapOf(g, listOf("XAUUSD"))
        val res = applySnapshotToStore(store, bad)
        gok("t3-10-tolak-pair-binance", !res.ok &&
            res.reasons.any { it.contains("XAUUSD") } && store.capture() == before,
            res.reasons.joinToString(";"))
    }
    // 11. Snapshot basi/invalid → tak dapat diterapkan; start langsung.
    run {
        val g = baseGlobals()
        val stale = snapOf(g, listOf("BTCUSDT"), status = "FAILED", valid = false)
        val v = validateSnapshotForApply(stale)
        val drift = listOf(com.aether.signal.premium.ui.DriftItem("strategi", "a", "b"))
        gok("t3-11-snapshot-basi", !v.ok &&
            decideStartAction(false, stale, drift) == StartDecision.START_DIRECT,
            v.reasons.joinToString(";"))
    }
    // 12. Engine berjalan → perubahan aktif diblokir.
    run {
        val ref = snapOf(baseGlobals().copy(strategy = "macd"), listOf("BTCUSDT"))
        val drift = diffSnapshots(
            snapOf(baseGlobals(), listOf("BTCUSDT")).copy(origin = "engine-live", status = "LIVE"), ref)
        gok("t3-12-blok-saat-running",
            decideStartAction(true, ref, drift) == StartDecision.BLOCK_RUNNING)
    }
    // 13a. Gagal tulis → state utuh.
    run {
        val store = FakeGlobals(baseGlobals(), failOnRestore = true)
        val before = store.capture()
        val ref = snapOf(baseGlobals().copy(strategy = "macd"), listOf("BTCUSDT"))
        val res = applySnapshotToStore(store, ref)
        gok("t3-13a-rollback-gagal-tulis", !res.ok && store.capture() == before,
            res.reasons.joinToString(";"))
    }
    // 13b. Rebuild pasca-terap beda dari snapshot → rollback.
    run {
        val store = FakeGlobals(baseGlobals())
        val before = store.capture()
        val g = baseGlobals()
        // Snapshot licik: params macd tetapi globals ema_trend.
        val sneaky = snapOf(g.copy(strategy = "macd"), listOf("BTCUSDT")).copy(globals = g)
        val res = applySnapshotToStore(store, sneaky)
        gok("t3-13b-rollback-verifikasi", !res.ok && store.capture() == before,
            res.reasons.joinToString(";"))
    }
    // 14. Stop lalu start ulang → sesi baru, fp mengikuti pilihan terakhir, tanpa sesi ganda.
    run {
        val g1 = baseGlobals()
        val s1 = freezeSession(g1, listOf("BTCUSDT"), 100L)
        // Stop: sesi dibuang.
        var active: com.aether.signal.premium.ui.EngineSession? = s1
        active = null
        // Start ulang dengan konfigurasi berbeda.
        val g2 = baseGlobals().copy(strategy = "supertrend", timeframe = "1h")
        val s2 = freezeSession(g2, listOf("BTCUSDT", "ETHUSDT"), 200L)
        active = s2
        gok("t3-14-restart-sesi-baru", active != null && active.fp != s1.fp &&
            active.pairs == listOf("BTCUSDT", "ETHUSDT") &&
            active.fp == configFingerprint(sanitizeParams(buildParamsFrom(g2, "BTCUSDT"))),
            "fp1=${s1.fp.take(8)} fp2=${active.fp.take(8)}")
    }
    // Tambahan: clamp diam-diam dilarang — input di luar rentang ditolak eksplisit.
    run {
        val store = FakeGlobals(baseGlobals())
        val before = store.capture()
        val g = baseGlobals()
        val p = sanitizeParams(buildParamsFrom(g, "BTCUSDT"))
        val over = snapOf(g, listOf("BTCUSDT")).copy(params = p.copy(riskPerTrade = 0.99))
        val res = applySnapshotToStore(store, over)
        gok("t3-15-tolak-clamp-diam", !res.ok &&
            res.reasons.any { it.contains("risiko") } && store.capture() == before,
            res.reasons.joinToString(";"))
    }
    // Tambahan: pemetaan pair single/multi eksplisit.
    run {
        val single = snapOf(baseGlobals(), listOf("ETHUSDT"))
        val e1 = effectiveGlobalsForApply(single)
        val multi = snapOf(baseGlobals(), listOf("BTCUSDT", "ETHUSDT"), mode = "multi")
        val e2 = effectiveGlobalsForApply(multi)
        gok("t3-16-pemetaan-pairs", e1.mode == "single" && e1.pair == "ETHUSDT" &&
            e1.engPairs == listOf("ETHUSDT") && e2.mode == "multi" &&
            e2.multiSel == listOf("BTCUSDT", "ETHUSDT") &&
            e2.universe.containsAll(listOf("BTCUSDT", "ETHUSDT")) &&
            e2.engPairs == listOf("BTCUSDT", "ETHUSDT"))
    }
    guardTest5()
    println("GUARD-TEST done pass=$gpass fail=$gfail")
    if (gfail > 0) kotlin.system.exitProcess(1)
}

fun guardTest5() {
    // 5E: pemetaan tahap notifikasi terpadu.
    run {
        gok("t5e-stage-map", notifStageOf("DIKIRIM") == "NOTIFICATION_SUBMITTED" &&
            notifStageOf("DITAHAN") == "NOTIFICATION_HELD" &&
            notifStageOf("GAGAL") == "NOTIFICATION_FAILED" &&
            notifStageOf("GAGAL_IZIN") == "NOTIFICATION_FAILED")
    }
    // 5F: universe offline (demo/yahoo) — diminta vs diterima, tanpa fallback.
    run {
        val d0 = fetchUniverse("demo", 0)
        val d6 = fetchUniverse("demo", 200)
        val dOver = fetchUniverse("demo", 500)
        gok("t5f-demo", d0.requested == 1 && d0.pairs.size == 1 && !d0.fallback &&
            d6.requested == 200 && d6.pairs.size == 6 && !d6.fallback &&
            dOver.requested == 200 && dOver.pairs.size == 6)
        val y = fetchUniverse("yahoo", 200)
        gok("t5f-yahoo", !y.fallback && y.pairs.isNotEmpty() &&
            y.pairs.size == y.pairs.distinct().size &&
            y.pairs.size <= 200 && y.requested == 200, "n=${y.pairs.size}")
    }
    // 5F: normalisasi batch — duplikat ternormalisasi ke simbol sama (kontainer
    // Set di pemanggil yang mendedup), simbol tak valid ditolak.
    run {
        val raw = listOf("BTC/USDT", "btcusdt", "ETHUSDT", "XAUUSD", "", "BTCUSDT")
        val normed = raw.mapNotNull { s ->
            val (n, e) = validateCustomPair(s, "binance")
            if (e == null) n else null
        }
        gok("t5f-batch", normed.size == 4 && normed.distinct().size == 2 &&
            normed.toSet() == setOf("BTCUSDT", "ETHUSDT") &&
            LinkedHashSet(normed).size == 2,
            "norm=$normed")
    }
    // 5F: batas provider tak dikenal → fallback eksplisit, bukan daftar diam-diam.
    run {
        val bad = fetchUniverse("nope_exchange", 200)
        gok("t5f-unknown-prov", bad.fallback && bad.error != null, "err=${bad.error}")
    }
}
