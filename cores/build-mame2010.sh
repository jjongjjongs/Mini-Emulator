#!/usr/bin/env bash
# Builds the MAME 2010 (0.139) libretro core for arm64-v8a with the Android
# NDK and copies it into app/src/main/jniLibs. Kept apart from
# build-cores.sh so CI caches this long build separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-mame2010.sh
set -euo pipefail

MAME2010_REPO=https://github.com/libretro/mame2010-libretro.git
MAME2010_COMMIT=dff8aadd1c3f38215af3955746d6e19abe0ddcea

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/mame2010"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$MAME2010_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$MAME2010_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$MAME2010_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi

# The H8 CPU cores write 8 flag letters plus the terminator into an 8-byte
# buffer; Android's FORTIFY aborts on it (Namco System 12 games, e.g. tektagt).
sed -i 's/static char res\[8\];/static char res[16];/' \
    "$DIR/src/emu/cpu/h83002/h8_16.c" "$DIR/src/emu/cpu/h83002/h8_8.c"
grep -q 'static char res\[16\];' "$DIR/src/emu/cpu/h83002/h8_16.c"
grep -q 'static char res\[16\];' "$DIR/src/emu/cpu/h83002/h8_8.c"

# libretro's CPS tilemap renderer dereferences the destination bitmap even on the
# priority-only pass CPS1 draws with no destination (sf2 and others crash).
PATCH="$HERE/patches/mame2010-cps1-priority-pass.patch"
if ! git -C "$DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
    git -C "$DIR" apply "$PATCH"
fi

echo "== building mame2010"
# The core's Android.mk sets neither 16 KB page alignment nor the flags its
# desktop builds use: MAME 0.139 assumes a signed char (ARM's is unsigned),
# breaks under strict aliasing, and tests `this` against NULL (e.g.
# device_config::subtag), which clang drops unless told not to. FORTIFY is
# turned off as on the desktop builds, where small overruns like the one above
# go unnoticed instead of killing the app.
"$NDK/ndk-build" -C "$DIR/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_CFLAGS=-fsigned-char -fno-strict-aliasing -fno-delete-null-pointer-checks -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0" \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/mame2010.log" 2>&1 || {
    tail -n 80 "$WORK/mame2010.log"
    exit 1
}
mkdir -p "$OUT"
cp "$DIR/libs/arm64-v8a/libretro.so" "$OUT/libmame2010_libretro_android.so"
echo "   -> $OUT/libmame2010_libretro_android.so ($(du -h "$OUT/libmame2010_libretro_android.so" | cut -f1))"
