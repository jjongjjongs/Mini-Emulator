#!/usr/bin/env bash
# Fetches the PS3 engine from an ARMSX3 release (RPCS3 for Android, GPL-2.0,
# https://github.com/ARMSX2/ARMSX3) instead of building it: the build compiles
# LLVM from source and takes longer than a CI job may run. The release APK is
# pinned by tag and checksum; from it come
#   libarmsx3-core.so  the engine (dlopen()ed by the glue below)
#   libarmsx3-jni.so   its JNI glue, bound to com.armsx3.core.ARMSX3 (app/src/main/java/com/armsx3/core)
#   assets/Icons       pictures the engine's save-data list and dialogs draw with
# The source for this exact build is tag 1.7.1 (commit 58d33fa).
# (0.0.430: from 1.6.7 to 1.7.1; libarmsx3-jni.so is byte for byte the same and the core's
# _armsx3_* functions are the same 148, so the Java side stays as it is.)
# (0.0.391: from 1.3.2 to 1.6.7; the JNI class moved from net.rpcsx.RPCSX to com.armsx3.core.ARMSX3.)
#
# Usage: GH_TOKEN=... ./cores/fetch-armsx3.sh
set -euo pipefail

TAG=1.7.1
APK=ARMSX3-1.7.1-a11-armv8.2-sdk30.apk
SHA256=a00f0e168666abf50a0e31166dce4fb38df3f68ca4e1906f5678e3cb9e984ca6

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}/armsx3"
LIBS="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets/ps3"

mkdir -p "$WORK" "$LIBS"
if [ ! -f "$WORK/$APK" ] || ! echo "$SHA256  $WORK/$APK" | sha256sum -c --quiet; then
    rm -f "$WORK/$APK"
    curl -fsSL -o "$WORK/$APK" "https://github.com/ARMSX2/ARMSX3/releases/download/$TAG/$APK" \
        || gh release download "$TAG" -R ARMSX2/ARMSX3 -p "$APK" -D "$WORK"
fi
echo "$SHA256  $WORK/$APK" | sha256sum -c

rm -rf "$WORK/x"
unzip -q "$WORK/$APK" 'lib/arm64-v8a/libarmsx3-core.so' 'lib/arm64-v8a/libarmsx3-jni.so' -d "$WORK/x"
cp "$WORK/x/lib/arm64-v8a/libarmsx3-core.so" "$WORK/x/lib/arm64-v8a/libarmsx3-jni.so" "$LIBS/"

echo "== top-level assets in the release:"
unzip -l "$WORK/$APK" 'assets/*' | awk 'NR>3 {print $4}' | cut -d/ -f2 | sort | uniq -c || true
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
if unzip -q "$WORK/$APK" 'assets/Icons/*' -d "$WORK/x" 2>/dev/null; then
    cp -r "$WORK/x/assets/Icons" "$ASSETS/Icons"
    echo "   Icons: $(find "$ASSETS/Icons" -type f | wc -l) files"
else
    echo "::warning::no assets/Icons in $APK"
fi
ls -lh "$LIBS/libarmsx3-core.so" "$LIBS/libarmsx3-jni.so"
