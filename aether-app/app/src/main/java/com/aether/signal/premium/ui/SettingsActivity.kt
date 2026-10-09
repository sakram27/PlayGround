package com.aether.signal.premium.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.data.fetchHistory
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs
import com.aether.signal.premium.data.PROVIDER_IDS
import com.aether.signal.premium.data.PROVIDER_LABELS
import com.aether.signal.premium.data.providerSupports
import com.aether.signal.premium.data.isDataStale
import com.aether.signal.premium.data.FetchErrorKind
import com.aether.signal.premium.data.classifyFetchError
import com.aether.signal.premium.engine.parseTimeframe
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : BaseActivity(R.id.nav_settings) {
    override val contentLayout = R.layout.activity_settings

    override fun build() {
        setBar("Settings", "Provider · koneksi · aplikasi")
        val provs = listOf("binance", "bybit", "yahoo", "demo")
        findViewById<MaterialAutoCompleteTextView>(R.id.spProv).apply {
            setSimpleItems(provs.toTypedArray())
            setText(App.provider, false)
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, pos, _ -> App.provider = provs[pos] }
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            App.prefs.edit().putString("provider", App.provider).apply()
            snack(this, "Tersimpan.")
        }
        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { export() }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { importJson() }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { testConn() }
        paintFetch()
        paintHealth()
        paintNotif()
        paintQuiet()
        paintAlways()
    }

    /** P8: Health Center — status nyata per provider + tombol uji per baris. */
    private fun paintHealth() {
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.healthList)
        list.vertical(this)
        val probe = mapOf("binance" to "BTCUSDT", "bybit" to "BTCUSDT", "yahoo" to "EUR/USD", "demo" to "BTCUSDT")
        val tfMin = try { parseTimeframe(App.timeframe) } catch (e: Exception) { 15 }
        val items = PROVIDER_IDS.map { p ->
            val st = App.healthStats(p)
            val supported = providerSupports(p, App.pair)
            val stale = isDataStale(App.lastHealthSuccess(p), System.currentTimeMillis(), tfMin)
            val head = if (!st.checked) "BELUM DIUJI"
            else if (st.lastOk) "SEHAT · ${st.lastLatencyMs}ms"
            else "GAGAL · ${kindLabel(st.lastKind)}"
            SigItem(head, "${PROVIDER_LABELS[PROVIDER_IDS.indexOf(p)]}",
                "cek ${if (st.lastAt > 0) App.fmtT(st.lastAt) else "—"} · " +
                    "OK ${st.success}/gagal ${st.failed} (20 terakhir) · " +
                    "dukung ${App.pair}: ${if (supported) "ya" else "tidak"} · " +
                    "data: ${if (stale) "kedaluwarsa" else "segar"}" +
                    if (!st.checked || st.lastMsg.isEmpty()) "" else " · ${st.lastMsg.take(50)}",
                "UJI")
        }
        list.adapter = SigAdapter(items, onClick = { pos ->
            testProvider(PROVIDER_IDS[pos], probe[PROVIDER_IDS[pos]] ?: "BTCUSDT")
        })
    }

    private fun kindLabel(k: FetchErrorKind?): String = when (k) {
        FetchErrorKind.TRANSIENT -> "sementara"
        FetchErrorKind.PERMANENT -> "permanen"
        null -> "tak diketahui"
    }

    /** Uji aktif satu provider (atas tindakan pengguna) + catat hasilnya. */
    private fun testProvider(provider: String, symbol: String) {
        snack(this, "Menguji $provider…")
        runBg {
            val t0 = System.nanoTime()
            try {
                val r = getCandles(provider, symbol, "1h", 60)
                App.recordHealth(provider, true, (System.nanoTime() - t0) / 1_000_000, null,
                    "${r.candles.size} candle $symbol")
                runOnUiThread { snack(this, "$provider OK (${r.candles.size} candle)."); paintHealth() }
            } catch (e: Exception) {
                val msg = e.message ?: "error"
                App.recordHealth(provider, false, (System.nanoTime() - t0) / 1_000_000,
                    classifyFetchError(msg), msg)
                runOnUiThread { snack(this, "$provider gagal: ${msg.take(80)}"); paintHealth() }
            }
        }
    }

    // ---------- notifikasi (D4): 5 sakelar independen + status izin ----------
    private fun paintNotif() {
        NotifBus.init(this)
        NotifBus.ensureChannels(this)
        val swE = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swNotifEntry)
        val swS = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swNotifSl)
        val swT = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swNotifTp)
        val swSo = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swNotifSound)
        val swV = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swNotifVibrate)
        swE.isChecked = NotifBus.entryOn
        swS.isChecked = NotifBus.slOn
        swT.isChecked = NotifBus.tpOn
        swSo.isChecked = NotifBus.soundOn
        swV.isChecked = NotifBus.vibrateOn
        swE.setOnCheckedChangeListener { _, on -> NotifBus.entryOn = on; paintNotifStatus() }
        swS.setOnCheckedChangeListener { _, on -> NotifBus.slOn = on; paintNotifStatus() }
        swT.setOnCheckedChangeListener { _, on -> NotifBus.tpOn = on; paintNotifStatus() }
        swSo.setOnCheckedChangeListener { _, on -> NotifBus.soundOn = on; paintNotifStatus() }
        swV.setOnCheckedChangeListener { _, on -> NotifBus.vibrateOn = on; paintNotifStatus() }
        findViewById<MaterialButton>(R.id.btnNotifPerm).setOnClickListener { requestNotifPerm() }
        findViewById<MaterialButton>(R.id.btnNotifHistory).setOnClickListener {
            startActivity(android.content.Intent(this, NotifHistoryActivity::class.java))
        }
        paintNotifStatus()
    }

    private fun paintNotifStatus() {
        val ok = NotifBus.systemEnabled()
        findViewById<TextView>(R.id.notifStatus).text =
            if (ok) "Izin notifikasi: AKTIF."
            else "Izin notifikasi: MATI — aktifkan agar peringatan muncul. Aplikasi tetap berjalan tanpa crash."
        findViewById<MaterialButton>(R.id.btnNotifPerm).visibility =
            if (ok) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestNotifPerm() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            snack(this, "Manfaat: peringatan entry/SL/TP hanya muncul bila diizinkan.")
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4101)
            } catch (e: Exception) { snack(this, "Gagal meminta izin: ${e.message}") }
        } else {
            snack(this, "Versi Android ini tidak memerlukan izin runtime.")
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 4101) paintNotifStatus()
    }

    // ---------- JAM TENANG (FITUR 4) ----------
    private fun paintQuiet() {
        NotifBus.init(this)
        val sw = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swQuiet)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = NotifBus.quietEnabled
        sw.setOnCheckedChangeListener { _, on ->
            val q = NotifBus.quietHours()
            val err = quietValidationError(q.copy(enabled = on))
            if (on && err != null) {
                snack(this, "$err (atur jam & hari dulu).")
            }
            NotifBus.quietEnabled = on
            paintQuietStatus()
        }
        findViewById<android.widget.Button>(R.id.btnQuietStart).setOnClickListener { pickQuietTime(true) }
        findViewById<android.widget.Button>(R.id.btnQuietEnd).setOnClickListener { pickQuietTime(false) }
        findViewById<android.widget.Button>(R.id.btnQuietDays).setOnClickListener { pickQuietDays() }
        // V13 F5: preset sekali ketuk — langsung diterapkan saat diketuk, lalu ringkasan diperbarui.
        findViewById<android.widget.Button>(R.id.btnQuietPresetWork).setOnClickListener {
            applyQuietPreset(QUIET_PRESET_WORK, "Malam Hari Kerja")
        }
        findViewById<android.widget.Button>(R.id.btnQuietPresetWeekend).setOnClickListener {
            applyQuietPreset(QUIET_PRESET_WEEKEND, "Akhir Pekan")
        }
        paintQuietStatus()
    }

    /** Terapkan preset jam tenang (persist). Tak menyentuh sinyal/backtest/dedup. */
    private fun applyQuietPreset(p: QuietHours, name: String) {
        NotifBus.quietEnabled = true
        NotifBus.quietStartMin = p.startMin
        NotifBus.quietEndMin = p.endMin
        NotifBus.quietDays = p.days
        val sw = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swQuiet)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = true
        sw.setOnCheckedChangeListener { _, on ->
            val q = NotifBus.quietHours()
            val err = quietValidationError(q.copy(enabled = on))
            if (on && err != null) snack(this, "$err (atur jam & hari dulu).")
            NotifBus.quietEnabled = on
            paintQuietStatus()
        }
        paintQuietStatus()
        snack(this, "Preset $name diterapkan: ${quietSummary(NotifBus.quietHours())}")
    }

    private fun paintQuietStatus() {
        val q = NotifBus.quietHours()
        findViewById<android.widget.Button>(R.id.btnQuietStart).text = "Mulai ${fmtMinuteOfDay(q.startMin)}"
        findViewById<android.widget.Button>(R.id.btnQuietEnd).text =
            if (q.endMin == MINUTES_PER_DAY) "Selesai 24:00" else "Selesai ${fmtMinuteOfDay(q.endMin)}"
        findViewById<android.widget.Button>(R.id.btnQuietDays).text = "Hari: ${quietDaysLabel(q.days)}"
        findViewById<TextView>(R.id.quietPresetStatus).text =
            "Jadwal: ${quietPresetLabel(q)}" +
                if (q.enabled && matchQuietPreset(q) == null)
                    " (diubah manual, tidak sama dengan preset mana pun)."
                else "."
        findViewById<TextView>(R.id.quietStatus).text = quietSummary(q) +
            "\nCatatan: jam tenang tidak menghentikan sinyal/backtest maupun notifikasi aplikasi lain, " +
            "dan sistem Android/OEM tetap dapat menunda notifikasi."
    }

    private fun pickQuietTime(isStart: Boolean) {
        val cur = if (isStart) NotifBus.quietStartMin else NotifBus.quietEndMin
        android.app.TimePickerDialog(this, { _, h, m ->
            val q = NotifBus.quietHours()
            val newStart = if (isStart) h * 60 + m else q.startMin
            val newEnd = if (isStart) q.endMin else h * 60 + m
            val err = quietValidationError(QuietHours(q.enabled, newStart, newEnd, q.days))
            if (err != null) snack(this, err)
            else {
                if (isStart) NotifBus.quietStartMin = newStart else NotifBus.quietEndMin = newEnd
                paintQuietStatus()
            }
        }, cur / 60, cur % 60, true).show()
    }

    private fun pickQuietDays() {
        val order = QUIET_DAY_ORDER
        val checked = BooleanArray(order.size) { order[it].first in NotifBus.quietDays }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Hari berlaku jam tenang")
            .setMultiChoiceItems(order.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Simpan") { _, _ ->
                val days = order.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                val q = NotifBus.quietHours()
                val err = quietValidationError(QuietHours(q.enabled, q.startMin, q.endMin, days))
                if (err != null) snack(this, err)
                else { NotifBus.quietDays = days; paintQuietStatus() }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        try { paintNotifStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintQuietStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintAlwaysStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintBattStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintNetStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintHealth() } catch (e: Exception) { /* layout belum siap */ }
        try { paintSaverStatus() } catch (e: Exception) { /* layout belum siap */ }
    }

    // ---------- Selalu Siaga (E): status dari layanan NYATA, bukan sakelar ----------
    private fun paintAlways() {
        val sw = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swAlways)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = MonitorService.running
        sw.setOnCheckedChangeListener { _, on ->
            if (on) {
                findViewById<TextView>(R.id.alwaysStatus).text = "Memulai pemantauan…"
                val err = MonitorService.start(this)
                if (err != null) {
                    snack(this, err)
                    sw.isChecked = false
                }
                paintAlwaysStatus()
            } else {
                MonitorService.stop(this)
                paintAlwaysStatus()
            }
        }
        findViewById<MaterialButton>(R.id.btnBatt).setOnClickListener { requestBattExempt() }
        findViewById<MaterialButton>(R.id.btnBattSys).setOnClickListener { openBattSettings() }
        paintAlwaysStatus()
        paintBattStatus()
        paintNetStatus()
        paintSaver()
    }

    /** P7: mode hemat baterai — kurangi frekuensi, jangan hentikan diam-diam. */
    private fun paintSaver() {
        val sw = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swSaver)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = App.batterySaver
        sw.setOnCheckedChangeListener { _, on ->
            App.batterySaver = on; App.saveBatterySaver()
            paintSaverStatus()
            snack(this, if (on) "Mode hemat aktif: interval mengikuti timeframe."
            else "Mode hemat mati: interval normal 60 detik.")
        }
        paintSaverStatus()
    }

    private fun paintSaverStatus() {
        try {
            val tfMin = try { parseTimeframe(App.timeframe) } catch (e: Exception) { 15 }
            findViewById<TextView>(R.id.saverStatus).text = if (App.batterySaver)
                "Hemat AKTIF · cek tiap ${saverIntervalSec(tfMin)} dtk (TF ${App.timeframe}). " +
                    "Pemantauan tetap jalan; sistem tetap bisa membatasi."
            else
                "Hemat MATI · cek tiap 60 detik."
        } catch (e: Exception) { /* abaikan */ }
    }

    /** Tiga status jujur (E1): dikecualikan / aktif / belum diperiksa. */
    private fun paintBattStatus() {
        val tv = findViewById<TextView>(R.id.battStatus)
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val exempt = pm.isIgnoringBatteryOptimizations(packageName)
            tv.text = if (exempt)
                "Pengecualian optimasi baterai: DIBERIKAN. (Membantu, bukan jaminan — sistem/OEM tetap bisa membatasi.)"
            else
                "Optimasi baterai: AKTIF membatasi Aether. Minta pengecualian agar pantauan lebih konsisten."
        } catch (e: Exception) {
            tv.text = "Status optimasi baterai belum dapat diperiksa di perangkat ini."
        }
    }

    private fun requestBattExempt() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                snack(this, "Pengecualian sudah diberikan.")
                return
            }
        } catch (e: Exception) { /* lanjut coba intent */ }
        try {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            openBattSettings()
        }
    }

    private fun openBattSettings() {
        // Halaman OEM berbeda-beda; fallback berlapis, tanpa crash (E3).
        val candidates = listOf(
            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (i in candidates) {
            try {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (e: Exception) { /* coba berikutnya */ }
        }
        snack(this, "Tidak dapat membuka pengaturan sistem di perangkat ini.")
    }

    private fun paintNetStatus() {
        val tv = findViewById<TextView>(R.id.netStatus)
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            val online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            tv.text = "Jaringan: " + if (online) "Online." else "Offline — pantauan menunggu koneksi, lanjut otomatis."
        } catch (e: Exception) {
            tv.text = "Jaringan: belum dapat diperiksa."
        }
    }

    private fun paintAlwaysStatus() {
        try {
            findViewById<TextView>(R.id.alwaysStatus).text = "Status: ${MonitorService.statusText()}" +
                if (MonitorService.running) "" else "\nRiwayat sinyal & transaksi tidak dihapus."
            val sw = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swAlways)
            if (sw.isChecked != MonitorService.running) {
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = MonitorService.running
                sw.setOnCheckedChangeListener { _, on ->
                    if (on) {
                        val err = MonitorService.start(this)
                        if (err != null) { snack(this, err); sw.isChecked = false }
                        paintAlwaysStatus()
                    } else {
                        MonitorService.stop(this)
                        paintAlwaysStatus()
                    }
                }
            }
        } catch (e: Exception) { /* abaikan */ }
    }

    private fun export() {
        try {
            val o = JSONObject()
            val s = JSONArray()
            for (x in App.signals) s.put(JSONObject().put("pair", x.pair).put("dir", x.dir).put("price", x.price).put("t", x.t))
            val h = JSONArray()
            for (t in App.hist) h.put(JSONObject().put("asset", t.asset).put("pnl", t.pnl).put("result", t.result))
            o.put("signals", s).put("hist", h)
            // F8: jurnal ikut diekspor level atas (data pengguna). Impor MENGGABUNG,
            // bukan menimpa (lihat applyImport + mergeJournal).
            val j = JSONArray()
            for (e in App.journal) j.put(JSONObject().put("id", e.id).put("createdAt", e.createdAt)
                .put("updatedAt", e.updatedAt).put("pair", e.pair).put("kind", e.kind)
                .put("tradeKey", e.tradeKey).put("title", e.title).put("body", e.body))
            o.put("journal", j)
            // P11: skema v2 terdokumentasi. Termasuk: provider, strategi, timeframe,
            // watchlist kustom, batas watchlist, pair engine, mode hemat, sakelar notifikasi.
            // TIDAK termasuk: sinyal/riwayat live (hanya arsip baca), kredensial
            // (aplikasi tidak menyimpan API key/token sama sekali), cache harga.
            val cfg = JSONObject()
                .put("provider", App.provider)
                .put("strategy", App.strategy)
                .put("timeframe", App.timeframe)
                .put("customPairs", JSONArray(App.customPairs.toList()))
                .put("watchLimit", App.watchLimit)
                .put("engPairs", JSONArray(App.engPairs.toList()))
                .put("batterySaver", App.batterySaver)
                .put("notif", JSONObject()
                    .put("entry", NotifBus.entryOn).put("sl", NotifBus.slOn)
                    .put("tp", NotifBus.tpOn).put("sound", NotifBus.soundOn)
                    .put("vibrate", NotifBus.vibrateOn))
                .put("quiet", JSONObject()
                    .put("enabled", NotifBus.quietEnabled)
                    .put("start", NotifBus.quietStartMin)
                    .put("end", NotifBus.quietEndMin)
                    .put("days", encodeQuietDays(NotifBus.quietDays)))
            o.put("config", cfg)
            o.put("app", "aether-signal").put("v", 1).put("schemaVersion", 2)
            val f = java.io.File(filesDir, "aether-export.json")
            f.writeText(o.toString(1))
            snack(this, "Tersimpan: ${f.absolutePath}")
        } catch (e: Exception) { snack(this, "Export gagal: ${e.message}") }
    }

    private fun importJson() {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/json"; addCategory(android.content.Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(i, 2201)
        } catch (e: Exception) { snack(this, "Tidak dapat membuka pemilih berkas: ${e.message}") }
    }

    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 2201 || res != RESULT_OK || data?.data == null) return
        // P11: validasi PENUH dulu; batal/gagal = nol perubahan pada konfigurasi aktif.
        val plan: ImportPlan
        try {
            val txt = contentResolver.openInputStream(data.data!!)!!.bufferedReader().readText()
            plan = parseConfigImport(txt)
        } catch (e: Exception) {
            snack(this, "Import dibatalkan: ${e.message}")
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Pulihkan konfigurasi?")
            .setMessage(importSummary(plan) + "\n\nKonfigurasi saat ini akan ditimpa.")
            .setPositiveButton("Pulihkan") { _, _ -> applyImport(plan) }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Terapkan atomik setelah persetujuan (P11). */
    private fun applyImport(plan: ImportPlan) {
        try {
            plan.provider?.let { App.provider = it }
            plan.strategy?.let { App.strategy = it }
            plan.timeframe?.let { App.timeframe = it }
            App.customPairs = LinkedHashSet(plan.customPairs)
            App.saveCustomPairs()
            plan.watchLimit?.let { App.watchLimit = it; App.saveWatchLimit() }
            if (plan.engPairs.isNotEmpty()) {
                App.engPairs = LinkedHashSet(plan.engPairs)
                App.saveEngPairs()
            }
            plan.batterySaver?.let { App.batterySaver = it; App.saveBatterySaver() }
            for ((k, v) in plan.notif) {
                when (k) {
                    "entry" -> NotifBus.entryOn = v
                    "sl" -> NotifBus.slOn = v
                    "tp" -> NotifBus.tpOn = v
                    "sound" -> NotifBus.soundOn = v
                    "vibrate" -> NotifBus.vibrateOn = v
                }
            }
            plan.quiet?.let { q ->
                NotifBus.quietEnabled = q.enabled
                NotifBus.quietStartMin = q.startMin
                NotifBus.quietEndMin = q.endMin
                NotifBus.quietDays = q.days
            }
            // F8: jurnal digabung (lokal menang atas id ganda) — tak pernah hapus
            // jurnal lama tanpa persetujuan; persetujuan sudah diberikan via dialog.
            var journalMsg = ""
            if (plan.journal.isNotEmpty()) {
                val (merged, added, skipped) = mergeJournal(App.journal, plan.journal)
                App.applyJournalMerge(merged)
                journalMsg = " Jurnal: $added baru digabung, $skipped duplikat dilewati."
            }
            App.saveStrategy()
            App.prefs.edit().putString("provider", App.provider).putString("strategy", App.strategy).apply()
            snack(this, "Konfigurasi dipulihkan. Sinyal/riwayat lama tetap dipertahankan.$journalMsg")
            recreate()
        } catch (e: Exception) { snack(this, "Gagal menerapkan: ${e.message}") }
    }

    private fun testConn() {
        findViewById<TextView>(R.id.status).text = "Mengetes…"
        runBg {
            val rows = ArrayList<SigItem>()
            fun t(name: String, prov: String, fn: () -> String) {
                val t0 = System.nanoTime()
                try {
                    val info = fn()
                    App.recordHealth(prov, true, (System.nanoTime() - t0) / 1_000_000, null, info)
                    rows.add(SigItem("OK", name, "$info · ${(System.nanoTime() - t0) / 1e6}ms".take(60), ""))
                } catch (e: Exception) {
                    val msg = e.message ?: "?"
                    App.recordHealth(prov, false, (System.nanoTime() - t0) / 1_000_000, classifyFetchError(msg), msg)
                    rows.add(SigItem("××", name, msg.take(60), ""))
                }
            }
            t("Binance", "binance") { val j = topPairs("binance", 1); if (j.isEmpty()) throw RuntimeException("daftar kosong"); j[0] }
            t("Bybit", "bybit") { val j = topPairs("bybit", 1); if (j.isEmpty()) throw RuntimeException("daftar kosong"); j[0] }
            t("Yahoo", "yahoo") { val c = getCandles("yahoo", "EUR/USD", "1h", 60); "${c.candles.size}c EUR/USD" }
            t("Demo", "demo") { val c = getCandles("demo", "BTCUSDT", "15m", 60); "${c.candles.size}c lokal" }
            runOnUiThread {
                findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.connList).apply {
                    vertical(this@SettingsActivity)
                    adapter = SigAdapter(rows)
                }
                findViewById<TextView>(R.id.status).text = "Selesai. Yang gagal berarti diblokir jaringan/perangkat."
                try { paintHealth() } catch (e: Exception) { /* abaikan */ }
            }
        }
    }

    private fun paintFetch() {
        findViewById<TextView>(R.id.status).text =
            if (fetchHistory.isEmpty()) "Belum ada fetch sesi ini." else "Fetch terakhir tercatat."
    }
}
