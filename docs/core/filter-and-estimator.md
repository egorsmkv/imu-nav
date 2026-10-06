# Route filter and navigation estimator

[Rust core guide](readme.md) · [Newcomer glossary](glossary.md)

The route filter keeps a best guess for how far the vehicle has travelled along its planned route.
It also tracks speed and how uncertain those guesses are. The estimator decides when to advance
that guess, which observations are safe to use, and how to replay late observations. This estimator
currently runs beside the Kotlin engine for comparison; it does not choose the position displayed
by the app.

`imu-nav-core` uses `tracing` for diagnostics and forbids unsafe Rust. Its modules are:

## `lib.rs`: route-state filter

The crate root defines a fixed-size linear Kalman filter whose state is:

```text
x = [s, v]
```

`s` is distance in metres along the route and `v` is along-route speed in metres per second. The
filter provides:

- constant-velocity prediction with acceleration process noise;
- position and speed measurement updates;
- normalized-innovation-squared gates for statistical outliers;
- Joseph-form covariance updates;
- trusted position anchors;
- a distance-proportional systematic-drift allowance in addition to covariance; and
- a conservative safety radius that combines random uncertainty and systematic drift.

The main public types are `RouteFilter`, `Estimate`, `Covariance2`, `UpdateOutcome` and
`FilterError`.

## `estimator.rs`: navigation estimator

`NavigationEstimator` owns a `RouteFilter` and applies the policy needed to run it during a trip. It:

- selects car or walking process noise;
- advances the filter to measurement timestamps and elapsed-realtime ticks;
- applies delayed GNSS fixes at their observation time, replaying later predictions, coarse fixes, motion hints and OBD
  readings from a bounded history (up to five seconds / 128 checkpoints); future, duplicate,
  out-of-order and expired GNSS fixes are ignored;
- projects trusted or suspect GNSS observations onto the route;
- rejects both position and speed from SUSPECT fixes at least 60 m off-route or at least 300 m
  from the estimate at measurement time, independently of the innovation gates;
- derives measurement uncertainty from GNSS accuracy and trust level;
- incorporates GNSS speed and fresh OBD-II vehicle speed;
- uses shared IMU stop/resume hints for car dead reckoning, with measured-speed and network-motion vetoes;
- gently corrects car position from confirmed coarse CELL/NET fixes without treating them as precise anchors;
- applies one-shot, bounded position corrections from completed IMU rotations matching isolated route turns;
- learns a per-trip OBD speed scale from precise, accepted GOOD GNSS while raw OBD has remained
  stable for at least three seconds; subsequent OBD updates use the learned scale;
- uses lower drift growth only after an accepted OBD speed update and until its 2.5-second
  expiry; rejected readings cannot extend freshness, and duplicate or stale OBD inputs are ignored;
- resets systematic drift after an accepted GOOD GNSS position; and
- replaces and re-anchors route geometry after rerouting, discarding the previous route's history.

The SUSPECT gates match the live engine's normal consistency limits. The comparison estimator
does not receive jam-recovery state, so it does not apply the live engine's relaxed recovery jump
limit. Neither these checks nor Kalman innovation gating replace the upstream trust classifier.
Prediction splits long intervals into steps of at most five seconds and splits at OBD expiry,
so all elapsed travel is accounted for without applying fresh-OBD uncertainty to earlier travel.

OBD scale learning requires speeds of at least 5 m/s, a GNSS/OBD time difference no greater than
250 ms, GNSS speed uncertainty at most 0.8 m/s, position uncertainty at most 20 m, and route offset
below 25 m. The stable raw OBD range is at most 0.5 m/s with no gap longer than one second.

Only ratios in 0.8–1.2 qualify, blended by 5% per accepted GNSS observation. SUSPECT fixes and
missing accuracy fields cannot calibrate the scale. Scale and plateau history are checkpointed
with the filter so delayed GNSS and OBD replay produce the same calibration as chronological input.
The existing systematic-drift allowance remains; learning a scale does not eliminate uncertainty.

Stop/resume hints reuse the Kotlin detector's hold time, resume confirmation and acceleration ramp.
They are derived from recorded IMU/network inputs, not an independent velocity measurement. Fresh
accepted GOOD GNSS or OBD speed takes precedence; recent non-duplicate network positions veto a stop
when estimated speed minus three standard deviations exceeds 1 m/s.

Walking ignores these hints.
Applying a hint changes the speed prior without reducing position uncertainty or resetting drift.
The pre-stop cruising speed is retained for resuming. Hints expire two seconds after the last actual
IMU sample, including across long tick gaps; missing or vetoed hints restore an uncertain cruising
prior instead of leaving the car permanently stopped. Delayed GNSS replays the recorded hints.

If the stopped/ramping speed model rejects a GOOD GNSS or OBD speed, the estimator also tests the
saved cruising-speed prior (6 m/s standard deviation) with the same innovation gate. This prevents
a false stop from repeatedly rejecting legitimate highway speeds above the stopped model's gate.

SUSPECT GNSS cannot use this alternative. Rejection by both models leaves the predicted state and
measurement freshness unchanged; accepting a speed clears motion control but does not correct
position, reduce its uncertainty, or erase systematic drift. A measured zero speed still uses the
stopped model first, rather than forcing a return to cruise.

Quiet highway motion cannot reliably be distinguished from standing still by vibration alone.
Likewise, prolonged phone handling can look like movement. If IMU disappears during a real stop,
the cruising fallback can drift forward. These are unresolved model limitations, not guarantees
of stop detection; the estimator remains comparison-only in this research prototype.

It returns a `TickOutcome` containing the estimate, optional route projection, and whether the
position and speed measurements passed their innovation gates.
