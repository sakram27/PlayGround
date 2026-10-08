/* AetherSignalBot Web v3.23 — freqtrade-grade backtest core.
 * Port & perbaikan dari com.aether.signal.core.backtest.BacktestEngine (Kotlin).
 * Tanpa lookahead: sinyal di close[i] dieksekusi di open[i+1].
 * Konservatif: jika SL & TP tersentuh dalam 1 candle yang sama -> SL dulu (rugi).
 */
'use strict';

export const MIN_CANDLES = 60;
export const WARMUP = 60;
export const DEFAULT_MAX_HOLDING = 100;

export function parseTimeframe(tf) {
  if (typeof tf !== 'string') throw new Error('Timeframe harus string, contoh: 15m / 1h / 4h / 1d / 1w');
  const s = tf.trim().toLowerCase();
  const m = /^(\d+)\s*([mhdw])$/.exec(s);
  if (!m) throw new Error('Timeframe tidak valid: "' + tf + '". Gunakan format 15m, 1h, 4h, 1d, 1w.');
  const n = parseInt(m[1], 10);
  if (!Number.isFinite(n) || n <= 0) throw new Error('Timeframe tidak valid: "' + tf + '".');
  const unit = m[2];
  const minutes = unit === 'm' ? n : unit === 'h' ? n * 60 : unit === 'd' ? n * 1440 : n * 10080;
  if (minutes <= 0) throw new Error('Timeframe tidak valid: "' + tf + '".');
  return minutes;
}

export function tfSeconds(tf) {
  return parseTimeframe(tf) * 60;
}

export function sanitizeParams(raw) {
  const p = Object.assign({
    asset: 'BTCUSDT', timeframe: '15m', initialCapital: 1000,
    riskPerTrade: 0.01, leverage: 1, feePercent: 0.0005, slippagePercent: 0.0002,
    slPercent: 0.015, tpPercent: 0.03, maxHolding: DEFAULT_MAX_HOLDING,
    strategy: 'ema_trend', strategyParams: {}, combo: null,
    startDate: 0, endDate: 0, useAtr: false, atrSlMult: 1.5, atrTpMult: 3.0,
  }, raw || {});
  p.asset = String(p.asset || 'BTCUSDT').toUpperCase().replace(/[^A-Z0-9]/g, '') || 'BTCUSDT';
  p.timeframe = String(p.timeframe || '15m');
  parseTimeframe(p.timeframe); // validasi, throw bila salah
  p.initialCapital = num(p.initialCapital, 1000);
  if (!(p.initialCapital > 0) || !Number.isFinite(p.initialCapital)) throw new Error('Modal awal harus > 0.');
  p.riskPerTrade = num(p.riskPerTrade, 0.01);
  p.riskPerTrade = clamp(p.riskPerTrade, 0.001, 0.10);
  p.leverage = Math.round(num(p.leverage, 1));
  p.leverage = clamp(p.leverage, 1, 100);
  p.feePercent = clamp(num(p.feePercent, 0.0005), 0, 0.02);
  p.slippagePercent = clamp(num(p.slippagePercent, 0.0002), 0, 0.02);
  p.slPercent = clamp(num(p.slPercent, 0.015), 0.0005, 0.5);
  p.tpPercent = clamp(num(p.tpPercent, 0.03), 0.0005, 1.0);
  p.maxHolding = Math.round(clamp(num(p.maxHolding, DEFAULT_MAX_HOLDING), 1, 2000));
  p.atrSlMult = clamp(num(p.atrSlMult, 1.5), 0.2, 10);
  p.atrTpMult = clamp(num(p.atrTpMult, 3.0), 0.2, 20);
  p.startDate = Math.round(num(p.startDate, 0));
  p.endDate = Math.round(num(p.endDate, 0));
  if (p.startDate && p.endDate && p.endDate <= p.startDate) throw new Error('Tanggal akhir harus sesudah tanggal awal.');
  p.strategy = String(p.strategy || 'ema_trend');
  if (!STRATEGIES[p.strategy]) throw new Error('Strategi tidak dikenal: ' + p.strategy);
  // Filter ala APK (FilterConfig: name + enabled + params). Default: semua mati.
  const rawF = Array.isArray(raw?.filters) ? raw.filters : (Array.isArray(p.filters) ? p.filters : []);
  p.filters = [];
  for (const f of rawF) {
    if (!f || !FILTERS[f.name]) continue;
    const def = FILTERS[f.name].defaults;
    const merged = { ...def, ...((f.params && typeof f.params === 'object') ? f.params : {}) };
    p.filters.push({ name: f.name, enabled: f.enabled !== false, params: merged });
  }
  return p;
}

function num(v, dflt) {
  if (v === '' || v === null || v === undefined) return dflt;
  const n = typeof v === 'number' ? v : parseFloat(String(v).replace(',', '.'));
  return Number.isFinite(n) ? n : dflt;
}
function clamp(v, a, b) { return Math.min(b, Math.max(a, v)); }

/* ---------- validasi & normalisasi candle ---------- */
export function normalizeCandles(input) {
  if (!Array.isArray(input)) throw new Error('Data candle harus array.');
  const out = [];
  const seen = new Set();
  for (const c of input) {
    const t = Math.round(Number(c.t ?? c.timestamp ?? c[0]));
    const o = Number(c.o ?? c.open ?? c[1]);
    const h = Number(c.h ?? c.high ?? c[2]);
    const l = Number(c.l ?? c.low ?? c[3]);
    const cl = Number(c.c ?? c.close ?? c[4]);
    const v = Number(c.v ?? c.volume ?? c[5] ?? 0);
    if (!Number.isFinite(t) || t <= 0) continue;
    if (![o, h, l, cl].every(Number.isFinite)) {
      throw new Error('Data candle tidak valid (mengandung nilai non-finite/rusak).');
    }
    if (h < Math.max(o, cl, l) - 1e-12 || l > Math.min(o, cl, h) + 1e-12) {
      throw new Error('Data candle tidak valid (high < max(open,close) atau low > min(open,close)).');
    }
    if (seen.has(t)) continue; // dedup timestamp (FIX v3.22: duplikat bikin sinyal ganda)
    seen.add(t);
    out.push({ t, o, h, l, c: cl, v: Number.isFinite(v) && v >= 0 ? v : 0 });
  }
  out.sort((a, b) => a.t - b.t);
  return out;
}

export function filterByDate(candles, startDate, endDate) {
  return candles.filter((c) => (!startDate || c.t >= startDate) && (!endDate || c.t <= endDate));
}

