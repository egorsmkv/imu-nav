# Tantivy address-search experiment (historical benchmark)

**Current status:** Tantivy is now the default for newly built packs. This report
records the earlier evaluation, including its initial recommendation. See
[Tantivy search](tantivy_search.md) for the production integration and compatibility
policy. The measurements below have not been relabeled as production measurements.

At the time of this opt-in evaluation, SQLite remained the phone
and Android Auto backend. Existing routing packs, offline-first policy, the
250 ms debounce, Photon cancellation and selection ownership are unchanged.
IMU Nav remains a research prototype, not a safety system.

## What is being compared

The [RustedBytes/tantivy-jni](https://github.com/RustedBytes/tantivy-jni) project
motivated this experiment. Its general search API uses relevance/top-document
collection and a JSON JNI interface. That is not an equivalent replacement for
IMU Nav's geographic candidate limits and exact house-number rules. We use the
same Tantivy 0.26.1 engine through a separate, small experimental JNI library;
these measurements do **not** measure that upstream wrapper.

`native/nav-search` indexes the normalized SQLite FTS text, including aliases.
It intersects prefix postings, selects nearest candidates using the same
wrapped-longitude heuristic and limits as SQLite, and retrieves exact and
building-part addresses. No geographic cutoff excludes a distant explicit town.
The shared `AddressSearch.prepare` supplies settlement interpretations; the
production Kotlin matcher still performs final geodesic ranking, exact-number
priority, two-addresses-per-street selection and deduplication.

A complete request uses one JNI retrieval call. Lifecycle and cancellation use
separate handle calls. The response is a request-local snapshot; it is not a
cross-request cache. JSON encoding, JNI, JSON decoding and Kotlin ranking are
included in the timings. Exact floating-point JSON parsing is enabled to retain
SQLite coordinates bit-for-bit. Binary transport is a possible future experiment,
not a speedup assumed by this one.

The native reader never opens an index writer. The builder writes only a new
output directory and refuses to overwrite one. A format marker and schema check
rejected incompatible experiment indexes. Those original indexes were not routing-pack
assets and could not be installed through the app; regenerate fixtures for the production format. JNI registries hold their mutex
only to obtain/remove Arc handles; independent requests can search concurrently.
Closing the Kotlin wrapper waits for its resource readers. Cancellation flags are
request-local and checked during term enumeration, posting scans, intersections,
candidate collection and address retrieval. They do not interrupt an individual
Tantivy store-block decompression or filesystem page fault. Total counts are
optional and default off; no separate count query is run.

During this evaluation the prototype was excluded from production APKs. The current
production adapter is in `:core`, and `scripts/build-rust-android.sh` builds the
reader. The reproduction commands below use this integrated adapter.

## Reproduce on a host

Use the repository's JDK/SDK/Rust requirements. Set `JAVA_HOME`, `ANDROID_HOME`
and, when needed, `RUSTUP_TOOLCHAIN=1.99.0`. Allow a few GB for Cargo output.

```sh
python3 tools/benchmark_search.py \
  --out /tmp/search-experiment \
  --target /tmp/search-experiment-target \
  --pack /path/to/public/monaco/search.db
```

Omit `--pack` for synthetic-only tests. `--sizes 10000 100000` is the default;
`--prepare-only` builds fixtures without running Gradle. The output directory
must not exist. The source pack opens read-only and is copied with SQLite backup.
No installed database is rewritten. Use only synthetic or public packs, never
personal search history, locations or trip recordings.

To rerun against existing fixtures:

```sh
CARGO_TARGET_DIR=/tmp/search-experiment-target \
TANTIVY_DATA_DIR=/tmp/search-experiment \
./gradlew :routing:test --tests '*TantivyPrototypeTest*' --rerun-tasks
```

Results are in `routing/build/test-results/test/TEST-org.imunav.routing.TantivyPrototypeTest.xml`.
Each case warms both engines 30 times, then alternates their order for 100 paired
samples. p50/p95 use sorted samples 49/94 (zero-based). Opening measurements
include the first query, are ordered SQLite then Tantivy and do **not** evict OS
page caches; they must not be described as OS-cold measurements. The host lifecycle
test may initialize JNI before the opening measurement. Debounce and
network time are excluded. Default `./gradlew check` skips the opt-in host
benchmark but runs the native correctness tests.

Host synthetic case numbers: `ma`, `го`, `Main 22`, `Main 22 к1`, `Main 220`,
`New Town Main 22`, `Main New Town 22`, `Main 99`, `Far Away Town Main 22`,
`D'Artagnan`, `absent`. Regional cases: `av`, `ru`, `Monaco`, `Rue Bel Respiro 1`,
`Rue Bel Respiro 9999`, `La Condamine Rue Imberty 6`, `го`, `absent`.
When a public pack is supplied, `regional.db` preserves its original FTS schema
and `regional-prefix.db` rebuilds FTS4 prefixes in a disposable copy.
Parity runs each query with the normal public reference, no reference, and a
synthetic reference near the dateline. Synthetic fixtures include aliases,
exact house results spanning more than five streets, building parts, a distant
three-word town and streets on both sides of the dateline.

## Reproduce on Android

The harness supports ARM64 and x86-64 (Linux host, NDK API 26). Gradle builds
and packages the native library:

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export CARGO_TARGET_DIR=/tmp/search-experiment-target
# Gradle now builds and packages the production native library automatically.
mkdir -p /tmp/search-device/assets
cp /tmp/search-experiment/*.db /tmp/search-device/assets/
cp -R /tmp/search-experiment/*.tantivy /tmp/search-device/assets/
./gradlew -PsearchPrototypeDir=/tmp/search-device \
  :app:assemblePlayBenchmark :app:assemblePlayBenchmarkAndroidTest
```

For a native x86-64 emulator comparison, add `-PsearchPrototypeAbi=x86_64` to
Gradle. The ABI override applies only to the experimental benchmark variant.
Running ARM64 JNI through an emulator's translation layer would distort the
comparison with platform SQLite; emulator results still do not represent a
physical ARM phone.

Install the benchmark and test APKs using the SDK's adb, then run only
`org.imunav.app.search.TantivyDeviceTest` with AndroidJUnitRunner. The property
switches the instrumentation target to the R8-enabled benchmark variant; normal
builds keep their existing instrumentation configuration. Experiment-only keep
rules preserve shared test entry points, Kotlin runtime APIs and AndroidX tracing
while allowing method-body optimization. They are not used by production releases. Do not use the debug
app to evaluate offline routing. Test assets are copied to a unique test cache
directory and deleted afterwards; installed packs and app data are untouched.

Android uses the real `AndroidSearchDb`, 15 warmups and 50 paired samples, with
p50/p95 at indices 24/47. Read aggregate results using `adb logcat -d -s
SearchExperiment:I`. Logs contain dataset/case identifiers and timing only.
Android synthetic cases omit host cases 4, 6, 7 and 9; regional cases omit host
cases 1, 6 and 7. The test also cancels a busy request from another thread and
verifies the reader remains usable afterwards.

## Results and decision

Measured on 2026-10-24 on an Intel Core i9-9900K host, JDK 21, Rust 1.99.0,
Tantivy 0.26.1 and an Android 16/API 36 x86-64 emulator. Compilation was idle
during timing. Both Android runs passed exact result parity and cancellation.
These are complete offline engine calls, including JNI and serialization, but
exclude dispatcher scheduling, UI work, debounce and networking.

Two Android runs (ranges show the two observed p50 values, in milliseconds):

| Dataset / query | SQLite p50 | Tantivy p50 | Observation |
| --- | ---: | ---: | --- |
| 100k synthetic, `ma` | 80.47–81.36 | 3.65–3.66 | About 22× faster |
| 100k synthetic, `Main 22` | 80.55–80.57 | 6.15–6.23 | About 13× faster |
| 100k synthetic, `New Town Main 22` | 159.69–161.48 | 12.02–12.13 | About 13× faster |
| Monaco with FTS prefixes, `av` | 0.479–0.482 | 0.783–0.791 | Repeatable regression |

For the 100k prefix case, SQLite p95 was 82.19–82.46 ms and Tantivy p95 was
3.76–3.84 ms. For Monaco's short prefix, SQLite p95 was 0.524–0.535 ms versus
0.955–1.033 ms. Do not generalize the large synthetic improvement to every query
or pack. Small-pack serialization/ranking overhead and warmup effects matter.

The first Android open-and-query measured 2.47 ms SQLite / 22.94 ms Tantivy in
run 1, including initial JNI library/class loading. Subsequent Monaco opens were
0.91 / 0.96 ms. These are not OS-cold disk tests and do not establish a cold-start
speedup. All open, p50/p95 and lookup counts are retained in the
[Android run 1](../benchmarks/tantivy-android-2026-10-24-run1.txt) and
[Android run 2](../benchmarks/tantivy-android-2026-10-24-run2.txt) aggregate logs.
The logs contain only public/synthetic dataset and query-case identifiers.

SQLite uses two logical lookups for a plain prefix, three for `Main 22`, nine
for `New Town Main 22`, and twelve for `Far Away Town Main 22` in these fixtures.
House queries use one batched SQLite call. Tantivy uses one retrieval JNI call,
plus begin/finish lifecycle calls. That JNI count is **not** a count of internal
term/posting lookups; native retrieval still performs several index operations.

Search files (bytes; native directory includes metadata, not the JNI library):

| Dataset | SQLite | Tantivy |
| --- | ---: | ---: |
| Monaco original | 143,360 | 61,122 |
| Monaco with FTS prefixes | 163,840 | 61,122 |
| 10k synthetic with FTS prefixes | 3,956,736 | 1,790,513 |
| 100k synthetic with FTS prefixes | 39,854,080 | 17,996,321 |

Synthetic SQLite fixtures use the builder's `(street_id, number)` address index.
The standalone ARM64 library is 2,425,472 bytes; x86-64 is 2,817,808 bytes.
These are uncompressed libraries, not APK size deltas. The benchmark keeps both
index formats to compare them; no extra index or library enters installed packs
or production APKs.

**Initial evaluation recommendation:** retain SQLite as the default. Tantivy is promising for broad matches
on large indexes, but this prototype does not meet a universal no-regression
criterion. A large real regional pack, physical ARM device timings, 32-bit ARM
support and a pack migration/integration design remain necessary before choosing
a production backend. Monaco is a real but small public pack (313 streets,
431 addresses); synthetic 100k results are not a substitute for country-scale
address distributions. The ARM64 library cross-build succeeded, but physical ARM
performance was not measured.

### Validation

The final host run passed both opt-in tests, including exact result parity with
and without a reference position, dateline cases, more than five exact-house
streets, distant settlements, cancellation isolation, close waiting for a reader,
and reopening. Its paired warm measurements were:

| Dataset / query | SQLite p50 / p95 ms | Tantivy p50 / p95 ms |
| --- | ---: | ---: |
| 100k synthetic, `ma` | 95.20 / 97.21 | 3.36 / 3.57 |
| 100k synthetic, `Main 22` | 95.84 / 97.68 | 5.21 / 5.44 |
| 100k synthetic, `New Town Main 22` | 192.48 / 196.27 | 11.58 / 12.26 |
| 100k synthetic, rare `D'Artagnan` | 0.207 / 0.223 | 0.196 / 0.221 |
| 100k synthetic, absent | 0.147 / 0.156 | 0.021 / 0.027 |
| Monaco with FTS prefixes, `av` | 0.527 / 0.652 | 0.336 / 0.428 |

[Full host results](../benchmarks/tantivy-host-2026-10-24.txt) include every case,
opening time, logical lookup count and size. The host and Android disagree on
the small Monaco prefix; host JDBC results alone are not an Android rollout gate.

- `./gradlew spotlessApply check` passed: formatting, native tests/Clippy, Kotlin
  tests, detekt and Android Lint. Core JVM tests: 361; app unit tests: 130 per
  distribution, all passing. Four native search tests cover posting intersections,
  geographic limits, exact/building boundaries, optional counts, read-only opens,
  format rejection and cancellation during broad posting work.
- Replay accuracy stayed `p95=20 max=25 rms=10 m`.
- `:app:assemblePlayRelease` produced the unsigned production release APK.
  Archive inspection confirmed it contains no Tantivy native library.
- R8-enabled benchmark and instrumentation APKs built; the isolated emulator suite
  passed twice, including the real Android SQLite adapter and cross-thread
  cancellation. Only the instrumentation APK contains `libimu_nav_search.so`.
- The full suite initially exposed a diagnostics test synchronization race: server
  receipt of `/finish` was mistaken for client upload completion. The test now
  waits for `UPLOADED` before checking disable behavior. Diagnostics runtime code
  is unchanged.

Physical ARM device timing and a large real regional pack were unavailable. No
Tantivy UI rollout, installed-pack conversion, Android Auto native-backend test,
or production backend switch is claimed by this experiment.
