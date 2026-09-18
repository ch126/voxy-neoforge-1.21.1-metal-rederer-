#!/usr/bin/env bash
# Convenience script for building libvoxy_metal.dylib on macOS (Apple Silicon).
# Usage: ./build.sh [Debug|Release]
#
# Requires: Xcode command-line tools (clang++), JDK 21. CMake >= 3.20 is
# optional; when unavailable the script invokes clang++ directly.
# Output:  src/main/resources/natives/macos-arm64/libvoxy_metal.dylib

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "error: libvoxy_metal only builds on macOS (got $(uname -s))" >&2
  exit 1
fi
if [[ "$(uname -m)" != "arm64" ]]; then
  echo "warning: host arch is $(uname -m); building arm64 slice regardless" >&2
fi

BUILD_TYPE="${1:-Release}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${HERE}/build"
OUTPUT_DIR="${HERE}/../../src/main/resources/natives/macos-arm64"
OUTPUT_FILE="${OUTPUT_DIR}/libvoxy_metal.dylib"

if [[ "${BUILD_TYPE}" != "Release" && "${BUILD_TYPE}" != "Debug" ]]; then
  echo "error: build type must be Release or Debug (got ${BUILD_TYPE})" >&2
  exit 1
fi

: "${JAVA_HOME:=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)}"
export JAVA_HOME
echo "Using JAVA_HOME=${JAVA_HOME}"

if command -v cmake >/dev/null 2>&1; then
  cmake -S "${HERE}" -B "${BUILD_DIR}" \
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
    -DCMAKE_OSX_ARCHITECTURES=arm64

  cmake --build "${BUILD_DIR}" --config "${BUILD_TYPE}" --parallel
else
  echo "cmake not found; using the Xcode Command Line Tools compiler directly"
  if ! xcrun --find clang++ >/dev/null 2>&1; then
    echo "error: clang++ not found; install Xcode Command Line Tools" >&2
    exit 1
  fi

  OPT_FLAGS=(-O3 -DNDEBUG)
  if [[ "${BUILD_TYPE}" == "Debug" ]]; then
    OPT_FLAGS=(-O0 -g)
  fi

  mkdir -p "${OUTPUT_DIR}"
  xcrun clang++ \
    -std=c++17 -arch arm64 -mmacosx-version-min=12.0 \
    -fobjc-arc -Wall -Wextra -Wno-unused-parameter \
    "${OPT_FLAGS[@]}" -dynamiclib \
    -install_name @rpath/libvoxy_metal.dylib \
    -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/darwin" -I"${HERE}/src" \
    "${HERE}/src/voxy_metal_jni.mm" \
    "${HERE}/src/voxy_metal_device.mm" \
    "${HERE}/src/voxy_metal_buffer.mm" \
    "${HERE}/src/voxy_metal_texture.mm" \
    "${HERE}/src/voxy_metal_memutil.mm" \
    "${HERE}/src/voxy_metal_render.mm" \
    "${HERE}/src/voxy_metal_compute.mm" \
    "${HERE}/src/voxy_metal_icb.mm" \
    "${HERE}/src/voxy_metal_iosurface.mm" \
    "${HERE}/src/voxy_metal_iosurface_gl.mm" \
    -framework Metal -framework Foundation -framework QuartzCore \
    -framework IOSurface -framework CoreGraphics -framework OpenGL \
    -o "${OUTPUT_FILE}"
fi

echo
echo "Built libvoxy_metal.dylib for ${BUILD_TYPE}."
echo "Installed to: src/main/resources/natives/macos-arm64/libvoxy_metal.dylib"
