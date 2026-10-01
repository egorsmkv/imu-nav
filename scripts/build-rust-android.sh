#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
output_root=${1:-"$repo_root/app/build/generated/rustJniLibs"}

find_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
        printf '%s\n' "$ANDROID_NDK_HOME"
        return
    fi
    if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "$ANDROID_NDK_ROOT" ]]; then
        printf '%s\n' "$ANDROID_NDK_ROOT"
        return
    fi
    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [[ -n "$sdk_root" && -d "$sdk_root/ndk" ]] || continue
        find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1
        return
    done
    return 1
}

ndk_root=$(find_ndk) || {
    echo "Android NDK not found. Set ANDROID_NDK_HOME or install an NDK in the Android SDK." >&2
    exit 1
}
toolchain_root="$ndk_root/toolchains/llvm/prebuilt"
host_toolchain=$(find "$toolchain_root" -mindepth 1 -maxdepth 1 -type d -print -quit)
if [[ -z "$host_toolchain" ]]; then
    echo "Android NDK LLVM toolchain not found under $toolchain_root" >&2
    exit 1
fi

api=26
rust_target_dir="$repo_root/native/target/android"

build_target() {
    local rust_target=$1
    local abi=$2
    local linker_prefix=$3
    local linker="$host_toolchain/bin/${linker_prefix}${api}-clang"
    if [[ ! -x "$linker" ]]; then
        echo "Android linker not found: $linker" >&2
        exit 1
    fi

    local linker_var
    linker_var="CARGO_TARGET_$(printf '%s' "$rust_target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
    env "$linker_var=$linker" CARGO_TARGET_DIR="$rust_target_dir" \
        cargo build --manifest-path "$repo_root/native/Cargo.toml" --package imu-nav-jni --release --target "$rust_target"
    mkdir -p "$output_root/$abi"
    install -m 0644 "$rust_target_dir/$rust_target/release/libimu_nav_jni.so" "$output_root/$abi/libimu_nav_jni.so"
}

build_target aarch64-linux-android arm64-v8a aarch64-linux-android
build_target armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi
build_target x86_64-linux-android x86_64 x86_64-linux-android
