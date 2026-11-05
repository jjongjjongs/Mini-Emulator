#!/usr/bin/env bash
# Builds the Vita3K libretro core (PS Vita) for arm64-v8a with the Android NDK into
# app/src/main/jniLibs. Vita3K's libretro port (danprice142's "libretro" branch, on top of
# upstream Vita3K, GPL-2) is built with BUILD_LIBRETRO; its Android dependencies (Boost,
# OpenSSL, zlib) come from vcpkg for the arm64-android triplet, as upstream's Android build
# does. A long build: it runs in its own workflow and the APK build restores its result.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk VCPKG_ROOT=/path/to/vcpkg ./cores/build-vita3k.sh
set -euo pipefail

VITA_REPO=https://github.com/danprice142/vita3k.git
VITA_COMMIT=f496cd07c00fa1e1257c1192cd667f3bd0b4dd02  # "Add Libretro support", 2026-02-23

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
VCPKG="${VCPKG_ROOT:?set VCPKG_ROOT to a vcpkg checkout}"
DIR="$WORK/vita3k"
BUILD="$DIR/build-android"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$VITA_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$VITA_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$VITA_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
echo "== submodules"
git -C "$DIR" submodule update -q --init --recursive --depth 1 || git -C "$DIR" submodule update -q --init --recursive
for PATCH in "$HERE"/patches/vita3k-*.patch; do
    [ -e "$PATCH" ] || continue
    if ! git -C "$DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
        git -C "$DIR" apply "$PATCH"
    fi
done

echo "== vcpkg packages (arm64-android)"
"$VCPKG/vcpkg" install boost-system boost-filesystem boost-program-options boost-icl boost-variant openssl zlib \
    --triplet=arm64-android > "$WORK/vita3k-vcpkg.log" 2>&1 || { tail -n 80 "$WORK/vita3k-vcpkg.log"; exit 1; }

echo "== building vita3k_libretro"
{
    (cd "$DIR" && cmake -S . -B "$BUILD" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_LIBRETRO=ON -DUSE_VITA3K_UPDATE=OFF -DUSE_DISCORD_RICH_PRESENCE=OFF \
        -DCMAKE_SYSTEM_NAME=Android -DANDROID_PLATFORM=android-28 -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384") &&
    cmake --build "$BUILD" --target vita3k_libretro -- -k 0 -j "$(nproc)"
} > "$WORK/vita3k.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error\|undefined reference" "$WORK/vita3k.log" | head -n 200 || true
    tail -n 80 "$WORK/vita3k.log"
    exit 1
}
mkdir -p "$OUT"
SO="$(find "$BUILD" -name 'vita3k_libretro*.so' -print -quit)"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all -o "$OUT/libvita3k_libretro_android.so" "$SO"
echo "   -> $OUT/libvita3k_libretro_android.so ($(du -h "$OUT/libvita3k_libretro_android.so" | cut -f1))"
