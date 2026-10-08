/* AetherSignalBot v3.26 controller.
 * Struktur & fungsi = APK 3.22: Dashboard, Strategies, Market Hub, Backtest, Settings + Signals, History, Lab.
 * Tahan-banting: tiap halaman dibungkus try/catch + tab fallback inline, jadi 1 error tak mematikan tab lain.
 */
'use strict';
import { runBacktest, strategyList, filterList, applyFilters, parseTimeframe, buildCache, normalizeCandles, decideAt } from './core.js';
import { getCandles, topPairs, YAHOO_UNIVERSE, parseCSV, binanceActiveHost } from './data.js';
import { pairIcon } from './icons.js';
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
  mktRows: [], mktLoaded: false, timerInt: null,
  engPairs: new Set(store.get('aether_engpairs', store.get('aether_drypairs', ['BTCUSDT', 'ETHUSDT']))),
  eng: { running: false, positions: [], closed: store.get('aether_dryclosed', []), equity: store.get('aether_dryequity', null), timer: null },
};
const STRS = (() => { try { return strategyList(); } catch { return []; } })();
const FLTS = (() => { try { return filterList(); } catch { return []; } })();
const SESSIONS = { all: [[0, 24]], asia: [[0, 8]], london: [[7, 16]], ny: [[13, 21]], asia_london: [[0, 16]], london_ny: [[7, 21]] };

// ---------- util ----------
function setStatus(msg, err) { const el = $('status'); if (el) { el.textContent = msg; el.classList.toggle('err', !!err); } }
function fmt(n, d = 2) { if (!Number.isFinite(n)) return '—'; return n.toLocaleString('id-ID', { minimumFractionDigits: d, maximumFractionDigits: d }); }
function fmt$(n) { if (!Number.isFinite(n)) return '—'; return (n < 0 ? '-' : '') + '$' + fmt(Math.abs(n)); }
function esc(s) { return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
function saveEnabled() { store.set('aether_enabled', [...state.enabled]); }
function pushSignal(s) {
  if (state.signals.some((x) => x.pair === s.pair && x.tf === s.tf && x.t === s.t && x.dir === s.dir)) return;
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

// ---------- FILTER (15 filter bawaan APK) ----------
function renderFilterChecks() {
  try {
    $('filterChecks').innerHTML = FLTS.map((f) =>
      '<label title="' + esc(f.desc) + '"><input type="checkbox" value="' + f.id + '"> ' + esc(f.name) + '</label>').join('');
  } catch (e) { console.warn('filter:', e); }
}
function readFilters() {
  const checked = new Set([...document.querySelectorAll('#filterChecks input:checked')].map((c) => c.value));
  if (!checked.size) return [];
  const num = (id, d) => { const v = parseFloat(String($(id).value).replace(',', '.')); return Number.isFinite(v) ? v : d; };
  const P = {
    volume: { mult: num('fVol', 1) }, atr_vol: { minPct: num('fAtrMin', 0.3) },
    adx: { min: num('fAdx', 20) }, rsi: { longMax: num('fRsiHi', 72), shortMin: num('fRsiLo', 28) },
    sr: { bufferPct: num('fSr', 0.3) }, session: { sessions: SESSIONS[$('fSession').value] || [[0, 24]] },
    min_vol: { minPct: num('fAtrMin', 0.3) }, max_vol: { maxPct: num('fAtrMax', 5) },
    cooldown: { bars: Math.round(num('fCool', 3)) },
  };
  return [...checked].map((name) => ({ name, enabled: true, params: P[name] || {} }));
}

// ---------- MARKET HUB (listview: Crypto / Forex+XAU) ----------
async function marketPairs() {
  const cat = $('mktCat').value;
  let prov = $('mktProvider').value;
  if (cat === 'forex') { prov = 'yahoo'; $('mktProvider').value = 'yahoo'; return { prov, pairs: Object.keys(YAHOO_UNIVERSE) }; }
  return { prov, pairs: await topPairs(prov, 10) };
}
async function loadMarket() {
  const tb = $('tblMkt').querySelector('tbody');
  try {
    const tf = $('mktTf').value;
    tb.innerHTML = '<tr><td colspan="5"><div class="skel"></div><div class="skel" style="margin-top:6px"></div><div class="skel" style="margin-top:6px"></div></td></tr>';
    let prov, pairs;
    try {
      ({ prov, pairs } = await marketPairs());
    } catch (err) {
      // Daftar pair pun gagal (jaringan diblokir?) → tampilkan box pemulihan, bukan tabel kosong
      tb.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:18px">'
        + '<p class="sub">Gagal mengambil daftar pair: ' + esc(err.message) + '</p>'
        + '<div class="row" style="justify-content:center">'
        + '<button class="btn primary sm" data-mktfix="demo">Pakai Demo (offline)</button> '
        + '<button class="btn ghost sm" data-mktfix="yahoo">Coba Yahoo Forex</button> '
        + '<button class="btn ghost sm" data-mktfix="retry">Coba lagi</button>'
        + '</div></td></tr>';
      $('mktStatus').textContent = 'Gagal. Pilih salah satu opsi di atas.';
      return;
    }
    // 1) render daftar LANGSUNG (pair selalu muncul walau harga belum ada)
    state.mktRows = pairs.map((sym) => ({ sym, prov, loading: true }));
    paintMarket();
    // 2) isi harga + sinyal progresif, batch paralel 3 (cepat + tahan 1-2 gagal)
    let done = 0;
    $('mktStatus').textContent = 'Memuat harga 0/' + pairs.length + '…';
    for (let k = 0; k < pairs.length; k += 3) {
      const batch = pairs.slice(k, k + 3);
      await Promise.all(batch.map(async (sym) => {
        try {
          const candles = await getCandles({ provider: prov, symbol: sym, timeframe: tf, limit: 200 });
          const last = candles[candles.length - 1];
          const ref = candles[Math.max(0, candles.length - 25)];
          let sig = 'NETRAL';
          try {
            const norm = normalizeCandles(candles);
            const cache = buildCache(norm);
            const dec = decideAt(norm, norm.length - 1, cache, { strategy: $('strategy').value || 'ema_trend', strategyParams: {} });
            if (dec.passed) {
              try {
                const flt = readFilters();
                if (flt.length && !applyFilters(norm, norm.length - 1, cache, dec.direction, flt, {}).passed) sig = 'FILTER×';
                else sig = dec.direction;
              } catch { sig = dec.direction; }
            }
          } catch { /* tetap NETRAL */ }
          Object.assign(state.mktRows.find((r) => r.sym === sym) || {}, { price: last.c, chg: ((last.c - ref.c) / ref.c) * 100, sig, loading: false });
        } catch (err) {
          Object.assign(state.mktRows.find((r) => r.sym === sym) || {}, { err: String(err.message || err).slice(0, 110), loading: false });
        } finally {
          done++;
          $('mktStatus').textContent = 'Memuat harga ' + done + '/' + pairs.length + '…';
          paintMarket();
        }
      }));
      if (state.mktRows.every((r) => !r.loading)) break;
    }
    const ok = state.mktRows.filter((r) => !r.err).length;
    $('mktStatus').textContent = ok
      ? 'Selesai (' + ok + '/' + pairs.length + ' pair). Klik ★ pakai di Backtest, klik baris = detail.'
      : 'Semua pair gagal (' + prov + '). Kemungkinan jaringan memblokir provider ini — coba Demo atau Yahoo.';
  } catch (err) { $('mktStatus').textContent = 'Error: ' + err.message; }
}
function paintMarket() {
  const q = ($('mktSearch').value || '').toUpperCase();
  const tb = $('tblMkt').querySelector('tbody');
  tb.innerHTML = state.mktRows.filter((r) => !q || r.sym.includes(q)).map((r) =>
    r.err ? '<tr><td><span class="paircell">' + pairIcon(r.sym) + esc(r.sym) + '</span></td><td colspan="3" style="color:#E8A0A0">' + esc(r.err) + '</td><td></td></tr>'
      : r.loading ? '<tr><td><span class="paircell">' + pairIcon(r.sym) + esc(r.sym) + '</span></td><td colspan="3" style="color:var(--dim)">memuat…</td><td></td></tr>'
      : '<tr data-sym="' + esc(r.sym) + '"><td><span class="paircell">' + pairIcon(r.sym) + esc(r.sym) + '</span></td><td>' + fmt(r.price, r.price > 1000 ? 2 : 4) + '</td><td>' + fmt(r.chg) + '%</td><td>' + esc(r.sig) + '</td><td><button class="btn ghost sm" data-pick="' + esc(r.sym) + '" data-prov="' + esc(r.prov || '') + '">★</button></td></tr>').join('')
    || '<tr><td colspan="5" style="text-align:center;color:var(--dim)">Tidak ada hasil.</td></tr>';
}

// ---------- PAIR PICKER (listview berkategori, anti input manual) ----------
async function pickerPairs() {
  const cat = ($('pairCat') && $('pairCat').value) || 'top';
  let prov = $('provider').value;
  if (cat === 'forex') return { prov: 'yahoo', pairs: Object.keys(YAHOO_UNIVERSE), autoProvider: 'yahoo' };
  return { prov, pairs: await topPairs(prov, 12), autoProvider: null };
}
async function openPairPicker() {
  $('pairModal').classList.remove('hidden');
  const list = $('pairList');
  list.innerHTML = '<p class="sub">Memuat…</p>';
  try {
    const { pairs, autoProvider } = await pickerPairs();
    const render = (f) => {
      list.innerHTML = pairs.filter((s) => !f || s.includes(f)).map((s) =>
        '<button class="pairitem" data-sym="' + esc(s) + '" data-prov="' + (autoProvider || '') + '"><span class="paircell">' + pairIcon(s) + '<b>' + esc(s) + '</b></span><small>' + (s === state.pair ? '✓ aktif' : 'pakai') + '</small></button>').join('')
        || '<p class="sub">Tidak ketemu.</p>';
    };
    render('');
    $('pairSearch').oninput = (e) => render(e.target.value.toUpperCase());
  } catch (err) {
    const demo = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'BNBUSDT', 'XRPUSDT', 'DOGEUSDT'];
    list.innerHTML = '<p class="sub">Provider gagal (' + esc(err.message) + ') — daftar offline:</p>' + demo.map((s) =>
      '<button class="pairitem" data-sym="' + s + '"><span class="paircell">' + pairIcon(s) + '<b>' + s + '</b></span><small>offline</small></button>').join('');
  }
}
function setPair(sym, autoProvider) {
  const raw = String(sym || '').trim();
  state.pair = raw.includes('/') || raw.includes(' ') ? raw.toUpperCase() : raw.toUpperCase().replace(/[^A-Z0-9]/g, '') || 'BTCUSDT';
  if (autoProvider) { try { $('provider').value = autoProvider; } catch { /* abaikan */ } }
  store.set('aether_pair', state.pair);
  try { $('pairLabel').innerHTML = pairIcon(state.pair) + '<span>' + esc(state.pair) + '</span>'; }
  catch { $('pairLabel').textContent = state.pair; }
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
    atrSlMult: 1.5, atrTpMult: 3.0,
    filters: readFilters(),
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
        { t: t.entryTime, pos: t.direction === 'LONG' ? 'belowBar' : 'aboveBar', color: t.direction === 'LONG' ? '#3FA97C' : '#D96A6A', shape: t.direction === 'LONG' ? 'arrowUp' : 'arrowDown', text: t.direction === 'LONG' ? 'L' : 'S' },
        { t: t.exitTime, pos: t.result === 'WIN' ? 'aboveBar' : 'belowBar', color: t.result === 'WIN' ? '#5B9CF6' : '#D9A441', shape: 'circle', text: t.result === 'WIN' ? '✓' : '✕' },
      ]));
      const last = res.trades[res.trades.length - 1];
      if (last) priceLines = [{ price: last.stopLoss, color: '#D96A6A', title: 'SL' }, { price: last.takeProfit, color: '#3FA97C', title: 'TP' }];
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
  try { paintQuick(res); } catch {}
  if (res.error) {
    const kind = /minimal|min|candle/i.test(res.error) ? 'NO DATA' : 'BACKTEST FAILED';
    $('summaryLine').innerHTML = '<b>' + esc(res.asset) + '</b> · <span class="stat-err">' + kind + '</span> · ' + esc(res.error);
    $('kpis').innerHTML = ''; $('tblPair').querySelector('tbody').innerHTML = ''; $('tblAdv').querySelector('tbody').innerHTML = '';
    lab.tradeRows = []; lab.tradePage = 0; paintTradePage(); $('tradeCount').textContent = '0';
    try { if ($('techOut')) $('techOut').textContent = kind + ' · ' + (source || '') + ' · ' + res.error; } catch {}
    try { if ($('equityCap')) $('equityCap').textContent = ''; } catch {}
    renderEquity($('equity'), []);
    return;
  }
  const noTrades = !(res.totalTrades > 0);
  const winTxt = noTrades ? 'NO TRADES' : ('Win ' + fmt(res.winRate, 1) + '%');
  $('summaryLine').innerHTML = '<b>' + esc(res.asset) + ' ' + esc(res.timeframe) + '</b> · ' + esc(res.strategy) + ' · ' + esc(source) +
    ' · Net <b style="color:' + (res.netProfit >= 0 ? 'var(--green)' : 'var(--red)') + '">' + fmt$(res.netProfit) + ' (' + fmt(res.netProfitPercent) + '%)</b>' +
    ' · ' + winTxt + ' · PF ' + (res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor)) + ' · MaxDD ' + fmt(res.maxDrawdownPercent) + '%';
  const K = [
    ['Net Profit', fmt$(res.netProfit), res.netProfit >= 0], ['Win Rate', noTrades ? 'NO TRADES' : (fmt(res.winRate, 1) + '%'), noTrades ? null : res.winRate >= 50],
    ['Total Trades', res.totalTrades, null], ['Profit Factor', res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor), res.profitFactor >= 1.5],
    ['Max DD', fmt(res.maxDrawdownPercent) + '%', res.maxDrawdownPercent <= 15],
  ];
  $('kpis').innerHTML = K.map(([k, v, p]) => '<div class="kpi"><small>' + k + '</small><b class="' + (p == null ? '' : p ? 'pos' : 'neg') + '">' + v + '</b></div>').join('');
  const r1 = [['Wins / Losses / Exp', res.wins + ' / ' + res.losses + ' / ' + res.expired], ['Tersaring filter', res.filtered ?? 0], ['Gross Profit', fmt$(res.grossProfit)], ['Gross Loss', fmt$(res.grossLoss)], ['Avg Win', fmt$(res.averageWin)], ['Avg Loss', fmt$(res.averageLoss)], ['Streak W/L', res.longestWinStreak + ' / ' + res.longestLossStreak], ['Avg holding', fmt(res.avgHolding, 1) + 'c'], ['Exposure', fmt(res.exposure, 1) + '%']];
  const r2 = [['Expectancy', fmt$(res.expectancy)], ['Avg R', fmt(res.averageR) + 'R'], ['Final', fmt$(res.finalCapital)], ['Sharpe', fmt(res.sharpe)], ['Sortino', fmt(res.sortino)], ['Calmar', res.calmar >= 999 ? '∞' : fmt(res.calmar)], ['CAGR', fmt(res.cagr) + '%'], ['Avg RR', fmt(res.averageRR)], ['MaxDD $', fmt$(res.maxDrawdown)], ['Modal', fmt$(res.initialCapital)], ['Strategi', esc(res.strategy)]];
  $('tblPair').querySelector('tbody').innerHTML = r1.map(([a, b]) => '<tr><td>' + a + '</td><td>' + b + '</td></tr>').join('');
  $('tblAdv').querySelector('tbody').innerHTML = r2.map(([a, b]) => '<tr><td>' + a + '</td><td>' + b + '</td></tr>').join('');
  $('tradeCount').textContent = res.trades.length;
  lab.tradeRows = res.trades.map((t, i) => ({ ...t, i }));
  lab.tradePage = 0;
  try { refreshTradeFilter([res.asset]); } catch {}
  paintTradePage();
  try { if ($('equityCap')) $('equityCap').textContent = res.asset + ' equity (single)'; } catch {}
  try { if ($('techOut')) $('techOut').textContent = res.asset + ' · ' + res.timeframe + ' · ' + res.strategy + ' · ' + source + ' · ' + res.totalTrades + ' trades · filtered ' + (res.filtered ?? 0); } catch {}
  // simpan ke riwayat lokal (dedup by kunci alami) + auto-signal dari trade terakhir
  const seen = new Set(state.hist.map((t) => t.asset + '|' + t.entryTime + '|' + t.exitTime + '|' + t.direction));
  const fresh = res.trades.filter((t) => {
    const k = t.asset + '|' + t.entryTime + '|' + t.exitTime + '|' + t.direction;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  }).map((t) => ({ ...t, id: t.exitTime + '_' + Math.random().toString(36).slice(2, 8) }));
  state.hist = [...fresh, ...state.hist].slice(0, 500);
  store.set('aether_hist', state.hist);
  renderHist();
  const lt = res.trades[res.trades.length - 1];
  if (lt) pushSignal({ pair: res.asset, tf: res.timeframe, dir: lt.direction, price: lt.entry, sl: lt.stopLoss, tp: lt.takeProfit, t: lt.entryTime, src: 'backtest' });
}

