#!/bin/bash
# FlashyTube — F-Droid edition build (no Gradle): aapt2 + javac + d8.
# Differences from the private build.sh: no activation lock, no native
# vault, no remote control; dependencies are plain jars in libs/ (the
# fdroiddata recipe downloads them from Maven Central before building);
# output is an UNSIGNED apk (F-Droid signs with its own key). Set
# SIGN_DEBUG=1 to sign with the local debug keystore for testing only.
set -e
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
# Use the newest build-tools the environment offers (F-Droid build images
# may carry a different revision than the author's machine).
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AJAR_PLAT="$(ls -d "$SDK"/platforms/android-34 2>/dev/null || ls -d "$SDK"/platforms/* | sort -V | tail -1)"
PJ="$(cd "$(dirname "$0")" && pwd)"
APP=$PJ/app/src/main
BLD=$PJ/build
AJAR=$AJAR_PLAT/android.jar

rm -rf "$BLD"
mkdir -p "$BLD/gen" "$BLD/classes" "$BLD/dex"

echo "== 1. aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$BLD/compiled_res.zip"

echo "== 2. aapt2 link"
VCODE="${VERSION_CODE:-5}"
VNAME="${VERSION_NAME:-1.1.3}"
"$BT/aapt2" link -o "$BLD/base.apk" \
  -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BLD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "$BLD/compiled_res.zip"

echo "== 3. javac"
CP="$AJAR"
for j in "$PJ"/libs/*.jar; do CP="$CP:$j"; done
find "$APP/java" "$BLD/gen" -name "*.java" > "$BLD/sources.txt"
javac -nowarn -encoding UTF-8 -cp "$CP" -d "$BLD/classes" @"$BLD/sources.txt"

echo "== 4. d8"
"$BT/d8" --lib "$AJAR" --min-api 26 \
  --output "$BLD/dex" \
  $(find "$BLD/classes" -name "*.class") "$PJ"/libs/*.jar

echo "== 5. add dex"
cp "$BLD/base.apk" "$BLD/app.apk"
(cd "$BLD/dex" && zip -q -j ../app.apk classes*.dex)

echo "== 6. zipalign"
"$BT/zipalign" -f -p 4 "$BLD/app.apk" "$BLD/flashytube-unsigned.apk"

if [ "${SIGN_DEBUG:-0}" = "1" ]; then
  "$BT/apksigner" sign --ks "$SDK/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --out "$BLD/flashytube-debugsigned.apk" "$BLD/flashytube-unsigned.apk"
  echo "debug-signed test build: $BLD/flashytube-debugsigned.apk"
fi
ls -la "$BLD/flashytube-unsigned.apk"
