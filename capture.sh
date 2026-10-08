#!/usr/bin/env bash
# Capture desktop + mobile screenshots of CAPTURE_URL into CAPTURE_DIR.
# Leaves the app running. Exit 75 = temp nav/browser infra failure, 1 = script/render defect.
set -euo pipefail
/usr/bin/time -p test -n "${CAPTURE_URL:?CAPTURE_URL must be set}"
/usr/bin/time -p test -n "${CAPTURE_DIR:?CAPTURE_DIR must be set}"
/usr/bin/time -p mkdir -p "$CAPTURE_DIR"
SESSION="capture-$$"

cleanup() { /usr/bin/time -p playwright-cli -s="$SESSION" close >/dev/null 2>&1 || true; }
trap cleanup EXIT

# 75: browser/navigation infrastructure failure (open itself failed).
/usr/bin/time -p playwright-cli -s="$SESSION" open "$CAPTURE_URL" || exit 75

# Wait for rendered content: readyState complete + non-empty body (up to ~20s).
ready=0
for _ in $(/usr/bin/time -p seq 1 20); do
  if /usr/bin/time -p playwright-cli -s="$SESSION" eval "document.readyState" 2>&1 | grep -q '"complete"'; then ready=1; break; fi
  /usr/bin/time -p sleep 1
done
[[ "$ready" == 1 ]] || exit 75

# 1: rendering defect (page loaded but nothing rendered).
body_len="$(/usr/bin/time -p playwright-cli -s="$SESSION" eval "document.body ? document.body.innerText.length : 0" 2>&1 | grep -A1 '^### Result' | tail -1 | tr -cd '0-9')"
[[ -n "$body_len" ]] && [[ "$body_len" -gt 0 ]] || exit 1

/usr/bin/time -p playwright-cli -s="$SESSION" resize 1280 800
/usr/bin/time -p sleep 1
/usr/bin/time -p playwright-cli -s="$SESSION" screenshot --filename "$CAPTURE_DIR/final-desktop.png"
/usr/bin/time -p test -s "$CAPTURE_DIR/final-desktop.png"

/usr/bin/time -p playwright-cli -s="$SESSION" resize 390 844
/usr/bin/time -p sleep 1
/usr/bin/time -p playwright-cli -s="$SESSION" screenshot --filename "$CAPTURE_DIR/final-mobile.png"
/usr/bin/time -p test -s "$CAPTURE_DIR/final-mobile.png"

echo "captured: $CAPTURE_DIR/final-desktop.png + final-mobile.png"