// ---------- COLLAPSIBLE SECTIONS (satu pola konsisten + ingat state) ----------
// Default: OPEN strategy/data/chart/result(+overall/pairs), COLLAPSED filters/risk/advanced/trades/tech.
// State ringan di localStorage `aether_labsec` (bukan DB).
const LAB_DEFAULTS = { strategy: true, data: true, filters: false, risk: false, advanced: false, chart: true, result: true, overall: true, pairs: true, trades: false, tech: false, combo: false };
function initCollapsibles() {
  try {
    const saved = store.get('aether_labsec', {});
    const secs = [...document.querySelectorAll('#tab-backtest .lab-sec')];
    secs.forEach((sec) => {
      const key = sec.dataset.sec || sec.dataset.sub || sec.id || 'sec';
      const head = sec.querySelector(':scope > .lab-sec-h');
      const body = sec.querySelector(':scope > .lab-sec-b');
      if (!head || !body) return;
      const open = (key in saved) ? !!saved[key] : (LAB_DEFAULTS[key] !== false);
      const apply = (isOpen) => {
        body.classList.toggle('hidden', !isOpen);
        head.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
        const ch = head.querySelector('.chev');
        if (ch) ch.textContent = isOpen ? '˄' : '›';
      };
      head.addEventListener('click', () => {
        const willOpen = body.classList.contains('hidden');
        const m = store.get('aether_labsec', {});
        m[key] = willOpen;
        store.set('aether_labsec', m);
        apply(willOpen);
      });
      apply(open);
    });
    // kompat lama: tombol collapseAll/expandAll sudah dihapus dari UI
    try { $('collapseAll')?.addEventListener('click', () => {}); } catch {}
    try { $('expandAll')?.addEventListener('click', () => {}); } catch {}
  } catch (e) { console.warn('labsec:', e); }
}

