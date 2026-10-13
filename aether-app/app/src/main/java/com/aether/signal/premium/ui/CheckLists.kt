package com.aether.signal.premium.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.aether.signal.premium.R

// Daftar pilihan native Android (ListView + kotak centang jelas).
// - Pilihan tunggal  -> CHOICE_MODE_SINGLE + simple_list_item_single_choice.
// - Pilihan banyak   -> CHOICE_MODE_MULTIPLE + simple_list_item_multiple_choice.
// - Grup header      -> baris non-checkable (isEnabled=false), tidak memicu aksi.
// SCROLL (H): tiap ListView di dalam ScrollView memakai tinggi TETAP di layout
// (220-320dp) + fixScrollConflict() di bawah agar induk tak mencuri gestur.

/** Cegah ScrollView/NestedScrollView induk mencuri scroll vertikal milik ListView.
 *  Tanpa ini daftar di dalam kartu ScrollView tidak bisa digulir di perangkat. */
fun ListView.fixScrollConflict() {
    setOnTouchListener { v, _ ->
        v.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }
}

/** ListView pilihan tunggal; tap item = pilih. */
fun Activity.bindSingleChoice(
    lv: ListView,
    labels: List<String>,
    checkedIdx: Int,
    onPick: (Int) -> Unit
) {
    lv.choiceMode = ListView.CHOICE_MODE_SINGLE
    lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, labels)
    lv.setItemChecked(checkedIdx.coerceIn(0, labels.size - 1), true)
    lv.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
        lv.setItemChecked(pos, true)
        onPick(pos)
    }
}

/** ListView pilihan banyak; tap item/checkbox = toggle. */
fun Activity.bindMultiChoice(
    lv: ListView,
    labels: List<String>,
    checked: BooleanArray,
    onToggle: (Int, Boolean) -> Unit
) {
    lv.choiceMode = ListView.CHOICE_MODE_MULTIPLE
    lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
    labels.indices.forEach { lv.setItemChecked(it, checked.getOrElse(it) { false }) }
    lv.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
        // ListView sudah membalik status; baca status aktual agar konsisten.
        val on = lv.isItemChecked(pos)
        onToggle(pos, on)
    }
}

sealed class CheckRow {
    data class Header(val title: String) : CheckRow()
    data class Item(val key: String, val label: String) : CheckRow()
}

/** Adapter grup: header tidak bisa disentuh, item bercetak centang. */
class GroupedCheckAdapter(
    private val act: Activity,
    private val rows: List<CheckRow>
) : BaseAdapter() {
    override fun getCount() = rows.size
    override fun getItem(p: Int) = rows[p]
    override fun getItemId(p: Int) = p.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(p: Int) = if (rows[p] is CheckRow.Header) 0 else 1
    override fun isEnabled(p: Int) = rows[p] is CheckRow.Item
    override fun getView(p: Int, reuse: View?, parent: ViewGroup): View {
        val r = rows[p]
        if (r is CheckRow.Header) {
            val tv = (reuse as? TextView) ?: TextView(act).apply {
                setTextColor(0xFF64748B.toInt())
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(4, 18, 4, 6)
            }
            tv.text = r.title.uppercase()
            return tv
        }
        val item = r as CheckRow.Item
        val ctv = (reuse as? CheckedTextView) ?: act.layoutInflater.inflate(
            android.R.layout.simple_list_item_multiple_choice, parent, false
        ) as CheckedTextView
        ctv.text = item.label
        ctv.setTextColor(0xFFF1F5F9.toInt())
        ctv.textSize = 14f
        ctv.minHeight = (48 * act.resources.displayMetrics.density).toInt()
        // Sinkronkan visual dengan status ListView (penting saat daur ulang view).
        ctv.isChecked = (parent as ListView).isItemChecked(p)
        return ctv
    }
}

