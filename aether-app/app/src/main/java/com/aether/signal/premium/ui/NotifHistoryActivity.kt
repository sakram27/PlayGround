package com.aether.signal.premium.ui

import android.view.View
import android.widget.TextView
import com.aether.signal.premium.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// FITUR 3 (V12): halaman riwayat notifikasi — data nyata dari NotifBus.history().
// Menghapus riwayat TIDAK menyentuh deduplikasi; reset dedup terpisah.
class NotifHistoryActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_notif_history
    override val showBack = true

    override fun build() {
        setBar("Riwayat Notifikasi", "Kejadian live mesin · status apa adanya")
        findViewById<MaterialButton>(R.id.btnHistClear).setOnClickListener { confirmClear() }
        findViewById<MaterialButton>(R.id.btnHistResetDedup).setOnClickListener { confirmResetDedup() }
        paint()
    }

    override fun onResume() {
        super.onResume()
        try { paint() } catch (e: Exception) { /* layout belum siap */ }
    }

    private fun paint() {
        NotifBus.init(this)
        val hist = NotifBus.history()
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.histList)
        list.vertical(this)
        findViewById<TextView>(R.id.histEmpty).visibility = if (hist.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.histStatus).text =
            "Tercatat: ${hist.size} dari maksimal ${NOTIF_HISTORY_CAP}. " +
                "Status deduplikasi aktif: ${NotifBus.dedupCount()} kunci."
        if (hist.isEmpty()) { list.adapter = SigAdapter(emptyList()); return }
        list.adapter = SigAdapter(hist.map { r ->
            SigItem(
                if (r.dir == "LONG") "▲" else if (r.dir == "SHORT") "▼" else "•",
                "${notifKindLabel(r.kind)} · ${r.dir} ${r.pair}",
                "${App.fmtDate(r.at)} · ${r.tf} · harga ${App.fmt(r.price, 4)}",
                notifStatusLabel(r.status),
                r.pair
            )
        })
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
