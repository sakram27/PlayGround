package com.aether.signal.premium.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.aether.signal.premium.R
import com.google.android.material.bottomsheet.BottomSheetDialog

// Bottom sheet detail instrumen: harga, aksi Backtest & Grafik.

class PairSheet(
    private val act: BaseActivity,
    private val sym: String, private val prov: String, private val tf: String,
    private val price: String, private val chg: Double?, private val sig: String,
    private val spark: List<Double> = emptyList(),
    private val onBacktest: () -> Unit, private val onChart: () -> Unit
) {
    fun show() {
        val dlg = BottomSheetDialog(act, R.style.SheetTheme)
        val v = LayoutInflater.from(act).inflate(R.layout.sheet_pair, null)
        v.findViewById<TextView>(R.id.sym).text = sym
        try {
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(PairIcons.iconFor(act, sym))
        } catch (e: Exception) { /* ikon opsional */ }
        v.findViewById<SparkView>(R.id.spark).setData(spark)
        v.findViewById<TextView>(R.id.meta).text =
            "Harga $price · ${if (chg != null && chg.isFinite()) App.fmt(chg) + "%" else "—"} · sinyal $sig · $prov $tf"
        v.findViewById<View>(R.id.btnBacktest).setOnClickListener { dlg.dismiss(); onBacktest() }
        v.findViewById<View>(R.id.btnChart).setOnClickListener { dlg.dismiss(); onChart() }
        dlg.setContentView(v)
        dlg.show()
    }
}
