#!/usr/bin/env bash
# Builds the Flycast libretro core (Dreamcast) for arm64-v8a with the Android NDK and
# CMake, and copies it into app/src/main/jniLibs. It renders with Vulkan where the device
# has it and OpenGL ES 3 otherwise (both offered by the bridge); the parts only the
# standalone app uses (Lua, crash reports, OpenMP) are left out. It runs in its own
# workflow, like melonDS DS.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-flycast.sh
set -euo pipefail

FLYCAST_REPO=https://github.com/flyinghead/flycast.git
FLYCAST_COMMIT=5aa091fde632fb332c8d8c34e280d62dc951954c  # v2.7

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/flycast"
BUILD="$DIR/build-android"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$FLYCAST_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$FLYCAST_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$FLYCAST_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
git -C "$DIR" submodule update -q --init --recursive --depth 1

GENERATOR="Unix Makefiles"
if command -v ninja > /dev/null; then
    GENERATOR=Ninja
fi

echo "== building flycast"
{
    cmake -S "$DIR" -B "$BUILD" -G "$GENERATOR" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DLIBRETRO=ON -DUSE_VULKAN=ON -DUSE_LUA=OFF -DUSE_BREAKPAD=OFF -DUSE_OPENMP=OFF \
        -DUSE_HOST_LIBZIP=OFF -DUSE_HOST_LIBCHDR=OFF \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" &&
    cmake --build "$BUILD" --target flycast_libretro -j"$JOBS"
} > "$WORK/flycast.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error" "$WORK/flycast.log" | head -n 60 || true
    tail -n 80 "$WORK/flycast.log"
    exit 1
}
SO="$(find "$BUILD" -name 'flycast_libretro*.so' | head -n 1)"
mkdir -p "$OUT"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all -o "$OUT/libflycast_libretro_android.so" "$SO"
echo "   -> $OUT/libflycast_libretro_android.so ($(du -h "$OUT/libflycast_libretro_android.so" | cut -f1))"
