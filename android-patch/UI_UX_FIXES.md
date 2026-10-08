# UI/UX + Stabilitas — patch siap-pasang (tanpa ubah arah tujuan aplikasi)

Aplikasi tetap: bot sinyal ala freqtrade (Dashboard, Market, Sinyal, Backtest,
Strategy Lab, Hyperopt/AI, Whitelist, Monitoring). Yang diperbaiki hanya
bug & UX, bukan konsep.

## 1. BacktestFragmentFull / StrategyLabActivity (wajib)

**Bug:** `runSingle` / `runAllPairs` / `runOptimization` memanggil
`provider.getHistoricalCandles()` + `BacktestEngine.run()` + render ribuan row
dalam satu coroutine tanpa stage + cancel yang rapi → ANR, progress macet di 0%,
error hanya toast "tidak ada trade" tanpa sebab.

**Fix:**
- Jalankan di `Dispatchers.IO` dengan `ensureActive()` tiap pair/bar-batch;
  render + chart di `Dispatchers.Main`. Gunakan `BacktestRunner` yang sudah ada
  (`startSingle`, `cancel`, `subscribe`, `Snapshot.stage/progress`) — JANGAN
  bikin thread sendiri.
- Tampilkan stage jujur: `fetch → warmup → simulate → metrics → render`
  via `snapshot.stage` + `progress`. Sudah ada kolomnya — tinggal diisi
  (v3.22 banyak mengisi `stage=""`).
- Validasi sebelum run (gagal cepat, pesan spesifik):
  pair kosong → "Pilih pair dulu"; timeframe kosong → "Timeframe belum dipilih";
  provider OFF semua → "Tidak ada provider ON"; candle < warmup+10 →
  tampilkan jumlah candle aktual ("dapat 73, butuh >50 warmup").
- Multi-pair: isolate failure per pair (satu pair gagal ≠ semua gagal),
  kumpulkan `PairResult(pair, ok, result|error)`; tabel per-pair tetap tampil
  untuk yang sukses + kolom error untuk yang gagal.
- Cap render trade rows (mis. 300 pertama + "… +N lagi") agar tidak OOM saat
  5000 candle menghasilkan ratusan trade.

## 2. LwChartHelper / ChartBridge (wajib — kompatibel dgn lw_chart.fixed.html)

**Bug:** `onLayoutInfo(y: Double)` menerima `y*dpr` (salah skala 2-3x di
layar hdpi) sehingga timer native melayang jauh dari label harga.

**Fix Kotlin:**
```kotlin
@JavascriptInterface
fun onLayoutInfo(arg: String) {
  // fixed.html mengirim JSON {y, dpr, h} ATAU 2-arg (yCss, dpr). Dukung keduanya + legacy 1-arg.
  runOnUiThread { positionTimerNative(arg) }
}
@JavascriptInterface
fun onLayoutInfo(yCss: Double, dpr: Double) { runOnUiThread { positionTimerNative(yCss) } }
```
- Jangan kalikan/divide manual di luar: `priceToCoordinate` = CSS px.
  Posisikan TextView timer = `webView.top + yCss - timer.height - 4`.
- `renderChart(payload)`: sanitasi di Kotlin juga (candle invalid dibuang,
  marker tanpa timestamp valid dibuang) sebelum `evaluateJavascript`, agar
  `setData([])` tak pernah dipanggil dgn data campur aduk.
- `setLoading(true)` sebelum fetch provider; `setLoading(false)` + `errorText`
  jujur saat provider gagal (jangan "NO MARKET DATA" generik).

## 3. TvWidgetHelper (wajib — keamanan)

**Bug:** `tv_widget.html` diisi via `replace("__SYMBOL__", symbol)` mentah →
injection `"</script>` bila custom pair aneh.

**Fix:**
```kotlin
fun sanitizeSymbol(s: String) =
  s.uppercase().take(32).filter { it.isLetterOrDigit() || it in ":._/-" }
    .takeIf { it.contains("/") || it.contains(":") } ?: "BINANCE:BTCUSDT"
fun sanitizeInterval(tf: String) =
  if (tf in setOf("1","3","5","15","30","60","120","240","D","W","M")) tf else "15"
```

## 4. Pasar/Whitelist/Dashboard (UX, tanpa ubah alur)

- Whitelist refresh: tombol Refresh disable + spinner + status
  "Whitelist diperbarui (N pair)" / error spesifik provider; JANGAN toast generik.
- MarketsFragment: cache `supportedTimeframes` per provider; union timeframes
  hanya dari provider ON (sudah benar di `ProviderPairSource` — pertahankan).
- Dashboard renderSignalsAsync: sudah benar pakai async — tambahkan timeout
  15 dtk + empty-state "Belum ada sinyal — coba ubah strategi/timeframe".

## 5. MonitoringService (stabilitas)

- Bungkus `runMonitoringCycle` per-pair dgn try/catch + `lastStageOf()` untuk
  diag (sudah ada di BacktestRunner — pakai pola sama); satu pair gagal
  tidak membatalkan seluruh siklus.

## Checklist verifikasi

- [ ] Backtest 100 candle + warmup 50 → ada trades (bukan 0 diam-diam).
- [ ] BUY di tren naik + SL 2% / TP 5% → mayoritas WIN, bukan LOSS massal.
- [ ] Fee tampil = 2× notional × fee% (cek 1 trade).
- [ ] Timer chart menempel di bawah label harga di hdpi & mdpi.
- [ ] Single-finger drag di trading_chart menggeser viewport.
- [ ] Custom pair `BTC/USDT";<script>` → fallback `BINANCE:BTCUSDT`, tidak crash.
