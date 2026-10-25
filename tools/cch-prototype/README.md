# Isolated Rust CCH routing-index prototype

This experiment compares `cch` **0.3.0**, pinned in its own Cargo.lock, with GraphHopper 11's
production CH algorithm. It is not part of the native workspace, Gradle build, APK or routing pack
format. No JNI or Android runtime integration is provided.

## What is compared

`../CchGraphExport.java` opens an existing production-compatible base graph using the app's
`PhoneGraphHopper` weighting. Each accessible directed road edge becomes a state. A transition
exists only when its turn is legal; its cost is the next road edge's production weight plus the
turn penalty. Query start costs include the first road edge. This edge-state representation retains
one-way access, turn restrictions, U-turn costs and left/right penalties, including walking access.

CCH accepts integer costs. Each transition is rounded to the nearest **0.001 weight unit**;
these weights include distance influence and are not simply travel seconds. `compare.py` checks
reachability, coverage and the accumulated rounding bound against GraphHopper's floating-point
optimum. Very close alternatives can change after rounding. This is not bit-for-bit route parity.
Every returned CCH path is also checked against the original legal transitions and integer cost,
outside the latency timer.

The prototype reuses Java's OSM import and routing models; it does not implement an independent Rust
OSM importer. Walking currently also uses edge states for adapter simplicity, even though a
node-based walking graph would be smaller. Consequently these results characterize this prototype,
not the best possible CCH implementation.

Queries use the same snapped **tower-node endpoints** on both engines. Timings include shortest-path
search and original-edge/state path reconstruction, but exclude endpoint snapping, geometry
expansion and spoken instructions. GraphHopper creates a fresh algorithm per request, as in normal
routing. CCH reuses `PathQuery` scratch but creates a fresh `ElimTreeQuery` for each request because
0.3.0 disallows repinning targets. The allocation is included in the measurement. Index opening and
query-independent endpoint lists/buffers are outside query timings.

## Build and run

Use the repository's JDK/SDK/Rust setup. Generate the desktop classpath first:

```bash
./gradlew :routing:installDist
mkdir -p build/cch-classes
javac -cp 'routing/build/install/routing/lib/*' -d build/cch-classes tools/CchGraphExport.java
cargo build --locked --release --manifest-path tools/cch-prototype/Cargo.toml
```

Use a **disposable copy** for CH preparation: `prepare` writes CH data and graph properties.
Other exporter modes open graphs read-only. Existing pack import/validation is deliberately not
changed. These tools consume local trusted experiment exports, not user-supplied archives.

```bash
cp -a /path/to/base-graph /path/to/disposable-ch-graph
java -Xmx8g -cp 'build/cch-classes:routing/build/install/routing/lib/*' \
  CchGraphExport /path/to/disposable-ch-graph car prepare ch-prepare.csv kyiv
java -Xmx4g -cp 'build/cch-classes:routing/build/install/routing/lib/*' \
  CchGraphExport /path/to/base-graph car export /path/to/new-export kyiv
RAYON_NUM_THREADS=4 tools/cch-prototype/target/release/imu-nav-cch-prototype \
  build /path/to/new-export /path/to/new-export/index
java -Xmx4g -cp 'build/cch-classes:routing/build/install/routing/lib/*' \
  CchGraphExport /path/to/disposable-ch-graph car ch graphhopper.csv kyiv
RAYON_NUM_THREADS=4 tools/cch-prototype/target/release/imu-nav-cch-prototype \
  measure /path/to/new-export cch.csv
python3 tools/cch-prototype/compare.py graphhopper.csv cch.csv --output comparison.json
```

Replace `car` with `foot` for walking. Export and index directories must not already exist.
Set `CARGO_TARGET_DIR` if build products need a different disk; adjust the executable path accordingly.
`ukraine` selects eight public city-centre journey pairs in both directions. Other region labels
select 32 pairs using seed 20261024 and the production snapping filter. Both engines run one warmup
plus five measured iterations; summaries use medians per pair before calculating cross-pair p50/p95.
The full exporter model includes the production subnetwork filter when choosing endpoints.

A subset of the same Ukraine source can be extracted with osmium-tool without changing app imports:

```bash
osmium extract -b 30.25,50.25,30.8,50.65 -s smart -S types=restriction \
  ukraine-latest.osm.pbf -o kyiv.osm.pbf
```

The smart extraction strategy completes restriction relations. Build its base graph using the
existing `RegionalSearchBenchmark build` tool documented in the Ukraine search report, then copy
that graph for CH preparation as above. PBF import and search-index work are shared prerequisites,
not CCH preparation. Include export time in any end-to-end replacement decision.

## Checks and known gaps

```bash
cargo test --locked --manifest-path tools/cch-prototype/Cargo.toml
cargo clippy --locked --manifest-path tools/cch-prototype/Cargo.toml --all-targets -- -D warnings
cargo fmt --manifest-path tools/cch-prototype/Cargo.toml --check
python3 -m unittest discover -s tools/cch-prototype -v
```

Tests cover directed one-way/unreachable paths and forbidden transitions, and verify that the
comparison rejects missing results, reachability differences, unbounded cost differences and NaNs.
The GraphHopper synthetic no-left-turn fixture is also exported and compared with CH; each
reconstructed Rust path is validated. Broader OSM via-way/conditional restrictions, ferry behavior,
Android performance/memory, geometry, instructions and cancellation still require dedicated work.
Production use would also need hardened index import and JNI lifecycle support.

The dependency is MIT licensed; its notice acknowledges RoutingKit's BSD-2-Clause algorithms.
Rayon/Crossbeam, memmap2 and libc are transitive dependencies, not new APK dependencies.

[Measured comparison and limitations](../../docs/benchmarks/cch-prototype-2026-10-24.md).

## Parallel CH with faster_paths

The same standalone workspace also pins `faster_paths` 0.1.0 (MIT OR Apache-2.0)
and bincode 1.3.3 (MIT). This is ordinary contraction hierarchies, not customizable CH.
The adapter uses the identical turn-state export and integer rounding as the CCH trial.
It reuses the library's query scratch, checks all candidate source/target states, and reconstructs
the winning path. Every returned path is validated against the input graph outside the timer.
Hub labels are not enabled. Serialized indexes are trusted local experimental data only.

```bash
RAYON_NUM_THREADS=4 tools/cch-prototype/target/release/faster \
  build /path/to/new-export /path/to/new-export/faster-index
RAYON_NUM_THREADS=4 tools/cch-prototype/target/release/faster \
  measure /path/to/new-export faster.csv
python3 tools/cch-prototype/compare.py graphhopper.csv faster.csv \
  --candidate-name faster_paths --output faster-comparison.json
```

`faster-index` must not exist before building. Unlike the memory-mapped CCH format, bincode
loads the complete hierarchy into memory before query measurement. Source/target snapping,
index loading, graph export and OSM import are excluded from query latency. This is not an
Android implementation or a replacement pack format.

[Measured faster_paths comparison](../../docs/benchmarks/faster-paths-2026-10-24.md).
