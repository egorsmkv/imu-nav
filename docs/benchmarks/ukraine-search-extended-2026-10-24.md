# Expanded Ukraine search benchmark — 2026-10-24

This extends the [initial Ukraine benchmark](ukraine-search-2026-10-24.md) using
the same production code, indexes, desktop hardware, and RAM-backed storage.
No emulator or Android runtime was used. No production search code was changed.

The subsequent [runtime optimization](ukraine-search-optimized-2026-10-24.md)
measures the updated reader against this same corpus. The results below describe
the original reader and are retained as the baseline.

## Scope and method

- **216 query/location cases, 180 distinct query strings, 14 categories.**
- Real address rows sampled deterministically from the 24 largest indexed
  cities; additional multiword settlements, building parts, slash/letter numbers,
  prefixes, absent-house cases, punctuation, and intentional typos.
- Timed cases use a local public city center, a distant public city center, or no
  reference. The corpus records the exact coordinates, all from public/synthetic
  data; no user history is involved.
- **1,164 ordered-result equality checks per run** across each case's reference,
  Kyiv, Lviv, Kharkiv, no reference, and a synthetic dateline reference. Duplicate
  reference coordinates within a case are removed. Repeated query text across
  cases means these are comparisons, not 1,164 unique query strings.
- Two fresh-JVM runs, each with 10 paired warmups and 50 paired timed samples per
  case, alternating engine order: **43,200 timed complete searches in total**.
- p50/p95 use sorted indices 24/47. Raw files retain both per-case values, result
  counts, returned address counts, SQLite call counts, and retrieval JNI calls.
- Includes planning, normalization, JNI/JSON, and ranking; excludes debounce and
  network. Index readers stay open. These are steady-state desktop timings, not
  phone or cold-storage results. There were no concurrent builds or benchmarks.

## Performance

On run 2, Tantivy has a lower p50 in **53/216 cases**, and a higher p50 in
**163/216**. All 53 wins and 162 of the losses repeat in run 1; one extremely
cheap case changes direction. Tiny differences are not independently meaningful.
This is a deliberately address-heavy diagnostic corpus, **not a production query
frequency distribution**. It does not estimate what fraction of users benefit.

The table reports the **median of each category's per-case p50 values**, in ms;
it is not a pooled request percentile. Full per-case p95 values are in the raw files.

| Category | Cases | SQLite median p50 | Tantivy median p50 | Tantivy lower p50 |
| --- | ---: | ---: | ---: | ---: |
| Exact address, local reference | 24 | 0.754 | 1.713 | 1 |
| Exact address, distant reference | 24 | 0.716 | 1.704 | 1 |
| Exact address, no reference | 12 | 0.513 | 1.882 | 1 |
| Street before settlement | 12 | 0.642 | 1.713 | 1 |
| Missing house number | 12 | 0.625 | 1.696 | 1 |
| Uppercase and commas | 12 | 0.697 | 1.877 | 1 |
| Settlement name | 20 | 0.543 | 1.047 | 2 |
| Cyrillic prefix | 20 | 9.178 | 1.736 | 19 |
| Latin prefix | 12 | 0.237 | 0.779 | 2 |
| Common street name | 16 | 4.585 | 1.175 | 15 |
| Multiword settlement | 16 | 1.281 | 2.845 | 1 |
| Building-part number | 12 | 0.739 | 1.525 | 0 |
| Slash/letter house number | 12 | 0.691 | 1.637 | 0 |
| Typos / absent / empty plan | 12 | 0.157 | 0.030 | 8 |

The gains remain concentrated in broad Cyrillic autocomplete and common street
names. Specific addresses, building parts, and multiword towns generally favor
SQLite in this expanded corpus. The largest Tantivy per-case p95 in run 2 is
**6.526 ms**; slower does not necessarily mean slow in absolute desktop terms.

One of the largest absolute regressions is `Кривий Ріг вулиця Жака-Іва Кусто 98`
without a reference: SQLite p50 **1.484 ms**, Tantivy **5.324 ms**. With the local
reference, it is **2.304 ms** versus **5.386 ms**. Profiling is still required to
attribute the overhead to retrieval, transport, or ranking.

