#!/usr/bin/env bash
# Builds the WonderSwan (Beetle Cygne) and Neo Geo Pocket (Beetle NeoPop) libretro cores
# for arm64-v8a with the Android NDK and copies them into app/src/main/jniLibs. Kept apart
# from the other build scripts so CI caches these separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-handhelds.sh
set -euo pipefail

WSWAN_REPO=https://github.com/libretro/beetle-wswan-libretro.git
WSWAN_COMMIT=4b01295838ea89e3f1355bbe4cb5cf98aa6108cd
NGP_REPO=https://github.com/libretro/beetle-ngp-libretro.git
NGP_COMMIT=a50d5ac288a81f2104ddf43195a4efdd15c72227

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
JOBS="${JOBS:-$(nproc)}"

checkout() {
    local dir="$1" repo="$2" commit="$3"
    if [ ! -d "$dir/.git" ]; then
        git init -q "$dir"
        git -C "$dir" remote add origin "$repo"
    fi
    if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]; then
        git -C "$dir" fetch -q --depth 1 origin "$commit"
        git -C "$dir" checkout -q --force FETCH_HEAD
    fi
}

build() {
    local name="$1" jni_dir="$2" output="$3"
    echo "== building $name"
    "$NDK/ndk-build" -C "$jni_dir" APP_ABI=arm64-v8a APP_PLATFORM=android-24 \
        "APP_LDFLAGS=-Wl,-z,max-page-size=16384" -j"$JOBS" > "$WORK/$name.log" 2>&1 || {
        tail -n 60 "$WORK/$name.log"
        exit 1
    }
    mkdir -p "$OUT"
    cp "$jni_dir/../libs/arm64-v8a/libretro.so" "$OUT/$output"
    echo "   -> $OUT/$output ($(du -h "$OUT/$output" | cut -f1))"
}

mkdir -p "$WORK"
checkout "$WORK/beetle_wswan" "$WSWAN_REPO" "$WSWAN_COMMIT"
checkout "$WORK/beetle_ngp" "$NGP_REPO" "$NGP_COMMIT"
build beetle_wswan "$WORK/beetle_wswan/jni" libmednafen_wswan_libretro_android.so
build beetle_ngp "$WORK/beetle_ngp/jni" libmednafen_ngp_libretro_android.so
