package com.aether.signal.premium.ui

import android.widget.LinearLayout
import android.widget.TextView
import com.aether.signal.premium.data.fetchHistory
import com.aether.signal.premium.data.getCandles
import com.aether.signal.premium.data.topPairs
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : BaseActivity("settings") {
    override fun subtitle() = ""
    private var status: TextView? = null
    private var connBox: LinearLayout? = null

    override fun build(body: LinearLayout) {
        AppHolder.set(this)
        appBar.showBack(false)
        section(body, "set_prov", "Data & Provider", true, { App.provider }) { box ->
            box.addView(fieldLabel(this, "Provider default"))
            val provs = listOf("binance", "bybit", "yahoo", "demo")
            val pd = DropDown(this); box.addView(pd)
            pd.setOptions(provs, provs.indexOf(App.provider).coerceAtLeast(0)) { App.provider = provs[it]; recreate() }
            box.addView(tbtn(this, "Simpan", 0) {
                App.prefs.edit().putString("provider", App.provider).apply()
                toast(this, "Tersimpan.")
            })
            box.addView(smBtn(this, "Export data (JSON)") { export() })
        }
        section(body, "set_conn", "Koneksi", true, { "" }) { box ->
            desc(box, "Binance/Bybit sering diblokir jaringan — pakai Yahoo atau Demo bila GAGAL.")
            box.addView(tbtn(this, "Tes Koneksi", 1) { testConn() })
            status = statusTv(this); box.addView(status)
            connBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(connBox)
        }
        section(body, "set_ai", "AI Analysis", false, { "" }) { box ->
            box.addView(fieldLabel(this, "Kedalaman default"))
            val depths = listOf("Ringkas", "Mendalam")
            var d = App.prefs.getString("ai_depth", "Ringkas") ?: "Ringkas"
            val dd = DropDown(this); box.addView(dd)
            dd.setOptions(depths, depths.indexOf(d).coerceAtLeast(0)) { d = depths[it]; App.prefs.edit().putString("ai_depth", d).apply() }
            desc(box, "AI Analysis memakai interpreter lokal atas data engine (tanpa API key, tanpa endpoint eksternal).")
        }
        section(body, "set_fetch", "Fetch terakhir", false, { "${fetchHistory.size}" }) { box ->
            table(box, listOf("Provider", "Symbol", "Dapat", "Cache"),
                fetchHistory.takeLast(10).reversed().map { m -> listOf(m.provider, m.symbol, m.received.toString(), m.cacheUsed) })
        }
        section(body, "set_about", "Tentang", false, { "4.1-native" }) { box ->
            desc(box, "Aether Signal 4.1-native (debug) · com.aether.signal.premium · Android Native Kotlin Classic Views · Sinyal edukasi, bukan nasihat keuangan.")
        }
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
            toast(this, "Tersimpan: ${f.absolutePath}")
        } catch (e: Exception) { toast(this, "Export gagal: ${e.message}") }
    }

    private fun testConn() {
        status?.text = "Mengetes…"
        connBox?.removeAllViews()
        Thread {
            val rows = ArrayList<List<String>>()
            fun t(name: String, fn: () -> String) {
                val t0 = System.nanoTime()
                try {
                    val info = fn()
                    rows.add(listOf(name, "OK", "$info · ${(System.nanoTime() - t0) / 1e6}ms".take(44)))
                } catch (e: Exception) {
                    rows.add(listOf(name, "GAGAL", (e.message ?: "?").take(44)))
                }
            }
            t("Binance") { val j = topPairs("binance", 1); if (j.isEmpty()) throw RuntimeException("daftar kosong"); j[0] }
            t("Bybit") { val j = topPairs("bybit", 1); if (j.isEmpty()) throw RuntimeException("daftar kosong"); j[0] }
            t("Yahoo") { val c = getCandles("yahoo", "EUR/USD", "1h", 60); "${c.candles.size}c EUR/USD" }
            t("Demo") { val c = getCandles("demo", "BTCUSDT", "15m", 60); "${c.candles.size}c lokal" }
            runOnUiThread {
                connBox?.removeAllViews()
                table(connBox!!, listOf("Provider", "Hasil", "Detail"), rows)
                status?.text = "Selesai. Yang GAGAL berarti diblokir jaringan/perangkat."
            }
        }.start()
    }
}
