# Independent Rust routing import validation — 2026-10-25

This migration replaces both production GraphHopper routing and OSM import. The host builder
uses `osmpbfreader` and `faster_paths`; Android uses our immutable CH runtime through JNI.
GraphHopper is retained only as a JVM test reference. New `rust-ch-1` packs are required.
The previous faster_paths prototype ZIP is not an app pack.

## Regional import

A public Kyiv extract (32,641,484 bytes, SHA-256
`2c80923300539de4e6ebb4e7726b296b524721ae59150727e88ae7f096a84f34`) was imported directly,
including car and walking hierarchies, Tantivy address search, and ZIP compression.
Four contraction workers were used; profiles were prepared sequentially.

| Metric | Kyiv initial migration build |
|---|---:|
| Wall time | 15.92 s |
| Peak process RSS | 888,848 KiB |
| Uncompressed pack payload | 250,837,570 bytes |
| Importable ZIP | 90,535,962 bytes |
| Directed car states | 393,298 |
| Directed walking roads | 763,770 |

[Build output](rust-routing-2026-10-25/kyiv-build.log) and
[`time -v` output](rust-routing-2026-10-25/kyiv-build-time.txt) preserve the measurement.
These numbers precede the final directional-access correction and union-find memory optimization;
they describe this recorded build, not an exact size guarantee for later packs.
Both native profiles loaded and each answered 32 deterministic road-endpoint smoke routes.
Those smoke routes alone do not establish long-distance routing correctness or latency.

## Compatibility and limits

The importer implements an explicit new access/speed policy, not byte-for-byte GraphHopper parity.
See the [routing reference](../reference/routing_and_search.md) for restrictions and policy.
Elevation import requires local HGT files; automatic DEM downloading is not implemented.
The app remains a research prototype, not a safety system.

Host measurements exclude Android, network and UI behavior. No emulator or phone was attached
for this migration's validation. Android installation, JNI execution on a device and guidance
quality in real driving remain unverified.

## Automated validation

`spotlessApply check :app:assembleFdroidRelease :app:assembleFdroidBenchmark` passed.
This includes Rust fmt/Clippy/tests, Kotlin tests and lint. The native suite covers CH versus
Dijkstra, turn and via-way restrictions, one-way roads, barriers, directional access, dateline
projection, local elevation, malformed packs and 32-bit mapping-window behavior. JVM tests
exercise the Rust importer/loader/JNI adapter and cancellation, plus the retained GraphHopper
reference tests. The blind-drive replay remains `p95=20 max=25 rms=10 m`.

Both release and benchmark APKs passed `verify_android_pages.py` (16 KiB ELF/RELRO and ZIP
alignment). The release R8 mapping contains no GraphHopper classes. Rust routing compiled for
arm64-v8a, armeabi-v7a and x86_64; the release APK intentionally ships ARM only. Archive-validator
tests (5) and CI selection tests (14) passed. Windows orchestration tests were skipped on Linux.

## Full Ukraine baseline before the final access-policy correction

Source: the 881,771,515-byte public Geofabrik Ukraine PBF, SHA-256
`e8b407fa9e6f3c03d3bd7d4dd58fd949fd7b925cf3d5590bcd690bfb950c94e5`.
Two workers; full OSM import, both hierarchies, Tantivy search and ZIP compression:

| Metric | Result |
|---|---:|
| Wall time | 736.84 s (12m 17s) |
| Peak builder RSS | 11,624,172 KiB (11.09 GiB) |
| ZIP | 2,211,173,591 bytes (2.21 GB) |
| Unpacked payload | 5,881,240,932 bytes (5.88 GB) |
| Car states / transitions | 9,204,961 / 25,741,663 |
| Walking road states / transitions | 11,151,788 / 11,151,788 |

This does not fit a 7 GiB runner. Allow more memory than the measured peak for the OS, and room
for source, staging files, completed pack and ZIP. It is substantially larger than the earlier
491 MB prototype ZIP, which was not a complete app pack.

[Build log](rust-routing-2026-10-25/ukraine-build.log),
[process statistics](rust-routing-2026-10-25/ukraine-build-time.txt),
[public road-coordinate requests](rust-routing-2026-10-25/ukraine-queries.json), and
[runtime output](rust-routing-2026-10-25/ukraine-routes.log) preserve the results.
Each query ran once followed by ten warm repetitions. Timings include snapping, CH traversal,
unpacking and geometry/guidance construction; exclude JNI/JSON, UI and networking. With ten
warm samples, reported p95 is the maximum. No cache flush was performed.

| Route | Car warm p50 / p95 (ms) | Walking warm p50 / p95 (ms) |
|---|---:|---:|
| Kyiv–Lviv public roads | 61.03 / 61.94 | 122.83 / 124.49 |
| Kyiv–Odesa public roads | 47.81 / 49.55 | 92.36 / 93.23 |
| Short Kyiv public roads | 66.80 / 69.59 | 139.96 / 144.81 |

Each profile also passed 32 deterministic road-endpoint smoke routes. Opening and deeply
validating the car file took 22.60 seconds initially and 11.63 seconds on a later run; walking
open took 20.60 seconds. These are host observations with uncontrolled OS caches, not Android
startup or cold-cache guarantees. This validation cost remains a limitation.

The first city-centre query (Kyiv 50.4501,30.5234 to Lviv 49.8397,24.0297) failed with
`no road connection`; changing to public-road coordinates succeeded. Review then corrected
an independent access-policy bug: general `access=yes` must not authorize cars on footways or
pedestrians on motorways. The country ZIP passed archive validation. The country archive and timings above predate that correction and
are baseline evidence, not a release-pack recommendation. Generate production packs with the
final builder. No speedup over GraphHopper CH is claimed from these different-policy workloads.

## Final-builder regional revalidation

After the mode-access correction, a fresh Kyiv pack built in 21.65 seconds with two workers:
ZIP 90,471,463 bytes; unpacked payload 250,673,746 bytes. Both profiles passed 32 road-endpoint
smoke routes and the short public-road query (car warm p50/p95 64.90/67.40 ms; foot
141.73/143.10 ms). The archive validator passed. See the
[final build log](rust-routing-2026-10-25/kyiv-final-build.log),
[process statistics](rust-routing-2026-10-25/kyiv-final-build-time.txt), and
[route output](rust-routing-2026-10-25/kyiv-final-routes.log).
The earlier city-centre Kyiv endpoint also failed when tested against this regional pack;
the mode-access correction did not fix that case. Nearest-road snapping/connectivity for that
endpoint remains an unresolved limitation. The full country pack was not rebuilt after the
final importer correction.
