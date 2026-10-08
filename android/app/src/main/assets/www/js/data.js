/* Data layer: Binance + Bybit + Yahoo (forex/mata uang, metal & XAU) + Demo + CSV.
 * Yahoo meniru YahooMarketProvider APK (universe FOREX + XAU/XAG, query1.finance.yahoo.com).
 */
'use strict';
import { parseTimeframe } from './core.js';

const cache = new Map();
function ck(provider, symbol, tf, limit) { return provider + '|' + symbol + '|' + tf + '|' + limit; }

export const PROVIDERS = [
  { id: 'binance', name: 'Binance (Crypto)', spot: true },
  { id: 'bybit', name: 'Bybit (Crypto)', spot: true },
  { id: 'yahoo', name: 'Yahoo (Forex & Metal)', spot: false },
  { id: 'demo', name: 'Demo (Offline)', spot: true },
];

const BYBIT_TF = { '1m': '1', '3m': '3', '5m': '5', '15m': '15', '30m': '30', '1h': '60', '2h': '120', '4h': '240', '6h': '360', '12h': '720', '1d': 'D', '1w': 'W' };

/* Universe Yahoo — 9 bawaan APK + tambahan mata uang (termasuk IDR) + spot metal */
export const YAHOO_UNIVERSE = {
  'EUR/USD': 'EURUSD=X', 'GBP/USD': 'GBPUSD=X', 'USD/JPY': 'USDJPY=X', 'AUD/USD': 'AUDUSD=X',
  'USD/CAD': 'USDCAD=X', 'USD/CHF': 'USDCHF=X', 'NZD/USD': 'NZDUSD=X',
  'USD/IDR': 'IDR=X', 'USD/SGD': 'SGD=X', 'USD/MYR': 'MYR=X', 'USD/INR': 'INR=X',
  'USD/CNY': 'CNY=X', 'USD/KRW': 'KRW=X', 'EUR/GBP': 'EURGBP=X', 'EUR/JPY': 'EURJPY=X',
  'GBP/JPY': 'GBPJPY=X', 'USD/TRY': 'TRY=X', 'USD/ZAR': 'ZAR=X', 'EUR/IDR': 'EURIDR=X',
  'XAU/USD': 'GC=F', 'XAG/USD': 'SI=F', 'XAU Spot': 'XAUUSD=X', 'XAG Spot': 'XAGUSD=X',
  'BTC/USD': 'BTC-USD', 'ETH/USD': 'ETH-USD',
};
export function yahooSymbol(labelOrCode) {
  const s = String(labelOrCode || '').trim().toUpperCase().replace(/[\s_]/g, '');
  if (YAHOO_UNIVERSE[labelOrCode]) return YAHOO_UNIVERSE[labelOrCode];
  for (const [k, v] of Object.entries(YAHOO_UNIVERSE)) {
    if (k.replace(/[\s_/]/g, '').toUpperCase() === s) return v;
  }
  // pola umum: EURUSD / EURUSD=X / EUR/USD
  let core = s.replace(/=X$/, '').replace('/', '');
  if (/^[A-Z]{6}$/.test(core) && /USD|EUR|GBP|JPY|IDR/.test(core)) return core + '=X';
  if (/^[A-Z]{6,7}-USD$/.test(s)) return s;
  return null;
}
const YAHOO_INTERVAL = { '1m': '1m', '5m': '5m', '15m': '15m', '30m': '30m', '1h': '60m', '4h': '60m', '1d': '1d', '1w': '1wk' };
const YAHOO_RANGE = { '1m': '5d', '5m': '1mo', '15m': '3mo', '30m': '3mo', '1h': '6mo', '4h': '1y', '1d': '2y', '1w': '5y' };

