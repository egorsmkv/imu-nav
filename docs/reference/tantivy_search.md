# Tantivy routing-pack address search

New routing packs use Tantivy 0.26.1 through the shared Kotlin/JNI adapter in
`:core`. SQLite is retained only for reading older packs and staging OSM data
on the desktop. IMU Nav remains a research prototype, not a safety system.

## Runtime behavior

`PackSearch` opens `search.tantivy/` when present. A legacy `search.db` is opened
only when there is no native index and metadata does not declare one. The app
never silently downgrades a damaged native index to SQLite. Existing packs are
not rewritten or migrated on the phone; rebuilding/importing a pack selects the
new backend. Both phone and Android Auto use `OfflineRouting.search`.

One native retrieval call collects candidates across all query stages. Kotlin's
shared matcher retains exact-house priority, building-part distinction, geographic
ranking, multiword settlements, generic street results for missing houses and
stable ordering. Spaced house letters such as `4 в` and `181 а` are now parsed
before street-type stop words, so they reach the existing exact-number lookup
instead of adding street/settlement interpretations. This also works on legacy
SQLite packs; no index rebuild is needed. Request-local snapshots are discarded after each request.
Tantivy's reader has no writer and never changes the installed index. Within a
request, native retrieval reuses lazy per-segment fast-field handles across all
interpretations, stops empty intersections early, and reads sparse house postings
directly. Those handles are discarded with the request; no cross-request result
cache is introduced. These optimizations work with existing `tantivy-1` indexes.

All opening/search work stays off the main thread and under the existing resource
lock. Each request has its own cancellation flag. Android `CancellationSignal`
forwards cancellation to JNI; term/posting loops check the flag, and Kotlin checks
again before ranking/returning. Closing a pack waits for readers. Cancellation
propagates through `PlaceSearch` without starting Photon. The 250 ms debounce,
query-and-location selection ownership and offline-first policy are unchanged:
Photon is called only if offline results are empty and online services are enabled.

## Pack generation and compatibility

The existing command now builds native indexes automatically:

```sh
./gradlew :routing:run --args="--osm region.osm.pbf --out graph-region --name Region"
python3 tools/verify_routing_pack.py graph-region.zip
```

JDK 17+ and Rust 1.99+ are required. Gradle builds the desktop native library with
its `builder` feature, which exposes the JNI writer. Distributed builder scripts
include the host library; a distribution is specific to the host OS/architecture.
Android builds omit the writer entry point and package reader libraries for the
existing ARM64/ARMv7 release ABIs and x86-64 emulator ABI. Both Linux/macOS shell
and Windows PowerShell native build scripts include the reader.

The relational staging database preserves the existing OSM import/alias behavior.
Its rows are streamed through a temporary JSONL file into a new native staging
index, validated, and moved into the pack. Staging files are removed before ZIP
creation. `--no-addresses` and `--no-search` retain their previous meaning.

The archive keeps the routing files and `pack.json` at the root, plus
`search.tantivy/` with its segment files, `meta.json`, and `search-format` marker.
Metadata adds `searchFormat: "tantivy-1"`; absent/null still supports legacy packs
or packs without search. The marker is `imu-nav-tantivy-1` followed by a newline.
Schema/format validation runs before the working pack is replaced. The importer
preserves this named subtree even in wrapped legacy ZIPs and rejects traversal.
Unknown declared formats and missing declared native indexes fail validation.
Older app versions can still read the graph but must be upgraded to search new
native-only packs. Keep SQLite packs for clients that cannot upgrade.

## Performance and validation

The [historical comparison](tantivy_search_experiment.md) found large gains for
broad matches on synthetic large indexes, but a slower short prefix on Monaco.
The switch accepts that measured small-pack tradeoff; it is not a claim that
Tantivy wins every query. The subsequent [Ukraine host benchmark](../benchmarks/ukraine-search-2026-10-24.md)
measures 1.11 million indexed addresses: broad-prefix gains, specific-address
regressions, and a 50% larger compressed search index. The
[expanded 216-case run](../benchmarks/ukraine-search-extended-2026-10-24.md) confirms
the address-query tradeoff and records shared distant-town/house-suffix matching gaps.
The subsequent [runtime optimization comparison](../benchmarks/ukraine-search-optimized-2026-10-24.md)
measures roughly 2.4× faster typical address cases and 2.6× faster multiword
settlement cases, while preserving result parity and index compatibility. The
[spaced-house-suffix fix](../benchmarks/ukraine-search-house-suffix-2026-10-24.md)
then restores exact house hits and cuts the formerly slowest Lviv query from
3.63 ms to 0.60 ms p50 on the same host. The distant-town candidate-limit gap
remains separate. Physical ARM latency
measurements remain unavailable. ARMv7, ARM64 and x86-64 cross-compilation is checked.

`./gradlew check` includes native tests, Kotlin tests, formatting, static analysis
and Android Lint. The miniature OSM integration test now builds and searches a
native pack, asserts that SQLite staging is absent, and round-trips the ZIP.
The pack validator and routing workflow recognize both formats; the workflow
installs the pinned Rust toolchain and smoke-tests the native reader.

The opt-in R8 device harness uses the production native library in the app APK.
Its tests compare results, exercise native preference and legacy compatibility,
reject missing/corrupt indexes, and verify cancellation. If `native-pack.zip` is
provided as a public fixture, it also imports through the real `OfflineRouting`
staging/validation/commit path in isolated storage, searches a house, and verifies
that a malformed replacement leaves the working pack usable. Existing app packs,
settings and trip data are untouched by the harness.

Validation on 2026-10-24: `spotlessApply`, full `./gradlew check`, the Play release
APK, the desktop distribution, writer-enabled Rust Clippy, and 18 focused Python
validator/CI-selection tests passed. The final R8 x86-64 emulator run passed all
three instrumentation tests above using public Monaco and synthetic fixtures.
Release APK inspection confirmed ARM64/ARMv7 reader libraries and no JNI writer
entry point. Replay remained `p95=20 max=25 rms=10 m`. Windows scripts, a physical
ARM phone and an Android Auto head unit were not exercised. The later Ukraine
search-only host measurements are linked above. The release APK was unsigned on this host; the emulator used the
debug-signed R8 benchmark build.

Android search builds explicitly set both linker `max-page-size` and `common-page-size`
to 16384, matching the estimator JNI library. This aligns LOAD segments and the end
of GNU_RELRO for 16 KB Android pages. Release and benchmark APKs must pass
`tools/verify_android_pages.py`; ZIP alignment alone does not check the ELF layout.
