package com.aether.signal.premium.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.aether.signal.premium.data.MARKET_TF_LABELS
import com.aether.signal.premium.data.MARKET_TIMEFRAMES
import com.aether.signal.premium.data.MARKET_DEFAULT_TF
import com.aether.signal.premium.data.BACKTEST_TIMEFRAMES
import com.aether.signal.premium.data.PROVIDER_IDS
import com.aether.signal.premium.data.PROVIDER_LABELS
import com.aether.signal.premium.data.fetchUniverse
import com.aether.signal.premium.data.getCandlesRetry
import com.aether.signal.premium.data.classifyFetchError
import com.aether.signal.premium.data.FetchResult
import com.aether.signal.premium.data.validateCustomPair
import com.aether.signal.premium.engine.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MarketActivity : BaseActivity(R.id.nav_markets) {
    override val contentLayout = R.layout.activity_market
    private var prov = "binance"
    private var forex = false
    // Provider crypto terakhir (binance/bybit/demo) agar kembali dari mode
    // Forex tak menyisakan prov="yahoo" — kategori & provider selalu sinkron.
    private var lastCryptoProv = "binance"
    private var segCat: MaterialButtonToggleGroup? = null
    private var tf = MARKET_DEFAULT_TF
    private var query = ""
    private var rows: List<MktRow> = emptyList()

    data class MktRow(val sym: String, val prov: String, var price: String = "—", var chg: Double? = null, var sig: String = "…", var err: String? = null, var spark: List<Double> = emptyList(), var stale: Boolean = false, var updatedAt: Long = 0L)
    private var watchAdapter: WatchAdapter? = null
    // P2: generasi pemuatan (abaikan hasil basi) + dedup permintaan berjalan.
    private var loadGen = 0
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Pembaruan harga berkala (REST batch, tanpa WebSocket — lihat Quotes.kt):
    // 30 dtk untuk binance/bybit/demo, 90 dtk untuk yahoo (24 pair, per-simbol).
    // Hanya harga/chg/updatedAt yang disegarkan; sparkline & sinyal tetap dari
    // pemindaian penuh agar tak membanjiri API. Berhenti saat layar pergi.
    private var refresher: java.util.concurrent.ScheduledFuture<*>? = null
    private val refreshExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var lastRefreshAt: Long = 0L
    private var lastRefreshOk: Int = 0
    private var lastRefreshTotal: Int = 0
    // Ringkasan scan terakhir (TAHAP 3): diminta vs diterima provider.
    private var lastScanReq: Int = 0
    private var lastScanGot: Int = 0

    /** Normalisasi simbol kustom sesuai provider aktif (C). */
    private fun resolveCustom(sym: String, pr: String): String =
        if (pr == "yahoo") sym.trim().uppercase() else sym.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** FLAGSHIP: header watchlist memuat timeframe aktual (pilihan pasar). */
    private fun paintWatchHead() {
        try { findViewById<TextView>(R.id.watchHead).text = "WATCHLIST · ${tf.uppercase()}" }
        catch (e: Exception) { /* abaikan */ }
    }

    private fun toItem(r: MktRow) = WatchItem(r.sym, r.price, r.chg, r.sig, r.err, r.spark, r.stale, r.updatedAt, healthOf(r.sym))

    /** TAHAP 2.3 label kesehatan dari RegimeDiag Engine aktual (bukan aturan baru).
     *  Hanya bila provider+timeframe cocok dengan pilihan saat ini; "" = tak ada data. */
    private fun healthOf(sym: String): String {
        return try {
            val d = App.regimeDiag[sym] ?: return ""
            if (d.provider != prov || d.timeframe != App.timeframe) return ""
            pairDataHealth(d.requested, d.received, d.valid, d.minRequired).substringBefore("(")
        } catch (e: Exception) { "" }
    }

    override fun build() {
        setBar("Markets", "Watchlist profesional")
        prov = App.provider
        if (prov != "yahoo") lastCryptoProv = prov
        // Kategori awal mengikuti provider aktif (yahoo = Forex), bukan selalu Crypto.
        forex = App.provider == "yahoo"
        val seg = findViewById<MaterialButtonToggleGroup>(R.id.segCat)
        segCat = seg
        seg.check(if (forex) R.id.segForex else R.id.segCrypto)
        seg.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                forex = id == R.id.segForex
                if (forex) {
                    // Forex/metal hanya tersedia di Yahoo: cerminkan pada dropdown,
                    // jangan diam-diam memakai provider lain.
                    if (App.provider != "yahoo") lastCryptoProv = App.provider
                    prov = "yahoo"
                    setStatus("Kategori Forex → provider Yahoo (Forex & Metal).")
                } else {
                    // Kembali ke crypto: pulihkan provider crypto terakhir
                    // (dulu prov tertinggal "yahoo" → kategori Crypto pakai Yahoo).
                    prov = if (lastCryptoProv == "yahoo") "binance" else lastCryptoProv
                }
                // Provider aktif global harus mengikuti kategori Market agar
                // Backtest/Engine membaca sumber yang sama. Saring seleksi multi
                // yang tak valid untuk provider baru (jangan campur crypto↔forex).
                App.provider = prov
                App.prefs.edit().putString("provider", prov).apply()
                val dropped = App.pruneMultiSelForProvider(prov)
                if (dropped.isNotEmpty()) snack("Seleksi multi dirapikan untuk $prov: " +
                    "dihapus ${dropped.size} (${dropped.take(5).joinToString(", ")}).")
                paintProv()
                paintProvStatus()
                load()
            }
        }
        paintProv()
        val tfView = findViewById<MaterialAutoCompleteTextView>(R.id.spTf)
        tfView.setSimpleItems(MARKET_TF_LABELS.toTypedArray())
        tfView.setText(MARKET_TF_LABELS[MARKET_TIMEFRAMES.indexOf(tf).coerceAtLeast(0)], false)
        // Penjamin sentuhan: ketuk field mana pun selalu membuka daftar (selain perilaku bawaan).
        tfView.setOnClickListener { tfView.showDropDown() }
        tfView.setOnItemClickListener { _, _, pos, _ ->
            tf = MARKET_TIMEFRAMES[pos]
            tfView.setText(MARKET_TF_LABELS[pos], false)
            setStatus("Timeframe ${MARKET_TF_LABELS[pos]} · memuat ulang…")
            paintWatchHead()
            load()
        }
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
            .addTextChangedListener { query = (it?.toString() ?: ""); paint() }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).vertical(this)
        findViewById<MaterialButton>(R.id.btnAllSig).setOnClickListener { openSignals() }
        paintSignals()
        paintProvStatus()
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener {
            findViewById<View>(R.id.btnRetry).visibility = View.GONE
            load()
        }
        paintCustom()
        findViewById<MaterialButton>(R.id.btnAddPair).setOnClickListener { showAddPair() }
        findViewById<MaterialButton>(R.id.btnResetWatch).setOnClickListener {
            App.resetWatchlist()
            paintCustom()
            snack(this, "Watchlist dikembalikan ke bawaan (12 pair).")
            load()
        }
        findViewById<MaterialButton>(R.id.btnWatchLimit).setOnClickListener {
            val v = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inWatchLimit)
                .text.toString().trim().toIntOrNull()
            if (v == null || v < 1 || v > MAX_WATCH_PAIRS) {
                snack(this, "Isi 1–$MAX_WATCH_PAIRS.")
                return@setOnClickListener
            }
            App.watchLimit = v; App.saveWatchLimit()
            paintCustom()
            load()
        }
        intent.getStringExtra("focus")?.let {
            if (it.isNotEmpty()) {
                query = it
                findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search).setText(it)
            }
        }
        load()
    }

    private fun paintProv() {
        val v = findViewById<MaterialAutoCompleteTextView>(R.id.spProv)
        v.setSimpleItems(PROVIDER_LABELS.toTypedArray())
        v.setText(PROVIDER_LABELS[PROVIDER_IDS.indexOf(prov).coerceAtLeast(0)], false)
        v.setOnClickListener { v.showDropDown() }
        v.setOnItemClickListener { _, _, pos, _ ->
            val picked = PROVIDER_IDS[pos]
            // Provider yahoo = kategori Forex; selainnya = Crypto. Selaraskan
            // toggle agar kategori & provider tak pernah bertentangan; toggle
            // yang menangani penetapan App.provider + saring seleksi.
            if (picked == "yahoo" && !forex) { segCat?.check(R.id.segForex); return@setOnItemClickListener }
            if (picked != "yahoo" && forex) { lastCryptoProv = picked; segCat?.check(R.id.segCrypto); return@setOnItemClickListener }
            if (picked != "yahoo") lastCryptoProv = picked
            prov = picked
            App.provider = prov
            App.prefs.edit().putString("provider", prov).apply()
            val dropped = App.pruneMultiSelForProvider(prov)
            if (dropped.isNotEmpty()) snack(this, "Seleksi multi dirapikan untuk $prov: " +
                "dihapus ${dropped.size} (${dropped.take(5).joinToString(", ")}).")
            v.setText(PROVIDER_LABELS[pos], false)
            paintProvStatus()
            setStatus("Provider ${PROVIDER_LABELS[pos]} · memuat ulang…")
            load()
        }
    }

    private fun setStatus(m: String) {
        findViewById<TextView>(R.id.status).text = m
    }

    /** Status provider aktif: nama utuh + sumber data, tak bertumpuk (L1). */
    private fun paintProvStatus() {
        val pr = if (forex) "yahoo" else prov
        val name = PROVIDER_LABELS[PROVIDER_IDS.indexOf(pr).coerceAtLeast(0)]
        val src = when (pr) {
            "binance" -> "sumber: spot crypto"
            "bybit" -> "sumber: spot crypto"
            "yahoo" -> "sumber: Forex & Metal"
            else -> "sumber: offline (seeded)"
        }
        // V27: status jujur dari ring kesehatan — hijau HANYA bila cek nyata
        // terakhir OK dan <15 mnt; merah bila cek terakhir gagal; kuning bila
        // belum pernah dicek / basi. Tanpa klaim "live".
        val st = try { App.healthStats(pr) } catch (e: Exception) { null }
        val stale = st == null || System.currentTimeMillis() - st.lastAt > 15 * 60 * 1000L
        val label = when {
            st != null && st.checked && st.lastOk && !stale -> "Aktif: $name · $src · OK ${st.lastLatencyMs}ms"
            st != null && st.checked && !st.lastOk -> "Aktif: $name · $src · GAGAL (${st.lastKind ?: "jaringan"})"
            else -> "Aktif: $name · $src · belum dicek"
        }
        findViewById<TextView>(R.id.provStatus).apply {
            text = label
            try {
                setTextColor(provStateColor(st?.lastOk == true, st?.checked == true, stale))
            } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun load() {
        loadGen++ // Batalkan hasil pool lama yang masih berjalan.
        findViewById<View>(R.id.loading).visibility = View.VISIBLE
        findViewById<View>(R.id.btnRetry).visibility = View.GONE
        findViewById<TextView>(R.id.empty).visibility = View.GONE
        paintFallbackBanner(false, "")
        runBg {
            try {
                val pr = if (forex) "yahoo" else prov
                // Jujur soal sumber daftar (B): tandai bila ini daftar darurat.
                // C: gabung daftar bawaan (dibatasi watchLimit) + pair kustom pengguna.
                val t0 = System.nanoTime()
                // Batas watchLimit dihormati semua provider termasuk Yahoo
                // (dulu cabang Yahoo mengabaikan batas → diminta 10 tampil 24).
                val base = fetchUniverse(pr, App.watchLimit)
                val basePair = base.pairs to base.fallback
                App.recordHealth(pr, true, (System.nanoTime() - t0) / 1_000_000, null,
                    "${basePair.first.size} pair")
                val merged = ArrayList(basePair.first)
                for (c in App.customPairs) {
                    val s = resolveCustom(c, pr)
                    if (s.isNotEmpty() && merged.none { it == s }) merged.add(s)
                }
                rows = merged.map { MktRow(it, pr) }
                val pairs = merged
                val isFallback = basePair.second
                // Sumber tunggal: tulis hasil scan (daftar dasar provider +
                // batas + fallback) agar Backtest/sheet Engine memakai daftar
                // yang SAMA — bukan daftar independen yang kebetulan mirip.
                // App.universe disinkron HANYA bila provider Market sama dengan
                // provider tersimpan (mode forex Yahoo tidak mencemari universe
                // crypto Backtest, dan sebaliknya).
                // Jenis diturunkan dari id provider (universeKindFor), bukan
                // dari toggle UI lokal, agar cocok dibaca semua layar.
                App.savePairUniverse(PairUniverse(
                    provider = pr,
                    kind = universeKindFor(pr),
                    limit = base.requested, pairs = basePair.first,
                    isFallback = isFallback, updatedAt = System.currentTimeMillis()))
                if (pr == App.provider) App.universe = basePair.first.toList()
                lastScanReq = App.watchLimit
                lastScanGot = base.pairs.size
                // A7: isi awal dari cache harga terakhir (berlabel basi, bukan live).
                for (r in rows) {
                    App.getLastPrice(pr, r.sym, tf)?.let { lp ->
                        r.price = lp.price; r.chg = lp.chg; r.spark = lp.spark
                        r.stale = true; r.updatedAt = lp.t
                    }
                }
                runOnUiThread {
                    findViewById<View>(R.id.loading).visibility = View.GONE
                    if (isFallback) {
                        paintFallbackBanner(true,
                            "Mode terbatas: $pr tak terjangkau dari jaringan ini " +
                                "(umum: HTTP 451/403). Menampilkan 4 pair utama — " +
                                "harga tetap live per pair. Coba Yahoo atau Demo untuk daftar penuh.")
                    }
                    setStatus("Provider ${PROVIDER_LABELS[PROVIDER_IDS.indexOf(pr).coerceAtLeast(0)]} · TF $tf · memuat harga 0/${pairs.size}…")
                    paint()
                    fillPrices()
                }
            } catch (e: Exception) {
                App.recordHealth(if (forex) "yahoo" else prov, false, 0,
                    classifyFetchError(e.message), e.message ?: "error")
                runOnUiThread {
                    findViewById<View>(R.id.loading).visibility = View.GONE
                    findViewById<TextView>(R.id.empty).apply {
                        visibility = View.VISIBLE
                        text = "Gagal memuat daftar pair (${PROVIDER_LABELS[PROVIDER_IDS.indexOf(if (forex) "yahoo" else prov).coerceAtLeast(0)]}): ${e.message}"
                    }
                    setStatus("Gagal memuat daftar pair.")
                    findViewById<View>(R.id.btnRetry).visibility = View.VISIBLE
                }
            }
        }
    }

    private fun paintFallbackBanner(show: Boolean, msg: String) {
        findViewById<TextView>(R.id.fallbackBanner).apply {
            visibility = if (show) View.VISIBLE else View.GONE
            text = msg
        }
    }

    // ---------- C: watchlist kustom ----------
    private var customTouch: androidx.recyclerview.widget.ItemTouchHelper? = null

    private fun paintCustom() {
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inWatchLimit)
            .setText(App.watchLimit.toString())
        // Pilihan batas maksimum pair (10/20/30/50/100/150/200, maks 200).
        try {
            val chips = findViewById<android.widget.LinearLayout>(R.id.limitChips)
            chips.removeAllViews()
            for (n in WATCH_LIMIT_OPTIONS) {
                val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                b.text = "$n"
                b.isCheckable = true
                b.isChecked = App.watchLimit == n
                b.setOnClickListener {
                    App.watchLimit = n; App.saveWatchLimit()
                    paintCustom()
                    load()
                }
                chips.addView(b)
            }
        } catch (e: Exception) { /* layout lama: abaikan */ }
        // C11: jumlah yang benar-benar ditampilkan = bawaan (dibatasi) + kustom.
        findViewById<TextView>(R.id.customCount).text =
            "Menampilkan maks ${App.watchLimit} bawaan + ${App.customPairs.size} kustom"
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.customList)
        list.vertical(this)
        if (App.customPairs.isEmpty()) {
            list.adapter = SigAdapter(listOf(SigItem("○", "Belum ada pair kustom.", "Tekan ＋ Tambah Pasangan.", "")))
            customTouch?.attachToRecyclerView(null)
            customTouch = null
            return
        }
        val data = App.customPairs.toMutableList()
        lateinit var adapter: CustomPairAdapter
        adapter = CustomPairAdapter(data,
            onDelete = { s ->
                App.customPairs.remove(s); App.saveCustomPairs()
                paintCustom()
                snack(this@MarketActivity, "$s dihapus dari watchlist.")
                load()
            },
            onMove = { _, _ ->
                // P9: urutan baru tersimpan (LinkedHashSet menjaga urutan).
                App.customPairs = LinkedHashSet(adapter.items)
                App.saveCustomPairs()
            },
            onDragStart = { h -> customTouch?.startDrag(h) })
        list.adapter = adapter
        customTouch?.attachToRecyclerView(null)
        customTouch = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
                override fun onMove(rv: androidx.recyclerview.widget.RecyclerView,
                        vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                        t: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                    adapter.move(vh.adapterPosition, t.adapterPosition)
                    return true
                }
                override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}
                override fun isLongPressDragEnabled() = false
            })
        customTouch?.attachToRecyclerView(list)
    }

    private fun showAddPair() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "mis. BTC/USDT"
            maxLines = 1
        }
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        wrap.addView(TextView(this).apply {
            text = "Provider aktif: $prov. Simbol harus tersedia pada provider ini."
            setTextColor(0xFF94A3B8.toInt()); textSize = 12f
        })
        wrap.addView(input)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Tambah Pasangan")
            .setView(wrap)
            .setPositiveButton("Tambah") { _, _ ->
                val (norm, err) = validateCustomPair(input.text.toString(), if (forex) "yahoo" else prov)
                if (err != null) {
                    // C6: tolak dengan alasan, jangan tambah diam-diam.
                    snack(this, err)
                    return@setPositiveButton
                }
                if (App.customPairs.size >= MAX_WATCH_PAIRS) {
                    snack(this, "Batas $MAX_WATCH_PAIRS pair kustom tercapai.")
                    return@setPositiveButton
                }
                App.customPairs.add(norm)
                App.saveCustomPairs()
                paintCustom()
                snack(this, "$norm ditambahkan.")
                load()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun fillPrices() {
        // P2: paralel 3 pekerja + dedup in-flight + coba ulang terbatas (transien
        // saja, jeda meningkat) + abaikan hasil basi. Scroll & posisi terjaga.
        val gen = loadGen
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val total = rows.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        for (r in rows) {
            pool.execute {
                val key = "${r.prov}|${r.sym}|$tf"
                // P2: jangan kirim duplikat bila permintaan sama masih berjalan.
                if (!inflight.add(key)) return@execute
                try {
                    val t0 = System.nanoTime()
                    var res: FetchResult? = null
                    var err: Exception? = null
                    try {
                        res = getCandlesRetry(r.prov, r.sym, tf, 200) { gen != loadGen }
                    } catch (e: Exception) { err = e }
                    val latency = (System.nanoTime() - t0) / 1_000_000
                    if (gen != loadGen) return@execute // Basi: reload baru sudah jalan.
                    if (res != null) {
                        val cs = res.candles
                        val last = cs.last(); val ref = cs[maxOf(0, cs.size - 25)]
                        // fmtPx adaptif: koin mikro (PEPE/SHIB) tetap terbaca.
                        r.price = App.fmtPx(last.c)
                        r.chg = (last.c - ref.c) / ref.c * 100
                        // Sparkline dari close historis yang SAMA (bukan data lain).
                        r.spark = cs.takeLast(60).map { it.c }
                        r.err = null; r.stale = false; r.updatedAt = System.currentTimeMillis()
                        // A7: simpan sebagai harga terakhir (untuk tampil saat offline nanti).
                        App.saveLastPrice(r.prov, r.sym, tf, r.price, r.chg, r.spark)
                        App.recordHealth(r.prov, true, latency, null, "${cs.size} candle")
                        r.sig = try {
                            val norm = normalizeCandles(cs.map { mapOf("t" to it.t, "o" to it.o, "h" to it.h, "l" to it.l, "c" to it.c, "v" to it.v) as Any? })
                            val cache = buildCache(norm)
                            val dec = decideAt(norm, norm.size - 1, cache, BacktestParams(strategy = App.strategy))
                            if (!dec.passed) "NETRAL" else {
                                val flt = App.activeFilterCfgs()
                                if (flt.isNotEmpty() && !applyFilters(norm, norm.size - 1, cache, dec.direction, flt, FilterCtx(lastExit = -1000000000)).passed) "FILTER×" else dec.direction
                            }
                        } catch (e: Exception) { "NETRAL" }
                    } else {
                        val msg = err?.message ?: "error"
                        r.err = msg.take(120)
                        App.recordHealth(r.prov, false, latency, classifyFetchError(msg), msg)
                        // A7: tanpa cache, kosongkan jujur (bukan nol); cache lama tetap tampil basi.
                        if (r.price == "—") { r.spark = emptyList(); r.stale = false; r.updatedAt = 0L }
                        else { r.stale = true }
                    }
                } finally {
                    inflight.remove(key)
                }
                val d = done.incrementAndGet()
                runOnUiThread {
                    if (isFinishing || isDestroyed || gen != loadGen) return@runOnUiThread
                    setStatus("Memuat harga $d/$total…")
                    // Kirim snapshot SEGAR ke adapter (akar harga tak tampil: snapshot basi).
                    try {
                        (findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).adapter as? WatchAdapter)
                            ?.notifyRow(r.sym, toItem(r))
                    } catch (e: Exception) { refreshRows() }
                    if (d >= total) {
                        // C: pisahkan dikonfigurasi / live / cache / gagal.
                        val live = rows.count { it.err == null && !it.stale }
                        val cached = rows.count { it.stale && it.price != "—" }
                        val failed = rows.count { it.err != null && it.price == "—" }
                        if (live + cached > 0) setStatus("$live live · $cached cache · $failed gagal / $total · $tf · klik = detail" +
                            " · scan diminta $lastScanReq, daftar $lastScanGot")
                        else {
                            setStatus("Semua pair gagal — coba Demo/Yahoo atau Coba lagi.")
                            findViewById<View>(R.id.btnRetry).visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
        pool.shutdown()
    }

    override fun onResume() {
        super.onResume()
        try { paintSignals() } catch (e: Exception) { /* layout belum siap */ }
        startRefresher()
    }

    override fun onPause() {
        try { stopRefresher() } catch (e: Exception) { /* abaikan */ }
        super.onPause()
    }

    override fun onDestroy() {
        try { stopRefresher(); refreshExec.shutdownNow() } catch (e: Exception) { /* abaikan */ }
        super.onDestroy()
    }

    private fun refreshIntervalSec(): Long =
        if ((if (forex) "yahoo" else prov) == "yahoo") 90L else 30L

    private fun startRefresher() {
        stopRefresher()
        val interval = refreshIntervalSec()
        try {
            refresher = refreshExec.scheduleWithFixedDelay({
                try {
                    if (isFinishing || isDestroyed) return@scheduleWithFixedDelay
                    refreshPrices()
                } catch (e: Exception) { /* siklus berikut mencoba lagi */ }
            }, interval, interval, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun stopRefresher() {
        try { refresher?.cancel(false) } catch (e: Exception) { /* abaikan */ }
        refresher = null
    }

    /** Satu siklus refresh harga ringan: ticker batch, tanpa rescan klines. */
    private fun refreshPrices() {
        val gen = loadGen
        val snapshot = rows.toList()
        if (snapshot.isEmpty()) return
        val pr = snapshot.firstOrNull()?.prov ?: return
        val syms = snapshot.map { it.sym }.distinct()
        val batch = try {
            com.aether.signal.premium.data.fetchQuotes(pr, syms)
        } catch (e: Exception) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && gen == loadGen) paintFreshness(0, syms.size)
            }
            return
        }
        if (gen != loadGen) return // basi: reload baru sudah jalan
        var updated = 0
        for (r in snapshot) {
            val q = batch.ticks[r.sym] ?: continue
            // Guard anti-tukar: kunci identity pair (sym+prov+tf implisit per baris).
            if (r.sym != q.sym) continue
            r.price = App.fmtPx(q.price)
            if (q.chgPct != null) r.chg = q.chgPct
            r.err = null; r.stale = false; r.updatedAt = q.fetchedAt
            App.saveLastPrice(pr, r.sym, tf, r.price, r.chg, r.spark)
            updated++
            runOnUiThread {
                if (!isFinishing && !isDestroyed && gen == loadGen) {
                    try {
                        (findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).adapter as? WatchAdapter)
                            ?.notifyRow(r.sym, toItem(r))
                    } catch (e: Exception) { /* abaikan */ }
                }
            }
        }
        lastRefreshAt = batch.at
        lastRefreshOk = updated
        lastRefreshTotal = syms.size
        runOnUiThread {
            if (!isFinishing && !isDestroyed && gen == loadGen) {
                paintFreshness(updated, syms.size)
                if (batch.missing.isNotEmpty()) {
                    setStatus("Refresh ${batch.source}: $updated/${syms.size} · " +
                        "hilang ${batch.missing.size} (${batch.missing.take(3).joinToString(",")})")
                }
            }
        }
    }

    /** Status kesegaran jujur: LIVE bila siklus terakhir sukses, else STALE/OFFLINE. */
    private fun paintFreshness(ok: Int, total: Int) {
        try {
            val ageS = if (lastRefreshAt > 0)
                ((System.currentTimeMillis() - lastRefreshAt) / 1000).toInt() else -1
            val txt = if (ok > 0 && ageS >= 0)
                "LIVE · $ok/$total diperbarui ${ageS}d lalu · via ${if (forex) "yahoo" else prov}"
            else "STALE · refresh terakhir gagal — menampilkan data terakhir yang valid"
            findViewById<TextView>(R.id.freshStatus).text = txt
        } catch (e: Exception) { /* layout lama: abaikan */ }
    }

    private fun paintSignals() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.sigList)
        list.vertical(this)
        val vis = engineSignalsOnly(App.signals).take(3)
        val items = vis.map {
            SigItem(it.dir, "${it.pair}  ${it.tf}", "${App.fmtDate(it.t)} · via ${it.src}${signalRowExtra(it)}", App.fmtPx(it.price), it.pair)
        }
        list.adapter = SigAdapter(items, onClick = { pos ->
            vis.getOrNull(pos)?.let { openSignalDetail(it.id) }
        })
        findViewById<TextView>(R.id.sigEmpty).apply {
            visibility = if (vis.isEmpty()) View.VISIBLE else View.GONE
            text = "Belum ada sinyal Engine — tekan Start Engine."
        }
    }

    private fun paint() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        val q = query.uppercase()
        val vis = rows.filter { q.isEmpty() || it.sym.contains(q) }
        findViewById<TextView>(R.id.empty).apply {
            visibility = if (vis.isEmpty() && rows.isNotEmpty()) View.VISIBLE else View.GONE
            text = "Tidak ada hasil."
        }
        val items = vis.map { toItem(it) }
        val cur = watchAdapter
        if (cur == null) {
            // SATU adapter untuk seumur layar (A11): tap buka detail pair yang benar (A9).
            watchAdapter = WatchAdapter(items) { item -> openRow(item.sym) }
            list.adapter = watchAdapter
        } else {
            cur.update(items)
        }
    }

    /** Bangun ulang item dari `rows` pada adapter yang SAMA (scroll terjaga). */
    private fun refreshRows() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        val q = query.uppercase()
        val items = rows.filter { q.isEmpty() || it.sym.contains(q) }
            .map { toItem(it) }
        val cur = watchAdapter
        if (cur == null) {
            watchAdapter = WatchAdapter(items) { item -> openRow(item.sym) }
            list.adapter = watchAdapter
        } else {
            cur.update(items)
        }
    }

    private fun openRow(sym: String) {
        val r = rows.find { it.sym == sym } ?: return
        if (r.err != null && r.price == "—") {
            // Tanpa data sama sekali: tampilkan alasan jujur, bukan diam.
            snack(this, "${r.sym}: ${r.err}")
            return
        }
        PairSheet(this, r.sym, r.prov, tf, r.price, r.chg, r.sig, r.spark,
                staleNote = if (r.stale) "data ${staleAgeLabel(r.updatedAt, System.currentTimeMillis())} · offline" else "",
                onBacktest = {
                    // P3: simbol + TF mengikuti pilihan Market (divalidasi, tanpa diam-diam diganti).
                    App.pair = r.sym; App.savePair(); App.provider = r.prov
                    App.prefs.edit().putString("provider", r.prov).apply()
                    val dropped = App.pruneMultiSelForProvider(r.prov)
                    if (dropped.isNotEmpty()) snack(this, "Seleksi multi dirapikan untuk ${r.prov}: " +
                        "dihapus ${dropped.size} (${dropped.take(5).joinToString(", ")}).")
                    val tfOk = BACKTEST_TIMEFRAMES.contains(tf)
                    App.timeframe = if (tfOk) tf else App.timeframe
                    startActivity(Intent(this, BacktestActivity::class.java).apply {
                        putExtra("asset", r.sym)
                        putExtra("timeframe", App.timeframe)
                        putExtra("fromMarket", true)
                    })
                },
                onChart = {
                    App.pair = r.sym; App.savePair(); App.provider = r.prov
                    App.prefs.edit().putString("provider", r.prov).apply()
                    GraphActivity.open(this, r.sym, r.prov, tf)
                }).show()
    }
}
