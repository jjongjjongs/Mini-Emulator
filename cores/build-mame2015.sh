#!/usr/bin/env bash
# Builds the MAME 2015 (0.160) libretro core for arm64-v8a with the Android
# NDK and copies it into app/src/main/jniLibs. Kept apart from the other
# cores so CI caches this long build on its own.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-mame2015.sh
set -euo pipefail

MAME2015_REPO=https://github.com/libretro/mame2015-libretro.git
MAME2015_COMMIT=7adbf440d5f554097f58f8c6ebb673adc4320a31

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/mame2015"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$MAME2015_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$MAME2015_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$MAME2015_COMMIT"
fi
git -C "$DIR" checkout -q --force FETCH_HEAD 2>/dev/null || git -C "$DIR" checkout -q --force "$MAME2015_COMMIT"

# The Makefile's Android target already uses the flags MAME needs
# (-fsigned-char, -fno-strict-aliasing, -fno-delete-null-pointer-checks);
# only the 16 KB page alignment is missing.
sed -i 's|^   LDFLAGS += $(fpic) $(SHARED) -static-libstdc++$|& -Wl,-z,max-page-size=16384|' "$DIR/Makefile"
grep -q 'static-libstdc++ -Wl,-z,max-page-size=16384' "$DIR/Makefile"

echo "== building mame2015"
make -C "$DIR" platform=android-arm64 NDK_ROOT="$NDK" -j"$JOBS" > "$WORK/mame2015.log" 2>&1 || {
    tail -n 80 "$WORK/mame2015.log"
    exit 1
}
mkdir -p "$OUT"
cp "$DIR/mame2015_libretro_android.so" "$OUT/libmame2015_libretro_android.so"
echo "   -> $OUT/libmame2015_libretro_android.so ($(du -h "$OUT/libmame2015_libretro_android.so" | cut -f1))"
