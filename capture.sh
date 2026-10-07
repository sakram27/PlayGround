#!/usr/bin/env bash
set -euo pipefail
# Capture desktop+mobile screenshots of CAPTURE_URL into CAPTURE_DIR.
# Leaves the app server running; output stays outside the project source.
cd "$(dirname "$0")"
/usr/bin/time -p bash -c 'echo "capture start"'
/usr/bin/time -p test -n "${CAPTURE_URL:?Set CAPTURE_URL}"
/usr/bin/time -p test -n "${CAPTURE_DIR:?Set CAPTURE_DIR}"
RUNTIME="${RUNTIME_DIR:-/home/runner/work/_temp/omgithub-runtime}"
/usr/bin/time -p test -f "$RUNTIME/scripts/default-capture.mjs"
/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
/usr/bin/time -p node "$RUNTIME/scripts/default-capture.mjs"
/usr/bin/time -p ls -lh "$CAPTURE_DIR/final-desktop.png" "$CAPTURE_DIR/final-mobile.png"
