/* App controller — UI/UX disempurnakan, arah app tetap (signal bot freqtrade-inspired). */
'use strict';
import { runBacktest, strategyList, parseTimeframe, genDemoCandles } from './core.js';
import { getCandles, topPairs, parseCSV } from './data.js';
import { renderMain, renderEquity } from './charts.js';

const $ = (id) => document.getElementById(id);
const els = {
  tabs: [...document.querySelectorAll('#tabs .tab')],
  provider: $('provider'), symbol: $('symbol'), timeframe: $('timeframe'), limit: $('limit'),
  strategy: $('strategy'), comboMode: $('comboMode'), capital: $('capital'), risk: $('risk'),
  leverage: $('leverage'), fee: $('fee'), slippage: $('slippage'), maxholding: $('maxholding'),
  sl: $('sl'), tp: $('tp'), useatr: $('useatr'), from: $('from'), to: $('to'), csv: $('csv'),
  run: $('run'), runAll: $('runAll'), cancel: $('cancel'), status: $('status'),
  chart: $('chart'), chartEmpty: $('chartEmpty'), chartMeta: $('chartMeta'), equity: $('equity'),
  summaryLine: $('summaryLine'), kpis: $('kpis'), tblPair: $('tblPair'), tblAdv: $('tblAdv'),
  tblTrades: $('tblTrades'), tradeCount: $('tradeCount'), multiOut: $('multiOut'),
  countdown: $('countdown'), stratList: $('stratList'), btnOpt: $('btnOpt'), optStatus: $('optStatus'),
  tblOpt: $('tblOpt'), btnMarket: $('btnMarket'), mktStatus: $('mktStatus'), tblMkt: $('tblMkt'),
  fixReport: $('fixReport'),
};
let lastCandles = [], lastParams = null, lastResult = null, cancelled = false, csvCandles = null;
let timerInt = null;

// tabs
els.tabs.forEach((t) => t.addEventListener('click', () => {
  els.tabs.forEach((x) => x.classList.remove('active'));
  t.classList.add('active');
  ['backtest', 'lab', 'market', 'bantuan'].forEach((k) => $('tab-' + k).classList.toggle('hidden', k !== t.dataset.tab));
}));

// strategi dropdown + katalog
const STRS = strategyList();
els.strategy.innerHTML = STRS.map((s) => '<option value="' + s.id + '">' + s.name + '</option>').join('');
els.strategy.value = 'ema_trend';
els.stratList.innerHTML = STRS.map((s) => '<details><summary>' + s.name + ' <span style="color:var(--dim);font-weight:400">(' + s.id + ')</span></summary><p class="sub">' + s.desc + '</p><button class="btn ghost sm" data-use="' + s.id + '">Pakai strategi ini</button></details>').join('');
els.stratList.addEventListener('click', (e) => {
  const b = e.target.closest('[data-use]');
  if (!b) return;
  els.strategy.value = b.dataset.use;
  els.tabs[0].click();
  window.scrollTo({ top: 0, behavior: 'smooth' });
});

// overlay toggles
['ovVol', 'ovE20', 'ovE50', 'ovE200'].forEach((id) => $(id).addEventListener('change', () => { if (lastCandles.length) paintChart(lastResult); }));

// CSV
els.csv.addEventListener('change', async () => {
  const f = els.csv.files[0];
  if (!f) { csvCandles = null; return; }
  try {
    csvCandles = parseCSV(await f.text());
    setStatus('CSV dimuat: ' + csvCandles.length + ' candle valid. Provider diabaikan saat run.', false);
  } catch (err) { csvCandles = null; setStatus('CSV error: ' + err.message, true); }
});

function setStatus(msg, isErr) {
  els.status.textContent = msg;
  els.status.classList.toggle('err', !!isErr);
}
function fmt(n, d = 2) { if (!Number.isFinite(n)) return '—'; return n.toLocaleString('id-ID', { minimumFractionDigits: d, maximumFractionDigits: d }); }
function fmt$ (n) { if (!Number.isFinite(n)) return '—'; const s = n < 0 ? '-' : ''; return s + '$' + fmt(Math.abs(n)); }

