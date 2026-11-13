# Reproduce the synthetic audit against production Kotlin and Rust, without personal recordings.
$ErrorActionPreference = 'Stop'
$auditRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$auditOutput = Join-Path $auditRoot 'build/math-audit'
New-Item -ItemType Directory -Path $auditOutput -Force | Out-Null

Push-Location -LiteralPath $auditRoot
try {
    & .\gradlew.bat --init-script tools/math_audit/math-audit.init.gradle :core:mathAudit --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin audit execution failed' }

    $auditMessages = & cargo build --manifest-path native/Cargo.toml --package imu-nav-core --locked --message-format=json-render-diagnostics |
        ForEach-Object { $_ | ConvertFrom-Json }
    if ($LASTEXITCODE -ne 0) { throw 'Rust core build failed' }

    # Cargo 1.100 stores intermediate libraries in per-package directories. Use the
    # current build's artifacts so stale libraries or a custom target directory cannot win.
    $auditArtifacts = @($auditMessages | Where-Object { $_.reason -eq 'compiler-artifact' })
    $auditLibraries = @($auditArtifacts | Where-Object { $_.target.name -eq 'imu_nav_core' } |
        ForEach-Object { $_.filenames } | Where-Object { $_.EndsWith('.rlib') })
    if ($auditLibraries.Count -ne 1) { throw 'Expected exactly one compiled Rust core library' }
    $auditDependencyArguments = @($auditArtifacts | ForEach-Object { $_.filenames } |
        ForEach-Object { Split-Path -Parent $_ } | Sort-Object -Unique |
        ForEach-Object { '-L'; "dependency=$_" })
    $auditExecutable = Join-Path $auditOutput 'native-probe.exe'
    & rustc --edition 2024 tools/math_audit/native_probe.rs --extern "imu_nav_core=$($auditLibraries[0])" @auditDependencyArguments -o $auditExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Rust audit compilation failed' }
    & $auditExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Rust audit execution failed' }
} finally {
    Pop-Location
}
