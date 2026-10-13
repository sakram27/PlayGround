import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.aether.signal.premium.engine.Candle
import com.aether.signal.premium.ui.App
import com.aether.signal.premium.ui.BotEngine
import com.aether.signal.premium.ui.EngineDiag
import com.aether.signal.premium.ui.NotifBus

var lpass = 0
var lfail = 0
fun lok(n: String, c: Boolean, x: String = "") {
    if (c) { lpass++; println("PASS $n $x") } else { lfail++; println("FAIL $n $x") }
}

// ---------- Test double: SharedPreferences dalam memori ----------
class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    var failEdit = false
    fun clearAll() = map.clear()
    override fun contains(k: String) = map.containsKey(k)
    override fun edit(): SharedPreferences.Editor = Ed()
    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getBoolean(k: String, d: Boolean) = map[k] as? Boolean ?: d
    override fun getFloat(k: String, d: Float) = (map[k] as? Number)?.toFloat() ?: d
    override fun getInt(k: String, d: Int) = (map[k] as? Number)?.toInt() ?: d
    override fun getLong(k: String, d: Long) = (map[k] as? Number)?.toLong() ?: d
    override fun getString(k: String, d: String?) = map[k] as? String ?: d
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String, d: Set<String>?) =
        (map[k] as? Set<String>)?.let { HashSet(it) } ?: d
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

    inner class Ed : SharedPreferences.Editor {
        val put = HashMap<String, Any?>()
        val del = HashSet<String>()
        var clear = false
        override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
        override fun putFloat(k: String, v: Float) = apply { put[k] = v }
        override fun putInt(k: String, v: Int) = apply { put[k] = v }
        override fun putLong(k: String, v: Long) = apply { put[k] = v }
        override fun putString(k: String, v: String?) = apply { put[k] = v }
        override fun putStringSet(k: String, v: Set<String>?) = apply { put[k] = v?.let { HashSet(it) } }
        override fun remove(k: String) = apply { del.add(k) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (failEdit) throw RuntimeException("injeksi-gagal-tulis")
            applyAll(); return true
        }
        override fun apply() {
            if (failEdit) throw RuntimeException("injeksi-gagal-tulis")
            applyAll()
        }
        private fun applyAll() {
            if (clear) map.clear()
            for (k in del) map.remove(k)
            map.putAll(put)
        }
        private fun apply(e: Ed): SharedPreferences.Editor = e
    }
}

// ---------- Test double: Context minimal (tanpa Service/PackageManager nyata) ----------
class FakeContext : ContextWrapper(null) {
    var prefs = FakePrefs()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getSystemService(name: String): Any? =
        java.lang.reflect.Proxy.newProxyInstance(
            java.lang.ClassLoader.getSystemClassLoader(),
            arrayOf(android.app.NotificationManager::class.java)
        ) { _, _, _ -> throw RuntimeException("fake-service") }
}

val fakePrefs = FakePrefs()

// android.jar constructor melempar Stub! — alokasi tanpa konstruktor.
fun newFakeContext(): FakeContext {
    val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    f.isAccessible = true
    val u = f.get(null) as sun.misc.Unsafe
    val c = u.allocateInstance(FakeContext::class.java) as FakeContext
    val pf = FakeContext::class.java.getDeclaredField("prefs")
    pf.isAccessible = true
    pf.set(c, FakePrefs())
    return c
}

val fakeCtx: FakeContext by lazy { newFakeContext() }

fun installPrefs() {
    val f = App.javaClass.getDeclaredField("prefs")
    f.isAccessible = true
    f.set(App, fakePrefs)
}

// prime cache mem ProvidersKt (private) via refleksi: getCandles hit tanpa jaringan.
fun primeMem(provider: String, sym: String, tf: String, lim: Int, candles: List<Candle>) {
    val kt = Class.forName("com.aether.signal.premium.data.ProvidersKt")
    val ck = kt.getDeclaredMethod("ck", String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)
    ck.isAccessible = true
    val key = ck.invoke(null, provider, sym.uppercase(), tf, lim) as String
    val put = kt.getDeclaredMethod("memPut", String::class.java, kotlin.Pair::class.java)
    put.isAccessible = true
    put.invoke(null, key, kotlin.Pair(candles, System.currentTimeMillis()))
}

