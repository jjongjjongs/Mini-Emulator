#!/usr/bin/env bash
# Builds the blueMSX libretro core (MSX1 / MSX2 / MSX2+ / turbo R) for arm64-v8a with the
# Android NDK into app/src/main/jniLibs, and packs the files it reads from the system
# folder into app/src/main/assets/msx_system.zip:
#   Databases/          the cartridge mapper database
#   Machines/*/config.ini  every machine description (they name the ROMs they need)
#   Machines/*C-BIOS*/  the C-BIOS ROMs (BSD-style licence, ship with the core)
# The repository's other ROM files are the original MSX BIOS / disk / Kanji ROMs, which
# are not ours to give out: they are left out, and the player adds their own dumps.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-msx.sh
set -euo pipefail

REPO_URL=https://github.com/libretro/blueMSX-libretro.git
COMMIT=e3086eb5d36d77fa11704cf53dc176686e70127d

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
SRC="$WORK/bluemsx"

mkdir -p "$WORK"
if [ ! -d "$SRC/.git" ]; then
    git init -q "$SRC"
    git -C "$SRC" remote add origin "$REPO_URL"
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$COMMIT" ]; then
    git -C "$SRC" fetch -q --depth 1 origin "$COMMIT"
    git -C "$SRC" checkout -q --force FETCH_HEAD
fi

echo "== building bluemsx"
"$NDK/ndk-build" -C "$SRC/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/bluemsx.log" 2>&1 || {
    tail -n 60 "$WORK/bluemsx.log"
    exit 1
}
mkdir -p "$OUT"
cp "$SRC/libs/arm64-v8a/libretro.so" "$OUT/libbluemsx_libretro_android.so"
echo "   -> $OUT/libbluemsx_libretro_android.so ($(du -h "$OUT/libbluemsx_libretro_android.so" | cut -f1))"

echo "== packing msx_system.zip"
STAGE="$WORK/msx_system"
rm -rf "$STAGE"
mkdir -p "$STAGE/Machines"
cp -r "$SRC/system/bluemsx/Databases" "$STAGE/"
( cd "$SRC/system/bluemsx/Machines"
  for dir in */; do
      dir="${dir%/}"
      mkdir -p "$STAGE/Machines/$dir"
      [ -f "$dir/config.ini" ] && cp "$dir/config.ini" "$STAGE/Machines/$dir/"
      case "$dir" in
          *C-BIOS*) cp "$dir"/* "$STAGE/Machines/$dir/" ;;
      esac
  done )
mkdir -p "$STAGE/Machines/Shared Roms"
# Nothing but C-BIOS may carry a ROM image.
if find "$STAGE" -type f \( -iname '*.rom' -o -iname '*.bin' \) ! -path '*C-BIOS*' | grep -q .; then
    echo "unexpected ROM files in msx_system.zip" >&2
    exit 1
fi
mkdir -p "$ASSETS"
rm -f "$ASSETS/msx_system.zip"
( cd "$STAGE" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | TZ=UTC zip -q -X -D "$ASSETS/msx_system.zip" -@ )
echo "   -> $ASSETS/msx_system.zip ($(du -h "$ASSETS/msx_system.zip" | cut -f1))"