// ---------- LAB UI: mode single/multi, preset, metas, chart tabs, trade pager ----------
const lab = { mode: 'single', tradePage: 0, tradeRows: [], multiCache: new Map(), universe: [], staged: new Set(), multiSel: new Set(store.get('aether_multipair', [])) };
function labMode() { return lab.mode; }
function setLabMode(m) {
  lab.mode = m;
  try {
    $('modeSingle')?.classList.toggle('active', m === 'single');
    $('modeMulti')?.classList.toggle('active', m === 'multi');
    if ($('modeRadioSingle')) $('modeRadioSingle').checked = (m === 'single');
    if ($('modeRadioMulti')) $('modeRadioMulti').checked = (m === 'multi');
    $('multiWrap')?.classList.toggle('hidden', m !== 'multi');
    $('pairSingleLabel')?.classList.toggle('hidden', m !== 'single');
    refreshLabMeta();
  } catch {}
}
function refreshLabMeta() {
  try {
    const strat = $('strategy')?.value || 'ema_trend';
    const tf = $('timeframe')?.value || '';
    if ($('strategyMeta')) $('strategyMeta').textContent = strat + ($('presetMode')?.value === 'custom' ? ' · custom' : ' · default');
    if ($('dataMeta')) $('dataMeta').textContent = lab.mode === 'multi' ? ('Multi Pair • ' + lab.multiSel.size + ' pairs • ' + tf) : ('Single • ' + (state.pair || '') + ' • ' + tf);
    if ($('comboMeta')) $('comboMeta').textContent = $('comboMode')?.value || 'Single';
    if ($('riskMeta')) $('riskMeta').textContent = 'SL ' + ($('sl')?.value || '-') + '% · TP ' + ($('tp')?.value || '-') + '%';
    const nF = document.querySelectorAll('#filterChecks input:checked').length;
    if ($('filterMeta')) $('filterMeta').textContent = nF ? (nF + ' aktif') : '0 aktif';
    if ($('quickMeta')) $('quickMeta').textContent = (lab.mode === 'multi' ? ('MULTI (' + lab.multiSel.size + ')') : (state.pair || '')) + ' · ' + tf + ' · ' + strat;
  } catch {}
}
function paintQuick(res) {
  try {
    if (!res || res.error) { $('quickKpis').innerHTML = '<span class="muted">' + esc(res?.error || 'Belum ada hasil.') + '</span>'; return; }
    const noTrades = !(res.totalTrades > 0);
    const items = [
      ['Trades', String(res.totalTrades), null],
      ['Win', noTrades ? 'NO TRADES' : (fmt(res.winRate, 1) + '%'), noTrades ? null : res.winRate >= 50],
      ['PF', res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor), res.profitFactor >= 1.5],
      ['Profit', fmt$(res.netProfit), res.netProfit >= 0],
      ['DD', fmt(res.maxDrawdownPercent) + '%', res.maxDrawdownPercent <= 15],
    ];
    $('quickKpis').innerHTML = items.map(([k, v, p]) => '<div class="qk"><small>' + k + '</small><b class="' + (p == null ? '' : p ? 'pos' : 'neg') + '">' + esc(v) + '</b></div>').join('');
    if ($('resultMeta')) $('resultMeta').textContent = esc(res.asset) + ' · ' + res.totalTrades + ' trades';
  } catch {}
}
const FILTER_PRESETS = {
  loose: ['trend', 'volume'],
  balanced: ['trend', 'htf', 'volume', 'adx', 'rsi', 'cooldown'],
  strict: ['trend', 'ema', 'htf', 'volume', 'atr_vol', 'adx', 'rsi', 'ms', 'sr', 'liq', 'min_vol', 'max_vol', 'cooldown', 'dup'],
};
function applyFilterPreset(ids) {
  try {
    document.querySelectorAll('#filterChecks input').forEach((c) => { c.checked = ids.includes(c.value); });
    refreshLabMeta();
  } catch {}
}
function initLabUi() {
  try { setLabMode('single'); } catch {}
  try { $('modeSingle').addEventListener('click', () => setLabMode('single')); } catch {}
  try { $('modeMulti').addEventListener('click', () => setLabMode('multi')); } catch {}
  try { $('modeRadioSingle').addEventListener('change', () => setLabMode('single')); } catch {}
  try { $('modeRadioMulti').addEventListener('change', () => setLabMode('multi')); } catch {}
  try { $('btnSelectPairs').addEventListener('click', openMultiModal); } catch {}
  try { $('multiClose').addEventListener('click', () => $('multiModal').classList.add('hidden')); } catch {}
  try { $('multiReload').addEventListener('click', async () => { await loadPairUniverse(); renderMultiSel(); if (!$('multiModal').classList.contains('hidden')) renderMultiList($('multiSearchModal').value); }); } catch {}
  try { $('multiSearchModal').addEventListener('input', (e) => renderMultiList(e.target.value)); } catch {}
  try { $('multiSelectAll').addEventListener('click', () => { visibleMultiPairs().forEach((s) => lab.staged.add(s)); renderMultiList($('multiSearchModal').value); }); } catch {}
  try { $('multiClearAll').addEventListener('click', () => { lab.staged.clear(); renderMultiList($('multiSearchModal').value); }); } catch {}
  try { $('multiClear').addEventListener('click', () => { lab.staged.clear(); renderMultiList($('multiSearchModal').value); }); } catch {}
  try {
    $('multiApply').addEventListener('click', () => {
      lab.multiSel = new Set(lab.staged);
      store.set('aether_multipair', [...lab.multiSel]);
      $('multiModal').classList.add('hidden');
      renderMultiSel(); refreshLabMeta();
      setStatus(lab.multiSel.size ? (lab.multiSel.size + ' pairs selected.') : 'Please select at least one pair.', !lab.multiSel.size);
    });
  } catch {}
  try {
    $('pairChips').addEventListener('click', (e) => {
      const b = e.target.closest('[data-rm]');
      if (!b) return;
      lab.multiSel.delete(b.dataset.rm);
      store.set('aether_multipair', [...lab.multiSel]);
      renderMultiSel(); refreshLabMeta();
    });
  } catch {}
  try { $('provider').addEventListener('change', () => { lab.universe = []; }); } catch {}
  try {
    $('presetMode').addEventListener('change', () => {
      const custom = $('presetMode').value === 'custom';
      $('stratDetail').classList.toggle('hidden', !custom);
      refreshLabMeta();
    });
  } catch {}
  try {
    $('comboToggle').addEventListener('click', (e) => {
      e.stopPropagation();
      const open = $('comboBody').classList.contains('hidden');
      $('comboBody').classList.toggle('hidden', !open);
      $('comboToggle').setAttribute('aria-expanded', open ? 'true' : 'false');
      $('comboToggle').querySelector('.chev').textContent = open ? '˄' : '›';
    });
  } catch {}
  try { ['strategy', 'timeframe', 'provider', 'sl', 'tp', 'comboMode'].forEach((id) => $(id)?.addEventListener('change', refreshLabMeta)); } catch {}
  try { document.querySelector('#filterChecks')?.addEventListener('change', refreshLabMeta); } catch {}
  try { $('filterPresetLoose').addEventListener('click', () => applyFilterPreset(FILTER_PRESETS.loose)); } catch {}
  try { $('filterPresetBal').addEventListener('click', () => applyFilterPreset(FILTER_PRESETS.balanced)); } catch {}
  try { $('filterPresetStrict').addEventListener('click', () => applyFilterPreset(FILTER_PRESETS.strict)); } catch {}
  try { $('filterClear').addEventListener('click', () => applyFilterPreset([])); } catch {}
  try {
    $('chartTabPrice').addEventListener('click', () => {
      $('chartTabPrice').classList.add('active'); $('chartTabEquity').classList.remove('active');
      $('chart').classList.remove('hidden'); $('equity').classList.add('hidden');
    });
    $('chartTabEquity').addEventListener('click', () => {
      $('chartTabEquity').classList.add('active'); $('chartTabPrice').classList.remove('active');
      $('equity').classList.remove('hidden'); $('chart').classList.add('hidden');
    });
  } catch {}
  try {
    $('tradePrev').addEventListener('click', () => { lab.tradePage = Math.max(0, lab.tradePage - 1); paintTradePage(); });
    $('tradeNext').addEventListener('click', () => { lab.tradePage += 1; paintTradePage(); });
    $('tradeExport').addEventListener('click', exportTrades);
    $('tradePairFilter').addEventListener('change', () => { lab.tradePage = 0; paintTradePage(); });
  } catch {}
  try { renderMultiSel(); } catch {}
  try { $('csv')?.addEventListener('change', () => { setTimeout(refreshCsvBadge, 50); }); } catch {}
  refreshCsvBadge();
}
function refreshCsvBadge() {
  try {
    const n = state.csv?.length || 0;
    if ($('csvBadge')) $('csvBadge').textContent = n ? ('CSV aktif (' + n + 'c) — dipakai single-pair saja, diabaikan saat multi.') : 'CSV: tidak aktif.';
    if ($('advMeta')) $('advMeta').textContent = n ? ('CSV ' + n + 'c') : 'Tanggal · CSV';
  } catch {}
}
function tradeRowsFiltered() {
  try {
    const f = $('tradePairFilter')?.value || '';
    const rows = lab.tradeRows || [];
    return f ? rows.filter((t) => (t.asset || t.pair) === f) : rows;
  } catch { return lab.tradeRows || []; }
}
function refreshTradeFilter(pairs) {
  try {
    const sel = $('tradePairFilter');
    if (!sel) return;
    const cur = sel.value;
    sel.innerHTML = '<option value="">All Pairs</option>' + (pairs || []).map((s) => '<option value="' + esc(s) + '">' + esc(s) + '</option>').join('');
    if ((pairs || []).includes(cur)) sel.value = cur;
  } catch {}
}
function paintTradePage() {
  try {
    const per = 25;
    const rows = tradeRowsFiltered();
    const total = (lab.tradeRows || []).length;
    const pages = Math.max(1, Math.ceil(rows.length / per));
    lab.tradePage = Math.min(Math.max(0, lab.tradePage), pages - 1);
    const slice = rows.slice(lab.tradePage * per, lab.tradePage * per + per);
    const tb = $('tblTrades').querySelector('tbody');
    tb.innerHTML = slice.map((t) =>
      '<tr><td>' + (t.i + 1) + '</td><td><span class="paircell">' + pairIcon(t.asset || t.pair) + esc(t.asset || t.pair || '') + '</span></td><td>' + new Date(t.exitTime).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td>' + t.direction + '</td><td>' + fmt(t.entry, 4) + '</td><td>' + fmt(t.exit, 4) + '</td><td>' + fmt$(t.pnl) + '</td><td>' + fmt(t.rMultiple) + '</td><td><span class="pill ' + (t.result === 'WIN' ? 'win' : t.result === 'LOSS' ? 'loss' : 'exp') + '">' + t.result + '</span></td><td>' + esc(t.strategy || '') + '</td><td>' + esc(t.timeframe || '') + '</td></tr>').join('')
      || '<tr><td colspan="11" style="text-align:center;color:var(--dim)">Tidak ada trade.</td></tr>';
    if ($('tradePage')) $('tradePage').textContent = (lab.tradePage + 1) + ' / ' + pages;
    if ($('tradeMeta')) $('tradeMeta').textContent = String(total);
    if ($('tradeCount')) $('tradeCount').textContent = String(total);
  } catch {}
}
function exportTrades() {
  try {
    const rows = tradeRowsFiltered();
    if (!rows.length) return;
    const csv = 'n,pair,time,direction,entry,exit,profit,r,result,strategy,timeframe\n' + rows.map((t) => [t.i + 1, t.asset || t.pair, new Date(t.exitTime).toISOString(), t.direction, t.entry, t.exit, t.pnl, t.rMultiple, t.result, t.strategy, t.timeframe].join(',')).join('\n');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    a.download = 'trades.csv'; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  } catch {}
}

// ---------- MULTI-PAIR SELECTION (universe + bottom sheet) ----------
async function loadPairUniverse() {
  const prov = $('provider').value;
  if (prov === 'yahoo') lab.universe = Object.keys(YAHOO_UNIVERSE);
  else if (prov === 'demo') lab.universe = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'BNBUSDT', 'XRPUSDT', 'DOGEUSDT'];
  else lab.universe = await topPairs(prov, 50);
  // prune seleksi yang tak ada di universe? tidak — dipertahankan (user bisa ganti provider kembali)
  return lab.universe;
}
function orderedSelection() {
  const sel = lab.multiSel;
  const inUni = lab.universe.filter((s) => sel.has(s));
  const extra = [...sel].filter((s) => !lab.universe.includes(s));
  return [...inUni, ...extra];
}
function renderMultiSel() {
  try {
    const n = lab.multiSel.size;
    if ($('multiCount')) $('multiCount').textContent = String(n);
    const box = $('pairChips');
    if (box) {
      box.innerHTML = n ? orderedSelection().map((s) =>
        '<span class="pairchip"><span class="paircell">' + pairIcon(s) + esc(s) + '</span><button type="button" data-rm="' + esc(s) + '" aria-label="Hapus ' + esc(s) + '">×</button></span>').join('')
        : '<span class="muted" style="font-size:12px">Belum ada pair — tekan Select Pairs.</span>';
    }
  } catch {}
}
async function openMultiModal() {
  try {
    $('multiModal').classList.remove('hidden');
    const list = $('multiList');
    list.innerHTML = '<p class="muted">Memuat…</p>';
    try { await loadPairUniverse(); }
    catch (err) { list.innerHTML = '<p class="sub">Gagal memuat daftar: ' + esc(err.message) + ' — coba ganti provider atau Reload.</p>'; return; }
    lab.staged = new Set(lab.multiSel);
    if ($('multiSearchModal')) $('multiSearchModal').value = '';
    renderMultiList('');
  } catch {}
}
function visibleMultiPairs() {
  const q = (($('multiSearchModal')?.value) || '').toUpperCase();
  return lab.universe.filter((s) => !q || s.includes(q));
}
function renderMultiList() {
  try {
    const rows = visibleMultiPairs();
    const box = $('multiList');
    box.innerHTML = rows.map((s) =>
      '<label class="paircheck"><input type="checkbox" data-ms="' + esc(s) + '" ' + (lab.staged.has(s) ? 'checked' : '') + '><span class="paircell">' + pairIcon(s) + '<b>' + esc(s) + '</b></span><small>' + (lab.staged.has(s) ? '✓' : '') + '</small></label>').join('')
      || '<p class="sub">Tidak ketemu.</p>';
    box.onchange = (e) => {
      const c = e.target.closest('[data-ms]');
      if (!c) return;
      if (c.checked) lab.staged.add(c.dataset.ms); else lab.staged.delete(c.dataset.ms);
      if ($('multiSelCount')) $('multiSelCount').textContent = lab.staged.size + ' pairs selected';
      const sm = c.closest('.paircheck')?.querySelector('small');
      if (sm) sm.textContent = c.checked ? '✓' : '';
    };
    if ($('multiSelCount')) $('multiSelCount').textContent = lab.staged.size + ' pairs selected';
  } catch {}
}
// Combined equity: gabung semua trade pair sukses, urut exitTime, akumulasi dari modal awal.
// Net = jumlah net (urutan tak memengaruhi jumlah); MaxDD dari peak-to-trough kurva gabungan.
function buildCombinedEquity(rows, initialCapital) {
  const all = [];
  rows.forEach((r) => { if (r.res && !r.res.error) (r.res.trades || []).forEach((t) => all.push(t)); });
  all.sort((a, b) => a.exitTime - b.exitTime);
  let eq = initialCapital, peak = eq, maxDD = 0;
  const curve = [];
  all.forEach((t) => {
    eq += t.pnl;
    if (!Number.isFinite(eq)) return;
    peak = Math.max(peak, eq);
    const dd = peak > 0 ? (peak - eq) / peak : 0;
    if (dd > maxDD) maxDD = dd;
    curve.push({ t: t.exitTime, equity: eq });
  });
  return { curve, maxDDPct: maxDD * 100, net: eq - initialCapital, n: all.length };
}

