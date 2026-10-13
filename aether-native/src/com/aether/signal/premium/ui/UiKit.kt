package com.aether.signal.premium.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*

// AETHER DESIGN SYSTEM — Dark Graphite institutional terminal.
// Prinsip: TradingView (grafik dominan, watchlist rapat) + Binance (aksi jelas,
// metric mudah pindai) + Bloomberg (kepadatan terstruktur, tabular numerals).

object C {
    const val BG = 0xFF0B0E13.toInt()          // hitam arang
    const val SURFACE = 0xFF12161D.toInt()     // graphite
    const val CARD = 0xFF171D26.toInt()        // permukaan kartu
    const val CARD2 = 0xFF1C232E.toInt()       // inset
    const val LINE = 0xFF232B36.toInt()        // hairline
    const val TXT = 0xFFE8EDF2.toInt()         // putih lembut
    const val MUT = 0xFF8B95A5.toInt()         // abu netral
    const val DIM = 0xFF5B6572.toInt()
    const val GREEN = 0xFF0ECB81.toInt()       // hijau sinyal (interaktif utama)
    const val GREEN_DIM = 0xFF0A3D2E.toInt()
    const val RED = 0xFFF6465D.toInt()
    const val RED_DIM = 0xFF452227.toInt()
    const val AMBER = 0xFFFFB800.toInt()
    const val ACC = GREEN                       // satu warna interaktif
    const val INK = 0xFF06110D.toInt()          // teks di atas aksen
}

object T {
    const val DISPLAY = 24f
    const val TITLE = 17f
    const val HEAD = 14f
    const val BODY = 13.5f
    const val SMALL = 12f
    const val CAP = 10f
}

object D {
    const val R_CARD = 10f
    const val R_PILL = 20f
    const val H_TAP = 48
    const val PAD = 14
}

fun dp(c: Context, n: Int): Int = (n * c.resources.displayMetrics.density).toInt()
fun dp(v: View, n: Int): Int = dp(v.context, n)
fun dpC(c: Context, n: Int): Int = dp(c, n)

// ---------- kartu terminal: fill graphite + hairline, radius konsisten ----------
open class TerminalCard @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val bg = GradientDrawable()
    init {
        orientation = VERTICAL
        bg.setColor(C.CARD)
        bg.setStroke(dpC(ctx, 1), C.LINE)
        bg.cornerRadius = dpC(ctx, D.R_CARD.toInt()).toFloat()
        background = bg
        val p = dpC(ctx, D.PAD)
        setPadding(p, p, p, p)
        val lp = LayoutParams(-1, -2)
        lp.setMargins(0, 0, 0, dpC(ctx, 10))
        layoutParams = lp
    }
}

// ---------- tombol terminal: hindari tampilan Button bawaan ----------
class TerminalButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var label = ""
    var variant = 0 // 0 primary, 1 ghost, 2 danger, 3 subtle
    var onTap: (() -> Unit)? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 14f * resources.displayMetrics.density; typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()
    init { minimumHeight = dpC(ctx, D.H_TAP); isClickable = true; isFocusable = true }

    override fun onMeasure(w: Int, h: Int) {
        val tw = tx.measureText(label) + dpC(context, 44)
        setMeasuredDimension(resolveSize(tw.toInt(), w), dpC(context, D.H_TAP))
    }

    override fun onDraw(cv: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = dpC(context, 9).toFloat()
        bg.color = when (variant) { 0 -> C.GREEN; 2 -> C.RED_DIM; 3 -> 0x00000000; else -> C.CARD2 }
        if (variant == 1) {
            bg.style = Paint.Style.STROKE; bg.strokeWidth = dpC(context, 1).toFloat(); bg.color = C.LINE
            cv.drawRoundRect(rect, r, r, bg)
            bg.style = Paint.Style.FILL
        } else cv.drawRoundRect(rect, r, r, bg)
        tx.color = when (variant) { 0 -> C.INK; 2 -> C.RED; 3 -> C.ACC; else -> C.TXT }
        cv.drawText(label, width / 2f, height / 2f - (tx.descent() + tx.ascent()) / 2f, tx)
    }

    override fun performClick(): Boolean {
        super.performClick()
        try { onTap?.invoke() } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
        return true
    }
}

