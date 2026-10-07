#!/usr/bin/env bash
set -euo pipefail
# FreqDroid web preview: serve static dist/ in foreground on $PORT.
# Timing: every external command is prefixed with /usr/bin/time -p.
cd "$(dirname "$0")"
/usr/bin/time -p pwd
/usr/bin/time -p bash -c 'echo "project_dir=$(pwd)"'
PROJECT_DIR_ABS="$(pwd)"
# Resolve dirs (all source/output stay inside PROJECT_DIR)
DIST_DIR="$PROJECT_DIR_ABS/dist"
/usr/bin/time -p test -f "$DIST_DIR/index.html"
PORT="${PORT:-3000}"
/usr/bin/time -p bash -c "echo serving_dist=$DIST_DIR port=$PORT"
/usr/bin/time -p mkdir -p "${OPENCODE_WEB_DIR:-/home/runner/work/_temp/omgithub-web}"
# No npm dependencies for static preview; build step is a no-op verify.
/usr/bin/time -p bash -c 'test ! -f package.json || npm --version'
WEB_DIR="${OPENCODE_WEB_DIR:-/home/runner/work/_temp/omgithub-web}"
/usr/bin/time -p bash -c "echo web_dir=$WEB_DIR"
/usr/bin/time -p python3 -c "import json; open('$WEB_DIR/deployment-output.json','w').write(json.dumps({'project': '$PROJECT_DIR_ABS', 'directory': '$DIST_DIR'})); print('wrote deployment-output.json')"
/usr/bin/time -p cat "$WEB_DIR/deployment-output.json"
/usr/bin/time -p echo "listening on 0.0.0.0:$PORT -> $DIST_DIR"
/usr/bin/time -p python3 -m http.server "$PORT" --directory "$DIST_DIR" --bind 0.0.0.0
