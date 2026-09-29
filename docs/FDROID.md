# F-Droid publication

IMU Nav has a dedicated `fdroid` distribution flavor. It keeps the application ID
`org.blinddriver.app` and is built entirely from the source and freely licensed dependencies in
this repository and trusted Maven repositories.

## Architecture and flavor boundary

The Android application is `:app`; `:core` contains platform-independent navigation logic and
`:routing` contains GraphHopper integration shared with the desktop pack builder. The `play` and
`fdroid` flavors use the same application code today. Common dependencies are all FOSS.

The flavor boundary exists so a future Play-only integration must be added with
`playImplementation(...)`, a Play source set, or both. It must never be placed in `implementation`
without first confirming that it is F-Droid-compatible. `:app:verifyFdroidDependencies` rejects
common Google/Firebase, billing, crash-reporting, analytics, and tracking SDK groups if one enters
the F-Droid runtime graph.

The F-Droid flavor differs in two intentional ways:

* It is always unsigned. F-Droid builds and signs the APK with its own key. A local
  `keystore.properties` is used only by the `play` flavor.
* It never bundles the optional, locally generated routing/search pack. A Play build may include
  `app/src/play/assets/routing/pack.zip` and `pack.json`; both are ignored by Git. F-Droid users can
  import or download a freely licensed pack from inside the app. All other app functionality is
  unchanged.

There are no Firebase, Google Play Services, ads, analytics, crash reporting, push messaging,
billing, Play Integrity, Google Sign-In, Google Maps, or proprietary ML components in either
flavor. The app downloads user-requested map, route, and cell data, but never downloads executable
code.

## Licensing and binary audit

The application source is MIT-licensed in `LICENSE`. Data and third-party notices are recorded in
`NOTICE.md` and in the asset-specific licence files. The tracked
`bundled-cells.csv.gz` is freely redistributable data compiled from OpenCellID (CC BY-SA 4.0) and
the public-domain Mozilla Location Service export; it is not executable code.

The only tracked JAR is the standard Gradle wrapper. No APK, AAB, AAR, native `.so`, signing key,
or `google-services.json` is tracked. MapLibre's BSD-2-Clause Android artifact from Maven Central
contains its upstream `libmaplibre.so` binaries. Maven Central is an F-Droid trusted repository,
and MapLibre publishes the corresponding source. Call this out in the inclusion merge request so
the scanner result is reviewed rather than hidden with `scanignore`.

GraphHopper's desktop-only OSM reader pulls LGPL `osmosis-osm-binary` and Protocol Buffers. The app
excludes both from every Android runtime classpath. They are not present in the APK.

## Prerequisites and local build

Install JDK 17 and Android SDK platform/build-tools 36. No API token, signing key, routing pack, or
other private file is required.

From a clean checkout:

```bash
./gradlew --no-daemon clean :app:verifyFdroidDependencies \
  :app:assembleFdroidRelease :app:lintFdroidRelease
```

The unsigned APK is written under:

```text
app/build/outputs/apk/fdroid/release/
```

Run the full project checks before a release:

```bash
./gradlew spotlessApply
./gradlew check
grep -h -o "p95[^<]*" core/build/test-results/test/*.xml
```

Inspect the resolved F-Droid graph and APK before tagging:

```bash
./gradlew :app:dependencies --configuration fdroidReleaseRuntimeClasspath
apkanalyzer manifest print app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
unzip -l app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
```

The manifest must not contain analytics, advertising, billing, Firebase, Play Services, push, or
Play-only components. Expected native files are MapLibre's `libmaplibre.so` and AndroidX Compose's
`libandroidx.graphics.path.so` for `arm64-v8a` and `armeabi-v7a` only. Both come from freely
licensed artifacts in trusted Maven repositories.

## Deterministic release inputs

Gradle, Android Gradle Plugin, Kotlin, build tools, and every declared library are pinned. Build
repositories are restricted in `settings.gradle.kts`; there are no mutable `+` or `SNAPSHOT`
versions and no build script downloads binaries. The release version is a literal `versionName`
and `versionCode` so F-Droid's tag updater can read them without running Gradle.

