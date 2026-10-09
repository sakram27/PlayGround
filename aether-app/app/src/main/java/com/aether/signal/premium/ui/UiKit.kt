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