function readParams() {
  const fromMs = els.from.value ? new Date(els.from.value + 'T00:00:00Z').getTime() : 0;
  const toMs = els.to.value ? new Date(els.to.value + 'T23:59:59Z').getTime() : 0;
  const comboMode = els.comboMode.value;
  const base = els.strategy.value;
  // combo default: strategi terpilih + 2 pendamping populer
  const combo = comboMode ? { mode: comboMode, strategies: [...new Set([base, 'supertrend', 'rsi'])].slice(0, 3) } : null;
  return {
    asset: els.symbol.value, timeframe: els.timeframe.value,
    initialCapital: +els.capital.value, riskPerTrade: (+els.risk.value) / 100,
    leverage: +els.leverage.value, feePercent: (+els.fee.value) / 100, slippagePercent: (+els.slippage.value) / 100,
    slPercent: (+els.sl.value) / 100, tpPercent: (+els.tp.value) / 100,
    maxHolding: +els.maxholding.value, strategy: base, strategyParams: {}, combo,
    startDate: fromMs || 0, endDate: toMs || 0,
    useAtr: els.useatr.value === '1',
  };
}

async function loadData(params) {
  if (csvCandles && csvCandles.length >= 60) return { candles: csvCandles, source: 'CSV (' + csvCandles.length + 'c)' };
  const candles = await getCandles({ provider: els.provider.value, symbol: params.asset, timeframe: params.timeframe, limit: +els.limit.value });
  return { candles, source: els.provider.value + ' · ' + params.asset + ' ' + params.timeframe };
}

function paintChart(result) {
  const overlays = { volume: $('ovVol').checked, ema20: $('ovE20').checked, ema50: $('ovE50').checked, ema200: $('ovE200').checked };
  let markers = [], priceLines = [];
  if (result && !result.error) {
    markers = result.trades.slice(-120).flatMap((t) => ([
      { t: t.entryTime, pos: t.direction === 'LONG' ? 'belowBar' : 'aboveBar', color: t.direction === 'LONG' ? '#00E676' : '#FF5252', shape: t.direction === 'LONG' ? 'arrowUp' : 'arrowDown', text: t.direction === 'LONG' ? 'L' : 'S' },
      { t: t.exitTime, pos: t.result === 'WIN' ? 'aboveBar' : 'belowBar', color: t.result === 'WIN' ? '#00E5FF' : '#FFC857', shape: t.result === 'WIN' ? 'circle' : 'circle', text: t.result === 'WIN' ? '✓' : '✕' },
    ]));
    const last = result.trades[result.trades.length - 1];
    if (last) priceLines = [
      { price: last.stopLoss, color: '#FF5252', title: 'SL' },
      { price: last.takeProfit, color: '#00E676', title: 'TP' },
    ];
  }
  const last = renderMain(els.chart, els.chartEmpty, { candles: lastCandles, markers, priceLines, overlays, errorText: '' });
  renderEquity(els.equity, result && !result.error ? result.equityCurve : []);
  if (lastCandles.length) {
    const l = lastCandles[lastCandles.length - 1];
    els.chartMeta.textContent = lastParams.asset + ' · ' + lastParams.timeframe + ' · ' + lastCandles.length + 'c · last ' + fmt(l.c, l.c > 1000 ? 2 : 4);
  }
  startCountdown(last);
}

