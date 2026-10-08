#!/usr/bin/env bash
set -euo pipefail
/usr/bin/time -p bash -c 'cd "$(dirname "/home/runner/work/PlayGround/PlayGround/capture.sh")"'
cd "$(dirname "$0")"
/usr/bin/time -p test -n "${CAPTURE_URL:-}"
/usr/bin/time -p test -n "${CAPTURE_DIR:-}"
/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
/usr/bin/time -p bash -c 'echo "capturing $CAPTURE_URL -> $CAPTURE_DIR"'
RUNTIME_DIR="${RUNTIME_DIR:-/home/runner/work/_temp/omgithub-runtime}"
/usr/bin/time -p test -f "$RUNTIME_DIR/scripts/default-capture.mjs"
/usr/bin/time -p node "$RUNTIME_DIR/scripts/default-capture.mjs"
STATUS=$?
/usr/bin/time -p ls -la "$CAPTURE_DIR"
exit $STATUS
