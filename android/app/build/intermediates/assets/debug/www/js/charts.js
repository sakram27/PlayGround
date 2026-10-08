/* Chart layer — perbaikan dari lw_chart.html v3.22:
 * FIX 1: kunci sumbu harga (Y) agar candle tidak gepeng saat pinch 2 jari.
 * FIX 2: satu label harga terakhir dinamis (hijau/merah), tidak dobel.
 * FIX 3: error provider jujur (bedakan "provider gagal" vs "no data").
 * FIX 4: overlay volume/EMA bisa toggle; resize observer; fitContent aman.
 */
'use strict';
import { ema } from './core.js';

let mainChart = null, candleSeries = null, volSeries = null, emaLines = {}, lastPriceLine = null;
let equityChart = null, equitySeries = null, ddSeries = null;
let currentCandles = [];

const THEME = {
  layout: { background: { type: 'solid', color: '#070b11' }, fontSize: 10, fontFamily: 'monospace', textColor: '#9aa7b4' },
  grid: { vertLines: { color: '#16202b' }, horzLines: { color: '#16202b' } },
  rightPriceScale: { borderColor: '#243449', autoScale: true, scaleMargins: { top: 0.06, bottom: 0.2 } },
  timeScale: { borderColor: '#243449', timeVisible: true, secondsVisible: false, rightOffset: 6, barSpacing: 7 },
  crosshair: { mode: 0, vertLine: { color: '#3a4a5f', labelBackgroundColor: '#243449' }, horzLine: { color: '#3a4a5f', labelBackgroundColor: '#243449' } },
  handleScale: { axisPressedMouseMove: { time: true, price: false }, mouseWheel: true, pinch: true },
  handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
};

export function getCandles() { return currentCandles; }

function ensureMain(el) {
  if (mainChart) return;
  mainChart = LightweightCharts.createChart(el, THEME);
  candleSeries = mainChart.addCandlestickSeries({
    upColor: '#00E676', downColor: '#FF5252', borderVisible: false,
    wickUpColor: '#00E676', wickDownColor: '#FF5252',
    priceLineVisible: false, lastValueVisible: false, // FIX: label digambar kustom, cegah dobel
  });
  volSeries = mainChart.addHistogramSeries({ priceScaleId: '', priceFormat: { type: 'volume' }, priceLineVisible: false, lastValueVisible: false });
  mainChart.priceScale('').applyOptions({ scaleMargins: { top: 0.84, bottom: 0 } });
  const mk = (color, glow) => {
    const g = mainChart.addLineSeries({ color: glow, lineWidth: 6, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, lineType: 2 });
    const s = mainChart.addLineSeries({ color, lineWidth: 2, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, lineType: 2 });
    return { g, s };
  };
  emaLines = { e20: mk('#00E5FF', 'rgba(0,229,255,0.16)'), e50: mk('#00E676', 'rgba(0,230,118,0.16)'), e200: mk('#B388FF', 'rgba(179,136,255,0.14)') };
  new ResizeObserver(() => {
    try { mainChart.applyOptions({ width: el.clientWidth || 300, height: el.clientHeight || 340 }); } catch { /* abaikan */ }
  }).observe(el);
  window.addEventListener('resize', () => {
    try { mainChart.applyOptions({ width: el.clientWidth || 300, height: el.clientHeight || 340 }); } catch { /* abaikan */ }
  });
}

