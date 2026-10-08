/* AetherSignalBot v3.24 controller.
 * Struktur & fungsi = APK 3.22: Dashboard, Strategies, Market Hub, Backtest, Settings + Signals, History, Lab.
 * Tahan-banting: tiap halaman dibungkus try/catch + tab fallback inline, jadi 1 error tak mematikan tab lain.
 */
'use strict';
import { runBacktest, strategyList, parseTimeframe, buildCache, normalizeCandles, decideAt } from './core.js';
import { getCandles, topPairs, parseCSV } from './data.js';
import { renderMain, renderEquity } from './charts.js';

const $ = (id) => document.getElementById(id);
const show = (name) => { if (window.__aetherShow) window.__aetherShow(name); };
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* penuh/privat */ } },
};

const state = {
  pair: store.get('aether_pair', 'BTCUSDT'),
  enabled: new Set(store.get('aether_enabled', ['ema_trend', 'supertrend', 'rsi', 'macd', 'breakout'])),
  signals: store.get('aether_signals', []),
  hist: store.get('aether_hist', []),
  candles: [], params: null, result: null, csv: null, cancelled: false,
  mktRows: [], botTimer: null, timerInt: null,
};
const STRS = (() => { try { return strategyList(); } catch { return []; } })();

// ---------- util ----------
function setStatus(msg, err) { const el = $('status'); if (el) { el.textContent = msg; el.classList.toggle('err', !!err); } }
function fmt(n, d = 2) { if (!Number.isFinite(n)) return '—'; return n.toLocaleString('id-ID', { minimumFractionDigits: d, maximumFractionDigits: d }); }
function fmt$(n) { if (!Number.isFinite(n)) return '—'; return (n < 0 ? '-' : '') + '$' + fmt(Math.abs(n)); }
function esc(s) { return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
function saveEnabled() { store.set('aether_enabled', [...state.enabled]); }
function pushSignal(s) {
  state.signals.unshift({ ...s, id: Date.now() + '_' + Math.floor(Math.random() * 1e6) });
  state.signals = state.signals.slice(0, 200);
  store.set('aether_signals', state.signals);
  renderDashboard(); renderSignals();
}

// ---------- STRATEGI: list + combo checks (opsi ke-2 dst) ----------
function renderStrategies() {
  try {
    const q = ($('stratSearch').value || '').toLowerCase();
    const box = $('stratList');
    box.innerHTML = STRS.filter((s) => !q || s.name.toLowerCase().includes(q) || s.id.includes(q)).map((s) =>
      '<details><summary>' + esc(s.name) + ' <span style="color:var(--dim);font-weight:400">(' + s.id + ')</span></summary>' +
      '<p class="sub">' + esc(s.desc) + '</p><div class="row"><button class="btn ghost sm" data-use="' + s.id + '">Pakai di Backtest</button>' +
      '<label style="font-size:12px;color:var(--mut)"><input type="checkbox" data-en="' + s.id + '" ' + (state.enabled.has(s.id) ? 'checked' : '') + '> aktif</label></div></details>').join('')
      || '<p class="sub">Tidak ketemu.</p>';
  } catch (e) { console.warn('strategi:', e); }
}
function renderComboChecks() {
  try {
    const box = $('comboChecks');
    const cur = $('strategy').value;
    box.innerHTML = STRS.filter((s) => s.id !== cur).map((s) =>
      '<label><input type="checkbox" value="' + s.id + '" ' + (['supertrend', 'rsi'].includes(s.id) ? 'checked' : '') + '> ' + esc(s.name) + '</label>').join('');
    box.onchange = () => {
      if (!$('comboMode').value && box.querySelectorAll('input:checked').length) $('comboMode').value = 'OR';
    };
  } catch (e) { console.warn('combo:', e); }
}

// ---------- MARKET HUB (listview) ----------
async function loadMarket() {
  const tb = $('tblMkt').querySelector('tbody');
  try {
    $('mktStatus').textContent = 'Memuat…';
    const prov = $('mktProvider').value, tf = $('mktTf').value;
    const pairs = await topPairs(prov, 10);
    state.mktRows = [];
    tb.innerHTML = '';
    for (const sym of pairs) {
      try {
        const candles = await getCandles({ provider: prov, symbol: sym, timeframe: tf, limit: 200 });
        const last = candles[candles.length - 1];
        const ref = candles[Math.max(0, candles.length - 25)];
        const chg = ((last.c - ref.c) / ref.c) * 100;
        let sig = 'NETRAL';
        try {
          const norm = normalizeCandles(candles);
          const dec = decideAt(norm, norm.length - 1, buildCache(norm), { strategy: $('strategy').value || 'ema_trend', strategyParams: {} });
          if (dec.passed) sig = dec.direction;
        } catch { /* tetap NETRAL */ }
        state.mktRows.push({ sym, price: last.c, chg, sig });
      } catch (err) { state.mktRows.push({ sym, err: String(err.message || err).slice(0, 90) }); }
    }
    paintMarket();
    $('mktStatus').textContent = 'Selesai (' + pairs.length + ' pair). Klik ★ pakai di Backtest, klik baris = detail.';
  } catch (err) { $('mktStatus').textContent = 'Error: ' + err.message; }
}
function paintMarket() {
  const q = ($('mktSearch').value || '').toUpperCase();
  const tb = $('tblMkt').querySelector('tbody');
  tb.innerHTML = state.mktRows.filter((r) => !q || r.sym.includes(q)).map((r) =>
    r.err ? '<tr><td>' + esc(r.sym) + '</td><td colspan="3" style="color:#ff9a9a">' + esc(r.err) + '</td><td></td></tr>'
      : '<tr data-sym="' + esc(r.sym) + '"><td>' + esc(r.sym) + '</td><td>' + fmt(r.price, r.price > 1000 ? 2 : 4) + '</td><td>' + fmt(r.chg) + '%</td><td>' + esc(r.sig) + '</td><td><button class="btn ghost sm" data-pick="' + esc(r.sym) + '">★</button></td></tr>').join('')
    || '<tr><td colspan="5" style="text-align:center;color:var(--dim)">Tidak ada hasil.</td></tr>';
}

// ---------- PAIR PICKER (listview, anti input manual) ----------
async function openPairPicker() {
  $('pairModal').classList.remove('hidden');
  const list = $('pairList');
  list.innerHTML = '<p class="sub">Memuat top pair…</p>';
  try {
    const prov = $('provider').value;
    const pairs = await topPairs(prov, 12);
    const render = (f) => {
      list.innerHTML = pairs.filter((s) => !f || s.includes(f)).map((s) =>
        '<button class="pairitem" data-sym="' + s + '"><b>' + s + '</b><small>' + (s === state.pair ? '✓ aktif' : 'pakai') + '</small></button>').join('')
        || '<p class="sub">Tidak ketemu.</p>';
    };
    render('');
    $('pairSearch').oninput = (e) => render(e.target.value.toUpperCase());
  } catch (err) {
    const demo = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'BNBUSDT', 'XRPUSDT', 'DOGEUSDT'];
    list.innerHTML = '<p class="sub">Provider gagal (' + esc(err.message) + ') — daftar offline:</p>' + demo.map((s) =>
      '<button class="pairitem" data-sym="' + s + '"><b>' + s + '</b><small>offline</small></button>').join('');
  }
}
function setPair(sym) {
  state.pair = String(sym).toUpperCase().replace(/[^A-Z0-9]/g, '') || 'BTCUSDT';
  store.set('aether_pair', state.pair);
  $('pairLabel').textContent = state.pair;
  $('pairModal').classList.add('hidden');
  setStatus('Pair: ' + state.pair + '. Klik Jalankan Backtest.', false);
}