const val LT0 = 1_700_000_000_000L
fun riseCandles(n: Int, o0: Double, step: Double = 0.001): List<Candle> {
    val out = ArrayList<Candle>()
    var prev = o0
    for (i in 0 until n) {
        val o = prev
        val c = o * (1 + step)
        out.add(Candle(LT0 + i * 900000L, o, maxOf(o, c) * 1.0005, minOf(o, c) * 0.9995, c, 100.0))
        prev = c
    }
    return out
}

fun resetEngine() {
    try { BotEngine.stop() } catch (e: Exception) { }
    // Sesi basi antar-tes harus dibuang eksplisit: stop() early-return saat
    // sudah berhenti sehingga clearSession tak berjalan. Tanpa ini tick
    // manual memakai SESI LAMA (proven by failing M2/M3/E1/M4).
    try { BotEngine.clearSession() } catch (e: Exception) { }
    BotEngine.positions.clear()
    BotEngine.closed.clear()
    BotEngine.equity = null
    BotEngine.onTick = null
    App.signals = ArrayList()
    App.backtestSignals = ArrayList()
    App.legacySignals = ArrayList()
    App.hist = ArrayList()
    App.engRunning = false
    App.regime.clear()
    App.regimeDiag.clear()
    try { NotifBus.clearHistory() } catch (e: Exception) { }
    try { NotifBus.resetDedup() } catch (e: Exception) { }
    EngineDiag.clearVerdicts()
    fakePrefs.clearAll()
}

fun baseCfg(pairs: List<String>, cap: Int = 1, mh: Int = 2000) {
    App.provider = "demo"
    App.timeframe = "15m"
    App.engPairs = LinkedHashSet(pairs)
    App.strategy = "ema_trend"
    App.comboMode = ""
    App.comboExtra = LinkedHashSet()
    App.filterOn = HashMap()
    App.capital = 1000.0
    App.riskPct = 1.0
    App.leverage = 1
    App.feePct = 0.0
    App.slipPct = 0.0
    App.maxHolding = mh
    App.maxOpenTrades = cap
    App.slPct = 50.0
    // TP 20%: valid dua arah (TP 100% -> TP=0 untuk SHORT -> INVALID_LEVEL).
    App.tpPct = 20.0
    App.tpMode = 1
    App.useAtr = false
    App.btTrailOn = false
    App.fromDate = 0L
    App.toDate = 0L
    App.limit = 500
}

fun primeRise(pairs: List<String>, o0: Double = 100.0) {
    for (s in pairs) primeMem("demo", s, "15m", 300, riseCandles(300, o0))
}

