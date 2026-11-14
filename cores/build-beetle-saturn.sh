#!/usr/bin/env bash
# Builds the Beetle Saturn libretro core (Mednafen's Sega Saturn emulation; GPLv2) for
# arm64-v8a with the Android NDK and copies it into app/src/main/jniLibs. The app offers it
# beside YabaSanshiro as the accurate Saturn engine, picked per game (0.0.404).
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-beetle-saturn.sh
set -euo pipefail

BEETLE_REPO=https://github.com/libretro/beetle-saturn-libretro.git
BEETLE_COMMIT=30f4c12072d265c9e553dcb29386059114945dea   # 2026-11-02

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"

mkdir -p "$WORK"
dir="$WORK/beetle-saturn"
if [ ! -d "$dir/.git" ]; then
    git init -q "$dir"
    git -C "$dir" remote add origin "$BEETLE_REPO"
fi
if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$BEETLE_COMMIT" ]; then
    git -C "$dir" fetch -q --depth 1 origin "$BEETLE_COMMIT"
    git -C "$dir" checkout -q --force FETCH_HEAD
fi

echo "== building beetle saturn"
"$NDK/ndk-build" -C "$dir/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/beetle-saturn.log" 2>&1 || {
    grep -n "error" "$WORK/beetle-saturn.log" | head -n 40
    tail -n 60 "$WORK/beetle-saturn.log"
    exit 1
}
mkdir -p "$OUT"
cp "$dir/libs/arm64-v8a/libretro.so" "$OUT/libmednafen_saturn_libretro_android.so"
echo "   -> $OUT/libmednafen_saturn_libretro_android.so ($(du -h "$OUT/libmednafen_saturn_libretro_android.so" | cut -f1))"