fun tbtn(ctx: Activity, t: String, variant: Int = 0, onClick: () -> Unit): TerminalButton =
    TerminalButton(ctx).apply { label = t; this.variant = variant; onTap = onClick }

// ---------- badge persentase ▲/▼ ----------
fun pctBadge(ctx: Context, pct: Double): TextView {
    val up = pct >= 0
    return TextView(ctx).apply {
        text = (if (up) "▲ +" else "▼ ") + App.fmt(pct) + "%"
        setTextColor(if (up) C.GREEN else C.RED)
        textSize = T.SMALL; typeface = Typeface.MONOSPACE
        val g = GradientDrawable()
        g.setColor(if (up) 0xFF0A2E22.toInt() else 0xFF3A1E22.toInt())
        g.cornerRadius = dpC(ctx, D.R_PILL.toInt()).toFloat()
        background = g
        setPadding(dpC(ctx, 8), dpC(ctx, 3), dpC(ctx, 8), dpC(ctx, 3))
    }
}

// ---------- segmented control asli (bukan spinner) ----------
class SegmentedControl @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val items = ArrayList<TerminalButton>()
    var selected = 0
        private set
    var onPick: ((Int) -> Unit)? = null

    fun setOptions(opts: List<String>, sel: Int, onPick: (Int) -> Unit) {
        this.onPick = onPick
        removeAllViews(); items.clear()
        orientation = HORIZONTAL
        val g = GradientDrawable()
        g.setColor(C.CARD2); g.cornerRadius = dpC(context, 9).toFloat()
        background = g
        setPadding(dpC(context, 3), dpC(context, 3), dpC(context, 3), dpC(context, 3))
        opts.forEachIndexed { i, o ->
            val b = TerminalButton(context).apply {
                label = o; variant = 3
                layoutParams = LayoutParams(0, dpC(context, 38), 1f)
                onTap = { pick(i) }
            }
            items.add(b); addView(b)
        }
        pick(sel.coerceIn(opts.indices))
    }

    fun pick(i: Int) {
        selected = i
        items.forEachIndexed { k, b ->
            b.variant = if (k == i) 0 else 3
            b.invalidate()
        }
        try { onPick?.invoke(i) } catch (e: Exception) {}
    }
}

