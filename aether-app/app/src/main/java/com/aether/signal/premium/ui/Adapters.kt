package com.aether.signal.premium.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aether.signal.premium.R

// Adapter RecyclerView generik untuk daftar aplikasi. Data nyata saja.
// Ikon pair: lihat PairIcons (satu-satunya sumber pemetaan).


data class WatchItem(val sym: String, val price: String, val chg: Double?, val sig: String, val err: String?, val spark: List<Double> = emptyList(), val stale: Boolean = false, val updatedAt: Long = 0L)

class WatchAdapter(var items: List<WatchItem>, val onClick: (WatchItem) -> Unit) : RecyclerView.Adapter<WatchAdapter.H>() {
    private var ctx: android.content.Context? = null
    init { setHasStableIds(true) }

    /** Ganti data TANPA membuat adapter baru (posisi scroll terjaga, A11). */
    fun update(newItems: List<WatchItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Perbarui satu baris; no-op bila simbol tak terlihat (filter aktif). */
    fun notifyRow(sym: String) {
        val i = items.indexOfFirst { it.sym == sym }
        if (i >= 0) notifyItemChanged(i)
    }

    /** Ganti SATU item dengan data segar lalu gambar ulang barisnya.
     *  WAJIB dipakai setelah fetch: snapshot lama di adapter tidak berubah
     *  sendiri (akar harga/spark tak pernah tampil). */
    fun notifyRow(sym: String, item: WatchItem) {
        val i = items.indexOfFirst { it.sym == sym }
        if (i < 0) return
        (items as? MutableList<WatchItem>)?.set(i, item) ?: run {
            items = items.toMutableList().also { it[i] = item }
        }
        notifyItemChanged(i)
    }
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val sym: TextView = v.findViewById(R.id.sym)
        val price: TextView = v.findViewById(R.id.price)
        val spark: SparkView = v.findViewById(R.id.spark)
        val chg: TextView = v.findViewById(R.id.chg)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
        ctx = p.context
        return H(LayoutInflater.from(p.context).inflate(R.layout.item_watch, p, false))
    }
    override fun getItemCount() = items.size
    override fun getItemId(p: Int) = items[p].sym.hashCode().toLong()
    override fun onBindViewHolder(h: H, i: Int) {
        val r = items[i]
        try { h.icon.setImageDrawable(PairIcons.iconFor(ctx ?: h.itemView.context, r.sym)) } catch (e: Exception) { /* ikon jangan matikan baris */ }
        h.sym.text = r.sym
        // A7: harga cache ditampilkan REDUP + label waktu — bukan harga live.
        // Tanpa cache dan gagal: "—" + ERR (bukan angka nol, A5).
        h.price.text = if (r.err != null && r.price == "—") "—" else r.price
        h.price.alpha = if (r.stale) 0.55f else 1f
        h.spark.setData(r.spark)
        h.sym.text = r.sym
        if (r.err != null && r.price == "—") {
            h.chg.text = "ERR"
            h.chg.setTextColor(0xFFF87171.toInt())
        } else if (r.stale) {
            h.chg.text = "⏱ " + fmtClock(r.updatedAt)
            h.chg.setTextColor(0xFF94A3B8.toInt())
        } else if (r.chg != null && r.chg.isFinite()) {
            val up = r.chg >= 0
            h.chg.text = (if (up) "▲ +" else "▼ ") + App.fmt(r.chg) + "%"
            h.chg.setTextColor((if (up) 0xFF10B981 else 0xFFF87171).toInt())
        } else {
            h.chg.text = r.sig
            h.chg.setTextColor(0xFF94A3B8.toInt())
        }
        h.itemView.setOnClickListener { if (r.err == null || r.price != "—") onClick(r) }
    }
}

data class SigItem(val dir: String, val main: String, val sub: String, val right: String, val sym: String = "")

