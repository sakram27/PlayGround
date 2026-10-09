package com.aether.signal.premium.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aether.signal.premium.R

// Adapter RecyclerView generik untuk daftar aplikasi. Data nyata saja.

fun pairIconRes(sym: String): Int {
    val s = sym.uppercase()
    return when {
        s.contains("XAU") || s.contains("GOLD") || s.contains("XAG") || s.contains("SILVER") || s.startsWith("XAU") -> R.drawable.ic_metal
        s.contains("/") || s.contains("USD/") || s.contains("EUR") || s.contains("GBP") || s.contains("JPY") || s.contains("IDR") -> R.drawable.ic_forex
        else -> R.drawable.ic_coin
    }
}

data class WatchItem(val sym: String, val price: String, val chg: Double?, val sig: String, val err: String?)

class WatchAdapter(var items: List<WatchItem>, val onClick: (WatchItem) -> Unit) : RecyclerView.Adapter<WatchAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val sym: TextView = v.findViewById(R.id.sym)
        val price: TextView = v.findViewById(R.id.price)
        val chg: TextView = v.findViewById(R.id.chg)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.item_watch, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val r = items[i]
        h.icon.setImageResource(pairIconRes(r.sym))
        h.sym.text = r.sym
        h.price.text = if (r.err != null) "—" else r.price
        if (r.err != null) {
            h.chg.text = "ERR"
            h.chg.setTextColor(0xFFF6465D.toInt())
        } else if (r.chg != null && r.chg.isFinite()) {
            val up = r.chg >= 0
            h.chg.text = (if (up) "▲ +" else "▼ ") + App.fmt(r.chg) + "%"
            h.chg.setTextColor((if (up) 0xFF0ECB81 else 0xFFF6465D).toInt())
        } else {
            h.chg.text = r.sig
            h.chg.setTextColor(0xFF8B95A5.toInt())
        }
        h.itemView.setOnClickListener { if (r.err == null) onClick(r) }
    }
}

data class SigItem(val dir: String, val main: String, val sub: String, val right: String)

open class SigAdapter(var items: List<SigItem>, var onClick: ((Int) -> Unit)? = null) : RecyclerView.Adapter<SigAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val dir: TextView = v.findViewById(R.id.dir)
        val main: TextView = v.findViewById(R.id.main)
        val sub: TextView = v.findViewById(R.id.sub)
        val right: TextView = v.findViewById(R.id.right)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.item_sig, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val r = items[i]
        h.dir.text = r.dir
        h.dir.setTextColor((if (r.dir == "LONG") 0xFF0ECB81 else 0xFFF6465D).toInt())
        h.main.text = r.main
        h.sub.text = r.sub
        h.right.text = r.right
        h.itemView.setOnClickListener { onClick?.invoke(i) }
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

data class PairCheckItem(val sym: String, var checked: Boolean, val state: String)

class PairCheckAdapter(var items: List<PairCheckItem>, val onToggle: (PairCheckItem, Boolean) -> Unit) : RecyclerView.Adapter<PairCheckAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val icon: ImageView = v.findViewById(R.id.icon)
        val sym: TextView = v.findViewById(R.id.sym)
        val state: TextView = v.findViewById(R.id.state)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.item_paircheck, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val r = items[i]
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = r.checked
        h.icon.setImageResource(pairIconRes(r.sym))
        h.sym.text = r.sym
        h.state.text = r.state
        h.check.setOnCheckedChangeListener { _, on -> r.checked = on; onToggle(r, on) }
        h.itemView.setOnClickListener { h.check.toggle() }
    }
}

data class StratItem(val id: String, val name: String, val desc: String, val active: Boolean, val inCombo: Boolean)

class StratAdapter(var items: List<StratItem>, val onUse: (StratItem) -> Unit, val onCombo: (StratItem, Boolean) -> Unit) : RecyclerView.Adapter<StratAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val desc: TextView = v.findViewById(R.id.desc)
        val use: android.widget.Button = v.findViewById(R.id.use)
        val combo: CheckBox = v.findViewById(R.id.combo)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.item_strat, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: H, i: Int) {
        val r = items[i]
        h.name.text = r.name
        h.name.setTextColor((if (r.active) 0xFF0ECB81 else 0xFFE8EDF2).toInt())
        h.desc.text = "${r.id} · ${r.desc}"
        h.use.text = if (r.active) "✓ Dipakai" else "Pakai"
        h.use.setOnClickListener { onUse(r) }
        h.combo.setOnCheckedChangeListener(null)
        h.combo.isChecked = r.inCombo
        h.combo.setOnCheckedChangeListener { _, on -> onCombo(r, on) }
    }
}

class ProgAdapter(var items: List<Triple<String, String, Int>>) : RecyclerView.Adapter<ProgAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val t: TextView = v as TextView
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
        val tv = TextView(p.context)
        tv.setTextColor(0xFF8B95A5.toInt())
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
