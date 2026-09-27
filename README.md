# Blind Driver (open source)

Car navigation that keeps working when GPS is **jammed or spoofed**. An open Kotlin
implementation of the approach used by the BlindDriver app: instead of trusting GPS, it
classifies every fix, and when GPS is unusable it dead-reckons **along the planned route** using
the phone's IMU, network location and map knowledge.

## How it works

The whole navigation state is one number, `s` — metres travelled along the route polyline.
Constraining the car to the route removes cross-track error and heading drift entirely; the
remaining along-track drift is repeatedly corrected by landmarks.

```
 GPS / NET / FUSED ─► TrustClassifier ─► GOOD / SUSPECT / BAD ─┐
 GnssStatus + AGC ──┘  (spoof & jam)                            │
 rotation vector,  ─► heading, vertical yaw rate, gyro bias,    │
 gyro, lin. accel      MotionDetector (stop / resume)           │
                                                                ▼
                 NavigationEngine.tick() every 500 ms: route cursor s
                 GPS usable → project fix onto route (smoothed)
                 else       → s += v·dt·motionFactor
                 corrections: turn-hold + gyro confirm, turn-signature matching,
                 compass snap, stop-at-signal snap, network band / catch-up / pull-back
                 deviation: GPS off-route, U-turn, missed turn, network off-route
```

| Component | File | What it does |
|---|---|---|
| Trust classifier | `core/.../gnss/TrustClassifier.kt` | Hard checks (BAD): mock, outside service area, altitude, >150 km/h, accuracy, clock skew, impossible jump, duplicate time, frozen coordinates, hard jamming, no satellites. Soft checks (SUSPECT): accuracy jump, speed ≠ displacement, disagreement with network fix, weak jamming, few sats, low C/N0, **flat C/N0 across satellites** (spoofer signature), GPS bearing vs. compass. |
| Jam detector | `TrustClassifier.kt` (`JamDetector`) | AGC hysteresis: enter < −12 dB, leave after 15 s > −8 dB. Re-injects A-GPS data on recovery. |
| Motion detector | `core/.../imu/MotionDetector.kt` | Stop = quiet accelerometer (mean & σ) or quiet gyro for 1.5 s; resume after 0.7 s of vibration; speed ramps 0.15→1 over 8 s after a stop. Integrates vertical yaw rate for turns. |
| Speed | `core/.../speed/Speed.kt` | Inverse-variance fusion of last GPS speed (σ grows with age), route prior (speed limit × learned driver ratio, or router's modelled speed), and **network speed** from a weighted linear regression of route-projected cell/Wi-Fi fixes. Speed caps near traffic signals / speed bumps. |
| Engine | `core/.../nav/NavigationEngine.kt` | Main loop, turn hold, corrections, deviation offers, uncertainty (`25 + 0.08·distance`, capped 350/600 m), voice announcements. |
| Network gate | `core/.../nav/NetworkTracker.kt` | Feasibility gate for network fixes (reachable at 150 km/h) with re-anchoring. |
| Android glue | `app/` | `SensorHub` (LocationManager, GnssStatus, GnssMeasurements/AGC, sensors), `OsrmRouter`, foreground `NavService`, TTS, Compose + MapLibre UI. |

All thresholds live in `core/.../Tuning.kt` (defaults = factory preset) and `TrustConfig`.

### Turn hold — the key trick
When the dead-reckoned marker reaches the next real turn (≥ 35°) it is parked 5 m before it.
It is released when the **gyro confirms the turn** (same direction, ≥ 60 % of the angle) → snap
to turn + 20 m; when two good network fixes are clearly past the turn; after a timeout; or — if
you drive on without turning — a "missed turn" reroute countdown starts. Independently, any clear
gyro turn is matched against route turns 400 m behind … 300 m ahead (turn-signature map matching).

## Build

Requirements: JDK 17+, Android SDK 36.

```bash
./gradlew :core:test          # pure-Kotlin engine tests, incl. simulated GPS-denied drives
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed from `keystore.properties` in the project root (gitignored):

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=blinddriver
keyPassword=...
```

Keep a backup of the keystore: Android only installs updates signed with the same key.
Without `keystore.properties`, `assembleRelease` produces an unsigned APK.

Usage: long-press the map to set a destination, tap **Start**. The **No GPS** chip ignores GPS
to try dead reckoning with real sensors; **Log** shows the engine's decisions
(`turn_hold`, `turn_snap`, `net_back`, `blind_deviation`, …). Trip logs are written to
`files/logs/` in app storage.

## Status and limitations

- Routing uses the public OSRM demo server (online, no traffic signals, rarely speed limits).
  For real use run your own OSRM, or add an offline router (e.g. GraphHopper with a Ukraine
  extract) implementing `Router` and filling `Route.signals` / `maxspeedKmh`.
- The service area defaults to a coarse Ukraine outline (`ServiceArea.UKRAINE_COARSE`); fixes
  outside it are rejected as spoofed. Change it in `AppGraph` to use the app elsewhere.
- Free-drive (no route) dead reckoning, speed cameras, saved places and settings UI are not
  implemented yet; `Tuning` already carries the camera parameters.
- The engine differs from the analysed app in a few places: the gyro bias estimate is applied to
  turn integration, and projections compute exact arc-length.
- Not road-tested. Treat as a research prototype, never as a safety system.

## Provenance

Written from scratch based on a behavioural analysis of BlindDriver 0.4.0 (algorithms,
thresholds and log vocabulary). No original source code, UI or assets are included; the only
data carried over is the coarse Ukraine border polygon (public geographic coordinates).
