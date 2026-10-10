package com.aether.signal.premium.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.aether.signal.premium.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// FITUR 8 (V14): jurnal trading lokal. CRUD penuh atas App.journal (persist
// "journal_v1", kunci terpisah dari riwayat/sinyal). Cari + filter pair +
// periode. Taut transaksi by tradeKey eksak (bukan kemiripan waktu/harga).
// Tak ada pengiriman ke mana pun — 100% lokal (+ ikut file Export JSON).
class JournalActivity : BaseActivity(0) {
    override val contentLayout = R.layout.activity_journal
    override val showBack = true

    private var query = ""
    private var pairSel = ""
    private var daysSel = 0

    override fun build() {
        setBar("Jurnal Trading", "Catatan lokal · tidak dikirim ke mana pun")
        findViewById<TextInputEditText>(R.id.jSearch).addTextChangedListener {
            query = it?.toString() ?: ""
            paint()
        }
        findViewById<MaterialButtonToggleGroup>(R.id.jPeriodGroup).apply {
            check(R.id.jPeriodAll)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    daysSel = when (id) {
                        R.id.jPeriod7 -> 7
                        R.id.jPeriod30 -> 30
                        else -> 0
                    }
                    paint()
                }
            }
        }
        findViewById<MaterialButton>(R.id.btnJAdd).setOnClickListener { openEditor(null) }
        paint()
        // Prefill dari detail transaksi backtest (pair + tradeKey eksak).
        val pp = intent.getStringExtra("prefill_pair") ?: ""
        val tk = intent.getStringExtra("prefill_tradeKey") ?: ""
        if (pp.isNotEmpty() || tk.isNotEmpty()) {
            intent.removeExtra("prefill_pair")
            intent.removeExtra("prefill_tradeKey")
            openEditor(null, prefillPair = pp, prefillTradeKey = tk)
        }
    }

    override fun onResume() {
        super.onResume()
        try { paint() } catch (e: Exception) { /* abaikan */ }
    }

    private fun current(): List<JournalEntry> {
        val now = System.currentTimeMillis()
        val from = if (daysSel > 0) now - daysSel * 86400000L else 0L
        return filterJournal(App.journal, query, pairSel, from, 0)
    }

    private fun pairOptions(): List<String> {
        val ps = App.journal.map { it.pair }.filter { it.isNotEmpty() }.distinct().sorted()
        return listOf("Semua") + ps
    }

    private fun paint() {
        App.init(this)
        val items = current()
        // Dropdown pair: dibangun ulang dari pair yang benar-benar ada.
        try {
            val ac = findViewById<MaterialAutoCompleteTextView>(R.id.jPair)
            ac.setSimpleItems(pairOptions().toTypedArray())
            if (ac.text.toString() != pairSel.ifEmpty { "Semua" }) {
                ac.setText(pairSel.ifEmpty { "Semua" }, false)
            }
            ac.setOnClickListener { ac.showDropDown() }
            ac.setOnItemClickListener { _, _, pos, _ ->
                val v = pairOptions().getOrNull(pos) ?: "Semua"
                pairSel = if (v == "Semua") "" else v
                paint()
            }
        } catch (e: Exception) { /* abaikan */ }
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.jList)
        list.vertical(this)
        val empty = findViewById<TextView>(R.id.jEmpty)
        if (App.journal.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = "Belum ada catatan. Ketuk \"＋ Catatan\" untuk menulis yang pertama — tersimpan otomatis di perangkat ini."
        } else if (items.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = "Tidak ada catatan yang cocok dengan pencarian/filter saat ini."
        } else {
            empty.visibility = View.GONE
        }
        findViewById<TextView>(R.id.jCount).text =
            "Menampilkan ${items.size} dari ${App.journal.size} catatan."
        list.adapter = SigAdapter(items.map { e ->
            SigItem("✎",
                (if (e.title.isNotEmpty()) e.title else "(tanpa judul)").take(60),
                "${e.kind} · ${(if (e.pair.isNotEmpty()) e.pair else "umum")} · ${App.fmtDate(e.createdAt)}" +
                    (if (e.tradeKey.isNotEmpty()) " · terkait transaksi" else ""),
                "")
        }, onClick = { pos -> if (pos in items.indices) openViewer(items[pos]) })
    }

    private class Field(val layout: TextInputLayout, val edit: TextInputEditText)

    private fun field(hint: String, initial: String, lines: Int = 1): Field {
        val layout = TextInputLayout(this).apply { this.hint = hint }
        val et = TextInputEditText(layout.context).apply {
            setText(initial)
            if (lines > 1) { setLines(lines); gravity = android.view.Gravity.TOP }
            else maxLines = 1
        }
        layout.addView(et)
        return Field(layout, et)
    }

    /** Editor tambah/ubah. prefillTradeKey hanya dari detail transaksi (eksak). */
    private fun openEditor(
        existing: JournalEntry?,
        prefillPair: String = "",
        prefillTradeKey: String = ""
    ) {
        val kinds = listOf("catatan", "sinyal", "transaksi", "ide")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val fTitle = field("Judul", existing?.title ?: "")
        val fPair = field("Pair (mis. BTCUSDT, kosong = umum)", existing?.pair ?: prefillPair)
        val kindLayout = TextInputLayout(this).apply { hint = "Jenis" }
        val acKind = MaterialAutoCompleteTextView(kindLayout.context).apply {
            setSimpleItems(kinds.toTypedArray())
            setText(existing?.kind?.takeIf { kinds.contains(it) } ?: "catatan", false)
            setOnClickListener { showDropDown() }
        }
        kindLayout.addView(acKind)
        val fBody = field("Catatan bebas (alasan, kondisi pasar, pelajaran…)", existing?.body ?: "", 5)
        root.addView(fTitle.layout)
        root.addView(fPair.layout)
        root.addView(kindLayout)
        root.addView(fBody.layout)
        val tk = existing?.tradeKey ?: prefillTradeKey
        if (tk.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "Terkait transaksi: $tk"
                setTextColor(0xFF94A3B8.toInt()); textSize = 11f
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Catatan baru" else "Ubah catatan")
            .setView(root)
            .setPositiveButton("Simpan") { _, _ ->
                val kind = acKind.text.toString().trim().takeIf { kinds.contains(it) } ?: "catatan"
                if (existing == null) {
                    App.addJournal(JournalEntry("", System.currentTimeMillis(), System.currentTimeMillis(),
                        fPair.edit.text.toString(), kind, tk,
                        fTitle.edit.text.toString(), fBody.edit.text.toString()))
                    snack(this, "Catatan tersimpan di perangkat ini.")
                } else {
                    val ok = App.updateJournal(existing.id, existing.copy(
                        pair = fPair.edit.text.toString(), kind = kind,
                        title = fTitle.edit.text.toString(), body = fBody.edit.text.toString()))
                    snack(this, if (ok) "Catatan diperbarui." else "Gagal memperbarui (id hilang).")
                }
                paint()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openViewer(e: JournalEntry) {
        val msg = StringBuilder()
        msg.append("${e.kind} · ${(if (e.pair.isNotEmpty()) e.pair else "umum")}\n")
        msg.append("Dibuat ${App.fmtDate(e.createdAt)}")
        if (e.updatedAt != e.createdAt) msg.append(" · diubah ${App.fmtDate(e.updatedAt)}")
        msg.append("\n")
        if (e.tradeKey.isNotEmpty()) msg.append("Terkait transaksi: ${e.tradeKey}\n")
        msg.append("\n${e.body.ifEmpty { "(isi kosong)" }}")
        MaterialAlertDialogBuilder(this)
            .setTitle(e.title.ifEmpty { "(tanpa judul)" })
            .setMessage(msg.toString())
            .setPositiveButton("Ubah") { _, _ -> openEditor(e) }
            .setNeutralButton("Hapus") { _, _ -> confirmDelete(e) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun confirmDelete(e: JournalEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus catatan ini?")
            .setMessage("Hanya catatan ini yang dihapus: ${(if (e.title.isNotEmpty()) e.title else "(tanpa judul)").take(80)}")
            .setPositiveButton("Hapus") { _, _ ->
                val ok = App.deleteJournal(e.id)
                snack(this, if (ok) "Catatan dihapus." else "Catatan tidak ditemukan.")
                paint()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