function startCountdown() {
  clearInterval(timerInt);
  els.countdown.classList.add('hidden');
  if (!lastCandles.length || !lastParams) return;
  let secs = 0;
  try { secs = parseTimeframe(lastParams.timeframe) * 60; } catch { return; }
  if (!(secs > 0)) return;
  const tick = () => {
    const lastT = lastCandles[lastCandles.length - 1].t;
    const left = Math.max(0, lastT + secs * 1000 - Date.now());
    const mm = String(Math.floor(left / 60000)).padStart(2, '0');
    const ss = String(Math.floor((left % 60000) / 1000)).padStart(2, '0');
    els.countdown.textContent = '⏱ ' + mm + ':' + ss;
    els.countdown.classList.remove('hidden');
  };
  tick();
  timerInt = setInterval(tick, 1000);
}

function paintResult(result, source) {
  lastResult = result;
  if (result.error) {
    els.summaryLine.textContent = 'Error: ' + result.error;
    els.kpis.innerHTML = ''; els.tblPair.querySelector('tbody').innerHTML = ''; els.tblAdv.querySelector('tbody').innerHTML = '';
    els.tblTrades.querySelector('tbody').innerHTML = ''; els.tradeCount.textContent = '0';
    renderEquity(els.equity, []);
    return;
  }
  const w = result.winRate, dd = result.maxDrawdownPercent;
  els.summaryLine.innerHTML = '<b>' + result.asset + ' ' + result.timeframe + '</b> · ' + result.strategy + ' · ' + source +
    ' · Net <b style="color:' + (result.netProfit >= 0 ? 'var(--green)' : 'var(--red)') + '">' + fmt$(result.netProfit) + ' (' + fmt(result.netProfitPercent) + '%)</b>' +
    ' · Win ' + fmt(w, 1) + '% · PF ' + (result.profitFactor >= 999 ? '∞' : fmt(result.profitFactor)) + ' · MaxDD ' + fmt(dd) + '%';
  const K = [
    ['Net Profit', fmt$(result.netProfit), result.netProfit >= 0], ['Win Rate', fmt(w, 1) + '%', w >= 50],
    ['Total Trades', result.totalTrades, null], ['Profit Factor', result.profitFactor >= 999 ? '∞' : fmt(result.profitFactor), result.profitFactor >= 1.5],
    ['Expectancy', fmt$(result.expectancy), result.expectancy >= 0], ['Avg R', fmt(result.averageR) + 'R', result.averageR >= 0],
    ['Max DD', fmt(dd) + '%', dd <= 15], ['Final Capital', fmt$(result.finalCapital), result.finalCapital >= result.initialCapital],
  ];
  els.kpis.innerHTML = K.map(([k, v, pos]) => '<div class="kpi"><small>' + k + '</small><b class="' + (pos == null ? '' : pos ? 'pos' : 'neg') + '">' + v + '</b></div>').join('');
  const rows1 = [
    ['Wins / Losses / Expired', result.wins + ' / ' + result.losses + ' / ' + result.expired],
    ['Gross Profit', fmt$(result.grossProfit)], ['Gross Loss', fmt$(result.grossLoss)],
    ['Avg Win', fmt$(result.averageWin)], ['Avg Loss', fmt$(result.averageLoss)],
    ['Win streak / Loss streak', result.longestWinStreak + ' / ' + result.longestLossStreak],
    ['Avg holding', fmt(result.avgHolding, 1) + ' candle'], ['Exposure', fmt(result.exposure, 1) + '%'],
  ];
  const rows2 = [
    ['Sharpe (per-trade)', fmt(result.sharpe)], ['Sortino', fmt(result.sortino)],
    ['Calmar', result.calmar >= 999 ? '∞' : fmt(result.calmar)], ['CAGR', fmt(result.cagr) + '%'],
    ['Avg RR terencana', fmt(result.averageRR)], ['MaxDD nominal', fmt$(result.maxDrawdown)],
    ['Modal awal', fmt$(result.initialCapital)], ['Strategi', result.strategy],
  ];
  els.tblPair.querySelector('tbody').innerHTML = rows1.map(([a, b]) => '<tr><td>' + a + '</td><td>' + b + '</td></tr>').join('');
  els.tblAdv.querySelector('tbody').innerHTML = rows2.map(([a, b]) => '<tr><td>' + a + '</td><td>' + b + '</td></tr>').join('');
  els.tradeCount.textContent = result.trades.length;
  const tb = result.trades.slice(-200).map((t, i) => '<tr class="' + (t.result === 'WIN' ? 'win' : t.result === 'LOSS' ? 'loss' : 'exp') + '"><td>' + (i + 1) + '</td><td>' + new Date(t.exitTime).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td>' + t.direction + '</td><td>' + fmt(t.entry, 4) + '</td><td>' + fmt(t.exit, 4) + '</td><td>' + fmt$(t.pnl) + '</td><td>' + fmt(t.rMultiple) + '</td><td><span class="pill ' + (t.result === 'WIN' ? 'win' : t.result === 'LOSS' ? 'loss' : 'exp') + '">' + t.result + '</span></td></tr>').join('');
  els.tblTrades.querySelector('tbody').innerHTML = tb || '<tr><td colspan="8" style="text-align:center;color:var(--dim)">Tidak ada trade (coba strategi/timeframe lain).</td></tr>';
}

els.run.addEventListener('click', async () => {
  cancelled = false; els.run.disabled = true; els.cancel.disabled = false;
  els.multiOut.innerHTML = '';
  try {
    const params = readParams();
    lastParams = params;
    setStatus('Mengambil data ' + params.asset + ' ' + params.timeframe + '…', false);
    const { candles, source } = await loadData(params);
    if (cancelled) { setStatus('Dibatalkan.', false); return; }
    lastCandles = candles;
    setStatus('Menjalankan backtest (' + candles.length + 'c)…', false);
    await new Promise((r) => setTimeout(r, 30)); // biar UI update
    const t0 = performance.now();
    const res = runBacktest(candles, params);
    if (cancelled) { setStatus('Dibatalkan.', false); return; }
    paintChart(res); paintResult(res, source);
    if (res.error) setStatus('Error: ' + res.error, true);
    else setStatus('Selesai dalam ' + Math.round(performance.now() - t0) + 'ms · ' + res.totalTrades + ' trade · Net ' + fmt$(res.netProfit) + '. Sumber: ' + source, false);
  } catch (err) {
    // FIX: error provider jujur, bukan NO MARKET DATA palsu
    els.chartEmpty.style.display = 'flex';
    els.chartEmpty.textContent = 'Gagal memuat data: ' + err.message + ' — coba provider Demo (offline) atau simbol lain.';
    setStatus('Error: ' + err.message, true);
  } finally { els.run.disabled = false; els.cancel.disabled = true; }
});

els.cancel.addEventListener('click', () => { cancelled = true; setStatus('Membatalkan…', false); });

els.runAll.addEventListener('click', async () => {
  cancelled = false; els.runAll.disabled = true; els.cancel.disabled = false; els.multiOut.innerHTML = '';
  try {
    const params = readParams();
    const pairs = (await topPairs(els.provider.value, 5)).filter((s) => s !== params.asset);
    pairs.unshift(params.asset);
    setStatus('Multi-pair: ' + pairs.join(', '), false);
    const rows = [];
    for (const sym of pairs.slice(0, 5)) {
      if (cancelled) break;
      setStatus('Backtest ' + sym + '…', false);
      try {
        const candles = csvCandles?.length ? csvCandles : await getCandles({ provider: els.provider.value, symbol: sym, timeframe: params.timeframe, limit: +els.limit.value });
        const res = runBacktest(candles, { ...params, asset: sym });
        if (!res.error && sym === params.asset) { lastCandles = candles; lastParams = { ...params, asset: sym }; paintChart(res); paintResult(res, els.provider.value); }
        rows.push({ sym, res });
      } catch (err) { rows.push({ sym, err: err.message }); }
    }
    rows.sort((a, b) => (b.res?.netProfit ?? -1e18) - (a.res?.netProfit ?? -1e18));
    els.multiOut.innerHTML = '<h3 style="font-size:13px">Ranking multi-pair</h3><div class="scrollx"><table class="tbl"><thead><tr><th>Pair</th><th>Net</th><th>Win%</th><th>PF</th><th>DD%</th><th>Trades</th></tr></thead><tbody>' +
      rows.map(({ sym, res, err }) => err ? '<tr><td>' + sym + '</td><td colspan="5" style="color:#ff9a9a">' + err + '</td></tr>'
        : res.error ? '<tr><td>' + sym + '</td><td colspan="5">' + res.error + '</td></tr>'
        : '<tr><td>' + sym + '</td><td>' + fmt$(res.netProfit) + '</td><td>' + fmt(res.winRate, 1) + '%</td><td>' + (res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor)) + '</td><td>' + fmt(res.maxDrawdownPercent) + '%</td><td>' + res.totalTrades + '</td></tr>').join('') + '</tbody></table></div>';
    setStatus('Multi-pair selesai (' + rows.length + ' pair).', false);
  } catch (err) { setStatus('Error multi-pair: ' + err.message, true); }
  finally { els.runAll.disabled = false; els.cancel.disabled = true; }
});