fun main() {
    installPrefs()
    try {
        NotifBus.init(fakeCtx)
    } catch (e: Exception) { /* abaikan */ }

    // L1: start ganda — sesi tunggal, pesan sudah-berjalan.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"))
        primeRise(listOf("BTCUSDT"))
        val latch0 = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch0.countDown() }
        val m1 = BotEngine.start()
        val at1 = EngineDiag.startedAt
        val s1fp = BotEngine.activeSession?.fp ?: ""
        // Tunggu tick awal selesai (latch onTick).
        latch0.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val m2 = BotEngine.start()
        lok("L1-start-ganda", App.engRunning && m2.contains("Sudah berjalan") &&
            EngineDiag.startedAt == at1 && BotEngine.activeSession?.fp == s1fp &&
            s1fp.isNotEmpty(), "m1=$m1 m2=$m2")
        BotEngine.stop()
    }
    // L2: cap global live — 3 pair sinyal, cap 1 → tepat 1 posisi + 2 CAP_FULL.
    run {
        resetEngine()
        val pairs = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
        baseCfg(pairs, cap = 1)
        primeRise(pairs)
        BotEngine.start()
        val latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val snap = EngineDiag.snapshot(
            App.engRunning, App.strategy, App.timeframe, pairs,
            BotEngine.positions.size, 0L)
        lok("L2-cap-global-1", BotEngine.positions.size == 1 &&
            snap.rejectedCap == 2 &&
            snap.pairVerdicts.count { it.outcome == "CAP_FULL" } == 2 &&
            snap.pairVerdicts.count { it.outcome == "SIGNAL_CREATED" } == 1,
            "pos=${BotEngine.positions.size} rej=${snap.rejectedCap} " +
                snap.pairVerdicts.joinToString(",") { "${it.sym}=${it.outcome}" })
        BotEngine.stop()
    }
    // L3: slot dibuka kembali — maxHolding=1: buka tick1, tutup tick2, buka tick3.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"), cap = 1, mh = 1)
        primeRise(listOf("BTCUSDT"))
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val n1 = BotEngine.positions.size
        val c1 = BotEngine.closed.size
        BotEngine.tick(true)
        val n2 = BotEngine.positions.size
        val c2 = BotEngine.closed.size
        BotEngine.tick(true)
        val n3 = BotEngine.positions.size
        lok("L3-slot-reopen", n1 == 1 && c1 == 0 && n2 == 0 && c2 == 1 && n3 == 1,
            "t1p=$n1 t2p=$n2/c=$c2 t3p=$n3")
        // Tanpa posisi ganda untuk identitas sama + riwayat konsisten.
        lok("L3-tanpa-ganda", BotEngine.positions.map { it.pair }.toSet().size == BotEngine.positions.size &&
            App.signals.map { "${it.pair}|${it.tf}|${it.t}|${it.dir}" }.toSet().size == App.signals.size,
            "sig=${App.signals.size}")
        BotEngine.stop()
    }
    // L4: stop lalu tick manual — tak ada evaluasi baru.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"))
        primeRise(listOf("BTCUSDT"))
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val e1 = BotEngine.positions.size
        val sg1 = App.signals.size
        BotEngine.stop()
        lok("L4-stop-bersih", !App.engRunning && BotEngine.activeSession == null, "")
        // Tick manual pasca-stop tetap berjalan (siklus penyelesaian) tetapi
        // gerbang engRunning memblokir entry/sinyal baru.
        BotEngine.tick(true)
        lok("L4-tick-pascastop-tanpa-entry", BotEngine.positions.size == e1 && App.signals.size == sg1, "")
    }
    // L5: slate flag end-to-end — stop dengan posisi terbuka → mati → start → amaran.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"))
        primeRise(listOf("BTCUSDT"))
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        BotEngine.stop() // posisi masih terbuka (maxHolding 2000)
        val flag = fakePrefs.map["sess_open"] as? String
        // Simulasi kematian proses: memoriVolatil hilang, prefs + sinyal bertahan.
        BotEngine.positions.clear()
        BotEngine.equity = null
        var latch2 = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch2.countDown() }
        BotEngine.start()
        // Sinkron: start() menetapkan amaran sebelum tick async berjalan
        // (tick butuh ratusan ms komputasi indikator; jendela balapan diabaikan).
        lok("L5-slate-amaran", flag != null && flag.startsWith("1|") &&
            EngineDiag.staleNote.contains("1 posisi paper"),
            "flag=$flag note=${EngineDiag.staleNote.take(40)}")
        latch2.await(20, java.util.concurrent.TimeUnit.SECONDS)
        lok("L5-amaran-hilang", EngineDiag.staleNote.isEmpty() && BotEngine.positions.size == 1, "")
        BotEngine.stop()
    }
    // L6: dedup lintas restart — sinyal sama tak tercatat ganda; posisi dibuka ulang (desain).
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"))
        primeRise(listOf("BTCUSDT"))
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val s1 = App.signals.size
        BotEngine.positions.clear() // crash: posisi hilang, sinyal bertahan
        BotEngine.tick(true)
        lok("L6-dedup-restart", s1 == 1 && App.signals.size == 1 && BotEngine.positions.size == 1,
            "sig=$s1->${App.signals.size} pos=${BotEngine.positions.size}")
        BotEngine.stop()
    }
    // L7: notifikasi HELD — sinyal tersimpan meski ditahan jam tenang.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"))
        primeRise(listOf("BTCUSDT"))
        try {
            NotifBus.quietEnabled = true
            NotifBus.quietStartMin = 0
            NotifBus.quietEndMin = 1439
            NotifBus.quietDays = setOf(1, 2, 3, 4, 5, 6, 7)
        } catch (e: Exception) { }
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val held = try { NotifBus.history().count { it.status == "DITAHAN" } } catch (e: Exception) { -1 }
        lok("L7-held-tak-hilang", App.signals.size == 1 && held == 1,
            "sig=${App.signals.size} held=$held")
        try {
            NotifBus.quietEnabled = false
        } catch (e: Exception) { }
        BotEngine.stop()
    }
    // L8: entryOn mati — senyap total, sinyal tetap tersimpan.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"))
        primeRise(listOf("BTCUSDT"))
        try { NotifBus.entryOn = false } catch (e: Exception) { }
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val h = try { NotifBus.history().size } catch (e: Exception) { -1 }
        lok("L8-toggle-mati", App.signals.size == 1 && h == 0, "sig=${App.signals.size} hist=$h")
        try { NotifBus.entryOn = true } catch (e: Exception) { }
        BotEngine.stop()
    }
    // L9: pair error (Yahoo simbol tak dikenal, tanpa jaringan) — pair lain tetap OK.
    run {
        resetEngine()
        App.provider = "yahoo"
        App.timeframe = "15m"
        App.engPairs = LinkedHashSet(listOf("EUR/USD", "BADSYM"))
        App.strategy = "ema_trend"
        App.comboMode = ""
        App.comboExtra = LinkedHashSet()
        App.filterOn = HashMap()
        App.capital = 1000.0; App.riskPct = 1.0; App.leverage = 1
        App.feePct = 0.0; App.slipPct = 0.0; App.maxHolding = 2000; App.maxOpenTrades = 3
        App.slPct = 50.0; App.tpPct = 100.0; App.tpMode = 1
        App.useAtr = false; App.btTrailOn = false
        App.fromDate = 0L; App.toDate = 0L; App.limit = 500
        primeMem("yahoo", "EUR/USD", "15m", 300, riseCandles(300, 1.10))
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val snap = EngineDiag.snapshot(App.engRunning, App.strategy, App.timeframe,
            App.engPairs.toList(), BotEngine.positions.size, 0L)
        lok("L9-error-isolasi", snap.evalOk >= 1 &&
            snap.pairVerdicts.any { it.sym == "BADSYM" && it.outcome == "PROVIDER_ERROR" },
            "ok=${snap.evalOk} " + snap.pairVerdicts.joinToString(",") { "${it.sym}=${it.outcome}" })
        BotEngine.stop()
    }
    lifecycleTest9()
    println("LIFECYCLE-TEST done pass=$lpass fail=$lfail")
    if (lfail > 0) kotlin.system.exitProcess(1) else kotlin.system.exitProcess(0)
}

