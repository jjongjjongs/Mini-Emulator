#!/usr/bin/env bash
# Fetches the original Xbox engine from a HakuX release (GPL-3.0-or-later; https://github.com/rfandango/hakuX)
# instead of building it (it needs a FEX fork and a long native build). The release APK is pinned by
# tag and checksum; from it come
#   libhakuxcore.so   the engine (FEX JIT, the Xbox kernel and devices, the HLE GPU), bound to
#                     com.rfandango.haku_x.NativeCoreActivity (app/src/main/java/com/rfandango/haku_x)
#   libSDL3.so        SDL3, the engine's window, sound and input (its Java side: app/src/main/java/org/libsdl/app,
#                     copied from the same tag)
# The adrenotools hook libraries in the APK are the same kind XenDroid's fetch brings; they are not
# taken (the app runs the engine on the system's Vulkan driver).
# The source for this exact build is tag v1.3.0 (commit a90f01ee4629977b36a76175cbd781d383056293).
#
# Usage: GH_TOKEN=... ./cores/fetch-hakux.sh
set -euo pipefail

TAG=v1.3.0
APK=hakuX-1.3.0.apk
SHA256=143f9e602ab20a7143f357d3a5fa8eba145bdd6cde90d80cbcfadfd7919e9176

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}/hakux"
LIBS="$REPO/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$WORK" "$LIBS"
if [ ! -f "$WORK/$APK" ] || ! echo "$SHA256  $WORK/$APK" | sha256sum -c --quiet; then
    rm -f "$WORK/$APK"
    curl -fsSL -o "$WORK/$APK" "https://github.com/rfandango/hakuX/releases/download/$TAG/$APK" \
        || gh release download "$TAG" -R rfandango/hakuX -p "$APK" -D "$WORK" \
        || { gh release download "$TAG" -R rfandango/hakuX -p "*.apk" -D "$WORK/any" && \
             for f in "$WORK"/any/*.apk; do echo "$SHA256  $f" | sha256sum -c --quiet && cp "$f" "$WORK/$APK"; done; }
fi
echo "$SHA256  $WORK/$APK" | sha256sum -c

rm -rf "$WORK/x"
for name in libhakuxcore.so libSDL3.so; do
    unzip -q "$WORK/$APK" "lib/arm64-v8a/$name" -d "$WORK/x"
    cp "$WORK/x/lib/arm64-v8a/$name" "$LIBS/"
done
ls -l "$LIBS/libhakuxcore.so" "$LIBS/libSDL3.so"
