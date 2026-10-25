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

## Regional functional check

Kyiv v2: 62,659,684 bytes zipped; 123,926,401 bytes unpacked. The same-policy v1 regional
pack had 90,471,463 and 250,673,746 bytes respectively. Both v2 profiles passed 32 road-endpoint
smoke routes and the archive validator. This regional build ran alongside project checks;
its duration is deliberately not used as a performance comparison.

## Validation limits

No emulator or phone is attached. Android installation, real JNI execution, device startup
and ARMv7 performance remain unverified. The existing city-centre snapping/connectivity
failure is outside this storage change. The app remains a research prototype, not a safety system.
