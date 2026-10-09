package com.aether.signal.premium.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aether.signal.premium.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Selalu Siaga (E): Foreground Service pemantauan nyata — BUKAN sakelar kosmetik.
// - Tiap 60 dtk: BotEngine.tick(false) memakai provider & konfigurasi pengguna.
// - Gagal provider/jaringan -> status "Menunggu koneksi", tanpa pindah provider.
// - Demo TIDAK dipakai untuk menyamarkan (hanya bila memang provider aktif).
// - Jujur soal batasan: Android/OEM dapat menghentikan service (E4).

class MonitorService : Service() {

    companion object {
        const val INTERVAL_SEC = 60L
        const val SVC_ID = 3101
        const val ACT_STOP = "com.aether.signal.premium.STOP_MONITOR"

        @Volatile var running = false
            private set
        @Volatile var lastTickMs: Long = 0
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var lastSummary: String = ""
            private set

        private const val PREF_MON = "aether_monitor"

        /** Pilihan pengguna (persist): ingin siaga — dipakai BootReceiver. */
        fun isAlwaysOn(ctx: Context): Boolean = try {
            ctx.getSharedPreferences(PREF_MON, Context.MODE_PRIVATE).getBoolean("always_on", false)
        } catch (e: Exception) { false }

        fun setAlwaysOn(ctx: Context, v: Boolean) {
            try {
                ctx.getSharedPreferences(PREF_MON, Context.MODE_PRIVATE)
                    .edit().putBoolean("always_on", v).apply()
            } catch (e: Exception) { /* abaikan */ }
        }

        /** @return null bila mulai (atau sudah jalan), pesan error bila gagal. */
        fun start(ctx: Context): String? {
            if (running) return null
            if (AppJBridge.engPairsEmpty()) return "Pilih pair engine dulu (Dashboard → Pair)."
            try {
                AppJBridge.validateEngine()
            } catch (e: Exception) {
                return "Config error: ${e.message}"
            }
            return try {
                setAlwaysOn(ctx, true)
                val i = Intent(ctx, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
                null
            } catch (e: Exception) {
                "Gagal memulai layanan: ${e.message}"
            }
        }

        fun stop(ctx: Context) {
            try { setAlwaysOn(ctx, false) } catch (e: Exception) { /* abaikan */ }
            try { ctx.stopService(Intent(ctx, MonitorService::class.java)) } catch (e: Exception) { /* abaikan */ }
        }

        fun statusText(): String = deriveMonitorState(running, lastError, lastTickMs, lastSummary)
    }

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var sched: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotifBus.init(this)
        NotifBus.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        lastError = null
        BotEngine.setExternallyDriven(true)
        App.engRunning = true
        try {
            startForeground(SVC_ID, persistent("Memulai pemantauan…"))
        } catch (e: Exception) {
            lastError = e.message
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        // Tick awal segera, lalu tiap INTERVAL_SEC.
        exec.execute { safeTick(); refresh() }
        sched?.cancel(false)
        sched = exec.scheduleAtFixedRate({ safeTick(); refresh() }, INTERVAL_SEC, INTERVAL_SEC, TimeUnit.SECONDS)
        return START_STICKY
    }

    private fun safeTick() {
        try {
            BotEngine.tick(false)
            lastTickMs = System.currentTimeMillis()
            lastError = null
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastTickMs))
            lastSummary = if (BotEngine.lastTotalPairs > 0 && BotEngine.lastOkPairs == 0)
                "Menunggu koneksi · 0/${BotEngine.lastTotalPairs} pair OK · $t"
            else
                "${BotEngine.lastOkPairs}/${BotEngine.lastTotalPairs} pair OK · ${App.signals.size} sinyal · ${BotEngine.positions.size} posisi · $t"
        } catch (e: Exception) {
            // Offline/gagal: status menunggu, scheduler tetap (E3: pemulihan otomatis).
            lastError = e.message
            lastSummary = "Menunggu koneksi · ${e.message?.take(60)}"
        }
    }

    private fun refresh() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(SVC_ID, persistent(lastSummary.ifEmpty { "Memantau…" }))
        } catch (e: Exception) { /* abaikan */ }
        try { BotEngine.onTick?.invoke() } catch (e: Exception) { /* UI mungkin mati */ }
    }

    private fun persistent(line: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, DashboardActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MonitorService::class.java).apply { action = ACT_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotifBus.CH_SVC)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Aether Signal · Selalu Siaga")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line\nKetuk untuk membuka · Hentikan via tombol."))
            .setContentIntent(open)
            .addAction(0, "Hentikan", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Sistem/OEM boleh menghentikan; START_STICKY meminta restart. Dilaporkan jujur di status.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        BotEngine.setExternallyDriven(false)
        App.engRunning = false
        try { sched?.cancel(false) } catch (e: Exception) { /* abaikan */ }
        try { exec.shutdownNow() } catch (e: Exception) { /* abaikan */ }
        super.onDestroy()
    }
}

/** Jembatan kecil agar service tak mengakses App internal secara liar. */
object AppJBridge {
    fun engPairsEmpty(): Boolean = App.engPairs.isEmpty()
    fun validateEngine() {
        App.buildParams(App.engPairs.first())
    }
}