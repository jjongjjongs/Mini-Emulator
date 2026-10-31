#!/usr/bin/env bash
# Builds the Cemu libretro core (Wii U) for arm64-v8a with the Android NDK, and copies
# it into app/src/main/jniLibs. It also packs Cemu's shared fonts and game profiles into
# app/src/main/assets/cemu_resources.zip, which the app unpacks into <system>/Cemu.
#
# The dependencies come from vcpkg and are built from source for the Android triplet,
# so this is a long build; it runs in its own workflow and the APK build only restores
# its result from the cache.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-cemu.sh
set -euo pipefail

CEMU_REPO=https://github.com/WizzardSK/cemu-libretro.git
CEMU_COMMIT=6d38ee8dd0a66d70f14381a9dc7ed4de77ba7fe0  # Cemu 2.6-372, 2026-10-30

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
DIR="$WORK/cemu"
BUILD="$DIR/build-android"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$CEMU_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$CEMU_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$CEMU_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
# vcpkg needs its history (the baseline is a commit in it); the rest can be shallow.
git -C "$DIR" submodule update -q --init dependencies/vcpkg
for SUB in dependencies/ZArchive dependencies/Vulkan-Headers dependencies/imgui dependencies/xbyak_aarch64 dependencies/cemu_graphic_packs; do
    git -C "$DIR" submodule update -q --init --depth 1 "$SUB" || git -C "$DIR" submodule update -q --init "$SUB"
done
for PATCH in "$HERE"/patches/cemu-*.patch; do
    [ -e "$PATCH" ] || continue
    if ! git -C "$DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
        git -C "$DIR" apply "$PATCH"
    fi
done

echo "== bootstrapping vcpkg"
[ -x "$DIR/dependencies/vcpkg/vcpkg" ] || bash "$DIR/dependencies/vcpkg/bootstrap-vcpkg.sh" -disableMetrics > "$WORK/cemu-vcpkg.log" 2>&1

echo "== building cemu (vcpkg dependencies first)"
{
    (cd "$DIR" && cmake -S . -B "$BUILD" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_LIBRETRO=ON -DENABLE_BLUEZ=OFF -DENABLE_FERAL_GAMEMODE=OFF \
        -DENABLE_WAYLAND=OFF -DENABLE_DISCORD_RPC=OFF \
        -DVCPKG_TARGET_TRIPLET=arm64-android -DVCPKG_HOST_TRIPLET=x64-linux \
        -DANDROID_PLATFORM=android-28 -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384") &&
    cmake --build "$BUILD" --target cemu_libretro -- -j "$(nproc)"
} > "$WORK/cemu.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error\|error: building" "$WORK/cemu.log" | head -n 60 || true
    find "$DIR/dependencies/vcpkg/buildtrees" -name "*-err.log" -newer "$WORK/cemu-vcpkg.log" 2>/dev/null | head -n 5 | while read -r LOG; do
        echo "---- $LOG"; tail -n 40 "$LOG"
    done
    tail -n 80 "$WORK/cemu.log"
    exit 1
}
mkdir -p "$OUT" "$ASSETS"
SO="$(find "$DIR/bin" "$BUILD" -name cemu_libretro.so -print -quit)"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all -o "$OUT/libcemu_libretro_android.so" "$SO"
echo "   -> $OUT/libcemu_libretro_android.so ($(du -h "$OUT/libcemu_libretro_android.so" | cut -f1))"

# Shared fonts (games draw their text with them) and the per-game profiles (CPU mode,
# shader precision) the core reads from <system>/Cemu.
rm -f "$ASSETS/cemu_resources.zip"
(cd "$DIR/bin" && TZ=UTC zip -q -X -r "$ASSETS/cemu_resources.zip" resources/sharedFonts gameProfiles)
echo "   -> $ASSETS/cemu_resources.zip ($(du -h "$ASSETS/cemu_resources.zip" | cut -f1))"
