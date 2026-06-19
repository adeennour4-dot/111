#!/bin/bash
set -e

echo "=== ZeroCopy v8 Build Script ==="
echo ""

# Build Rust core (optional)
if [ -d "rust_core" ] && command -v cargo &> /dev/null; then
    echo "[1/2] Building Rust optimization layer..."
    cd rust_core
    if [ -z "${ANDROID_NDK_HOME}" ]; then
        echo "  ANDROID_NDK_HOME not set, skipping Rust build"
    else
        ./build_android.sh
    fi
    cd ..
else
    echo "[1/2] Rust core not available (optional, skipping)"
fi

# Build Android APK
echo "[2/2] Building Android APK..."
./gradlew assembleDebug

echo ""
echo "=== Build Complete ==="
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "Install: adb install -r app/build/outputs/apk/debug/app-debug.apk"
