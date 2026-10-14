#!/usr/bin/env bash
# Builds the PPSSPP libretro core (PSP) for arm64-v8a with the Android NDK,
# copies it into app/src/main/jniLibs and packs the system files the core
# reads from <system>/PPSSPP (fonts, VFPU tables, compat.ini, ...) into
# app/src/main/assets/ppsspp_system.zip. Kept apart from the other build
# scripts so CI caches this long build separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-ppsspp.sh
set -euo pipefail

PPSSPP_REPO=https://github.com/hrydgard/ppsspp.git
PPSSPP_COMMIT=fa50bb1976065c4f8b1b47af227d367fe9771555  # v1.20.4

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"
DIR="$WORK/ppsspp"

mkdir -p "$WORK"
if [ ! -d "$DIR/.git" ]; then
    git init -q "$DIR"
    git -C "$DIR" remote add origin "$PPSSPP_REPO"
fi
if [ "$(git -C "$DIR" rev-parse HEAD 2>/dev/null || true)" != "$PPSSPP_COMMIT" ]; then
    git -C "$DIR" fetch -q --depth 1 origin "$PPSSPP_COMMIT"
    git -C "$DIR" checkout -q --force FETCH_HEAD
fi
# Everything the libretro build uses; test suites, other platforms' SDKs and the
# debugger UI are left out.
SKIP=" pspautotests frametests ios/MoltenVK SDL/macOS assets/debugger ext/discord-rpc ext/oboe "
for path in $(git -C "$DIR" config --file .gitmodules --get-regexp '\.path$' | awk '{print $2}'); do
    case "$SKIP" in *" $path "*) continue ;; esac
    git -C "$DIR" submodule update -q --init --depth 1 -- "$path"
done

echo "== building ppsspp"
"$NDK/ndk-build" -C "$DIR/libretro/jni" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/ppsspp.log" 2>&1 || {
    grep -n "error" "$WORK/ppsspp.log" | head -n 40
    tail -n 60 "$WORK/ppsspp.log"
    exit 1
}
mkdir -p "$OUT" "$ASSETS"
cp "$DIR/libretro/libs/arm64-v8a/libretro.so" "$OUT/libppsspp_libretro_android.so"
echo "   -> $OUT/libppsspp_libretro_android.so ($(du -h "$OUT/libppsspp_libretro_android.so" | cut -f1))"

rm -f "$ASSETS/ppsspp_system.zip"
(cd "$DIR/assets" && find flash0 vfpu shaders compat.ini knownfuncs.ini langregion.ini ppge_atlas.zim ppge_atlas.meta -type f | sort \
    | TZ=UTC zip -q -X -D "$ASSETS/ppsspp_system.zip" -@)
echo "   -> $ASSETS/ppsspp_system.zip ($(du -h "$ASSETS/ppsspp_system.zip" | cut -f1))"
