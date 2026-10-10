# Route geometry, GPS trust and speed

[Rust core guide](readme.md) · [Newcomer glossary](glossary.md)

`imu-nav-core` first decides where a point would land on the route, whether satellite evidence is trustworthy, and how network positions or measured speeds can support the estimate. The trust classifier runs before a GPS fix can become a trusted position; the route filter has its own statistical gates as a second check.

## `route.rs`: route geometry

`RouteGeometry` stores a geographic polyline and its cumulative distances. It validates coordinates
and projects a `GeoPoint` to the nearest route segment, returning:

- distance along the route;
- perpendicular offset from the route;
- segment index; and
- the projected geographic point.

Projection first searches a window around the current `s` value. If that result is too far from the
route, it can fall back to a global search. This avoids snapping to a distant repeated section while
still allowing recovery from a large position error.

Rust route/trust distance calculations and Kotlin `Geo.distance` clamp the haversine term to its
mathematical range before taking square roots. This keeps valid antipodal distances finite despite
rounding, so route lengths and physical jump checks cannot silently become `NaN`.

## `trust.rs`: GNSS trust firewall

`TrustClassifier` classifies each GNSS fix as `Good`, `Suspect` or `Bad` before the fix reaches the
navigation estimator. Its checks cover:

- invalid, mock and out-of-service-area fixes;
- altitude, speed, accuracy and wall-clock consistency;
- duplicate timestamps, impossible jumps and frozen coordinates;
- reported speed versus geographic displacement;
- disagreement with an independent network fix;
- satellite count, C/N0 strength and C/N0 spread;
- GNSS bearing versus compass heading; and
- weak, hard and chained jamming evidence.

The same module contains `JamDetector`, an AGC-based state machine with hysteresis. Trust checks are
separate from Kalman innovation gating because a gradual spoofing signal may remain statistically
plausible.

Rust and Kotlin reject supplied non-finite fix/receiver measurements and negative speed or accuracy
as invalid. Receiver checks use only past/current GNSS status younger than five seconds. Missing
measurements remain optional; `JamDetector` ignores non-finite AGC without advancing its exit hold.
Clock-skew and independent-network age comparisons reject overflowing timestamp differences rather
than interpreting wrapped differences as current evidence.

Clock-skew rejection leaves both the classifier's sequence watermark and the positioning hub's
latest usable state unchanged, so a future wall clock cannot block subsequent valid fixes.
AGC hysteresis ignores negative, duplicate and regressing sample times. Only GOOD GPS speed may
establish standstill for gyroscope-bias learning; rejected or SUSPECT fixes interrupt that evidence.

Independent network evidence must have valid geographic coordinates and a finite, non-negative
horizontal accuracy. Malformed network fixes are ignored for both disagreement and hard-jamming
confirmation; they cannot establish the exception that downgrades hard jamming to `Suspect`.

## `network.rs`: cell/network position tracking

`NetworkTracker` handles route-projected network and cell observations. It:

- rejects positions that are not physically reachable from the current anchor;
- accepts a new anchor only after consistent evidence;
- keeps short recent and 30-second history views;
- detects whether the last two samples are mutually consistent; and
- feeds suitable, non-duplicate samples to the network speed estimator.

The physical gate rejects negative, duplicate and out-of-order timestamps before changing an
anchor or a pending reanchor chain. A fix must be newer than both the current anchor and the latest
pending candidate. This prevents stale input from increasing the time available for a later jump.

Recording is a separate public boundary: Rust and Kotlin both ignore samples with negative elapsed
time or accuracy, non-finite position/accuracy/offset, or coordinates outside latitude ±90° and
longitude ±180°. Rejection leaves recent samples, history, speed evidence and duplicate-coordinate
tracking untouched. Valid zero accuracy and boundary coordinates remain accepted.

## `speed.rs`: speed estimation and fusion

This module contains two related components:

- `NetworkSpeedEstimator` fits weighted position-over-time regression to recent network samples,
  removes large residual outliers, and reports speed with uncertainty.
- `fuse_speed` combines available GNSS speed, route-speed prior and network-derived speed using
  inverse-variance weighting, with increasing uncertainty as the last GNSS speed ages.

Both Rust and Kotlin ignore malformed speed sources and validate network metadata before applying
the uncertainty floor. Overflowed fusion is unavailable (Rust `None`, Kotlin/JNI speed `0`). Network
regression rejects malformed observations, future/expired samples, too few surviving inliers and
non-finite fits. Historical queries leave stored observations available for later queries.

Fusion keeps the inverse-variance weighted mean, but Rust reports
`sigma = sum(weight * source_sigma) / sum(weight)`. By the covariance Cauchy–Schwarz bound this
covers arbitrary source correlation **if the supplied marginal standard deviations are valid**.
A GPS-trained route prior must not be treated as independent information. This bound does not
cover unknown bias or make the network regression's heuristic uncertainty calibrated.

`speed::tests::independent_episode_ensembles_cover_unknown_source_correlation` checks 8,192
independent synthetic Gaussian episodes at each correlation 0, 0.7 and 1, using fixed seed
`0x517a91`. The independent variance oracle is `(1-rho)*36/53 + rho*(66/53)^2`.
Empirical variances must agree within 8%; nominal 95% intervals must cover more than 94% of
episodes (and less than 96.5% at rho=1). These are fixed regression thresholds, not simultaneous
confidence claims. A shared 6 m/s bias is a negative control and must fail coverage (<5%).
Run with `cargo test --manifest-path native/Cargo.toml independent_episode_ensembles -- --nocapture`.

For parameter units, exact regression windows, return values and Kotlin correction boundaries, see
the [navigation input contract reference](input-contracts.md). The reference also explains why a
successful physical gate does not replace validation at the recording boundary.


## Duplicate and delayed measurements

Both Kotlin and Rust trust classifiers reject a GPS fix with `dup_time` / `DuplicateTime`
before changing classifier state when either its elapsed time or wall time is not strictly
newer than the previous raw fix. This rejection reports only the timing reason; it does not
run additional plausibility checks or reset frozen-position/jamming history. Reset explicitly
starts a new sequence.

The positioning hub still records raw GPS duplicates and delayed arrivals, but elapsed-time
rejections do not replace its latest judged fix or notify navigation observers. Motion history
and gyro-bias sampling similarly ignore repeated or older IMU timestamps. Gyro calibration
uses yaw samples only through the GPS speed measurement's elapsed time, excluding future yaw.
The existing wall-clock plausibility checks, motion expiry and ESKF equations are unchanged.
