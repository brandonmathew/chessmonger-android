#!/usr/bin/env bash
# Manual APK pipeline for ChessMonger Android (no Gradle: the Gradle
# daemon cannot run in this VM — same proven pipeline as Gambit Chess).
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$HOME/workspace/tools/android/env.sh"

BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
MAIN="$PROJECT_DIR/app/src/main"
BUILD="$PROJECT_DIR/build"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/lib"

echo "== Stockfish =="
for abi_dir in "$PROJECT_DIR/stockfish-bin"/*/; do
    abi_name="$(basename "$abi_dir")"
    if [ -f "$abi_dir/libstockfish.so" ]; then
        mkdir -p "$BUILD/lib/$abi_name"
        cp "$abi_dir/libstockfish.so" "$BUILD/lib/$abi_name/"
        echo "  packaged lib/$abi_name/libstockfish.so"
    fi
done

echo "== Java =="
find "$MAIN/java" -name "*.java" > "$BUILD/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR" \
    -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== DEX =="
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
    --lib "$ANDROID_JAR" --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name "*.class")

echo "== Package =="
"$BT/aapt" package -f \
    -M "$MAIN/AndroidManifest.xml" \
    -S "$MAIN/res" \
    -I "$ANDROID_JAR" \
    -F "$BUILD/app-unsigned.apk"

cd "$BUILD"
zip -qj app-unsigned.apk dex/classes.dex
zip -qr app-unsigned.apk lib
cd "$PROJECT_DIR"

"$BT/zipalign" -p -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

KEYSTORE="$HOME/workspace/android-test/HelloKai/build/manual/debug.keystore"
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$BUILD/ChessMongerAndroid.apk" "$BUILD/app-aligned.apk"

echo "== Done: $BUILD/ChessMongerAndroid.apk =="