/* ---------- indikator ---------- */
export function ema(values, period) {
  const out = new Array(values.length).fill(null);
  if (values.length < period || period < 1) return out;
  const k = 2 / (period + 1);
  let prev = 0;
  for (let i = 0; i < values.length; i++) {
    if (i + 1 < period) continue;
    if (i + 1 === period) {
      let s = 0;
      for (let j = i - period + 1; j <= i; j++) s += values[j];
      prev = s / period;
    } else {
      prev = values[i] * k + prev * (1 - k);
    }
    out[i] = prev;
  }
  return out;
}
export function sma(values, period) {
  const out = new Array(values.length).fill(null);
  if (period < 1) return out;
  let s = 0;
  for (let i = 0; i < values.length; i++) {
    s += values[i];
    if (i >= period) s -= values[i - period];
    if (i + 1 >= period) out[i] = s / period;
  }
  return out;
}
export function rsi(closes, period = 14) {
  const out = new Array(closes.length).fill(null);
  if (closes.length <= period) return out;
  let gain = 0, loss = 0;
  for (let i = 1; i <= period; i++) {
    const d = closes[i] - closes[i - 1];
    if (d >= 0) gain += d; else loss -= d;
  }
  gain /= period; loss /= period;
  out[period] = loss === 0 ? 100 : 100 - 100 / (1 + gain / loss);
  for (let i = period + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1];
    gain = (gain * (period - 1) + Math.max(d, 0)) / period;
    loss = (loss * (period - 1) + Math.max(-d, 0)) / period;
    out[i] = loss === 0 ? 100 : 100 - 100 / (1 + gain / loss);
  }
  return out;
}
export function atr(candles, period = 14) {
  const out = new Array(candles.length).fill(null);
  if (candles.length <= period) return out;
  const trs = [];
  for (let i = 0; i < candles.length; i++) {
    if (i === 0) trs.push(candles[0].h - candles[0].l);
    else trs.push(Math.max(
      candles[i].h - candles[i].l,
      Math.abs(candles[i].h - candles[i - 1].c),
      Math.abs(candles[i].l - candles[i - 1].c),
    ));
  }
  let a = trs.slice(0, period).reduce((x, y) => x + y, 0) / period;
  out[period - 1] = a;
  for (let i = period; i < candles.length; i++) a = out[i] = (a * (period - 1) + trs[i]) / period;
  return out;
}
export function macd(closes, fast = 12, slow = 26, signal = 9) {
  const ef = ema(closes, fast), es = ema(closes, slow);
  const line = closes.map((_, i) => (ef[i] == null || es[i] == null ? null : ef[i] - es[i]));
  const valid = line.filter((v) => v != null);
  const sigValid = ema(valid, signal);
  const sig = new Array(closes.length).fill(null);
  let k = 0;
  for (let i = 0; i < closes.length; i++) {
    if (line[i] != null) { sig[i] = sigValid[k++] ?? null; }
  }
  return { line, signal: sig, hist: line.map((v, i) => (v == null || sig[i] == null ? null : v - sig[i])) };
}
export function bollinger(closes, period = 20, mult = 2) {
  const mid = sma(closes, period);
  return {
    mid,
    upper: closes.map((_, i) => {
      if (mid[i] == null) return null;
      let s = 0;
      for (let j = i - period + 1; j <= i; j++) s += (closes[j] - mid[i]) ** 2;
      return mid[i] + mult * Math.sqrt(s / period);
    }),
    lower: closes.map((_, i) => {
      if (mid[i] == null) return null;
      let s = 0;
      for (let j = i - period + 1; j <= i; j++) s += (closes[j] - mid[i]) ** 2;
      return mid[i] - mult * Math.sqrt(s / period);
    }),
  };
}
export function stochastic(candles, kPeriod = 14, dPeriod = 3) {
  const k = new Array(candles.length).fill(null);
  for (let i = kPeriod - 1; i < candles.length; i++) {
    let hh = -Infinity, ll = Infinity;
    for (let j = i - kPeriod + 1; j <= i; j++) { hh = Math.max(hh, candles[j].h); ll = Math.min(ll, candles[j].l); }
    k[i] = hh === ll ? 50 : ((candles[i].c - ll) / (hh - ll)) * 100;
  }
  const kv = k.map((v) => v ?? 50);
  const dRaw = sma(kv, dPeriod);
  return { k, d: k.map((v, i) => (v == null ? null : dRaw[i])) };
}
export function supertrend(candles, period = 10, mult = 3) {
  const a = atr(candles, period);
  const dir = new Array(candles.length).fill(0); // 1 bull, -1 bear
  const line = new Array(candles.length).fill(null);
  let up = 0, dn = 0;
  for (let i = 0; i < candles.length; i++) {
    if (a[i] == null) continue;
    const hl2 = (candles[i].h + candles[i].l) / 2;
    const nbUp = hl2 - mult * a[i], nbDn = hl2 + mult * a[i];
    if (i === 0 || a[i - 1] == null) { up = nbUp; dn = nbDn; }
    else {
      up = (candles[i - 1].c > up) ? Math.max(nbUp, up) : nbUp;
      dn = (candles[i - 1].c < dn) ? Math.min(nbDn, dn) : nbDn;
    }
    if (i === 0 || dir[i - 1] === 0) dir[i] = candles[i].c >= (up + dn) / 2 ? 1 : -1;
    else if (dir[i - 1] === 1 && candles[i].c < up) dir[i] = -1;
    else if (dir[i - 1] === -1 && candles[i].c > dn) dir[i] = 1;
    else dir[i] = dir[i - 1];
    line[i] = dir[i] === 1 ? up : dn;
  }
  return { dir, line };
}
export function vwap(candles) {
  const out = new Array(candles.length).fill(null);
  let pv = 0, vv = 0;
  const day = new Date(candles[0]?.t || 0).getUTCDate();
  let curDay = day;
  for (let i = 0; i < candles.length; i++) {
    const d = new Date(candles[i].t).getUTCDate();
    if (d !== curDay) { pv = 0; vv = 0; curDay = d; }
    const tp = (candles[i].h + candles[i].l + candles[i].c) / 3;
    pv += tp * candles[i].v; vv += candles[i].v;
    out[i] = vv > 0 ? pv / vv : candles[i].c;
  }
  return out;
}
export function adx(candles, period = 14) {
  const out = new Array(candles.length).fill(null);
  if (candles.length <= period * 2) return out;
  let spDM = 0, smDM = 0, sTR = 0;
  const dxs = [];
  for (let i = 1; i < candles.length; i++) {
    const upM = candles[i].h - candles[i - 1].h;
    const dnM = candles[i - 1].l - candles[i].l;
    const pDM = upM > dnM && upM > 0 ? upM : 0;
    const mDM = dnM > upM && dnM > 0 ? dnM : 0;
    const tr = Math.max(candles[i].h - candles[i].l, Math.abs(candles[i].h - candles[i - 1].c), Math.abs(candles[i].l - candles[i - 1].c));
    if (i <= period) { spDM += pDM; smDM += mDM; sTR += tr; }
    else {
      spDM = spDM - spDM / period + pDM; smDM = smDM - smDM / period + mDM; sTR = sTR - sTR / period + tr;
      const pDI = sTR === 0 ? 0 : (spDM / sTR) * 100;
      const mDI = sTR === 0 ? 0 : (smDM / sTR) * 100;
      dxs.push(pDI + mDI === 0 ? 0 : (Math.abs(pDI - mDI) / (pDI + mDI)) * 100);
    }
  }
  let adxV = dxs.slice(0, period).reduce((a, b) => a + b, 0) / period;
  for (let i = 0; i < dxs.length; i++) {
    if (i < period - 1) continue;
    if (i === period - 1) out[period * 2 - 1] = adxV;
    else { adxV = (adxV * (period - 1) + dxs[i]) / period; out[period + 1 + i] = adxV; }
  }
  return out;
}