function resample(candles, tfMin) {
  // gabung N candle 1h -> 1 candle 4h
  const per = Math.max(1, Math.round(tfMin / 60));
  if (per <= 1) return candles;
  const out = [];
  for (let i = 0; i < candles.length; i += per) {
    const g = candles.slice(i, i + per);
    if (!g.length) continue;
    out.push({ t: g[0].t, o: g[0].o, h: Math.max(...g.map((c) => c.h)), l: Math.min(...g.map((c) => c.l)), c: g[g.length - 1].c, v: g.reduce((s, c) => s + (c.v || 0), 0) });
  }
  return out;
}

async function fetchJSON(url, timeoutMs = 15000) {
  const ctl = new AbortController();
  const to = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { signal: ctl.signal, headers: { 'User-Agent': 'Mozilla/5.0 AetherSignalBot/3.24' } });
    if (!r.ok) throw new Error('HTTP ' + r.status + ' dari ' + new URL(url).hostname);
    return await r.json();
  } finally { clearTimeout(to); }
}

async function yahooCandles(symbol, timeframe, limit) {
  const ysym = yahooSymbol(symbol);
  if (!ysym) throw new Error('Yahoo tidak mengenal pair "' + symbol + '". Pilih dari daftar Forex (mis. EUR/USD, USD/IDR, XAU/USD).');
  const tf = timeframe.toLowerCase();
  const interval = YAHOO_INTERVAL[tf] || '15m';
  const range = YAHOO_RANGE[tf] || '3mo';
  const j = await fetchJSON('https://query1.finance.yahoo.com/v8/finance/chart/' + encodeURIComponent(ysym) + '?interval=' + interval + '&range=' + range + '&includePrePost=false');
  const res = j?.chart?.result?.[0];
  const ts = res?.timestamp || [];
  const q = res?.indicators?.quote?.[0] || {};
  if (!ts.length) throw new Error('Yahoo mengembalikan data kosong untuk ' + symbol + ' (' + ysym + '). Coba timeframe lain.');
  let out = [];
  for (let i = 0; i < ts.length; i++) {
    const o = q.open?.[i], h = q.high?.[i], l = q.low?.[i], c = q.close?.[i];
    if (![o, h, l, c].every(Number.isFinite)) continue;
    out.push({ t: ts[i] * 1000, o, h, l, c, v: Number.isFinite(q.volume?.[i]) ? q.volume[i] : 0 });
  }
  if (tf === '4h') out = resample(out, 240);
  out = out.slice(-Math.max(limit, 60));
  if (out.length < 60) throw new Error('Yahoo hanya memberi ' + out.length + ' candle untuk ' + symbol + ' ' + timeframe + ' (butuh ≥60). Coba timeframe lebih kecil.');
  return out;
}

