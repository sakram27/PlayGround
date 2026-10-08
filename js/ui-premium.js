/* Aether Signal — lapisan presentasi premium (classic script, offline-safe file://).
 * ATURAN KERAS: dilarang menghitung ulang logika trading. File ini HANYA:
 *  - menata ulang DOM yang sudah dirender engine (cermin Dashboard),
 *  - mengelompokkan filter (pindah node, tanpa ubah nilai),
 *  - preferensi tampilan, jam, dan interpreter deskriptif untuk AI Analysis
 *    (agregat DATA dari localStorage/DOM berlabel DATA/ANALYSIS/OPINION).
 * Engine (bundle/core/data) tidak disentuh.
 */
(function () {
  'use strict';
  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function store(k, d) { try { var v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } }
  function fmtN(n, d) {
    if (!isFinite(n)) return '—';
    return Number(n).toLocaleString('id-ID', { minimumFractionDigits: d == null ? 2 : d, maximumFractionDigits: d == null ? 2 : d });
  }

  /* ---------- jam + status feed ---------- */
  function tickClock() {
    try {
      var d = new Date();
      var p = function (x) { return String(x).padStart(2, '0'); };
      $('clock').textContent = p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
      var cd = $('countdown');
      if (cd && !$('featCd').classList) return;
      var f = $('featCd');
      if (f && cd && cd.textContent) { f.textContent = cd.textContent; f.classList.remove('hidden'); }
      else if (f) f.classList.add('hidden');
    } catch (e) { /* abaikan */ }
  }

  /* ---------- 1. Pengelompokan filter (pindah node saja) ---------- */
  var FGROUPS = [
    ['Trend', ['trend', 'ema', 'htf']],
    ['Volume', ['volume', 'min_vol', 'max_vol']],
    ['Volatilitas', ['atr_vol']],
    ['Momentum', ['adx', 'rsi']],
    ['Struktur', ['ms', 'sr']],
    ['Likuiditas', ['liq']],
    ['Sesi', ['session']],
    ['Proteksi', ['cooldown', 'dup']],
  ];
  function groupFilters() {
    try {
      var box = $('filterChecks');
      if (!box || box.dataset.grouped) return;
      var labels = Array.prototype.slice.call(box.querySelectorAll('label'));
      if (!labels.length) return; // engine belum render — coba lagi nanti
      box.dataset.grouped = '1';
      box.innerHTML = '';
      FGROUPS.forEach(function (g) {
        var wrap = document.createElement('div');
        wrap.className = 'fgroup';
        var h = document.createElement('b'); h.textContent = g[0]; wrap.appendChild(h);
        var inner = document.createElement('div'); inner.className = 'checks'; inner.style.flexDirection = 'column'; inner.style.alignItems = 'stretch';
        g[1].forEach(function (id) {
          var lb = labels.filter(function (l) { var i = l.querySelector('input'); return i && i.value === id; })[0];
          if (lb) inner.appendChild(lb);
        });
        if (!inner.children.length) return;
        wrap.appendChild(inner); box.appendChild(wrap);
      });
      // sisa tak dikenal (jaga-jaga versi engine lain)
      labels.forEach(function (lb) { if (!lb.parentNode || lb.parentNode === box) return; if (!lb.isConnected) box.appendChild(lb); });
    } catch (e) { /* abaikan */ }
  }

  /* ---------- 2. Cermin Dashboard (dari DOM + localStorage engine) ---------- */
  function readMarketRows() {
    var out = [];
    try {
      var trs = document.querySelectorAll('#tblMkt tbody tr[data-sym]');
      trs.forEach(function (tr) {
        var tds = tr.querySelectorAll('td');
        if (tds.length < 4) return;
        out.push({ sym: tr.getAttribute('data-sym'), price: tds[1].textContent.trim(), chg: tds[2].textContent.trim(), sig: tds[3].textContent.trim() });
      });
    } catch (e) { /* abaikan */ }
    return out;
  }
  function paintStrip() {
    var el = $('mktStrip'); if (!el) return;
    var rows = readMarketRows().slice(0, 10);
    if (!rows.length) { el.innerHTML = '<div class="strip-empty">Market overview — buka Markets lalu Muat untuk mengisi strip ini.</div>'; return; }
    el.innerHTML = rows.map(function (r) {
      var bear = r.chg.charAt(0) === '-';
      return '<button class="pause" data-goto="market"><small>' + esc(r.sym) + '</small><b>' + esc(r.price) + '</b><i class="' + (bear ? 'dn' : 'up') + '">' + esc(r.chg) + ' · ' + esc(r.sig) + '</i></button>';
    }).join('');
  }
  function paintFeatured() {
    var box = $('lastSignal'); if (!box) return;
    var sigs = store('aether_signals', []);
    var s = sigs[0];
    if (!s) { box.innerHTML = '<p class="muted">Belum ada sinyal — Start engine atau jalankan backtest.</p>'; paintRecent(sigs); return; }
    var long = s.dir === 'LONG';
    var risk = Math.abs(s.price - s.sl), rew = Math.abs(s.tp - s.price);
    var rr = risk > 0 ? (rew / risk) : NaN;
    var rrPct = isFinite(rr) ? Math.min(100, Math.round((rr / (rr + 1)) * 100)) : 0;
    box.innerHTML =
      '<div class="feat-grid" style="grid-column:1/-1"><div><div class="feat-sym">' + esc(s.pair) + '</div>' +
      '<div class="row" style="margin-top:6px"><span class="feat-dir ' + (long ? 'long' : 'short') + '">' + esc(s.dir) + '</span>' +
      '<span class="muted" style="font-size:12px">' + esc(s.tf || '') + ' · ' + new Date(s.t).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</span></div></div></div>' +
      '<div class="feat-kv"><small>ENTRY</small><b>' + fmtN(s.price, 4) + '</b></div>' +
      '<div class="feat-kv"><small>STOP</small><b>' + fmtN(s.sl, 4) + '</b></div>' +
      '<div class="feat-kv"><small>TARGET</small><b>' + fmtN(s.tp, 4) + '</b></div>' +
      '<div class="feat-kv"><small>RISK / REWARD</small><b>1 : ' + (isFinite(rr) ? rr.toFixed(1) : '—') + '</b><div class="confbar"><i style="width:' + rrPct + '%"></i></div></div>';
    // penting: pertahankan data-sig agar klik → detail engine
    var wrap = document.createElement('div');
    wrap.setAttribute('data-sig', s.id); wrap.style.display = 'contents';
    while (box.firstChild) wrap.appendChild(box.firstChild);
    box.appendChild(wrap);
    paintRecent(sigs);
  }
  function paintRecent(sigs) {
    var el = $('recentSig'); if (!el) return;
    sigs = sigs || store('aether_signals', []);
    if (!sigs.length) { el.innerHTML = '<p class="muted" style="padding:4px 2px">Belum ada — sinyal dari engine/backtest tampil di sini.</p>'; return; }
    el.innerHTML = sigs.slice(0, 5).map(function (x) {
      return '<div class="srow" data-sig="' + esc(x.id) + '"><span class="dir ' + (x.dir === 'LONG' ? 'long' : 'short') + '">' + esc(x.dir) + '</span>' +
        '<span><span class="s-pair">' + esc(x.pair) + '</span><br><span class="s-sub">' + esc(x.tf || '') + ' · ' + new Date(x.t).toLocaleString('id-ID', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) + '</span></span>' +
        '<span class="s-conf">' + fmtN(x.price, 4) + '</span></div>';
    }).join('');
  }
  function paintCond() {
    var el = $('mktCond'); if (!el) return;
    var rows = readMarketRows();
    var sigs = store('aether_signals', []);
    var hist = store('aether_hist', []);
    var longs = 0, shorts = 0;
    rows.forEach(function (r) { if (r.sig === 'LONG') longs++; else if (r.sig === 'SHORT') shorts++; });
    sigs.slice(0, 20).forEach(function (x) { if (x.dir === 'LONG') longs++; else if (x.dir === 'SHORT') shorts++; });
    var bias = longs === 0 && shorts === 0 ? ['NETRAL', ''] : longs > shorts ? ['BULLISH', 'up'] : shorts > longs ? ['BEARISH', 'dn'] : ['SEIMBANG', 'warn'];
    var chgs = rows.map(function (r) { return parseFloat(String(r.chg).replace('%', '').replace(',', '.')); }).filter(isFinite);
    var med = chgs.length ? chgs.slice().sort(function (a, b) { return a - b; })[Math.floor(chgs.length / 2)] : NaN;
    var eng = 'STOP', engCls = '';
    try { eng = ($('botBadge') && $('botBadge').textContent.trim()) || 'STOP'; engCls = eng === 'RUN' ? 'up' : ''; } catch (e) { /* abaikan */ }
    function cell(l, v, c) { return '<div><small>' + l + '</small><b class="' + c + '">' + v + '</b></div>'; }
    el.innerHTML =
      cell('BIAS', esc(bias[0]), bias[1]) +
      cell('LONG/SHORT', longs + ' / ' + shorts, '') +
      cell('MEDIAN 24H', isFinite(med) ? (med >= 0 ? '+' : '') + med.toFixed(2) + '%' : '—', isFinite(med) ? (med >= 0 ? 'up' : 'dn') : '') +
      cell('SIGNALS', String(sigs.length), '') +
      cell('ENGINE', esc(eng), engCls);
    void hist;
  }
  function refreshMirrors() { try { paintStrip(); } catch (e) {} try { paintFeatured(); } catch (e) {} try { paintCond(); } catch (e) {} }

  /* ---------- 3. Tombol "konteks teknis" pada status error ---------- */
  function armTech(btnHost, getMsg) {
    try {
      if (!btnHost || btnHost.dataset.teched) return;
      btnHost.dataset.teched = '1';
      var b = document.createElement('button');
      b.className = 'techbtn'; b.textContent = 'Salin konteks teknis';
      b.addEventListener('click', function () {
        var ctx = 'Aether Signal · ' + new Date().toISOString() + '\nPesan: ' + getMsg() +
          '\nPair: ' + ((($('pairLabel') || {}).textContent || '').trim()) +
          ' · Provider: ' + ((($('provider') || {}).value || '')) +
          ' · TF: ' + ((($('timeframe') || {}).value || ''));
        try { navigator.clipboard.writeText(ctx).then(function () { b.textContent = 'Tersalin.'; }); }
        catch (e) { b.textContent = ctx; }
      });
      btnHost.appendChild(b);
    } catch (e) { /* abaikan */ }
  }
  function watchErrors() {
    try {
      var st = $('status');
      if (st && st.classList.contains('err') && st.textContent) armTech(st, function () { return st.textContent; });
      var ms = $('mktStatus');
      if (ms && /gagal|error/i.test(ms.textContent || '')) armTech(ms, function () { return ms.textContent; });
    } catch (e) { /* abaikan */ }
  }

  /* ---------- 4. AI Analysis (interpreter deskriptif, bukan engine) ---------- */
  function aiAnalyze() {
    var box = $('aiContent'), st = $('aiStatus');
    try { st.textContent = 'Menganalisis…'; } catch (e) {}
    setTimeout(function () {
      try {
        var sigs = store('aether_signals', []), hist = store('aether_hist', []);
        var deep = ($('aiDepth') && $('aiDepth').value === 'mendalam') || (store('aether_ui', {}).aiDepth === 'mendalam');
        var sumLine = (($('summaryLine') || {}).textContent || '').trim();
        if (!sigs.length && !hist.length && !sumLine) {
          box.innerHTML = '<div class="panel"><div class="empty-cell">Belum ada data engine.<br><span class="muted">Jalankan backtest atau Start engine dulu, lalu Analisis Sekarang.</span></div></div>';
          st.textContent = 'Siap — belum ada data.';
          return;
        }
        var wins = hist.filter(function (t) { return t.result === 'WIN'; }).length;
        var net = hist.reduce(function (s, t) { return s + (t.pnl || 0); }, 0);
        var wr = hist.length ? (wins / hist.length) * 100 : NaN;
        var byPair = {};
        hist.forEach(function (t) { var k = t.asset || t.pair || '?'; byPair[k] = byPair[k] || { n: 0, w: 0, net: 0 }; byPair[k].n++; if (t.result === 'WIN') byPair[k].w++; byPair[k].net += (t.pnl || 0); });
        var pairs = Object.keys(byPair).sort(function (a, b) { return byPair[b].net - byPair[a].net; });
        var longs = sigs.filter(function (x) { return x.dir === 'LONG'; }).length;
        var shorts = sigs.filter(function (x) { return x.dir === 'SHORT'; }).length;
        var last = sigs[0];
        function blk(t, items) { return '<div class="aiblock"><h4>' + t + '</h4><ul>' + items.map(function (x) { return '<li>' + x + '</li>'; }).join('') + '</ul></div>'; }
        var D = '<span class="tag data">DATA</span>', A = '<span class="tag an">ANALYSIS</span>', O = '<span class="tag op">OPINION</span>';
        var html = '';
        html += blk('AI Market Summary', [
          D + 'Sinyal tersimpan: <b>' + sigs.length + '</b> (LONG ' + longs + ' / SHORT ' + shorts + '). Trade riwayat: <b>' + hist.length + '</b>.',
          A + (longs + shorts === 0 ? 'Tidak ada bias arah yang terbaca — engine belum menghasilkan sinyal.' : longs > shorts ? 'Aliran sinyal condong LONG (' + longs + ' vs ' + shorts + ').' : shorts > longs ? 'Aliran sinyal condong SHORT (' + shorts + ' vs ' + longs + ').' : 'Aliran sinyal seimbang LONG/SHORT.'),
          deep && pairs.length ? A + 'Pair teratas by net: ' + pairs.slice(0, 3).map(function (k) { return esc(k) + ' (' + fmtN(byPair[k].net) + ')'; }).join(', ') + '.' : A + 'Cakupan pair mengikuti konfigurasi Backtest &amp; Market Hub.'
        ]);
        html += blk('Signal Analysis', last ? [
          D + 'Sinyal terakhir: <b>' + esc(last.dir) + ' ' + esc(last.pair) + '</b> ' + esc(last.tf || '') + ' @ ' + fmtN(last.price, 4) + ' (SL ' + fmtN(last.sl, 4) + ' / TP ' + fmtN(last.tp, 4) + ', via ' + esc(last.src || '—') + ').',
          A + 'Level risiko/imbalan dibaca langsung dari level engine — bukan rekomendasi baru.',
          O + 'Pastikan setup selaras dengan bias timeframe lebih besar sebelum menindaklanjuti.'
        ] : [D + 'Belum ada sinyal tersimpan.', O + 'Jalankan backtest atau Start engine untuk menghasilkan sinyal.']);
        html += blk('Risk Analysis', hist.length ? [
          D + 'Net riwayat ' + fmtN(net) + ' dari ' + hist.length + ' trade · win rate ' + (isFinite(wr) ? wr.toFixed(1) + '%' : '—') + '.',
          A + (isFinite(wr) ? (wr >= 50 ? 'Proporsi menang di atas setengah — jaga disiplin risiko agar expectancy bertahan.' : 'Proporsi menang di bawah setengah — strategi mengandalkan payoff per trade; waspadai drawdown.') : 'Belum cukup sampel untuk menilai.'),
          O + 'Batasi risiko per trade kecil dan hindari leverage tinggi saat volatilitas melebar.'
        ] : [D + 'Belum ada trade riwayat.', O + 'Ukur risiko lewat backtest dulu sebelum live/paper.']);
        html += blk('Strategy & Backtest Analysis', [
          D + (sumLine && sumLine !== 'Belum ada hasil.' ? esc(sumLine) : 'Belum ada ringkasan backtest sesi ini.'),
          A + (pairs.length ? 'Distribusi performa terkonsentrasi pada: ' + pairs.slice(0, 3).map(esc).join(', ') + '.' : 'Jalankan backtest multi-pair untuk melihat distribusi per pair.'),
          O + 'Strategi yang bagus di backtest belum tentu bagus ke depan — uji di beberapa timeframe sebelum dipercaya.'
        ]);
        html += blk('Recommendation', [
          O + 'Fokus: 1) pastikan provider OK (Settings → Tes Koneksi), 2) mulai dari 1 pair + 1 strategi, 3) catat setiap anomali sebelum mengubah parameter.',
          O + 'Semua angka di atas berasal dari engine — tidak ada data buatan.'
        ]);
        box.innerHTML = html;
        st.textContent = 'Selesai — ' + sigs.length + ' sinyal, ' + hist.length + ' trade dibaca.';
      } catch (e) { try { $('aiStatus').textContent = 'Gagal menyusun: ' + e.message; } catch (x) {} }
    }, 30);
  }

  /* ---------- 5. Preferensi tampilan ---------- */
  function applyUiPrefs() {
    try {
      var p = store('aether_ui', {});
      document.body.classList.toggle('dense', p.density === 'rapat');
      document.body.classList.toggle('num-big', p.numsize === 'besar');
      document.body.classList.toggle('nomotion', p.motion === 'kurang');
      if ($('setDensity') && p.density) $('setDensity').value = p.density;
      if ($('setNumsize') && p.numsize) $('setNumsize').value = p.numsize;
      if ($('setMotion') && p.motion) $('setMotion').value = p.motion;
      if ($('setAiDepth') && p.aiDepth) $('setAiDepth').value = p.aiDepth;
      if ($('aiDepth') && ! $('aiDepth').dataset.synced) { if (p.aiDepth) $('aiDepth').value = p.aiDepth; $('aiDepth').dataset.synced = '1'; }
    } catch (e) { /* abaikan */ }
  }
  function saveUiPrefs() {
    try {
      var cur = store('aether_ui', {});
      var p = {
        density: ($('setDensity') || {}).value || cur.density || 'nyaman',
        numsize: ($('setNumsize') || {}).value || cur.numsize || 'normal',
        motion: ($('setMotion') || {}).value || cur.motion || 'penuh',
        aiDepth: ($('setAiDepth') || {}).value || cur.aiDepth || 'ringkas',
      };
      localStorage.setItem('aether_ui', JSON.stringify(p));
      applyUiPrefs();
    } catch (e) { /* abaikan */ }
  }

  /* ---------- init ---------- */
  function init() {
    applyUiPrefs();
    tickClock(); setInterval(tickClock, 1000);
    var n = 0;
    var iv = setInterval(function () {
      groupFilters(); refreshMirrors(); watchErrors();
      if (++n > 40 && document.getElementById('filterChecks').dataset.grouped) { /* tetap pantau ringan */ }
      if (n > 600) clearInterval(iv);
    }, 1500);
    document.addEventListener('click', function () { setTimeout(function () { refreshMirrors(); groupFilters(); watchErrors(); }, 400); });
    try { $('aiRun').addEventListener('click', aiAnalyze); } catch (e) { /* abaikan */ }
    ['setDensity', 'setNumsize', 'setMotion', 'setAiDepth'].forEach(function (id) {
      try { $(id).addEventListener('change', saveUiPrefs); } catch (e) { /* abaikan */ }
    });
    try {
      var orig = window.__aetherShow;
      window.__aetherShow = function (name) {
        orig(name);
        if (name === 'ai') { /* konten dirender on-demand via tombol */ }
        setTimeout(refreshMirrors, 120);
      };
    } catch (e) { /* abaikan */ }
    window.__aetherPremium = { version: 'redesign-1', refresh: refreshMirrors };
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