// ---------- bottom navigation 5 tab, ikon digambar ----------
class BottomNavBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    var onTab: ((String) -> Unit)? = null
    private val tabs = listOf("home" to "Home", "markets" to "Markets", "signals" to "Signals", "lab" to "Lab", "settings" to "Settings")
    var active = "home"

    init {
        orientation = HORIZONTAL
        setBackgroundColor(C.SURFACE)
        val p = GradientDrawable()
        p.setColor(C.SURFACE); p.setStroke(dpC(ctx, 1), C.LINE)
        background = p
        setPadding(dpC(ctx, 2), dpC(ctx, 8), dpC(ctx, 2), dpC(ctx, 10))
    }

    fun render() {
        removeAllViews()
        for ((k, label) in tabs) {
            val cell = NavCell(context, k, label, k == active)
            cell.layoutParams = LayoutParams(0, dpC(context, 56), 1f)
            cell.setOnClickListener { if (k != active) onTab?.invoke(k) }
            addView(cell)
        }
    }

    private class NavCell(ctx: Context, val key: String, val label: String, val on: Boolean) : View(ctx) {
        private val ic = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dpC(ctx, 2).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dpC(ctx, 10).toFloat() }

        override fun onDraw(cv: Canvas) {
            val col = if (on) C.GREEN else C.DIM
            ic.color = col; tx.color = col
            if (on) {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.GREEN }
                cv.drawCircle(width / 2f, dpC(context, 4).toFloat(), dpC(context, 2).toFloat(), dot)
            }
            val cx = width / 2f; val top = dpC(context, 10).toFloat(); val s = dpC(context, 9).toFloat()
            val path = Path()
            when (key) {
                "home" -> { path.moveTo(cx - s, top + s); path.lineTo(cx, top); path.lineTo(cx + s, top + s); path.moveTo(cx - s * 0.6f, top + s * 0.8f); path.lineTo(cx - s * 0.6f, top + s * 1.8f); path.lineTo(cx + s * 0.6f, top + s * 1.8f); path.lineTo(cx + s * 0.6f, top + s * 0.8f) }
                "markets" -> { path.moveTo(cx - s, top + s * 1.6f); path.lineTo(cx - s * 0.3f, top + s * 0.5f); path.lineTo(cx + s * 0.2f, top + s); path.lineTo(cx + s, top - s * 0.4f); path.moveTo(cx + s * 0.3f, top - s * 0.4f); path.lineTo(cx + s, top - s * 0.4f); path.lineTo(cx + s, top + s * 0.3f) }
                "signals" -> { path.moveTo(cx + s * 0.3f, top - s * 0.6f); path.lineTo(cx - s * 0.6f, top + s * 0.7f); path.lineTo(cx, top + s * 0.7f); path.lineTo(cx - s * 0.3f, top + s * 2f); path.lineTo(cx + s * 0.6f, top + s * 0.6f); path.lineTo(cx, top + s * 0.6f); path.close() }
                "lab" -> { path.moveTo(cx - s * 0.5f, top - s * 0.6f); path.lineTo(cx + s * 0.5f, top - s * 0.6f); path.moveTo(cx, top - s * 0.6f); path.lineTo(cx, top + s * 0.4f); path.moveTo(cx - s * 0.7f, top + s * 1.9f); path.lineTo(cx + s * 0.7f, top + s * 1.9f); path.moveTo(cx - s * 0.5f, top + s * 1.1f); path.lineTo(cx + s * 0.5f, top + s * 1.1f) }
                else -> { path.addCircle(cx, top + s * 0.7f, s * 0.9f, Path.Direction.CW); path.moveTo(cx, top + s * 0.7f); path.lineTo(cx, top + s * 0.7f) }
            }
            cv.drawPath(path, ic)
            tx.typeface = Typeface.DEFAULT_BOLD
            cv.drawText(label, cx, top + s * 2.9f, tx)
        }
    }
}

// ---------- app bar: identitas + status data + jam + settings ----------
class AppBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    var onSettings: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    private val dotV = View(ctx)
    private val statusV: TextView
    private val clockV: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(C.BG)
        setPadding(dpC(ctx, 14), dpC(ctx, 10), dpC(ctx, 14), dpC(ctx, 10))
        val back = TextView(ctx).apply { text = "‹"; textSize = 24f; setTextColor(C.MUT); setPadding(0, 0, dpC(ctx, 10), 0); visibility = GONE }
        back.setOnClickListener { onBack?.invoke() }
        addView(back)
        val brand = LinearLayout(ctx).apply { orientation = VERTICAL; layoutParams = LayoutParams(0, -2, 1f) }
        brand.addView(TextView(ctx).apply { text = "AETHER SIGNAL"; textSize = T.HEAD; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.TXT); letterSpacing = 0.08f })
        val sub = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dotV.layoutParams = LayoutParams(dpC(ctx, 7), dpC(ctx, 7))
        val dg = GradientDrawable(); dg.setColor(C.AMBER); dg.cornerRadius = 99f
        dotV.background = dg
        sub.addView(dotV)
        statusV = TextView(ctx).apply { text = "feed"; textSize = T.CAP; setTextColor(C.MUT); setPadding(dpC(ctx, 6), 0, 0, 0) }
        sub.addView(statusV)
        brand.addView(sub)
        addView(brand)
        clockV = TextView(ctx).apply { textSize = T.SMALL; typeface = Typeface.MONOSPACE; setTextColor(C.MUT) }
        addView(clockV)
        val st = TextView(ctx).apply { text = "⚙"; textSize = 20f; setTextColor(C.MUT); setPadding(dpC(ctx, 12), dpC(ctx, 6), dpC(ctx, 4), dpC(ctx, 6)) }
        st.setOnClickListener { onSettings?.invoke() }
        addView(st)
        tag = Triple(back, dotV, dg)
        tick()
        postDelayed(object : Runnable { override fun run() { tick(); postDelayed(this, 1000) } }, 1000)
    }

    fun showBack(show: Boolean) { ((tag as Triple<*, *, *>).first as TextView).visibility = if (show) VISIBLE else GONE }

    fun setFeed(ok: Boolean?, msg: String) {
        val col = when (ok) { true -> C.GREEN; false -> C.RED; null -> C.AMBER }
        dotV.background = GradientDrawable().apply { setColor(col); cornerRadius = 99f }
        statusV.text = msg
    }

    private fun tick() {
        try {
            val f = android.icu.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            clockV.text = f.format(java.util.Date())
        } catch (e: Exception) { clockV.text = "" }
    }
}

