# Builds the same API-26 JNI libraries as the Unix script, using native Windows NDK launchers.
param([string] $OutputRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot 'app/build/generated/rustJniLibs'
}

# Explicit NDK selections take precedence over SDK discovery, matching the Unix build.
function Find-AndroidNdk {
    foreach ($candidate in @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return $candidate
        }
    }
    $defaultSdk = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, $defaultSdk)) {
        if (-not $sdkRoot) { continue }
        $ndkDirectory = Join-Path $sdkRoot 'ndk'
        if (-not (Test-Path -LiteralPath $ndkDirectory -PathType Container)) { continue }
        $installed = Get-ChildItem -LiteralPath $ndkDirectory -Directory |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+(\.\d+)?$' } |
            Sort-Object { [version] $_.Name } -Descending |
            Select-Object -First 1
        if ($installed) { return $installed.FullName }
    }
    throw 'Android NDK not found. Set ANDROID_NDK_HOME or install NDK (Side by side) in Android Studio SDK Manager.'
}

$ndkRoot = Find-AndroidNdk
$toolchainBin = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin'
if (-not (Test-Path -LiteralPath $toolchainBin -PathType Container)) {
    throw "Android NDK Windows LLVM toolchain not found under $toolchainBin"
}
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Cargo not found. Install Rust and restart Android Studio so its process inherits the updated PATH.'
}

$api = 26
$rustTargetDirectory = Join-Path $repoRoot 'native/target/android'
$env:CARGO_TARGET_DIR = $rustTargetDirectory

# Cargo invokes each NDK .cmd launcher with the target/API already configured; no WSL is needed.
function Build-AndroidTarget([string] $RustTarget, [string] $Abi, [string] $LinkerPrefix) {
    $linker = Join-Path $toolchainBin "${LinkerPrefix}${api}-clang.cmd"
    if (-not (Test-Path -LiteralPath $linker -PathType Leaf)) {
        throw "Android linker not found: $linker"
    }
    $linkerVariable = 'CARGO_TARGET_' + $RustTarget.ToUpperInvariant().Replace('-', '_') + '_LINKER'
    [Environment]::SetEnvironmentVariable($linkerVariable, $linker, 'Process')
    & cargo build --manifest-path (Join-Path $repoRoot 'native/Cargo.toml') --package imu-nav-jni --release --target $RustTarget
    if ($LASTEXITCODE -ne 0) {
        throw "Cargo build failed for $RustTarget (exit $LASTEXITCODE). See the Cargo output above."
    }
    $abiDirectory = Join-Path $OutputRoot $Abi
    New-Item -ItemType Directory -Path $abiDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $rustTargetDirectory "$RustTarget/release/libimu_nav_jni.so") -Destination (Join-Path $abiDirectory 'libimu_nav_jni.so') -Force
}

Build-AndroidTarget 'aarch64-linux-android' 'arm64-v8a' 'aarch64-linux-android'
Build-AndroidTarget 'armv7-linux-androideabi' 'armeabi-v7a' 'armv7a-linux-androideabi'
Build-AndroidTarget 'x86_64-linux-android' 'x86_64' 'x86_64-linux-android'
