# Reproduce the synthetic audit against production Kotlin and Rust, without personal recordings.
$ErrorActionPreference = 'Stop'
$auditRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$auditOutput = Join-Path $auditRoot 'build/math-audit'
New-Item -ItemType Directory -Path $auditOutput -Force | Out-Null

Push-Location -LiteralPath $auditRoot
try {
    & .\gradlew.bat --init-script tools/math_audit/math-audit.init.gradle :core:mathAudit --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin audit execution failed' }

    & cargo build --manifest-path native/Cargo.toml --package imu-nav-core --locked
    if ($LASTEXITCODE -ne 0) { throw 'Rust core build failed' }

    $auditTarget = if ($env:CARGO_TARGET_DIR) {
        if ([System.IO.Path]::IsPathRooted($env:CARGO_TARGET_DIR)) {
            [System.IO.Path]::GetFullPath($env:CARGO_TARGET_DIR)
        } else {
            [System.IO.Path]::GetFullPath((Join-Path $auditRoot $env:CARGO_TARGET_DIR))
        }
    } else {
        Join-Path $auditRoot 'native/target'
    }
    $auditDependencies = Join-Path $auditTarget 'debug/deps'
    $auditLibrary = Get-ChildItem -LiteralPath $auditDependencies -Filter 'libimu_nav_core-*.rlib' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $auditLibrary) { throw 'Compiled Rust core library not found' }
    $auditExecutable = Join-Path $auditOutput 'native-probe.exe'
    & rustc --edition 2024 tools/math_audit/native_probe.rs --extern "imu_nav_core=$auditLibrary" -L "dependency=$auditDependencies" -o $auditExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Rust audit compilation failed' }
    & $auditExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Rust audit execution failed' }
} finally {
    Pop-Location
}
