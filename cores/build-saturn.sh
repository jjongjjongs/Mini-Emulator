#!/usr/bin/env bash
# Builds the YabaSanshiro libretro core (Sega Saturn; OpenGL ES 3 renderer and the arm64
# SH2 dynarec) for arm64-v8a with the Android NDK and copies it into app/src/main/jniLibs.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-saturn.sh
set -euo pipefail

YABA_REPO=https://github.com/libretro/yabause.git
YABA_COMMIT=09ed8e5b2e97e7a848ea2514545c34c7b809e399   # branch yabasanshiro

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"

mkdir -p "$WORK"
dir="$WORK/yabasanshiro"
if [ ! -d "$dir/.git" ]; then
    git init -q "$dir"
    git -C "$dir" remote add origin "$YABA_REPO"
fi
if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$YABA_COMMIT" ]; then
    git -C "$dir" fetch -q --depth 1 origin "$YABA_COMMIT"
    git -C "$dir" checkout -q --force FETCH_HEAD
fi

jni="$dir/yabause/src/libretro/jni"
echo "== building yabasanshiro"
"$NDK/ndk-build" -C "$jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/yabasanshiro.log" 2>&1 || {
    grep -n "error" "$WORK/yabasanshiro.log" | head -n 40
    tail -n 60 "$WORK/yabasanshiro.log"
    exit 1
}
mkdir -p "$OUT"
cp "$jni/../libs/arm64-v8a/libretro.so" "$OUT/libyabasanshiro_libretro_android.so"
echo "   -> $OUT/libyabasanshiro_libretro_android.so ($(du -h "$OUT/libyabasanshiro_libretro_android.so" | cut -f1))"
