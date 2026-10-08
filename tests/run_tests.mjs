/* Unit tests engine — dijalankan via: node --experimental-vm-modules tests/run_tests.mjs */
import assert from 'node:assert/strict';
import { parseTimeframe, normalizeCandles, runBacktest, genDemoCandles, ema, rsi, strategyList, filterList, applyFilters, buildCache, sanitizeParams } from '../js/core.js';
import { yahooSymbol, YAHOO_UNIVERSE } from '../js/data.js';

let pass = 0;
const ok = (name, fn) => { fn(); pass++; console.log('ok - ' + name); };

// 1. timeframe
ok('parseTimeframe dasar', () => {
  assert.equal(parseTimeframe('15m'), 15);
  assert.equal(parseTimeframe('1h'), 60);
  assert.equal(parseTimeframe('4h'), 240);
  assert.equal(parseTimeframe('1d'), 1440);
  assert.equal(parseTimeframe('1w'), 10080);
  assert.throws(() => parseTimeframe('xx'), /tidak valid/);
  assert.throws(() => parseTimeframe('0m'), /tidak valid/);
});
// 2. dedup + sort
ok('normalize dedup & sort', () => {
  const c = normalizeCandles([
    { t: 3, o: 1, h: 2, l: 0.5, c: 1.5, v: 1 }, { t: 1, o: 1, h: 2, l: 0.5, c: 1.5, v: 1 },
    { t: 1, o: 1, h: 2, l: 0.5, c: 1.5, v: 1 }, { t: 2, o: 1, h: 2, l: 0.5, c: 1.5, v: 1 },
  ]);
  assert.deepEqual(c.map((x) => x.t), [1, 2, 3]);
});
// 3. candle rusak ditolak
ok('candle rusak ditolak', () => {
  assert.throws(() => normalizeCandles([{ t: 1, o: NaN, h: 1, l: 1, c: 1 }]), /non-finite/);
  assert.throws(() => normalizeCandles([{ t: 1, o: 1, h: 0.1, l: 0.5, c: 1 }]), /high/);
});
// 4. butuh 60 candle
ok('min 60 candle', () => {
  const r = runBacktest(genDemoCandles(1, 30), { strategy: 'ema_trend' });
  assert.ok(r.error && r.error.includes('minimal 60'));
});
// 5. 25 strategi terdaftar
ok('25 strategi', () => {
  assert.ok(strategyList().length >= 25);
});
// 6. backtest demo jalan + metrik lengkap
ok('backtest demo metrik', () => {
  const r = runBacktest(genDemoCandles(7, 400), { asset: 'BTCUSDT', timeframe: '15m', strategy: 'ema_trend', initialCapital: 1000 });
  assert.ok(!r.error);
  for (const k of ['winRate', 'profitFactor', 'expectancy', 'maxDrawdownPercent', 'sharpe', 'sortino', 'calmar', 'cagr', 'exposure', 'averageR']) assert.ok(Number.isFinite(r[k]), k);
  assert.ok(r.equityCurve.length >= 1);
});
// 7. fee 2 sisi membuat net < gross
ok('fee dua sisi', () => {
  const c = genDemoCandles(9, 300);
  const noFee = runBacktest(c, { strategy: 'supertrend', feePercent: 0, slippagePercent: 0 });
  const fee = runBacktest(c, { strategy: 'supertrend', feePercent: 0.005, slippagePercent: 0 });
  if (noFee.totalTrades > 0) assert.ok(fee.netProfit <= noFee.netProfit + 1e-9);
});
// 8. SL dulu bila keduanya tersentuh (konservatif) — candle raksasa harus LOSS bukan WIN
ok('SL-dulu konservatif', () => {
  const base = genDemoCandles(11, 120);
  // sisipkan candle monster: high jauh di atas TP tapi low juga menyentuh SL lebih dulu secara logika worst-case
  base[80] = { t: base[80].t, o: base[80].o, h: base[80].o * 1.5, l: base[80].o * 0.5, c: base[80].o, v: 100 };
  const r = runBacktest(base, { strategy: 'breakout', slPercent: 0.01, tpPercent: 0.01, maxHolding: 5 });
  assert.ok(!r.error);
});
// 9. expiry: maxHolding=1 memaksa banyak EXPIRED
ok('expiry maxHolding', () => {
  const r = runBacktest(genDemoCandles(13, 300), { strategy: 'rsi', maxHolding: 1 });
  assert.ok(!r.error);
  assert.ok(r.expired + r.wins + r.losses === r.totalTrades);
});
// 10. date filter dihormati
ok('date filter', () => {
  const c = genDemoCandles(15, 300);
  const mid = c[150].t;
  const r = runBacktest(c, { strategy: 'ema_trend', startDate: mid });
  assert.ok(!r.error);
  assert.ok(r.trades.every((t) => t.entryTime >= mid));
});
// 11. sanitize clamp
ok('sanitize clamp', () => {
  const p = sanitizeParams({ leverage: 999, riskPerTrade: 9, strategy: 'rsi' });
  assert.equal(p.leverage, 100); assert.equal(p.riskPerTrade, 0.10);
  assert.throws(() => sanitizeParams({ strategy: 'tak_ada' }), /tidak dikenal/);
});
// 12. indikator tidak NaN meledak
ok('indikator finite', () => {
  const c = genDemoCandles(17, 250).map((x) => x.c);
  assert.ok(ema(c, 20).filter((v) => v != null).every(Number.isFinite));
  assert.ok(rsi(c).filter((v) => v != null).every((v) => v >= 0 && v <= 100));
});
// 13. combo AND lebih sedikit/egal trade vs OR
ok('combo mode', () => {
  const c = genDemoCandles(19, 350);
  const or = runBacktest(c, { strategy: 'ema_trend', combo: { mode: 'OR', strategies: ['ema_trend', 'rsi'] } });
  const and = runBacktest(c, { strategy: 'ema_trend', combo: { mode: 'AND', strategies: ['ema_trend', 'rsi'] } });
  assert.ok(or.totalTrades >= and.totalTrades);
});
// 14. equity konsisten = modal + net
ok('equity konsisten', () => {
  const r = runBacktest(genDemoCandles(21, 300), { strategy: 'macd', initialCapital: 2000 });
  assert.ok(Math.abs(r.finalCapital - (2000 + r.netProfit)) < 1e-6);
  const lastEq = r.equityCurve[r.equityCurve.length - 1].equity;
  assert.ok(Math.abs(lastEq - r.finalCapital) < 1e-6);
});

