# F-Droid publication

[Documentation index](../README.md)

This guide is for maintainers preparing an F-Droid release. The F-Droid APK is built from freely licensed source and is unsigned locally; F-Droid signs the copy it distributes.

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

Do not commit signing keys, generated APKs or a locally built routing pack. The [detailed F-Droid reference](reference/FDROID.md) covers the audit, scanner results and submission steps. See the [glossary](GLOSSARY.md) for APK, flavor and tag.
