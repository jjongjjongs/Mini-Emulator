#!/usr/bin/env bash
# Builds the Famicom/NES (FCEUmm) and Mega Drive (Genesis Plus GX) libretro
# cores for arm64-v8a with the Android NDK and copies them into
# app/src/main/jniLibs. Kept apart from the other build scripts so CI caches
# these separately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-consoles.sh
set -euo pipefail

FCEUMM_REPO=https://github.com/libretro/libretro-fceumm.git
FCEUMM_COMMIT=ac0d00a7fc01166f84c7bac01d1084ebfd2b865e
GPGX_REPO=https://github.com/libretro/Genesis-Plus-GX.git
GPGX_COMMIT=393f70cd64c7334564c19a424c383f53986c13f4

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
checkout "$WORK/fceumm" "$FCEUMM_REPO" "$FCEUMM_COMMIT"
checkout "$WORK/genesis_plus_gx" "$GPGX_REPO" "$GPGX_COMMIT"
build fceumm "$WORK/fceumm/jni" libfceumm_libretro_android.so
build genesis_plus_gx "$WORK/genesis_plus_gx/libretro/jni" libgenesis_plus_gx_libretro_android.so