// ---------- BACKTEST ----------
function readParams() {
  const fromMs = $('from').value ? new Date($('from').value + 'T00:00:00Z').getTime() : 0;
  const toMs = $('to').value ? new Date($('to').value + 'T23:59:59Z').getTime() : 0;
  const mode = $('comboMode').value;
  const extra = [...document.querySelectorAll('#comboChecks input:checked')].map((c) => c.value);
  const combo = mode ? { mode, strategies: [...new Set([$('strategy').value, ...extra])].slice(0, 5) } : null;
  if (combo && combo.strategies.length < 2) throw new Error('Combo butuh minimal 2 strategi (centang Strategi 2+).');
  return {
    asset: state.pair, timeframe: $('timeframe').value,
    initialCapital: +$('capital').value, riskPerTrade: (+$('risk').value) / 100,
    leverage: +$('leverage').value, feePercent: (+$('fee').value) / 100, slippagePercent: (+$('slippage').value) / 100,
    slPercent: (+$('sl').value) / 100, tpPercent: (+$('tp').value) / 100,
    maxHolding: +$('maxholding').value, strategy: $('strategy').value, strategyParams: {}, combo,
    startDate: fromMs || 0, endDate: toMs || 0, useAtr: $('useatr').value === '1',
  };
}
async function loadData(params) {
  if (state.csv?.length >= 60) return { candles: state.csv, source: 'CSV (' + state.csv.length + 'c)' };
  const candles = await getCandles({ provider: $('provider').value, symbol: params.asset, timeframe: params.timeframe, limit: +$('limit').value });
  return { candles, source: $('provider').value + ' · ' + params.asset + ' ' + params.timeframe };
}
function paintChart(res) {
  try {
    const overlays = { volume: $('ovVol').checked, ema20: $('ovE20').checked, ema50: $('ovE50').checked, ema200: $('ovE200').checked };
    let markers = [], priceLines = [];
    if (res && !res.error) {
      markers = res.trades.slice(-120).flatMap((t) => ([
        { t: t.entryTime, pos: t.direction === 'LONG' ? 'belowBar' : 'aboveBar', color: t.direction === 'LONG' ? '#00E676' : '#FF5252', shape: t.direction === 'LONG' ? 'arrowUp' : 'arrowDown', text: t.direction === 'LONG' ? 'L' : 'S' },
        { t: t.exitTime, pos: t.result === 'WIN' ? 'aboveBar' : 'belowBar', color: t.result === 'WIN' ? '#00E5FF' : '#FFC857', shape: 'circle', text: t.result === 'WIN' ? '✓' : '✕' },
      ]));
      const last = res.trades[res.trades.length - 1];
      if (last) priceLines = [{ price: last.stopLoss, color: '#FF5252', title: 'SL' }, { price: last.takeProfit, color: '#00E676', title: 'TP' }];
    }
    renderMain($('chart'), $('chartEmpty'), { candles: state.candles, markers, priceLines, overlays });
    renderEquity($('equity'), res && !res.error ? res.equityCurve : []);
    if (state.candles.length) {
      const l = state.candles[state.candles.length - 1];
      $('chartMeta').textContent = state.params.asset + ' · ' + state.params.timeframe + ' · ' + state.candles.length + 'c · last ' + fmt(l.c, l.c > 1000 ? 2 : 4);
    }
    startCountdown();
  } catch (e) { console.warn('chart:', e); }
}
function startCountdown() {
  clearInterval(state.timerInt);
  $('countdown').classList.add('hidden');
  if (!state.candles.length || !state.params) return;
  let secs = 0;
  try { secs = parseTimeframe(state.params.timeframe) * 60; } catch { return; }
  if (!(secs > 0)) return;
  const tick = () => {
    const left = Math.max(0, state.candles[state.candles.length - 1].t + secs * 1000 - Date.now());
    $('countdown').textContent = '⏱ ' + String(Math.floor(left / 60000)).padStart(2, '0') + ':' + String(Math.floor((left % 60000) / 1000)).padStart(2, '0');
    $('countdown').classList.remove('hidden');
  };
  tick();
  state.timerInt = setInterval(tick, 1000);
}
function paintResult(res, source) {
  state.result = res;
  if (res.error) {
    $('summaryLine').textContent = 'Error: ' + res.error;
    $('kpis').innerHTML = ''; $('tblPair').querySelector('tbody').innerHTML = ''; $('tblAdv').querySelector('tbody').innerHTML = '';
    $('tblTrades').querySelector('tbody').innerHTML = ''; $('tradeCount').textContent = '0';
    renderEquity($('equity'), []);
    return;
  }
  $('summaryLine').innerHTML = '<b>' + esc(res.asset) + ' ' + esc(res.timeframe) + '</b> · ' + esc(res.strategy) + ' · ' + esc(source) +
    ' · Net <b style="color:' + (res.netProfit >= 0 ? 'var(--green)' : 'var(--red)') + '">' + fmt$(res.netProfit) + ' (' + fmt(res.netProfitPercent) + '%)</b>' +
    ' · Win ' + fmt(res.winRate, 1) + '% · PF ' + (res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor)) + ' · MaxDD ' + fmt(res.maxDrawdownPercent) + '%';
  const K = [
    ['Net Profit', fmt$(res.netProfit), res.netProfit >= 0], ['Win Rate', fmt(res.winRate, 1) + '%', res.winRate >= 50],
    ['Total Trades', res.totalTrades, null], ['Profit Factor', res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor), res.profitFactor >= 1.5],
    ['Expectancy', fmt$(res.expectancy), res.expectancy >= 0], ['Avg R', fmt(res.averageR) + 'R', res.averageR >= 0],
    ['Max DD', fmt(res.maxDrawdownPercent) + '%', res.maxDrawdownPercent <= 15], ['Final', fmt$(res.finalCapital), res.finalCapital >= res.initialCapital],
  ];
  $('kpis').innerHTML = K.map(([k, v, p]) => '<div class="kpi"><small>' + k + '</small><b class="' + (p == null ? '' : p ? 'pos' : 'neg') + '">' + v + '</b></div>').join('');
  const r1 = [['Wins / Losses / Exp', res.wins + ' / ' + res.losses + ' / ' + res.expired], ['Gross Profit', fmt$(res.grossProfit)], ['Gross Loss', fmt$(res.grossLoss)], ['Avg Win', fmt$(res.averageWin)], ['Avg Loss', fmt$(res.averageLoss)], ['Streak W/L', res.longestWinStreak + ' / ' + res.longestLossStreak], ['Avg holding', fmt(res.avgHolding, 1) + 'c'], ['Exposure', fmt(res.exposure, 1) + '%']];
  const r2 = [['Sharpe', fmt(res.sharpe)], ['Sortino', fmt(res.sortino)], ['Calmar', res.calmar >= 999 ? '∞' : fmt(res.calmar)], ['CAGR', fmt(res.cagr) + '%'], ['Avg RR', fmt(res.averageRR)], ['MaxDD $', fmt$(res.maxDrawdown)], ['Modal', fmt$(res.initialCapital)], ['Strategi', esc(res.strategy)]];
  $('tblPair').querySelector('tbody').innerHTML = r1.map(([a, b]) => '<tr><td>' + a + '</td><td>' + b + '</td></tr>').join('');
  $('tblAdv').querySelector('tbody').innerHTML = r2.map(([a, b]) => '<tr><td>' + a + '</td><td>' + b + '</td></tr>').join('');
  $('tradeCount').textContent = res.trades.length;
  $('tblTrades').querySelector('tbody').innerHTML = res.trades.slice(-200).map((t, i) =>
    '<tr><td>' + (i + 1) + '</td><td>' + new Date(t.exitTime).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td>' + t.direction + '</td><td>' + fmt(t.entry, 4) + '</td><td>' + fmt(t.exit, 4) + '</td><td>' + fmt$(t.pnl) + '</td><td>' + fmt(t.rMultiple) + '</td><td><span class="pill ' + (t.result === 'WIN' ? 'win' : t.result === 'LOSS' ? 'loss' : 'exp') + '">' + t.result + '</span></td></tr>').join('')
    || '<tr><td colspan="8" style="text-align:center;color:var(--dim)">Tidak ada trade.</td></tr>';
  // simpan ke riwayat lokal + auto-signal dari trade terakhir
  state.hist = [...res.trades.map((t) => ({ ...t, id: t.exitTime + '_' + Math.random().toString(36).slice(2, 8) })), ...state.hist].slice(0, 500);
  store.set('aether_hist', state.hist);
  renderHist();
  const lt = res.trades[res.trades.length - 1];
  if (lt) pushSignal({ pair: res.asset, tf: res.timeframe, dir: lt.direction, price: lt.entry, sl: lt.stopLoss, tp: lt.takeProfit, t: lt.entryTime, src: 'backtest' });
}

