# Native navigation core

`imu-nav-core` contains deterministic, Android-independent estimation and trust logic. `imu-nav-jni`
is a deliberately small handle-based boundary; Kotlin never owns or dereferences a native pointer.
`NavigationEstimator` owns prediction timing, travel-mode process noise, OBD freshness, GNSS route
projection, measurement sigma policy, innovation gating and systematic-drift resets; Kotlin passes
observations rather than manipulating its covariance matrix.

## Filter model

The state is `x = [s, v]`: metres along the selected route and along-route speed. With a constant
velocity model, both the transition and the position/speed observations are linear, so a conventional
Kalman filter is the correct model. An Extended Kalman Filter would add Jacobian complexity without
adding information. Route projection is nonlinear geospatial work, but it happens before the scalar
`s` observation reaches the filter.

The 2×2 covariance matrix is

```text
P = [ var(s)    cov(s,v) ]
    [ cov(s,v)  var(v)   ]
```

The off-diagonal term matters: after prediction, a position correction can also improve speed. The
prediction derives `Q` from unknown acceleration and `dt`; each update derives `R` by squaring the
sensor's standard deviation (`sigma`). Updates use normalized innovation squared for outlier gating
and the Joseph covariance form for numerical stability. A separate distance-proportional allowance
covers persistent odometer/model bias, which Gaussian covariance alone tends to underestimate.

“Sigma algebra” is not an additional Kalman operation. In probability theory a sigma-algebra defines
measurable events. If “sigma” means sigma points, that describes an Unscented Kalman Filter. A UKF is
not useful for the present linear `[s, v]` state; it would become a candidate only if the native state
later included nonlinear latitude/longitude, heading and IMU-bias dynamics.

## Why the external crate is not a dependency

[`SuperInstance/kalman-filter`](https://github.com/SuperInstance/kalman-filter) was evaluated at commit
`371789b60f99e034227048bc8ff26bc8ae013926`. Its small Rust `KalmanFilter2D` is a useful teaching
implementation of the same constant-velocity idea, but it exposes mutable state, accepts only position
observations, uses a fixed caller-supplied process matrix, has no input validation or innovation gate,
and uses the shortened covariance update. Its 2D update also mutates covariance entries while later
entries still depend on the old matrix. The navigation core therefore keeps a purpose-built fixed-size
implementation with speed observations, Joseph updates, explicit error handling, long-run covariance
tests, spoofing gates, route geometry and JNI lifecycle management.

## Security boundary and rollout

Kalman consistency is not spoofing detection: a gradual spoofer can remain statistically plausible.
Receiver health, clock/physics checks, independent network agreement, service-area checks and AGC
jamming hysteresis run before measurement fusion. Android already uses these native trust decisions,
native route projection, the physical gate for network/cell fixes, weighted speed regression and
inverse-variance speed fusion. The route estimator currently runs beside the established Kotlin
engine and logs disagreement; it must pass recorded-trip accuracy comparisons before becoming the
displayed navigation state.
