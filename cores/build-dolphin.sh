#!/usr/bin/env bash
# Builds the Dolphin libretro core (GameCube and Wii) for arm64-v8a with the Android NDK and
# CMake, copies it into app/src/main/jniLibs, and packs the data it reads at run time
# (Data/Sys: game settings, fonts, shaders) into app/src/main/assets/dolphin_sys.zip, which
# the app unpacks to <system>/dolphin-emu/Sys. Dolphin wants a recent NDK (r29, as its own
# libretro Android build uses), so this fetches one when the given NDK is older.
# It runs in its own workflow (dolphin.yml), like Flycast.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-dolphin.sh
set -euo pipefail

DOLPHIN_REPO=https://github.com/libretro/dolphin.git
DOLPHIN_COMMIT=b93001ed50c8b34e6ead4eed713c2005a80386e4  # 2026-10-29
NDK_URL=https://dl.google.com/android/repository/android-ndk-r29-linux.zip

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/dolphin"
BUILD="$DIR/build-android"
mkdir -p "$WORK"

NDK="${ANDROID_NDK_HOME:-}"
major=0
if [ -n "$NDK" ] && [ -f "$NDK/source.properties" ]; then
    major="$(sed -n 's/^Pkg.Revision *= *\([0-9]*\).*/\1/p' "$NDK/source.properties")"
fi
if [ "${major:-0}" -lt 29 ]; then
    if [ ! -d "$WORK/android-ndk-r29" ]; then
        echo "== fetching NDK r29 (have ${major:-none})"
        curl -sSL -o "$WORK/ndk.zip" "$NDK_URL"
        unzip -q "$WORK/ndk.zip" -d "$WORK"
        rm -f "$WORK/ndk.zip"
    fi
    NDK="$WORK/android-ndk-r29"
fi

if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$DOLPHIN_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$DOLPHIN_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$DOLPHIN_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
git -C "$DIR" submodule update -q --init --recursive --depth 1

# Dolphin only opens a WBFS file whose size matches the sector count in its header, but
# tools that trim the unused space after the last block write smaller files that hold the
# whole game. Read those too: a block past the end of the file just fails to read.
WBFS="$DIR/Source/Core/DiscIO/WbfsBlob.cpp"
if grep -q 'if (m_size != (m_header.hd_sector_count \* m_hd_sector_size))' "$WBFS"; then
    sed -i 's/if (m_size != (m_header.hd_sector_count \* m_hd_sector_size))/if (m_size > (m_header.hd_sector_count * m_hd_sector_size))/' "$WBFS"
fi
grep -q 'if (m_size > (m_header.hd_sector_count \* m_hd_sector_size))' "$WBFS"

GENERATOR="Unix Makefiles"
if command -v ninja > /dev/null; then
    GENERATOR=Ninja
fi

echo "== building dolphin"
{
    cmake -S "$DIR" -B "$BUILD" -G "$GENERATOR" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release -DLIBRETRO=ON \
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" &&
    cmake --build "$BUILD" -j"$JOBS"
} > "$WORK/dolphin.log" 2>&1 || {
    grep -n "error:\|Error [0-9]\|CMake Error" "$WORK/dolphin.log" | head -n 60 || true
    tail -n 80 "$WORK/dolphin.log"
    exit 1
}
SO="$(find "$BUILD" -name '*dolphin_libretro*.so' | head -n 1)"
if [ -z "$SO" ]; then
    echo "no dolphin_libretro .so in $BUILD"
    find "$BUILD" -name '*.so' | head
    exit 1
fi
mkdir -p "$OUT" "$ASSETS"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-all -o "$OUT/libdolphin_libretro_android.so" "$SO"
echo "   -> $OUT/libdolphin_libretro_android.so ($(du -h "$OUT/libdolphin_libretro_android.so" | cut -f1))"

# Data/Sys without the desktop UI's themes and the big title lists.
rm -f "$ASSETS/dolphin_sys.zip"
(cd "$DIR/Data/Sys" && find . -type f ! -path './Themes/*' ! -name 'wiitdb-*.txt' | LC_ALL=C sort \
    | TZ=UTC zip -q -X -D "$ASSETS/dolphin_sys.zip" -@)
echo "   -> $ASSETS/dolphin_sys.zip ($(du -h "$ASSETS/dolphin_sys.zip" | cut -f1))"
