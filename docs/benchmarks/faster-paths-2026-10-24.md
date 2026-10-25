# faster_paths routing benchmark

## Result

`faster_paths` 0.1.0 is a promising car-routing prototype, but not a drop-in replacement for
GraphHopper. On the Kyiv extract its car query kernel is substantially faster, while the current
walking adapter is slower than GraphHopper. Acceleration files are larger for both profiles.
Production routing, Android dependencies and pack formats are unchanged.

## Method

The isolated [prototype](../../tools/cch-prototype/README.md) uses the same graph exports,
production weights, legal turn transitions and endpoint pairs as the
[earlier CCH experiment](cch-prototype-2026-10-24.md). That report records source checksums,
extraction bounds and graph counts. Host: i9-9900K, 32 GiB RAM, Linux; Rust 1.99.0 release
build, GraphHopper 11.0 and JDK 21. Regional files are on tmpfs. CPU-heavy measurements run
sequentially. No simulator, network requests, or personal travel data are involved.

This tests **ordinary parallel CH**, using `contract_graph_parallel`, not CCH or hub labels.
The Rust library receives a directed state graph exported from GraphHopper; it does not parse OSM.
Weights are rounded to 0.001 objective units, exactly as in the earlier CCH test; these are not
simply travel seconds. Every reconstructed path is checked against legal input transitions and
its exact integer cost. Comparisons to GraphHopper allow only the accumulated rounding bound.

Queries include search plus path reconstruction. Index loading, snapping, geometry expansion,
and instructions are excluded. The hierarchy is deserialized into memory; query scratch is reused.
Endpoint state lists are precomputed. Each 32-pair regional test has one warmup and five measured
iterations. p50/p95 summarize the 32 per-pair medians. This small sample does not establish Android
or end-to-end navigation latency. Walking uses an unnecessarily large edge-state adapter, whereas
GraphHopper uses node-based walking CH; a better walking adapter remains future work.

## Kyiv query latency

All times are milliseconds. GraphHopper and faster_paths were measured again in this task;
CCH values are from the preceding experiment on the same exports.

| Profile / engine | Warm p50 | Warm p95 |
| --- | ---: | ---: |
| Car, GraphHopper CH (fresh run) | 1.245 | 1.991 |
| Car, faster_paths (repeat) | 0.162 | 0.527 |
| Foot, GraphHopper CH (fresh run) | 1.278 | 1.979 |
| Foot, faster_paths (repeat) | 2.088 | 3.605 |

The first faster_paths pass gave car p50 0.161 ms and foot 2.124 ms. Earlier GraphHopper
passes gave car 0.996–1.025 ms and foot 1.107–1.176 ms; the variation does not change the
conclusion. Earlier CCH gave car 0.912–0.928 ms and foot 5.744–5.750 ms.

## Kyiv preparation and size

Preparation is process wall time including graph loading and index writing, excluding the shared
OSM import. Rust additionally needs export: 1.08 s car / 1.29 s foot in the earlier measurement.
GraphHopper preparation used one worker; faster_paths uses four Rayon workers. These are practical
configurations, not a controlled language comparison. CCH used four Rayon workers where supported.
Sizes below are decimal MB of acceleration files only; road geometry and the shared base graph
are additional. faster_paths stores u64 weights and includes its hierarchy data in a bincode file.

| Profile / engine | Preparation seconds | Index MB | Peak preparation RSS MiB |
| --- | ---: | ---: | ---: |
| Car, GraphHopper CH | 34.23 | 13.63 | 784 |
| Car, CCH | 14.85 | 90.18 | 297 |
| Car, faster_paths | 4.30 | 48.99 | 252 |
| Foot, GraphHopper CH | 8.39 | 11.53 | 687 |
| Foot, CCH | 73.77 | 426.24 | 1363 |
| Foot, faster_paths | 33.28 | 172.96 | 770 |

Preparation values are single observations, not medians across repeated builds.
A separate one-worker faster_paths car build took 14.73 s (251.5 MiB peak RSS),
versus 4.30 s with four workers: about 3.4× lower wall time for this sample.

## Smaller graphs and correctness

Monaco faster_paths p50: car 0.0167 ms, foot 0.2127 ms; earlier GraphHopper CH:
0.2079 / 0.2879 ms. Small graphs alone would have hidden the walking regression on Kyiv.
The synthetic no-left-turn fixture also passes. Regional tests and the Kyiv repeat produce
1,344 matching result rows (including warmups and repeats, not 1,344 unique journeys).
Reachability agrees and cost differences stay within the stated rounding bound.

Raw CSVs, comparison JSON and process resource measurements are in
[faster-paths-2026-10-24/](faster-paths-2026-10-24/).

## Full Ukraine car graph

The same 9,089,750-state / 25,124,111-transition export completed preprocessing successfully:

- Four Rayon workers, 600-second timeout, 9 GiB virtual-address-space cap.
- Contraction: 179.282 seconds. Complete build process: 193.17 seconds.
- Peak build RSS: 6,755,844 KiB (6.44 GiB).
- Serialized hierarchy: 1,416,662,005 bytes (1.42 GB decimal).
- Output was written to the host disk, not tmpfs; input remained on tmpfs.
- Query-process peak RSS: 2,083,988 KiB, including the retained input graph and validation data.
  This is not a measurement of the minimum memory an optimized runtime would require.

The prior CCH trial timed out after 600 seconds during ordering on this same export. That is
an incomplete attempt, not a measured full CCH preparation time. No full-country GraphHopper CH
index was available locally. A comparison with its unaccelerated Dijkstra search checks correctness,
but cannot establish a speedup over the app's normal CH routing.

These preparation figures exclude the shared OSM import and graph export. Even with faster
contraction, this prototype does not remove Java from the pack-building pipeline. The index and
peak RAM also need attention before considering Android use or a small CI runner.

All 96 full-country result rows matched the independent GraphHopper Dijkstra reference (16 pairs,
one warmup and five measured iterations). Maximum objective-weight difference was 0.018875,
within the per-path rounding bound. A second faster_paths run also passed all 96 comparisons.
Its warm p50 was 0.556–0.619 ms, p95 0.879–0.917 ms across the two runs.
The Dijkstra reference measured p50 266 ms and p95 4,304 ms; this is a correctness baseline,
**not the app's accelerated CH performance**. Combined regional and national runs validate
1,536 rows including repeats, not that many unique journeys.

## Verification and next decision

`cargo test --locked`, Clippy with warnings denied, Rust formatting, four Python comparator tests,
`git diff --check`, and `./gradlew spotlessApply check` passed. The Rust tests include a serialized
hierarchy compared against an independent all-pairs shortest-path oracle on an asymmetric graph
with disconnected components. No production runtime code changed in this experiment, and no
Android build or device performance validation was performed.

Next useful work would be reducing walking to a node graph and reducing hierarchy storage/runtime
memory, then testing real Android route requests including snapping, geometry and instructions.
These results justify further car-routing experiments, not switching the shipped pack format yet.