/* ---------- cache indikator per-run ---------- */
export function buildCache(candles) {
  const closes = candles.map((c) => c.c);
  const vols = candles.map((c) => c.v);
  const m = macd(closes);
  const bb = bollinger(closes);
  const st = stochastic(candles);
  const stt = supertrend(candles);
  return {
    closes,
    e20: ema(closes, 20), e50: ema(closes, 50), e200: ema(closes, 200),
    e55: ema(closes, 55), e9: ema(closes, 9), e21: ema(closes, 21),
    s20: sma(closes, 20), s50: sma(closes, 50), sVol20: sma(vols.map((v) => v || 0), 20),
    rsi14: rsi(closes, 14), atr14: atr(candles, 14), adx14: adx(candles, 14),
    macd: m, bb, stoch: st, st: stt, vw: vwap(candles),
  };
}

/* ---------- 25 strategi (nama stabil = kunci registry) ---------- */
function sig(dir, confidence, reasons) { return { direction: dir, confidence, reasons }; }
const NONE = () => sig('NONE', 0, []);

export const STRATEGIES = {
  ema_trend: { name: 'EMA Trend', desc: 'Close > EMA50 + EMA20 > EMA50 = LONG; sebaliknya SHORT.', fn(c, i, X, P) {
    if (i < 50 || X.e20[i] == null || X.e50[i] == null) return NONE();
    const bull = c[i].c > X.e50[i] && X.e20[i] > X.e50[i];
    const bear = c[i].c < X.e50[i] && X.e20[i] < X.e50[i];
    if (bull) return sig('LONG', 0.6, ['close>EMA50', 'EMA20>EMA50']);
    if (bear) return sig('SHORT', 0.6, ['close<EMA50', 'EMA20<EMA50']);
    return NONE();
  } },
  ema_cross: { name: 'EMA Cross 9/21', desc: 'Golden/death cross EMA9 vs EMA21.', fn(c, i, X) {
    if (i < 22 || X.e9[i] == null || X.e21[i] == null || X.e9[i - 1] == null || X.e21[i - 1] == null) return NONE();
    if (X.e9[i - 1] <= X.e21[i - 1] && X.e9[i] > X.e21[i]) return sig('LONG', 0.65, ['EMA9 cross-up EMA21']);
    if (X.e9[i - 1] >= X.e21[i - 1] && X.e9[i] < X.e21[i]) return sig('SHORT', 0.65, ['EMA9 cross-down EMA21']);
    return NONE();
  } },
  rsi: { name: 'RSI Reversal', desc: 'RSI<30 jenuh jual (LONG), RSI>70 jenuh beli (SHORT).', fn(c, i, X, P) {
    const os = P.oversold ?? 30, ob = P.overbought ?? 70;
    const r = X.rsi14[i]; if (r == null) return NONE();
    if (r < os && c[i].c > c[i - 1].c) return sig('LONG', 0.6, ['RSI oversold ' + r.toFixed(1)]);
    if (r > ob && c[i].c < c[i - 1].c) return sig('SHORT', 0.6, ['RSI overbought ' + r.toFixed(1)]);
    return NONE();
  } },
  macd: { name: 'MACD', desc: 'Histogram cross nol searah tren EMA50.', fn(c, i, X) {
    const h = X.macd.hist; if (i < 27 || h[i] == null || h[i - 1] == null) return NONE();
    const up = X.e50[i] != null && c[i].c > X.e50[i];
    const dn = X.e50[i] != null && c[i].c < X.e50[i];
    if (h[i - 1] <= 0 && h[i] > 0 && up) return sig('LONG', 0.62, ['MACD cross-up']);
    if (h[i - 1] >= 0 && h[i] < 0 && dn) return sig('SHORT', 0.62, ['MACD cross-down']);
    return NONE();
  } },
  bollinger: { name: 'Bollinger Mean-Revert', desc: 'Reject lower band = LONG; reject upper = SHORT.', fn(c, i, X) {
    if (i < 20 || X.bb.lower[i] == null) return NONE();
    if (c[i].l <= X.bb.lower[i] && c[i].c > X.bb.lower[i]) return sig('LONG', 0.58, ['lower-band reject']);
    if (c[i].h >= X.bb.upper[i] && c[i].c < X.bb.upper[i]) return sig('SHORT', 0.58, ['upper-band reject']);
    return NONE();
  } },
  stochastic: { name: 'Stochastic', desc: 'Cross %K/%D di zona ekstrem.', fn(c, i, X) {
    const { k, d } = X.stoch; if (i < 16 || k[i] == null || d[i] == null || k[i - 1] == null || d[i - 1] == null) return NONE();
    if (k[i - 1] <= d[i - 1] && k[i] > d[i] && k[i] < 30) return sig('LONG', 0.6, ['stoch cross-up <30']);
    if (k[i - 1] >= d[i - 1] && k[i] < d[i] && k[i] > 70) return sig('SHORT', 0.6, ['stoch cross-down >70']);
    return NONE();
  } },
  adx: { name: 'ADX Trend', desc: 'ADX>25 + close di sisi EMA20 yang benar.', fn(c, i, X, P) {
    const a = X.adx14[i]; if (a == null || X.e20[i] == null) return NONE();
    const th = P.adxMin ?? 25;
    if (a < th) return NONE();
    if (c[i].c > X.e20[i]) return sig('LONG', 0.55, ['ADX ' + a.toFixed(1)]);
    if (c[i].c < X.e20[i]) return sig('SHORT', 0.55, ['ADX ' + a.toFixed(1)]);
    return NONE();
  } },
  supertrend: { name: 'Supertrend', desc: 'Flip arah supertrend.', fn(c, i, X) {
    if (i < 11) return NONE();
    if (X.st.dir[i] === 1 && X.st.dir[i - 1] === -1) return sig('LONG', 0.66, ['supertrend flip bull']);
    if (X.st.dir[i] === -1 && X.st.dir[i - 1] === 1) return sig('SHORT', 0.66, ['supertrend flip bear']);
    return NONE();
  } },
  vwap: { name: 'VWAP Revert', desc: 'Deviasi jauh dari VWAP + wick reject.', fn(c, i, X) {
    if (i < 5 || X.vw[i] == null) return NONE();
    const dev = (c[i].c - X.vw[i]) / X.vw[i];
    if (dev < -0.004 && c[i].c > c[i].o) return sig('LONG', 0.55, ['di bawah VWAP, reject']);
    if (dev > 0.004 && c[i].c < c[i].o) return sig('SHORT', 0.55, ['di atas VWAP, reject']);
    return NONE();
  } },
  support_resistance: { name: 'Support / Resistance', desc: 'Bounce dari swing 20-candle.', fn(c, i) {
    if (i < 21) return NONE();
    let hh = -Infinity, ll = Infinity;
    for (let j = i - 20; j < i; j++) { hh = Math.max(hh, c[j].h); ll = Math.min(ll, c[j].l); }
    if (Math.abs(c[i].l - ll) / ll < 0.001 && c[i].c > c[i].o) return sig('LONG', 0.55, ['support bounce']);
    if (Math.abs(c[i].h - hh) / hh < 0.001 && c[i].c < c[i].o) return sig('SHORT', 0.55, ['resistance reject']);
    return NONE();
  } },
  breakout: { name: 'Breakout 20', desc: 'Close menembus Donchian 20 + volume confirm.', fn(c, i, X) {
    if (i < 21) return NONE();
    let hh = -Infinity, ll = Infinity;
    for (let j = i - 20; j < i; j++) { hh = Math.max(hh, c[j].h); ll = Math.min(ll, c[j].l); }
    const avgV = X.sVol20[i] || 0;
    if (c[i].c > hh && c[i].v >= avgV * 1.2) return sig('LONG', 0.63, ['breakout high20 vol-ok']);
    if (c[i].c < ll && c[i].v >= avgV * 1.2) return sig('SHORT', 0.63, ['breakdown low20 vol-ok']);
    return NONE();
  } },
  pullback: { name: 'Pullback EMA21', desc: 'Tren EMA50 + pullback ke EMA21 + engulfing.', fn(c, i, X) {
    if (i < 51 || X.e21[i] == null || X.e50[i] == null) return NONE();
    const upTrend = X.e21[i] > X.e50[i];
    const dnTrend = X.e21[i] < X.e50[i];
    const nearEma = Math.abs(c[i].l - X.e21[i]) / X.e21[i] < 0.003 || Math.abs(c[i].h - X.e21[i]) / X.e21[i] < 0.003;
    const bullEng = c[i].c > c[i].o && c[i].c >= c[i - 1].o && c[i].o <= c[i - 1].c;
    const bearEng = c[i].c < c[i].o && c[i].o >= c[i - 1].c && c[i].c <= c[i - 1].o;
    if (upTrend && nearEma && bullEng) return sig('LONG', 0.62, ['pullback bull']);
    if (dnTrend && nearEma && bearEng) return sig('SHORT', 0.62, ['pullback bear']);
    return NONE();
  } },
  market_structure: { name: 'Market Structure', desc: 'HH/HL = LONG bias, LH/LL = SHORT bias.', fn(c, i) {
    if (i < 6) return NONE();
    const hh = c[i - 2].h < c[i].h && c[i - 4]?.h < c[i - 2].h;
    const hl = c[i - 2].l < c[i].l && c[i - 4]?.l < c[i - 2].l;
    const lh = c[i - 2].h > c[i].h && c[i - 4]?.h > c[i - 2].h;
    const ll = c[i - 2].l > c[i].l && c[i - 4]?.l > c[i - 2].l;
    if ((hh || hl) && c[i].c > c[i].o) return sig('LONG', 0.56, ['struktur bullish']);
    if ((lh || ll) && c[i].c < c[i].o) return sig('SHORT', 0.56, ['struktur bearish']);
    return NONE();
  } },
  bos: { name: 'BOS', desc: 'Break of Structure searah candle momentum.', fn(c, i) {
    if (i < 11) return NONE();
    let hh = -Infinity, ll = Infinity;
    for (let j = i - 10; j < i; j++) { hh = Math.max(hh, c[j].h); ll = Math.min(ll, c[j].l); }
    const body = Math.abs(c[i].c - c[i].o) / c[i].o;
    if (c[i].c > hh && body > 0.002) return sig('LONG', 0.6, ['BOS up']);
    if (c[i].c < ll && body > 0.002) return sig('SHORT', 0.6, ['BOS down']);
    return NONE();
  } },
  choch: { name: 'CHoCH', desc: 'Change of character + close kembali.', fn(c, i, X) {
    if (i < 12 || X.e21[i] == null) return NONE();
    const wasDn = c[i - 2].c < X.e21[i - 2];
    const nowUp = c[i].c > X.e21[i] && c[i].c > c[i - 1].h;
    const wasUp = c[i - 2].c > X.e21[i - 2];
    const nowDn = c[i].c < X.e21[i] && c[i].c < c[i - 1].l;
    if (wasDn && nowUp) return sig('LONG', 0.6, ['CHoCH bull']);
    if (wasUp && nowDn) return sig('SHORT', 0.6, ['CHoCH bear']);
    return NONE();
  } },
  liquidity_sweep: { name: 'Liquidity Sweep', desc: 'Sweep low/high + reclaim cepat.', fn(c, i) {
    if (i < 21) return NONE();
    let ll = Infinity, hh = -Infinity;
    for (let j = i - 20; j < i; j++) { ll = Math.min(ll, c[j].l); hh = Math.max(hh, c[j].h); }
    if (c[i].l < ll && c[i].c > ll && c[i].c > c[i].o) return sig('LONG', 0.61, ['sweep low reclaim']);
    if (c[i].h > hh && c[i].c < hh && c[i].c < c[i].o) return sig('SHORT', 0.61, ['sweep high reclaim']);
    return NONE();
  } },
  order_block: { name: 'Order Block', desc: 'Impuls + kembali ke zona OB + reject.', fn(c, i) {
    if (i < 6) return NONE();
    const impUp = (c[i - 3].c - c[i - 3].o) / c[i - 3].o > 0.006;
    const impDn = (c[i - 3].o - c[i - 3].c) / c[i - 3].o > 0.006;
    if (impUp && c[i].l <= c[i - 3].o && c[i].c > c[i].o) return sig('LONG', 0.57, ['OB bull retap']);
    if (impDn && c[i].h >= c[i - 3].o && c[i].c < c[i].o) return sig('SHORT', 0.57, ['OB bear retap']);
    return NONE();
  } },
  fvg: { name: 'Fair Value Gap', desc: 'Gap 3-candle + fill separuh + lanjut.', fn(c, i) {
    if (i < 4) return NONE();
    const gapUp = c[i - 2].l > c[i - 3].h;
    const gapDn = c[i - 2].h < c[i - 3].l;
    if (gapUp && c[i].l <= (c[i - 2].l + c[i - 3].h) / 2 && c[i].c > c[i].o) return sig('LONG', 0.56, ['FVG bull']);
    if (gapDn && c[i].h >= (c[i - 2].h + c[i - 3].l) / 2 && c[i].c < c[i].o) return sig('SHORT', 0.56, ['FVG bear']);
    return NONE();
  } },
  breaker: { name: 'Breaker Block', desc: 'BOS gagal (false break) + kembali dalam range.', fn(c, i) {
    if (i < 12) return NONE();
    let hh = -Infinity, ll = Infinity;
    for (let j = i - 11; j < i - 1; j++) { hh = Math.max(hh, c[j].h); ll = Math.min(ll, c[j].l); }
    if (c[i - 1].h > hh && c[i].c < hh && c[i].c < c[i].o) return sig('SHORT', 0.58, ['breaker bear']);
    if (c[i - 1].l < ll && c[i].c > ll && c[i].c > c[i].o) return sig('LONG', 0.58, ['breaker bull']);
    return NONE();
  } },
  fibonacci: { name: 'Fibonacci', desc: 'Retrace 0.5–0.618 dari swing 30 + reject.', fn(c, i) {
    if (i < 31) return NONE();
    let hh = -Infinity, ll = Infinity;
    for (let j = i - 30; j < i; j++) { hh = Math.max(hh, c[j].h); ll = Math.min(ll, c[j].l); }
    const range = hh - ll; if (range <= 0) return NONE();
    const rUp = (hh - c[i].c) / range; // retrace dari high (tren naik)
    const rDn = (c[i].c - ll) / range; // retrace dari low (tren turun)
    if (rUp > 0.45 && rUp < 0.68 && c[i].c > c[i].o && c[i - 1].c < c[i].c) return sig('LONG', 0.55, ['fib golden bull']);
    if (rDn > 0.45 && rDn < 0.68 && c[i].c < c[i].o && c[i - 1].c > c[i].c) return sig('SHORT', 0.55, ['fib golden bear']);
    return NONE();
  } },
  smc_basic: { name: 'SMC Basic', desc: 'BOS + displacement searah.', fn(c, i) {
    if (i < 11) return NONE();
    let hh = -Infinity, ll = Infinity;
    for (let j = i - 10; j < i; j++) { hh = Math.max(hh, c[j].h); ll = Math.min(ll, c[j].l); }
    const disp = Math.abs(c[i].c - c[i].o) / c[i].o;
    const range = hh - ll;
    if (c[i].c > hh && disp > 0.003 && range > 0) return sig('LONG', 0.6, ['SMC BOS+displacement']);
    if (c[i].c < ll && disp > 0.003 && range > 0) return sig('SHORT', 0.6, ['SMC BOS+displacement']);
    return NONE();
  } },
  ict_setup: { name: 'ICT Setup', desc: 'Sweep + market-structure-shift + entry.', fn(c, i, X) {
    if (i < 21) return NONE();
    let ll = Infinity, hh = -Infinity;
    for (let j = i - 20; j < i; j++) { ll = Math.min(ll, c[j].l); hh = Math.max(hh, c[j].h); }
    const sweepLow = c[i - 1].l < ll;
    const sweepHigh = c[i - 1].h > hh;
    if (sweepLow && c[i].c > c[i - 1].h && c[i].c > c[i].o) return sig('LONG', 0.62, ['ICT sweep+mss']);
    if (sweepHigh && c[i].c < c[i - 1].l && c[i].c < c[i].o) return sig('SHORT', 0.62, ['ICT sweep+mss']);
    return NONE();
  } },
  volume: { name: 'Volume Spike', desc: 'Lonjakan volume 2x + body kuat.', fn(c, i, X) {
    if (i < 21) return NONE();
    const avg = X.sVol20[i]; if (!avg) return NONE();
    const body = (c[i].c - c[i].o) / c[i].o;
    if (c[i].v > avg * 2 && body > 0.003) return sig('LONG', 0.57, ['vol spike bull']);
    if (c[i].v > avg * 2 && body < -0.003) return sig('SHORT', 0.57, ['vol spike bear']);
    return NONE();
  } },
  mtf_confirm: { name: 'MTF Confirm', desc: 'EMA50 + RSI filter (proxy multi-timeframe).', fn(c, i, X) {
    if (i < 50 || X.e50[i] == null || X.rsi14[i] == null) return NONE();
    if (c[i].c > X.e50[i] && X.rsi14[i] > 55 && X.rsi14[i] < 75) return sig('LONG', 0.58, ['MTF bull confirm']);
    if (c[i].c < X.e50[i] && X.rsi14[i] < 45 && X.rsi14[i] > 25) return sig('SHORT', 0.58, ['MTF bear confirm']);
    return NONE();
  } },
  donchian_momentum: { name: 'Donchian Momentum', desc: 'Mid-channel cross + RSI momentum (ke-25, pelengkap freqtrade).', fn(c, i, X) {
    if (i < 21 || X.rsi14[i] == null) return NONE();
    let hh = -Infinity, ll = Infinity;
    for (let j = i - 20; j < i; j++) { hh = Math.max(hh, c[j].h); ll = Math.min(ll, c[j].l); }
    const mid = (hh + ll) / 2;
    if (c[i].c > mid && c[i - 1].c <= mid && X.rsi14[i] > 50) return sig('LONG', 0.57, ['donchian mid cross-up']);
    if (c[i].c < mid && c[i - 1].c >= mid && X.rsi14[i] < 50) return sig('SHORT', 0.57, ['donchian mid cross-down']);
    return NONE();
  } },
};

