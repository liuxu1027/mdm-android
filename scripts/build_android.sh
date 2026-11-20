#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CRATE_MANIFEST="$ROOT_DIR/crates/mdm-android/Cargo.toml"
JNI_OUT="$ROOT_DIR/android/app/src/main/jniLibs"
KT_OUT="$ROOT_DIR/android/app/src/main/java"

mkdir -p "$JNI_OUT" "$KT_OUT"

echo ">>> Building libmdm_android.so for arm64-v8a..."
cd "$ROOT_DIR/crates/mdm-android"
cargo ndk -t arm64-v8a -o "$JNI_OUT" build --release
cd "$ROOT_DIR"

SO_PATH="$ROOT_DIR/crates/mdm-android/target/aarch64-linux-android/release/libmdm_android.so"
if [ ! -f "$SO_PATH" ]; then
  SO_PATH="$JNI_OUT/arm64-v8a/libmdm_android.so"
fi

echo ">>> Generating UniFFI Kotlin bindings..."
cd "$ROOT_DIR/crates/mdm-android"
cargo run --bin uniffi-bindgen -- generate --library "$SO_PATH" --language kotlin --out-dir "$KT_OUT"
cd "$ROOT_DIR"

echo ">>> Done! Native libs in $JNI_OUT and Kotlin bindings in $KT_OUT"