// ---------- SINYAL & RIWAYAT ----------
function renderSignals() {
  try {
    const d = $('fltDir').value, tf = $('fltTf').value, q = ($('fltSearch').value || '').toUpperCase();
    const rows = state.signals.filter((s) => (!d || s.dir === d) && (!tf || s.tf === tf) && (!q || s.pair.includes(q)));
    $('tblSignals').querySelector('tbody').innerHTML = rows.slice(0, 150).map((s) =>
      '<tr data-sig="' + s.id + '"><td>' + new Date(s.t).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td><span class="paircell">' + pairIcon(s.pair) + esc(s.pair) + '</span></td><td>' + esc(s.dir) + '</td><td>' + fmt(s.price, 4) + '</td><td>' + fmt(s.sl, 4) + '</td><td>' + fmt(s.tp, 4) + '</td></tr>').join('')
      || '<tr><td colspan="6" style="text-align:center;color:var(--dim)">Belum ada sinyal.</td></tr>';
  } catch (e) { console.warn('signals:', e); }
}
function renderHist() {
  try {
    const wins = state.hist.filter((t) => t.result === 'WIN').length;
    const net = state.hist.reduce((s, t) => s + (t.pnl || 0), 0);
    $('histKpis').innerHTML = [['Trades', state.hist.length, null], ['Win', state.hist.length ? fmt((wins / state.hist.length) * 100, 1) + '%' : '—', wins * 2 >= state.hist.length], ['Net', fmt$(net), net >= 0]].map(([k, v, p]) => '<div class="kpi"><small>' + k + '</small><b class="' + (p == null ? '' : p ? 'pos' : 'neg') + '">' + v + '</b></div>').join('');
    $('tblHist').querySelector('tbody').innerHTML = state.hist.slice(0, 150).map((t) =>
      '<tr><td>' + new Date(t.exitTime || t.t).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td><span class="paircell">' + pairIcon(t.asset || t.pair) + esc(t.asset || t.pair) + '</span></td><td>' + esc(t.direction || t.dir) + '</td><td>' + fmt$(t.pnl) + '</td><td>' + esc(t.result || '—') + '</td></tr>').join('')
      || '<tr><td colspan="5" style="text-align:center;color:var(--dim)">Kosong.</td></tr>';
  } catch (e) { console.warn('hist:', e); }
}
function renderDashboard() {
  try {
    const wins = state.hist.filter((t) => t.result === 'WIN').length;
    const net = state.hist.reduce((s, t) => s + (t.pnl || 0), 0);
    $('dashKpis').innerHTML = [['Total Signals', state.signals.length, null], ['Total Trades', state.hist.length, null], ['Win Rate', state.hist.length ? fmt((wins / state.hist.length) * 100, 1) + '%' : '—', wins * 2 >= state.hist.length], ['Net PnL', fmt$(net), net >= 0]].map(([k, v, p]) => '<div class="kpi"><small>' + k + '</small><b class="' + (p == null ? '' : p ? 'pos' : 'neg') + '">' + v + '</b></div>').join('');
    const s = state.signals[0];
    $('lastSignal').innerHTML = s ? '<div class="sigcard" data-sig="' + s.id + '"><span class="paircell">' + pairIcon(s.pair) + '<b>' + esc(s.dir) + ' ' + esc(s.pair) + ' ' + esc(s.tf || '') + '</b></span><br><small style="color:var(--mut)">' + new Date(s.t).toLocaleString('id-ID') + ' · entry ' + fmt(s.price, 4) + ' · SL ' + fmt(s.sl, 4) + ' · TP ' + fmt(s.tp, 4) + '</small></div>' : '<p class="sub">Belum ada sinyal.</p>';
  } catch (e) { console.warn('dash:', e); }
}

// ---------- DETAIL modal ----------
function openDetail(title, html) {
  $('detailTitle').childNodes[0].textContent = title + ' ';
  $('detailBody').innerHTML = html;
  $('detailModal').classList.remove('hidden');
}

// ---------- INIT (setiap blok tahan gagal) ----------
function init() {
  try { $('pairLabel').innerHTML = pairIcon(state.pair) + '<span>' + esc(state.pair) + '</span>'; } catch { try { $('pairLabel').textContent = state.pair; } catch { /* abaikan */ } }
  try { renderStrategies(); $('stratSearch').oninput = renderStrategies; } catch { /* abaikan */ }
  try { renderComboChecks(); $('strategy').onchange = () => { renderComboChecks(); refreshLabMeta(); }; } catch { /* abaikan */ }
  try { renderFilterChecks(); } catch { /* abaikan */ }
  try { initCollapsibles(); } catch { /* abaikan */ }
  try { initLabUi(); } catch { /* abaikan */ }
  try { refreshLabMeta(); } catch { /* abaikan */ }
  try { renderDashboard(); renderSignals(); renderHist(); renderEng(); } catch { /* abaikan */ }
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
      if (pk) { setPair(pk.dataset.pick, pk.dataset.prov || null); show('backtest'); return; }
      const pi = e.target.closest('.pairitem');
      if (pi) { setPair(pi.dataset.sym, pi.dataset.prov || null); return; }
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
  $('mktCat').addEventListener('change', () => {
    if ($('mktCat').value === 'forex') $('mktProvider').value = 'yahoo';
    loadMarket();
  });
  // pemulihan saat provider diblokir + auto-load saat tab Market pertama dibuka
  document.addEventListener('click', (e) => {
    const f = e.target.closest ? e.target.closest('[data-mktfix]') : null;
    if (!f) return;
    const v = f.dataset.mktfix;
    if (v === 'demo') { $('mktProvider').value = 'demo'; $('mktCat').value = 'top'; }
    if (v === 'yahoo') { $('mktProvider').value = 'yahoo'; $('mktCat').value = 'forex'; }
    loadMarket();
  });
  try {
    const origShow = window.__aetherShow;
    window.__aetherShow = (n) => {
      origShow(n);
      if (n === 'market' && !state.mktLoaded) { state.mktLoaded = true; loadMarket(); }
    };
  } catch { /* abaikan */ }
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
  try { $('pairCat').addEventListener('change', openPairPicker); } catch { /* abaikan */ }
  try { $('pairManualBtn')?.addEventListener('click', () => { if ($('pairManual')?.value.trim()) setPair($('pairManual').value); }); } catch { /* input manual dihapus dari UI */ }
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

  // run (single) — dipicu tombol Run utama / quickRun
  async function doSingleRun() {
    state.cancelled = false; $('quickRun').disabled = true; $('cancel').disabled = false; $('cancel').classList.remove('hidden');
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
      setStatus(res.error ? ('Error: ' + res.error) : ('Selesai ' + Math.round(performance.now() - t0) + 'ms · ' + res.totalTrades + ' trade · Net ' + fmt$(res.netProfit) + ' · ' + source), !!res.error);
    } catch (err) {
      $('chartEmpty').style.display = 'flex';
      $('chartEmpty').textContent = 'Gagal: ' + err.message + ' — coba Demo (offline).';
      setStatus('Error: ' + err.message, true);
    } finally { $('quickRun').disabled = false; $('cancel').disabled = true; $('cancel').classList.add('hidden'); }
  }
  try { $('run')?.addEventListener('click', doSingleRun); } catch {}
  try { $('quickRun').addEventListener('click', () => { if (labMode() === 'multi') doMultiRun(); else doSingleRun(); }); } catch {}
  $('cancel').addEventListener('click', () => { state.cancelled = true; });
  // ---------- MULTI-PAIR (fixed) ----------
  // ROOT CAUSE lama: (1) chart/result hanya di-paint bila sym===pair utama -> multi tanpa pair
  // utama terlihat "tidak menghasilkan apa-apa"; (2) CSV 1-pair dipakai ulang untuk semua pair;
  // (3) tanpa progress/status per-pair; (4) tanpa agregat Overall. Engine (core) TIDAK diubah.
  function pairStatusOf(entry) {
    if (entry.err) return { key: 'nodata', label: 'NO DATA', cls: 'stat-err' };
    const res = entry.res;
    if (!res) return { key: 'failed', label: 'FAILED', cls: 'stat-err' };
    if (res.error) return { key: 'nodata', label: 'NO DATA', cls: 'stat-err' };
    if (!(res.totalTrades > 0)) return { key: 'notrades', label: 'NO TRADES', cls: 'stat-warn' };
    return { key: 'ok', label: 'SUCCESS', cls: 'stat-ok' };
  }
  function paintProgress(items, done) {
    try {
      $('labProgress').classList.remove('hidden');
      $('labProgressCount').textContent = done + ' / ' + items.length + ' completed';
      $('labProgressList').innerHTML = items.map((it) => {
        const icon = it.st === 'done-ok' ? '✓' : it.st === 'done-bad' ? '✕' : it.st === 'run' ? '…' : '·';
        const cls = it.st === 'done-ok' ? 'done' : it.st === 'done-bad' ? 'fail' : it.st === 'run' ? 'run' : 'wait';
        return '<div class="prow ' + cls + '"><span class="st">' + icon + '</span><span>' + esc(it.sym) + '</span><span style="margin-left:auto">' + esc(it.note || (it.st === 'run' ? 'processing' : it.st.startsWith('done') ? it.st : 'waiting')) + '</span></div>';
      }).join('');
    } catch {}
  }
  // Agregat trade-weighted (definisi sama dengan engine): win rate / PF / avg dari
  // GABUNGAN trade, bukan rata-rata persentase per pair. MaxDD dari kurva gabungan.
  function aggregateOverall(rows, combinedDD) {
    const ok = rows.filter((r) => r.res && !r.res.error);
    const trades = ok.flatMap((r) => r.res.trades);
    const wins = trades.filter((t) => t.result === 'WIN');
    const losses = trades.filter((t) => t.result === 'LOSS');
    const grossProfit = wins.reduce((s, t) => s + t.pnl, 0);
    const grossLossAbs = Math.abs(losses.reduce((s, t) => s + t.pnl, 0));
    const net = trades.reduce((s, t) => s + t.pnl, 0);
    const winsN = wins.length, total = trades.length;
    return {
      totalTrades: total, wins: winsN, losses: losses.length,
      winRate: total ? (winsN / total) * 100 : NaN,
      profitFactor: grossLossAbs > 0 ? grossProfit / grossLossAbs : (grossProfit > 0 ? Infinity : 0),
      netProfit: net,
      averageWin: winsN ? grossProfit / winsN : 0,
      averageLoss: losses.length ? -grossLossAbs / losses.length : 0,
      maxDD: combinedDD != null ? combinedDD : Math.max(0, ...ok.map((r) => r.res.maxDrawdownPercent || 0)),
      pairsOk: ok.length,
    };
  }
  async function runRanking(pairs) {
    const p = readParams();
    const list = pairs.slice(0, 10);
    console.log('[multi] Backtest started. Selected pairs: [' + list.join(', ') + ']');
    const items = list.map((sym) => ({ sym, st: 'wait', note: 'waiting' }));
    const rows = [];
    lab.multiCache = new Map();
    let done = 0;
    const csvActive = !!(state.csv?.length >= 60);
    if (csvActive) setStatus('CSV diabaikan saat multi-pair — mengambil data per pair.', false);
    paintProgress(items, done);
    for (const it of items) {
      if (state.cancelled) { it.st = 'wait'; it.note = 'dibatalkan'; paintProgress(items, done); break; }
      it.st = 'run'; it.note = 'processing';
      setStatus('Backtest ' + it.sym + '… (' + (done + 1) + '/' + items.length + ')', false);
      paintProgress(items, done);
      console.log('[multi] Pair started: ' + it.sym);
      try {
        // CSV guard: jangan pakai CSV 1-pair untuk multi. Setiap pair: data → indikator
        // (buildCache di dalam runBacktest) → sinyal → trade → result, independen penuh.
        const candles = await getCandles({ provider: $('provider').value, symbol: it.sym, timeframe: p.timeframe, limit: +$('limit').value });
        console.log('[multi] ' + it.sym + ' — Data loaded: ' + candles.length + ' candles');
        const res = runBacktest(candles, { ...p, asset: it.sym });
        console.log('[multi] ' + it.sym + ' — Signals filtered: ' + (res.filtered ?? 0) + ', Trades generated: ' + (res.totalTrades ?? 0));
        lab.multiCache.set(it.sym, { candles, res });
        rows.push({ sym: it.sym, res });
        const st = pairStatusOf({ res });
        it.st = st.key === 'ok' || st.key === 'notrades' ? 'done-ok' : 'done-bad';
        it.note = st.label + (res && !res.error ? (' · ' + res.totalTrades + ' tr') : '');
        console.log('[multi] Pair completed: ' + it.sym + ' (' + st.label + ')');
      } catch (err) {
        rows.push({ sym: it.sym, err: err.message });
        it.st = 'done-bad'; it.note = 'NO DATA';
        console.log('[multi] Pair failed: ' + it.sym + ' (NO DATA: ' + err.message + ')');
      }
      done++;
      paintProgress(items, done);
      await new Promise((r) => setTimeout(r, 10));
    }
    rows.sort((a, b) => (b.res?.netProfit ?? -1e18) - (a.res?.netProfit ?? -1e18));
    paintMultiResult(rows, p, done, items.length);
    // Chart+result: tampilkan pair terbaik yang punya hasil (bukan hanya pair utama),
    // agar multi-pair selalu terlihat menghasilkan sesuatu bila ada 1 saja yang sukses.
    const firstOk = rows.find((r) => r.res && !r.res.error);
    if (firstOk) {
      const cached = lab.multiCache.get(firstOk.sym);
      state.candles = cached.candles; state.params = { ...p, asset: firstOk.sym };
      paintChart(firstOk.res); paintResult(firstOk.res, $('provider').value + ' · multi');
    }
    try {
      const all = [];
      rows.forEach((r) => { if (r.res && !r.res.error) (r.res.trades || []).forEach((t) => all.push(t)); });
      all.sort((a, b) => a.exitTime - b.exitTime);
      lab.tradeRows = all.map((t, i) => ({ ...t, i }));
      lab.tradePage = 0;
      refreshTradeFilter(rows.filter((r) => r.res && !r.res.error).map((r) => r.sym));
      paintTradePage();
      // paintChart di atas menimpa equity dengan kurva pair — kembalikan kurva gabungan
      const cmb = buildCombinedEquity(rows, p.initialCapital);
      try {
        if ($('equityCap')) $('equityCap').textContent = cmb.n ? ('Combined Equity — ' + rows.filter((r) => r.res && !r.res.error).length + ' pairs · ' + cmb.n + ' trades') : 'Combined Equity — NO TRADES';
        renderEquity($('equity'), cmb.curve);
      } catch {}
    } catch {}
    const okN = rows.filter((r) => r.res && !r.res.error).length;
    setStatus(okN + ' of ' + rows.length + ' pairs processed successfully' + (state.cancelled ? ' (dibatalkan).' : '.'), okN === 0);
    console.log('[multi] Done: ' + okN + ' of ' + rows.length + ' pairs processed successfully');
    try { $('labProgressCount').textContent = done + ' / ' + items.length + ' completed'; } catch {}
    return rows;
  }
  function paintMultiResult(rows, p, done, total) {
    try {
      const combined = buildCombinedEquity(rows, p.initialCapital);
      const agg = aggregateOverall(rows, combined.maxDDPct);
      const okN = rows.filter((r) => r.res && !r.res.error).length;
      const pfTxt = agg.profitFactor === Infinity ? '∞' : (Number.isFinite(agg.profitFactor) ? fmt(agg.profitFactor) : '—');
      const wrTxt = agg.totalTrades === 0 ? 'NO TRADES' : (Number.isFinite(agg.winRate) ? fmt(agg.winRate, 1) + '%' : '—');
      if ($('pairsMeta')) $('pairsMeta').textContent = okN + ' of ' + rows.length + ' pairs';
      $('multiOut').innerHTML =
        '<h3 style="font-size:13px">Overall — ' + okN + ' of ' + rows.length + ' pairs processed successfully</h3>' +
        '<div class="kpis">' +
        [['Total Trades', String(agg.totalTrades), null], ['Win Rate', wrTxt, null], ['Profit Factor', pfTxt, null], ['Net Profit', fmt$(agg.netProfit), agg.netProfit >= 0], ['Max DD', fmt(agg.maxDD) + '%', null], ['Avg Win', fmt$(agg.averageWin), null], ['Avg Loss', fmt$(agg.averageLoss), null]].map(([k, v, c]) => '<div class="kpi"><small>' + k + '</small><b class="' + (c == null ? '' : c ? 'pos' : 'neg') + '">' + esc(v) + '</b></div>').join('') + '</div>' +
        '<h3 style="font-size:13px;margin-top:10px">Performance by Pair (klik baris = lihat chart)</h3><div class="scrollx"><table class="tbl"><thead><tr><th>Pair</th><th>Status</th><th>Net</th><th>Win</th><th>PF</th><th>DD%</th><th>Tr</th></tr></thead><tbody>' +
        rows.map(({ sym, res, err }) => {
          const st = pairStatusOf({ sym, res, err });
          if (err) return '<tr data-msym="' + esc(sym) + '"><td>' + esc(sym) + '</td><td><span class="' + st.cls + '">NO DATA</span></td><td colspan="5" style="color:#E8A0A0">' + esc(err).slice(0, 90) + '</td></tr>';
          if (res.error) return '<tr data-msym="' + esc(sym) + '"><td>' + esc(sym) + '</td><td><span class="' + st.cls + '">NO DATA</span></td><td colspan="5">' + esc(res.error).slice(0, 90) + '</td></tr>';
          const win = res.totalTrades ? (fmt(res.winRate, 1) + '%') : 'NO TRADES';
          return '<tr data-msym="' + esc(sym) + '"><td>' + esc(sym) + '</td><td><span class="' + st.cls + '">' + st.label + '</span></td><td>' + fmt$(res.netProfit) + '</td><td>' + win + '</td><td>' + (res.profitFactor >= 999 ? '∞' : fmt(res.profitFactor)) + '</td><td>' + fmt(res.maxDrawdownPercent) + '%</td><td>' + res.totalTrades + '</td></tr>';
        }).join('') + '</tbody></table></div>' +
        '<p class="desc" style="margin-top:8px">Combined equity dari ' + agg.totalTrades + ' trade · MaxDD gabungan ' + fmt(agg.maxDD) + '% · filter aktif ' + (p.filters?.length || 0) + '. Klik baris pair untuk chart + equity per-pair.</p>';
      // Combined Equity Curve (jujur dari penjumlahan trade, tanpa ubah rumus)
      try {
        if ($('equityCap')) $('equityCap').textContent = combined.n ? ('Combined Equity — ' + okN + ' pairs · ' + combined.n + ' trades') : 'Combined Equity — NO TRADES';
        renderEquity($('equity'), combined.curve);
      } catch {}
      const tb = $('multiOut').querySelector('tbody');
      if (tb) tb.onclick = (e) => {
        const tr = e.target.closest('tr[data-msym]');
        if (!tr) return;
        const hit = lab.multiCache.get(tr.dataset.msym);
        if (!hit) return;
        state.candles = hit.candles; state.params = { ...p, asset: tr.dataset.msym };
        paintChart(hit.res); paintResult(hit.res, $('provider').value + ' · multi › ' + tr.dataset.msym);
        try { if ($('equityCap')) $('equityCap').textContent = tr.dataset.msym + ' equity (per-pair)'; } catch {}
        const el = document.querySelector('#tab-backtest .lab-sec[data-sec="chart"] .lab-sec-b');
        if (el && el.classList.contains('hidden')) el.classList.remove('hidden');
        document.getElementById('resAnchor')?.scrollIntoView({ block: 'start' });
      };
    } catch (e) { console.warn('multi paint:', e); }
  }
  // Kompat lama: daftar inline diganti bottom-sheet (universe di memori)
  async function loadMultiList() { try { await loadPairUniverse(); renderMultiSel(); } catch {} }
  async function doMultiRun() {
    if (!lab.universe.length) { try { await loadPairUniverse(); } catch (err) { setStatus('Error: ' + err.message, true); return; } }
    const sel = orderedSelection();
    if (!sel.length) { setStatus('Please select at least one pair.', true); return; }
    console.log('[multi] Backtest started (mode Multi). Selected pairs: [' + sel.join(', ') + ']');
    state.cancelled = false; $('quickRun').disabled = true; $('cancel').disabled = false; $('cancel').classList.remove('hidden'); $('multiOut').innerHTML = '';
    try { await runRanking(sel); }
    catch (err) { setStatus('BACKTEST FAILED: ' + err.message, true); }
    finally { $('quickRun').disabled = false; $('cancel').disabled = true; $('cancel').classList.add('hidden'); }
  }

  try { $('runAll')?.addEventListener('click', async () => {
    setLabMode('multi');
    try {
      if (!lab.universe.length) await loadPairUniverse();
      const top5 = lab.universe.slice(0, 5);
      lab.multiSel = new Set(top5);
      store.set('aether_multipair', [...lab.multiSel]);
      renderMultiSel(); refreshLabMeta();
      await doMultiRun();
    } catch (err) { setStatus('Error: ' + err.message, true); }
  }); } catch {}
  try { $('btnMulti')?.addEventListener('click', () => setLabMode(labMode() === 'multi' ? 'single' : 'multi')); } catch {}
  try { $('multiReload')?.addEventListener('click', loadMultiList); } catch {}
  try { $('multiTop5')?.addEventListener('click', async () => {
    try {
      if (!lab.universe.length) await loadPairUniverse();
      lab.multiSel = new Set(lab.universe.slice(0, 5));
      store.set('aether_multipair', [...lab.multiSel]);
      renderMultiSel(); refreshLabMeta();
    } catch (err) { setStatus('Error: ' + err.message, true); }
  }); } catch {}
  try { $('multiRun')?.addEventListener('click', doMultiRun); } catch {}

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

  // bot engine terpadu (Start Bot Dashboard == Start Dry Run)
  async function startEngine() {
    if (!state.engPairs.size) {
      $('dryStatus').textContent = 'Pilih pair dulu (☑ Pair).';
      try { $('botStatus').textContent = 'Pilih pair dulu (☑ Pair).'; } catch {}
      return;
    }
    try { engCfg(); } catch (err) {
      $('dryStatus').textContent = 'Config error: ' + err.message;
      try { $('botStatus').textContent = 'Config error: ' + err.message; } catch {}
      return;
    }
    try { if ('Notification' in window && Notification.permission === 'default') await Notification.requestPermission(); } catch { /* abaikan */ }
    state.eng.running = true;
    syncEngButtons();
    await engTick(true);
    clearInterval(state.eng.timer);
    state.eng.timer = setInterval(() => engTick(false), 90000);
  }
  function stopEngine() {
    state.eng.running = false;
    clearInterval(state.eng.timer);
    syncEngButtons();
    renderEng();
  }
  function syncEngButtons() {
    const r = state.eng.running;
    try {
      $('btnStartBot').classList.toggle('hidden', r); $('btnStopBot').classList.toggle('hidden', !r);
      $('botBadge').textContent = r ? 'RUN' : 'STOP';
      try { $('dryBadge').textContent = r ? 'RUN' : 'STOP'; } catch {}
    } catch { /* abaikan */ }
  }
  $('btnStartBot').addEventListener('click', startEngine);
  $('btnStopBot').addEventListener('click', stopEngine);

  // ---------- DRY RUN (paper-trading ala freqtrade) ----------
  function engCfg() {
    const p = readParams(); // strategi + combo + filter + risiko sama dengan Backtest
    return p;
  }
  function paperLevels(entry, dir, cache, idx, p) {
    if (p.useAtr && cache.atr14[idx] != null && cache.atr14[idx] > 0) {
      const a = cache.atr14[idx];
      if (dir === 'LONG') return { sl: entry - a * p.atrSlMult, tp: entry + a * p.atrTpMult };
      return { sl: entry + a * p.atrTpMult, tp: entry - a * p.atrTpMult };
    }
    if (dir === 'LONG') return { sl: entry * (1 - p.slPercent), tp: entry * (1 + p.tpPercent) };
    return { sl: entry * (1 + p.slPercent), tp: entry * (1 - p.tpPercent) };
  }
  function renderEng() {
    try {
      try { $('engCount').textContent = state.engPairs.size; } catch {}
      const d = state.eng;
      $('tblDryOpen').querySelector('tbody').innerHTML = d.positions.map((o) =>
        '<tr><td>' + esc(o.pair) + '</td><td>' + o.dir + '</td><td>' + fmt(o.entry, 5) + '</td><td>' + fmt(o.mark ?? o.entry, 5) + '</td><td>' + fmt$((o.upl ?? 0)) + '</td><td>' + fmt(o.sl, 5) + '</td><td>' + fmt(o.tp, 5) + '</td></tr>').join('')
        || '<tr><td colspan="7" style="text-align:center;color:var(--dim)">Kosong.</td></tr>';
      $('dryClosedCount').textContent = d.closed.length;
      $('tblDryClosed').querySelector('tbody').innerHTML = d.closed.slice(0, 120).map((t) =>
        '<tr><td>' + new Date(t.exitT).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</td><td>' + esc(t.pair) + '</td><td>' + t.dir + '</td><td>' + fmt$(t.pnl) + '</td><td><span class="pill ' + (t.result === 'WIN' ? 'win' : t.result === 'LOSS' ? 'loss' : 'exp') + '">' + t.result + '</span></td></tr>').join('')
        || '<tr><td colspan="5" style="text-align:center;color:var(--dim)">Belum ada.</td></tr>';
      const net = d.closed.reduce((s, t) => s + (t.pnl || 0), 0);
      const txt = d.running
        ? 'Jalan · equity $' + fmt((d.equity ?? 0)) + ' · net ' + fmt$(net) + ' · scan ' + new Date().toLocaleTimeString('id-ID')
        : 'Berhenti · equity $' + fmt((d.equity ?? 0)) + ' · net ' + fmt$(net) + '.';
      $('dryStatus').textContent = txt;
      try { $('botStatus').textContent = txt; } catch {}
    } catch (e) { console.warn('dry render:', e); }
  }
  async function engTick(manual) {
    const d = state.eng;
    if (!d.running && !manual) return;
    let p;
    try { p = engCfg(); }
    catch (err) { $('dryStatus').textContent = 'Dry run error: ' + err.message; return; }
    if (d.equity == null) d.equity = p.initialCapital;
    const pairs = [...state.engPairs];
    if (!pairs.length) { $('dryStatus').textContent = 'Pilih pair dry-run dulu.'; return; }
    const prov = $('provider').value;
    // FIX v3.32: ctx filter berbasis waktu per pair agar cooldown/dup konsisten dengan backtest
    state.engFctx = state.engFctx || {};
    let tfMs = 900000;
    try { tfMs = parseTimeframe(p.timeframe) * 60000; } catch { /* default 15m */ }
    for (const sym of pairs) {
      try {
        const raw = await getCandles({ provider: prov, symbol: sym, timeframe: p.timeframe, limit: 200 });
        const candles = normalizeCandles(raw);
        const cache = buildCache(candles);
        const last = candles[candles.length - 1];
        const open = d.positions.find((o) => o.pair === sym);
        if (open) {
          open.mark = last.c;
          open.upl = (open.dir === 'LONG' ? last.c - open.entry : open.entry - last.c) * open.qty;
          // cek SL/TP pada candle baru sejak entry (konservatif: SL dulu)
          const fresh = candles.filter((c) => c.t > open.entryT);
          for (const b of fresh) {
            const slHit = open.dir === 'LONG' ? b.l <= open.sl : b.h >= open.sl;
            const tpHit = open.dir === 'LONG' ? b.h >= open.tp : b.l <= open.tp;
            if (!slHit && !tpHit) continue;
            const win = tpHit && !slHit;
            const exit = win
              ? (open.dir === 'LONG' ? open.tp * (1 - p.slippagePercent) : open.tp * (1 + p.slippagePercent))
              : (open.dir === 'LONG' ? open.sl * (1 - p.slippagePercent) : open.sl * (1 + p.slippagePercent));
            const gross = (open.dir === 'LONG' ? exit - open.entry : open.entry - exit) * open.qty;
            const fees = (open.entry * open.qty + exit * open.qty) * p.feePercent;
            const pnl = gross - fees;
            d.equity += pnl;
            d.positions = d.positions.filter((o) => o !== open);
            d.closed.unshift({ pair: sym, dir: open.dir, entry: open.entry, exit, pnl, result: win ? 'WIN' : 'LOSS', entryT: open.entryT, exitT: b.t });
            d.closed = d.closed.slice(0, 300);
            state.engFctx[sym] = { ...(state.engFctx[sym] || {}), lastExitT: b.t, tfMs };
            pushSignal({ pair: sym, tf: p.timeframe, dir: open.dir, price: exit, sl: open.sl, tp: open.tp, t: b.t, src: 'dryrun-' + (win ? 'TP' : 'SL') });
            break;
          }
        } else {
          const i = candles.length - 1;
          const dec = decideAt(candles, i, cache, p);
          if (!dec.passed) continue;
          const fx = state.engFctx[sym] || {};
          state.engFctx[sym] = { ...fx, lastSigT: last.t, lastSigDir: dec.direction, tfMs };
          if ((p.filters || []).length) {
            const fr = applyFilters(candles, i, cache, dec.direction, p.filters, { ...fx, tfMs });
            if (!fr.passed) continue;
          }
          let entry = last.c;
          entry = dec.direction === 'LONG' ? entry * (1 + p.slippagePercent) : entry * (1 - p.slippagePercent);
          const lv = paperLevels(entry, dec.direction, cache, i, p);
          if (!Number.isFinite(lv.sl) || !Number.isFinite(lv.tp) || lv.sl <= 0 || lv.tp <= 0) continue;
          const riskDist = Math.abs(entry - lv.sl);
          if (!(riskDist > 0)) continue;
          let qty = (d.equity * p.riskPerTrade) / riskDist;
          const maxNot = d.equity * p.leverage;
          if (qty * entry > maxNot) qty = maxNot / entry;
          if (!(qty > 0)) continue;
          d.positions.push({ pair: sym, dir: dec.direction, entry, entryT: last.t, sl: lv.sl, tp: lv.tp, qty, mark: last.c, upl: 0 });
          pushSignal({ pair: sym, tf: p.timeframe, dir: dec.direction, price: entry, sl: lv.sl, tp: lv.tp, t: last.t, src: 'dryrun-entry' });
        }
      } catch { /* lanjut pair berikut */ }
    }
    store.set('aether_dryclosed', d.closed);
    store.set('aether_dryequity', d.equity);
    renderEng();
  }
  async function loadEngList(boxId, searchId) {
    const box = $(boxId || 'engChecks');
    box.innerHTML = '<p class="sub">Memuat…</p>';
    try {
      const prov = $('provider').value;
      const pairs = prov === 'yahoo' ? Object.keys(YAHOO_UNIVERSE) : await topPairs(prov, 12);
      const render = (f) => {
        box.innerHTML = pairs.filter((s) => !f || s.includes(f)).map((s) =>
          '<label><input type="checkbox" value="' + esc(s) + '" ' + (state.engPairs.has(s) ? 'checked' : '') + '> <span class="paircell">' + pairIcon(s) + esc(s) + '</span></label>').join('');
      };
      render('');
      $(searchId || 'engSearch').oninput = (e) => render(e.target.value.toUpperCase());
      box.onchange = () => {
        state.engPairs = new Set([...box.querySelectorAll('input:checked')].map((c) => c.value));
        store.set('aether_engpairs', [...state.engPairs]);
        renderEng();
      };
    } catch (err) { box.innerHTML = '<p class="sub">Gagal: ' + esc(err.message) + '</p>'; }
  }

  // engine pair picker (satu-satunya di dashboard)
  $('engPairsBtn').addEventListener('click', () => { $('engPickBox').classList.toggle('hidden'); if (!$('engPickBox').classList.contains('hidden')) loadEngList('engChecks', 'engSearch'); });
  $('engReload').addEventListener('click', () => loadEngList('engChecks', 'engSearch'));
  $('dryClear').addEventListener('click', () => {
    state.eng.positions = []; state.eng.closed = []; state.eng.equity = null;
    store.set('aether_dryclosed', []); store.set('aether_dryequity', null);
    renderEng(); renderDashboard();
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
  $('btnTestConn').addEventListener('click', async () => {
    const tb = $('tblConn').querySelector('tbody');
    $('connStatus').textContent = 'Mengetes…';
    tb.innerHTML = '';
    const tests = [
      ['Binance', async () => { const j = await topPairs('binance', 1); if (!j.length) throw new Error('daftar kosong'); const h = binanceActiveHost() || ''; return j[0] + (h.includes('vision') ? ' via Vision' : ' via .com'); }],
      ['Bybit', async () => { const j = await topPairs('bybit', 1); if (!j.length) throw new Error('daftar kosong'); return j[0]; }],
      ['Yahoo Forex', async () => { const c = await getCandles({ provider: 'yahoo', symbol: 'EUR/USD', timeframe: '1h', limit: 60 }); return c.length + 'c EUR/USD'; }],
      ['Demo', async () => { const c = await getCandles({ provider: 'demo', symbol: 'BTCUSDT', timeframe: '15m', limit: 60 }); return c.length + 'c lokal'; }],
    ];
    for (const [name, fn] of tests) {
      const t0 = performance.now();
      try {
        const info = await fn();
        tb.innerHTML += '<tr><td>' + name + '</td><td><span class="pill win">OK</span></td><td>' + esc(info) + ' · ' + Math.round(performance.now() - t0) + 'ms</td></tr>';
      } catch (err) {
        tb.innerHTML += '<tr><td>' + name + '</td><td><span class="pill loss">GAGAL</span></td><td>' + esc(String(err.message || err).slice(0, 120)) + '</td></tr>';
      }
    }
    $('connStatus').textContent = 'Selesai. Yang GAGAL berarti diblokir jaringan/perangkat — pakai yang OK.';
  });

  // laporan fix
  try {
    $('fixReport').innerHTML = [
      ['v3.32 — Audit engine total (1719 cek)', 'Harness <b>tests/audit_engine.mjs</b>: 25 strategi × 3 seed + combo OR/AND/MAJORITY + 15 filter satuan & gabungan + flat/spike/mini/no-volume + determinisme + leverage ekstrem — invarian: final=modal+net, streak=total, winRate, maxDD 0–100%, metrik finite, equityCurve konsisten, level SL/TP searah, exit≥entry. <b>0 gagal.</b> Temuan & fix: (1) <b>cache localStorage tak pernah hit</b> (cek Array salah) — diperbaiki; (2) <b>filter cooldown/dup mati di live engine</b> — kini ctx berbasis waktu per pair; (3) <b>mode ATR tak pernah entry di dry-run</b> (mult ATR tak diteruskan) — diperbaiki; (4) sinyal & riwayat duplikat saat run ulang — dedup kunci alami. E2E browser: backtest 200c selesai + 8 KPI render, 0 error.'],
      ['v3.28 — Market tetap kosong (AKAR + FIX)', 'Hasil curl: <b>api.binance.com → HTTP 451</b> (blokir wilayah) dan <b>Bybit → diblokir CloudFront per negara</b>. Fix: Binance kini lewat <b>failover host otomatis .com → data-api.binance.vision (CORS *, tanpa geo-block) → .us</b>, host yang jalan diingat sesi ini. Teruji live: top pairs + klines via Vision. Tes Koneksi kini menampilkan host aktif. Jika Bybit tetap GAGAL = wajar (geo-block/CORS) → pakai Binance/Yahoo/Demo.'],
      ['v3.27 — Market tak muncul (FIX)', 'Daftar pair kini dirender <b>langsung</b> (tak menunggu harga), harga diisi <b>progresif per-batch</b> dengan progres n/N. Bila daftar pun gagal → box pemulihan 1-ketuk (<b>Demo / Yahoo / Coba lagi</b>). Tab Market juga <b>auto-load</b> saat pertama dibuka. Catatan: Binance/Bybit sering <b>diblokir jaringan/ISP di ID</b> — gunakan <b>Tes Koneksi</b> di Settings untuk memastikan.'],
      ['v3.27 — Bot + Dry Run = 1 engine', 'Start di Dashboard <b>sama persis</b> dengan Start di Dry Run: 1 set pair, 1 status, 1 badge, 1 interval (90 dtk), sinyal + posisi paper dari konfigurasi Backtest yang sama.'],
      ['v3.27 — Bersih-bersih UI', 'Header judul + sub “Sama seperti kartu…” dihapus sesuai permintaan. Versi tampil di footer & laporan saja.'],
      ['v3.26 — Yahoo diperbaiki (TERUJI live)', 'Akar: range 3 bulan untuk 15m ditolak Yahoo (<b>HTTP 422</b>) — padahal 15m adalah default app. Fix: range aman per-TF + <b>failover query1→query2 + retry</b> + pesan error spesifik (422/429). Teruji: EUR/USD, USD/IDR, XAU/USD di 15m/1h/1d.'],
      ['v3.26 — Logo & icon pair', '<b>Logo app baru</b> (sinyal-pulse gradient, SVG + icon launcher Android + favicon). Setiap pair kini berlogo: <b>crypto</b> (logo asli via CDN + fallback offline), <b>mata uang</b> (bendera + fallback kode), <b>XAU/XAG</b> (lambang Au/Ag). Tampil di Market, pair picker, multi-select, Sinyal, Riwayat & Dry Run.'],
      ['v3.26 — Redesign modern & simple', 'Sistem desain baru: permukaan solid tenang, radius konsisten, tombol tegas, KPI ringkas, tabel header-lengket, bottom-nav pill melayang, sheet modal bergagang, skeleton loading, ikon berlingkaran. Fungsi & alur 100% sama.'],
      ['v3.25 — Multi-pair manual', 'Backtest: tombol <b>☑ Multi-pair manual</b> → centang pair (maks 10) → ranking Net/Win/PF/DD + kolom Filter×. Daftar mengikuti provider aktif (termasuk Yahoo forex).'],
      ['v3.25 — Dry Run', 'Halaman <b>Dry Run</b> (paper-trading ala freqtrade): pakai konfigurasi Backtest + filter di <b>multi-pair pilihan manual</b>, posisi paper dibuka/ditutup otomatis (SL-dulu, fee+slippage), mark-to-market tiap 90 detik, equity & hasil tersimpan lokal. Tanpa uang asli.'],
      ['v3.25 — 15 Filter APK', 'FilterEngine dipetakan 1:1 dari APK: <b>Trend, EMA55, HTF Trend, Volume, ATR Volatility, ADX, RSI, Market Structure, S/R, Liquidity Sweep, Trading Session (UTC), Min/Max Volatility, Cooldown, Duplicate Protection</b> — berlaku di Backtest, multi-pair, Dry Run & Bot. Kolom “Tersaring filter” di hasil.'],
      ['v3.25 — Provider Yahoo (Forex, Metal & XAU)', 'Provider <b>Yahoo (Forex, Metal & XAU)</b> meniru YahooMarketProvider APK: EUR/USD, GBP/USD, USD/JPY, AUD/USD, USD/CAD, USD/CHF, NZD/USD, <b>USD/IDR</b>, USD/SGD, USD/MYR, USD/INR, USD/CNY, USD/KRW, EUR/GBP, EUR/JPY, GBP/JPY, USD/TRY, USD/ZAR, EUR/IDR, XAU/USD, XAG/USD, spot metal, BTC/ETH-USD. Pair picker & Market Hub berkategori: Crypto Top / Mata Uang, Metal & XAU.'],
      ['v3.24 — Tab Strategi & Market mati', 'Akar: ES-module via file:// diblokir WebView → app.js gagal total. Fix: flag WebView + fallback tab inline + dropdown hardcode + try/catch per halaman.'],
      ['Engine & chart', 'close[i]→open[i+1], SL-dulu se-candle, fee 2 sisi + slippage, sizing risiko-tetap + clamp leverage, dedup timestamp, min 60, expiry→EXPIRED, Sharpe/Sortino/Calmar/CAGR/exposure; chart kunci-Y + label dinamis.'],
    ].map(([t, d]) => '<details open><summary>' + t + '</summary><p class="sub">' + d + '</p></details>').join('');
  } catch { /* abaikan */ }
}

try { init(); } catch (e) {
  // Tab tetap hidup via fallback inline walau init gagal
  console.warn('init partial:', e);
  try { setStatus('Sebagian modul gagal: ' + e.message + ' — tab tetap bisa dibuka.', true); } catch { /* abaikan */ }
}
