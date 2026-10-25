# Ukraine routing acceleration: preliminary host comparison

## Recommendation

Prototype landmarks (LM) behind an explicit pack-format capability before considering a production
switch. Keep contraction hierarchies (CH) in shipping packs for now. Ordinary flexible A* avoids
hierarchy preparation, but multi-second intercity queries on this desktop make it an unattractive
replacement for phone routing. These results do not justify replacing Java or GraphHopper.

No production acceleration mode was changed by this experiment. The earlier two-thread CH
preparation change is separate.

## Method and limits

Host: Intel Core i9-9900K, JDK 21, GraphHopper 11.0, `-Xmx8g`, graph files on `/dev/shm`.
Source: Geofabrik Ukraine, 881,771,515 bytes, SHA-256
`e8b407fa9e6f3c03d3bd7d4dd58fd949fd7b925cf3d5590bcd690bfb950c94e5`.
The reused base graph has the production car/foot profiles, turn costs and network cleanup.
See [the search build report](ukraine-search-2026-10-24.md) for its 73.801-second import measurement.

The benchmark compares bidirectional A* without acceleration against LM, with 16 landmarks per
profile and other GraphHopper defaults. Query processes use read-only memory-mapped graphs.
The phone column uses `PhoneGraphHopper` weighting on the host JVM, **not Android hardware**.
Each process runs 11 public city-centre journeys once for warmup, then three measured repetitions.
A second pair of processes reverses the LM/flexible order. This is an initial screening, not a p95
study, cold-storage benchmark, or exhaustive route-correctness proof. No concurrent benchmark
workloads were run during these route measurements.

A fresh CH query benchmark was unavailable: the GitHub connector rejected the existing Ukraine
artifact because its 587,510,403 bytes exceeded the connector's 512 MiB limit. The earlier CI build
was about 34 minutes, including more than hierarchy preparation and on different hardware. Do not
interpret this as a measured CH-versus-LM speedup ratio.

## Preparation and size

LM preparation using the phone weighting implementation took **237.009 seconds**, including base
graph opening. `/usr/bin/time` measured 237.39 seconds for the process and peak RSS 5,434,632 KiB.
It used the default single preparation worker. OSM import, search indexing and archive compression
are excluded. The existing base graph was copied before preparation; installed packs were untouched.

| Routing data | Bytes | MiB |
| --- | ---: | ---: |
| Base graph without acceleration | 522,239,390 | 498.0 |
| Base graph plus LM | 1,105,248,046 | 1,054.0 |

These are uncompressed routing files, excluding Tantivy. LM adds about 556 MiB; it is not a
storage-saving result. Neither zipped LM size nor Android runtime memory has been measured.

## Warm query latency

Median milliseconds, three measured samples per query per run. Both columns below use the same
phone weighting implementation; ranges show the medians from the two separate processes.

| Public journey | Flexible A* | Landmarks |
| --- | ---: | ---: |
| Kyiv local | 7.95–8.91 | 5.21–7.86 |
| Kyiv–Zhytomyr | 101.14–115.23 | 6.23–8.96 |
| Kyiv–Lviv | 1,947.97–2,026.25 | 27.51–32.63 |
| Lviv–Odesa | 2,890.06–2,966.86 | 467.91–482.57 |
| Kyiv–Kharkiv | 1,305.94–1,375.99 | 20.48–20.72 |

Lviv–Odesa remains comparatively expensive: LM visited 315,174 nodes in the first run. Route
geometry and instructions are included in timings. Desktop Janino weighting was also checked;
its flexible Kyiv–Lviv and Lviv–Odesa medians were 1,856.07 and 2,876.64 ms respectively.

Across five processes (desktop flexible, phone flexible/LM, then reversed phone repeats), every
one of the 44 rows per process had no error and matched the desktop flexible reference in route
weight (0.001), distance (0.01 m) and travel time (1 ms). These are 11 distinct journeys, not 220
independent route cases. A synthetic no-left-turn fixture additionally gave a legal car detour of
1,981.469 m versus a 727.964 m walking route, and LM's car weight matched flexible routing.

## Compatibility obstacle

Simply adding LM to the current desktop configuration fails with:

```
The turn_penalty feature is not supported for car. You have to enable this in 'turn_costs' in config.yml.
```

[GraphHopper's LM setup](https://github.com/graphhopper/graphhopper/blob/11.0/core/src/main/java/com/graphhopper/GraphHopper.java)
asks for a weighting with turn costs disabled to compute optimistic landmark distances. Its default
weighting factory rejects our custom turn-penalty model in that mode. This fails both preparation
and opening an LM graph with the unmodified desktop factory.

The experiment instead uses the existing `PhoneGraphHopper` weighting, which honors the
`disableTurnCosts` parameter for preparation and retains turn penalties/restrictions for queries.
[The LM router](https://github.com/graphhopper/graphhopper/blob/11.0/core/src/main/java/com/graphhopper/routing/Router.java)
then performs edge-based routing for the car profile. Dropping turn costs from the actual car
profile, or using node-based car CH, would change route legality and is not an acceptable shortcut.

Shipping LM would still require a tested desktop weighting adapter, explicit acceleration metadata,
matching builder/phone configuration, backward-compatible loading of CH packs, archive validation,
and broader correctness tests (one-way roads, U-turns, disconnected regions, via points, rerouting).
The current phone loader expects CH and cannot consume this experiment as an ordinary pack.
An R8-enabled ARM device test must establish route latency, memory and minimum-Android behavior
before choosing a new default. No device was available in this experiment.

## Reproduction and raw results

Compile the standalone probe after `./gradlew :routing:installDist`:

```bash
javac -cp 'routing/build/install/routing/lib/*' -d build/routing-modes tools/RoutingModesBenchmark.java
```

Use a **new copy** of the base graph made by `RegionalSearchBenchmark build` (documented in the
search report). Preparation writes acceleration files and properties into that copy:

```bash
cp -a /path/to/base-graph /path/to/disposable-lm-graph
java -Xmx8g -cp 'build/routing-modes:routing/build/install/routing/lib/*' \
  RoutingModesBenchmark prepare-phone /path/to/disposable-lm-graph lm prepare.csv
java -Xmx8g -cp 'build/routing-modes:routing/build/install/routing/lib/*' \
  RoutingModesBenchmark phone /path/to/disposable-lm-graph lm lm.csv
java -Xmx8g -cp 'build/routing-modes:routing/build/install/routing/lib/*' \
  RoutingModesBenchmark phone /path/to/base-graph flex flex.csv
java -cp 'build/routing-modes:routing/build/install/routing/lib/*' \
  RoutingModesBenchmark verify-turns /path/to/new-junction-fixture
```

Replace `phone` with `desktop` for the standard weighting factory; desktop LM currently fails as
noted above. The `ch` mode can measure a matching production Ukraine CH graph if available.

Raw files: [preparation](ukraine-routing-modes-2026-10-24-prepare.csv),
[desktop flexible](ukraine-routing-modes-2026-10-24-flex-desktop.csv),
[phone flexible](ukraine-routing-modes-2026-10-24-flex-phone.csv),
[phone LM](ukraine-routing-modes-2026-10-24-lm-phone.csv),
[phone flexible repeat](ukraine-routing-modes-2026-10-24-flex-phone-repeat.csv),
[phone LM repeat](ukraine-routing-modes-2026-10-24-lm-phone-repeat.csv).