// ---------- dropdown konsisten (tombol + dialog daftar) ----------
class DropDown @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val btn = TerminalButton(ctx).apply { variant = 1 }
    var options: List<String> = emptyList()
    var selected = 0
    var onPick: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        addView(btn.apply { layoutParams = LayoutParams(-1, dpC(ctx, 46)) })
        btn.setOnClickListener { open() }
    }

    fun setOptions(opts: List<String>, sel: Int, onPick: (Int) -> Unit) {
        options = opts; selected = sel.coerceIn(opts.indices); this.onPick = onPick
        paint()
    }

    private fun paint() {
        btn.label = (if (options.isNotEmpty()) options[selected] else "—") + "  ▾"
        btn.invalidate()
    }

    private fun open() {
        if (options.isEmpty()) return
        AlertDialog.Builder(context).setTitle("Pilih")
            .setSingleChoiceItems(options.toTypedArray(), selected) { d, w ->
                selected = w; paint()
                try { onPick?.invoke(w) } catch (e: Exception) {}
                d.dismiss()
            }.setNegativeButton("Batal", null).show()
    }
}

// ---------- bottom sheet profesional ----------
class Sheet(ctx: Context) : Dialog(ctx) {
    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sv = ScrollView(ctx)
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val pad = dpC(ctx, 16)
        inner.setPadding(pad, dpC(ctx, 8), pad, pad + 40)
        inner.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dpC(ctx, 40), dpC(ctx, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dpC(ctx, 12) }
            background = GradientDrawable().apply { setColor(C.LINE); cornerRadius = 99f }
        })
        inner.addView(box)
        sv.addView(inner)
        setContentView(sv)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.BOTTOM)
        window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(C.SURFACE)
            cornerRadii = floatArrayOf(dpC(ctx, 18).toFloat(), dpC(ctx, 18).toFloat(), dpC(ctx, 18).toFloat(), dpC(ctx, 18).toFloat(), 0f, 0f, 0f, 0f)
        })
        window?.attributes?.windowAnimations = android.R.style.Animation_Dialog
    }
    fun title(t: String) {
        box.addView(TextView(context).apply { text = t; textSize = T.TITLE; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.TXT); setPadding(0, 0, 0, dpC(context, 10)) }, 0)
    }
    fun content(): LinearLayout = box
}

