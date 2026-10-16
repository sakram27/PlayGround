#!/usr/bin/env bash
set -euo pipefail
/usr/bin/time -p bash -c 'cd "$(dirname "$0")" && pwd'
cd "$(dirname "$0")"
if [ -z "${CAPTURE_URL:-}" ]; then echo "CAPTURE_URL is required" >&2; exit 1; fi
if [ -z "${CAPTURE_DIR:-}" ]; then echo "CAPTURE_DIR is required" >&2; exit 1; fi
/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
/usr/bin/time -p bash -c 'echo "capturing $CAPTURE_URL -> $CAPTURE_DIR"'
RUNTIME="${RUNTIME_DIR:-/home/runner/work/_temp/omgithub-runtime}"
/usr/bin/time -p test -f "$RUNTIME/scripts/default-capture.mjs"
/usr/bin/time -p node "$RUNTIME/scripts/default-capture.mjs"
STATUS=$?
if [ $STATUS -ne 0 ]; then echo "capture backend exited $STATUS" >&2; exit $STATUS; fi
/usr/bin/time -p test -f "$CAPTURE_DIR/final-desktop.png"
/usr/bin/time -p test -f "$CAPTURE_DIR/final-mobile.png"
/usr/bin/time -p bash -c 'test -s "$CAPTURE_DIR/final-desktop.png"'
/usr/bin/time -p bash -c 'test -s "$CAPTURE_DIR/final-mobile.png"'
/usr/bin/time -p ls -lh "$CAPTURE_DIR/final-desktop.png" "$CAPTURE_DIR/final-mobile.png"
echo "capture ok, app left running" >&2