/* ---------- decision (single + combo) ---------- */
export function decideAt(candles, idx, cache, params) {
  const combo = params.combo;
  if (!combo || !Array.isArray(combo.strategies) || combo.strategies.length === 0) {
    const s = STRATEGIES[params.strategy];
    const r = s.fn(candles, idx, cache, params.strategyParams || {});
    return { passed: r.direction !== 'NONE', direction: r.direction, confidence: r.confidence, reasons: r.reasons, strategy: params.strategy };
  }
  const mode = combo.mode || 'OR';
  const results = combo.strategies.map((name) => {
    const s = STRATEGIES[name];
    if (!s) return { direction: 'NONE', confidence: 0 };
    const r = s.fn(candles, idx, cache, params.strategyParams || {});
    return { name, ...r };
  });
  const longs = results.filter((r) => r.direction === 'LONG');
  const shorts = results.filter((r) => r.direction === 'SHORT');
  if (mode === 'AND') {
    if (longs.length === results.length) return { passed: true, direction: 'LONG', confidence: avg(longs.map((r) => r.confidence)), reasons: longs.flatMap((r) => r.reasons), strategy: combo.strategies.join('+') };
    if (shorts.length === results.length) return { passed: true, direction: 'SHORT', confidence: avg(shorts.map((r) => r.confidence)), reasons: shorts.flatMap((r) => r.reasons), strategy: combo.strategies.join('+') };
    return { passed: false, direction: 'NONE', confidence: 0, reasons: [], strategy: combo.strategies.join('+') };
  }
  // OR / MAJORITY
  const need = mode === 'MAJORITY' ? Math.floor(results.length / 2) + 1 : 1;
  if (longs.length >= need && longs.length >= shorts.length) {
    return { passed: true, direction: 'LONG', confidence: avg(longs.map((r) => r.confidence)), reasons: longs.flatMap((r) => r.reasons), strategy: longs.map((r) => r.name).join('+') };
  }
  if (shorts.length >= need && shorts.length > longs.length) {
    return { passed: true, direction: 'SHORT', confidence: avg(shorts.map((r) => r.confidence)), reasons: shorts.flatMap((r) => r.reasons), strategy: shorts.map((r) => r.name).join('+') };
  }
  return { passed: false, direction: 'NONE', confidence: 0, reasons: [], strategy: (combo.strategies || []).join('+') };
}
function avg(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0; }

