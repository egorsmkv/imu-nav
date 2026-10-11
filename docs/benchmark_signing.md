# Installing and updating benchmark APKs

Benchmark builds install as `org.imunav.app.bench`, independently of the release and debug apps.
Both distribution flavors use this benchmark package. An update must have the same signing
certificate as the installed copy and must not reduce its version code.

## Why older CI APKs could not replace an existing installation

Previously, `benchmark` always used Android's default debug signing configuration. A local
machine usually keeps its debug keystore, but fresh GitHub Actions runners generate different
keys. Two CI runs could therefore publish different certificates for the same package. Android
rejects such replacement with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; changing the filename or
increasing the version code cannot fix that certificate mismatch.

This is the signing defect identified for [issue #76](https://github.com/egorsmkv/imu-nav/issues/76).
The issue has no device error log or APK pair attached, so other installation errors should be
diagnosed separately rather than assuming every failure is a certificate mismatch.

## One-time setup for installable CI artifacts

Run the setup generator from the repository root with Python 3.10+ and JDK keytool (set `JAVA_HOME`
if keytool is not on PATH). It creates a dedicated RSA-3072 benchmark identity and random passwords:

```bash
python3 tools/generate_benchmark_signing_env.py
```

On Windows use `python` instead of `python3`, or `uv run python` if Python is managed by uv.
The generator does not print credentials or upload them. It writes this bundle to the ignored
`keystore/benchmark-signing/` directory:

| File | Purpose |
| --- | --- |
| `benchmark.jks` | Durable private signing key; retain a secure backup |
| `env.sh` | Bash/POSIX exports for the existing APK signer |
| `env.ps1` | PowerShell exports, also readable by Windows PowerShell 5.1 |
| `github-settings.json` | Four Actions secret values and the public certificate variable |

The directory is owner-only: POSIX permissions are `0700`/`0600`, and Windows uses a current-user
ACL before writing secrets. Treat **all** bundle files as private, including the environment
exports and JSON. Back up the bundle securely; never commit, attach or upload it as a build artifact.

If a signing identity already exists, reuse it rather than generating a replacement:

```bash
python3 tools/generate_benchmark_signing_env.py \
  --keystore keystore/benchmark.jks --alias imunav-benchmark
```

Reuse prompts for masked passwords. Press Enter at the private-key prompt if it uses the same
password as the store. Noninteractive reuse requires `IMUNAV_BENCHMARK_STORE_PASSWORD` and
`IMUNAV_BENCHMARK_KEY_PASSWORD` in the environment. The source keystore is unchanged, and the
generator verifies access to the private key, not just its public certificate.

An existing output directory is never overwritten. Load its environment files directly when it
is already configured; `--output-dir keystore/another-bundle` can export a reused identity elsewhere.
Outputs inside this checkout must remain under `keystore/`. A loaded certificate pin is checked
when reusing a key, and loaded signing credentials block accidental new-key generation.

Set these **repository Actions secrets** in `egorsmkv/imu-nav` from the JSON `secrets` object:

| Secret | Value |
| --- | --- |
| `BENCHMARK_KEYSTORE_BASE64` | Base64-encoded keystore |
| `BENCHMARK_STORE_PASSWORD` | Keystore password |
| `BENCHMARK_KEY_ALIAS` | Signing alias |
| `BENCHMARK_KEY_PASSWORD` | Private-key password |

Set the public **Actions variable** `BENCHMARK_CERT_SHA256` from the JSON `variables` object to
detect an accidental identity change. It is optional in CI, but the generator supplies it and the
local exports always pin it. Configure secrets yourself in repository settings or with GitHub CLI;
the generator makes no GitHub API calls.

For local signing, load the generated environment into the current shell:

```bash
. keystore/benchmark-signing/env.sh
```

PowerShell:

```powershell
. .\keystore\benchmark-signing\env.ps1
```

If PowerShell blocks the generated local script, use your permitted execution policy for the
current process. The generator does not change system execution policies.

These exports set all four `IMUNAV_BENCHMARK_*` credentials and `IMUNAV_BENCHMARK_CERT_SHA256` for
`tools/benchmark_signing.py`. Build with `-PunsignedBenchmark`, then sign the resulting APK:

```bash
./gradlew :app:assembleFdroidBenchmark -PunsignedBenchmark
python3 tools/benchmark_signing.py \
  --apk app/build/outputs/apk/fdroid/benchmark/app-fdroid-benchmark-unsigned.apk \
  --output-dir dist/benchmark \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner"
```

Use an empty output directory for each signing operation. Shell exports apply only to the current
shell and its children; close the shell that loaded the exports when finished.

F-Droid CI and the manual bundled-map workflow both build with `-PunsignedBenchmark` and then run
`tools/benchmark_signing.py`. The signer restores the private key only in an owner-only temporary
directory, uses environment passwords, verifies the APK signature, and removes the key afterward.
Neither the private key nor passwords enter Git, APKs, caches or uploaded evidence.

When all four key secrets are unavailable, including on fork pull requests, validation still
builds an **unsigned, non-installable** benchmark. Partial or invalid key settings fail instead
of selecting a different signer. The artifact name and `benchmark-signing.json` state whether
the APK is `signed` or `unsigned`. Signed reports include its public certificate and APK SHA-256.
The F-Droid **release** remains unsigned for F-Droid to sign; benchmark signing is a separate path.

## Updating a phone

For a signed benchmark APK from the configured identity:

```bash
adb install -r app-fdroid-benchmark.apk
```

This replaces the package while retaining its private data. The `-r` flag cannot bypass a
certificate mismatch. Local builds without `-PunsignedBenchmark` keep using that local machine's
debug key; they are compatible with previous builds from the same retained key, not automatically
with the CI identity.

If the installed benchmark was signed by an old runner's discarded key, the new key cannot update
it in place. Signing-key rotation also requires the old private key. Export important trip
recordings and settings before deciding to remove that benchmark installation and install the
first APK from the durable identity: removing an app deletes its private data. The project tools
never remove a navigation installation for you.

To inspect the installed certificate, use `adb shell pm path org.imunav.app.bench`, pull the
returned APK path, and compare `apksigner verify --print-certs` with the new APK's report.

| Installer result | Check |
| --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Installed and new certificate fingerprints differ |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | New version code is below the installed one; use a newer build |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | An unsigned validation/release APK was selected; use the signed benchmark artifact |

## Regression evidence

F-Droid CI runs `tools/test_benchmark_updates.py` on disposable Android API 28 and 35 emulators.
It creates tiny, ABI-independent APK fixtures, restores one synthetic private key independently
for each signing operation, and verifies that a different key is rejected. A version update and
a subsequent reinstall with the retained key must succeed and preserve a private sentinel file.
Only the fixture package `org.imunav.benchmarkupdatetest` is removed. Evidence contains public
certificate fingerprints and installer output; temporary keys are never uploaded.

These fixtures check Android's signing/update contract and the distribution signer. They do not
replace release APK assembly, native page-alignment checks, or device tests of navigation behavior.

Sources: [Android app signing](https://developer.android.com/studio/publish/app-signing),
[apksigner](https://developer.android.com/tools/apksigner), and
[GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
