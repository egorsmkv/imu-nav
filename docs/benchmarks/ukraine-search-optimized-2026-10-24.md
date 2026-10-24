# Tantivy runtime optimization — Ukraine, 2026-10-24

The production reader now reuses fast-field column handles within each request,
stops empty term intersections early, and reads house-key postings directly.
The change speeds up typical specific-address cases about **2.4×** and multiword
settlement cases about **2.6×** in the expanded Ukraine desktop corpus.

A later [spaced-house-suffix fix](ukraine-search-house-suffix-2026-10-24.md)
addresses the slow `4 в` query variants and their missing house results. The
measurements below remain the native-optimization baseline before that parser fix.

## What changed and why

Temporary native stage timing of the public query
`Кривий Ріг вулиця Жака-Іва Кусто 98` identified repeated numeric-column opening
as the main cost. Across the last 30 profiled requests, median summed candidate
lookup time was about **4.705 ms**: column opening accounted for **3.274 ms**,
posting matching **0.912 ms**, candidate collection **0.421 ms**, and stored-row
reads **0.076 ms**. House retrieval was about **0.063 ms** for this particular
query. These are attribution measurements with temporary timing probes, not the
final benchmark timings. The probes and logging were removed before validation.

The implementation changes are:

- One stable searcher and lazy per-segment column handles for the entire request.
  Opening columns decodes metadata, so repeating it for every interpretation was
  expensive even when few or no documents survived. Handles are released at the
  end of the request; there is no cross-request result cache or invalidation rule.
- Stop intersecting as soon as there are no surviving documents. This also avoids
  opening columns or scanning zero-filled candidate masks for an empty conjunction.
- For sparse house keys, visit exact-number and building-part postings directly,
  instead of allocating and scanning a full-segment bitset for each street/key.
- Make the native library an input to the Gradle JVM test task, so native-only
  changes invalidate cached test results. The opt-in fixture directory is also
  a declared input property.

The index schema, `tantivy-1` format, limits, candidate ordering, distance rules,
matching/parser behavior, JNI protocol, and online policy are unchanged. Existing
Tantivy packs benefit from the updated reader **without rebuilding the pack**.
Legacy SQLite packs continue using SQLite. No new dependencies were added.

## Controlled comparison

Same unchanged [Ukraine index](ukraine-search-2026-10-24.md), same
[216-case corpus](ukraine-search-extended-2026-10-24-corpus.tsv), same production
Kotlin matcher and desktop harness. Baseline native code is from `9916371`.
The original shared library was saved before editing; both libraries were tested
against the exact same index files.

Run order: **before 1 → after 1 → after 2 → before 2**, separate JVMs. Each case
gets 10 paired warmups and 50 timed SQLite/Tantivy pairs, alternating engine order.
This is **86,400 timed complete searches** across four runs. Build processes and
Gradle daemons were stopped first; no emulator ran. Host, storage, and timing
limitations from the previous reports still apply: x86-64 desktop, tmpfs indexes,
no network/debounce, warm readers, and no mobile or cold-flash performance claim.

Every run passed **1,164 exact ordered-result comparisons** against SQLite.
All four runs also have identical result counts and address-result counts per case.
The shared matching gaps found in the expanded report are intentionally unchanged;
backend parity is not a claim that every sampled address is found.

## Results

The table uses pair 2. Values are **medians of per-case p50 times within each
category**, in milliseconds; they are not pooled request percentiles. Raw files
contain each case's p50 and p95 (sorted sample indices 24 and 47).

| Category | Tantivy before | Tantivy after | Improvement | SQLite control, after run |
| --- | ---: | ---: | ---: | ---: |
| Exact address, local reference | 1.711 | 0.716 | 2.39× | 0.752 |
| Exact address, distant reference | 1.705 | 0.715 | 2.39× | 0.714 |
| Exact address, no reference | 1.872 | 0.722 | 2.59× | 0.514 |
| Street before settlement | 1.767 | 0.729 | 2.42× | 0.645 |
| Missing house number | 1.717 | 0.706 | 2.43× | 0.626 |
| Uppercase and commas | 1.874 | 0.735 | 2.55× | 0.686 |
| Settlement name | 1.036 | 0.782 | 1.33× | 0.548 |
| Cyrillic prefix | 1.748 | 1.510 | 1.16× | 9.221 |
| Latin prefix | 0.784 | 0.558 | 1.40× | 0.234 |
| Common street name | 1.190 | 0.913 | 1.30× | 4.566 |
| Multiword settlement | 2.872 | 1.103 | 2.60× | 1.281 |
| Building-part number | 1.576 | 0.718 | 2.19× | 0.757 |
| Slash/letter number | 1.633 | 0.741 | 2.20× | 0.680 |
| Typos / absent / empty plan | 0.033 | 0.031 | 1.07× | 0.156 |

Across the 216 cases:

- **191 cases improve by at least 10% in both comparisons.**
- No case in either comparison regresses by **both more than 10% and 0.1 ms** at
  p50. This is an explicit reporting threshold, not a claim of zero timing noise.
- Tantivy's p50 beats SQLite in **111/216** cases in after run 1 and **109/216**
  in after run 2, versus **54/216** and **53/216** before. This diagnostic corpus
  is address-heavy and is not a production query-frequency distribution.
- The largest per-case Tantivy p95 in pair 2 falls from **6.518 ms to 3.846 ms**.
  This is the maximum of the per-case p95 values, not an overall request p95.

Tantivy still loses some selective/no-reference/Latin-prefix cases to SQLite.
These changes improve the current backend; they do not establish a universal win.
The index size and compressed download size are unchanged because no index was rebuilt.

## Validation and compatibility

- `cargo test -p imu-nav-search`: five tests passed, including a new multi-segment
  test with overlapping document IDs, deleted rows, empty conjunctions, filtered
  street lookups, house retrieval, dateline ranking, and no-reference ordering.
- Writer-enabled Clippy passed with pedantic warnings treated as errors.
- `spotlessApply` and full `./gradlew check` passed. The native-input change forced
  the JVM tests to rerun; both opt-in `TantivyPrototypeTest` tests passed, including
  cancellation isolation, close/read ownership, synthetic and public Monaco parity.
- Play release APK built successfully with R8. ARM64, ARMv7, and x86-64 native
  readers cross-compiled; the release APK contains ARM64 and ARMv7 libraries.
- Replay remains `p95=20 max=25 rms=10 m`.
- No device execution: emulator use was excluded by request and no phone was connected.
  The release APK on this host is unsigned.

## Reproduce

Use the expanded report's `RegionalSearchBenchmark matrix` command with a separate
`-Djava.library.path=...` for the saved baseline and optimized host libraries.
Keep the Kotlin/JDBC classpath, corpus, input indexes, heap limit, and sample counts
identical. The locally retained libraries are under the ignored build paths
`build/ukraine-search/lib-before/` and `build/ukraine-search/lib-after/`.

Library SHA-256:

- Before: `393a4bc64cf2dddc85adc52ec02c6c8460ed758aa665c42d00354792fd8aa1f6`.
- After: `bd3e66a9cd08e6efa95ffe8e62d54c0751b16a2ac8ce0e4069b254508e94ebc4`.

Raw runs: [before 1](ukraine-search-optimized-2026-10-24-before1.txt),
[after 1](ukraine-search-optimized-2026-10-24-after1.txt),
[after 2](ukraine-search-optimized-2026-10-24-after2.txt),
[before 2](ukraine-search-optimized-2026-10-24-before2.txt).