/* ---------- 15 filter ala APK v3.22 (FilterEngine.KNOWN_FILTERS) ---------- */
function swing(candles, idx, lookback) {
  let hh = -Infinity, ll = Infinity;
  for (let j = Math.max(0, idx - lookback); j < idx; j++) { hh = Math.max(hh, candles[j].h); ll = Math.min(ll, candles[j].l); }
  return { hh, ll };
}
function atrPct(cache, idx, candles) {
  const a = cache.atr14[idx];
  if (a == null || !(a > 0) || !(candles[idx].c > 0)) return null;
  return (a / candles[idx].c) * 100;
}
export const FILTERS = {
  trend: { name: 'Trend Filter', desc: 'Harga harus di sisi EMA50 yang searah.', defaults: {},
    fn(c, i, X, dir) { if (X.e50[i] == null) return { ok: false, why: 'EMA50 belum siap' };
      if (dir === 'LONG') return c[i].c > X.e50[i] ? { ok: true } : { ok: false, why: 'close di bawah EMA50' };
      return c[i].c < X.e50[i] ? { ok: true } : { ok: false, why: 'close di atas EMA50' }; } },
  ema: { name: 'EMA Filter', desc: 'Harga harus selaras EMA55 (filter APK asli).', defaults: {},
    fn(c, i, X, dir) { if (X.e55[i] == null) return { ok: false, why: 'EMA55 belum siap' };
      if (dir === 'LONG') return c[i].c > X.e55[i] ? { ok: true } : { ok: false, why: 'tidak selaras EMA55' };
      return c[i].c < X.e55[i] ? { ok: true } : { ok: false, why: 'tidak selaras EMA55' }; } },
  htf: { name: 'HTF Trend', desc: 'Tren besar via EMA200 harus searah.', defaults: {},
    fn(c, i, X, dir) { if (X.e200[i] == null) return { ok: false, why: 'EMA200 belum siap' };
      if (dir === 'LONG') return c[i].c > X.e200[i] ? { ok: true } : { ok: false, why: 'di bawah EMA200 (HTF bear)' };
      return c[i].c < X.e200[i] ? { ok: true } : { ok: false, why: 'di atas EMA200 (HTF bull)' }; } },
  volume: { name: 'Volume Filter', desc: 'Volume candle harus di atas rata-rata agar entry valid.', defaults: { mult: 1.0 },
    fn(c, i, X, dir, P) { const avg = X.sVol20[i] || 0;
      if (!(avg > 0)) return { ok: false, why: 'data volume belum siap' };
      return c[i].v >= avg * (P.mult ?? 1) ? { ok: true } : { ok: false, why: 'volume lemah' }; } },
  atr_vol: { name: 'ATR Volatility', desc: 'Volatilitas ATR% minimal agar pergerakan cukup.', defaults: { minPct: 0.2 },
    fn(c, i, X, dir, P) { const v = atrPct(X, i, c); if (v == null) return { ok: false, why: 'ATR belum siap' };
      return v >= (P.minPct ?? 0.2) ? { ok: true } : { ok: false, why: 'ATR terlalu rendah: ' + v.toFixed(2) + '%' }; } },
  adx: { name: 'ADX Filter', desc: 'Blokir tren lemah (ADX di bawah ambang).', defaults: { min: 20 },
    fn(c, i, X, dir, P) { const a = X.adx14[i]; if (a == null) return { ok: false, why: 'ADX belum siap' };
      return a >= (P.min ?? 20) ? { ok: true } : { ok: false, why: 'ADX lemah (' + a.toFixed(1) + ')' }; } },
  rsi: { name: 'RSI Filter', desc: 'Blokir LONG jenuh-beli & SHORT jenuh-jual.', defaults: { longMax: 72, shortMin: 28 },
    fn(c, i, X, dir, P) { const r = X.rsi14[i]; if (r == null) return { ok: false, why: 'RSI belum siap' };
      if (dir === 'LONG') return r <= (P.longMax ?? 72) ? { ok: true } : { ok: false, why: 'RSI jenuh ' + r.toFixed(0) };
      return r >= (P.shortMin ?? 28) ? { ok: true } : { ok: false, why: 'RSI jenuh ' + r.toFixed(0) }; } },
  ms: { name: 'Market Structure Filter', desc: 'Struktur HH/HL untuk LONG, LH/LL untuk SHORT.', defaults: { lookback: 6 },
    fn(c, i, X, dir, P) { void X; void P; if (i < 6) return { ok: false, why: 'data struktur kurang' };
      const bull = c[i - 2].h < c[i].h || c[i - 2].l < c[i].l;
      const bear = c[i - 2].h > c[i].h || c[i - 2].l > c[i].l;
      if (dir === 'LONG') return bull ? { ok: true } : { ok: false, why: 'struktur tak bullish' };
      return bear ? { ok: true } : { ok: false, why: 'struktur tak bearish' }; } },
  sr: { name: 'S/R Filter', desc: 'Blokir entry yang terlalu dekat ke level lawan.', defaults: { bufferPct: 0.3, lookback: 20 },
    fn(c, i, X, dir, P) { void X; const { hh, ll } = swing(c, i, P.lookback ?? 20);
      if (!Number.isFinite(hh) || !Number.isFinite(ll)) return { ok: false, why: 'S/R belum siap' };
      const buf = (P.bufferPct ?? 0.3) / 100;
      if (dir === 'LONG') return ((hh - c[i].c) / c[i].c) >= buf ? { ok: true } : { ok: false, why: 'terlalu dekat resistance' };
      return ((c[i].c - ll) / c[i].c) >= buf ? { ok: true } : { ok: false, why: 'terlalu dekat support' }; } },
  liq: { name: 'Liquidity Sweep Filter', desc: 'Harus ada sweep likuiditas searah sebelum entry.', defaults: { lookback: 20 },
    fn(c, i, X, dir, P) { void X; const { hh, ll } = swing(c, i, P.lookback ?? 20);
      let swept = false;
      for (let j = Math.max(1, i - (P.lookback ?? 20)); j < i; j++) {
        if (dir === 'LONG' && c[j].l < ll && c[j].c > ll) { swept = true; break; }
        if (dir === 'SHORT' && c[j].h > hh && c[j].c < hh) { swept = true; break; }
      }
      return swept ? { ok: true } : { ok: false, why: 'tanpa sweep searah' }; } },
  session: { name: 'Trading Session', desc: 'Hanya entry di jam sesi aktif (UTC).', defaults: { sessions: [[0, 24]] },
    fn(c, i, X, dir, P) { void X; void dir; const h = new Date(c[i].t).getUTCHours();
      const ss = Array.isArray(P.sessions) ? P.sessions : [[0, 24]];
      const ok = ss.some(([a, b]) => h >= a && h < b);
      return ok ? { ok: true } : { ok: false, why: 'di luar sesi' }; } },
  min_vol: { name: 'Min Volatility', desc: 'Minimal volatilitas ATR% agar pasar tidak terlalu sepi.', defaults: { minPct: 0.3 },
    fn(c, i, X, dir, P) { const v = atrPct(X, i, c); if (v == null) return { ok: false, why: 'ATR belum siap' };
      return v >= (P.minPct ?? 0.3) ? { ok: true } : { ok: false, why: 'pasar terlalu sepi' }; } },
  max_vol: { name: 'Max Volatility', desc: 'Blokir pasar terlalu liar (ATR% di atas ambang).', defaults: { maxPct: 5.0 },
    fn(c, i, X, dir, P) { const v = atrPct(X, i, c); if (v == null) return { ok: false, why: 'ATR belum siap' };
      return v <= (P.maxPct ?? 5) ? { ok: true } : { ok: false, why: 'pasar terlalu liar' }; } },
  cooldown: { name: 'Cooldown', desc: 'Jeda minimal antar trade (candle).', defaults: { bars: 3 },
    fn(c, i, X, dir, P, ctx) { void c; void X; void dir; const b = P.bars ?? 3;
      return (i - (ctx?.lastExit ?? -1e9)) >= b ? { ok: true } : { ok: false, why: 'cooldown' }; } },
  dup: { name: 'Duplicate Protection', desc: 'Blokir sinyal duplikat searah yang berdekatan.', defaults: { bars: 5 },
    fn(c, i, X, dir, P, ctx) { void c; void X;
      if (!ctx?.lastSig || ctx.lastSig.dir !== dir) return { ok: true };
      return (i - ctx.lastSig.idx) >= (P.bars ?? 5) ? { ok: true } : { ok: false, why: 'duplikat diblok' }; } },
};
export function filterList() {
  return Object.entries(FILTERS).map(([id, f]) => ({ id, name: f.name, desc: f.desc, defaults: f.defaults }));
}
/* ctx: {lastExit, lastSig:{dir,idx}} — dikelola caller agar cooldown/dup akurat */
export function applyFilters(candles, idx, cache, direction, activeFilters, ctx) {
  const failed = [];
  for (const f of activeFilters || []) {
    if (!f || f.enabled === false) continue;
    const def = FILTERS[f.name];
    if (!def) continue;
    let r;
    try { r = def.fn(candles, idx, cache, direction, f.params || def.defaults, ctx || {}); }
    catch { r = { ok: false, why: 'error' }; }
    if (!r || r.ok !== true) failed.push(def.name + (r?.why ? ' (' + r.why + ')' : ''));
  }
  return { passed: failed.length === 0, failed };
}

