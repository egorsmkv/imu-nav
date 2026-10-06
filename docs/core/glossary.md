# Newcomer glossary for the Rust core

[Rust core guide](README.md) · [Project glossary](../GLOSSARY.md)

These are the terms used in the core guides. The descriptions explain what each term means in this
project; the linked guides contain the exact rules and limits.

- **Cargo workspace** — A set of Rust packages built together. `native/` is the workspace for the
  navigation crates. The cell-sharing server has a separate Cargo manifest.
- **Crate** — One Rust compilation unit. Here the core is an algorithm library, JNI is the Android
  bridge, and the simulator is a desktop program. [Crate overview](README.md).
- **Host** — The development computer running tests, replay or simulation, rather than the phone.
- **JNI** — Java Native Interface, the bridge that lets Kotlin call compiled Rust code.
  [Android bridge](jni-and-android.md).
- **Native library / `cdylib`** — A compiled library loaded by another program. Android loads
  `libimu_nav_jni.so` from the JNI crate. [Android bridge](jni-and-android.md).
- **JNI handle** — An integer Kotlin keeps to refer to a Rust object. Rust checks that it still
  names a live object before using it. [Android bridge](jni-and-android.md).
- **GNSS / GPS fix** — One satellite position observation. The trust classifier labels it GOOD,
  SUSPECT or BAD before navigation uses it. [GPS trust](positioning-and-trust.md).
- **Dead reckoning** — Estimating movement after trusted GPS stops, using speed and other evidence
  along a planned route. [Route filter](filter-and-estimator.md).
- **Route coordinate `s`** — Distance in metres from the start of the planned route. The filter
  estimates `s`, rather than freely moving in latitude and longitude. [Route filter](filter-and-estimator.md).
- **Route projection** — Finding the nearest useful point on the route for a geographic observation.
  A projection also reports how far the observation lies off the route. [Route geometry](positioning-and-trust.md).
- **Kalman filter** — A calculation that predicts position and speed, then adjusts those estimates
  when a measurement passes its checks. [Route filter](filter-and-estimator.md).
- **Uncertainty / covariance** — Numbers describing how unsure the filter is about position and
  speed, and how their errors may be related. They are model estimates, not safety guarantees.
  [Route filter](filter-and-estimator.md).
- **Innovation gate** — A check that rejects a measurement when it differs too much from the
  current prediction relative to the expected uncertainty. [Route filter](filter-and-estimator.md).
- **Systematic drift** — Error that can accumulate while the model follows an incorrect speed or
  other persistent bias. The filter tracks an allowance for it separately from random uncertainty.
  [Route filter](filter-and-estimator.md).
- **Coarse fix** — A cell or network position with much less precision than trusted GPS. Several
  consistent fixes may support a small correction. [Cautious corrections](corrections.md).
- **OBD-II speed** — Vehicle speed read from a car diagnostic adapter. The app reads it and never
  writes to the car. [Estimator](filter-and-estimator.md).
- **IMU** — The phone's motion sensors, including its gyroscope and accelerometer. Recorded IMU
  evidence can suggest a stop, restart or turn. [Cautious corrections](corrections.md).
- **Checkpoint / delayed replay** — A saved estimator state that lets a late observation be applied
  at its original timestamp before later events are played again. [Estimator](filter-and-estimator.md).
- **p95** — An error value at or below which 95% of the measured samples fall. A synthetic replay
  p95 is not a real-drive accuracy claim. [Replay comparisons](build-and-replay.md).
- **Profiling** — Measuring where code uses time or memory. A host profile does not directly
  measure performance on Android. [Profiling guide](profiling.md).
- **Simulator** — A desktop program that feeds repeatable synthetic trips into the core. It helps
  compare behavior and performance changes without a phone. [Simulator guide](simulator.md).
- **Kani / bounded proof** — A tool and method for checking selected Rust rules across inputs
  within stated limits. Passing a bounded proof does not certify the entire navigator.
  [Verification guide](verification.md).