## Correctness and shared matching gaps

**Every result-parity comparison passed in both runs**: same complete ordered
results, including coordinates and distance values. This establishes backend
parity on the corpus, not perfect address recall. In particular, the following
**11 real-address query/location cases returned no results in both engines**:

| Case | Query from indexed public data | Timed reference lat, lon |
| --- | --- | --- |
| 43 | `Харків вулиця Багалія 8` | 49.8419, 24.0315 |
| 44 | `Харків вулиця Багалія 8` | none |
| 78 | `Херсон Університетська вулиця 181 а` | 46.6401, 32.6144 |
| 79 | `Херсон Університетська вулиця 181 а` | 49.8419, 24.0315 |
| 81 | `Харків 4-й Верстатний в’їзд 3` | 49.8419, 24.0315 |
| 85 | `Дніпро вулиця Віталія Білахіва 57` | 49.8419, 24.0315 |
| 87 | `Миколаїв вулиця 11 Лінія 134` | 49.8419, 24.0315 |
| 194 | `Дніпро вулиця Леоніда Каденюка 1 а` | 48.4680, 35.0418 |
| 197 | `Львів вулиця Григорія Смольського 1 а` | 49.8420, 24.0316 |
| 198 | `Запоріжжя вулиця Стародніпровська 12 а` | 47.8508, 35.1183 |
| 200 | `Миколаїв Прорізний провулок 11 а` | 46.9759, 31.9940 |

Two concrete shared issues were identified:

1. **Exact towns can be lost behind prefix matches before filtering/ranking.**
   Settlement interpretation requests ten nearest/id-ordered prefix matches.
   For `харків*`, the index contains 14 matching places. The exact `Харків` city
   is candidate 1 near Kharkiv, candidate 13 near Lviv, and candidate 11 without
   a reference. It falls outside the ten-row limit in the latter cases, behind
   names such as `Харківці` and `Харківка`. Thus `Харків вулиця Багалія 8` finds an
   address locally but returns nothing with the distant or absent reference.
   Both engines preserve this current candidate-selection behavior. This is a
   confirmed explanation for the Kharkiv example; the other distant-city misses
   were recorded but not individually traced.
2. **Separated letter suffixes are not recognized as house numbers.** The shared
   parser supports an attached letter (`15а`) and building parts (`22 к1`), but
   does not recognize a separate `а` in `181 а` / `1 а`. Those tokens can be
   interpreted as part of the street query, yielding no results. Some such
   queries can also return another interpretation, so merely counting nonempty
   results would overstate address recall.

Two additional nonnegative-category cases return nothing because all their
words are removed as street-type stop words: `пр` and `Набережна вулиця`.
All 12 intentional typo/absent/empty-plan cases return nothing, as expected.
No fuzzy matching or online fallback was exercised.

A nonempty result or an address-result count is not an assertion that the exact
sampled address was returned. This run checks equality between the backends;
a separate ground-truth recall suite is needed for that stronger claim.

## Reproduce

Compile the updated harness as described in the initial report. Reuse the existing
Ukraine `search.db` and `search.tantivy/`; no routing or index rebuild is needed.

```sh
python3 tools/ukraine_search_corpus.py /tmp/ukraine-search/index/search.db \
  /tmp/ukraine-search/extended.tsv
java -Xmx2g -Dsearch.benchmark.warmups=10 -Dsearch.benchmark.samples=50 \
  -Djava.library.path=routing/build/install/routing/lib \
  -cp '/tmp/ukraine-search/classes:routing/build/install/routing/lib/*' \
  RegionalSearchBenchmark matrix /tmp/ukraine-search/index \
  /tmp/ukraine-search/extended.tsv
```

Run the Java command twice in separate JVMs. The generator opens SQLite read-only,
uses seed `20261024`, and refuses to overwrite its output. The checked-in corpus
is the canonical set for these measurements; a changed extract or database engine
can choose different representative rows during corpus generation.

Artifacts: [corpus](ukraine-search-extended-2026-10-24-corpus.tsv),
[run 1](ukraine-search-extended-2026-10-24-run1.txt),
[run 2](ukraine-search-extended-2026-10-24-run2.txt).
