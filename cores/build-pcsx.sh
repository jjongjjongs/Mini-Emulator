#!/usr/bin/env bash
# Builds the PCSX ReARMed libretro core (PlayStation) for arm64-v8a with the
# Android NDK and copies it into app/src/main/jniLibs. Kept apart from the other
# build scripts so CI caches it separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-pcsx.sh
set -euo pipefail

PCSX_REPO=https://github.com/libretro/pcsx_rearmed.git
PCSX_COMMIT=7b0e8f8fadfbce3d170d195b9d9eecaabfa08c8e  # 2026-10-29

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/pcsx_rearmed"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$PCSX_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$PCSX_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$PCSX_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi

echo "== building pcsx_rearmed"
"$NDK/ndk-build" -C "$DIR/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/pcsx_rearmed.log" 2>&1 || {
    grep -n "error:\|Error [0-9]" "$WORK/pcsx_rearmed.log" | head -n 60 || true
    tail -n 60 "$WORK/pcsx_rearmed.log"
    exit 1
}
mkdir -p "$OUT"
cp "$DIR/libs/arm64-v8a/libretro.so" "$OUT/libpcsx_rearmed_libretro_android.so"
echo "   -> $OUT/libpcsx_rearmed_libretro_android.so ($(du -h "$OUT/libpcsx_rearmed_libretro_android.so" | cut -f1))"