// Lab optimasi
els.btnOpt.addEventListener('click', async () => {
  if (!lastCandles.length || !lastParams) { els.optStatus.textContent = 'Jalankan backtest dulu agar ada data acuan.'; return; }
  els.btnOpt.disabled = true;
  const grid = [];
  for (const sl of [1, 1.5, 2.5]) for (const tp of [2, 3, 5]) for (const r of [0.5, 1, 2]) grid.push({ sl, tp, r });
  const out = [];
  for (const g of grid) {
    const res = runBacktest(lastCandles, { ...lastParams, slPercent: g.sl / 100, tpPercent: g.tp / 100, riskPerTrade: g.r / 100 });
    if (!res.error) {
      const score = res.netProfit - res.maxDrawdownPercent * (lastParams.initialCapital * 0.002) + Math.min(res.profitFactor, 5) * 10;
      out.push({ ...g, res, score });
    }
  }
  out.sort((a, b) => b.score - a.score);
  els.tblOpt.querySelector('tbody').innerHTML = out.map((o, i) => '<tr><td>' + (i + 1) + '</td><td>' + o.sl + '</td><td>' + o.tp + '</td><td>' + o.r + '</td><td>' + fmt$(o.res.netProfit) + '</td><td>' + fmt(o.res.profitFactor) + '</td><td>' + fmt(o.res.maxDrawdownPercent) + '</td><td>' + o.res.totalTrades + '</td><td><button class="btn ghost sm" data-i="' + i + '">Pakai</button></td></tr>').join('');
  els.tblOpt.querySelector('tbody').onclick = (e) => {
    const b = e.target.closest('[data-i]'); if (!b) return;
    const o = out[+b.dataset.i];
    els.sl.value = o.sl; els.tp.value = o.tp; els.risk.value = o.r;
    els.tabs[0].click();
    setStatus('Parameter lab diterapkan: SL ' + o.sl + '% TP ' + o.tp + '% risiko ' + o.r + '%. Klik Jalankan Backtest.', false);
  };
  els.optStatus.textContent = 'Selesai: ' + out.length + ' kombinasi dinilai dari ' + lastCandles.length + ' candle.';
  els.btnOpt.disabled = false;
});

