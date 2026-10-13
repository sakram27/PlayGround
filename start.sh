#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
PORT="${PORT:-3000}"
PROJECT_ROOT="$(/usr/bin/time -p pwd)"
/usr/bin/time -p test -n "$PROJECT_ROOT"
/usr/bin/time -p mkdir -p "$PROJECT_ROOT/dist"
/usr/bin/time -p test -f "$PROJECT_ROOT/dist/index.html"
if /usr/bin/time -p test -f "$PROJECT_ROOT/package.json"; then
  if /usr/bin/time -p test -f "$PROJECT_ROOT/package-lock.json"; then
    /usr/bin/time -p npm ci --no-audit --no-fund
  else
    /usr/bin/time -p npm install --no-audit --no-fund
  fi
  if /usr/bin/time -p test -f "$PROJECT_ROOT/dist/index.html"; then
    /usr/bin/time -p echo "dist/index.html already present; skipping build"
  else
    /usr/bin/time -p npm run build
  fi
  /usr/bin/time -p test -f "$PROJECT_ROOT/dist/index.html"
fi
OPENCODE_WEB_DIR="${OPENCODE_WEB_DIR:-/home/runner/work/_temp/omgithub-web}"
/usr/bin/time -p mkdir -p "$OPENCODE_WEB_DIR"
/usr/bin/time -p printf '{"project":%s,"directory":%s}' "$(/usr/bin/time -p printf '%s' "$PROJECT_ROOT" | /usr/bin/time -p python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" "$(/usr/bin/time -p printf '%s' "$PROJECT_ROOT/dist" | /usr/bin/time -p python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" > "$OPENCODE_WEB_DIR/deployment-output.json"
/usr/bin/time -p cat "$OPENCODE_WEB_DIR/deployment-output.json"
/usr/bin/time -p echo "Serving $PROJECT_ROOT/dist on PORT $PORT"
/usr/bin/time -p python3 -m http.server "$PORT" --directory "$PROJECT_ROOT/dist"