/* ---------- risk: SL/TP + sizing ---------- */
export function calcRiskLevels(entry, direction, cache, idx, params) {
  let sl, tp;
  if (params.useAtr && cache.atr14[idx] != null && cache.atr14[idx] > 0) {
    const a = cache.atr14[idx];
    if (direction === 'LONG') { sl = entry - a * params.atrSlMult; tp = entry + a * params.atrTpMult; }
    else { sl = entry + a * params.atrSlMult; tp = entry - a * params.atrTpMult; }
  } else if (direction === 'LONG') { sl = entry * (1 - params.slPercent); tp = entry * (1 + params.tpPercent); }
  else { sl = entry * (1 + params.slPercent); tp = entry * (1 - params.tpPercent); }
  if (!Number.isFinite(sl) || !Number.isFinite(tp) || sl <= 0 || tp <= 0) return null;
  if (direction === 'LONG' && !(sl < entry && tp > entry)) return null;
  if (direction === 'SHORT' && !(sl > entry && tp < entry)) return null;
  const riskDist = Math.abs(entry - sl);
  if (!(riskDist > 0) || !Number.isFinite(riskDist)) return null;
  return { sl, tp, riskDist };
}

/* ---------- backtest utama ---------- */
export function runBacktest(rawCandles, rawParams) {
  const params = sanitizeParams(rawParams);
  let candles = normalizeCandles(rawCandles);
  candles = filterByDate(candles, params.startDate, params.endDate);
  if (candles.length < MIN_CANDLES) {
    return errorResult(params, 'Butuh minimal ' + MIN_CANDLES + ' candle (dapat ' + candles.length + '). ' +
      (params.startDate || params.endDate ? 'Coba perlebar rentang tanggal. ' : '') + 'Minta 1000 candle dari provider.');
  }
  const cache = buildCache(candles);
  const trades = [];
  const equityCurve = [];
  let equity = params.initialCapital;
  let peak = equity;
  let maxDD = 0;
  equityCurve.push({ t: candles[0].t, equity });

  const startIdx = Math.max(WARMUP, 2);
  const activeFilters = (params.filters || []).filter((f) => f && f.enabled !== false);
  const fctx = { lastExit: -1e9, lastSig: null };
  let filtered = 0;
  for (let i = startIdx; i < candles.length - 1; i++) {
    const dec = decideAt(candles, i, cache, params);
    if (!dec.passed) continue;
    // Filter ala APK (FilterEngine.evaluate): sinyal yang gagal filter dilewati & dihitung
    if (activeFilters.length) {
      const fr = applyFilters(candles, i, cache, dec.direction, activeFilters, fctx);
      fctx.lastSig = { dir: dec.direction, idx: i };
      if (!fr.passed) { filtered++; continue; }
    }
    // Eksekusi di OPEN candle berikutnya (tanpa lookahead) + slippage merugikan
    const next = candles[i + 1];
    let entry = next.o;
    entry = dec.direction === 'LONG' ? entry * (1 + params.slippagePercent) : entry * (1 - params.slippagePercent);
    if (!Number.isFinite(entry) || entry <= 0) continue;
    const lv = calcRiskLevels(entry, dec.direction, cache, i, params);
    if (!lv) continue;
    // Sizing: risiko tetap per trade, clamp notional ke equity*leverage
    const riskAmount = equity * params.riskPerTrade;
    let qty = riskAmount / lv.riskDist;
    const maxNotional = equity * params.leverage;
    const notional = qty * entry;
    if (!Number.isFinite(qty) || qty <= 0) continue;
    if (notional > maxNotional) qty = maxNotional / entry;
    if (!(qty > 0) || !Number.isFinite(qty)) continue;

    // Scan ke depan: SL/TP intrabar, konservatif (SL dulu bila keduanya tersentuh)
    let exit = null, exitIdx = -1, result = 'EXPIRED';
    const lastJ = Math.min(candles.length - 1, i + params.maxHolding);
    for (let j = i + 1; j <= lastJ; j++) {
      const b = candles[j];
      let slHit = false, tpHit = false;
      if (dec.direction === 'LONG') { slHit = b.l <= lv.sl; tpHit = b.h >= lv.tp; }
      else { slHit = b.h >= lv.sl; tpHit = b.l <= lv.tp; }
      if (slHit && tpHit) { // konservatif ala freqtrade: worst-case = SL
        exit = dec.direction === 'LONG' ? lv.sl * (1 - params.slippagePercent) : lv.sl * (1 + params.slippagePercent);
        exitIdx = j; result = 'LOSS'; break;
      }
      if (slHit) {
        exit = dec.direction === 'LONG' ? lv.sl * (1 - params.slippagePercent) : lv.sl * (1 + params.slippagePercent);
        exitIdx = j; result = 'LOSS'; break;
      }
      if (tpHit) {
        exit = dec.direction === 'LONG' ? lv.tp * (1 - params.slippagePercent) : lv.tp * (1 + params.slippagePercent);
        exitIdx = j; result = 'WIN'; break;
      }
    }
    if (exit == null) { // expired: tutup di close terakhir window
      const b = candles[lastJ];
      exit = dec.direction === 'LONG' ? b.c * (1 - params.slippagePercent) : b.c * (1 + params.slippagePercent);
      exitIdx = lastJ; result = 'EXPIRED';
    }
    const gross = (dec.direction === 'LONG' ? exit - entry : entry - exit) * qty;
    const fees = (entry * qty + exit * qty) * params.feePercent; // FIX: fee 2 sisi
    const pnl = gross - fees;
    if (!Number.isFinite(pnl)) continue;
    const rMult = riskAmount > 0 ? pnl / riskAmount : 0;
    equity += pnl;
    if (!Number.isFinite(equity)) equity = params.initialCapital;
    peak = Math.max(peak, equity);
    const dd = peak > 0 ? (peak - equity) / peak : 0;
    if (dd > maxDD) maxDD = dd;
    equityCurve.push({ t: candles[exitIdx].t, equity });
    trades.push({
      direction: dec.direction, asset: params.asset, timeframe: params.timeframe,
      entry, exit, stopLoss: lv.sl, takeProfit: lv.tp,
      entryTime: next.t, exitTime: candles[exitIdx].t,
      strategy: dec.strategy, confidence: dec.confidence, reasons: dec.reasons,
      qty, riskAmount, fees, pnl, pnlPercent: entry > 0 ? ((dec.direction === 'LONG' ? exit - entry : entry - exit) / entry) * 100 * params.leverage : 0,
      rMultiple: rMult, result, holding: exitIdx - (i + 1) + 1,
    });
    i = exitIdx; // tidak overlap: 1 posisi per waktu (seperti freqtrade default)
    fctx.lastExit = exitIdx;
  }

  return buildResult(params, candles, trades, equityCurve, maxDD, filtered);
}

