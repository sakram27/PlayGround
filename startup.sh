#!/usr/bin/env bash
# Once-per-worker prerequisites for the AetherSignalBot static project.
set -euo pipefail
/usr/bin/time -p node --version
/usr/bin/time -p test -x /usr/local/bin/node
# Headed chromium for capture (linux.json uses channel "chromium", headless:false).
if /usr/bin/time -p test -d "$HOME/.cache/ms-playwright/chromium-1248" || /usr/bin/time -p test -d "$HOME/.cache/ms-playwright/chromium-1243"; then
  echo 'playwright chromium present'
else
  /usr/bin/time -p playwright-cli install-browser chromium
fi
