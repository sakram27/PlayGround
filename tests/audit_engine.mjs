/* Audit harness v3.32: semua strategi x combo x filter -> invarian hasil.
 * Jalankan: node tests/audit_engine.mjs  (exit 1 bila ada anomali)
 */
import { runBacktest, genDemoCandles, strategyList, filterList, normalizeCandles } from '../js/core.js';

let fails = 0, checks = 0;
const bad = (msg) => { fails++; console.log('FAIL - ' + msg); };
const ok = (cond, msg) => { checks++; if (!cond) bad(msg); };

const STRS = strategyList().map((s) => s.id);
const seeds = [7, 42, 99];
console.log('strategi:', STRS.length, '| filter:', filterList().length);

function invariants(r, tag) {
  if (r.error) { ok(r.trades.length === 0, tag + ': error tapi ada trades'); return; }
  ok(Number.isFinite(r.finalCapital), tag + ': finalCapital tidak finite');
  ok(Math.abs(r.finalCapital - (r.initialCapital + r.netProfit)) < 1e-6, tag + ': final != modal+net');
  ok(r.wins + r.losses + r.expired === r.totalTrades, tag + ': wins+losses+exp != total');
  ok(Math.abs(r.winRate - (r.totalTrades ? (r.wins / r.totalTrades) * 100 : 0)) < 1e-9, tag + ': winRate salah');
  ok(r.maxDrawdownPercent >= 0 && r.maxDrawdownPercent <= 100, tag + ': maxDD% di luar 0-100 (' + r.maxDrawdownPercent + ')');
  for (const k of ['sharpe', 'sortino', 'calmar', 'cagr', 'exposure', 'expectancy', 'averageR', 'profitFactor']) {
    if (!Number.isFinite(r[k])) bad(tag + ': metrik ' + k + ' tidak finite');
    else checks++;
  }
  const lastEq = r.equityCurve[r.equityCurve.length - 1]?.equity;
  ok(Math.abs((lastEq ?? r.finalCapital) - r.finalCapital) < 1e-6, tag + ': equityCurve terakhir != final');
  for (const t of r.trades) {
    if (!Number.isFinite(t.pnl) || !Number.isFinite(t.entry) || !Number.isFinite(t.exit)) { bad(tag + ': trade non-finite'); break; }
    if (!(t.exitTime >= t.entryTime)) { bad(tag + ': exitTime < entryTime'); break; }
    if (!(t.qty > 0)) { bad(tag + ': qty <= 0'); break; }
    if (t.direction === 'LONG' && !(t.stopLoss < t.entry && t.takeProfit > t.entry)) { bad(tag + ': level LONG invalid'); break; }
    if (t.direction === 'SHORT' && !(t.stopLoss > t.entry && t.takeProfit < t.entry)) { bad(tag + ': level SHORT invalid'); break; }
  }
}

