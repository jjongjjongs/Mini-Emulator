#!/usr/bin/env bash
# Builds the mGBA libretro core (Game Boy Advance / Game Boy / Game Boy Color) for
# arm64-v8a with the Android NDK and copies it into app/src/main/jniLibs. Until 0.0.249 the
# app carried a prebuilt copy from the original release; now it is built from libretro's
# mGBA like the other cores, so it can be kept up to date.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-mgba.sh
set -euo pipefail

MGBA_REPO=https://github.com/libretro/mgba.git
MGBA_COMMIT=7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6  # 0.11-dev, 2026-09-16

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/mgba"
BUILD="$WORK/mgba-build"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$MGBA_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$MGBA_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$MGBA_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi

echo "== building mgba"
rm -rf "$BUILD"
{
    cmake -S "$DIR" -B "$BUILD" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
        -DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON &&
    cmake --build "$BUILD" --target mgba_libretro -j"$JOBS"
} > "$WORK/mgba.log" 2>&1 || {
    tail -n 60 "$WORK/mgba.log"
    exit 1
}
mkdir -p "$OUT"
cp "$BUILD/mgba_libretro.so" "$OUT/libmgba_libretro_android.so"
echo "   -> $OUT/libmgba_libretro_android.so ($(du -h "$OUT/libmgba_libretro_android.so" | cut -f1))"
