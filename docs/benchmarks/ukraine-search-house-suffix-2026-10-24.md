# Spaced house-suffix fix — Ukraine, 2026-10-24

The shared query parser now recognizes a trailing single-letter house suffix
such as `4 в`, `181 а`, or `12/2 а`, including an optional building part (`4 в к1`).
It identifies the house before removing street/place abbreviations, so suffixes
like `с`, `г`, and `м` are preserved too. The query keeps its indexed spaced
spelling; this does **not** merge `4 в` with `4в` or introduce transliteration.

Previously the extra letter became a street/settlement token, creating unnecessary
interpretations and preventing exact house retrieval. `Топольна вулиця Львів 4 в`
returned a generic street rather than the requested house. It now returns
`Топольна вулиця, 4 в` in Львів first.

The change is in the shared Kotlin parser, so both Tantivy and legacy SQLite
benefit. No schema, stored-number normalization, native library, candidate limits,
online policy, or debounce change is involved. **Existing packs need no rebuild.**
Exact numbers still rank before building-part matches; absent houses retain generic
street results. Attached suffixes, slash numbers, and existing `22 к1` syntax remain
supported. Multiple-letter suffixes and equivalence between spaced/attached forms
are outside this change.

## Correctness

- Core parser regressions cover spaced letters (including stop-word letters),
  uppercase/punctuation/spacing, slash stems, optional building parts, existing
  compact numbers, non-house text, and the existing trailing street-word behavior.
- Synthetic JDBC/Tantivy integration checks exact first-address hits, matching
  parity, number boundaries (`4`, `4 в`, `4в`, `40 в`), exact-versus-building-part
  priority, and missing-house fallback with both old and prefix-enabled SQLite schemas.
- On the existing Ukraine index, the public-data verifier asserts exact first
  address title, city, and backend equality for six addresses: Lviv's Топольна `4 в`,
  Kherson's Університетська `181 а`, Dnipro's Леоніда Каденюка `1 а`, Lviv's Григорія
  Смольського `1 а`, Zaporizhzhia's Стародніпровська `12 а`, and Mykolaiv's Прорізний `11 а`.
- Each of the two fixed-parser runs passes **1,164 complete ordered-result
  comparisons** between SQLite and Tantivy across the existing 216-case corpus.
- **11 query/location cases now return an address where they previously returned
  none**: five Lviv query variants, two Kherson locations, and four other suffix cases.
  All other cases retain their result and address-result counts; this count check
  alone is not a claim of complete ground-truth recall.

The separately documented distant-settlement candidate-limit issue is unchanged.
In particular, this fix does not claim to repair the Kharkiv-from-Lviv/no-reference
misses. Broad short prefixes still require enumerating their matching candidates.

## Performance

Same Ukraine index, native library from the preceding runtime optimization,
216-case corpus, host, and tmpfs storage as the
[previous comparison](ukraine-search-optimized-2026-10-24.md). Only the Kotlin parser
changes. Baseline source is `b2678f4`; its compiled `core.jar` was preserved before
editing and placed first on the baseline JVM classpath. No index was rebuilt.

Sequence: one baseline run, then two fixed-parser runs, separate JVMs, after all
builds/Gradle daemons stopped. Each case has 10 paired warmups and 50 timed
SQLite/Tantivy pairs, alternating backend order: **64,800 timed searches** total.
The table shows baseline versus fixed run 2, in milliseconds (p50 / p95, sorted
sample indices 24/47). It includes complete search processing, excludes debounce
and network, and makes no Android or cold-storage performance claim.

| Case | Query | Reference | Tantivy before p50 / p95 | Tantivy fixed p50 / p95 |
| --- | --- | --- | ---: | ---: |
| 63 | `Топольна вулиця Львів 4 в` | local city | 3.627 / 3.903 | 0.601 / 0.654 |
| 60 | `Львів Топольна вулиця 4 в` | local city | 2.935 / 3.110 | 0.631 / 0.654 |
| 61 | `Львів Топольна вулиця 4 в` | Kyiv | 2.866 / 3.000 | 0.631 / 0.664 |
| 62 | `Львів Топольна вулиця 4 в` | none | 2.839 / 2.954 | 0.630 / 0.680 |
| 65 | `ЛЬВІВ, ТОПОЛЬНА ВУЛИЦЯ, 4 в` | local city | 2.904 / 3.098 | 0.632 / 0.670 |
| 78 | `Херсон Університетська вулиця 181 а` | local city | 1.980 / 2.062 | 0.715 / 0.771 |
| 194 | `Дніпро вулиця Леоніда Каденюка 1 а` | local city | 1.755 / 1.858 | 0.773 / 0.802 |
| 197 | `Львів вулиця Григорія Смольського 1 а` | local city | 1.715 / 1.786 | 0.736 / 0.783 |
| 198 | `Запоріжжя вулиця Стародніпровська 12 а` | local city | 1.622 / 1.731 | 0.627 / 0.668 |
| 200 | `Миколаїв Прорізний провулок 11 а` | local city | 1.608 / 1.680 | 0.635 / 0.684 |

The formerly slowest query improves about **6×**, from p50 **3.627 ms** to
**0.601 ms**, while gaining the correct address result. Fixed run 1 agrees
(p50 **0.601 ms**). The city-first Lviv variant improves about **4.7×**.
No case in either fixed run slows by both more than 10% and 0.1 ms at p50 versus
the baseline. The maximum per-case Tantivy p95 across all 216 cases after the fix
is **3.005 ms**; it is not an aggregate request percentile.

## Validation and reproduction

`spotlessApply`, full `./gradlew check`, and the R8 Play release build passed.
The opt-in synthetic/Monaco JNI tests ran without skips. Replay remains
`p95=20 max=25 rms=10 m`. No emulator was used, as requested; no phone was connected.
The release APK built on this host is unsigned.

Use the existing `RegionalSearchBenchmark matrix` commands with the same
[corpus](ukraine-search-extended-2026-10-24-corpus.tsv). For the original parser,
put the saved `core-before-suffix.jar` before the distribution JAR wildcard on
the classpath. The local saved copy is under `build/ukraine-search/` (gitignored).

To independently verify actual first-address hits, compile and run
`tools/VerifyUkraineHouseSuffix.java` against the desktop distribution, passing
the directory containing `search.db` and `search.tantivy/`:

```sh
javac -cp 'routing/build/install/routing/lib/*' -d /tmp/ukraine-search/classes \
  tools/VerifyUkraineHouseSuffix.java
java -Djava.library.path=routing/build/install/routing/lib \
  -cp '/tmp/ukraine-search/classes:routing/build/install/routing/lib/*' \
  VerifyUkraineHouseSuffix /tmp/ukraine-search/index
```

Artifacts: [baseline](ukraine-search-house-suffix-2026-10-24-before.txt),
[fixed run 1](ukraine-search-house-suffix-2026-10-24-after1.txt),
[fixed run 2](ukraine-search-house-suffix-2026-10-24-after2.txt),
[exact first-hit assertions](ukraine-search-house-suffix-2026-10-24-exact-hits.txt).
