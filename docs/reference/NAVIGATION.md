# Navigation and positioning

> Detailed reference. For easy steps, see the [guide](../NAVIGATION.md) or the [glossary](../GLOSSARY.md).

## How it works

The whole navigation state is one number, `s` — metres travelled along the route polyline.
Constraining the car to the route removes cross-track error and heading drift entirely; the
remaining along-track drift is repeatedly corrected by landmarks.

```text
 GPS / NET / FUSED ─► TrustClassifier ─► GOOD / SUSPECT / BAD ──┐
 GnssStatus + AGC ──┘  (spoof & jam)                            │
 rotation vector,  ─► heading, vertical yaw rate, gyro bias,    │
 gyro, lin. accel      MotionDetector (stop / resume)           │
 OBD-II car speed ──► replaces the speed estimate (optional)    │
 barometer ─────────► height trace for terrain matching         │
                                                                ▼
                 NavigationEngine.tick() every 500 ms: route cursor s
                 GPS usable → project fix onto route (smoothed)
                 else       → selected fallback: dead reckoning, cell towers, or hybrid
                 hybrid corrections: turn-hold + gyro confirm, turn-signature matching,
                 compass snap, stop-at-signal snap, terrain (barometer ↔ route heights),
                 network band / catch-up / pull-back
                 deviation: GPS off-route, U-turn, missed turn, network off-route
```

| Component                | File                                               | What it does                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ------------------------ | -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Trust classifier         | `core/.../gnss/TrustClassifier.kt`                 | Hard checks (BAD): invalid coordinates, mock, implausible altitude, >150 km/h, accuracy, clock skew, impossible jump, duplicate time, frozen coordinates, hard jamming, no satellites. Valid positions are accepted worldwide. Soft checks (SUSPECT): accuracy jump, speed ≠ displacement, disagreement with network fix, weak jamming, few sats, low C/N0, **flat C/N0 across satellites** (spoofer signature), GPS bearing vs. compass.                                                                                                            |
| Jam detector             | `TrustClassifier.kt` (`JamDetector`)               | AGC hysteresis: enter < −12 dB, leave after 15 s > −8 dB. Re-injects A-GPS data on recovery.                                                                                                                                                                                                                                                                                                                                                                                                      |
| Motion detector          | `core/.../imu/MotionDetector.kt`                   | Stop = quiet accelerometer (mean & σ) or quiet gyro for 1.5 s; resume after 0.7 s of vibration; speed ramps 0.15→1 over 8 s after a stop. Integrates vertical yaw rate for turns.                                                                                                                                                                                                                                                                                                                 |
| Speed                    | `core/.../speed/Speed.kt`                          | Inverse-variance fusion of last GPS speed (σ grows with age), route prior (speed limit × learned driver ratio, or router's modelled speed), and **network speed** from a weighted linear regression of route-projected cell/Wi-Fi fixes. Speed caps near traffic signals / speed bumps.                                                                                                                                                                                                           |
| Engine                   | `core/.../nav/NavigationEngine.kt`                 | Main loop, turn hold, corrections, deviation offers, uncertainty (`25 + 0.08·distance`, or `0.02·distance` with OBD-II speed, capped 350/600 m), voice announcements.                                                                                                                                                                                                                                                                                                                             |
| Native navigation core   | `native/nav-core/`, `native/nav-jni/`              | Rust GPS trust firewall, AGC jamming hysteresis, route projection, network/cell reachability gating, weighted speed regression and inverse-variance speed fusion, plus `[s, speed]` covariance with Joseph updates, innovation gating and a separate systematic-drift safety allowance. Android uses these native decisions through opaque JNI handles; the estimator runs in comparison mode while replay data validates its tuning. JVM replay keeps Kotlin implementations for parity testing. |
| Car speed (OBD-II)       | `core/.../obd/Elm327.kt`, `app/.../obd/ObdLink.kt` | Reads vehicle speed (PID `010D`) 5× per second from a paired Bluetooth ELM327 adapter. While fresh it replaces the speed guess; a GPS/OBD scale factor is learned while GPS is trusted.                                                                                                                                                                                                                                                                                                           |
| Terrain matching         | `core/.../nav/ElevationMatcher.kt`                 | Keeps the last 1.5 km of (odometer, barometric height) and slides it along the route's elevation profile, trying odometer scales 0.75–1.35. Only a clear fit counts: ≥ 4 m relief, ≤ 2.5 m RMS misfit, and every other position ≥ 1.8× worse. It snaps the marker there (never across an unconfirmed turn) or confirms it, and shrinks the uncertainty.                                                                                                                                           |
| Network gate             | `core/.../nav/NetworkTracker.kt`                   | Feasibility gate for network fixes (reachable at 150 km/h) with re-anchoring.                                                                                                                                                                                                                                                                                                                                                                                                                     |
| Android glue             | `app/`                                             | `SensorHub` (LocationManager, GnssStatus, GnssMeasurements/AGC, sensors), `OsrmRouter`, foreground `NavService`, TTS, Compose + MapLibre UI.                                                                                                                                                                                                                                                                                                                                                      |
| Offline cell positioning | `core/.../cells/Cells.kt`, `app/.../cells/`        | Scans visible cells (LTE/GSM/UMTS/NR) every 5 s, looks them up in an on-device SQLite tower database and computes a weighted-centroid fix (serving cell, signal strength and cell size as weights; outlier towers dropped; LTE timing advance bounds single-cell accuracy). Works without Google services or internet, and is immune to GNSS jamming. Fixes enter the engine as `CELL` and stand in for network location.                                                                         |

## Turn hold — the key trick

When the dead-reckoned marker reaches the next real turn (≥ 35°) it is parked 5 m before it.
It is released when the **gyro confirms the turn** (same direction, ≥ 60 % of the angle) → snap
to turn + 20 m; when two good network fixes are clearly past the turn; after a timeout; or — if
you drive on without turning — a "missed turn" reroute countdown starts. Independently, any clear
gyro turn is matched against route turns 400 m behind … 300 m ahead (turn-signature map matching).

## Car speed and hills

Two optional inputs make dead reckoning much more accurate (**Settings → Everyday settings → Car speed and hills**):

- **OBD-II adapter.** A cheap Bluetooth ELM327 dongle in the car's diagnostic socket (under the dashboard,
  every petrol car since ~2001 and diesel since ~2004). Pair it in the phone's Bluetooth settings, switch
  on _Car speed from OBD-II adapter_, pick it and tap _Test connection_ (ignition on). The app then connects
  when a car trip starts, reads the speed 5× a second and reconnects by itself; the status pill shows
  _Estimated + car speed_. Only the standard speed request is sent: nothing is written to the car. Classic
  Bluetooth adapters work; BLE-only ones do not. Android 12+ asks for the _Nearby devices_ permission.
