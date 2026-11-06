#!/usr/bin/env bash
# Builds the PC-98 libretro core NP2kai (Neko Project II kai, MIT) for arm64-v8a with the
# Android NDK into app/src/main/jniLibs, and packs the font it reads into
# app/src/main/assets/pc98_system.zip (np2kai/font.bmp):
#   NP2kai's libretro build has no TrueType support, so without a PC-98 font.rom it shows
#   no Japanese text. font.bmp is made here from the Shinonome (東雲) 16-dot bitmap fonts,
#   which are in the public domain, by cores/gen-pc98-font.py. A player's own font.rom
#   (and bios.rom, sound.rom, the YM2608 rhythm samples) go next to it and win.
# The engine runs without bios.rom on its built-in compatible BIOS.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-np2kai.sh
# Needs pcf2bdf and the xfonts-shinonome fonts (apt) for the font.
set -euo pipefail

REPO_URL=https://github.com/AZO234/NP2kai.git
COMMIT=5939e0c6d5985c4c08fc70f289a83290e5d3e6f7   # 2026-09-05

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
SRC="$WORK/np2kai"

mkdir -p "$WORK"
if [ ! -d "$SRC/.git" ]; then
    git init -q "$SRC"
    git -C "$SRC" remote add origin "$REPO_URL"
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$COMMIT" ]; then
    git -C "$SRC" fetch -q --depth 1 origin "$COMMIT"
    git -C "$SRC" checkout -q --force FETCH_HEAD
fi

# jni/Android.mk at this commit has "INCFLAGS += (NP21_INCFLAGS)" (no $), which puts a bare
# "(NP21_INCFLAGS)" on every compile line; and it asks git for a tag the shallow fetch lacks.
sed -i 's/+= (NP21_INCFLAGS)/+= $(NP21_INCFLAGS)/' "$SRC/jni/Android.mk"

echo "== building np2kai"
"$NDK/ndk-build" -C "$SRC/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    NP2KAI_VERSION=rev.22 NP2KAI_HASH="${COMMIT:0:7}" \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/np2kai.log" 2>&1 || {
    tail -n 80 "$WORK/np2kai.log"
    exit 1
}
mkdir -p "$OUT"
cp "$SRC/libs/arm64-v8a/libretro.so" "$OUT/libnp2kai_libretro_android.so"
echo "   -> $OUT/libnp2kai_libretro_android.so ($(du -h "$OUT/libnp2kai_libretro_android.so" | cut -f1))"

echo "== making the PC-98 font"
FONTS=/usr/share/fonts/X11/misc
STAGE="$WORK/pc98_system"
rm -rf "$STAGE"
mkdir -p "$STAGE/np2kai"
gunzip -c "$FONTS/shnmk16.pcf.gz" > "$WORK/shnmk16.pcf"
gunzip -c "$FONTS/shnm8x16r.pcf.gz" > "$WORK/shnm8x16r.pcf"
pcf2bdf -o "$WORK/shnmk16.bdf" "$WORK/shnmk16.pcf"
pcf2bdf -o "$WORK/shnm8x16r.bdf" "$WORK/shnm8x16r.pcf"
python3 "$HERE/gen-pc98-font.py" "$WORK/shnmk16.bdf" "$WORK/shnm8x16r.bdf" "$STAGE/np2kai/font.bmp"
mkdir -p "$ASSETS"
rm -f "$ASSETS/pc98_system.zip"
( cd "$STAGE" && zip -q -r -9 "$ASSETS/pc98_system.zip" np2kai )
echo "   -> $ASSETS/pc98_system.zip ($(du -h "$ASSETS/pc98_system.zip" | cut -f1))"
