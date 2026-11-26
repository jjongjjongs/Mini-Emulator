#!/usr/bin/env bash
# Fetches the Xbox 360 engine from a XenDroid release (Xenia for Android: XenDroid's own code
# WTFPL, Xenia BSD-3-Clause; https://github.com/rfandango/XenDroid) instead of building it
# (Xenia's build takes a long time). The release APK is pinned by tag and checksum; from it come
#   libe.so                     the engine, bound to xendroid.compose.Emulator / xendroid.emulator.Emulator
#                               (app/src/main/java/xendroid, copied from XenDroid's emulator-core)
#   libhardware_ProcessorInfo.so  the Vulkan GPU probe (xendroid.hardware.ProcessorInfo)
#   libSPIRV-Tools-shared.so    shader tools the engine loads
#   lib*_hook*.so               the hooks for a player's own Adreno driver (adrenotools)
#   assets/config/default_config.toml  the engine's settings, assets/game-patches  its patches
# The source for this exact build is tag XenDroid-0b11201 (commit 0b1120187340be3644e28ceb7a04be04cd0f2e08).
#
# Usage: GH_TOKEN=... ./cores/fetch-xendroid.sh
set -euo pipefail

TAG=XenDroid-0b11201
APK=XenDroid_Release_0b11201.apk
SHA256=9948e467af8193952a2a90c918996c015b3d15c6936616ea2b14dc5ccaca3d73

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}/xendroid"
LIBS="$REPO/app/src/main/jniLibs/arm64-v8a"
ASSETS="$REPO/app/src/main/assets/x360"

mkdir -p "$WORK" "$LIBS"
if [ ! -f "$WORK/$APK" ] || ! echo "$SHA256  $WORK/$APK" | sha256sum -c --quiet; then
    rm -f "$WORK/$APK"
    curl -fsSL -o "$WORK/$APK" "https://github.com/rfandango/XenDroid/releases/download/$TAG/$APK" \
        || gh release download "$TAG" -R rfandango/XenDroid -p "$APK" -D "$WORK"
fi
echo "$SHA256  $WORK/$APK" | sha256sum -c

LIBNAMES="libe.so libhardware_ProcessorInfo.so libSPIRV-Tools-shared.so libhook_impl.so libmain_hook.so libgsl_alloc_hook.so libfile_redirect_hook.so"
rm -rf "$WORK/x"
for name in $LIBNAMES; do
    unzip -q "$WORK/$APK" "lib/arm64-v8a/$name" -d "$WORK/x"
    cp "$WORK/x/lib/arm64-v8a/$name" "$LIBS/"
done
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
unzip -q "$WORK/$APK" 'assets/config/default_config.toml' 'assets/game-patches/*' -d "$WORK/x"
cp "$WORK/x/assets/config/default_config.toml" "$ASSETS/default_config.toml"
cp -r "$WORK/x/assets/game-patches" "$ASSETS/game-patches"
echo "   patches: $(find "$ASSETS/game-patches" -type f | wc -l) files"
for name in $LIBNAMES; do ls -lh "$LIBS/$name"; done
