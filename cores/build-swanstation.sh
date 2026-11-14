#!/usr/bin/env bash
# Builds the SwanStation libretro core (PlayStation, from DuckStation before its licence
# change; GPLv3) for arm64-v8a with the Android NDK and copies it into
# app/src/main/jniLibs. The app offers it beside PCSX ReARMed as the high-quality PS1
# engine (upscaling, PGXP, true colour, widescreen).
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-swanstation.sh
set -euo pipefail

SWAN_REPO=https://github.com/libretro/swanstation.git
SWAN_COMMIT=631f0fb31b809e0f889d7c551fe695788e39c1d3  # 2026-10-24

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/swanstation"
BUILD="$WORK/swanstation-build"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$SWAN_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$SWAN_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$SWAN_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
for PATCH in "$HERE"/patches/swanstation-*.patch; do
    [ -e "$PATCH" ] || continue
    if ! git -C "$DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
        git -C "$DIR" apply "$PATCH"
    fi
done

echo "== building swanstation"
rm -rf "$BUILD"
{
    cmake -S "$DIR" -B "$BUILD" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" &&
    cmake --build "$BUILD" --target swanstation_libretro -j"$JOBS"
} > "$WORK/swanstation.log" 2>&1 || {
    tail -n 80 "$WORK/swanstation.log"
    exit 1
}
SO="$(find "$BUILD" -name '*swanstation_libretro*.so' | head -n 1)"
[ -n "$SO" ] || { echo "swanstation_libretro .so not found"; exit 1; }
mkdir -p "$OUT"
cp "$SO" "$OUT/libswanstation_libretro_android.so"
"$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded "$OUT/libswanstation_libretro_android.so"
if "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf -d "$OUT/libswanstation_libretro_android.so" | grep -q 'libc++_shared'; then
    echo "swanstation links libc++_shared.so, which the app does not ship"
    exit 1
fi
echo "   -> $OUT/libswanstation_libretro_android.so ($(du -h "$OUT/libswanstation_libretro_android.so" | cut -f1))"
