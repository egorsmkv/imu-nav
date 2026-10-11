# CI release signing

[Documentation index](../README.md)

The **F-Droid build** workflow produces a developer-signed release for direct installation on
manual runs and pushes of `v*` tags. Its artifact is named
`imu-nav-fdroid-release-signed-<commit>` and contains `app-fdroid-release.apk`, the R8 mapping,
and `release-signing.json` with public APK and certificate SHA-256 hashes.

Pull-request and merge-queue builds produce explicitly **unsigned** validation artifacts and
never receive the release credentials. Manual/tag builds fail when release credentials are
missing, incomplete, invalid or disagree with the configured certificate pin.

The Gradle task `:app:assembleFdroidRelease` remains unsigned. CI signs a separate copy; the
official F-Droid repository applies its own signature to its distribution. A developer-signed
APK cannot update an installation signed with F-Droid's key. Retain the existing developer
release identity if users already have developer-signed installations.

Android's [app signing guide](https://developer.android.com/studio/publish/app-signing)
explains certificate identity and update compatibility.

## Configure the repository once

If there is no existing developer release identity, generate a separate private bundle:

```bash
python3 tools/generate_benchmark_signing_env.py --profile release
```

The command writes `keystore/release-signing/release.jks`, `env.sh`, `env.ps1` and
`github-settings.json`. It prints no credentials, protects the bundle and refuses to overwrite
an existing output. Back up the entire bundle securely; updates must retain the same key.

To reuse an existing release key instead, run the following in an interactive terminal and
enter its passwords at the masked prompts. Use the existing key alias:

```bash
python3 tools/generate_benchmark_signing_env.py --profile release \
  --keystore /private/path/existing-release.jks --alias existing-alias
```

Passwords can also come from `IMUNAV_RELEASE_STORE_PASSWORD` and
`IMUNAV_RELEASE_KEY_PASSWORD`. Never pass passwords on the command line. Reusing validates
the private key without modifying the source keystore. For another export of the same identity,
specify a new private `--output-dir` and reuse the original keystore explicitly.

Open `github-settings.json` privately. In **Settings → Secrets and variables → Actions**, add
these **Repository secrets** from its `secrets` section:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Add `RELEASE_CERT_SHA256` as a **Repository variable** from its `variables` section to pin the
certificate. The pin is public; the four secrets are private. Runtime environment variables
use the `IMUNAV_` prefix; GitHub secret/variable names do not. Existing `BENCHMARK_*` settings
continue to sign diagnostic builds and are never used as a release fallback.

After the workflow changes are pushed, run **Actions → F-Droid build → Run workflow** on the
trusted release branch, or push a version tag. Download the artifact containing **release-signed**.
Existing artifacts remain unchanged; rerunning a run from an older commit uses its old workflow.
Only run manual distribution builds from trusted source, because they can access the release key.

## Local signing and troubleshooting

Load the generated `env.sh` in Bash (`source keystore/release-signing/env.sh`) or dot-source
`env.ps1` in PowerShell (`. ./keystore/release-signing/env.ps1`). Build an unsigned release, then:

```bash
python3 tools/release_signing.py \
  --apk app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk \
  --output-dir build/release-distribution \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner"
```

On Windows use the SDK's `apksigner.bat`. Output must be a separate empty directory. The signer
restores the key privately, verifies exactly one signing certificate, checks the pin if supplied,
and removes temporary key files. Artifacts and logs contain only public hashes and filenames.
CI also checks 16 KB ELF and APK alignment after signing.

For missing-key failures, configure all four `RELEASE_*` secrets. For verification failures,
check the keystore, alias, both passwords and certificate pin privately. An installation update
requires the same package, signing identity and an acceptable version code; an incompatible
signature requires a separate migration and may lose app data if the old app is uninstalled.
Do not replace the release identity to fix a configuration error.

IMU Nav is a research prototype, not a safety system.
