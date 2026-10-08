#!/usr/bin/env bash
# Capture desktop + mobile screenshots of the exact CAPTURE_URL into CAPTURE_DIR.
# Exit 75 = temporary navigation/browser infrastructure failure (handled downstream);
# exit 1 = script or rendering defect. Leaves the app server running.
set -euo pipefail
cd "$(dirname "$0")"
/usr/bin/time -p test -n "${CAPTURE_URL:?Set CAPTURE_URL}"
/usr/bin/time -p test -n "${CAPTURE_DIR:?Set CAPTURE_DIR}"
/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
/usr/bin/time -p node "${RUNTIME_DIR:?}/scripts/default-capture.mjs"
/usr/bin/time -p test -f "$CAPTURE_DIR/final-desktop.png"
/usr/bin/time -p test -f "$CAPTURE_DIR/final-mobile.png"
