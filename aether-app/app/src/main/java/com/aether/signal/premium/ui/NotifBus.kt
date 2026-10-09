package com.aether.signal.premium.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aether.signal.premium.R
import com.aether.signal.premium.ai.Sig

// Notifikasi Android native (D): entry / SL / TP dari mesin live (BotEngine).
// - Hanya sinyal/pemicu NYATA (src dryrun-*); backtest historis diabaikan.
// - Dedup persisten per kejadian; tap membuka SignalsActivity.
// - Tanpa data sensitif/kredensial pada teks notifikasi.

object NotifBus {
    const val CH_ENTRY = "aether_entry"
    const val CH_SLTP = "aether_sltp"
    const val CH_SVC = "aether_service"

    private var appCtx: Context? = null
    private const val PREF = "aether_notif"

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    private fun prefs(): SharedPreferences =
        appCtx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------- pengaturan (persist, D4) ----------
    var entryOn: Boolean
        get() = prefs().getBoolean("entry", true)
        set(v) = prefs().edit().putBoolean("entry", v).apply()
    var slOn: Boolean
        get() = prefs().getBoolean("sl", true)
        set(v) = prefs().edit().putBoolean("sl", v).apply()
    var tpOn: Boolean
        get() = prefs().getBoolean("tp", true)
        set(v) = prefs().edit().putBoolean("tp", v).apply()
    var soundOn: Boolean
        get() = prefs().getBoolean("sound", true)
        set(v) {
            prefs().edit().putBoolean("sound", v).apply()
            appCtx?.let { ensureChannels(it) }
        }
    var vibrateOn: Boolean
        get() = prefs().getBoolean("vibrate", true)
        set(v) {
            prefs().edit().putBoolean("vibrate", v).apply()
            appCtx?.let { ensureChannels(it) }
        }

    fun systemEnabled(): Boolean {
        val c = appCtx ?: return false
        return try {
            NotificationManagerCompat.from(c).areNotificationsEnabled()
        } catch (e: Exception) { false }
    }

    fun needsRuntimePermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            appCtx?.let {
                ContextCompat.checkSelfPermission(it, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            } != false

    // ---------- channel (dibuat ulang saat suara/getar berubah) ----------
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = if (soundOn) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Hapus dulu agar perubahan suara/getar benar-benar berlaku.
        try { nm.deleteNotificationChannel(CH_ENTRY); nm.deleteNotificationChannel(CH_SLTP) } catch (e: Exception) { /* abaikan */ }
        val entry = NotificationChannel(CH_ENTRY, "Sinyal entry", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sinyal entry baru dari mesin"
            enableVibration(vibrateOn)
            setSound(sound, if (sound != null) attrs else null)
        }
        val sltp = NotificationChannel(CH_SLTP, "Stop loss & Take profit", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Stop loss / take profit tercapai pada posisi terpantau"
            enableVibration(vibrateOn)
            setSound(sound, if (sound != null) attrs else null)
        }
        val svc = NotificationChannel(CH_SVC, "Selalu Siaga", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Status pemantauan latar belakang"
            setSound(null, null)
            enableVibration(false)
        }
        try {
            nm.createNotificationChannel(entry)
            nm.createNotificationChannel(sltp)
            nm.createNotificationChannel(svc)
        } catch (e: Exception) { /* abaikan */ }
    }

    // ---------- dedup persisten (D: cegah ganda, lintas restart) ----------
    private fun seenIds(): MutableSet<String> =
        HashSet(prefs().getStringSet("seen", emptySet()) ?: emptySet())

    /** @return true bila kejadian BARU (belum pernah diberitahukan). */
    fun markSeen(key: String): Boolean {
        val s = seenIds()
        val fresh = dedupAdd(s, key)
        if (fresh) prefs().edit().putStringSet("seen", s).apply()
        return fresh
    }

    // Dedup memakai top-level dedupAdd() dari PureLogic.kt (teruji JVM).

    fun tapIntent(): PendingIntent? {
        val c = appCtx ?: return null
        val i = Intent(c, SignalsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return try {
            PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } catch (e: Exception) { null }
    }

    private fun post(channel: String, id: Int, title: String, text: String) {
        val c = appCtx ?: return
        if (!systemEnabled()) return
        ensureChannels(c)
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        tapIntent()?.let { b.setContentIntent(it) }
        try {
            NotificationManagerCompat.from(c).notify(id, b.build())
        } catch (e: SecurityException) { /* izin dicabut saat posting */ }
    }

    /** Dipanggil BotEngine tepat setelah pushSignal live. Backtest ("backtest") diabaikan. */
    fun onSignal(s: Sig) {
        when {
            s.src == "dryrun-entry" -> {
                if (!entryOn) return
                val key = "e|${s.pair}|${s.tf}|${s.t}|${s.dir}"
                if (!markSeen(key)) return
                post(CH_ENTRY, key.hashCode(),
                    "Entry ${s.dir} ${s.pair}",
                    "${s.tf} @ ${App.fmt(s.price, 4)} · SL ${App.fmt(s.sl, 4)} · TP ${App.fmt(s.tp, 4)} · ${App.fmtDate(s.t)}")
            }
            s.src == "dryrun-TP" -> {
                if (!tpOn) return
                val key = "x|${s.pair}|${s.t}|${s.dir}|TP"
                if (!markSeen(key)) return
                post(CH_SLTP, key.hashCode(),
                    "Take profit tercapai · ${s.dir} ${s.pair}",
                    "${s.tf} @ ${App.fmt(s.price, 4)} · ${App.fmtDate(s.t)}")
            }
            s.src == "dryrun-SL" -> {
                if (!slOn) return
                val key = "x|${s.pair}|${s.t}|${s.dir}|SL"
                if (!markSeen(key)) return
                post(CH_SLTP, key.hashCode(),
                    "Stop loss tercapai · ${s.dir} ${s.pair}",
                    "${s.tf} @ ${App.fmt(s.price, 4)} · ${App.fmtDate(s.t)}")
            }
            else -> { /* backtest/sumber lain: bukan kejadian live */ }
        }
    }
}
