#!/usr/bin/env bash
# Builds the melonDS DS libretro core (Nintendo DS) for arm64-v8a with the Android NDK and
# CMake, and copies it into app/src/main/jniLibs. melonDS DS fetches its own dependencies
# while configuring, so this needs network access. It runs in its own workflow, like PCEE2.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-melonds.sh
set -euo pipefail

MELONDS_REPO=https://github.com/JesseTG/melonds-ds.git
MELONDS_COMMIT=f394adbacb5722ee97c1b37c8064da9a25818310  # 2026-09-27

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/melonds"
BUILD="$DIR/build-android"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$MELONDS_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$MELONDS_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$MELONDS_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi

GENERATOR="Unix Makefiles"
if command -v ninja > /dev/null; then
    GENERATOR=Ninja
fi

echo "== building melonds"
{
    cmake -S "$DIR" -B "$BUILD" -G "$GENERATOR" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        "-DCMAKE_CXX_FLAGS=-Wno-deprecated-declarations -Wno-unknown-attributes" \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" &&
    cmake --build "$BUILD" --target melondsds_libretro -j"$JOBS"
} > "$WORK/melonds.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error" "$WORK/melonds.log" | head -n 60 || true
    tail -n 80 "$WORK/melonds.log"
    exit 1
}
SO="$(find "$BUILD" -name 'melondsds_libretro_android.so' | head -n 1)"
mkdir -p "$OUT"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all -o "$OUT/libmelondsds_libretro_android.so" "$SO"
echo "   -> $OUT/libmelondsds_libretro_android.so ($(du -h "$OUT/libmelondsds_libretro_android.so" | cut -f1))"
