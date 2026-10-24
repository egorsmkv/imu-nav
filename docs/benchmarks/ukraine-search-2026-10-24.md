# Ukraine address-search benchmark — 2026-10-24

Production Tantivy search is substantially faster for broad Cyrillic prefixes/common
street names on this extract, but slower for several specific house and multiword
settlement queries. The installed indexes are nearly the same size; Tantivy's ZIP
is about 50% larger. This is a desktop measurement, not an Android performance claim.

The [expanded 216-case benchmark](ukraine-search-extended-2026-10-24.md) adds broader
coverage, repeat timings, and shared matching-gap findings.

## Data and build

Source: [Geofabrik Ukraine (with Crimea)](https://download.geofabrik.de/europe/ukraine.html),
downloaded from `https://download.geofabrik.de/europe/ukraine-latest.osm.pbf`.
The response was last modified **2026-10-23 23:27:23 UTC**; Geofabrik reports OSM
coverage through **2026-10-23 20:20:51 UTC**. Download size: **881,771,515 bytes**.
The download matched the upstream MD5 `37c3f04fe16d2894043d9ed4c552f735`.
SHA-256: `e8b407fa9e6f3c03d3bd7d4dd58fd949fd7b925cf3d5590bcd690bfb950c94e5`.

Production search code: commit `991637196b4584e808b444f2cfe27161d9f24bc7`.
The pending 0.11.0 version metadata does not change search code.
The harness uses the existing `SearchIndexBuilder` and `TantivyIndexBuilder`.
It imports the same car/foot base graph, turn costs, and minimum network size as
the pack builder, but disables CH shortcut preparation because search reads only
the base graph. **This is a search-index build, not a complete installable routing
pack.** Route shortcut build time, full archive size, and route latency are not measured.
The SQLite baseline is the current optimized reader with FTS4 prefix indexes,
request-local memoization, and batched house lookups, not the older unoptimized code.

Final contents:

- 36,864 place rows.
- 245,738 street rows.
- 1,111,630 address rows attached to streets, out of 1,232,779 candidate addresses.
- No elevation data; no Android emulator was started or running.

| Search storage | SQLite | Tantivy |
| --- | ---: | ---: |
| Uncompressed bytes | 104,259,584 | 103,608,864 |
| Uncompressed MiB | 99.43 | 98.81 |
| ZIP bytes (DEFLATE level 6) | 52,134,183 | 78,256,431 |
| ZIP MiB | 49.72 | 74.63 |

Tantivy is 0.62% smaller uncompressed and 50.11% larger as a ZIP.
ZIPs contain only the search component. Production routing ZIPs additionally contain
GraphHopper data and `pack.json`; these numbers are not full-pack sizes.

| Build stage | Wall seconds |
| --- | ---: |
| Base road graph, without CH | 73.801 |
| SQLite search staging | 74.279 |
| Export + Tantivy build + open validation | 9.073 |
| Whole process | 157.44 |

Peak build RSS: **4,060,944 KiB (3.87 GiB)** with an 8 GiB Java heap limit.
The native stage includes JSONL export and cleanup. These are single-build timings.

## Measurement method

Host: Intel Core i9-9900K, 8 cores / 16 threads, 32 GiB RAM, Linux x86-64,
JDK 21, Tantivy 0.26.1, SQLite JDBC 3.53.4.0. Indexes and input were on `/dev/shm`
(tmpfs) because the primary disk was nearly full. This favors cached storage access;
**no cold-disk measurement was made**. No concurrent build or benchmark was run
while timing searches; background host services were not disabled.

The desktop harness calls the production Kotlin matcher through JDBC and the
production Kotlin/JNI Tantivy adapter. Timings include normalization, planning,
candidate retrieval, JSON transport, final distance/ranking, and native request
handle allocation. They exclude debounce and network. Both readers stay open
for warm timing. Java heap limit is 2 GiB.

For each query: 30 paired warmups, followed by 100 paired samples alternating
engine order. Two complete runs used separate JVMs. The table below is run 2;
run 1 confirms the direction of every measured gain/regression. p50/p95 are sorted
sample indices 49/94. Queries are public/synthetic test strings, not user history;
the reference is a fixed public Kyiv center coordinate (50.4501, 30.5234).

Before timing, exact ordered result equality was checked for all 15 queries at
three reference settings: Kyiv, no reference, and a synthetic dateline reference
(0, 179.999). **All 45 comparisons passed in both runs.** The missing-house query
returns generic streets; `22`, `22 к1`, and `220` retain their distinct behavior.
The Lviv query tests an explicitly requested town far from the Kyiv reference.

A logical SQLite call is not comparable to a single low-level Tantivy lookup:
Tantivy performs all retrieval stages behind one JNI call. Both backends already
batch house retrieval, so this switch does not reduce SQLite's one house batch.

## Warm results (milliseconds)

| Public query | SQLite p50 / p95 | Tantivy p50 / p95 | Results | SQLite calls / house batches |
| --- | ---: | ---: | ---: | ---: |
| `ше` | 12.837 / 13.620 | 2.459 / 2.748 | 20 | 2 / 0 |
| `го` | 10.652 / 11.030 | 2.155 / 2.386 | 20 | 2 / 0 |
| `ky` | 0.512 / 0.581 | 0.634 / 0.750 | 20 | 2 / 0 |
| `Шевченка` | 11.233 / 11.771 | 1.630 / 1.790 | 20 | 2 / 0 |
| `Соборна` | 2.478 / 2.701 | 0.899 / 0.975 | 20 | 2 / 0 |
| `Київ` | 2.604 / 2.815 | 1.271 / 1.399 | 20 | 2 / 0 |
| `Хрещатик 22` | 0.483 / 0.535 | 1.218 / 1.261 | 20 | 3 / 1 |
| `Київ Хрещатик 22` | 1.591 / 1.796 | 2.391 / 2.544 | 3 | 7 / 1 |
| `Хрещатик 9999` | 0.448 / 0.475 | 1.051 / 1.087 | 18 | 3 / 1 |
| `Біла Церква Ярослава Мудрого 10` | 2.016 / 2.221 | 5.129 / 5.387 | 2 | 13 / 1 |
| `Львів Шевченка 22` | 4.292 / 4.617 | 2.538 / 2.653 | 5 | 7 / 1 |
| `невідомийпошукxyz` | 0.163 / 0.196 | 0.044 / 0.049 | 0 | 2 / 0 |
| `Полтава Героїв України 22` | 1.247 / 1.342 | 3.003 / 3.132 | 3 | 8 / 1 |
| `Полтава Героїв України 22 к1` | 1.273 / 1.436 | 3.051 / 3.158 | 2 | 8 / 1 |
| `Полтава Героїв України 220` | 1.230 / 1.292 | 2.941 / 3.067 | 1 | 8 / 1 |

`ше` improves about 5.2× and `Шевченка` about 6.9× at p50. Conversely,
`Хрещатик 22` takes about 2.5× as long, and the Bila Tserkva query about 2.5×.
Do not describe this as a universal search speedup. These timings alone do not
attribute the specific-address overhead to serialization, retrieval, or ranking;
that requires profiling.

## First open plus first query

Ten fresh JVMs per backend, alternating backend order, query `ше`. The timer
includes backend/class/native-library initialization, index open, the first
complete search, and close; it excludes JVM process launch. Index files remained
in memory, so these are **process-cold, storage-warm** measurements.

| Backend | Median ms | Min–max ms |
| --- | ---: | ---: |
| SQLite | 219.873 | 211.153–226.672 |
| Tantivy | 79.233 | 77.238–80.713 |

## Reproduce

Build the desktop distribution using the documented JDK/SDK/Rust toolchains:

```sh
./gradlew :routing:installDist
mkdir -p /tmp/ukraine-search/classes
curl --fail --location --output /tmp/ukraine-search/ukraine-latest.osm.pbf \
  https://download.geofabrik.de/europe/ukraine-latest.osm.pbf
javac -cp 'routing/build/install/routing/lib/*' \
  -d /tmp/ukraine-search/classes tools/RegionalSearchBenchmark.java
java -Xmx8g -Djava.library.path=routing/build/install/routing/lib \
  -cp '/tmp/ukraine-search/classes:routing/build/install/routing/lib/*' \
  RegionalSearchBenchmark build /tmp/ukraine-search/ukraine-latest.osm.pbf \
  /tmp/ukraine-search/index
java -Xmx2g -Djava.library.path=routing/build/install/routing/lib \
  -cp '/tmp/ukraine-search/classes:routing/build/install/routing/lib/*' \
  RegionalSearchBenchmark measure /tmp/ukraine-search/index \
  docs/benchmarks/ukraine-search-2026-10-24-queries.txt
```

The output graph/database must not already exist. `native OUTPUT` can resume
native conversion after a completed SQLite build. `open OUTPUT sqlite 'ше'` and
`open OUTPUT tantivy 'ше'` measure first open in separate JVMs. The source URL is
rolling; use the recorded checksum/date when comparing this exact dataset.

Raw results: [run 1](ukraine-search-2026-10-24-run1.txt),
[run 2](ukraine-search-2026-10-24-run2.txt),
[first open](ukraine-search-2026-10-24-first-open.txt),
[build stages](ukraine-search-2026-10-24-build.txt),
[sizes](ukraine-search-2026-10-24-sizes.json), and
[query corpus](ukraine-search-2026-10-24-queries.txt).

Physical ARM devices, flash-storage cold opens, and country-sized full routing
archives remain unmeasured. IMU Nav remains a research prototype, not a safety system.
