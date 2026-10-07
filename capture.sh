#!/usr/bin/env bash
set -euo pipefail
# Capture desktop + mobile screenshots of $CAPTURE_URL into $CAPTURE_DIR.
cd "$(dirname "$0")"

URL="${CAPTURE_URL:-}"
OUT="${CAPTURE_DIR:-}"
if [[ -z "$URL" || -z "$OUT" ]]; then
  echo "Set CAPTURE_URL and CAPTURE_DIR." >&2
  exit 1
fi

# Linux headed Chromium needs the persistent Xvfb display.
if [[ -f "$HOME/.local/share/omgithub-playwright/display" && -z "${DISPLAY:-}" ]]; then
  /usr/bin/time -p bash -c 'export DISPLAY=:$(cat "$HOME/.local/share/omgithub-playwright/display"); echo "DISPLAY=$DISPLAY"'
  export DISPLAY=":$(/usr/bin/time -p cat "$HOME/.local/share/omgithub-playwright/display")"
fi

/usr/bin/time -p mkdir -p "$OUT"

# Fail fast with correct exit codes: 75 = transient infra, 1 = rendering/script defect.
HTTP="$(/usr/bin/time -p curl --silent --location --max-time 15 --output /dev/null --write-out '%{http_code}' "$URL")" || { echo "curl failed (transient)" >&2; exit 75; }
case "$HTTP" in
  2*) echo "HTTP $HTTP OK: $URL" ;;
  408|429|500|502|503|504) echo "HTTP $HTTP transient loading preview" >&2; exit 75 ;;
  *) echo "HTTP $HTTP loading preview" >&2; exit 1 ;;
esac

SESSION="cap-$$"
/usr/bin/time -p playwright-cli -s="$SESSION" open "$URL" --headed
cleanup() { /usr/bin/time -p playwright-cli -s="$SESSION" close 2>/dev/null || true; }
/usr/bin/time -p playwright-cli -s="$SESSION" eval "document.readyState"
/usr/bin/time -p sleep 2
# Rendered-content check (defect if missing).
if ! /usr/bin/time -p playwright-cli -s="$SESSION" snapshot | /usr/bin/time -p grep -iq "FreqTrade"; then
  echo "Rendered content check failed: 'FreqTrade' not found" >&2
  cleanup
  exit 1
fi

# Desktop 1440x900
/usr/bin/time -p playwright-cli -s="$SESSION" resize 1440 900
/usr/bin/time -p sleep 1
if ! /usr/bin/time -p playwright-cli -s="$SESSION" screenshot --filename "$OUT/final-desktop.png"; then
  echo "Desktop screenshot failed (transient)" >&2; cleanup; exit 75
fi

# Mobile 390x844 (same page, narrow viewport)
/usr/bin/time -p playwright-cli -s="$SESSION" resize 390 844
/usr/bin/time -p sleep 1
if ! /usr/bin/time -p playwright-cli -s="$SESSION" screenshot --filename "$OUT/final-mobile.png"; then
  echo "Mobile screenshot failed (transient)" >&2; cleanup; exit 75
fi

/usr/bin/time -p test -f "$OUT/final-desktop.png"
/usr/bin/time -p test -f "$OUT/final-mobile.png"
cleanup
echo "Captured $OUT/final-desktop.png + $OUT/final-mobile.png (app left running)"
