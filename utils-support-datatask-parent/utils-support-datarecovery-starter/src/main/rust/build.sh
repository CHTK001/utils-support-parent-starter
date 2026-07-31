#!/bin/bash
set -e

BUILD_MODE="release"
TARGET="x86_64-unknown-linux-gnu"

echo "=========================================="
echo "  Rust Data Recovery FFI Build"
echo "=========================================="

if [ "$1" = "debug" ]; then
    BUILD_MODE="debug"
fi

if [ "$1" = "linux" ] || [ "$1" = "linux-debug" ]; then
    TARGET="x86_64-unknown-linux-gnu"
    if [ "$1" = "linux-debug" ]; then
        BUILD_MODE="debug"
    fi
fi

echo "[INFO] Target: $TARGET"
echo "[INFO] Mode: $BUILD_MODE"

cargo build --$BUILD_MODE --target $TARGET

echo "=========================================="
echo "  Build completed!"
echo "=========================================="
