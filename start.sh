#!/usr/bin/env bash
# Serve the built static Aether Signal preview in the foreground.
# - Source lives in PROJECT_DIR/aether-signal/www, built output in PROJECT_DIR/dist.
# - Writes $OPENCODE_WEB_DIR/deployment-output.json {project, directory}.
# - Serves $PORT (default 3000) in the foreground; the controller owns the tmux session.
set -euo pipefail
cd "$(dirname "$0")"
/usr/bin/time -p mkdir -p dist
/usr/bin/time -p rm -rf dist
/usr/bin/time -p cp -R aether-signal/www dist
/usr/bin/time -p test -f dist/index.html
/usr/bin/time -p node -e '
const fs = require("node:fs");
const path = require("node:path");
const project = process.cwd();
const directory = path.resolve("dist");
const outDir = process.env.OPENCODE_WEB_DIR;
if (!outDir) { console.error("OPENCODE_WEB_DIR is not set"); process.exit(1); }
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(
  path.join(outDir, "deployment-output.json"),
  JSON.stringify({ project, directory }),
);
console.log("deployment-output.json written for " + project);
'
/usr/bin/time -p node -e '
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const root = path.resolve("dist");
const mime = {
  ".html": "text/html", ".js": "application/javascript", ".css": "text/css",
  ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png",
  ".jpg": "image/jpeg", ".webp": "image/webp",
};
const server = http.createServer((req, res) => {
  try {
    const url = new URL(req.url, "http://localhost");
    let target = path.resolve(root, "." + decodeURIComponent(url.pathname));
    if (target !== root && !target.startsWith(root + "/")) {
      res.writeHead(404); res.end("Not found"); return;
    }
    let file = target;
    try {
      if (fs.statSync(file).isDirectory()) file = path.join(file, "index.html");
    } catch {
      file = path.join(root, "index.html");
    }
    const content = fs.readFileSync(file);
    res.setHeader("Content-Type", mime[path.extname(file)] || "application/octet-stream");
    res.setHeader("Cache-Control", "no-cache");
    res.end(content);
  } catch {
    res.writeHead(404); res.end("Not found");
  }
});
const port = Number(process.env.PORT || 3000);
server.listen(port, "0.0.0.0", () => console.log("Aether Signal preview serving dist/ on :" + port));
'