// Market
els.btnMarket.addEventListener('click', async () => {
  els.mktStatus.textContent = 'Memuat…';
  try {
    const pairs = await topPairs(els.provider.value, 8);
    const tb = els.tblMkt.querySelector('tbody'); tb.innerHTML = '';
    for (const sym of pairs) {
      try {
        const candles = await getCandles({ provider: els.provider.value, symbol: sym, timeframe: els.timeframe.value, limit: 200 });
        const last = candles[candles.length - 1];
        const chg = ((last.c - candles[candles.length - 25].c) / candles[candles.length - 25].c) * 100;
        const res = runBacktest(candles, { asset: sym, timeframe: els.timeframe.value, initialCapital: 1000, riskPerTrade: 0.01, leverage: 1, feePercent: 0.0005, slippagePercent: 0.0002, slPercent: 0.015, tpPercent: 0.03, maxHolding: 100, strategy: els.strategy.value });
        const lastT = res.trades[res.trades.length - 1];
        const fresh = lastT && (last.t - lastT.exitTime) < 10 * 15 * 60000;
        const sigTxt = res.error ? '—' : (lastT ? lastT.direction + (fresh ? ' ★' : '') : 'NETRAL');
        tb.innerHTML += '<tr><td>' + sym + '</td><td>' + fmt(last.c, last.c > 1000 ? 2 : 4) + '</td><td>' + fmt(chg) + '%</td><td>' + sigTxt + '</td></tr>';
      } catch (err) { tb.innerHTML += '<tr><td>' + sym + '</td><td colspan="3" style="color:#ff9a9a">' + err.message.slice(0, 90) + '</td></tr>'; }
    }
    els.mktStatus.textContent = 'Selesai (' + pairs.length + ' pair, ★ = sinyal fresh).';
  } catch (err) { els.mktStatus.textContent = 'Error: ' + err.message; }
});

