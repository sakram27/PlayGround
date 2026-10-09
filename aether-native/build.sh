#!/usr/bin/env bash
# Build APK native Aether Signal tanpa Gradle: kotlinc + aapt2 + d8 + zipalign + apksigner.
# Variabel lingkungan yang dibutuhkan:
#   KOTLINC_HOME (berisi bin/kotlinc), ANDROID_JAR (android.jar platform),
#   BT (direktori build-tools berisi aapt2, d8, zipalign, lib/apksigner.jar),
#   KS (keystore), KS_PASS, KS_ALIAS. Output: AetherNative-debug.apk
set -euo pipefail
cd "$(dirname "$0")"
OUT=${OUT:-/tmp/nat}
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
"$KOTLINC_HOME/bin/kotlinc" $(find src -name "*.kt") -cp "$ANDROID_JAR" -jvm-target 1.8 -d "$OUT/classes"
export LD_LIBRARY_PATH="$BT/lib64:${LD_LIBRARY_PATH:-}"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml "$OUT/res.zip"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex.zip" $(find "$OUT/classes" -name "*.class") "$KOTLINC_HOME/lib/kotlin-stdlib.jar"
python3 - "$OUT" <<'EOF'
import sys, zipfile
out = sys.argv[1]
dex = zipfile.ZipFile(out + '/dex.zip').read('classes.dex')
with zipfile.ZipFile(out + '/base.apk') as zin, zipfile.ZipFile(out + '/unsigned.apk', 'w', zipfile.ZIP_DEFLATED) as z:
    for item in zin.infolist():
        z.writestr(item, zin.read(item.filename))
    z.writestr('classes.dex', dex)
print('assembled')
EOF
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out AetherNative-debug.apk "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" verify AetherNative-debug.apk && echo VERIFIED
ls -la AetherNative-debug.apk
