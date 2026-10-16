#!/usr/bin/env bash
set -euo pipefail
/usr/bin/time -p bash -c 'cd "$(dirname "$0")" && pwd'
cd "$(dirname "$0")"
/usr/bin/time -p pwd
PORT="${PORT:-3000}"
export PORT
/usr/bin/time -p bash -c 'echo "PORT=$PORT"'
PROJECT_ROOT="$(pwd)"
/usr/bin/time -p pwd >/dev/null
DIST_DIR="$PROJECT_ROOT/dist"
STATIC_DIR="$DIST_DIR"
if [ -f "$PROJECT_ROOT/package.json" ]; then
  /usr/bin/time -p node -e "const p=require('./package.json');process.exit(p.dependencies&&p.dependencies.vite||p.devDependencies&&p.devDependencies.vite?0:1)" && IS_VITE=1 || IS_VITE=0
  if [ "$IS_VITE" = "1" ]; then
    if [ -f "$PROJECT_ROOT/package-lock.json" ]; then /usr/bin/time -p npm ci --no-audit --no-fund; else /usr/bin/time -p npm install --no-audit --no-fund; fi
    /usr/bin/time -p npm run build
    STATIC_DIR="$DIST_DIR"
  else
    echo "package.json without vite: serving static dir" >&2
    if [ -f "$PROJECT_ROOT/index.html" ]; then STATIC_DIR="$PROJECT_ROOT"; fi
  fi
else
  /usr/bin/time -p test -f "$DIST_DIR/index.html"
fi
/usr/bin/time -p test -f "$STATIC_DIR/index.html"
WEB_DIR="${OPENCODE_WEB_DIR:-/home/runner/work/_temp/omgithub-web}"
/usr/bin/time -p mkdir -p "$WEB_DIR"
/usr/bin/time -p bash -c "case \"$STATIC_DIR\" in \"$PROJECT_ROOT\"*) exit 0;; *) exit 1;; esac"
/usr/bin/time -p printf '{"project":"%s","directory":"%s"}\n' "$PROJECT_ROOT" "$STATIC_DIR" > "$WEB_DIR/deployment-output.json"
/usr/bin/time -p cat "$WEB_DIR/deployment-output.json"
echo "Serving $STATIC_DIR on 0.0.0.0:$PORT (project=$PROJECT_ROOT)" >&2
exec python3 -m http.server "$PORT" --directory "$STATIC_DIR" --bind 0.0.0.0