// ---------- SINYAL & RIWAYAT ----------
function renderSignals() {
  try {
    const d = $('fltDir').value, tf = $('fltTf').value, q = ($('fltSearch').value || '').toUpperCase();
    const rows = state.signals.filter((s) => (!d || s.dir === d) && (!tf || s.tf === tf) && (!q || s.pair.includes(q)));
    $('tblSignals').querySelector('tbody').innerHTML = rows.slice(0, 150).map((s) =>
      '<tr data-sig="' + s.id + '"><td>' + new Date(s.t).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td>' + esc(s.pair) + '</td><td>' + esc(s.dir) + '</td><td>' + fmt(s.price, 4) + '</td><td>' + fmt(s.sl, 4) + '</td><td>' + fmt(s.tp, 4) + '</td></tr>').join('')
      || '<tr><td colspan="6" style="text-align:center;color:var(--dim)">Belum ada sinyal.</td></tr>';
  } catch (e) { console.warn('signals:', e); }
}
function renderHist() {
  try {
    const wins = state.hist.filter((t) => t.result === 'WIN').length;
    const net = state.hist.reduce((s, t) => s + (t.pnl || 0), 0);
    $('histKpis').innerHTML = [['Trades', state.hist.length, null], ['Win', state.hist.length ? fmt((wins / state.hist.length) * 100, 1) + '%' : '—', wins * 2 >= state.hist.length], ['Net', fmt$(net), net >= 0]].map(([k, v, p]) => '<div class="kpi"><small>' + k + '</small><b class="' + (p == null ? '' : p ? 'pos' : 'neg') + '">' + v + '</b></div>').join('');
    $('tblHist').querySelector('tbody').innerHTML = state.hist.slice(0, 150).map((t) =>
      '<tr><td>' + new Date(t.exitTime || t.t).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td>' + esc(t.asset || t.pair) + '</td><td>' + esc(t.direction || t.dir) + '</td><td>' + fmt$(t.pnl) + '</td><td>' + esc(t.result || '—') + '</td></tr>').join('')
      || '<tr><td colspan="5" style="text-align:center;color:var(--dim)">Kosong.</td></tr>';
  } catch (e) { console.warn('hist:', e); }
}
function renderDashboard() {
  try {
    const wins = state.hist.filter((t) => t.result === 'WIN').length;
    const net = state.hist.reduce((s, t) => s + (t.pnl || 0), 0);
    $('dashKpis').innerHTML = [['Total Signals', state.signals.length, null], ['Total Trades', state.hist.length, null], ['Win Rate', state.hist.length ? fmt((wins / state.hist.length) * 100, 1) + '%' : '—', wins * 2 >= state.hist.length], ['Net PnL', fmt$(net), net >= 0]].map(([k, v, p]) => '<div class="kpi"><small>' + k + '</small><b class="' + (p == null ? '' : p ? 'pos' : 'neg') + '">' + v + '</b></div>').join('');
    const s = state.signals[0];
    $('lastSignal').innerHTML = s ? '<div class="sigcard" data-sig="' + s.id + '"><b>' + esc(s.dir) + ' ' + esc(s.pair) + ' ' + esc(s.tf || '') + '</b><br><small style="color:var(--mut)">' + new Date(s.t).toLocaleString('id-ID') + ' · entry ' + fmt(s.price, 4) + ' · SL ' + fmt(s.sl, 4) + ' · TP ' + fmt(s.tp, 4) + '</small></div>' : '<p class="sub">Belum ada sinyal.</p>';
  } catch (e) { console.warn('dash:', e); }
}

// ---------- BOT (MonitoringService ringan) ----------
async function botCycle() {
  try {
    const prov = store.get('aether_set', {}).provider || $('provider').value;
    const tf = store.get('aether_set', {}).tf || $('timeframe').value;
    const strat = $('strategy').value || 'ema_trend';
    const pairs = (await topPairs(prov, 5)).slice(0, 5);
    for (const sym of pairs) {
      try {
        const candles = await getCandles({ provider: prov, symbol: sym, timeframe: tf, limit: 200 });
        const norm = normalizeCandles(candles);
        const dec = decideAt(norm, norm.length - 1, buildCache(norm), { strategy: strat, strategyParams: {} });
        if (dec.passed) {
          const last = norm[norm.length - 1];
          const dup = state.signals.some((x) => x.pair === sym && x.tf === tf && Math.abs(x.t - last.t) < 60_000);
          if (!dup) {
            const sl = dec.direction === 'LONG' ? last.c * 0.985 : last.c * 1.015;
            const tp = dec.direction === 'LONG' ? last.c * 1.03 : last.c * 0.97;
            pushSignal({ pair: sym, tf, dir: dec.direction, price: last.c, sl, tp, t: last.t, src: 'bot' });
            if (store.get('aether_set', {}).notif !== '0' && 'Notification' in window && Notification.permission === 'granted') {
              try { new Notification(dec.direction + ' ' + sym, { body: 'Entry ' + last.c.toFixed(2) }); } catch { /* abaikan */ }
            }
          }
        }
      } catch { /* lanjut pair berikut */ }
    }
    $('botStatus').textContent = 'Bot jalan · scan ' + new Date().toLocaleTimeString('id-ID') + ' · ' + state.signals.length + ' sinyal.';
  } catch (e) { $('botStatus').textContent = 'Bot error: ' + e.message; }
}

// ---------- DETAIL modal ----------
function openDetail(title, html) {
  $('detailTitle').childNodes[0].textContent = title + ' ';
  $('detailBody').innerHTML = html;
  $('detailModal').classList.remove('hidden');
}

// ---------- INIT (setiap blok tahan gagal) ----------
function init() {
  try { $('pairLabel').textContent = state.pair; } catch { /* abaikan */ }
  try { renderStrategies(); $('stratSearch').oninput = renderStrategies; } catch { /* abaikan */ }
  try { renderComboChecks(); $('strategy').onchange = renderComboChecks; } catch { /* abaikan */ }
  try { renderDashboard(); renderSignals(); renderHist(); } catch { /* abaikan */ }
  try {
    const st = store.get('aether_set', {});
    if (st.provider) { $('provider').value = st.provider; $('mktProvider').value = st.provider; $('setProvider').value = st.provider; }
    if (st.tf) $('setTf').value = st.tf;
  } catch { /* abaikan */ }

  // navigasi tambahan
  document.addEventListener('click', (e) => {
    try {
      const u = e.target.closest('[data-use]');
      if (u) { $('strategy').value = u.dataset.use; renderComboChecks(); show('backtest'); return; }
      const pk = e.target.closest('[data-pick]');
      if (pk) { setPair(pk.dataset.pick); show('backtest'); return; }
      const pi = e.target.closest('.pairitem');
      if (pi) { setPair(pi.dataset.sym); return; }
      const sg = e.target.closest('[data-sig]');
      if (sg) {
        const s = state.signals.find((x) => x.id === sg.dataset.sig);
        if (s) openDetail(s.dir + ' ' + s.pair, '<p class="sub">' + new Date(s.t).toLocaleString('id-ID') + ' · ' + esc(s.tf || '') + ' · via ' + esc(s.src || '—') + '</p><div class="scrollx"><table class="tbl"><tbody>' + [['Entry', fmt(s.price, 4)], ['Stop Loss', fmt(s.sl, 4)], ['Take Profit', fmt(s.tp, 4)]].map(([a, b]) => '<tr><td>' + a + '</td><td>' + b + '</td></tr>').join('') + '</tbody></table></div><div class="row" style="margin-top:8px"><button class="btn primary sm" id="dUse">Pakai pair di Backtest</button></div>');
        return;
      }
      if (e.target.id === 'dUse' && document.querySelector('#detailBody')) { /* ditangani di bawah */ }
    } catch { /* abaikan */ }
  });
  document.addEventListener('click', (e) => {
    if (e.target.id === 'dUse') {
      const t = $('detailTitle').textContent;
      const m = /([A-Z0-9]{4,})/.exec(t);
      if (m) setPair(m[1]);
      $('detailModal').classList.add('hidden');
      show('backtest');
    }
  });

  // strategi enable toggle (delegasi)
  $('stratList').addEventListener('change', (e) => {
    const c = e.target.closest('[data-en]');
    if (!c) return;
    if (c.checked) state.enabled.add(c.dataset.en); else state.enabled.delete(c.dataset.en);
    saveEnabled();
  });

  // market
  $('btnMarket').addEventListener('click', loadMarket);
  $('mktSearch').addEventListener('input', paintMarket);
  $('tblMkt').addEventListener('click', (e) => {
    const tr = e.target.closest('tr[data-sym]');
    if (!tr || e.target.closest('[data-pick]')) return;
    const r = state.mktRows.find((x) => x.sym === tr.dataset.sym);
    if (r && !r.err) openDetail(r.sym, '<p class="sub">Harga ' + fmt(r.price, 4) + ' · 24h ' + fmt(r.chg) + '% · sinyal ' + esc(r.sig) + '</p><div class="row"><button class="btn primary sm" data-pick="' + esc(r.sym) + '">★ Pakai di Backtest</button></div>');
  });

  // pair picker
  $('btnPickPair').addEventListener('click', openPairPicker);
  $('pairClose').addEventListener('click', () => $('pairModal').classList.add('hidden'));
  $('pairRefresh').addEventListener('click', openPairPicker);
  $('pairManualBtn').addEventListener('click', () => { if ($('pairManual').value.trim()) setPair($('pairManual').value); });
  $('detailClose').addEventListener('click', () => $('detailModal').classList.add('hidden'));

  // overlay
  ['ovVol', 'ovE20', 'ovE50', 'ovE200'].forEach((id) => { try { $(id).addEventListener('change', () => { if (state.candles.length && state.result) paintChart(state.result); }); } catch { /* abaikan */ } });

  // csv
  $('csv').addEventListener('change', async () => {
    const f = $('csv').files[0];
    if (!f) { state.csv = null; return; }
    try { state.csv = parseCSV(await f.text()); setStatus('CSV: ' + state.csv.length + ' candle.', false); }
    catch (err) { state.csv = null; setStatus('CSV error: ' + err.message, true); }
  });

  // run
  $('run').addEventListener('click', async () => {
    state.cancelled = false; $('run').disabled = true; $('cancel').disabled = false; $('multiOut').innerHTML = '';
    try {
      state.params = readParams();
      setStatus('Mengambil data ' + state.params.asset + '…', false);
      const { candles, source } = await loadData(state.params);
      if (state.cancelled) return;
      state.candles = candles;
      setStatus('Backtest ' + candles.length + 'c…', false);
      await new Promise((r) => setTimeout(r, 30));
      const t0 = performance.now();
      const res = runBacktest(candles, state.params);
      if (state.cancelled) return;
      paintChart(res); paintResult(res, source);
      setStatus(res.error ? 'Error: ' + res.error : 'Selesai ' + Math.round(performance.now() - t0) + 'ms · ' + res.totalTrades + ' trade · Net ' + fmt$(res.netProfit) + ' · ' + source, !!res.error);
    } catch (err) {
      $('chartEmpty').style.display = 'flex';
      $('chartEmpty').textContent = 'Gagal: ' + err.message + ' — coba Demo (offline).';
      setStatus('Error: ' + err.message, true);
    } finally { $('run').disabled = false; $('cancel').disabled = true; }
  });
  $('cancel').addEventListener('click', () => { state.cancelled = true; });
  $('runAll').addEventListener('click', async () => {
    state.cancelled = false; $('runAll').disabled = true; $('cancel').disabled = false;
    try {
      const p = readParams();
      const pairs = (await topPairs($('provider').value, 5)).filter((s) => s !== p.asset);
      pairs.unshift(p.asset);
      const rows = [];
      for (const sym of pairs.slice(0, 5)) {
        if (state.cancelled) break;
        setStatus('Backtest ' + sym + '…', false);
        try {
          const candles = state.csv?.length ? state.csv : await getCandles({ provider: $('provider').value, symbol: sym, timeframe: p.timeframe, limit: +$('limit').value });
          const res = runBacktest(candles, { ...p, asset: sym });
          if (!res.error && sym === p.asset) { state.candles = candles; state.params = { ...p, asset: sym }; paintChart(res); paintResult(res, $('provider').value); }
          rows.push({ sym, res });
        } catch (err) { rows.push({ sym, err: err.message }); }
      }
      rows.sort((a, b) => (b.res?.netProfit ?? -1e18) - (a.res?.netProfit ?? -1e18));
      $('multiOut').innerHTML = '<h3 style="font-size:13px">Ranking multi-pair</h3><div class="scrollx"><table class="tbl"><thead><tr><th>Pair</th><th>Net</th><th>Win%</th><th>PF</th><th>DD%</th><th>Tr</th></tr></thead><tbody>' +
        rows.map(({ sym, res, err }) => err ? '<tr><td>' + esc(sym) + '</td><td colspan="5" style="color:#ff9a9a">' + esc(err) + '</td></tr>' : res.error ? '<tr><td>' + esc(sym) + '</td><td colspan="5">' + esc(res.error) + '</td></tr>' : '<tr><td>' + esc(sym) + '</td><td>' + fmt$(res.netProfit) + '</td><td>' + fmt(res.winRate, 1) + '%</td><td>' + (res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor)) + '</td><td>' + fmt(res.maxDrawdownPercent) + '%</td><td>' + res.totalTrades + '</td></tr>').join('') + '</tbody></table></div>';
      setStatus('Multi-pair selesai.', false);
    } catch (err) { setStatus('Error: ' + err.message, true); }
    finally { $('runAll').disabled = false; $('cancel').disabled = true; }
  });

  // lab
  $('btnOpt').addEventListener('click', async () => {
    if (!state.candles.length || !state.params) { $('optStatus').textContent = 'Jalankan backtest dulu.'; return; }
    $('btnOpt').disabled = true;
    const out = [];
    for (const sl of [1, 1.5, 2.5]) for (const tp of [2, 3, 5]) for (const r of [0.5, 1, 2]) {
      const res = runBacktest(state.candles, { ...state.params, slPercent: sl / 100, tpPercent: tp / 100, riskPerTrade: r / 100 });
      if (!res.error) out.push({ sl, tp, r, res, score: res.netProfit - res.maxDrawdownPercent * (state.params.initialCapital * 0.002) + Math.min(res.profitFactor, 5) * 10 });
    }
    out.sort((a, b) => b.score - a.score);
    $('tblOpt').querySelector('tbody').innerHTML = out.map((o, i) => '<tr><td>' + (i + 1) + '</td><td>' + o.sl + '</td><td>' + o.tp + '</td><td>' + o.r + '</td><td>' + fmt$(o.res.netProfit) + '</td><td>' + fmt(o.res.profitFactor) + '</td><td>' + fmt(o.res.maxDrawdownPercent) + '</td><td>' + o.res.totalTrades + '</td><td><button class="btn ghost sm" data-i="' + i + '">Pakai</button></td></tr>').join('');
    $('tblOpt').querySelector('tbody').onclick = (e) => {
      const b = e.target.closest('[data-i]'); if (!b) return;
      const o = out[+b.dataset.i];
      $('sl').value = o.sl; $('tp').value = o.tp; $('risk').value = o.r;
      show('backtest');
      setStatus('Lab diterapkan: SL ' + o.sl + '% TP ' + o.tp + '% risiko ' + o.r + '%.', false);
    };
    $('optStatus').textContent = 'Selesai: ' + out.length + ' kombinasi.';
    $('btnOpt').disabled = false;
  });

  // sinyal filter + hapus
  ['fltDir', 'fltTf'].forEach((id) => { try { $(id).addEventListener('change', renderSignals); } catch { /* abaikan */ } });
  try { $('fltSearch').addEventListener('input', renderSignals); } catch { /* abaikan */ }
  $('btnClearSignals').addEventListener('click', () => { state.signals = []; store.set('aether_signals', []); renderSignals(); renderDashboard(); });
  $('btnClearHist').addEventListener('click', () => { state.hist = []; store.set('aether_hist', []); renderHist(); renderDashboard(); });

  // bot
  $('btnStartBot').addEventListener('click', async () => {
    try { if ('Notification' in window && Notification.permission === 'default') await Notification.requestPermission(); } catch { /* abaikan */ }
    $('btnStartBot').classList.add('hidden'); $('btnStopBot').classList.remove('hidden');
    $('botBadge').textContent = 'BOT RUN';
    await botCycle();
    state.botTimer = setInterval(botCycle, 120000);
  });
  $('btnStopBot').addEventListener('click', () => {
    clearInterval(state.botTimer);
    $('btnStartBot').classList.remove('hidden'); $('btnStopBot').classList.add('hidden');
    $('botBadge').textContent = 'BOT STOP'; $('botStatus').textContent = 'Bot berhenti.';
  });

  // settings
  $('btnSaveSet').addEventListener('click', () => {
    store.set('aether_set', { provider: $('setProvider').value, tf: $('setTf').value, notif: $('setNotif').value });
    $('provider').value = $('setProvider').value; $('mktProvider').value = $('setProvider').value;
    $('setStatus').textContent = 'Tersimpan.';
  });
  $('btnExport').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify({ signals: state.signals, hist: state.hist }, null, 1)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'aether-export.json'; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  });

  // laporan fix
  try {
    $('fixReport').innerHTML = [
      ['Inspeksi APK 3.22 (full)', 'Bottom nav asli = <b>Dashboard, Strategies, Market Hub, Backtest, Settings</b> + SignalList/Detail, StrategyLab/Builder/Detail, MarketDetail, History, Diagnostics, MonitoringService — semua dipetakan ulang ke web tanpa mengubah fungsi.'],
      ['Tab Strategi & Market mati (FIX utama)', 'Akar: <b>ES-module via file:// diblokir WebView</b> (tanpa <i>allowUniversalAccessFromFileURLs</i>) → app.js gagal total. Fix: flag WebView + <b>fallback tab inline non-module</b> + dropdown strategi di-hardcode di HTML + tiap halaman try/catch.'],
      ['Pair manual → listview', 'Backtest & Market Hub kini pakai <b>pair picker listview</b> (top-by-volume + search + refresh + fallback offline). Input manual disembunyikan di &lt;details&gt;.'],
      ['Opsi strategi ke-2 / combo hilang', 'Dikembalikan: <b>Strategi 1 dropdown + Strategi 2+ checkboxes + Mode OR/AND/MAJORITY</b> (setara ExperimentConfig), validasi min 2 strategi, auto-OR bila ada centang.'],
      ['Engine (tetap freqtrade-grade)', 'close[i]→open[i+1], SL-dulu se-candle, fee 2 sisi + slippage, sizing risiko-tetap + clamp leverage 1–100, dedup timestamp, hormat tanggal, min 60, expiry→EXPIRED, metrik Sharpe/Sortino/Calmar/CAGR/exposure.'],
      ['Chart (arah tetap)', 'Kunci sumbu Y anti-gepeng, 1 label dinamis hijau/merah, error provider jujur, overlay toggle, countdown ikut timeframe.'],
    ].map(([t, d]) => '<details open><summary>' + t + '</summary><p class="sub">' + d + '</p></details>').join('');
  } catch { /* abaikan */ }
}

try { init(); } catch (e) {
  // Tab tetap hidup via fallback inline walau init gagal
  console.warn('init partial:', e);
  try { setStatus('Sebagian modul gagal: ' + e.message + ' — tab tetap bisa dibuka.', true); } catch { /* abaikan */ }
}
