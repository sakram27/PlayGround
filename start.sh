#!/usr/bin/env bash
set -euo pipefail
/usr/bin/time -p bash -c 'cd "$(dirname "/home/runner/work/PlayGround/PlayGround/start.sh")"'
cd "$(dirname "$0")"
PROJECT_DIR="/home/runner/work/PlayGround/PlayGround"
/usr/bin/time -p test -d "$PROJECT_DIR"
/usr/bin/time -p test -f "$PROJECT_DIR/dist/index.html"
PORT="${PORT:-3000}"
/usr/bin/time -p bash -c 'echo "PORT=$PORT"'
OPENCODE_WEB_DIR="${OPENCODE_WEB_DIR:-/home/runner/work/_temp/omgithub-web}"
/usr/bin/time -p mkdir -p "$OPENCODE_WEB_DIR"
/usr/bin/time -p bash -c 'echo "writing deployment-output.json"'
DEPLOY_DIR="$PROJECT_DIR/dist"
/usr/bin/time -p python3 -c "import json; json.dump({'project':'$PROJECT_DIR','directory':'$DEPLOY_DIR'}, open('$OPENCODE_WEB_DIR/deployment-output.json','w'))"
/usr/bin/time -p cat "$OPENCODE_WEB_DIR/deployment-output.json"
# Serve built static output in the foreground (no backgrounding).
exec /usr/bin/time -p python3 -m http.server "$PORT" --directory "$DEPLOY_DIR"
