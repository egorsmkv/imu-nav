# Kotlin application review — 9 October 2026

Base: `1102c50eea10c26999a1186fd5ba9f259a531809` (`main`).

This is a cross-module static review with a small, uncompiled patch, not a completed
end-to-end validation or a measured performance result. Applied skills:
`optimize-kotlin-performance`, `optimize-kotlin-concurrency`, `optimize-kotlin-stdlib`.
Repository instructions and SECURITY.txt were read. Dependencies and public APIs are unchanged.

## Local changes

1. **Hidden tower layer could reappear.** In
   `app/src/main/kotlin/org/imunav/app/cells/CellTowerLayerCoordinator.kt`, `setVisible(false)`
   cleared the state without cancelling the pending query. A query already suspended in
   `withContext(Dispatchers.IO)` could return and replace that empty state. Cancel its job
   before clearing the layer. Returning from the IO dispatcher then respects cancellation.
   This prevents publication; it does not interrupt SQLite work already executing.
2. **Repeated profile allocations during terrain matching.** In
   `core/src/main/kotlin/org/imunav/core/nav/ElevationMatcher.kt`, every eligible position/scale
   candidate allocated a profile `DoubleArray`. A per-call `Trace` now owns one reusable
   profile. Each successful fit fills every element before reading it. No array escapes into
   a candidate; arithmetic order, candidate ordering and confidence gates are unchanged.
   With a full 2,000 m search interval, 5 m spacing and 13 scales, there are 5,213 candidate
   attempts. Eligible attempts previously allocated separate profile arrays. This is a
   source-derived allocation count, not a measured ART allocation or latency result.
3. **Duplicate full-route projection.** In
   `core/src/main/kotlin/org/imunav/core/route/Route.kt`, a search already spanning all segments
   could repeat the identical global scan when its offset exceeded the fallback threshold.
   This occurs in hazard projection, whose threshold is zero. Skip the second scan only when
   the first interval already covers all segments. Partial-interval fallback is unchanged.
   This removes redundant segment visits; it does not establish an end-to-end speedup.

## Remaining findings, ordered for follow-up

| Priority | Evidence | Consequence and next check |
| --- | --- | --- |
| P2 | `PlaceSearch.search` / `OsrmRouter.route` dispatch blocking `Http.getText`; `Http.getText` uses `Call.execute` without coroutine cancellation wiring | Superseded requests can continue network work after their coroutine is cancelled. Test delayed responses and repeated query/route changes; adapt the cancellation-aware approach already used by `SyncApi` while preserving proxy/client configuration. |
| P2 | `SensorHub` delivers events on the main looper; `AppGraph` calls `InertialShadow.onSensor` synchronously; ESKF prediction constructs multiple 15×15 matrices | The opt-in inertial experiment performs substantial arithmetic and allocations on the UI thread. Frame stalls are a hypothesis, not observed here. Profile the benchmark APK with the experiment on/off before changing state ownership or matrix code. |
| P2 | `TripManager` uses `newSingleThreadExecutor`; `RecordingSession.record` enqueues a closure for every event; `TripFileLog` also queues writes | No explicit queue bound/backpressure is visible in these paths. Slow storage can retain sensor events and increase shutdown delay. Test a deliberately stalled writer and queue depth. Do not silently drop replay events; define an explicit recording-failure policy first. |
| P2 | `RoutePreviewCoordinator.update` catches routing via `runCatching` and handles all failures as preview errors | Cancellation of the parent scope, without a generation change, can publish a failure. Superseding requests are already generation-protected. Add a parent-cancellation regression and rethrow `CancellationException`. |

These are code-level findings and test targets. No device freeze, OOM, network traffic count or
navigation accuracy regression was measured in this environment.

## Review coverage and limits

The inventory contains 256 Kotlin source/test files. This was not a line-by-line audit of all
256 files. Reviewed paths include the following; findings must not be interpreted as assurance
that unreviewed code is correct.

| Area | Reviewed |
| --- | --- |
| App | Navigation request ownership; route previews; phone/car search; map sources and car update gating; tower viewport queries; sensor ingress; bookmark persistence; pack resource access; account-sync scheduling |
| Core | Route projection; terrain matching and its engine call site; selected IMU/ESKF paths; recording/logging queues; HTTP dispatch |
| Routing | GraphHopper route/map-match entry points and lifecycle locking through `OfflineRouting` |
| Replay | CLI dispatch and recording loading; available replay/JNI regression suites inventoried |
| Native/server | Build requirements, JNI ownership boundary, lock/blocking-work entry points and test layout inspected; no comprehensive Rust audit or Rust execution |
| Integration | Gradle configuration, Android manifest and Kotlin CI workflow inspected; Python tooling and web player tests executed |

Existing protections observed include generation tokens for navigation, prepared-resource cleanup,
exclusive pack replacement, background route feature construction, visibility-aware car map updates,
and serialized recent-search history. These were inspected, not revalidated on Android.

## Executed validation

| Check | Result |
| --- | --- |
| `./gradlew check` | Blocked before configuration: Gradle 9.6.1 download failed with `Network is unreachable` |
| `python3 -m unittest discover -s tools/tests` | PASS — 36 tests |
| `node --test server/tests/web/trip-player.test.cjs` | PASS — 2 tests |
| `git diff --check` | PASS |

The Python/JavaScript tests do not validate the Kotlin patch. No Kotlin compiler, Gradle
distribution, Android SDK/device or Cargo executable was available in the inspected locations/PATH.
Kotlin tests, detekt, Spotless, Android Lint, JNI tests, Rust tests, release APK and device
profiling have **not** passed in this session because they could not be run.

## Required completion checks

1. Run `./gradlew spotlessApply` and `./gradlew check` in a provisioned environment.
2. Verify `TerrainAndVehicleSpeedTest` and `ReplayTest`; preserve the repository's replay
   reference (`p95=20 max=25 rms=10 m`) rather than assuming it still holds.
3. Exercise full-route and partial-route projection, off-route hazards, repeated points,
   and terrain candidates rejected before a profile is fully filled.
4. Reproduce the tower scenario with a delayed query: start query, disable towers, release
   query, verify the layer stays empty, re-enable and verify fresh content is published.
5. Build the release/benchmark APK and test on an emulator/device with a real routing pack.
   Measure matched workloads, main-thread timing, ART allocations and replay accuracy before
   publishing a performance claim. Desktop microbenchmarks alone are insufficient.

The initial review left changes local; the user subsequently authorized a pull request.
