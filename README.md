# FreqDroid — Freqtrade versi APK Android 📱

Aplikasi Android ala-[Freqtrade](https://github.com/freqtrade/freqtrade) (bot trading crypto open-source Python).
Dibuat dengan Kotlin + Jetpack Compose + Material3. Bisa jalan **standalone di HP** (mode demo paper-trading pakai data publik Binance)
atau **terhubung ke bot freqtrade asli** di VPS via REST API.

<!-- omgithub:readme:start -->
## 🚀 Build, play, and remix with OMGithub

**Created using [OMGithub.com](https://omgithub.com).**

[![OMGithub](https://img.shields.io/badge/OMGithub-Open%20project-orange?style=for-the-badge)](https://omgithub.com/sakram27/PlayGround)
[![GitHub](https://img.shields.io/badge/GitHub-Source-181717?logo=github&style=for-the-badge)](https://github.com/sakram27/PlayGround)

- 🎮 [Open the project](https://omgithub.com/sakram27/PlayGround).
- ✨ [Remix this project](https://omgithub.com/?remix=sakram27%2FPlayGround).
- 💻 [Explore the source](https://github.com/sakram27/PlayGround).
- 🛠️ [Check build runs](https://github.com/sakram27/PlayGround/actions).
- 🐛 [Report an issue](https://github.com/sakram27/PlayGround/issues).
<!-- omgithub:readme:end -->

## ✨ Fitur (mirip freqtrade)

| Freqtrade asli | FreqDroid (APK) |
|---|---|
| Dry-run / live trading | ✅ Dry-run demo di HP + Start/Stop bot server |
| Backtesting | ✅ Backtest EMA-9/21 + RSI-14 di HP, winrate, total profit, max drawdown |
| WebUI profit/status/balance | ✅ Tab Market: profit %, fiat, trades, sinyal BUY/SELL/HOLD |
| Telegram /forceexit, /status, /profit | ✅ Tab Bot: Start/Stop + daftar perintah |
| Exchange via CCXT (Binance, dll) | ✅ Harga live via Binance public API (6 pair, TF 5m–1d) |
| Strategy Python | ✅ `EmaRsiCross` bawaan (port Kotlin dari SampleStrategy) |
| API server | ✅ Klien REST `token/login, status, profit, balance, performance, start/stop` — isi URL di tab Server |

## 📲 Cara dapat APK

**Opsi 1 — Unduh dari GitHub Actions (termudah):**
1. Buka tab **Actions → Build FreqDroid APK → run terbaru**
2. Unduh artifact **FreqDroid-debug-apk** (`app-debug.apk`, ±17 MB)
3. Kirim ke HP, izinkan “Install unknown apps”, instal.

**Opsi 2 — Build sendiri:**
```bash
./gradlew :app:assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Syarat: JDK 17 + Android SDK (compileSdk 34, minSdk 26 = Android 8+).

## 🖥️ Hubungkan ke bot freqtrade asli (opsional)

1. Di VPS, aktifkan API server di `config.json`:
   ```json
   "api_server": {"enabled": true, "listen_ip_address": "0.0.0.0", "port": 8080}
   ```
2. Restart: `freqtrade trade --config config.json`
3. Di aplikasi buka tab **Server**, isi URL (`http://IP-VPS:8080`), username, password → **Simpan & Hubungkan**.
4. Kalau URL dikosongkan → otomatis **mode DEMO** (paper-trading di HP, tanpa uang asli).

> ⚠️ Disclaimer sama seperti freqtrade: software edukasi, jangan pakai uang yang tidak siap hilang. Selalu mulai dengan Dry-run.

## 🗂️ Struktur kode

```
app/src/main/java/com/freqdroid/app/
  MainActivity.kt          # Nav + Scaffold
  ui/Screens.kt            # 4 tab: Market, Trades, Bot, Server + CandleChart Canvas
  ui/AppViewModel.kt       # state, Binance fetch, backtest, freqtrade login/start/stop
  data/Models.kt           # model profit/status/trade ala freqtrade REST
  data/Apis.kt             # Retrofit: FreqtradeApi + BinanceApi
  data/Repository.kt       # DataStore settings + parse klines
  engine/Engine.kt         # Indikator EMA/RSI + PaperEngine.backtest() + signal()
.github/workflows/build-apk.yml  # CI build APK otomatis
```

## ⚠️ Keterbatasan vs freqtrade penuh

- Freqtrade penuh butuh Python + TA-Lib + exchange keys dan jalan 24/7 — tidak realistis dipindah 100% ke APK.
- FreqDroid adalah **companion + paper-trading**: strategi sederhana di HP + remote control ke bot VPS.
- Belum ada: FreqAI, Hyperopt, futures/leverage, notifikasi Telegram (masih via bot asli).
