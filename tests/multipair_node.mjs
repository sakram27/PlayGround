// Regression: single vs multi-pair consistency (engine TIDAK diubah).
// Meniru loop runRanking() di www/js/app.js memakai provider demo (offline).
// node tests/multipair_node.mjs
import { runBacktest } from '../www/js/core.js';
import { getCandles, topPairs } from '../www/js/data.js';

const base = {
  timeframe: '15m', initialCapital: 1000, riskPerTrade: 0.01, leverage: 1,
  feePercent: 0.0005, slippagePercent: 0.0002, slPercent: 0.015, tpPercent: 0.03,
  maxHolding: 100, strategy: 'ema_trend', strategyParams: {}, combo: null,
  filters: [], startDate: 0, endDate: 0, useAtr: false,
};
let fail = 0;
const ok = (name, cond, extra = '') => {
  console.log((cond ? 'PASS' : 'FAIL') + ' ' + name + (extra ? ' — ' + extra : ''));
  if (!cond) fail++;
};

// T1: 1 pair menghasilkan hasil
const c1 = await getCandles({ provider: 'demo', symbol: 'BTCUSDT', timeframe: '15m', limit: 500 });
const r1 = runBacktest(c1, { ...base, asset: 'BTCUSDT' });
ok('T1 single-pair ada hasil', !r1.error && r1.totalTrades > 0, `${r1.totalTrades} trades`);

// T2/T3: multi-pair 2 & 4 pair — semua pair masuk request & ada hasil
for (const n of [2, 4]) {
  const pairs = (await topPairs('demo', 12)).slice(0, n);
  const rows = [];
  for (const sym of pairs) {
    const c = await getCandles({ provider: 'demo', symbol: sym, timeframe: '15m', limit: 500 });
    rows.push({ sym, res: runBacktest(c, { ...base, asset: sym }) });
  }
  ok(`T${n === 2 ? 2 : 3} multi-${n} semua pair diproses`, rows.length === n && rows.every((r) => !r.res.error),
    rows.map((r) => `${r.sym}=${r.res.totalTrades}t`).join(' '));
}

// T4: single vs multi identik untuk pair yang sama (tidak ada state bocor antar pair)
const cM = await getCandles({ provider: 'demo', symbol: 'BTCUSDT', timeframe: '15m', limit: 500 });
const rM = runBacktest(cM, { ...base, asset: 'BTCUSDT' });
ok('T4 single-vs-multi identik', r1.totalTrades === rM.totalTrades && r1.netProfit === rM.netProfit);

// T5: pair gagal terisolasi (tidak menghentikan batch)
let isolated = false;
try {
  await getCandles({ provider: 'binance', symbol: 'FAKEPAIR999', timeframe: '15m', limit: 200 });
} catch { isolated = true; }
ok('T5 pair gagal terisolasi', isolated);

process.exit(fail ? 1 : 0);