fun lifecycleTest9() {
    // D/M1: kandidat valid vs filter-reject pada engine sungguhan (data uji).
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"), cap = 3)
        primeRise(listOf("BTCUSDT"))
        App.filterOn = HashMap<String, Boolean>().also { it["rsi"] = true }
        App.fRsiHi = 72.0
        App.fRsiLo = 28.0
        BotEngine.tick(true)
        val snap = EngineDiag.snapshot(false, App.strategy, App.timeframe,
            listOf("BTCUSDT"), BotEngine.positions.size, 0L)
        lok("M1-filter-reject", BotEngine.positions.isEmpty() && App.signals.isEmpty() &&
            snap.evalOk == 1 && snap.filteredOut > 0 &&
            snap.pairVerdicts.any { it.outcome == "FILTERED" },
            "filtered=${snap.filteredOut} verdicts=" + snap.pairVerdicts.joinToString(",") { it.outcome })
    }
    // D/M2: LONG + SHORT timestamp sama — identitas tak saling menimpa.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT", "ETHUSDT"), cap = 3)
        primeMem("demo", "BTCUSDT", "15m", 300, riseCandles(300, 100.0, 0.001))
        primeMem("demo", "ETHUSDT", "15m", 300, riseCandles(300, 50.0, -0.001))
        var latchM2 = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latchM2.countDown() }
        BotEngine.start()
        latchM2.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val sigs = App.signals
        lok("M2-long-short-sama-t", sigs.size == 2 &&
            sigs.map { it.id }.toSet().size == 2 &&
            sigs.map { it.dir }.toSet() == setOf("LONG", "SHORT") &&
            sigs.map { it.t }.toSet().size == 1 &&
            BotEngine.positions.size == 2,
            "ids=" + sigs.map { it.id })
    }
    // E/M3: batas riwayat 200 — dorong sinyal ke-201, ukuran tetap, terbaru di depan.
    run {
        resetEngine()
        baseCfg(listOf("XRPUSDT"), cap = 3)
        for (k in 0 until 200) {
            App.signals.add(com.aether.signal.premium.ai.Sig("OLD", "15m", "LONG", 1.0, 0.9, 1.1,
                1000L + k, "dryrun-entry", "old$k", strategy = "ema_trend"))
        }
        primeRise(listOf("XRPUSDT"), o0 = 7.0)
        var latchM3 = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latchM3.countDown() }
        BotEngine.start()
        latchM3.await(20, java.util.concurrent.TimeUnit.SECONDS)
        lok("M3-history-cap", App.signals.size == 200 && App.signals.first().pair == "XRPUSDT",
            "size=${App.signals.size} first=${App.signals.firstOrNull()?.pair}")
    }
    // E1: penyimpanan gagal setelah posisi dibuat → rollback, tanpa posisi hantu.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"), cap = 3)
        primeRise(listOf("BTCUSDT"))
        fakePrefs.failEdit = true
        var latchE1 = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latchE1.countDown() }
        BotEngine.start()
        latchE1.await(20, java.util.concurrent.TimeUnit.SECONDS)
        fakePrefs.failEdit = false
        val snap = EngineDiag.snapshot(false, App.strategy, App.timeframe,
            listOf("BTCUSDT"), BotEngine.positions.size, 0L)
        val diskRow = (fakePrefs.map["signals"] as? String)?.contains("BTCUSDT") == true
        lok("E1-store-rollback", BotEngine.positions.isEmpty() &&
            snap.pairVerdicts.any { it.outcome == "STORE_FAILED" } && !diskRow,
            "pos=${BotEngine.positions.size} memSig=${App.signals.size} disk=$diskRow " +
                snap.pairVerdicts.joinToString(",") { it.outcome })
    }
    // E3/M4: pengajuan notifikasi gagal (sistem mati di JVM) → sinyal tetap ada + baris GAGAL_IZIN/GAGAL.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"), cap = 3)
        primeRise(listOf("BTCUSDT"))
        var latchM4 = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latchM4.countDown() }
        BotEngine.start()
        latchM4.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val rows = try { NotifBus.history() } catch (e: Exception) { emptyList() }
        lok("M4-gagal-tak-hapus-sinyal", App.signals.size == 1 && rows.size == 1 &&
            (rows.firstOrNull()?.status == "GAGAL_IZIN" || rows.firstOrNull()?.status == "GAGAL"),
            "sig=${App.signals.size} hist=" + rows.map { it.status })
    }
    // F2: start cepat 5x → satu sesi.
    run {
        resetEngine()
        baseCfg(listOf("BTCUSDT"))
        primeRise(listOf("BTCUSDT"))
        val at0 = EngineDiag.startedAt
        repeat(5) { BotEngine.start() }
        lok("F2-start-cepat", App.engRunning && EngineDiag.startedAt != at0,
            "")
        // Tepat satu sesi aktif (start berikut menolak).
        val m = BotEngine.start()
        lok("F2-tanpa-sesi-ganda", m.contains("Sudah berjalan"), "m=$m")
        BotEngine.stop()
    }
    // F4: stop di tengah tick 60-pair → invarian berlaku untuk semua interleaving.
    run {
        resetEngine()
        val pairs = (1..60).map { "P$it" }
        baseCfg(pairs, cap = 3)
        for (s in pairs) primeMem("demo", s, "15m", 300, riseCandles(300, 100.0 + pairs.indexOf(s)))
        var latch = java.util.concurrent.CountDownLatch(1)
        BotEngine.onTick = { latch.countDown() }
        BotEngine.start()
        BotEngine.stop()
        // Tunggu tick mana pun selesai (batas 20 dtk), lalu verifikasi invarian.
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        // Stop konsisten dinilai SEBELUM tick susulan manual (tick manual
        // membuat sesi baca-saja via ensureSession — bukan sesi Engine).
        val stoppedOk = !App.engRunning && BotEngine.activeSession == null
        val t0 = EngineDiag.lastTickAt
        val deadline = System.currentTimeMillis() + 15000
        var advanced = EngineDiag.lastTickAt != t0
        while (!advanced && System.currentTimeMillis() < deadline) {
            BotEngine.tick(true)
            advanced = EngineDiag.lastTickAt != t0
        }
        lok("F4-stop-tengah-tick", stoppedOk && advanced && BotEngine.positions.size <= 3 &&
            BotEngine.positions.map { it.pair }.toSet().size == BotEngine.positions.size,
            "pos=${BotEngine.positions.size}")
    }
    println("LIFECYCLE9 done")
}
