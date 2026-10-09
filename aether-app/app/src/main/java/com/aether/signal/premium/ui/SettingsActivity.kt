package com.aether.signal.premium.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import com.aether.signal.premium.R
import com.aether.signal.premium.data.fetchHistory
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs
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
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { testConn() }
        paintFetch()
        paintNotif()
        paintAlways()
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

    override fun onResume() {
        super.onResume()
        try { paintNotifStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintAlwaysStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintBattStatus() } catch (e: Exception) { /* layout belum siap */ }
        try { paintNetStatus() } catch (e: Exception) { /* layout belum siap */ }
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
            val f = java.io.File(filesDir, "aether-export.json")
            f.writeText(o.toString(1))
            snack(this, "Tersimpan: ${f.absolutePath}")
        } catch (e: Exception) { snack(this, "Export gagal: ${e.message}") }
    }

    private fun testConn() {
        findViewById<TextView>(R.id.status).text = "Mengetes…"
        runBg {
            val rows = ArrayList<SigItem>()
            fun t(name: String, fn: () -> String) {
                val t0 = System.nanoTime()
                try {
                    val info = fn()
                    rows.add(SigItem("OK", name, "$info · ${(System.nanoTime() - t0) / 1e6}ms".take(60), ""))
                } catch (e: Exception) {
                    rows.add(SigItem("××", name, (e.message ?: "?").take(60), ""))
                }
            }
            t("Binance") { val j = topPairs("binance", 1); if (j.isEmpty()) throw RuntimeException("daftar kosong"); j[0] }
            t("Bybit") { val j = topPairs("bybit", 1); if (j.isEmpty()) throw RuntimeException("daftar kosong"); j[0] }
            t("Yahoo") { val c = getCandles("yahoo", "EUR/USD", "1h", 60); "${c.candles.size}c EUR/USD" }
            t("Demo") { val c = getCandles("demo", "BTCUSDT", "15m", 60); "${c.candles.size}c lokal" }
            runOnUiThread {
                findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.connList).apply {
                    vertical(this@SettingsActivity)
                    adapter = SigAdapter(rows)
                }
                findViewById<TextView>(R.id.status).text = "Selesai. Yang gagal berarti diblokir jaringan/perangkat."
            }
        }
    }

    private fun paintFetch() {
        findViewById<TextView>(R.id.status).text =
            if (fetchHistory.isEmpty()) "Belum ada fetch sesi ini." else "Fetch terakhir tercatat."
    }
}
