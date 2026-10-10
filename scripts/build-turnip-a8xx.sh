#!/usr/bin/env bash
# Builds a Mesa Turnip (freedreno Vulkan) driver with Adreno a8xx support for
# Android (arm64) and packages it as an adrenotools driver zip.
# Requirements: Linux x86_64 host, git, python3, meson, ninja, glslang, curl, unzip, zip,
# plus the Android NDK (set ANDROID_NDK_HOME).
# Usage: MESA_REPO=<url> MESA_REF=<branch|tag> API_LEVEL=33 ./build-turnip-a8xx.sh
set -euo pipefail

MESA_REPO="${MESA_REPO:-https://gitlab.freedesktop.org/mesa/mesa.git}"
MESA_REF="${MESA_REF:-main}"       # a8xx support lives in recent mainline Mesa; use an a8xx fork here if needed
API_LEVEL="${API_LEVEL:-33}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
WORK="${WORK:-$PWD/build-turnip}"
OUT="${OUT:-$PWD/out}"

mkdir -p "$WORK" "$OUT"
cd "$WORK"
[ -d mesa ] || git clone --depth 1 --branch "$MESA_REF" "$MESA_REPO" mesa

TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cat > android-aarch64.txt <<CROSS
[binaries]
ar = '$TC/llvm-ar'
c = ['ccache', '$TC/aarch64-linux-android$API_LEVEL-clang']
cpp = ['ccache', '$TC/aarch64-linux-android$API_LEVEL-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '-static-libstdc++']
strip = '$TC/llvm-strip'
pkg-config = ['env', 'PKG_CONFIG_LIBDIR=NDKDIR/pkgconfig', '/usr/bin/pkg-config']
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
CROSS

cd mesa
meson setup ../build --cross-file ../android-aarch64.txt -Dbuildtype=release \
  -Dplatforms=android -Dplatform-sdk-version="$API_LEVEL" -Dandroid-stub=true \
  -Dgallium-drivers= -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl \
  -Dvulkan-beta=true -Dglx=disabled -Degl=disabled -Dllvm=disabled -Dshared-llvm=disabled
ninja -C ../build src/freedreno/vulkan/libvulkan_freedreno.so

PKG="$WORK/pkg"; rm -rf "$PKG"; mkdir -p "$PKG"
cp ../build/src/freedreno/vulkan/libvulkan_freedreno.so "$PKG/vulkan.turnip-a8xx.so"
cat > "$PKG/meta.json" <<META
{
  "schemaVersion": 1,
  "name": "Turnip a8xx",
  "description": "Mesa Turnip with Adreno a8xx support ($MESA_REF)",
  "author": "build-turnip-a8xx.sh",
  "packageVersion": "1",
  "vendor": "Mesa",
  "driverVersion": "Vulkan 1.3 Turnip $(git rev-parse --short HEAD)",
  "minApi": 28,
  "libraryName": "vulkan.turnip-a8xx.so"
}
META
(cd "$PKG" && zip -r "$OUT/turnip-a8xx.zip" .)
echo "Built $OUT/turnip-a8xx.zip"