// 1. tiap strategi single, 3 seed
for (const seed of seeds) {
  const c = genDemoCandles(seed, 400);
  for (const s of STRS) {
    let r;
    try { r = runBacktest(c, { strategy: s, timeframe: '15m' }); }
    catch (e) { bad(s + ' seed' + seed + ' THROW: ' + e.message); continue; }
    checks++;
    invariants(r, s + '#' + seed);
  }
}
// 2. combo OR/AND/MAJORITY untuk semua pasangan populer
const combos = [['ema_trend', 'rsi'], ['supertrend', 'macd', 'volume'], ['bos', 'choch', 'liq_dummy']];
for (const mode of ['OR', 'AND', 'MAJORITY']) {
  for (const set of combos) {
    const valid = set.filter((x) => STRS.includes(x));
    if (valid.length < 2) continue;
    const c = genDemoCandles(7, 400);
    let r;
    try { r = runBacktest(c, { strategy: valid[0], combo: { mode, strategies: valid } }); }
    catch (e) { bad('combo ' + mode + ' THROW: ' + e.message); continue; }
    checks++;
    invariants(r, 'combo-' + mode + '-' + valid.join('+'));
  }
}
// 3. tiap filter menyala sendiri (harus: tidak throw, trades <= base, filtered >= 0)
{
  const c = genDemoCandles(7, 500);
  const base = runBacktest(c, { strategy: 'ema_trend' });
  for (const f of filterList()) {
    let r;
    try { r = runBacktest(c, { strategy: 'ema_trend', filters: [{ name: f.id, enabled: true, params: f.defaults }] }); }
    catch (e) { bad('filter ' + f.id + ' THROW: ' + e.message); continue; }
    checks++;
    invariants(r, 'filter-' + f.id);
    ok(r.totalTrades <= base.totalTrades, 'filter ' + f.id + ': trades bertambah (' + r.totalTrades + '>' + base.totalTrades + ')');
    ok((r.filtered ?? 0) >= 0, 'filter ' + f.id + ': filtered invalid');
  }
  // semua filter sekaligus
  const all = runBacktest(c, { strategy: 'ema_trend', filters: filterList().map((f) => ({ name: f.id, enabled: true, params: f.defaults })) });
  invariants(all, 'filter-semua');
}
// 4. edge: flat market, spike, data mini, forex tanpa volume
{
  const flat = [];
  let t = Date.now() - 500 * 900000;
  for (let i = 0; i < 500; i++) flat.push({ t: t + i * 900000, o: 100, h: 100, l: 100, c: 100, v: 10 });
  for (const s of ['ema_trend', 'rsi', 'supertrend', 'bollinger', 'fibonacci', 'breakout']) {
    const r = runBacktest(flat, { strategy: s });
    invariants(r, 'flat-' + s);
  }
  const spike = genDemoCandles(5, 200);
  spike[100] = { ...spike[100], h: spike[100].o * 1.5, l: spike[100].o * 0.5 };
  invariants(runBacktest(spike, { strategy: 'breakout' }), 'spike');
  const mini = runBacktest(genDemoCandles(5, 30), { strategy: 'ema_trend' });
  ok(!!mini.error, 'mini: harus error min-60');
  const novol = genDemoCandles(5, 300).map((c) => ({ ...c, v: 0 }));
  invariants(runBacktest(novol, { strategy: 'volume' }), 'novol');
}
// 5. determinisme
{
  const c = genDemoCandles(7, 300);
  const a = JSON.stringify(runBacktest(c, { strategy: 'macd', filters: [{ name: 'adx', enabled: true, params: { min: 20 } }] }));
  const b = JSON.stringify(runBacktest(c, { strategy: 'macd', filters: [{ name: 'adx', enabled: true, params: { min: 20 } }] }));
  ok(a === b, 'hasil tidak deterministik');
}
// 6. leverage & risiko ekstrem tetap finite
{
  const c = genDemoCandles(7, 300);
  for (const p of [{ leverage: 100, riskPerTrade: 0.1 }, { leverage: 1, riskPerTrade: 0.001 }, { feePercent: 0.02, slippagePercent: 0.02 }]) {
    const r = runBacktest(c, { strategy: 'supertrend', ...p });
    invariants(r, 'ekstrem-' + JSON.stringify(p));
  }
}

// 7. cooldown/dup berbasis waktu (live engine) + determinisme backtest
{
  const c = genDemoCandles(31, 300);
  const n = normalizeCandles(c);
  const { buildCache, applyFilters } = await import('../js/core.js');
  const X = buildCache(n);
  const tfMs = 900000, T = n[200].t;
  const cdBlock = applyFilters(n, 200, X, 'LONG', [{ name: 'cooldown', enabled: true, params: { bars: 5 } }], { lastExitT: T - 2 * tfMs, tfMs });
  ok(!cdBlock.passed, 'cooldown waktu: harus blokir');
  const cdPass = applyFilters(n, 200, X, 'LONG', [{ name: 'cooldown', enabled: true, params: { bars: 5 } }], { lastExitT: T - 10 * tfMs, tfMs });
  ok(cdPass.passed, 'cooldown waktu: harus lolos');
  const dupBlock = applyFilters(n, 200, X, 'SHORT', [{ name: 'dup', enabled: true, params: { bars: 10 } }], { lastSigT: T - tfMs, lastSigDir: 'SHORT', tfMs });
  ok(!dupBlock.passed, 'dup waktu: harus blokir');
  const dupDiff = applyFilters(n, 200, X, 'LONG', [{ name: 'dup', enabled: true, params: { bars: 10 } }], { lastSigT: T - tfMs, lastSigDir: 'SHORT', tfMs });
  ok(dupDiff.passed, 'dup beda arah: harus lolos');
  const r1 = runBacktest(c, { strategy: 'ema_trend', filters: [{ name: 'cooldown', enabled: true, params: { bars: 3 } }, { name: 'dup', enabled: true, params: { bars: 5 } }] });
  invariants(r1, 'cooldown-dup-backtest');
}
// 8. edge combo: 1 strategi, array kosong, unknown tercampur
{
  const c = genDemoCandles(33, 300);
  const single = runBacktest(c, { strategy: 'rsi' });
  const empty = runBacktest(c, { strategy: 'rsi', combo: { mode: 'AND', strategies: [] } });
  ok(empty.totalTrades === single.totalTrades, 'combo kosong harus = single');
  const one = runBacktest(c, { strategy: 'rsi', combo: { mode: 'AND', strategies: ['rsi'] } });
  invariants(one, 'combo-1-strat');
  const off = runBacktest(c, { strategy: 'rsi', filters: [{ name: 'volume', enabled: false, params: { mult: 99 } }] });
  ok(off.totalTrades === single.totalTrades, 'filter mati harus diabaikan');
}

console.log('\nchecks=' + checks + ' fails=' + fails);
process.exit(fails ? 1 : 0);
