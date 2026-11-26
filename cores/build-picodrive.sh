#!/usr/bin/env bash
# Builds the PicoDrive libretro core (Mega CD and 32X) for arm64-v8a with the Android NDK and
# copies it into app/src/main/jniLibs. PicoDrive pulls its CPU cores and CD/CHD readers in
# as git submodules.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-picodrive.sh
set -euo pipefail

PICO_REPO=https://github.com/libretro/picodrive.git
PICO_COMMIT=1890c2932234c9d30f4cd3851d02228baae8f09e

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"

mkdir -p "$WORK"
dir="$WORK/picodrive"
if [ ! -d "$dir/.git" ]; then
    git init -q "$dir"
    git -C "$dir" remote add origin "$PICO_REPO"
fi
if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$PICO_COMMIT" ]; then
    git -C "$dir" fetch -q --depth 1 origin "$PICO_COMMIT"
    git -C "$dir" checkout -q --force FETCH_HEAD
fi
git -C "$dir" submodule update --init --recursive -q

echo "== building picodrive"
"$NDK/ndk-build" -C "$dir/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/picodrive.log" 2>&1 || {
    tail -n 80 "$WORK/picodrive.log"
    exit 1
}
mkdir -p "$OUT"
cp "$dir/libs/arm64-v8a/libretro.so" "$OUT/libpicodrive_libretro_android.so"
echo "   -> $OUT/libpicodrive_libretro_android.so ($(du -h "$OUT/libpicodrive_libretro_android.so" | cut -f1))"
