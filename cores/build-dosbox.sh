#!/usr/bin/env bash
# Builds the DOSBox Pure libretro core (MS-DOS) for arm64-v8a with the
# Android NDK and copies it into app/src/main/jniLibs. Kept apart from
# build-cores.sh so CI caches it separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-dosbox.sh
set -euo pipefail

DOSBOX_REPO=https://github.com/libretro/dosbox-pure.git
DOSBOX_COMMIT=cdaea10ce8cac231bc341ca05d4c5e4e5fe0fbd5

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/dosbox-pure"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$DOSBOX_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$DOSBOX_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$DOSBOX_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi

echo "== building dosbox-pure"
"$NDK/ndk-build" -C "$DIR/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/dosbox-pure.log" 2>&1 || {
    tail -n 60 "$WORK/dosbox-pure.log"
    exit 1
}
mkdir -p "$OUT"
cp "$DIR/libs/arm64-v8a/libretro.so" "$OUT/libdosbox_pure_libretro_android.so"
echo "   -> $OUT/libdosbox_pure_libretro_android.so ($(du -h "$OUT/libdosbox_pure_libretro_android.so" | cut -f1))"
