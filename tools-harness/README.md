# tools-harness — suite pengujian Aether Signal

Jalankan: `./tools-harness/run-jvm.sh` (semua hijau) atau dengan `--live`
untuk + `ScannerLiveTest` (HTTP nyata, read-only).

## Taksonomi (jangan dicampur)

| Suite | Lingkungan | Kebutuhan |
|---|---|---|
| ForensicFixTest, GuardTest, CapTest, ProofTest, F01ImpactTest, ParamMatrixTest | JVM (`run-jvm.sh`) | `kotlinc`, `android.jar`, `org.json` |
| ScannerLiveTest | JVM + jaringan | endpoint publik Binance/Yahoo |
| AuditTest, EngineLifecycleTest | **Bukan JVM** — butuh graph Android penuh (R generated + AAR AndroidX: lifecycle/material/charts) | `gradle :app:assembleDebug` (kompilasi) dan/atau instrumentation di perangkat |
| Perangkat | HP/emulator | notifikasi-shade, Doze/OEM, semalaman, reboot, scanner-200 live |

## Himpunan sumber JVM (terverifikasi bebas AndroidX/R)

`engine/*`, `data/*`, `ai/*`, ditambah file `ui` yang hanya memakai
`android.jar` murni (`Context`/`SharedPreferences`) atau tanpa Android:

PureLogic, AppState, EngineStartGuard, SignalTrace, SignalDetailLogic,
RichSignal, EngineDiag, TrailingLogic, JournalLogic, AblationRegime,
DriftLogic, BacktestExplain, NotifyLogic.

`BotEngine`/`NotifBus`/Activity TIDAK ikut (butuh AndroidX/`R`) — diuji via
Gradle build + perangkat, bukan di sini.
