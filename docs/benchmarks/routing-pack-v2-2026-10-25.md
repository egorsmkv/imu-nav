# Compact routing pack validation — 2026-10-25

The `rust-ch-2` storage migration shares canonical E7 geometry and names, shrinks state
records, selects lossless integer widths, and groups spatial postings. It does not change
OSM access policy, hierarchy costs, snapping policy or Tantivy. See the
[format specification](../reference/routing-pack-format.md).

## Method

Baseline revision: `10a0e0e` (the final v1 importer, including its last access-policy correction).
The public Ukraine source is 881,771,515 bytes, SHA-256
`e8b407fa9e6f3c03d3bd7d4dd58fd949fd7b925cf3d5590bcd690bfb950c94e5`.
Both full builds use two workers, sequential profiles, default network pruning, addresses,
Tantivy search and no elevation. CPU-heavy builds and route measurements run sequentially.
OS caches are not flushed; startup observations are not cold-cache guarantees.

A frozen baseline builder and runtime are retained under `build/routing-v2-benchmark/baseline-bin`.
`benchmark-routing PACK QUERIES OUTPUT` runs each public-coordinate query once and then 30 warm
iterations, recording geometry/guidance and 64 deterministic fixed-vertex paths per profile.
`tools/verify_routing_equivalence.py` compares errors, fixed-vertex costs and road sequences,
route geometry (3 cm endpoint-projection tolerance), guidance, speeds and elevation.
The E7 coordinate rounding itself stays below 1 cm. Timings include snapping, CH traversal,
unpacking and route construction, but exclude JNI/JSON, UI, debounce and networking.

The [public queries](routing-pack-v2-2026-10-25/queries.json) include Kyiv–Lviv, Kyiv–Odesa,
Kyiv–Kharkiv, Lviv–Odesa, short Kyiv routes, the known disconnected city-centre endpoint and
an unreachable location. Existing failures are compared rather than silently discarded.
The country baseline here is newly built; the older preliminary v1 pack from the migration
report is not used for conformance comparisons.

## Baseline

The fresh v1 pack is 2,211,249,860 bytes zipped and 5,881,579,765 bytes unpacked. Its full
build took 897.33 seconds, with peak RSS 11,147,792 KiB.
[Build output](routing-pack-v2-2026-10-25/baseline-build.log) and
[process statistics](routing-pack-v2-2026-10-25/baseline-build.time) retain the evidence.

## Combined Ukraine result

| Measurement | v1 | v2 |
| --- | ---: | ---: |
| ZIP bytes (Deflate 6) | 2,211,249,860 | 1,337,433,705 |
| Unpacked bytes | 5,881,579,765 | 2,528,839,872 |
| Full build seconds | 897.33 | 776.35 |
| Builder peak RSS (KiB) | 11,147,792 | 11,528,276 |

The ZIP shrank 39.5% and installed data 57.0%. Build-time improvement is observational:
OS caches were uncontrolled and import durations differed. Compaction took 95.706 seconds;
ZIP creation took 46.516 seconds. Both builds retained 9,201,872 car states and 11,150,416 foot
states. The shared file contains 5,660,805 geometries and 28,532,899 points.

Three sequential benchmark comparisons passed exact fixed-vertex paths/costs, guidance,
speeds and error outcomes. Median warm p95 changes across those runs ranged from -4.7% to
+3.3% by query; this is comparable routing latency, not a claimed routing speedup. Reader
peak RSS fell from approximately 3,081,300 to 1,608,400 KiB (48%). This benchmark opens each
profile separately; production owns and validates the shared mapping once per pack.
Startup measurements are cache-dependent and are not cold-open claims.

On the identical v2 directory, Deflate 6 took 45.847 seconds and produced 1,337,433,705 bytes.
Deflate 9 took 133.435 seconds and produced 1,263,095,092 bytes: 5.6% smaller, but 87.6 seconds
longer. The default stays at 6 because the extra compression time exceeded 10% of the full
build duration. Tantivy compression settings remain unchanged.

## Download choices

CI prepares the combined hierarchy once, then derives single-mode packs without OSM reimport
or recontraction. It remaps shared geometry/name IDs and copies hierarchy and snapping tables.

| Ukraine mode | ZIP bytes | Installed payload bytes | Derivation + ZIP seconds |
| --- | ---: | ---: | ---: |
| Car | 908,749,664 | 1,684,479,200 | 46.800 |
| Walk | 667,225,081 | 1,218,464,841 | 45.280 |
| Car + walk | 1,337,433,705 | 2,528,839,872 | Included in full build |

Sizes use decimal bytes and exclude the tiny metadata file from installed payload totals.
The app installs one pack at a time; select the combined download for both modes.
Both single-mode packs passed the same 64 fixed-vertex paths and public-coordinate workload
against the corresponding combined profile. The production pack loader also passed 32 road-endpoint
smoke routes per mode. A local Monaco run exercised build, derivation, ZIP validation,
extraction and native loading for all three downloads. GitHub-hosted execution has not been run.

[Car equivalence](routing-pack-v2-2026-10-25/car-equivalence.json),
[walking equivalence](routing-pack-v2-2026-10-25/walk-equivalence.json), and
[fixture output](routing-pack-v2-2026-10-25/fixture-variants.log) retain the evidence.

All five Ukraine ZIPs (three choices plus the two compression trials) passed the archive
validator. Every Tantivy file matched both its uncompressed SHA-256 and its compressed ZIP
stream across these archives; search is unchanged. See the
[archive validation report](routing-pack-v2-2026-10-25/variant-validation.json).

## Regional functional check

Kyiv v2: 62,659,684 bytes zipped; 123,926,401 bytes unpacked. The same-policy v1 regional
pack had 90,471,463 and 250,673,746 bytes respectively. Both v2 profiles passed 32 road-endpoint
smoke routes and the archive validator. This regional build ran alongside project checks;
its duration is deliberately not used as a performance comparison.

## Validation limits

No emulator or phone is attached. Android installation, real JNI execution, device startup
and ARMv7 performance remain unverified. The existing city-centre snapping/connectivity
failure is outside this storage change. The app remains a research prototype, not a safety system.

## Reproduction commands

Compile each revision before timing, retain its binaries in separate directories, and use the
same PBF and options. The baseline revision lacks the new benchmark CLI; copy the current
`benchmark-routing.rs` into a temporary checkout of baseline `native/nav-routing/src/bin/` to
build it against the old runtime. It uses only APIs shared by both revisions. This does not
change the old routing algorithm or pack layout.

```bash
cargo build --locked --release --manifest-path native/Cargo.toml -p imu-nav-routing --features builder
/usr/bin/time -v native/target/release/build-routing --osm ukraine-latest.osm.pbf --out ukraine-v2 --name Ukraine --threads 2
native/target/release/check-routing ukraine-v2
native/target/release/benchmark-routing ukraine-v2 docs/benchmarks/routing-pack-v2-2026-10-25/queries.json compact.json
python3 tools/verify_routing_equivalence.py baseline.json compact.json
native/target/release/archive-routing ukraine-v2 ukraine-v2-level9.zip 9
python3 tools/verify_routing_pack.py ukraine-v2-level9.zip
```

The compression trial uses the exact same extracted directory; only root routing entries
change Deflate level. Tantivy entries retain the same default compression and are checked for
identical compressed streams and uncompressed payloads. Archives must be written outside the
pack directory, preventing accidental inclusion of their own output.
