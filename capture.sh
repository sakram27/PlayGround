#!/usr/bin/env bash
set -euo pipefail
time -p cd "$(dirname "$0")"
/usr/bin/time -p bash -n capture.sh
/usr/bin/time -p test -n "${CAPTURE_URL:?Set CAPTURE_URL.}"
/usr/bin/time -p test -n "${CAPTURE_DIR:?Set CAPTURE_DIR.}"
/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
case "${CAPTURE_URL:?}" in
  http://*|https://*) ;;
  *) echo "CAPTURE_URL must be http(s): $CAPTURE_URL" >&2; exit 1 ;;
esac
# Delegate to the maintained runtime capture (headed Chromium, desktop+mobile).
# Exit 75 = temporary navigation/browser infra failure; exit 1 = script/rendering defect.
/usr/bin/time -p node "${RUNTIME_DIR:?}/scripts/default-capture.mjs"
status=$?
if [ $status -ne 0 ]; then
  echo "capture backend exited $status" >&2
  exit $status
fi
/usr/bin/time -p test -f "$CAPTURE_DIR/final-desktop.png"
/usr/bin/time -p test -f "$CAPTURE_DIR/final-mobile.png"
echo "captures ready in $CAPTURE_DIR"
