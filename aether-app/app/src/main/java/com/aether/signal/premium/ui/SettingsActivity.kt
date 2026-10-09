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
            setOnItemClickListener { _, _, pos, _ -> App.provider = provs[pos] }
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            App.prefs.edit().putString("provider", App.provider).apply()
            snack(this, "Tersimpan.")
        }
        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { export() }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { testConn() }
        paintFetch()
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
