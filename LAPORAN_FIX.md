# LAPORAN FIX — AetherSignalBot v3.22-debug → Fixed

Bahasa: Indonesia. APK yang dianalisis: `AetherSignalBot-v3.22-debug.apk` (6,4 MB,
13 dex, package `com.aether.signal`). Arah/tujuan aplikasi **tidak diubah**:
tetap bot sinyal terinspirasi freqtrade (sinyal strategi + filter + RiskEngine
SL/TP + backtest + hyperopt/AI + whitelist multi-provider + chart Lightweight
Charts/TradingView + monitoring). Yang diperbaiki hanya **kebenaran engine,
error handling, dan UX** — bukan konsep.

> Cara pakai: `aether/` = engine fixed + 11 tests (lolos). `assets-fixed/*.fixed.html`
> = ganti file di `assets/`. `android-patch/BacktestEngine.fixed.kt` = timpa file
> Kotlin asli (signature publik sama). `android-patch/UI_UX_FIXES.md` = checklist
> Kotlin/UI per layar.

## Ringkasan: apa saja yang sudah di-fix

| ID | Lokasi | Bug (bukti) | Fix | Status |
|----|--------|-------------|-----|--------|
| FIX-01 | `BacktestEngine.run` (SL/TP) | **Sisi SL/TP terbalik (kritis).** Bytecode: BUY cek `high>=SL→LOSS`, `low<=TP→WIN`; SELL cek `low<=SL→LOSS`, `high>=TP→WIN`. SL BUY ada di bawah entry sehingga `high>=SL` hampir selalu benar → semua BUY langsung LOSS palsu (dan sebaliknya). | BUY: `low<=SL→LOSS`, `high>=TP→WIN`; SELL kebalikannya. Same-bar dua-duanya kena → konservatif LOSS + flag `ambiguous`. | ✅ + 3 tests |
| FIX-02 | `BacktestEngine.run` warmup | `const/16 v4,220` — loop mulai indeks 220 walau cek minimal cuma 60. Data 60–219 candle → **0 trade diam-diam**. | `startupCandleCount` dinamis (default 50), error jujur bila kurang. | ✅ + test |
| FIX-03 | Performa loop | `subList(0,i)` + `DecisionEngine.decide()` tiap bar → O(n²), indikator dihitung ulang dari nol. Hyperopt/multi-pair jadi lambat. | Sinyal precompute/di-pass via `SignalFn`; patch Kotlin dokumentasikan pola batch + `ensureActive()`. | ✅ (desain) |
| FIX-04 | Fee & slippage | Fee 1x (`notional*fee`), slippage hanya entry. Freqtrade: fee 2 sisi. | Fee entry+exit, slippage entry+exit (arah benar per BUY/SELL). | ✅ + test |
| FIX-05 | Sizing & leverage | `risk = cap*risk%*leverage; size = risk/riskFrac` — leverage menggandakan risiko (salah), tanpa cap notional & guard bangkrut. | `risk = cap*risk%` (tanpa leverage); `notional = min(risk/riskFrac, cap*leverage)`; floor modal 0. | ✅ + test |
| FIX-06 | Expired | `if (i-entry >= 200)` hardcode. | `params.maxHoldingBars` (default 200, konfig). | ✅ + test |
| FIX-07 | Equity & MaxDD | Equity hanya `Pair(ts, cap)` saat close → MaxDD understated, tidak ada mark-to-market. | Equity per-bar + MaxDD dari equity curve. | ✅ + test |
| FIX-08 | Guard dana | Size invalid/NaN hanya sebagian di-skip (`goto skip`), bisa trade fiktif. | Skip + catat bila `notional<=0/NaN`, SL/TP tidak waras, entry invalid. | ✅ |
| FIX-09 | `parseTimeframe` | Tanpa `w`, default 15 implisit, tanpa guard. | Support `m/h/d/w` + fallback 15 aman. | ✅ + test |
| FIX-10 | Sanitasi data | Validasi non-finite ada, tapi tanpa sort/dedupe timestamp. | Sort + dedupe (terakhir menang) + buang invalid. | ✅ + test |
| FIX-11 | Metrik freqtrade | Tanpa Sharpe/Sortino/Calmar/SQN/Kelly/buy-and-hold → optimasi Hyperopt buta risiko. | `aether/metrics.py` + kolom hasil baru (default 0.0 agar kompatibel). | ✅ + test |
| CHART-01 | `lw_chart.html reportLayout` | `onLayoutInfo(y*dpr)` — `priceToCoordinate` sudah CSS px, dikali dpr → timer native melayang 2–3x di layar hdpi. | Kirim `{y, dpr, h}` JSON (kompatibel mundur 1-arg/2-arg), Kotlin konversi sendiri. | ✅ |
| CHART-02 | `lw_chart.html` | Tanpa guard bila bundle `LightweightCharts` gagal load → halaman hitam tanpa pesan. | Guard + pesan jujur. | ✅ |
| CHART-03 | `lw_chart.html` markers | Marker dgn timestamp di luar data → `setMarkers` throw → chart kosong. | Filter marker valid saja. | ✅ |
| CHART-04 | `lw_chart.html` data | Candle rusak (`h<l`, NaN, ≤0) diteruskan ke `setData` → throw. | `isValidCandle` + filter + pesan jujur. | ✅ |
| CHART-05 | `lw_chart.html` zoom | `fitContent()` tiap render → zoom user selalu ke-reset. | Fit hanya bila jumlah bar berubah. | ✅ |
| TC-01/02 | `trading_chart.html` | Touch 1 jari no-op (hanya `onMove`), tidak ada pan; `touchend` tanpa `draw()`. | Pan 1 jari + clamp + redraw; pinch guard `pinch!=0`. | ✅ |
| CH-01/02 | `chart.html` | Iframe TradingView tanpa deteksi gagal → stuck blank saat offline/diblokir. | Deteksi `load` + fallback 8 dtk + pesan + arahkan ke chart lokal. | ✅ |
| TV-01 | `tv_widget.html` + `TvWidgetHelper` | `replace(__SYMBOL__)` mentah → injeksi `</script>` via custom pair. | Whitelist symbol/interval/locale (lihat `UI_UX_FIXES.md`). | ✅ (spec+code) |
| UI-01 | `BacktestFragmentFull/StrategyLab` | Fetch+simulasi+render tanpa stage/cancel rapi → ANR, progress 0%, error generik. | IO + `BacktestRunner` stage/progress/cancel + validasi gagal-cepat + isolate per-pair + cap 300 rows. | ✅ (spec) |
| UI-02 | Whitelist/Market/Dashboard/Monitoring | Toast generik, tanpa timeout/empty-state, satu pair gagal gugurkan semua. | Status spesifik, cache timeframe, timeout 15 dtk, try/catch per-pair. | ✅ (spec) |

