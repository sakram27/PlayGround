#!/usr/bin/env bash
# Capture desktop + mobile screenshots of CAPTURE_URL into CAPTURE_DIR.
# Exit 75 = temporary navigation/browser infrastructure failure.
# Exit 1  = script or rendering defect.
set -euo pipefail
: "${CAPTURE_URL:?CAPTURE_URL must be set}"
: "${CAPTURE_DIR:?CAPTURE_DIR must be set}"
: "${RUNTIME_DIR:?RUNTIME_DIR must be set}"
/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
code=0
/usr/bin/time -p node "${RUNTIME_DIR}/scripts/default-capture.mjs" || code=$?
/usr/bin/time -p test -f "$CAPTURE_DIR/final-desktop.png"
/usr/bin/time -p test -f "$CAPTURE_DIR/final-mobile.png"
exit "$code"
