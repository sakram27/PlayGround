/* Data layer: Binance + Bybit + Demo + CSV, dengan cache localStorage + validasi jujur. */
'use strict';
import { parseTimeframe } from './core.js';

const cache = new Map();
function ck(provider, symbol, tf, limit) { return provider + '|' + symbol + '|' + tf + '|' + limit; }

export const PROVIDERS = [
  { id: 'binance', name: 'Binance', spot: true },
  { id: 'bybit', name: 'Bybit', spot: true },
  { id: 'demo', name: 'Demo (Offline)', spot: true },
];

const BYBIT_TF = { '1m': '1', '3m': '3', '5m': '5', '15m': '15', '30m': '30', '1h': '60', '2h': '120', '4h': '240', '6h': '360', '12h': '720', '1d': 'D', '1w': 'W' };

async function fetchJSON(url, timeoutMs = 15000) {
  const ctl = new AbortController();
  const to = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { signal: ctl.signal });
    if (!r.ok) throw new Error('HTTP ' + r.status + ' dari ' + new URL(url).hostname);
    return await r.json();
  } finally { clearTimeout(to); }
}

export async function getCandles({ provider = 'binance', symbol = 'BTCUSDT', timeframe = '15m', limit = 500 } = {}) {
  symbol = String(symbol).toUpperCase().replace(/[^A-Z0-9]/g, '');
  parseTimeframe(timeframe);
  limit = Math.min(1000, Math.max(60, Math.round(limit) || 500));
  const key = ck(provider, symbol, timeframe, limit);
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
  if (provider === 'demo') {
    const { genDemoCandles } = await import('./core.js');
    candles = genDemoCandles(symbol.length * 777 + timeframe.length * 131, limit, guessPrice(symbol), parseTimeframe(timeframe));
  } else if (provider === 'binance') {
    const j = await fetchJSON('https://api.binance.com/api/v3/klines?symbol=' + encodeURIComponent(symbol) + '&interval=' + encodeURIComponent(timeframe) + '&limit=' + limit);
    if (!Array.isArray(j) || !j.length) throw new Error('Binance mengembalikan data kosong untuk ' + symbol + ' ' + timeframe + '. Coba simbol lain (mis. BTCUSDT, ETHUSDT, SOLUSDT).');
    candles = j.map((k) => ({ t: k[0], o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[5] }));
  } else if (provider === 'bybit') {
    const tf = BYBIT_TF[timeframe.toLowerCase()];
    if (!tf) throw new Error('Bybit tidak mendukung timeframe ' + timeframe + '. Gunakan 1m/5m/15m/1h/4h/1d/1w.');
    const j = await fetchJSON('https://api.bybit.com/v5/market/kline?category=spot&symbol=' + encodeURIComponent(symbol) + '&interval=' + encodeURIComponent(tf) + '&limit=' + limit);
    const list = j?.result?.list;
    if (!Array.isArray(list) || !list.length) throw new Error('Bybit mengembalikan data kosong untuk ' + symbol + '. Pastikan simbol spot valid (mis. BTCUSDT).');
    candles = list.map((k) => ({ t: +k[0], o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[5] })).reverse();
  } else throw new Error('Provider tidak dikenal: ' + provider);

  // validasi cepat sebelum cache
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
