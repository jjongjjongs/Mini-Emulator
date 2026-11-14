#!/usr/bin/env bash
# Builds the Snes9x libretro core (Super Famicom / SNES) for arm64-v8a with the
# Android NDK and copies it into app/src/main/jniLibs. Kept apart from
# build-cores.sh so CI caches it separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-snes9x.sh
set -euo pipefail

SNES9X_REPO=https://github.com/libretro/snes9x.git
SNES9X_COMMIT=fae2fea08f74180759ef540ee94259213f503480

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/snes9x"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$SNES9X_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$SNES9X_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$SNES9X_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi

echo "== building snes9x"
"$NDK/ndk-build" -C "$DIR/libretro/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/snes9x.log" 2>&1 || {
    tail -n 60 "$WORK/snes9x.log"
    exit 1
}
mkdir -p "$OUT"
cp "$DIR/libretro/libs/arm64-v8a/libretro.so" "$OUT/libsnes9x_libretro_android.so"
echo "   -> $OUT/libsnes9x_libretro_android.so ($(du -h "$OUT/libsnes9x_libretro_android.so" | cut -f1))"
