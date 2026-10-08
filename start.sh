#!/usr/bin/env bash
# Serve the built static download page (foreground) and publish deployment output.
# Source + built output stay inside PROJECT_DIR (./dist).
set -euo pipefail
cd "$(dirname "$0")"
PROJECT_DIR="$(/usr/bin/time -p pwd)"
PORT="${PORT:-3000}"
WEB_DIR="${OPENCODE_WEB_DIR:-/home/runner/work/_temp/omgithub-web}"

/usr/bin/time -p test -f dist/index.html
/usr/bin/time -p mkdir -p "$WEB_DIR"
/usr/bin/time -p python3 - "$WEB_DIR" "$PROJECT_DIR/dist" <<'PY'
import json, sys
web_dir, directory = sys.argv[1], sys.argv[2]
with open(f"{web_dir}/deployment-output.json", "w") as f:
    json.dump({"project": "/home/runner/work/PlayGround/PlayGround",
               "directory": directory}, f)
print(f"deployment-output.json -> directory={directory}")
PY
echo "Serving $PROJECT_DIR/dist on http://127.0.0.1:$PORT (foreground)"
/usr/bin/time -p python3 -m http.server "$PORT" --directory "$PROJECT_DIR/dist"
