#!/usr/bin/env bash
# Builds the Mupen64Plus-Next libretro core (Nintendo 64) for arm64-v8a with the Android
# NDK and copies it into app/src/main/jniLibs. It renders with GLideN64 on OpenGL ES 3,
# the context the bridge offers, so the Vulkan (ParaLLEl) and software (Angrylion)
# renderers and the LLE RSPs are left out. It runs in its own workflow, like melonDS DS.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-mupen64.sh
set -euo pipefail

M64_REPO=https://github.com/libretro/mupen64plus-libretro-nx.git
M64_COMMIT=12edd2c74a517ff86dfa8cfc71ad75e4c10486d5  # develop, 2026-10-01

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/mupen64plus"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$M64_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$M64_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$M64_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi

echo "== building mupen64plus-next"
"$NDK/ndk-build" -C "$DIR/libretro/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    GLES3=1 HAVE_PARALLEL_RSP=0 HAVE_PARALLEL_RDP=0 HAVE_THR_AL=0 LLE=0 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/mupen64plus.log" 2>&1 || {
    grep -n "error:\|Error [0-9]" "$WORK/mupen64plus.log" | head -n 60 || true
    tail -n 80 "$WORK/mupen64plus.log"
    exit 1
}
SO="$(find "$DIR/libretro" -path '*arm64-v8a*' -name 'libretro.so' | head -n 1)"
mkdir -p "$OUT"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all -o "$OUT/libmupen64plus_next_libretro_android.so" "$SO"
echo "   -> $OUT/libmupen64plus_next_libretro_android.so ($(du -h "$OUT/libmupen64plus_next_libretro_android.so" | cut -f1))"