## Error yang ditemukan & langsung di-fix

1. **Semua BUY langsung LOSS** (FIX-01) — error logika paling fatal; gagal di tren naik sekalipun.
2. **Backtest 0 trades tanpa pesan** pada data <221 candle (FIX-02).
3. **Timer chart melayang** di HP hdpi (CHART-01).
4. **Chart sentuh tidak bisa geser** 1 jari (TC-01).
5. **Blank chart offline** tanpa fallback (CH-01/02).
6. **Fee kekecilan 50%** (dihitung 1x, seharusnya 2x) → profit terlihat lebih bagus dari aslinya (FIX-04).
7. **Risiko berlipat leverage** (FIX-05) → ukuran posisi bisa > modal × leverage tanpa cap.
8. **Crash/injeksi custom pair** (TV-01).

## Verifikasi

- `python3 -m pytest aether/test_engine.py -v` → **11 passed**.
- Setiap test dirancang **gagal pada logika buggy v3.22** dan lolos pada engine fixed
  (contoh: `test_fix02` memakai 100 candle — engine lama return 0 trades).
- File `*.fixed.html` mempertahankan palet neon, EMA glow, price-line dinamis,
  dan bridge `AetherBridge` — hanya koordinat/skala & guard yang diperbaiki.
- `BacktestEngine.fixed.kt` memiliki signature publik identik sehingga call-site
  lama (`BacktestRunner`, `StrategyLabActivity`, `BacktestFragmentFull`) tetap kompil;
  2 field baru (`startupCandleCount`, `maxHoldingBars`) + 1 flag trade
  (`ambiguousSameBar`) semuanya punya **default** (kompatibel mundur).

## Yang TIDAK diubah (sesuai permintaan)

- Arah tujuan: tetap bot sinyal freqtrade-style (bukan jadi exchange/auto-trading).
- Chart: tetap Lightweight Charts lokal + TradingView widget resmi; tidak diganti library.
- Strategi/filter/whitelist: tidak dihapus/diganti, hanya dipanggil lebih efisien & aman.
- Bahasa UI (Indonesia) & tema gelap dipertahankan.

## Keterbatasan yang jujur

- Repo ini awalnya kosong (PlayGround) — tidak ada source Android asli selain APK.
  Patch Kotlin & HTML adalah **drop-in fix berbasis bukti bytecode**, bukan rebuild
  APK penuh dari sini. Untuk menghasilkan `AetherSignalBot-v3.23-fixed.apk`,
  terapkan 3 file `.fixed` + `BacktestEngine.fixed.kt` + `UI_UX_FIXES.md` ke repo
  Android asli lalu `./gradlew assembleDebug`.
- `DecisionEngine`/`StrategyRegistry`/`RiskEngine` tidak diubah (analisis menunjukkan
  bug utama ada di konsumen `BacktestEngine`, bukan di mereka).
