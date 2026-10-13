package com.aether.signal.premium.ui

// TEST-ONLY (tools-harness, tak pernah masuk source set aplikasi): pengganti
// MonitorService (Service Android, tak dapat hidup di JVM) agar BotEngine
// dapat dikompilasi/diuji. BENTUK BERBEDA dari aslinya (object vs class +
// companion) — hanya menyediakan `running` yang dibaca BotEngine.start().
// Jalur service sungguhan (onStartCommand/scheduleNext/onDestroy) TIDAK
// tercakup uji ini; diaudit baca-kode (lihat laporan).
object MonitorService {
    @Volatile var running: Boolean = false
}
