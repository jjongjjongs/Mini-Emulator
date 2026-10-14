#!/usr/bin/env bash
# Builds the Play! libretro core (PlayStation 2) for arm64-v8a with the Android
# NDK and CMake, and copies it into app/src/main/jniLibs. Kept apart from the
# other build scripts so CI caches this long build separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-play.sh
set -euo pipefail

PLAY_REPO=https://github.com/jpd002/Play-.git
PLAY_COMMIT=fa75cea31a33f5ab7a2008ed0d90dc74353e9cef  # 0.77

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/play"
BUILD="$DIR/build_arm64"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$PLAY_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$PLAY_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$PLAY_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
git -C "$DIR" submodule update -q --init --recursive --depth 1
# Fixes on top of the pinned commit (each says what it fixes):
#   sif-dma-bounds: SIF DMA copies could run past the end of IOP or EE RAM and kill
#     the process (Tekken 5 crashed in CSifManPs2::ExecuteSifDma).
#   state-pause: retro_serialize/unserialize ran while the VM thread kept emulating, so
#     restored states crashed the game (CIopBios::SleepThread, EE jumping to 0).
#   audio-queue: the libretro sound handler kept only the last block the VM wrote each
#     frame, so most of the sound was dropped and it crackled like radio static.
for PATCH in "$HERE"/patches/play-*.patch; do
    if ! git -C "$DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
        git -C "$DIR" apply "$PATCH"
    fi
done

GENERATOR="Unix Makefiles"
if command -v ninja > /dev/null; then
    GENERATOR=Ninja
fi

echo "== building play"
{
    cmake -S "$DIR" -B "$BUILD" -G "$GENERATOR" \
        -DBUILD_LIBRETRO_CORE=yes -DBUILD_PLAY=off -DBUILD_TESTS=off \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
        -DANDROID_STL=c++_static \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" &&
    cmake --build "$BUILD" --target play_libretro -j"$JOBS"
} > "$WORK/play.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error" "$WORK/play.log" | head -n 60 || true
    tail -n 60 "$WORK/play.log"
    exit 1
}
mkdir -p "$OUT"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all \
    -o "$OUT/libplay_libretro_android.so" "$BUILD/Source/ui_libretro/play_libretro_android.so"
echo "   -> $OUT/libplay_libretro_android.so ($(du -h "$OUT/libplay_libretro_android.so" | cut -f1))"
