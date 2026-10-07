#!/usr/bin/env bash
set -euo pipefail
# FreqTrade Mobile web preview — static build in PROJECT_DIR/dist, serve on $PORT.
cd "$(dirname "$0")"

PORT="${PORT:-3000}"
PROJECT_ROOT="$(/usr/bin/time -p pwd)"
WEB_SRC="$PROJECT_ROOT/web"
OUT_DIR="$PROJECT_ROOT/dist"
META_DIR="${OPENCODE_WEB_DIR:-/home/runner/work/_temp/omgithub-web}"

/usr/bin/time -p mkdir -p "$OUT_DIR" "$META_DIR"
/usr/bin/time -p test -f "$WEB_SRC/index.html"

/usr/bin/time -p cp -f "$WEB_SRC/index.html" "$OUT_DIR/index.html"
/usr/bin/time -p cp -f "$WEB_SRC/styles.css" "$OUT_DIR/styles.css"
/usr/bin/time -p cp -f "$WEB_SRC/app.js" "$OUT_DIR/app.js"
/usr/bin/time -p test -f "$OUT_DIR/index.html"

export PROJECT_ROOT OUT_DIR META_DIR
/usr/bin/time -p bash -c 'printf "{\"project\":\"%s\",\"directory\":\"%s\"}" "$PROJECT_ROOT" "$OUT_DIR" > "$META_DIR/deployment-output.json"'
/usr/bin/time -p cat "$META_DIR/deployment-output.json"
echo "Serving $OUT_DIR on port $PORT (project: $PROJECT_ROOT)"

exec python3 -m http.server "$PORT" --directory "$OUT_DIR" --bind 0.0.0.0
