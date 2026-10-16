#!/usr/bin/env bash
set -euo pipefail
time -p cd "$(dirname "$0")"
/usr/bin/time -p bash -n start.sh
/usr/bin/time -p pwd
PROJECT_ROOT="$(pwd)"
DIST_DIR="$PROJECT_ROOT/dist"
/usr/bin/time -p test -f "$DIST_DIR/index.html"
DEPLOY_FILE="${OPENCODE_WEB_DIR:?}/deployment-output.json"
/usr/bin/time -p mkdir -p "$OPENCODE_WEB_DIR"
/usr/bin/time -p printf '{"project":%s,"directory":%s}\n' "$(printf '%s' "$PROJECT_ROOT" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.stringify(s)))')" "$(printf '%s' "$DIST_DIR" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.stringify(s)))')" > "$DEPLOY_FILE"
/usr/bin/time -p cat "$DEPLOY_FILE"
PORT="${PORT:-3000}"
/usr/bin/time -p node -e 'const p=Number(process.env.PORT||3000); if(!Number.isInteger(p)||p<1||p>65535){console.error("Invalid PORT");process.exit(1)}'
echo "Serving $DIST_DIR on port $PORT (foreground)"
# Foreground static server; no dependencies to install, no build step needed (dist/ is committed).
exec node --env-file-if-exists=.env -e '
const fs=require("node:fs"),path=require("node:path"),http=require("node:http");
const root=process.env.DIST_DIR||path.join(process.cwd(),"dist");
const mime={".html":"text/html",".js":"application/javascript",".css":"text/css",".json":"application/json",".svg":"image/svg+xml",".png":"image/png",".jpg":"image/jpeg",".webp":"image/webp"};
const server=http.createServer((req,res)=>{try{
  const u=new URL(req.url,"http://localhost");
  const p=path.resolve(root,"."+decodeURIComponent(u.pathname));
  if(p!==path.resolve(root)&&!p.startsWith(path.resolve(root)+"/")){res.writeHead(404);res.end();return}
  const f=fs.statSync(p).isDirectory()?path.join(p,"index.html"):p;
  res.setHeader("Content-Type",mime[path.extname(f)]||"application/octet-stream");
  res.setHeader("Cache-Control","no-cache");
  res.end(fs.readFileSync(f));
}catch{res.writeHead(404);res.end("Not found")}});
server.listen(Number(process.env.PORT||3000),"0.0.0.0",()=>console.log("listening on "+server.address().port));
'
