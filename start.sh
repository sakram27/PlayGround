#!/usr/bin/env bash
# AetherSignalBot: build static output into ./dist and serve it in the foreground.
# Writes $OPENCODE_WEB_DIR/deployment-output.json for the controller.
set -euo pipefail
cd "$(dirname "$0")"
PROJECT_DIR="$PWD"
DIST_DIR="$PROJECT_DIR/dist"
PORT="${PORT:-3000}"

/usr/bin/time -p rm -rf "$DIST_DIR"
/usr/bin/time -p mkdir -p "$DIST_DIR/js"
/usr/bin/time -p cp index.html styles.css icon.svg "$DIST_DIR/"
/usr/bin/time -p cp -r js/app.js js/bundle.js js/charts.js js/core.js js/data.js js/icons.js "$DIST_DIR/js/"
/usr/bin/time -p mkdir -p "$DIST_DIR/js/vendor"
/usr/bin/time -p cp js/vendor/lightweight-charts.standalone.production.js "$DIST_DIR/js/vendor/"
/usr/bin/time -p test -f "$DIST_DIR/index.html"
/usr/bin/time -p test -f "$DIST_DIR/js/bundle.js"

PROJECT_DIR="$PROJECT_DIR" DIST_DIR="$DIST_DIR" OPENCODE_WEB_DIR="${OPENCODE_WEB_DIR:?OPENCODE_WEB_DIR must be set}" /usr/bin/time -p node -e "
const d = JSON.stringify({ project: process.env.PROJECT_DIR, directory: process.env.DIST_DIR });
require('fs').writeFileSync(process.env.OPENCODE_WEB_DIR + '/deployment-output.json', d);
"

PORT="$PORT" DIST_DIR="$DIST_DIR" /usr/bin/time -p node -e "
const http = require('http'), fs = require('fs'), path = require('path');
const root = process.env.DIST_DIR;
const mime = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };
http.createServer((req, res) => {
  try {
    const u = new URL(req.url, 'http://localhost');
    let p = path.resolve(root, '.' + decodeURIComponent(u.pathname));
    if (!p.startsWith(root)) { res.writeHead(404); res.end(); return; }
    if (fs.statSync(p).isDirectory()) p = path.join(p, 'index.html');
    res.setHeader('Content-Type', mime[path.extname(p)] || 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.end(fs.readFileSync(p));
  } catch { res.writeHead(404); res.end('Not found'); }
}).listen(Number(process.env.PORT || 3000), '0.0.0.0', () => console.log('serving ' + process.env.DIST_DIR + ' on :' + (process.env.PORT || 3000)));
"
