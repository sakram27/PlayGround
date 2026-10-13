# Rilis V37 — Aether Signal (7.13 / versionCode 37)

Dokumen ini adalah catatan rilis, BUKAN klaim siap produksi.
Harness lulus ≠ teruji di HP nyata (lihat §5).

## 1. Identitas artefak (terverifikasi dari file aktual)

- Berkas: `aether-app/AetherV37-debug.apk`
- Package: `com.aether.signal.premium`
- VersionName: **7.13** — VersionCode: **37** (`aapt2 dump badging`)
- Ukuran: **6.865.590 byte** (`du -b`, cocok dengan Content-Length tautan permanen)
- SHA-256 (dihitung ulang): `06eb44c9e3507b2a7eab188b59a94ef658757b48c1576e8d7e90d605329b9d8e`
- Penandatangan: `CN=Aether Debug V35, O=Aether, C=US`
- Sidik SHA-256 sertifikat: `2b0fbdf757e4d8914f3f4ef55d388e05716f92ac5ffeb616f83b282e174b2c45`
- Algoritma: SHA256withRSA, RSA 2048-bit, X.509 v3 self-signed
- Serial: `ffbbae65bf6fe9b3` — berlaku 2026-10-12 s.d. 2056-10-04
- Skema tanda tangan: v2+v3 OK (`apksigner verify` lolos)

Perintah verifikasi ulang (di root repo):

    sha256sum aether-app/AetherV37-debug.apk
    /usr/local/lib/android/sdk/build-tools/36.1.0/aapt2 dump badging aether-app/AetherV37-debug.apk | head -n 1
    /usr/local/lib/android/sdk/build-tools/36.1.0/apksigner verify --print-certs aether-app/AetherV37-debug.apk
    keytool -printcert -jarfile aether-app/AetherV37-debug.apk

## 2. Kompatibilitas pembaruan (berdasar sertifikat, bukan nama versi)

- V35, V36, V37 memakai sertifikat **identik** (`2b0fbdf7…`) → update langsung V35→V36→V37 bisa.
- Dari V34 ke bawah (signer berbeda) → Android menolak update
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Prosedur: Ekspor JSON di aplikasi lama
  → uninstall → instal V37 → Impor JSON.

## 3. Perubahan utama V37 (layout Backtest, tanpa ubah mesin)

- Toggle Single Pair / Multi Pair (`segMode`) pindah ke kartu **DATA & PASANGAN**
  (tepat di atas `singleBox`/`multiBox`); tidak ada duplikat di BACKTEST CONSOLE.
  ID, listener, dan perilaku tidak berubah.
- Perampingan tanpa buang fungsi: `comboList` 220dp→160dp,
  `filterList` 240dp→180dp (tetap scroll internal), header Trailing 10dp→8dp.
- Chart equity tetap 220dp; urutan kartu tetap (MONITOR < CONSOLE < EQUITY < HASIL).
- Mesin backtest/strategi/filter/Hyperopt/metrik: **tidak diubah**.

## 4. Status pengujian (dipisah jujur)

- **Otomatis (dilaporkan lulus pada sesi build V37, tidak diulang pada sesi audit ini):**
  harness JVM **975 pass / 0 fail**, termasuk 32 tes `v37()` (posisi toggle,
  perampingan, kelengkapan kartu, scroll, urutan kartu).
- **Struktur sumber + XML (terverifikasi ulang di sesi ini):** segMode tepat 1×
  dan berada di antara DATA & PASANGAN dan BACKTEST CONSOLE; tidak ada
  ScrollView bersarang; `apksigner`/`aapt2` lolos (lihat §1).
- **BELUM dilakukan di perangkat Android nyata:** ketuk tombol, scroll layar,
  notifikasi, izin Android 13+, Foreground Service/Doze, fetch provider live.
  Lihat panduan validasi manual terpisah.

## 5. Batasan yang diketahui

- Cache disk/arsip berlabel sumber (`memory`/`disk`/tanggal) — baca label sebelum
  menganggap data live.
- Hyperopt hanya grid SL×TP×risiko; hasil basi diperingatkan bila basis berubah.
- Entry live memakai harga saat ini; backtest memakai open candle berikut
  (disengaja, dinyatakan di ringkasan konfigurasi efektif).

## 6. Keystore — status dan prosedur (tanpa rahasia di dokumen ini)

- **Status:** keystore penandatangan V37 (`aether-signal/build-tools/`,
  gitignored) **tidak ada di lingkungan kerja saat ini** (hilang oleh reset
  lingkungan). Alias yang tercatat: `aetherdebug35`.
- **Konsekuensi:** versi berikutnya TIDAK boleh memakai kunci baru sambil
  mengklaim update kompatibel — kunci baru = instal bersih + Ekspor/Impor JSON.
- **Pencadangan terenkripsi (jalankan di mesin yang MEMEGANG keystore):**

      openssl enc -aes-256-cbc -pbkdf2 \
        -in aether-signal/build-tools/aether-debug.keystore \
        -out aether-debug-V35-backup.keystore.enc
      keytool -list -v -keystore aether-signal/build-tools/aether-debug.keystore \
        | grep -i "SHA-256"   # catat, bandingkan dengan §1

  Simpan file `.enc` dan frasa sandi di **dua tempat berbeda dan permanen**
  (mis. password manager + media offline). Jangan pernah commit keystore,
  kata sandi, atau file `.enc` ke repo publik / layanan sementara / log / chat.

- **Pemulihan:**

      openssl enc -d -aes-256-cbc -pbkdf2 \
        -in aether-debug-V35-backup.keystore.enc \
        -out aether-signal/build-tools/aether-debug.keystore
      # verifikasi sidik SAMA dengan §1 sebelum menandatangani apa pun

## 7. Distribusi

- **Permanen (terverifikasi HTTP 200, Content-Length 6865590):**
  `https://raw.githubusercontent.com/sakram27/PlayGround/6eb376c0444b2aab64bc8bd91a5592de0f3ae71f/aether-app/AetherV37-debug.apk`
  (terpin ke commit; alternatif: clone/pull repo lalu ambil file di
  `aether-app/AetherV37-debug.apk`).
- GitHub Release tidak dibuat: token sesi ini tidak punya izin unggah
  (`push: false`), dan keystore/kredensial tidak boleh diterbitkan.
- Tautan sementara (tmpfiles, kedaluwarsa ±1 jam) tidak dipakai sebagai rilis.
- **Penyimpanan lokal di HP (bila unduhan langsung gagal):** unduh di PC →
  verifikasi SHA-256 (`certutil -hashfile` / `sha256sum`) → salin via USB →
  pasang dari File Manager → bandingkan versi 7.13 di info aplikasi.