open class SigAdapter(var items: List<SigItem>, var onClick: ((Int) -> Unit)? = null, var wrapMain: Boolean = false) : RecyclerView.Adapter<SigAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val dir: TextView = v.findViewById(R.id.dir)
        val main: TextView = v.findViewById(R.id.main)
        val sub: TextView = v.findViewById(R.id.sub)
        val right: TextView = v.findViewById(R.id.right)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.item_sig, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val r = items[i]
        if (r.sym.isNotEmpty()) {
            try {
                h.icon.visibility = View.VISIBLE
                h.icon.setImageDrawable(PairIcons.iconFor(h.itemView.context, r.sym))
            } catch (e: Exception) { h.icon.visibility = View.GONE }
        } else h.icon.visibility = View.GONE
        h.dir.text = r.dir
        h.dir.setTextColor((if (r.dir == "LONG") 0xFF10B981 else 0xFFF87171).toInt())
        h.main.text = r.main
        h.main.maxLines = if (wrapMain) 100 else 1
        h.sub.text = r.sub
        h.right.text = r.right
        // Posisi segar saat diklik (bukan posisi saat bind yang bisa basi).
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick?.invoke(pos)
        }
    }
}

/** V22: baris log monitor — waktu + teks, warna by jenis (info/warn/err). */
class LogAdapter(var items: List<MonLogLine>, var timeFmt: (Long) -> String) : RecyclerView.Adapter<LogAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView = v.findViewById(R.id.lt)
        val x: TextView = v.findViewById(R.id.lx)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.item_log, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val r = items[i]
        h.t.text = try { timeFmt(r.t) } catch (e: Exception) { "" }
        h.x.text = r.text
        h.x.setTextColor((when (r.kind) {
            2 -> 0xFFF87171
            1 -> 0xFFFBBF24
            else -> 0xFFF1F5F9
        }).toInt())
    }
}

class KvAdapter(var items: List<Pair<String, String>>) : RecyclerView.Adapter<KvAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val k: TextView = v.findViewById(R.id.k)
        val val_: TextView = v.findViewById(R.id.v)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.item_kv, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        h.k.text = items[i].first
        h.val_.text = items[i].second
    }
}

class ProgAdapter(var items: List<Triple<String, String, Int>>) : RecyclerView.Adapter<ProgAdapter.H>() {    class H(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView = v as TextView
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
        val tv = TextView(p.context)
        tv.setTextColor(0xFF94A3B8.toInt())
        tv.textSize = 12f
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setPadding(0, 6, 0, 6)
        return H(tv)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val (sym, icon, col) = items[i]
        h.t.text = "$icon  $sym"
        h.t.setTextColor(col)
    }
}

fun RecyclerView.vertical(ctx: android.content.Context) {
    layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
    addItemDecoration(object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: android.graphics.Rect, v: View, p: RecyclerView, s: RecyclerView.State) {
            out.bottom = 1
        }
    })
}

/** P9: baris pair kustom — ikon + drag handle + hapus. Urutan = urutan data. */
class CustomPairAdapter(
    var items: MutableList<String>,
    val onDelete: (String) -> Unit,
    val onMove: (from: Int, to: Int) -> Unit,
    val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<CustomPairAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val sym: TextView = v.findViewById(R.id.sym)
        val drag: TextView = v.findViewById(R.id.drag)
        val del: View = v.findViewById(R.id.del)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        H(LayoutInflater.from(p.context).inflate(R.layout.item_custompair, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val s = items[i]
        try { h.icon.setImageDrawable(PairIcons.iconFor(h.itemView.context, s)) } catch (e: Exception) { /* ikon opsional */ }
        h.sym.text = s
        h.drag.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_DOWN) onDragStart(h)
            false
        }
        h.del.setOnClickListener { onDelete(s) }
    }
    fun move(from: Int, to: Int) {
        if (from == to) return
        val s = items.removeAt(from)
        items.add(to, s)
        notifyItemMoved(from, to)
        onMove(from, to)
    }
}
