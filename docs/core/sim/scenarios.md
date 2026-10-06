# Scenarios and inputs

[Simulator guide](README.md) · [Build and run](run.md) · [Glossary](glossary.md)

Each scenario creates a repeatable route and observations for a particular part of `imu-nav-core`.
The cases help compare behavior across code changes; they are synthetic and do not measure real-drive
accuracy.

The simulation exercises native trust classification, jamming detection, network positioning checks,
speed fusion, route projection and position estimation. In app scenarios, navigation ticks and
ordinary OBD inputs use 500 ms intervals; GPS arrives once per second and cell fixes every five
seconds. These are explicit synthetic workloads, not a reproduction of every Android power profile.
Straight northbound routes start inside Ukraine; the reroute adds a small lateral deviation.
Observations are generated before measurement.

| Scenario        | Behavior exercised                                                                       |
| --------------- | ---------------------------------------------------------------------------------------- |
| `driving`       | Healthy GPS, cell inputs, OBD and ordinary history maintenance                           |
| `jam`           | Bad receiver conditions, GPS exclusion and recovery after AGC hysteresis                 |
| `delayed`       | GPS observed two seconds before delivery, with intervening OBD and cell inputs           |
| `stop`          | GPS outage, confirmed stop hints and resumed motion without OBD                          |
| `reroute`       | Install a changed route halfway through a trip and discard old route history             |
| `walking`       | Pedestrian hints through a GPS outage, without car OBD                                   |
| `lifecycle`     | Eight full start/drive/drop cycles with fresh state                                      |
| `winding`       | Global coarse-fix projection onto a winding 20 km route                                  |
| `parallel`      | 40 m separated out-and-back sections with precise and ambiguous coarse fixes             |
| `crossing`      | Figure-eight intersections, with accepted and rejected coarse projections                |
| `reacquisition` | Fixes far outside a small local search window force a global scan                        |
| `sensor-burst`  | 50 Hz OBD stress stream with two-second-delayed GPS, filling the 128-frame history limit |

`advanced` selects the five focused cases; `all` includes every case. The four geometry cases call the
public route APIs directly with one query per configured second. They measure route construction,
projection and ambiguity handling, not full navigation or position accuracy. Their navigation-error
fields are zero by construction. The sensor-burst case uses full navigation with an intentionally
high OBD rate; it does not represent the normal Android sensor cadence.

Motion and walking inputs represent the _outputs_ of upstream detectors; this is not raw IMU
processing. Ground truth drives sensor synthesis only; the estimator receives observations through
its public APIs. Route geometry and session state are rebuilt for every repetition. Streaming output
fingerprints include covariance, drift, projection, trust decisions and acceptance flags; no growing
per-tick output log is kept in the profiled heap. Network history bounds and route ownership cleanup
are checked during execution. Existing core tests enforce estimator history bounds and rollback.
