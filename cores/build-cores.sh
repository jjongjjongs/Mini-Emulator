#!/usr/bin/env bash
# Builds the arcade libretro cores for arm64-v8a with the Android NDK and
# copies them into app/src/main/jniLibs. The cores are pinned to the commits
# below; bump them deliberately.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./cores/build-cores.sh
set -euo pipefail

FBNEO_REPO=https://github.com/libretro/FBNeo.git
FBNEO_COMMIT=a0cd2c6ab8502b92a78397b3e8cf6db502f279ac
MAME2003P_REPO=https://github.com/libretro/mame2003-plus-libretro.git
MAME2003P_COMMIT=b5ca65f5fd5a80f1372e0fcc67f00f7e0f09cf62

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
    "$NDK/ndk-build" -C "$jni_dir" APP_ABI=arm64-v8a APP_PLATFORM=android-24 -j"$JOBS" > "$WORK/$name.log" 2>&1 || {
        tail -n 60 "$WORK/$name.log"
        exit 1
    }
    mkdir -p "$OUT"
    cp "$jni_dir/../libs/arm64-v8a/libretro.so" "$OUT/$output"
    echo "   -> $OUT/$output ($(du -h "$OUT/$output" | cut -f1))"
}

mkdir -p "$WORK"
checkout "$WORK/fbneo" "$FBNEO_REPO" "$FBNEO_COMMIT"
checkout "$WORK/mame2003-plus" "$MAME2003P_REPO" "$MAME2003P_COMMIT"
build fbneo "$WORK/fbneo/src/burner/libretro/jni" libfbneo_libretro_android.so
build mame2003-plus "$WORK/mame2003-plus/jni" libmame2003_plus_libretro_android.so
