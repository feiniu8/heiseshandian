#!/usr/bin/env bash
# 构建黑色闪电（无需 Gradle）
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/android-sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
AJ="$SDK/platforms/android-34/android.jar"
VERSION_CODE="${VERSION_CODE:-8}"
VERSION_NAME="${VERSION_NAME:-1.0.7}"
PKG_DIR="java"

if [ ! -f "$AJ" ]; then
  echo "找不到 $AJ，请设置 ANDROID_SDK 指向 Android SDK 根目录" >&2
  exit 1
fi

rm -rf build
mkdir -p build/classes build/dex

echo "[1/5] aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/5] aapt2 link"
"$BT/aapt2" link -o build/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  build/res.zip

echo "[3/5] javac"
javac -source 8 -target 8 -bootclasspath "$AJ" -d build/classes \
  $(find "$PKG_DIR" -name '*.java')

echo "[4/5] d8"
"$BT/d8" --lib "$AJ" --min-api 23 --output build/dex \
  $(find build/classes -name '*.class')

echo "[5/5] 组装 APK"
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/base.apk') as zi, zipfile.ZipFile('build/unsigned.apk', 'w') as zo:
    for i in zi.infolist():
        data = zi.read(i.filename)
        if i.filename == 'resources.arsc':
            ni = zipfile.ZipInfo(i.filename)
            ni.compress_type = zipfile.ZIP_STORED
            zo.writestr(ni, data)
        else:
            zo.writestr(i, data)
    ni = zipfile.ZipInfo('classes.dex')
    ni.compress_type = zipfile.ZIP_STORED
    zo.writestr(ni, open('build/dex/classes.dex', 'rb').read())
PY

"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/zipalign" -c -p 4 build/aligned.apk

OUT="build/black-lightning-unsigned.apk"
cp build/aligned.apk "$OUT"

if [ "${1:-}" = "--sign" ]; then
  KS="$2"; ALIAS="${3:-blitz}"; SPASS="${4:-}"; KPASS="${5:-$SPASS}"
  "$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$SPASS" --key-pass "pass:$KPASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out build/black-lightning-signed.apk build/aligned.apk
  "$BT/apksigner" verify build/black-lightning-signed.apk
  OUT="build/black-lightning-signed.apk"
fi

echo "完成：$OUT"
