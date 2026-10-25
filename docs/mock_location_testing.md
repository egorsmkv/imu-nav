# System GPS emulator tests

The `Android GPS instrumentation` workflow builds the F-Droid debug app and AndroidTest APK
once and runs the same APKs on disposable x86_64 Google APIs emulators:

| API | Android | Platform path |
| --- | --- | --- |
| 28 | 9 | Legacy `addTestProvider` overload |
| 31 | 12 | `ProviderProperties.Builder` |
| 35 | 15 | Modern provider and foreground-service restrictions |
| 36 | 16 | Current target SDK runtime |
| 35 / 16 KB | 15 | `google_apis_ps16k`; actual `PAGE_SIZE=16384` is required |

The workflow selects affected app, core, routing, native and build inputs. Documentation-only
changes avoid builds and emulators; manual dispatch and merge queues run the suite. Matrix
failures do not cancel other API results. This suite is separate from host Kotlin coverage.

The 16 KB image uses 6144 MB of emulator RAM; the other images use 4096 MB.
At 4096 MB, the 16 KB image's first-boot Google services exhausted guest memory
and Android's low-memory killer terminated instrumentation before any tests
completed. A `Process crashed` result still fails the job; inspect `logcat.txt`
to distinguish a low-memory kill from an application exception.

## What is exercised

Nine ordinary tests and two host-orchestrated recovery stages under `app/src/androidTest/kotlin/org/imunav/app/location/` exercise the production
provider and worker through the emulator's real `LocationManager`, AppOps and Binder:

- First publication with GPS explicitly disabled, then a second distinct fix. An actual GPS
  listener checks coordinates, uncertainty, speed, bearing, mock flag and monotonic sample time.
  Single-fix consumers run in a resumed activity with verified while-in-use fine location access;
  instrumentation alone does not grant that access on newer Android versions. No republishing
  retries conceal a dropped first sample.
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
- Whole-app saved-trip recovery: a separate invocation seeds a synthetic recorded trip and active
  GPS, the host force-stops the target, and a fresh instrumentation invocation verifies a new PID,
  TripManager restoration, remembered opt-in, fresh GPS after foreground relaunch and cleanup.
  The two stages are deliberately excluded from the ordinary package suite.

The same runner also requires a Graphics Path JNI (Rust port) load/iteration test, three saved-snapshot validation tests and one production search
restoration test across Activity recreation and rotation/window resize. The ordinary suite therefore
contains fourteen tests, followed by the two whole-app recovery stages. Foreground relaunch must
activate the saved trip only after the location service has been promoted; background application
creation must leave the engine inactive.

All coordinates and routes are synthetic. Fixtures use separate preference files except the
`NavService` and whole-app tests, which exercise AppGraph's worker on the disposable emulator.
Fixture activity and service are unexported and exist only in Debug APKs. Ordinary tests and the
paired recovery verification restore the mock AppOp and global location switch, remove their
listeners/providers, and stop service/worker resources. The host also cleans up failed runs.

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
  -Pandroid.testInstrumentationRunnerArguments.class=org.imunav.app.location.AndroidMockLocationProviderTest,org.imunav.app.location.MockLocationSharingTest,org.imunav.app.location.MockLocationProcessRecoveryTest
```

Alternatively build both APKs with `:app:assembleFdroidDebug :app:assembleFdroidDebugAndroidTest`
and run `python3 tools/run_mock_location_tests.py --apk-directory app/build/outputs/apk --output
build/gps-instrumentation`. Use a directory containing only the app/test APK pair for this option.
The Python runner also orchestrates the whole-app force-stop/relaunch stages. Do not select the
entire test package in an ordinary connected-test run: the seeding stage intentionally leaves
a trip active for its paired restart check. The CI runner validates terminal instrumentation records and completion, failing on assertions,
crashes, skipped tests, duplicate records or missing classes/tests even if `adb` returns zero.

Per-API artifacts contain `results.xml`, raw instrumentation output, API number, actual page size, ADB version/device
inventory, logcat and `dumpsys location`. The runner awaits ADB transport for at most 30 seconds
before collecting evidence and cleaning up; it never retries test invocations or GPS samples.
ADB output is decoded as UTF-8 with malformed bytes replaced by `�`, so non-UTF-8 logcat
messages cannot interrupt evidence collection or emulator cleanup. Nonzero ADB exits,
timeouts and instrumentation failures still fail the suite.
Missing evidence or failed cleanup also fails the suite. Consult the workflow run for results at the exact commit under review;
the presence of these tests alone is not evidence that a given API passed.

## Physical-device and longer lifecycle checks

The emulator suite does not establish:

- GNSS chipset/HAL behavior, sensor accuracy, road navigation accuracy, proprietary fused
  location providers, or acceptance by any particular third-party application.
- OEM battery restrictions, long screen-off/Doze sessions or vendor-specific background kills.
- Automatic `START_STICKY` restart timing, low-memory-kill scheduling, and reboot behavior.
  Whole-app restoration is verified after a host force-stop and explicit foreground relaunch;
  the test does not promise Android will automatically restart a force-stopped application.
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
