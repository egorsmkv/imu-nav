# F-Droid publication

[Documentation index](../README.md)

This guide is for maintainers preparing an F-Droid release. The F-Droid APK is built from freely licensed source and is unsigned locally; F-Droid signs the copy it distributes.

Manual and version-tag CI runs sign a separate release copy for direct downloads using a durable
developer release identity. See [CI release signing](release_signing.md) for `RELEASE_*` setup.
Pull-request validation releases remain unsigned; official F-Droid signatures are independent.

After these workflow changes are included in a tag, publishing a GitHub Release for a `v*` tag
automatically builds that tag and attaches `app-fdroid-release.apk` to its **Assets**. Published
prereleases are included. A signed `app-fdroid-benchmark.apk` is also attached when the independent
benchmark signing key is configured; unsigned benchmark builds stay in Actions artifacts.
`fdroid-SHA256SUMS` contains checksums for the attached APKs. Upload waits for the build, lint,
alignment and update-compatibility checks to pass. Creating a draft or pushing a tag alone
keeps the existing Actions artifacts; the release upload starts when the Release is published.

The build uses the protected `android-distribution` environment and its existing approval rules.
Configure the `RELEASE_*` signing settings there as described in [CI release signing](release_signing.md).
The separate upload job has release-write permission and receives no signing keys. Reruns preserve
existing release assets: duplicate filenames fail instead of replacing an already published APK.

The CI **benchmark** APK is a separate diagnostics build. Its private signing identity and update
compatibility are described in [benchmark signing](benchmark_signing.md). Download its **signed**
artifact for installation; an **unsigned** validation artifact cannot be installed directly.

## Prepare a release

1. Make sure the source repository and issue tracker are publicly reachable. Use a real release tag that matches the app version; do not submit metadata with a placeholder tag.
2. Update `versionName` and `versionCode` in `app/build.gradle.kts`. Add matching English and Ukrainian changelogs under `fastlane/metadata/android/`.
3. From a clean checkout, run:

   ```bash
   ./gradlew --no-daemon clean :app:verifyFdroidDependencies \
     :app:assembleFdroidRelease :app:lintFdroidRelease
   ./gradlew check
   ```

4. Inspect the unsigned APK under `app/build/outputs/apk/fdroid/release/`. Check dependencies and the manifest for Play-only or tracking components.
5. Copy `fdroid/metadata.yml` into `fdroiddata` as `metadata/org.imunav.app.yml`, update the tag, and run `fdroid lint org.imunav.app` and `fdroid build org.imunav.app`.
6. Submit the metadata as a new-app merge request only after the source, tag and build can be reviewed. Use authentic screenshots from a tested app if you add screenshots.

Do not commit signing keys, generated APKs or a locally built routing pack. The [detailed F-Droid reference](reference/fdroid.md) covers the audit, scanner results and submission steps. See the [glossary](glossary.md) for APK, flavor and tag.
