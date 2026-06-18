#!/bin/bash
# Build Rust core for Android arm64-v8a
# Requires: rustup, cargo-ndk, Android NDK
#
# Setup:
#   rustup target add aarch64-linux-android
#   cargo install cargo-ndk
#   export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.0.12077973
#
# Run from rust_core/ directory

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Detect NDK
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.0.12077973}"
if [ ! -d "$NDK" ]; then
    echo "NDK not found. Set ANDROID_NDK_HOME or ANDROID_HOME"
    exit 1
fi

export ANDROID_NDK_HOME="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

echo "Building zerocopy-core for aarch64-linux-android..."
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release

echo "Build complete! Library placed in app/src/main/jniLibs/arm64-v8a/"
echo "Size:"
ls -lh ../app/src/main/jniLibs/arm64-v8a/libzerocopy_core.so
