#!/usr/bin/env bash
# Once-per-worker prerequisites.
set -euo pipefail
/usr/bin/time -p playwright-cli install-browser chromium
