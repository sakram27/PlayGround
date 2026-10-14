#!/usr/bin/env bash
# tools-harness/run-jvm.sh — menjalankan SELURUH suite JVM Aether Signal
# secara deterministik dengan SATU set sumber yang terdokumentasi.
#
# Taksonomi pengujian (jujur, jangan dicampur):
#   - JVM (script ini): ForensicFixTest, GuardTest, CapTest, ProofTest,
#     F01ImpactTest, ParamMatrixTest. Murni Kotlin/JVM, tanpa AndroidX,
#     tanpa R generated, tanpa jaringan (kecuali ScannerLiveTest bila
#     dijalankan eksplisit — lihat bawah).
#   - Belum via JVM: AuditTest, EngineLifecycleTest — butuh graph Android
#     penuh (R generated + AndroidX AAR: lifecycle/material/charts).
#     Dijalankan via `gradle :app:assembleDebug` (kompilasi) dan/atau
#     instrumentation test di perangkat. JANGAN diklaim lulus dari analisis.
#   - Perangkat: notifikasi-shade, Doze/OEM, semalaman, reboot, scanner-200
#     live. Hanya di HP/emulator; tidak ada di sini.
#
# Penggunaan: ./tools-harness/run-jvm.sh [--live]
#   --live: + ScannerLiveTest (HTTP nyata ke endpoint publik, read-only).
set -euo pipefail
cd "$(dirname "$0")/.."

AJAR=/usr/local/lib/android/sdk/platforms/android-36/android.jar
JSON=/tmp/json.jar
if [ ! -f "$JSON" ]; then
  echo "Mengunduh org.json (satu-satunya dependensi eksternal JVM)..."
  curl -sL --max-time 60 -o "$JSON" \
    https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
fi
STDLIB=$(find /usr/share/kotlinc /usr/local -name "kotlin-stdlib.jar" 2>/dev/null | head -1)
OUT=/tmp/aether-jvm
rm -rf "$OUT" && mkdir -p "$OUT"

U="aether-app/app/src/main/java/com/aether/signal/premium/ui"
# Himpunan sumber JVM: file ui yang bebas AndroidX/R (terverifikasi bebas
# `import android|androidx` kecuali android.jar murni: Context/SharedPreferences).
# AppState (Context/SharedPreferences dari android.jar) ikut agar Guard/Cap
# dapat memakai App.fmt/fmtPx/isDateRangeValid.
# Lihat: tools-harness/README.md untuk daftar + alasan tiap file.
# shellcheck disable=SC2086
kotlinc \
  aether-app/app/src/main/java/com/aether/signal/premium/engine/*.kt \
  aether-app/app/src/main/java/com/aether/signal/premium/data/*.kt \
  aether-app/app/src/main/java/com/aether/signal/premium/ai/*.kt \
  $U/PureLogic.kt $U/AppState.kt $U/EngineStartGuard.kt $U/SignalTrace.kt \
  $U/SignalDetailLogic.kt $U/RichSignal.kt $U/EngineDiag.kt $U/TrailingLogic.kt \
  $U/JournalLogic.kt $U/AblationRegime.kt $U/DriftLogic.kt $U/BacktestExplain.kt \
  $U/NotifyLogic.kt \
  tools-harness/ForensicFixTest.kt tools-harness/GuardTest.kt \
  tools-harness/CapTest.kt tools-harness/ProofTest.kt \
  tools-harness/F01ImpactTest.kt tools-harness/ParamMatrixTest.kt \
  -cp "$JSON:$AJAR" -d "$OUT"

CP="$OUT:$JSON:$STDLIB"
PASS=0
for T in ForensicFixTestKt GuardTestKt CapTestKt ProofTestKt F01ImpactTestKt ParamMatrixTestKt; do
  echo "== $T =="
  if java -cp "$CP" "$T" 2>&1 | tail -2; then PASS=$((PASS + 1)); fi
done
echo "SUITES GREEN: $PASS/6"
[ "$PASS" = "6" ]

if [ "${1:-}" = "--live" ]; then
  echo "== ScannerLiveTestKt (HTTP nyata, read-only) =="
  kotlinc \
    aether-app/app/src/main/java/com/aether/signal/premium/engine/*.kt \
    aether-app/app/src/main/java/com/aether/signal/premium/data/*.kt \
    tools-harness/ScannerLiveTest.kt \
    -cp "$JSON" -d "$OUT-live" 2>&1 | grep "error:" | head -5 || true
  java -cp "$OUT-live:$JSON:$STDLIB" ScannerLiveTestKt
fi
