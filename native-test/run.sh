#!/usr/bin/env bash
# Builds the native bridge for the host (Linux x86_64) against small stand-ins
# for the Android NDK APIs, builds the mGBA libretro core from source, and
# drives the bridge through JNI from a plain JVM with a tiny test ROM.
#
# Needs: clang/clang++, ld.lld, llvm-objcopy, cmake, make, git, a JDK, Vulkan headers
# (libvulkan-dev; the Vulkan presenter is built with a headless surface for the host).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build}"
MGBA_REF="${MGBA_REF:-master}"
mkdir -p "$WORK"

# 1. mGBA libretro core
if [ ! -f "$WORK/mgba-build/mgba_libretro.so" ]; then
    if [ ! -d "$WORK/mgba" ]; then
        git clone --depth 1 --branch "$MGBA_REF" https://github.com/libretro/mgba.git "$WORK/mgba"
    fi
    cmake -S "$WORK/mgba" -B "$WORK/mgba-build" -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_LIBRETRO=ON -DBUILD_QT=OFF -DBUILD_SDL=OFF -DBUILD_SHARED=OFF -DBUILD_STATIC=OFF \
        -DSKIP_LIBRARY=ON -DUSE_FFMPEG=OFF -DUSE_ZLIB=OFF -DUSE_PNG=OFF -DUSE_LIBZIP=OFF \
        -DUSE_MINIZIP=OFF -DUSE_SQLITE3=OFF -DUSE_ELF=OFF -DUSE_LUA=OFF -DUSE_EDITLINE=OFF \
        -DUSE_DEBUGGERS=OFF -DUSE_GDB_STUB=OFF -DUSE_EPOXY=OFF -DBUILD_GL=OFF -DBUILD_GLES2=OFF \
        -DBUILD_GLES3=OFF -DUSE_DISCORD_RPC=OFF > "$WORK/mgba-cmake.log"
    make -C "$WORK/mgba-build" -j"$(nproc)" mgba_libretro > "$WORK/mgba-make.log"
fi
mkdir -p "$WORK/lib"
ln -sf "$WORK/mgba-build/mgba_libretro.so" "$WORK/lib/libmgba_libretro_android.so"

# 2. Test ROM: fills the screen red, writes a marker and KEYINPUT into SRAM.
clang --target=armv4t-none-eabi -c "$HERE/rom/rom.s" -o "$WORK/rom.o"
ld.lld -Ttext=0x08000000 -e _start "$WORK/rom.o" -o "$WORK/rom.elf"
llvm-objcopy -O binary "$WORK/rom.elf" "$WORK/test.gba"
truncate -s 1024 "$WORK/test.gba"

# 3. Bridge + NDK stand-ins
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
SANITIZE="${SANITIZE:-}"
clang++ -std=c++17 -O1 -g -Wall -Wextra -fPIC -shared $SANITIZE \
    -I"$HERE/include" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -DVKHW_HEADLESS "$REPO/app/src/main/cpp/gbabridge.cpp" "$REPO/app/src/main/cpp/vulkan_hw.cpp" "$REPO/app/src/main/cpp/dsnet.cpp" "$HERE/stubs.cpp" \
    -o "$WORK/lib/libgbabridge.so" -ldl -lpthread

# 4. JNI harness. NativeGba is copied from the app with its methods made public.
mkdir -p "$WORK/src/com/example/minigba" "$WORK/classes"
sed -e 's/^final class NativeGba/public final class NativeGba/' -e 's/^    static native/    public static native/' \
    "$REPO/app/src/main/java/com/example/minigba/NativeGba.java" > "$WORK/src/com/example/minigba/NativeGba.java"
javac -d "$WORK/classes" "$WORK/src/com/example/minigba/NativeGba.java" $(find "$HERE/java" -name '*.java')

rm -rf "$WORK/data"
LD_LIBRARY_PATH="$WORK/lib" java -Djava.library.path="$WORK/lib" -cp "$WORK/classes" \
    HostTest "$WORK/test.gba" "$WORK/data"