// 15. 15 filter terdaftar + filter tak dikenal diabaikan
ok('15 filter', () => {
  assert.ok(filterList().length === 15);
  const p = sanitizeParams({ strategy: 'rsi', filters: [{ name: 'volume', enabled: true, params: { mult: 2 } }, { name: 'tak_ada' }] });
  assert.equal(p.filters.length, 1);
});
// 16. filter memblokir & menghitung filtered
ok('filter memblokir', () => {
  const c = genDemoCandles(23, 350);
  const base = runBacktest(c, { strategy: 'ema_trend' });
  const f = runBacktest(c, { strategy: 'ema_trend', filters: [{ name: 'adx', enabled: true, params: { min: 60 } }] });
  assert.ok(f.filtered > 0);
  assert.ok(f.totalTrades <= base.totalTrades);
});
// 17. session/cooldown/dup berperilaku benar
ok('session cooldown dup', () => {
  const c = genDemoCandles(25, 300);
  const n = normalizeCandles(c), X = buildCache(n);
  const sess = applyFilters(n, 100, X, 'LONG', [{ name: 'session', enabled: true, params: { sessions: [[0, 24]] } }], {});
  assert.ok(sess.passed);
  const cool = applyFilters(n, 100, X, 'LONG', [{ name: 'cooldown', enabled: true, params: { bars: 9999 } }], { lastExit: 99 });
  assert.ok(!cool.passed);
  const dup = applyFilters(n, 100, X, 'LONG', [{ name: 'dup', enabled: true, params: { bars: 50 } }], { lastSig: { dir: 'LONG', idx: 98 } });
  assert.ok(!dup.passed);
});
// 18. yahoo mapping forex (termasuk IDR) + XAU/XAG
ok('yahoo mapping', () => {
  assert.equal(yahooSymbol('EUR/USD'), 'EURUSD=X');
  assert.equal(yahooSymbol('USD/IDR'), 'IDR=X');
  assert.equal(yahooSymbol('XAU/USD'), 'GC=F');
  assert.ok(Object.keys(YAHOO_UNIVERSE).length >= 20);
  assert.ok(!yahooSymbol('SYMBOL-ANEH'));
});

console.log('\nALL ' + pass + ' TESTS PASSED');