/** Mengikat ListView grup ke predicate pilihan; mengembalikan fungsi refresh tampilan. */
fun Activity.bindGroupedMulti(
    lv: ListView,
    rows: List<CheckRow>,
    isChecked: (String) -> Boolean,
    onToggle: (key: String, on: Boolean) -> Unit
): () -> Unit {
    lv.choiceMode = ListView.CHOICE_MODE_MULTIPLE
    lv.adapter = GroupedCheckAdapter(this, rows)
    fun refresh() {
        rows.forEachIndexed { i, r ->
            if (r is CheckRow.Item) lv.setItemChecked(i, isChecked(r.key))
        }
    }
    refresh()
    lv.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
        val r = rows.getOrNull(pos) as? CheckRow.Item ?: return@OnItemClickListener
        val on = lv.isItemChecked(pos)
        onToggle(r.key, on)
        // Paksa gambar ulang baris (dawa ulang view bisa basi).
        (lv.getChildAt(pos - lv.firstVisiblePosition) as? CheckedTextView)?.isChecked = on
    }
    return ::refresh
}

/** Adapter baris pair: ikon + kotak centang (satu sumber ikon). */
class IconCheckAdapter(
    private val act: Activity,
    private val syms: List<String>,
    private val single: Boolean
) : BaseAdapter() {
    override fun getCount() = syms.size
    override fun getItem(p: Int) = syms[p]
    override fun getItemId(p: Int) = p.toLong()
    override fun getView(p: Int, reuse: View?, parent: ViewGroup): View {
        val v = reuse ?: act.layoutInflater.inflate(R.layout.item_checkpair, parent, false)
        val sym = syms[p]
        val icon = v.findViewById<android.widget.ImageView>(R.id.icon)
        try { icon.setImageDrawable(PairIcons.iconFor(act, sym)) } catch (e: Exception) { /* ikon opsional */ }
        val check = v.findViewById<CheckedTextView>(R.id.check)
        check.text = sym
        check.isChecked = (parent as ListView).isItemChecked(p)
        if (single) check.setCheckMarkDrawable(android.R.drawable.btn_radio)
        return v
    }
}

/** Dialog bottom-sheet multi-pilih generik: search + All/Clear + count + Apply/Cancel. */
fun Activity.showMultiCheckSheet(
    title: String,
    allLabels: List<String>,
    initially: Set<String>,
    searchHint: String = "Cari…",
    extraAction: Pair<String, () -> Set<String>>? = null,
    onApply: (Set<String>) -> Unit
) {
    val staged = LinkedHashSet(initially)
    val sh = BottomSheetDialog(this, R.style.SheetTheme)
    val root = layoutInflater.inflate(R.layout.sheet_checklist, null)
    root.findViewById<TextView>(R.id.title).text = title
    val search = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search)
    search.hint = searchHint
    val lv = root.findViewById<ListView>(R.id.list)
    lv.fixScrollConflict()
    val cnt = root.findViewById<TextView>(R.id.count)
    fun current(): List<String> {
        val q = search.text.toString().uppercase()
        return allLabels.filter { q.isEmpty() || it.uppercase().contains(q) }
    }
    fun render() {
        val vis = current()
        lv.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, vis)
        vis.forEachIndexed { i, s -> lv.setItemChecked(i, staged.contains(s)) }
        cnt.text = "${staged.size} dipilih"
    }
    lv.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
        val vis = current()
        val s = vis.getOrNull(pos) ?: return@OnItemClickListener
        if (lv.isItemChecked(pos)) staged.add(s) else staged.remove(s)
        cnt.text = "${staged.size} dipilih"
    }
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = render()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    root.findViewById<View>(R.id.btnAll).setOnClickListener { current().forEach { staged.add(it) }; render() }
    root.findViewById<View>(R.id.btnClear).setOnClickListener { staged.clear(); render() }
    extraAction?.let { (label, fn) ->
        root.findViewById<android.widget.Button>(R.id.btnExtra).apply {
            visibility = View.VISIBLE
            text = label
            setOnClickListener { staged.addAll(fn()); render() }
        }
    }
    root.findViewById<View>(R.id.btnApply).setOnClickListener { sh.dismiss(); onApply(LinkedHashSet(staged)) }
    render()
    sh.setContentView(root)
    sh.show()
}
