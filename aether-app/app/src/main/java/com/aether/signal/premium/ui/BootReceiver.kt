package com.aether.signal.premium.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Mulai ulang pemantauan setelah reboot/update HANYA bila pengguna mengaktifkan
// Selalu Siaga (pref always_on). Jujur soal batas: setelah Force Stop,
// Android TIDAK mengirim BOOT_COMPLETED sebelum pengguna membuka aplikasi manual.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            App.init(ctx)
            NotifBus.init(ctx)
            if (MonitorService.isAlwaysOn(ctx)) {
                // Validasi di dalam start(); gagal -> status, bukan crash.
                MonitorService.start(ctx)
            }
        } catch (e: Exception) { /* boot tak boleh crash */ }
    }
}