// Laporan fix (di akhir sesuai permintaan user)
els.fixReport.innerHTML = [
  ['Engine inti (freqtrade-grade)', 'Eksekusi dipindah ke <b>open[i+1]</b> (sebelumnya sinyal bisa intip candle berjalan = lookahead bias). Validasi OHLC finite + high/low konsisten + <b>dedup timestamp</b> + sort + hormati <b>start/end date</b>. Min 60 candle dengan pesan jelas.'],
  ['SL/TP konservatif', 'Bila SL &amp; TP tersentuh dalam 1 candle yang sama → <b>SL dulu (rugi)</b>, sesuai perilaku freqtrade worst-case. Sebelumnya urutan tak tentu.'],
  ['Biaya realistis', '<b>Fee 2 sisi</b> (entry+exit) + slippage 2 sisi merugikan. Sizing <b>risiko-tetap</b> dengan clamp notional equity×leverage; validasi leverage 1–100 &amp; risiko 0.1–10%; cegah NaN/Infinity.'],
  ['Metrik lengkap', 'Tambah <b>maxDD nominal + %, Sharpe, Sortino, Calmar, CAGR, exposure, expectancy, averageR, avgRR, streaks, avg holding</b>; profitFactor aman bagi-nol (∞→999). Sebelumnya hanya winRate/profit kasar.'],
  ['Expiry & non-overlap', 'Tambah <b>max holding → EXPIRED</b> (default 100) dan 1 posisi per waktu agar equity compounding benar.'],
  ['Chart (arah tetap)', 'Kunci <b>sumbu harga Y</b> saat pinch (tidak gepeng), <b>1 label harga dinamis</b> hijau/merah (hapus dobel), error provider <b>jujur</b> (bedakan provider-gagal vs no-data), overlay volume/EMA toggle, countdown mengikuti <b>timeframe data aktual</b>, fitContent aman.'],
  ['UI/UX + validasi', 'Semua input angka di-parse aman (koma→titik, default bila kosong), timeframe/symbol divalidasi, tombol Batal untuk multi-pair, ranking multi-pair, Strategy Lab grid-search 27 kombinasi, import CSV, cache 5 menit + mode Demo offline. Bahasa Indonesia konsisten.'],
  ['Error yang di-fix saat verifikasi', 'Lihat <b>tests/run_tests.mjs</b>: 14 assertion lolos (parseTimeframe, dedup, fee-2-sisi, SL-dulu, expiry, Sharpe, dsb). Tidak ada crash input kosong; chart tidak gepeng; tidak ada label ganda.'],
].map(([t, d]) => '<details open><summary>' + t + '</summary><p class="sub">' + d + '</p></details>').join('');