export async function getCandles({ provider = 'binance', symbol = 'BTCUSDT', timeframe = '15m', limit = 500 } = {}) {
  const rawSymbol = String(symbol || '').trim();
  parseTimeframe(timeframe);
  limit = Math.min(1000, Math.max(60, Math.round(limit) || 500));
  const key = ck(provider, rawSymbol.toUpperCase(), timeframe, limit);
  if (cache.has(key)) return cache.get(key);
  try {
    const ls = localStorage.getItem('aether_cache_' + key);
    if (ls) {
      const parsed = JSON.parse(ls);
      if (Array.isArray(parsed) && parsed.length >= 60 && (Date.now() - parsed._at < 5 * 60 * 1000)) {
        cache.set(key, parsed.candles);
        return parsed.candles;
      }
    }
  } catch { /* abaikan */ }

  let candles;
  const sym = rawSymbol.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (provider === 'demo') {
    const { genDemoCandles } = await import('./core.js');
    candles = genDemoCandles(symbol.length * 777 + timeframe.length * 131, limit, guessPrice(symbol), parseTimeframe(timeframe));
  } else if (provider === 'binance') {
    if (!sym) throw new Error('Pilih pair dulu dari listview.');
    const j = await fetchJSON('https://api.binance.com/api/v3/klines?symbol=' + encodeURIComponent(sym) + '&interval=' + encodeURIComponent(timeframe) + '&limit=' + limit);
    if (!Array.isArray(j) || !j.length) throw new Error('Binance mengembalikan data kosong untuk ' + sym + ' ' + timeframe + '. Cek penulisan simbol.');
    candles = j.map((k) => ({ t: k[0], o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[5] }));
  } else if (provider === 'bybit') {
    if (!sym) throw new Error('Pilih pair dulu dari listview.');
    const tf = BYBIT_TF[timeframe.toLowerCase()];
    if (!tf) throw new Error('Bybit tidak mendukung timeframe ' + timeframe + '. Gunakan 1m/5m/15m/1h/4h/1d/1w.');
    const j = await fetchJSON('https://api.bybit.com/v5/market/kline?category=spot&symbol=' + encodeURIComponent(sym) + '&interval=' + encodeURIComponent(tf) + '&limit=' + limit);
    const list = j?.result?.list;
    if (!Array.isArray(list) || !list.length) throw new Error('Bybit mengembalikan data kosong untuk ' + sym + '. Pastikan simbol spot valid.');
    candles = list.map((k) => ({ t: +k[0], o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[5] })).reverse();
  } else if (provider === 'yahoo') {
    candles = await yahooCandles(rawSymbol, timeframe, limit);
  } else throw new Error('Provider tidak dikenal: ' + provider);

  const bad = candles.filter((c) => ![c.o, c.h, c.l, c.c].every(Number.isFinite));
  if (bad.length) throw new Error('Provider mengembalikan ' + bad.length + ' candle rusak. Coba refresh.');
  cache.set(key, candles);
  try { localStorage.setItem('aether_cache_' + key, JSON.stringify({ _at: Date.now(), candles })); } catch { /* abaikan */ }
  return candles;
}

function guessPrice(sym) {
  if (sym.includes('BTC')) return 67000;
  if (sym.includes('ETH')) return 3500;
  if (sym.includes('SOL')) return 170;
  if (sym.includes('BNB')) return 590;
  return 100;
}

export function parseCSV(text) {
  const lines = String(text).split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (!lines.length) throw new Error('File CSV kosong.');
  const out = [];
  const start = /[a-zA-Z]/.test(lines[0].split(',')[0]) ? 1 : 0; // lewati header
  for (let i = start; i < lines.length; i++) {
    const p = lines[i].split(/[,;\t]/).map((s) => s.trim());
    if (p.length < 5) continue;
    let t = Date.parse(p[0]);
    if (!Number.isFinite(t)) t = Number(p[0]) * (String(p[0]).length <= 10 ? 1000 : 1);
    const o = +p[1], h = +p[2], l = +p[3], c = +p[4], v = p[5] != null && p[5] !== '' ? +p[5] : 0;
    if (![t, o, h, l, c].every(Number.isFinite)) continue;
    out.push({ t, o, h, l, c, v: Number.isFinite(v) ? v : 0 });
  }
  if (out.length < 60) throw new Error('CSV hanya menghasilkan ' + out.length + ' candle valid (butuh ≥60). Format: time,open,high,low,close,volume.');
  return out;
}

export async function topPairs(provider = 'binance', limit = 12) {
  if (provider === 'demo') return ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'BNBUSDT', 'XRPUSDT', 'DOGEUSDT'].slice(0, limit);
  if (provider === 'yahoo') return Object.keys(YAHOO_UNIVERSE).slice(0, limit);
  try {
    if (provider === 'bybit') {
      const j = await fetchJSON('https://api.bybit.com/v5/market/tickers?category=spot');
      const list = j?.result?.list || [];
      return list.filter((x) => x.symbol.endsWith('USDT')).sort((a, b) => (+b.turnover24h || 0) - (+a.turnover24h || 0)).slice(0, limit).map((x) => x.symbol);
    }
    const j = await fetchJSON('https://api.binance.com/api/v3/ticker/24hr');
    return j.filter((x) => x.symbol.endsWith('USDT')).sort((a, b) => (+b.quoteVolume || 0) - (+a.quoteVolume || 0)).slice(0, limit).map((x) => x.symbol);
  } catch { return ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'BNBUSDT']; }
}