- **Barometer + road heights.** Phones with a barometer measure height changes to well under a metre.
  With a routing pack built with `--elevation`, the engine compares them with the route's hills and
  corrects the position every few seconds on hilly roads (flat roads give no correction by design).

Both are recorded in trip recordings (`V` and `B` lines), so the replay tool reproduces them.

## Walking without GPS

On foot the engine replaces the car speed model with the phone's **step detector**: speed = steps
per second × stride, and the stride (0.72 m to start) is learned while GPS is trusted. Car-only
corrections are off (turn hold, gyro turn matching, compass snap, U-turn and missed-turn detection,
traffic-signal and speed-bump rules — they assume a phone fixed in a car holder), off-route and
arrival distances are tighter (`Tuning.forWalking()`), and maneuvers are announced at 150 / 50 / 15 m.
Cell-tower and network corrections apply when the Hybrid fallback is selected. Step counting needs the _Physical activity_
permission (asked when choosing _Walk_); without it or without a step sensor, a 1.3 m/s pace is
assumed while the phone is moving. Walk recordings contain the steps, so the replay tool works for them too.

Usage: search for the **From** and **To** addresses in the route panel, or long-press the map to
choose a destination. The proposed route is drawn on the map as soon as its start and destination
are known; review it, then tap **Start**. The current trusted position is used when **From** is not
changed. Planning uses GOOD GPS up to 5 seconds old, otherwise network/cell location up to 30 seconds
old; expired or future-dated fixes cannot supply an automatic start. A manual start takes precedence.
If there is no fresh automatic position, search for the starting address or pan the
crosshair onto your position and tap **Start here** first. Tap the status pill for
positioning diagnostics (satellites, spoofing reasons, cells, _Simulate GPS loss_, trip log); the gear
opens **Settings**. Its four expandable groups keep common controls separate from maps, cell-tower
data and advanced tools. **Everyday settings → Navigation without GPS** selects dead reckoning only, cell-tower
positions only (held between scans), or the recommended hybrid that dead-reckons continuously and
uses cell/network fixes to constrain drift. Trusted GPS remains preferred in all three modes.
Each group starts with a short explanation; the map group also distinguishes routing packs (which
calculate routes) from map packs (which draw streets). Pack imports can be cancelled during extraction
and validation. Once replacement starts, it finishes or rolls back to the previous pack, and reports
the actual result. A new import waits until cleanup finishes. Routing, map matching and address
search retain their pack resources until each operation completes, before replacement can close them.
The map opens at the phone's last GPS position (spoofed or out-of-area fixes are ignored) or, if set in
**Settings → Everyday settings → Map start**, at a fixed place (typed coordinates, your position or the map centre).
**Settings → Everyday settings → Navigation without GPS → Haptic feedback** disables both navigation
vibrations and app tap feedback. The preference is saved, applies immediately and cancels active
navigation vibrations; it remains visible even on devices without a vibrator. When enabled, tap
feedback still follows Android's touch-feedback setting. Vibration service calls run off the UI thread.
The interface and voice follow the phone's language (Ukrainian, English or Russian) unless changed in
**Settings → Everyday settings → Language**, and the phone's light/dark theme. Spoken directions can be disabled under
**Settings → Everyday settings → Navigation without GPS**. The phone vibrates as well, with a different
pattern for an upcoming turn (one buzz), the turn itself (two), leaving the route (three short), GPS lost
(long + short), GPS back, a new route and arrival, so alerts can be told apart without looking. Turn that off with
**Vibrate on turns and alerts** in the same group. Start, Stop, the Car/Walk selector and choosing a destination
also give a short tap of feedback, following the phone's touch-feedback setting. Map and settings layouts adapt to portrait and landscape.
Map panels scroll when text does not fit, preserving space for zoom controls; long position labels
shorten to keep History and Settings accessible, including with enlarged system text. In short windows,
map warnings and controls scroll separately from the route panel; the compass stays clear of the zoom buttons.
Action buttons, travel modes, legends and trip statistics wrap to the next line when needed. Settings,
address search and the trip log keep content above the keyboard, and the setup and log control panels
limit their height so the main content remains reachable. System font scaling is preserved. Text trip logs are written to
`files/logs/`, trip recordings to `files/trips/` in app storage. Log writes and trip boundaries share one worker, so queued messages stay with their original trip.
Log files have unique suffixes and buffered writes flush within five seconds (or at rotation/trip end); an abrupt process kill can lose the last buffered lines. The in-app trip-log viewer shows timestamps, highlights problems, and can search,
filter, follow, copy or share the latest diagnostic events as a text file.
