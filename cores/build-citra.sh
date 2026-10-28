#!/usr/bin/env bash
# Builds the Citra libretro core (Nintendo 3DS) for arm64-v8a with the Android NDK and
# CMake, and copies it into app/src/main/jniLibs. Citra pulls ~30 submodules, so this
# needs network access and runs in its own workflow, like melonDS DS.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-citra.sh
set -euo pipefail

CITRA_REPO=https://github.com/libretro/citra.git
CITRA_COMMIT=a0483e9abe8134ae6b290cdca7d49cb77689f05b  # 2026-09-20

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/citra"
BUILD="$DIR/build-android"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$CITRA_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$CITRA_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$CITRA_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
git -C "$DIR" submodule update -q --init --recursive --depth 1 --jobs 8

# Local fixes, applied once (skipped when already in the tree):
#   bionic-strerror: Android's strerror_r is the GNU (char*) flavour, like glibc's.
for PATCH in "$HERE"/patches/citra-*.patch; do
    [ -e "$PATCH" ] || continue
    if ! git -C "$DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
        git -C "$DIR" apply "$PATCH"
    fi
done

GENERATOR="Unix Makefiles"
if command -v ninja > /dev/null; then
    GENERATOR=Ninja
fi

echo "== building citra"
{
    cmake -S "$DIR" -B "$BUILD" -G "$GENERATOR" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_LIBRETRO=ON -DENABLE_QT=OFF -DENABLE_SDL2=OFF -DENABLE_WEB_SERVICE=OFF \
        -DENABLE_SCRIPTING=OFF -DENABLE_OPENAL=OFF -DENABLE_CUBEB=OFF -DENABLE_LIBUSB=OFF \
        -DENABLE_TESTS=OFF -DENABLE_LTO=OFF -DENABLE_COMPATIBILITY_LIST_DOWNLOAD=OFF \
        -DCITRA_USE_PRECOMPILED_HEADERS=OFF -DCITRA_WARNINGS_AS_ERRORS=OFF -DENABLE_OPT=OFF \
        "-DCMAKE_CXX_FLAGS=-Wno-deprecated-declarations -Wno-unknown-attributes" \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" &&
    cmake --build "$BUILD" --target citra_libretro -j"$JOBS"
} > "$WORK/citra.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error" "$WORK/citra.log" | head -n 60 || true
    tail -n 80 "$WORK/citra.log"
    exit 1
}
SO="$(find "$BUILD" -name 'citra_libretro_android.so' | head -n 1)"
mkdir -p "$OUT"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all -o "$OUT/libcitra_libretro_android.so" "$SO"
echo "   -> $OUT/libcitra_libretro_android.so ($(du -h "$OUT/libcitra_libretro_android.so" | cut -f1))"
