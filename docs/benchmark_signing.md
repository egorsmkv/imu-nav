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

Use a dedicated private benchmark keystore and retain a backup. Reuse an existing benchmark
signing identity if its private key is available. Otherwise, create one locally with JDK keytool:

```bash
mkdir -p keystore
keytool -genkeypair -storetype JKS -keystore keystore/benchmark.jks \
  -alias imunav-benchmark -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=IMU Nav Benchmark"
```

Keytool prompts for the passwords. Keep the keystore and its base64 copy inside the ignored
`keystore/` directory; never commit or attach either file. Encode the file without printing it:

```bash
python3 -c 'import base64, pathlib; pathlib.Path("keystore/benchmark.base64").write_bytes(base64.b64encode(pathlib.Path("keystore/benchmark.jks").read_bytes()))'
```

Set these **repository Actions secrets** in `egorsmkv/imu-nav`:

| Secret | Value |
| --- | --- |
| `BENCHMARK_KEYSTORE_BASE64` | Contents of the base64 file |
| `BENCHMARK_STORE_PASSWORD` | Keystore password |
| `BENCHMARK_KEY_ALIAS` | `imunav-benchmark`, or the alias of the reused key |
| `BENCHMARK_KEY_PASSWORD` | Private-key password |

GitHub CLI can read the file directly and prompt for the other values:

```bash
gh secret set BENCHMARK_KEYSTORE_BASE64 --repo egorsmkv/imu-nav < keystore/benchmark.base64
gh secret set BENCHMARK_STORE_PASSWORD --repo egorsmkv/imu-nav
gh secret set BENCHMARK_KEY_ALIAS --repo egorsmkv/imu-nav
gh secret set BENCHMARK_KEY_PASSWORD --repo egorsmkv/imu-nav
```

To detect an accidental identity change, also pin the public certificate in the repository
**Actions variable** `BENCHMARK_CERT_SHA256`. Export only the public certificate:

```bash
keytool -exportcert -keystore keystore/benchmark.jks -alias imunav-benchmark \
  -file keystore/benchmark-cert.der
python3 -c 'import hashlib, pathlib; print(hashlib.sha256(pathlib.Path("keystore/benchmark-cert.der").read_bytes()).hexdigest())'
gh variable set BENCHMARK_CERT_SHA256 --repo egorsmkv/imu-nav
```

Use the printed fingerprint as the variable's value. The variable is optional, but if present it
must match the signer. It contains no private key.

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
