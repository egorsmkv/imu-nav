# Rust CCH prototype versus current GraphHopper CH

## Decision

Keep production GraphHopper CH. The isolated Rust prototype works and improves car preparation on
a Kyiv extract, but it is not an overall replacement win: warm car query latency is similar,
acceleration files are much larger, and the current walking adapter is slower. Full-Ukraine CCH
ordering did not finish within this experiment's ten-minute budget. This does not establish its
complete build time or prove it cannot finish.

The prototype lived in `tools/cch-prototype` (removed with GraphHopper; see commit `b3d1bd9`), with a separate
Cargo workspace and lockfile. It uses `cch` 0.3.0 from crates.io, checksum
`15568834f8c95df9da186c4088b4b201e924dc051e87806be0968b6a0eec363b`.
It adds no APK dependency and changes neither production routing nor the pack format.

## Dataset and method

- Host: Intel Core i9-9900K, 32 GiB RAM, Linux; files on `/dev/shm`.
- JDK 21, GraphHopper 11.0, Rust 1.99.0, optimized Rust build, `RAYON_NUM_THREADS=4`.
- GraphHopper uses the existing production profiles, turn restrictions, U-turn penalties and
  left/right turn penalties. CH preparation is measured separately per profile, with one worker.
- Ukraine source SHA-256:
  `e8b407fa9e6f3c03d3bd7d4dd58fd949fd7b925cf3d5590bcd690bfb950c94e5`.
- Kyiv: bbox `30.25,50.25,30.8,50.65`, extracted with osmium-tool 1.16.0 using
  `-s smart -S types=restriction` to complete restriction relations. PBF size 32,641,484 bytes;
  SHA-256 `2c80923300539de4e6ebb4e7726b296b524721ae59150727e88ae7f096a84f34`.
- The shared Kyiv base import took 5.807 seconds in a setup run. Search generation, compilation,
  PBF extraction and ZIP creation are excluded from the acceleration preparation comparison.
- Monaco uses the existing production pack from the earlier standalone-builder validation.
- Each region/profile uses 32 seeded endpoint pairs (seed 20261024), snapped with the production
  access/subnetwork filter to tower nodes. No personal travel data is used.

The Rust adapter exports accessible directed road edges as states. Transitions encode legal turns
and the weight of the next edge plus its turn penalty. Thus this tests CCH on the existing routing
model, not a different OSM parser or profile. Both walking and car currently use edge states; this
is unnecessary overhead for walking compared with GraphHopper's node-based foot CH.

Queries measure the routing kernel plus original-edge/state path reconstruction. They exclude
snapping, geometry expansion and turn-by-turn instruction generation. The Rust code reuses path
scratch but includes fresh distance-query allocation: `cch` 0.3.0 forbids retargeting the same
`ElimTreeQuery`. GraphHopper creates a fresh CH algorithm per query. Index opening is excluded.
Each process has one warmup pass and five measured passes. Reported p50/p95 are percentiles of
**32 per-pair warm medians**, not a large-sample production tail-latency estimate.

Kyiv measurements run sequentially after the country-sized attempt ended. A second pair of query
processes reverses engine order. This is host screening, not an Android, cold-storage or end-to-end
navigation benchmark.

## Kyiv preparation

Complete measured preparation-process wall times, including opening the base/input and writing the
new index. The adapter export is a separate prerequisite for CCH and is shown explicitly.

| Profile | GraphHopper CH | Rust CCH | CCH export | CH peak RSS | CCH build peak RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Car | 34.23 s | 14.85 s | 1.08 s | 784 MiB | 297 MiB |
| Walking | 8.39 s | 73.77 s | 1.29 s | 687 MiB | 1,363 MiB |

CCH stages explain the tradeoff:

| Profile | States / legal transitions | Ordering | Structure | Customization |
| --- | --- | ---: | ---: | ---: |
| Car | 399,398 / 987,328 | 14.414 s | 0.276 s | 0.053 s |
| Walking | 800,415 / 2,447,115 | 71.602 s | 1.187 s | 0.500 s |

Ordering dominates. Parallel customization is fast, but it is only a small part of a fresh build.
Topology reuse can help when only weights change; an updated OSM topology still needs preparation.
The prototype uses separate profile topologies, so it does not yet amortize one structure across
car/foot profiles. Export peak RSS was 446 MiB for car and 523 MiB for walking, in separate processes.

