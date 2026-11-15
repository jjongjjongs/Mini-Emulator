#!/usr/bin/env bash
# Builds the PCEE2 libretro core (PCSX2 for libretro, PlayStation 2) for arm64-v8a
# with the Android NDK, and copies it into app/src/main/jniLibs. It also packs the
# PCSX2 game database into app/src/main/assets/pcsx2_resources.zip, which the app
# unpacks into <system>/pcsx2/resources (per-game fixes come from it).
#
# This is a long build (the dependencies are built from source too), so it runs in
# its own workflow and the APK build only restores its result from the cache.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-pcee2.sh
# The NDK must be recent (r27 or newer): PCSX2 needs a current C++ library.
set -euo pipefail

PCEE2_REPO=https://github.com/portableandroid/pcee2-libretro.git
PCEE2_COMMIT=1ace4763bae660a750aec3bcf9ef21005cbe56b2  # PCSX2 v2.9.78, 2026-09-30

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
DIR="$WORK/pcee2"
DEPS="$WORK/pcee2-deps"
BUILD="$DIR/build-android"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$PCEE2_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$PCEE2_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$PCEE2_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
git -C "$DIR" submodule update -q --init --recursive --depth 1
# Fixes on top of the pinned commit:
#   has-picture: exports retro_pcee2_has_picture(), whether the GS has presented a game
#     picture yet; the app restores a quick save only after that (on Vulkan the frames
#     handed over from the first vsync do not tell).
#   no-osd-messages: PCSX2's on-screen notices (missing patches.zip, the blending level a
#     game recommends) were drawn over the game for its first seconds.
for PATCH in "$HERE"/patches/pcee2-*.patch; do
    [ -e "$PATCH" ] || continue
    if ! git -C "$DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
        git -C "$DIR" apply "$PATCH"
    fi
done

echo "== building pcee2 dependencies"
if [ ! -f "$DEPS/.done" ]; then
    (cd "$WORK" && ANDROID_NDK="$NDK" ANDROID_ABI=arm64-v8a ANDROID_API=24 \
        bash "$DIR/pcee2-libretro/scripts/build-deps-android.sh" "$DEPS") > "$WORK/pcee2-deps.log" 2>&1 || {
        tail -n 80 "$WORK/pcee2-deps.log"
        exit 1
    }
    touch "$DEPS/.done"
fi

echo "== building pcee2"
{
    cmake -S "$DIR" -B "$BUILD" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_QT_UI=OFF -DENABLE_TESTS=OFF -DENABLE_LIBRETRO=ON \
        -DCMAKE_PREFIX_PATH="$DEPS" -DCMAKE_FIND_ROOT_PATH="$DEPS" \
        -DSHADERC_STATIC=ON "-DSHADERC_LIBRARY=$DEPS/lib/libshaderc_combined.a" \
        -DDISABLE_ADVANCE_SIMD=ON \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" &&
    cmake --build "$BUILD" --target pcee2_libretro
} > "$WORK/pcee2.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error" "$WORK/pcee2.log" | head -n 60 || true
    tail -n 80 "$WORK/pcee2.log"
    exit 1
}
mkdir -p "$OUT" "$ASSETS"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all \
    -o "$OUT/libpcee2_libretro_android.so" "$BUILD/bin/pcee2_libretro.so"
echo "   -> $OUT/libpcee2_libretro_android.so ($(du -h "$OUT/libpcee2_libretro_android.so" | cut -f1))"

# The game database carries PCSX2's per-game fixes; the shaders and fonts the core
# needs are built into it.
rm -f "$ASSETS/pcsx2_resources.zip"
(cd "$DIR/bin/resources" && TZ=UTC zip -q -X -D "$ASSETS/pcsx2_resources.zip" GameIndex.yaml)
echo "   -> $ASSETS/pcsx2_resources.zip ($(du -h "$ASSETS/pcsx2_resources.zip" | cut -f1))"
