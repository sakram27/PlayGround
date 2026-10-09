package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

// FITUR 3 (V12): halaman riwayat notifikasi — data nyata dari NotifBus.history().
// V13: + pencarian & penyaringan (sumber tunggal, tak menghapus catatan),
//      + ekspor CSV via SAF (pola yang sama dengan SignalsActivity).
// Menghapus riwayat TIDAK menyentuh deduplikasi; reset dedup terpisah.
class NotifHistoryActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_notif_history
    override val showBack = true

    private var query = ""
    private var kindSel = "all"
    private var statusSel = "all"

    override fun build() {
        setBar("Riwayat Notifikasi", "Kejadian live mesin · status apa adanya")
        findViewById<MaterialButton>(R.id.btnHistClear).setOnClickListener { confirmClear() }
        findViewById<MaterialButton>(R.id.btnHistResetDedup).setOnClickListener { confirmResetDedup() }
        findViewById<MaterialButtonToggleGroup>(R.id.histKindGroup).apply {
            check(R.id.histKindAll)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    kindSel = when (id) {
                        R.id.histKindEntry -> "entry"
                        R.id.histKindTp -> "tp"
                        R.id.histKindSl -> "sl"
                        else -> "all"
                    }
                    paint()
                }
            }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.histStatusGroup).apply {
            check(R.id.histStatusAll)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    statusSel = when (id) {
                        R.id.histStatusSent -> "sent"
                        R.id.histStatusHeld -> "held"
                        R.id.histStatusFailed -> "failed"
                        else -> "all"
                    }
                    paint()
                }
            }
        }
        findViewById<TextInputEditText>(R.id.histSearch).addTextChangedListener {
            query = it?.toString() ?: ""
            paint()
        }
        findViewById<MaterialButton>(R.id.btnHistClearFilter).setOnClickListener {
            query = ""; kindSel = "all"; statusSel = "all"
            findViewById<TextInputEditText>(R.id.histSearch).setText("")
            findViewById<MaterialButtonToggleGroup>(R.id.histKindGroup).check(R.id.histKindAll)
            findViewById<MaterialButtonToggleGroup>(R.id.histStatusGroup).check(R.id.histStatusAll)
            paint()
        }
        findViewById<MaterialButton>(R.id.btnHistExport).setOnClickListener { exportHistCsv() }
        paint()
    }

    override fun onResume() {
        super.onResume()
        try { paint() } catch (e: Exception) { /* layout belum siap */ }
    }

    /** Daftar tampil = riwayat aktual yang disaring. Urutan dipertahankan (terbaru dulu). */
    private fun shown(): List<NotifRec> =
        filterNotifHistory(NotifBus.history(), query, kindSel, statusSel)

    private fun paint() {
        NotifBus.init(this)
        val all = NotifBus.history()
        val items = shown()
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.histList)
        list.vertical(this)
        val empty = findViewById<TextView>(R.id.histEmpty)
        if (all.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = "Belum ada notifikasi tercatat. Riwayat terisi saat mesin live mengirim sinyal entry/SL/TP."
        } else if (items.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = "Tidak ada catatan yang cocok dengan pencarian/filter saat ini."
        } else {
            empty.visibility = View.GONE
        }
        findViewById<TextView>(R.id.histStatus).text =
            "Tercatat: ${all.size} dari maksimal ${NOTIF_HISTORY_CAP}. " +
                "Status deduplikasi aktif: ${NotifBus.dedupCount()} kunci."
        findViewById<TextView>(R.id.histCount).text =
            "Menampilkan ${items.size} dari ${all.size} catatan."
        if (items.isEmpty()) { list.adapter = SigAdapter(emptyList()); return }
        list.adapter = SigAdapter(items.map { r ->
            SigItem(
                if (r.dir == "LONG") "▲" else if (r.dir == "SHORT") "▼" else "•",
                "${notifKindLabel(r.kind)} · ${r.dir} ${r.pair}",
                "${App.fmtDate(r.at)} · ${r.tf} · harga ${App.fmt(r.price, 4)}",
                notifStatusLabel(r.status),
                r.pair
            )
        })
    }

    /** V13 F1: ekspor tampilan saat ini (catatan nyata) — SAF pilih lokasi, fallback bagikan. */
    private fun exportHistCsv() {
        val items = shown()
        if (items.isEmpty()) {
            snack(this, if (NotifBus.history().isEmpty())
                "Riwayat kosong — tidak ada yang diekspor."
            else "Tidak ada catatan yang cocok dengan filter — tidak ada yang diekspor.")
            return
        }
        pendingCsv = buildNotifCsv(items)
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_TITLE, "aether-notifikasi.csv")
            }
            startActivityForResult(i, 3302)
        } catch (e: Exception) {
            shareCsvFallback()
        }
    }

    private var pendingCsv: String? = null

    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 3302 || res != RESULT_OK || data?.data == null) return
        try {
            contentResolver.openOutputStream(data.data!!)!!.bufferedWriter().use { it.write(pendingCsv ?: "") }
            snack(this, "CSV tersimpan di lokasi pilihan. Riwayat tidak diubah.")
        } catch (e: Exception) { snack(this, "Gagal menyimpan CSV: ${e.message}") }
        pendingCsv = null
    }

    private fun shareCsvFallback() {
        try {
            val dir = java.io.File(filesDir, "shared").apply { mkdirs() }
            val f = java.io.File(dir, "aether-notifikasi.csv")
            f.writeText(pendingCsv ?: "")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            val sh = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(sh, "Bagikan CSV"))
        } catch (e: Exception) { snack(this, "Export gagal: ${e.message}") }
        pendingCsv = null
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus riwayat notifikasi?")
            .setMessage("Semua catatan riwayat akan dihapus. Status deduplikasi TETAP dipertahankan " +
                "agar notifikasi lama tidak terkirim ulang.")
            .setPositiveButton("Hapus") { _, _ ->
                NotifBus.clearHistory()
                paint()
                snack(this, "Riwayat dihapus. Deduplikasi tetap utuh.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmResetDedup() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset deduplikasi?")
            .setMessage("Setelah direset, kejadian yang pernah diberitahukan dapat muncul kembali " +
                "sebagai notifikasi baru. Riwayat tidak terpengaruh.")
            .setPositiveButton("Reset") { _, _ ->
                NotifBus.resetDedup()
                paint()
                snack(this, "Deduplikasi direset.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