## Kyiv warm routing and path reconstruction

Ranges are the results from the two independent process orders.

| Profile | CH p50 | CCH p50 | CH p95 | CCH p95 |
| --- | ---: | ---: | ---: | ---: |
| Car | 0.996–1.025 ms | 0.912–0.928 ms | 1.498–2.012 ms | 0.958–0.974 ms |
| Walking | 1.107–1.176 ms | 5.744–5.750 ms | 1.575–1.929 ms | 6.109–6.136 ms |

Car median latency is close; it is not a large overall navigation speedup. The current walking
adapter is about five times slower at the median. A node-based walking adapter and reusable
retargetable query buffers are plausible follow-up optimizations, not measured improvements.

## Storage

Uncompressed acceleration files only; shared base geometry and attributes are excluded. These are
not complete installable pack sizes or compressed ZIP sizes.

| Profile | CH files | CCH structure + metric | CCH / CH |
| --- | ---: | ---: | ---: |
| Car | 13,631,688 bytes (13.0 MiB) | 90,182,408 bytes (86.0 MiB) | 6.6× |
| Walking | 11,534,536 bytes (11.0 MiB) | 426,240,240 bytes (406.5 MiB) | 37.0× |

The current prototype additionally reads its state export: 19,081,776 bytes for car and 41,988,548
bytes for walking. A production design would need compact edge/state mapping and query-only data
rather than simply packaging all these experiment files.

## Correctness and smaller fixtures

All 1,344 compared result rows matched reachability and the documented accumulated rounding bound:
Kyiv car/foot in two process orders, Monaco car/foot, and a synthetic restricted junction. These are
repetitions of small seeded query sets, not 1,344 distinct journeys. Maximum objective-weight error
was 0.013140; CCH rounds each transition to 0.001 objective-weight units. Those units include distance
influence and must not be confused with measured travel time. Near-tied optimal paths may differ.

Every returned CCH state path was independently checked against the original legal transitions and
its summed integer weight, outside the latency timer. The no-left-turn fixture is the same fixture
that requires the car to detour while allowing walking to take the direct path. Separate Rust tests
check asymmetric one-way/unreachable routing and ensure shortcuts do not invent forbidden turns.
Python validator tests reject missing results, reachability mismatches, excessive cost error and NaN.

Monaco p50 kernel timings were 0.208 ms CH versus 0.0115 ms CCH for car, and 0.288 ms versus 0.0880 ms
for walking. The larger Kyiv case shows why the tiny-region result should not be generalized.

## Full Ukraine feasibility attempt

The production base graph exported to **9,089,750 car states and 25,124,111 legal transitions** in
6.70 seconds, yielding a 455,505,896-byte export. The CCH build used a 600-second timeout and 9 GiB
virtual-address-space cap. It exited with timeout status 124 while still in `inertial_order`:

- Wall time: 600.12 seconds; user CPU time: 593.00 seconds.
- Peak RSS before termination: 2,673,428 KiB (about 2.55 GiB).
- No complete index, customization measurement or query result was produced.

Some setup/export work ran concurrently early in this bounded attempt, so it is a feasibility
observation, not an isolated complete build benchmark. There is no fresh full-Ukraine CH-versus-CCH
comparison here. The old CI CH build and this bounded host attempt must not be presented as a speedup
ratio. Completing a same-host full-country comparison remains outstanding.

## Validation and next steps

The standalone Rust tests, Clippy and rustfmt checks passed. The comparator's four tests passed.
The Java exporter compiled and all measured profiles completed with legal reconstructed paths.
`./gradlew check` passed. No Android device or JNI runtime integration was exercised.

Before considering replacement: implement node-based walking, investigate query allocation and
index footprint, complete the country-sized benchmark, then validate snapping/virtual endpoints,
via-way and conditional restrictions, geometry, guidance, cancellation, JNI lifetime and R8/16 KB
Android behavior. The current experiment does not justify migrating the app from GraphHopper.

Reproduction commands: `tools/cch-prototype/README.md` at commit `b3d1bd9` ·
[Raw CSVs, comparison JSON and build metadata](cch-prototype-2026-10-24/)