// ---------- baris instrumen rapat ala watchlist ----------
class InstrumentRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val symV = TextView(ctx).apply { textSize = T.HEAD; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LayoutParams(0, -2, 1.3f) }
    private val prV = TextView(ctx).apply { textSize = T.HEAD; typeface = Typeface.MONOSPACE; setTextColor(C.TXT); gravity = Gravity.END; layoutParams = LayoutParams(0, -2, 1f) }
    private val badgeBox = LinearLayout(ctx).apply { gravity = Gravity.END; layoutParams = LayoutParams(0, -2, 0.9f) }
    var onTap: (() -> Unit)? = null
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpC(ctx, 14), dpC(ctx, 12), dpC(ctx, 14), dpC(ctx, 12))
        addView(symV); addView(prV); addView(badgeBox)
        setOnClickListener { try { onTap?.invoke() } catch (e: Exception) {} }
    }
    fun bind(sym: String, price: String, chgPct: Double?, sig: String) {
        symV.text = sym
        prV.text = price
        badgeBox.removeAllViews()
        if (chgPct != null && chgPct.isFinite()) badgeBox.addView(pctBadge(context, chgPct))
        else badgeBox.addView(TextView(context).apply { text = sig; textSize = T.CAP; setTextColor(C.MUT) })
    }
}

// ---------- status loading/kosong/gagal ----------
fun stateBox(ctx: Activity, kind: String, msg: String, retry: (() -> Unit)? = null): LinearLayout {
    val b = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(ctx, 24), 0, dp(ctx, 24)) }
    if (kind == "loading") b.addView(ProgressBar(ctx).apply { indeterminateDrawable?.setTint(C.GREEN) })
    b.addView(TextView(ctx).apply {
        text = msg; textSize = T.SMALL
        setTextColor(if (kind == "error") C.RED else C.MUT)
        gravity = Gravity.CENTER; setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), 0)
    })
    if (kind == "error" && retry != null) b.addView(tbtn(ctx, "Coba lagi", 1, retry))
    return b
}

// ---------- BaseActivity + helper lama yang dipertahankan ----------
abstract class BaseActivity(val tab: String) : Activity() {
    lateinit var appBar: AppBar
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)
        AppHolder.set(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        appBar = AppBar(this).apply {
            onSettings = { navTo("settings") }
            onBack = { finish() }
        }
        root.addView(appBar)
        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 12))
        }
        sv.addView(body)
        root.addView(sv)
        val nav = BottomNavBar(this).apply { active = tab; render(); onTab = { navTo(it) } }
        root.addView(nav)
        setContentView(root)
        try { build(body) } catch (e: Exception) {
            body.addView(stateBox(this, "error", "Gagal memuat layar: ${e.message}"))
        }
    }

    open fun subtitle(): String = ""
    abstract fun build(body: LinearLayout)

    fun navTo(k: String) {
        val cls = when (k) {
            "home" -> DashboardActivity::class.java
            "markets" -> MarketActivity::class.java
            "signals" -> SignalsActivity::class.java
            "lab" -> LabActivity::class.java
            "backtest" -> BacktestActivity::class.java
            "ai" -> AiActivity::class.java
            else -> SettingsActivity::class.java
        }
        startActivity(Intent(this, cls))
        finish()
    }

    // Pindah tanpa finish — untuk alur Lab → Backtest/AI agar tombol Back kembali ke Lab.
    fun navKeep(k: String) {
        val cls = when (k) {
            "backtest" -> BacktestActivity::class.java
            "ai" -> AiActivity::class.java
            else -> LabActivity::class.java
        }
        startActivity(Intent(this, cls))
    }

    override fun onResume() { super.onResume(); App.init(this); AppHolder.set(this) }
}

fun panel(ctx: Activity): TerminalCard = TerminalCard(ctx)

fun title(v: LinearLayout, t: String, meta: String = ""): TextView {
    val tv = TextView(v.context).apply {
        text = if (meta.isEmpty()) t else "$t  ·  $meta"
        setTextColor(C.TXT); textSize = T.TITLE; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(this, 8))
    }
    v.addView(tv)
    return tv
}

fun eyebrow(v: LinearLayout, t: String): TextView {
    val tv = TextView(v.context).apply {
        text = t.uppercase(); setTextColor(C.DIM); textSize = T.CAP; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(this, 2), 0, dp(this, 6))
    }
    v.addView(tv)
    return tv
}