function errorResult(params, message) {
  return {
    error: message, asset: params.asset, timeframe: params.timeframe,
    totalTrades: 0, wins: 0, losses: 0, expired: 0, winRate: 0, lossRate: 0,
    grossProfit: 0, grossLoss: 0, netProfit: 0, netProfitPercent: 0,
    profitFactor: 0, expectancy: 0, averageWin: 0, averageLoss: 0, averageR: 0, averageRR: 0,
    maxDrawdown: 0, maxDrawdownPercent: 0, sharpe: 0, sortino: 0, calmar: 0, cagr: 0, exposure: 0,
    avgHolding: 0, longestWinStreak: 0, longestLossStreak: 0, filtered: 0,
    initialCapital: params.initialCapital, finalCapital: params.initialCapital,
    trades: [], equityCurve: [],
  };
}

function buildResult(params, candles, trades, equityCurve, maxDD, filtered = 0) {
  const wins = trades.filter((t) => t.result === 'WIN');
  const losses = trades.filter((t) => t.result === 'LOSS');
  const expired = trades.filter((t) => t.result === 'EXPIRED');
  const total = trades.length;
  const grossProfit = wins.reduce((s, t) => s + t.pnl, 0);
  const grossLossAbs = Math.abs(losses.reduce((s, t) => s + t.pnl, 0));
  const netProfit = trades.reduce((s, t) => s + t.pnl, 0);
  const finalCapital = params.initialCapital + netProfit;
  const winRate = total ? (wins.length / total) * 100 : 0;
  const profitFactor = grossLossAbs > 0 ? grossProfit / grossLossAbs : (grossProfit > 0 ? Infinity : 0);
  const expectancy = total ? netProfit / total : 0;
  const averageWin = wins.length ? grossProfit / wins.length : 0;
  const averageLoss = losses.length ? -grossLossAbs / losses.length : 0;
  const averageR = total ? trades.reduce((s, t) => s + t.rMultiple, 0) / total : 0;
  // streaks
  let lw = 0, ll = 0, cw = 0, cl = 0;
  for (const t of trades) {
    if (t.result === 'WIN') { cw++; cl = 0; lw = Math.max(lw, cw); }
    else if (t.result === 'LOSS') { cl++; cw = 0; ll = Math.max(ll, cl); }
    else { cw = 0; cl = 0; }
  }
  // Sharpe/Sortino per-trade (risk-free 0)
  const rets = trades.map((t) => t.pnl / params.initialCapital);
  const sharpe = sharpeRatio(rets);
  const sortino = sortinoRatio(rets);
  const maxDDPct = maxDD * 100;
  const years = candles.length > 1 ? Math.max((candles[candles.length - 1].t - candles[0].t) / (365.25 * 86400000), 1 / 365.25) : 1 / 365.25;
  const cagr = params.initialCapital > 0 && finalCapital > 0 ? (Math.pow(finalCapital / params.initialCapital, 1 / years) - 1) * 100 : 0;
  const calmar = maxDD > 0 ? (cagr / (maxDDPct || 1)) : (cagr > 0 ? Infinity : 0);
  const totalHolding = trades.reduce((s, t) => s + t.holding, 0);
  const exposure = candles.length ? (totalHolding / candles.length) * 100 : 0;
  // RR rata-rata terencana
  const rrs = trades.map((t) => {
    const risk = Math.abs(t.entry - t.stopLoss);
    const rew = Math.abs(t.takeProfit - t.entry);
    return risk > 0 ? rew / risk : 0;
  });
  return {
    asset: params.asset, timeframe: params.timeframe, strategy: labelStrategy(params),
    initialCapital: params.initialCapital, finalCapital,
    totalTrades: total, wins: wins.length, losses: losses.length, expired: expired.length,
    winRate, lossRate: total ? (losses.length / total) * 100 : 0,
    grossProfit, grossLoss: -grossLossAbs, netProfit,
    netProfitPercent: params.initialCapital ? (netProfit / params.initialCapital) * 100 : 0,
    profitFactor: Number.isFinite(profitFactor) ? profitFactor : (profitFactor === Infinity ? 999 : 0),
    expectancy, averageWin, averageLoss, averageR,
    averageRR: rrs.length ? rrs.reduce((a, b) => a + b, 0) / rrs.length : 0,
    maxDrawdown: params.initialCapital * maxDD, maxDrawdownPercent: maxDDPct,
    sharpe: finite(sharpe), sortino: finite(sortino),
    calmar: Number.isFinite(calmar) ? calmar : (calmar === Infinity ? 999 : 0),
    cagr: finite(cagr), exposure: finite(exposure),
    avgHolding: total ? totalHolding / total : 0,
    longestWinStreak: lw, longestLossStreak: ll, filtered,
    trades, equityCurve,
  };
}
function finite(v) { return Number.isFinite(v) ? v : 0; }
function sharpeRatio(rets) {
  if (rets.length < 2) return 0;
  const m = rets.reduce((a, b) => a + b, 0) / rets.length;
  const sd = Math.sqrt(rets.reduce((s, r) => s + (r - m) ** 2, 0) / (rets.length - 1));
  return sd === 0 ? 0 : (m / sd) * Math.sqrt(rets.length);
}
function sortinoRatio(rets) {
  if (rets.length < 2) return 0;
  const m = rets.reduce((a, b) => a + b, 0) / rets.length;
  const dn = rets.filter((r) => r < 0);
  if (!dn.length) return m > 0 ? 99 : 0;
  const dsd = Math.sqrt(dn.reduce((s, r) => s + r * r, 0) / dn.length);
  return dsd === 0 ? 0 : (m / dsd) * Math.sqrt(rets.length);
}
function labelStrategy(params) {
  if (params.combo?.strategies?.length) return params.combo.strategies.join(' + ') + ' (' + (params.combo.mode || 'OR') + ')';
  return params.strategy;
}

export function strategyList() {
  return Object.entries(STRATEGIES).map(([id, s]) => ({ id, name: s.name, desc: s.desc }));
}

/* Demo generator (seeded, untuk mode offline) */
export function genDemoCandles(seed = 42, n = 500, startPrice = 65000, tfMin = 15) {
  let rnd = seed >>> 0;
  const rand = () => ((rnd = (rnd * 1664525 + 1013904223) >>> 0) / 4294967296);
  const out = [];
  let price = startPrice;
  let t = Date.now() - n * tfMin * 60000;
  t -= t % (tfMin * 60000);
  let trend = 0;
  for (let i = 0; i < n; i++) {
    if (i % 80 === 0) trend = (rand() - 0.5) * 0.004;
    const drift = trend + (rand() - 0.5) * 0.006;
    const o = price;
    const c = Math.max(1, o * (1 + drift));
    const h = Math.max(o, c) * (1 + rand() * 0.0015);
    const l = Math.min(o, c) * (1 - rand() * 0.0015);
    const v = 50 + rand() * 200 + Math.abs(drift) * 80000;
    out.push({ t, o, h, l, c, v });
    price = c; t += tfMin * 60000;
  }
  return out;
}
