# Capture app performance on Android

[Documentation index](../README.md) · [Trips and recordings](TRIPS.md)

Every app build can create a local performance bundle. Open **Settings → Advanced →
Diagnostics → Record performance**, tap **Start capture**, use the app, then tap **Stop and prepare
ZIP**. Capture works during navigation and while using the map, search or settings. It continues
until you stop it or the app process ends. Tap **Share ZIP** and choose a destination in Android's
share menu. The app does not upload the ZIP to the cell-sharing server.

The ZIP contains:

| File | What it records |
| --- | --- |
| `manifest.json` | App/device version, capture times, completion state and dropped-entry counts. |
| `methods.trace` | Sampled Kotlin/Java method stacks for this app process; present only after a clean stop. |
| `memory.csv` | Process Java heap use, native heap allocation, proportional set size and CPU time sampled every five seconds. |
| `native-timings.json` | Call counts, total time and longest call for selected Rust JNI operations. |
| `logs.txt` | App diagnostic log lines emitted during the capture, with credential-shaped values redacted. |
| `trip-events.rec` | Trip recorder events produced during the capture, if navigating. |

`trip-events.rec` is an excerpt. If capture starts partway through a trip it excludes earlier
events, so it is **not** a standalone replay recording. Share the complete trip separately from
History if replay is needed. No older trip or Logcat history is copied into the bundle.

The method trace samples managed code at 10 ms intervals with a 16 MiB buffer. It does not show
Rust internal call stacks. The native report times selected JNI operations, including the core work
they call; it is not a full native CPU profile.

Recording changes timing, so use repeated captures
for comparisons. Event and log files have size limits, and the manifest reports dropped entries.
If Android stops the app mid-capture, the next launch offers a partial ZIP with the flushed files;
the unclosed method trace is omitted.

The app keeps at most three ZIPs and removes older ZIPs when
preparing another capture. Android may clear them from app cache earlier.

**Before sharing:** trip events and logs may contain precise locations and sensor data. Check the
destination carefully. IMU Nav remains a research prototype, not a safety system.