fun desc(v: LinearLayout, t: String): TextView {
    val tv = TextView(v.context).apply {
        text = t; setTextColor(C.MUT); textSize = T.SMALL
        setPadding(0, 0, 0, dp(this, 8))
    }
    v.addView(tv)
    return tv
}

object AppHolder {
    private var c: Activity? = null
    fun set(a: Activity) { c = a }
    fun ctx(): Activity = c!!
}

fun row(v: LinearLayout): LinearLayout {
    val r = LinearLayout(v.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    v.addView(r)
    return r
}

// Kompat: btn/smBtn lama → TerminalButton.
fun btn(ctx: Activity, t: String, primary: Boolean = false, onClick: () -> Unit): TerminalButton =
    tbtn(ctx, t, if (primary) 0 else 1, onClick)

fun smBtn(ctx: Activity, t: String, onClick: () -> Unit): TerminalButton {
    val b = tbtn(ctx, t, 1, onClick)
    b.minimumHeight = dpC(ctx, 38)
    return b
}

fun toast(ctx: Activity, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun statusTv(ctx: Activity): TextView = TextView(ctx).apply {
    text = "Siap."; setTextColor(C.MUT); textSize = T.SMALL
    setPadding(dp(this, 4), dp(this, 6), dp(this, 4), dp(this, 4))
}

fun inputNum(ctx: Activity, value: String): EditText = EditText(ctx).apply {
    setText(value); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    setTextColor(C.TXT); setBackgroundColor(C.CARD2); textSize = T.BODY
    setPadding(dpC(ctx, 10), dpC(ctx, 10), dpC(ctx, 10), dpC(ctx, 10))
    layoutParams = LinearLayout.LayoutParams(-1, dpC(ctx, D.H_TAP))
}

// Kompat: spinner lama → DropDown konsisten.
fun spinner(ctx: Activity, items: List<String>, sel: Int, onSel: (Int) -> Unit): DropDown {
    val d = DropDown(ctx)
    d.layoutParams = LinearLayout.LayoutParams(-1, -2)
    d.setOptions(items, sel, onSel)
    // Abaikan tembakan awal: DropDown hanya memanggil saat user memilih. Aman dari loop.
    return d
}

fun fieldLabel(ctx: Activity, t: String): TextView = TextView(ctx).apply {
    text = t.uppercase(); setTextColor(C.MUT); textSize = T.CAP; typeface = Typeface.DEFAULT_BOLD
    setPadding(0, dp(this, 10), 0, dp(this, 4))
}

fun kpiGrid(v: LinearLayout, items: List<Triple<String, String, Boolean?>>): GridLayout {
    val g = GridLayout(v.context).apply { columnCount = 2 }
    for ((k, val_, good) in items) {
        val cell = TerminalCard(v.context).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                width = 0
                setMargins(dp(v.context, 3), dp(v.context, 3), dp(v.context, 3), dp(v.context, 3))
            }
        }
        cell.removeAllViews()
        cell.setPadding(dp(v.context, 10), dp(v.context, 8), dp(v.context, 10), dp(v.context, 8))
        cell.addView(TextView(v.context).apply { text = k.uppercase(); setTextColor(C.DIM); textSize = 9f; typeface = Typeface.DEFAULT_BOLD })
        cell.addView(TextView(v.context).apply {
            text = val_; textSize = 17f; typeface = Typeface.MONOSPACE
            setTextColor(when (good) { true -> C.GREEN; false -> C.RED; null -> C.TXT })
        })
        g.addView(cell)
    }
    v.addView(g)
    return g
}

