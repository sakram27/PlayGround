package com.aether.signal.premium.ui

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar

// Helper kecil lintas layar. Komponen visual memakai Material + XML.

fun dp(c: Context, n: Int): Int = (n * c.resources.displayMetrics.density).toInt()

fun snack(a: Activity, msg: String) {
    try {
        Snackbar.make(a.findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
    }
}

fun confirm(a: Activity, titleT: String, msg: String, onYes: () -> Unit) {
    AlertDialog.Builder(a).setTitle(titleT).setMessage(msg)
        .setPositiveButton("Ya") { _, _ -> try { onYes() } catch (e: Exception) { snack(a, "Error: ${e.message}") } }
        .setNegativeButton("Batal", null).show()
}

fun runBg(fn: () -> Unit) {
    Thread {
        try { fn() } catch (e: Exception) { e.printStackTrace() }
    }.start()
}

// Tombol Engine dua-warna: hijau = aksi Start (engine berhenti), merah = aksi
// Stop (engine berjalan). Latar + teks diganti eksplisit agar kontras di tema
// gelap; stroke ghost dipertahankan agar konsisten dengan desain.
fun paintEngineButton(btn: com.google.android.material.button.MaterialButton, running: Boolean) {
    try {
        if (running) {
            btn.text = "■ Stop Engine"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF87171.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFFF87171.toInt())
        } else {
            btn.text = "▶ Start Engine"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF10B981.toInt())
            btn.setTextColor(0xFF06110D.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF10B981.toInt())
        }
    } catch (e: Exception) { /* gaya opsional, jangan matikan layar */ }
}

// V27: tombol peran mengikuti fungsi aktual — Danger untuk hapus/berhenti,
// Info untuk navigasi/detail. Cermin paintEngineButton agar konsisten.
fun paintDangerButton(btn: com.google.android.material.button.MaterialButton) {
    try {
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF87171.toInt())
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFFF87171.toInt())
    } catch (e: Exception) { /* gaya opsional, jangan matikan layar */ }
}

fun paintInfoButton(btn: com.google.android.material.button.MaterialButton) {
    try {
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF3B82F6.toInt())
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF3B82F6.toInt())
    } catch (e: Exception) { /* gaya opsional, jangan matikan layar */ }
}
// V27: tombol Run Backtest mengikuti proses aktual (bukan dekorasi).
// Berjalan = cyan + nonaktif (cegah run ganda); idle = hijau gradient.
fun paintRunButtons(
    btnRun: com.google.android.material.button.MaterialButton,
    btnCancel: com.google.android.material.button.MaterialButton,
    running: Boolean
) {
    try {
        if (running) {
            // V29: yang memproses = slate redup + nonaktif (jelas bukan aksi),
            // sedangkan Batal menyala merah sebagai satu-satunya aksi aktif.
            btnRun.text = "● Berjalan…"
            btnRun.setBackgroundColor(0xFF334155.toInt())
            btnRun.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF334155.toInt())
            btnRun.setTextColor(0xFF94A3B8.toInt())
            btnRun.isEnabled = false
            btnRun.isClickable = false
            btnRun.strokeColor = android.content.res.ColorStateList.valueOf(0xFF334155.toInt())
            btnCancel.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF87171.toInt())
            btnCancel.setTextColor(0xFFFFFFFF.toInt())
            btnCancel.strokeColor = android.content.res.ColorStateList.valueOf(0xFFF87171.toInt())
        } else {
            btnRun.text = "▶  Run Backtest"
            try {
                // Kembalikan gradasi utama; tint dihapus agar tidak mengotori gradien.
                btnRun.backgroundTintList = null
                btnRun.setBackgroundResource(com.aether.signal.premium.R.drawable.grad_primary)
            } catch (e: Exception) {
                btnRun.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF10B981.toInt())
            }
            btnRun.setTextColor(0xFF06110D.toInt())
            btnRun.isEnabled = true
            btnRun.isClickable = true
            btnCancel.backgroundTintList = null
            btnCancel.setTextColor(0xFFF1F5F9.toInt())
            try {
                btnCancel.strokeColor = android.content.res.ColorStateList.valueOf(
                    btnCancel.context.getColor(com.aether.signal.premium.R.color.line))
            } catch (e: Exception) { /* abaikan */ }
        }
    } catch (e: Exception) { /* gaya opsional, jangan matikan layar */ }
}
