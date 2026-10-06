# Route geometry, GPS trust and speed

[Rust core guide](README.md) · [Newcomer glossary](glossary.md)

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

## `network.rs`: cell/network position tracking

`NetworkTracker` handles route-projected network and cell observations. It:

- rejects positions that are not physically reachable from the current anchor;
- accepts a new anchor only after consistent evidence;
- keeps short recent and 30-second history views;
- detects whether the last two samples are mutually consistent; and
- feeds suitable, non-duplicate samples to the network speed estimator.

## `speed.rs`: speed estimation and fusion

This module contains two related components:

- `NetworkSpeedEstimator` fits weighted position-over-time regression to recent network samples,
  removes large residual outliers, and reports speed with uncertainty.
- `fuse_speed` combines available GNSS speed, route-speed prior and network-derived speed using
  inverse-variance weighting, with increasing uncertainty as the last GNSS speed ages.