fun table(v: LinearLayout, headers: List<String>, rows: List<List<String>>, weights: List<Float>? = null): LinearLayout {
    val t = LinearLayout(v.context).apply { orientation = LinearLayout.VERTICAL }
    fun mkRow(cells: List<String>, header: Boolean, stripe: Boolean): LinearLayout {
        val r = LinearLayout(v.context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(this, 4), dp(this, 9), dp(this, 4), dp(this, 9))
            if (stripe) setBackgroundColor(0x0DFFFFFF)
        }
        cells.forEachIndexed { i, c ->
            val w = weights?.getOrElse(i) { 1f } ?: 1f
            r.addView(TextView(v.context).apply {
                text = c; textSize = if (header) T.CAP else T.SMALL
                typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                setTextColor(if (header) C.DIM else C.TXT)
                gravity = if (i == 0) Gravity.START else Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, -2, w)
            })
        }
        return r
    }
    t.addView(mkRow(headers, true, false))
    t.addView(View(v.context).apply { layoutParams = LinearLayout.LayoutParams(-1, 1); setBackgroundColor(C.LINE) })
    if (rows.isEmpty()) t.addView(TextView(v.context).apply { text = "Kosong."; setTextColor(C.DIM); textSize = T.SMALL; gravity = Gravity.CENTER; setPadding(0, dp(this, 12), 0, dp(this, 12)) })
    rows.forEachIndexed { i, r -> t.addView(mkRow(r, false, i % 2 == 1)) }
    v.addView(t)
    return t
}

fun section(parent: LinearLayout, key: String, titleT: String, defaultOpen: Boolean, meta: () -> String = { "" }, body: (LinearLayout) -> Unit): LinearLayout {
    val ctx = parent.context as Activity
    val sec = TerminalCard(ctx)
    val head = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        isClickable = true; isFocusable = true
    }
    val tt = TextView(ctx).apply { textSize = T.HEAD; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.TXT); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
    val mm = TextView(ctx).apply { textSize = T.CAP; typeface = Typeface.MONOSPACE; setTextColor(C.DIM) }
    val ch = TextView(ctx).apply { textSize = 16f; setTextColor(C.GREEN); setPadding(dp(ctx, 8), 0, 0, 0) }
    head.addView(tt); head.addView(mm); head.addView(ch)
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun paint() {
        val open = App.secOpen(key, defaultOpen)
        tt.text = titleT
        mm.text = meta()
        ch.text = if (open) "˄" else "›"
        box.visibility = if (open) View.VISIBLE else View.GONE
    }
    head.setOnClickListener { App.setSecOpen(key, !App.secOpen(key, defaultOpen)); paint() }
    sec.addView(head)
    sec.addView(box)
    body(box)
    paint()
    parent.addView(sec)
    return sec
}

fun confirm(ctx: Activity, titleT: String, msg: String, onYes: () -> Unit) {
    AlertDialog.Builder(ctx).setTitle(titleT).setMessage(msg)
        .setPositiveButton("Ya") { _, _ -> try { onYes() } catch (e: Exception) { toast(ctx, "Error: ${e.message}") } }
        .setNegativeButton("Batal", null).show()
}

// Pahlawan metrik: nilai raksasa + baris sub-metrik (hierarki Bloomberg).
fun metricHero(v: LinearLayout, label: String, value: String, good: Boolean?, subs: List<Pair<String, String>>): LinearLayout {
    val ctx = v.context
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 4), 0, dp(ctx, 4)) }
    box.addView(TextView(ctx).apply { text = label.uppercase(); setTextColor(C.DIM); textSize = T.CAP; typeface = Typeface.DEFAULT_BOLD })
    box.addView(TextView(ctx).apply {
        text = value; textSize = T.DISPLAY; typeface = Typeface.MONOSPACE
        setTextColor(when (good) { true -> C.GREEN; false -> C.RED; null -> C.TXT })
    })
    if (subs.isNotEmpty()) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, val_) in subs) {
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            c.addView(TextView(ctx).apply { text = k.uppercase(); setTextColor(C.DIM); textSize = 9f; typeface = Typeface.DEFAULT_BOLD })
            c.addView(TextView(ctx).apply { text = val_; setTextColor(C.TXT); textSize = T.SMALL; typeface = Typeface.MONOSPACE })
            r.addView(c)
        }
        box.addView(r)
    }
    v.addView(box)
    return box
}
