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
import java.util.Calendar

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

    // ---------- jam tenang (FITUR 4, persist) ----------
    var quietEnabled: Boolean
        get() = prefs().getBoolean("qh_enabled", false)
        set(v) = prefs().edit().putBoolean("qh_enabled", v).apply()
    var quietStartMin: Int
        get() = prefs().getInt("qh_start", 22 * 60)
        set(v) = prefs().edit().putInt("qh_start", v.coerceIn(0, 1439)).apply()
    var quietEndMin: Int
        get() = prefs().getInt("qh_end", 7 * 60)
        set(v) = prefs().edit().putInt("qh_end", v.coerceIn(0, MINUTES_PER_DAY)).apply()
    var quietDays: Set<Int>
        get() = parseQuietDays(prefs().getString("qh_days", "1,2,3,4,5,6,7"))
        set(v) = prefs().edit().putString("qh_days", encodeQuietDays(v)).apply()

    fun quietHours(): QuietHours = QuietHours(quietEnabled, quietStartMin, quietEndMin, quietDays)

    /** true bila saat ini notifikasi Aether harus ditahan (jam tenang). */
    fun quietNow(): Boolean {
        val c = appCtx ?: return false
        return try {
            val cal = Calendar.getInstance()
            val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            quietActive(quietHours(), mins, cal.get(Calendar.DAY_OF_WEEK))
        } catch (e: Exception) { false }
    }

    // ---------- riwayat notifikasi nyata (FITUR 3, persist) ----------
    private var histCache: MutableList<NotifRec>? = null

    fun history(): MutableList<NotifRec> {
        var h = histCache
        if (h == null) {
            h = parseNotifHistory(prefs().getString("history", null))
            histCache = h
        }
        return h
    }

    private fun persistHistory() {
        try { prefs().edit().putString("history", encodeNotifHistory(history())).apply() } catch (e: Exception) { }
    }

    /** Hapus riwayat tampilan. TIDAK menyentuh status deduplikasi aktif (dipisah). */
    fun clearHistory() {
        histCache = ArrayList()
        prefs().edit().remove("history").apply()
    }

    /** Reset deduplikasi (agar kejadian lama boleh diberitahukan lagi). Terpisah dari riwayat. */
    fun resetDedup() {
        prefs().edit().remove("seen").apply()
    }

    // ---------- digest per jam (V16 F8, default MATI) ----------
    // Hanya sinyal ENTRY yang digabung; SL/TP tetap langsung (keluar posisi kritis).
    var digestOn: Boolean
        get() = prefs().getBoolean("digest_on", false)
        set(v) = prefs().edit().putBoolean("digest_on", v).apply()

    private fun digestBucket(): DigestBucket? {
        val k = prefs().getString("digest_key", null) ?: return null
        val c = prefs().getInt("digest_count", 0)
        if (c <= 0) return null
        val lines = (prefs().getString("digest_lines", "") ?: "").split("\n").filter { it.isNotEmpty() }
        val label = prefs().getString("digest_label", "") ?: ""
        return DigestBucket(k, label, c, lines)
    }

    private fun saveDigestBucket(b: DigestBucket) {
        prefs().edit().putString("digest_key", b.key).putInt("digest_count", b.count)
            .putString("digest_lines", b.lines.joinToString("\n"))
            .putString("digest_label", b.hourLabel).apply()
    }

    private fun currentBucketKey(): Pair<String, String> {
        val cal = Calendar.getInstance()
        val key = digestBucketKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY))
        val label = String.format("%02d.00", cal.get(Calendar.HOUR_OF_DAY))
        return key to label
    }

    fun dedupCount(): Int = seenIds().size

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

    /**
     * Kirim satu notifikasi + catat hasilnya secara jujur pada riwayat.
     * Urutan gerbang: jam tenang → izin sistem → posting. Setiap gerbang mencatat
     * status apa adanya; "DIKIRIM" berarti perintah tampil diterima Android tanpa error.
     */
    private fun strategyNames(): Map<String, String> = try {
        com.aether.signal.premium.engine.strategyList().associate { it.id to it.name }
    } catch (e: Exception) { emptyMap() }
    private fun dispatch(kind: String, channel: String, id: Int, title: String, text: String, s: Sig) {
        // V18 #1: BigText kaya dari jejak tersimpan (sumber sama dengan Bagikan).
        val big = try {
            val names = try { strategyNames() } catch (e: Exception) { emptyMap<String, String>() }
            buildRichSignal(s, names).body
        } catch (e: Exception) { text }
        val c = appCtx ?: return
        val rec = NotifRec(System.currentTimeMillis(), kind, s.pair, s.tf, s.dir, s.price, "DIKIRIM")
        if (quietNow()) {
            addNotifRec(history(), rec.copy(status = "DITAHAN")); persistHistory()
            return
        }
        if (!systemEnabled()) {
            addNotifRec(history(), rec.copy(status = "GAGAL_IZIN")); persistHistory()
            return
        }
        // V16 F8: mode digest — ENTRY digabung per jam kalender lokal (satu id
        // notifikasi per bucket + onlyAlertOnce = satu bunyi per jam). Tiap sinyal
        // tetap dicatat individual di riwayat; jam tenang di atas tetap berlaku.
        if (digestOn && kind == "entry") {
            dispatchDigest(c, s, rec)
            return
        }
        ensureChannels(c)
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        tapIntent()?.let { b.setContentIntent(it) }
        try {
            NotificationManagerCompat.from(c).notify(id, b.build())
            addNotifRec(history(), rec); persistHistory()
        } catch (e: SecurityException) {
            addNotifRec(history(), rec.copy(status = "GAGAL_IZIN")); persistHistory()
        } catch (e: Exception) {
            addNotifRec(history(), rec.copy(status = "GAGAL")); persistHistory()
        }
    }

    /**
     * V16 F8: posting ringkasan digest. Bucket & riwayat individual dicatat;
     * tidak ada pengiriman ulang (satu id per bucket, update menimpa).
     */
    private fun dispatchDigest(c: Context, s: Sig, rec: NotifRec) {
        val (key, label) = try { currentBucketKey() } catch (e: Exception) { return }
        val line = "${s.pair} ${s.dir} ${s.tf}"
        val bucket = digestAdd(digestBucket(), key, label, line)
        saveDigestBucket(bucket)
        ensureChannels(c)
        val text = digestSummary(bucket)
        val b = NotificationCompat.Builder(c, CH_ENTRY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Ringkasan sinyal sejam")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        tapIntent()?.let { b.setContentIntent(it) }
        try {
            NotificationManagerCompat.from(c).notify(key.hashCode(), b.build())
            addNotifRec(history(), rec); persistHistory()
        } catch (e: SecurityException) {
            addNotifRec(history(), rec.copy(status = "GAGAL_IZIN")); persistHistory()
        } catch (e: Exception) {
            addNotifRec(history(), rec.copy(status = "GAGAL")); persistHistory()
        }
    }

    /** Dipanggil BotEngine tepat setelah pushSignal live. Backtest ("backtest") diabaikan. */
    fun onSignal(s: Sig) {
        when {
            s.src == "dryrun-entry" -> {
                if (!entryOn) return
                val key = "e|${s.pair}|${s.tf}|${s.t}|${s.dir}"
                if (!markSeen(key)) return
                dispatch("entry", CH_ENTRY, key.hashCode(),
                    "Entry ${s.dir} ${s.pair}",
                    "${s.tf} @ ${App.fmt(s.price, 4)} · SL ${App.fmt(s.sl, 4)} · TP ${App.fmt(s.tp, 4)} · ${App.fmtDate(s.t)}", s)
            }
            s.src == "dryrun-TP" -> {
                if (!tpOn) return
                val key = "x|${s.pair}|${s.t}|${s.dir}|TP"
                if (!markSeen(key)) return
                dispatch("tp", CH_SLTP, key.hashCode(),
                    "Take profit tercapai · ${s.dir} ${s.pair}",
                    "${s.tf} @ ${App.fmt(s.price, 4)} · ${App.fmtDate(s.t)}", s)
            }
            s.src == "dryrun-SL" -> {
                if (!slOn) return
                val key = "x|${s.pair}|${s.t}|${s.dir}|SL"
                if (!markSeen(key)) return
                dispatch("sl", CH_SLTP, key.hashCode(),
                    "Stop loss tercapai · ${s.dir} ${s.pair}",
                    "${s.tf} @ ${App.fmt(s.price, 4)} · ${App.fmtDate(s.t)}", s)
            }
            else -> { /* backtest/sumber lain: bukan kejadian live */ }
        }
    }
}
