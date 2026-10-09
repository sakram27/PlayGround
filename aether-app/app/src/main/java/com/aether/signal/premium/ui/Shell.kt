package com.aether.signal.premium.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.aether.signal.premium.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar

// Shell Material: Toolbar + konten + BottomNavigationView 5 tab. Tanpa WebView.

abstract class BaseActivity(val tabItem: Int = R.id.nav_home) : AppCompatActivity() {
    abstract val contentLayout: Int
    protected lateinit var toolbar: MaterialToolbar
    open val showBack: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)
        setContentView(R.layout.shell)
        toolbar = findViewById(R.id.toolbar)
        if (showBack) {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            toolbar.setNavigationOnClickListener { finish() }
        }
        toolbar.inflateMenu(R.menu.topbar)
        toolbar.setOnMenuItemClickListener {
            navTo("settings")
            true
        }
        LayoutInflater.from(this).inflate(contentLayout, findViewById<FrameLayout>(R.id.content), true)
        val nav = findViewById<BottomNavigationView>(R.id.bottomnav)
        nav.selectedItemId = tabItem
        nav.setOnItemSelectedListener {
            val k = when (it.itemId) {
                R.id.nav_home -> "home"; R.id.nav_markets -> "markets"
                R.id.nav_signals -> "signals"; R.id.nav_lab -> "lab"
                else -> "settings"
            }
            val cur = when (tabItem) {
                R.id.nav_home -> "home"; R.id.nav_markets -> "markets"
                R.id.nav_signals -> "signals"; R.id.nav_lab -> "lab"
                else -> "settings"
            }
            if (k != cur) navTo(k)
            true
        }
        try { build() } catch (e: Exception) {
            snack("Gagal memuat layar: ${e.message}")
        }
    }

    abstract fun build()

    fun setBar(title: String, sub: String = "") {
        toolbar.title = title
        toolbar.subtitle = sub
    }

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
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    fun navKeep(k: String) {
        val cls = when (k) {
            "backtest" -> BacktestActivity::class.java
            "ai" -> AiActivity::class.java
            else -> LabActivity::class.java
        }
        startActivity(Intent(this, cls))
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out)
    }

    fun snack(msg: String) {
        try {
            Snackbar.make(findViewById(R.id.content), msg, Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); App.init(this) }
}
