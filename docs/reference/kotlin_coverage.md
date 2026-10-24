# Kotlin host coverage

> Detailed reference. For easy steps, see the [guide](../kotlin_coverage.md) or the [glossary](../glossary.md).

The Kotlin coverage job measures production source lines in `:core`, `:routing`,
`:replay` and `:app`. It runs host JVM tests, including the real JNI library,
and F-Droid debug app unit tests. This is not emulator or device coverage.

**The app gate counts non-UI code.** Code that draws is verified on devices and is
listed separately in the report (and `summary.json` as `ui_covered`/`ui_lines`):
the Compose screens under `app/.../ui/`, the Android Auto screens
(`CarNavigationScreen`, `CarSearchScreen`), the car map renderer and the MapLibre
layers (`CarMapRenderer`, `NavigationLayers`, `BuildingVisibility`). The list is
`UI_PREFIXES` in `tools/kotlin_coverage.py`. Services, sensors, storage, sync and
the app graph stay in the gate even when tests do not reach them.

**Goal: 90 % for every gate** (`TARGET_PERCENT`). The committed baselines are
ratchets towards it: raise them whenever tests add coverage, never lower them.

App unit tests run under **Robolectric** (`robolectric.properties` replaces the real
`ImuNavApp` with a plain `Application`, so each test builds what it checks). They use
real SQLite and SharedPreferences, the host JNI library (the same build the replay
tests use), `MockWebServer` for HTTP, a tiny routing pack built with the real pack
builder (`TestPacks`), and an in-memory stand-in for the Android Keystore
(`FakeAndroidKeyStore`). A few assertions about replacing files by renaming are
skipped on Windows hosts, where `File.renameTo` does not replace an existing file;
Android and CI run them.

## Run and review

Use Linux, Python 3.10+, JDK 21+, Android SDK platform 36/build-tools 36.0.0, and a
Rust toolchain supported by `native/Cargo.toml`. CI uses `nightly-2026-09-25`, also
used by Rust coverage. Set `JAVA_HOME`, `ANDROID_HOME` and `RUSTUP_TOOLCHAIN` as
needed. Android NDK 28.2.13676358 and CMake 3.22.1 are required for Gradle project
configuration; no signing key, emulator or map pack is needed for this job.

```bash
python3 -m unittest discover -s tools/tests -v
python3 tools/kotlin_coverage.py
```

Open `build/kotlin-coverage/index.html`. The report includes module gates and
combined source pages. Green means a line executed; red means it did not.
`summary.json` contains exact counts; `coverage.lcov` exports the deduplicated
production lines. Detailed JaCoCo HTML and XML reports, class inventories and
Gradle diagnostics are retained under `runs/<run-id>/`. Use `--output PATH` to
choose a different report directory.

If the runner reports a Gradle failure, inspect that run's `gradle.log` first.
CI also uploads the host test XML results, including assertion messages and
stack traces, alongside coverage diagnostics when collection fails.

The desktop NMEA fixture is a single text line; its checkout line ending may be
LF or CRLF. `NavigationMirrorTest` reads that line independently of the file
terminator and requires CRLF in both standalone NMEA output and the mirrored
state's embedded NMEA value.

JaCoCo 0.8.14 is pinned in the version catalog. Instrumentation is opt-in through
`-PkotlinCoverage`; ordinary tests, release APKs and performance measurements do
not use the agent. The runner invokes `kotlinCoverageReport` with this property
and a unique output directory. Test execution is forced, build-cache restoration
of test results is disabled for coverage, and each run checks all four execution
files, class inventories and reports. Compiled classes may safely remain cached.

## Counting and source ownership

- Classes come from Gradle source-set outputs and the Android variant artifact
  API, before dexing, shrinking or APK transforms. Never-executed production
  classes remain in the reports.
- Test classes, dependencies, Android `R` and `BuildConfig` are excluded.
  JaCoCo's Kotlin filters remove compiler-generated instructions. Empty entries
  for filtered inline dependency bodies, such as `Comparisons.kt`, are ignored;
  unresolved executable source entries fail validation.
- Core and routing reports consume compatible execution data from all four test
  suites, crediting callers' tests to the module owning the exercised code.
- Replay compiles several JNI wrappers from the app sources. Its report uses the
  replay bytecode, and the app report uses the original Android bytecode. Their
  executable/covered source lines are unioned after reporting, and wrappers are
  counted once under `:app`. Differing class definitions are never combined into
  one JaCoCo class bundle.
- Every production Kotlin source must be represented in the reports. Empty,
  missing or unresolved output and class-mapping warnings fail the runner rather
  than silently improving the percentage.
- A line is covered if any instruction on that line executed. Branch coverage
  remains available in the original JaCoCo reports and is not gated or summed
  across different compilation domains.

`GRAPH_DIR` is removed from the coverage subprocess environment, so the optional
real-pack smoke tests skip consistently. Portable routing tests still exercise
locally built fixtures. Only the F-Droid debug app variant is measured because
both flavors currently share application code. No emulator tests are included.

## Baseline gates

`config/kotlin-coverage-baseline.json` stores the measured covered/total line
counts for each module. Verification compares ratios using integer arithmetic:

```text
current_covered × baseline_total >= baseline_covered × current_total
```

Displayed rounding never changes a pass/fail decision. A module cannot borrow
coverage from another module. Baselines do not update automatically.

To deliberately refresh the floors after reviewing a measurement change or a
coverage improvement, run:

```bash
python3 tools/kotlin_coverage.py --update-baseline
```

This measures the complete suite before replacing the baseline file. Review its
version-controlled diff; the command can lower as well as raise the floors.
JaCoCo version changes require an explicit baseline refresh. Keep compiler,
variant, exclusions and test selection consistent when comparing measurements.

The `Kotlin coverage` workflow verifies the committed floors on pull requests and
pushes to `main`, and uploads reports even when a gate fails. Before coverage, the
workflow runs `:core:serverApiTest` against a temporary local Rust server; that
integration test does not contribute to the coverage totals. Coverage is separate from
`./gradlew check`; normal tests, formatting, detekt and Android lint remain
required independently.