Build from a clean tagged checkout. Do not add `local.properties`, `keystore.properties`, the
`keystore/` directory, generated APKs, or locally generated routing packs to Git. Android Gradle
Plugin records the Git revision in the APK, so building from the exact clean tag is required for a
byte-for-byte comparison. Use `diffoscope` when comparing two unsigned APKs.

## Upstream metadata

Localized listing metadata is in `fastlane/metadata/android/en-US` and `uk-UA`. Changelog filenames
are the numeric Android version code; version `0.6.0` uses `changelogs/7.txt`. Add real device
screenshots only under each locale's `images/phoneScreenshots/` directory. Do not generate mock
screenshots.

## Release and tagging

1. Update `versionName` and monotonically increase `versionCode` in `app/build.gradle.kts`.
2. Add matching `fastlane/.../changelogs/<versionCode>.txt` files in English and Ukrainian.
3. Run the local build, lint, dependency check, full test suite, and device test.
4. Commit all release sources and metadata, then create a tag matching the recipe, for example
   `v0.6.0`. The tag must point to the exact clean commit that produces the APK.
5. Push the commit and tag. Confirm the GitHub F-Droid workflow succeeds.

There is currently no `v0.6.0` tag. This is a publication blocker: create it only after these
changes have been reviewed and committed. Do not submit the template while its `commit` value
still names a nonexistent tag.

The configured GitHub repository also currently returns HTTP 404/401 to unauthenticated web and
Git clients. F-Droid requires a publicly accessible source repository, so make the repository
public before tagging or submitting. Once public, enable or identify a public issue tracker and add
its verified URL as `IssueTracker` in the metadata template.

## Preparing fdroiddata metadata

Copy `fdroid/metadata.yml` to a current `fdroiddata` checkout as
`metadata/org.blinddriver.app.yml`. Replace the template's TODO tag with the real release tag if a
different naming scheme was used. No `sudo`, `prebuild`, `scanignore`, `scandelete`, NDK, secret, or
custom binary download step should be necessary. The important build fields are:

```yaml
Builds:
  - versionName: 0.6.0
    versionCode: 7
    commit: v0.6.0
    subdir: app
    gradle:
      - fdroid
```

`subdir: app` lets fdroidserver find the Android module output; `gradle: fdroid` selects
`assembleFdroidRelease`.

## Testing with fdroidserver

Use a current `fdroidserver` and `fdroiddata` checkout or the official buildserver container. From
the `fdroiddata` root, after copying the metadata file, run:

```bash
fdroid readmeta
fdroid rewritemeta org.blinddriver.app
fdroid lint org.blinddriver.app
fdroid checkupdates --allow-dirty org.blinddriver.app
fdroid build org.blinddriver.app
```

Review the source scanner output instead of suppressing findings. In particular, explain that the
cell database is freely licensed non-executable data and MapLibre is a freely licensed dependency
from a trusted Maven repository. Verify the built manifest and compare the fdroidserver APK with a
second clean unsigned build using `diffoscope`.

The authoritative process and policy are documented by F-Droid:

* <https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/>
* <https://f-droid.org/docs/Build_Metadata_Reference/>
* <https://f-droid.org/docs/Inclusion_Policy/>
* <https://f-droid.org/docs/Reproducible_Builds/>

## Submission

1. Fork <https://gitlab.com/fdroid/fdroiddata> and create a branch named for the application ID.
2. Add `metadata/org.blinddriver.app.yml`, run all commands above, and commit it as a new app.
3. Push the branch and open a merge request to `fdroid/fdroiddata` using the `New App` label.
4. Link the public source repository and release tag, state that upstream authorizes inclusion, and
   summarize the dependency/native/data audit.
5. Respond to scanner and reviewer findings in the merge request. Do not add broad `scanignore`
   entries merely to silence them.

## Known blockers

* The source repository is not anonymously accessible. F-Droid cannot clone a private repository;
  make it public and verify both the source and issue-tracker URLs first.
* A public release tag matching `versionName` does not yet exist.
* The F-Droid build has been designed to omit the untracked 400+ MB routing pack. Reviewers should
  still confirm that the tracked 8 MB CC BY-SA cell database and MapLibre's trusted-Maven native
  libraries satisfy the current scanner and inclusion review.
* No authentic screenshots are present. Screenshots are recommended for the listing but are not a
  build blocker; add only real captures from a tested build.