export function renderMain(el, emptyEl, payload) {
  ensureMain(el);
  const { candles, markers = [], priceLines = [], overlays = { volume: true, ema20: true, ema50: true, ema200: false }, errorText = '' } = payload || {};
  if (!candles || !candles.length) {
    emptyEl.style.display = 'flex';
    emptyEl.textContent = errorText || 'NO MARKET DATA';
    try { candleSeries.setData([]); volSeries.setData([]); Object.values(emaLines).forEach(({ g, s }) => { g.setData([]); s.setData([]); }); } catch { /* abaikan */ }
    return;
  }
  emptyEl.style.display = 'none';
  currentCandles = candles;
  candleSeries.setData(candles.map((c) => ({ time: c.t / 1000, open: c.o, high: c.h, low: c.l, close: c.c })));
  volSeries.setData(candles.map((c) => ({ time: c.t / 1000, value: c.v || 0, color: c.c >= c.o ? 'rgba(52,211,153,0.45)' : 'rgba(248,113,113,0.45)' })));
  const closes = candles.map((c) => c.c);
  const e20 = ema(closes, 20), e50 = ema(closes, 50), e200 = ema(closes, 200);
  const ed = (arr) => candles.filter((_, i) => arr[i] != null).map((c, k) => ({ time: c.t / 1000, value: arr[candles.indexOf(c)] })).filter((p) => Number.isFinite(p.value));
  const setE = (key, arr) => { const d = candles.map((c, i) => (arr[i] == null ? null : { time: c.t / 1000, value: arr[i] })).filter(Boolean); emaLines[key].s.setData(d); emaLines[key].g.setData(d); };
  setE('e20', e20); setE('e50', e50); setE('e200', e200);
  volSeries.applyOptions({ visible: !!overlays.volume });
  emaLines.e20.s.applyOptions({ visible: !!overlays.ema20 }); emaLines.e20.g.applyOptions({ visible: !!overlays.ema20 });
  emaLines.e50.s.applyOptions({ visible: !!overlays.ema50 }); emaLines.e50.g.applyOptions({ visible: !!overlays.ema50 });
  emaLines.e200.s.applyOptions({ visible: !!overlays.ema200 }); emaLines.e200.g.applyOptions({ visible: !!overlays.ema200 });
  try { candleSeries.setMarkers(markers.map((m) => ({ time: m.t / 1000, position: m.pos || 'belowBar', color: m.color || '#34d399', shape: m.shape || 'arrowUp', text: m.text || '' }))); } catch { /* abaikan */ }
  // label harga terakhir dinamis (FIX dobel label)
  if (lastPriceLine) { try { candleSeries.removePriceLine(lastPriceLine); } catch { /* abaikan */ } lastPriceLine = null; }
  const last = candles[candles.length - 1];
  const bull = last.c >= last.o;
  try {
    lastPriceLine = candleSeries.createPriceLine({ price: last.c, color: bull ? '#00E676' : '#FF5252', lineWidth: 1, lineStyle: 0, axisLabelVisible: true, title: '' });
    (priceLines || []).slice(-3).forEach((pl) => {
      if (pl.price == null || !Number.isFinite(pl.price)) return;
      try { candleSeries.createPriceLine({ price: pl.price, color: pl.color || '#8ab4f8', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: pl.title || '' }); } catch { /* abaikan */ }
    });
  } catch { /* abaikan */ }
  mainChart.timeScale().fitContent();
  // countdown mengikuti timeframe data aktual
  return last;
}

export function renderEquity(el, equityCurve) {
  if (!equityCurve || !equityCurve.length) { el.innerHTML = '<div class="chart-empty">Belum ada equity curve — jalankan backtest.</div>'; equityChart = null; return; }
  el.innerHTML = '';
  equityChart = LightweightCharts.createChart(el, {
    layout: { background: { type: 'solid', color: '#0b1119' }, textColor: '#9aa7b4', fontSize: 10, fontFamily: 'monospace' },
    grid: { vertLines: { color: '#141d29' }, horzLines: { color: '#141d29' } },
    rightPriceScale: { borderColor: '#243449' }, timeScale: { borderColor: '#243449', timeVisible: true },
  });
  equitySeries = equityChart.addAreaSeries({ lineColor: '#00E5FF', topColor: 'rgba(0,229,255,0.28)', bottomColor: 'rgba(0,229,255,0.02)', lineWidth: 2, priceLineVisible: false, lastValueVisible: true });
  equitySeries.setData(equityCurve.map((p) => ({ time: Math.floor(p.t / 1000), value: p.equity })));
  // drawdown shading: area di bawah peak
  let peak = -Infinity;
  const dd = equityCurve.map((p) => { peak = Math.max(peak, p.equity); return { time: Math.floor(p.t / 1000), value: peak > 0 ? ((p.equity - peak) / peak) * 100 : 0 }; });
  try {
    ddSeries = equityChart.addLineSeries({ color: 'rgba(255,82,82,0.7)', lineWidth: 1, priceScaleId: 'dd', priceLineVisible: false, lastValueVisible: false });
    equityChart.priceScale('dd').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });
    ddSeries.setData(dd);
  } catch { /* abaikan */ }
  equityChart.timeScale().fitContent();
}
