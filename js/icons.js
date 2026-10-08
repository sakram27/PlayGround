/* Ikon pair: logo asli via CDN + fallback lokal (tetap tampil offline).
 * Crypto: atomiclabs/cryptocurrency-icons. Forex: flagcdn. XAU/XAG: SVG lokal.
 */
'use strict';

const COIN_CDN = 'https://cdn.jsdelivr.net/gh/atomiclabs/cryptocurrency-icons@1a63530be6e374711a2b01b17b17f3a4c552717f/128/color/';
const QUOTES = ['USDT', 'USDC', 'FDUSD', 'TUSD', 'BUSD', 'BTC', 'ETH', 'BNB', 'EUR', 'USD', 'IDR', 'TRY'];
const COIN_IDS = new Set(('btc eth usdt sol bnb xrp doge ada avax link dot matic trx ltc bch near atom arb op inj sui pepe ton etc fil apt wbtc steth usdc fdusd dai shib atom uni fog'.split(' ')));

function baseAsset(pair) {
  const s = String(pair || '').toUpperCase().replace(/[^A-Z]/g, '');
  for (const q of QUOTES) {
    if (s.length > q.length && s.endsWith(q)) return s.slice(0, -q.length);
  }
  return s;
}
const FLAG = {
  EUR: 'eu', USD: 'us', GBP: 'gb', JPY: 'jp', AUD: 'au', CAD: 'ca', CHF: 'ch', NZD: 'nz',
  IDR: 'id', SGD: 'sg', MYR: 'my', INR: 'in', CNY: 'cn', KRW: 'kr', TRY: 'tr', ZAR: 'za',
};
const METAL_SVG = {
  XAU: '<svg viewBox="0 0 32 32" width="100%" height="100%"><circle cx="16" cy="16" r="15" fill="#f5c542"/><circle cx="16" cy="16" r="11.5" fill="none" stroke="#a87500" stroke-width="1.6"/><text x="16" y="21" text-anchor="middle" font-size="13" font-weight="900" fill="#5c3d00">Au</text></svg>',
  XAG: '<svg viewBox="0 0 32 32" width="100%" height="100%"><circle cx="16" cy="16" r="15" fill="#cfd8e3"/><circle cx="16" cy="16" r="11.5" fill="none" stroke="#7d8ea3" stroke-width="1.6"/><text x="16" y="21" text-anchor="middle" font-size="13" font-weight="900" fill="#3c4a5c">Ag</text></svg>',
};

function esc(s) { return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

function wrap(inner, letters, label) {
  const fb = '<span class="ic-fb">' + esc(letters) + '</span>';
  return '<span class="ic" title="' + esc(label) + '">' + fb + inner + '</span>';
}

/* Ikon utama: otomatis crypto vs forex/XAU dari format label */
export function pairIcon(symOrLabel) {
  const raw = String(symOrLabel || '');
  if (raw.includes('/') || raw.includes(' ')) return fxIcon(raw);
  const base = baseAsset(raw);
  if (base === 'XAU' || base === 'GOLD') return wrap(METAL_SVG.XAU, 'Au', raw);
  if (base === 'XAG' || base === 'SILVER') return wrap(METAL_SVG.XAG, 'Ag', raw);
  const id = base.toLowerCase();
  const letters = base.slice(0, base.length > 3 ? 3 : 4);
  if (!COIN_IDS.has(id)) return wrap('', letters || '?', raw);
  const img = '<img src="' + COIN_CDN + id + '.png" alt="" loading="lazy" onerror="this.remove()">';
  return wrap(img, letters, raw);
}

export function fxIcon(label) {
  const parts = String(label || '—').split(/[/\s]/).filter(Boolean).map((x) => x.toUpperCase());
  const base = (parts[0] || '').replace(/[^A-Z]/g, '');
  if (base === 'XAU') return wrap(METAL_SVG.XAU, 'Au', label);
  if (base === 'XAG') return wrap(METAL_SVG.XAG, 'Ag', label);
  if (base === 'BTC' || base === 'ETH') return pairIcon(base + 'USDT');
  const cc = FLAG[base];
  const letters = base.slice(0, 2) || '?';
  if (!cc) return wrap('', letters, label);
  const img = '<img src="https://flagcdn.com/w80/' + cc + '.png" srcset="https://flagcdn.com/w160/' + cc + '.png 2x" alt="" loading="lazy" onerror="this.remove()">';
  return wrap(img, letters, label);
}
