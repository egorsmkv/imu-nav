# System GPS emulator tests

The `Android GPS instrumentation` workflow builds the F-Droid debug app and AndroidTest APK
once and runs the same APKs on disposable x86_64 Google APIs emulators:

| API | Android | Platform path |
| --- | --- | --- |
| 28 | 9 | Legacy `addTestProvider` overload |
| 31 | 12 | `ProviderProperties.Builder` |
| 35 | 15 | Modern provider and foreground-service restrictions |
| 36 | 16 | Current target SDK runtime |

The workflow selects affected app, core, routing, native and build inputs. Documentation-only
changes avoid builds and emulators; manual dispatch and merge queues run the suite. Matrix
failures do not cancel other API results. This suite is separate from host Kotlin coverage.

## What is exercised

Nine tests under `app/src/androidTest/kotlin/org/imunav/app/location/` exercise the production
provider and worker through the emulator's real `LocationManager`, AppOps and Binder:

- First publication with GPS explicitly disabled, then a second distinct fix. An actual GPS
  listener checks coordinates, uncertainty, speed, bearing, mock flag and monotonic sample time.
  No republishing retries conceal a dropped first sample.
- Denied mock AppOps, granted access, settings refresh, revocation during an active session,
  failed cleanup and successful cleanup after access is granted again.
- Disable/re-enable, repeated removal, platform removal before startup cleanup, invalid/idle
  guidance and explicit stop. Cleanup must restore the original GPS provider properties.
- The actual `NavService` started while an activity is resumed. The activity moves to CREATED;
  a listener must receive a fresh sample after that transition. Stopping the service must release
  the provider while retaining the remembered opt-in.
- Real OS process death: a debug-only bound process runs `MockLocationSharing`, persists
  ownership, publishes and kills itself. Instrumentation observes Binder death, rebinds, verifies
  a different PID, preserved opt-in, cleared ownership and restored GPS before supplying new
  guidance. A new real GPS fix must then arrive. This tests the production worker's process
  recovery contract; it does not simulate process death by merely reconstructing an object.

All coordinates and routes are synthetic. Fixtures use separate preference files except the
`NavService` test, which exercises AppGraph's worker on the disposable emulator. Fixture activity
and service are unexported and exist only in Debug APKs. Each test restores the mock AppOp and
global location switch, removes its listener/provider, and stops service/worker resources.

Android's platform service can return silently when mock AppOps is denied, including in Android
9 and 12. The adapter checks authorization before platform mutations and again before
acknowledging publication/cleanup, retaining the durable marker when cleanup cannot run.
GPS is enabled before its first sample is injected.

## Run locally

Use a **disposable emulator**, never a phone containing real navigation data. Tests enforce
the emulator hardware check before changing settings. Prepare the SDK/NDK/Rust targets listed
in [build and test](build_and_test.md), start the desired AVD and run:

```bash
./gradlew :app:connectedFdroidDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=org.imunav.app.location
```

Alternatively build both APKs with `:app:assembleFdroidDebug :app:assembleFdroidDebugAndroidTest`
and run `python3 tools/run_mock_location_tests.py --apk-directory app/build/outputs/apk --output
build/gps-instrumentation`. Use a directory containing only the app/test APK pair for this option.
The CI runner validates terminal instrumentation records and completion, failing on assertions,
crashes, skipped tests, duplicate records or missing classes/tests even if `adb` returns zero.

Per-API artifacts contain `results.xml`, raw instrumentation output, API number, logcat and
`dumpsys location`. Consult the workflow run for results at the exact commit under review;
the presence of these tests alone is not evidence that a given API passed.

## Physical-device and longer lifecycle checks

The emulator suite does not establish:

- GNSS chipset/HAL behavior, sensor accuracy, road navigation accuracy, proprietary fused
  location providers, or acceptance by any particular third-party application.
- OEM battery restrictions, long screen-off/Doze sessions or vendor-specific background kills.
- Automatic `START_STICKY` restart timing of the entire app, saved-trip restoration after a
  low-memory kill, reboot behavior, or force-stop behavior. The recovery test kills/rebinds the
  GPS worker process; it does not promise Android will restart a force-stopped application.
- UI selection of the mock app in Developer options. Tests configure the same AppOp through
  shell access; on a phone the user must select this installation themselves.

On a physical device, enable mock selection manually, start a synthetic/controlled trip, verify
fresh mock fixes in an independent consumer, move the app to the background, then test screen
off, trip end, disabling, permission revocation/regrant and an authorized process-kill/relaunch.
Check saved-trip restoration and original GNSS availability separately. Record device/OEM/API,
power restrictions, durations and actual outcomes. IMU Nav remains a research prototype, not
a safety system.

Platform references: [LocationManager](https://developer.android.com/reference/android/location/LocationManager),
[Android 9 LocationManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/core/java/com/android/server/LocationManagerService.java),
[Android 12 LocationManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.0.0_r1/services/core/java/com/android/server/location/LocationManagerService.java),
[emulator runner](https://github.com/ReactiveCircus/android-emulator-runner).
