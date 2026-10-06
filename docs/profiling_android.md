# Capture app performance on Android

[Documentation index](../README.md) · [Trips and recordings](trips.md)

Every app build can create a local performance bundle. Open **Settings → Advanced →
Diagnostics → Record performance**, tap **Start capture**, use the app, then tap **Stop and prepare
ZIP**. Capture works during navigation and while using the map, search or settings. It continues
until you stop it or the app process ends. Tap **Share ZIP** and choose a destination in Android's
share menu. You can also choose **Upload to sharing server** after signing in. Every upload requires confirmation; nothing is sent automatically.

The ZIP contains:

| File | What it records |
| --- | --- |
| `manifest.json` | App/device version, build flavor/type and source revision, capture times, rendering policy, completion state and dropped-entry counts. |
| `methods.trace` | Sampled Kotlin/Java method stacks for this app process; present only after a clean stop. |
| `memory.csv` | Java/native heap counters and CPU time every five seconds; detailed process memory at start, every 15 seconds and at stop. |
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
destination carefully.

## Reading schema 2 captures

`memory.csv` keeps the original five columns and adds Android memory-summary categories and
`memory_sample_duration_ms`. PSS and summary columns are empty on cheap samples. Do not fill
these gaps with zero or treat a previous PSS value as a new measurement. Older schema 1 archives
have PSS on every sample and remain useful; inspect `manifest.json` before comparing formats.

The `summary_*_kb` columns use Android's memory-summary definitions, including private Java,
native, graphics, code, other and stack memory, and the system contribution. They are not separate
PSS measurements. Java heap use, native allocation and total PSS overlap: do not add them together.
Memory-sampling duration helps identify capture overhead on slower devices.

The manifest records the effective map FPS, camera animation, prefetch setting and device RAM
classification under `start.rendering`. This is a snapshot at capture start. Build source revisions
come from CI, `-PsourceRevision=...`, or local Git when available; a revision alone does not identify
uncommitted changes. Keep the exact APK and its matching R8 `mapping.txt` for every comparison.
CI APK artifacts include mappings by build variant. A mapping from a later build cannot reliably
explain obfuscated methods in an earlier trace.

## Comparing changes

Use the same device, pack, actions and power conditions. Compare five paired runs and separate
cold route planning from warm runs. Both builds must use the same capture instrumentation;
otherwise reduced profiling overhead can look like an application improvement.

Compare process CPU, peak and settled PSS, and frame timing. Repeat map/settings/history transitions
and background/resume to investigate retained memory. A short rise while loading tiles or touching
memory-mapped routing data does not establish a leak. Managed traces omit native internal stacks;
use a system/native profile when allocation ownership remains unclear.

## Map rendering on constrained phones

Phones that Android identifies as low RAM, or with at most 4 GiB of physical RAM, use a 15 FPS map
cap with no automatic camera glide or lower-resolution tile prefetch in Auto, Balanced and Battery
saver modes. This also applies when Auto selects faster sensor sampling on a charger. Detection runs
once off the main thread; existing rendering settings apply until it finishes.

**Max accuracy** explicitly restores normal rendering settings. Sensor sampling and navigation
accuracy continue to follow the selected power profile. Reduced prefetch can leave newly visited
areas blank longer while their tiles load. The same policy applies to the phone, trip history and
Android Auto maps.

Hidden maps retain their view but defer style, geometry and camera updates until visible. Android
memory-pressure callbacks release MapLibre resources without deleting offline packs or trip data.

## Choose a lower map frame rate

Open **Settings → General → Battery → Map frame rate** and choose **Auto**, **10**, **15**,
**20**, or **30 FPS**. Auto uses the current power and device policy. A numbered option sets
an upper limit; power saving or device limits can reduce it further. The current limit appears
below the choices.

The choice takes effect immediately on the navigation map, trip history map and Android Auto.
It is remembered on this device and is not synced to other phones. Lower FPS can save battery
but makes movement less smooth. Sensor rates and navigation accuracy stay the same.

## Upload a profile to the sharing server

After **Stop and prepare ZIP**, choose **Upload to sharing server**. The app fetches the server’s
current privacy notice and shows the destination before asking for confirmation. Diagnostics uploads
must be enabled by the server administrator. This action does not enable automatic trip diagnostics.

Uploads include the whole ZIP, including any precise locations, sensor events, method trace and
logs. Interrupted captures can also be uploaded. You and the server’s administrators can download
it under **Diagnostics** (`/debug`). Use that page to delete a recording. Withdrawing diagnostics
consent or deleting your account also removes uploaded profiles. Profiles expire after 30 days.

Uploads are limited to 48 MiB per ZIP and share the diagnostics quota of 256 MiB and 100 sessions
per account. Retrying the same ZIP does not create another copy. Leaving the screen or changing
accounts cancels the request; if the server already accepted it, it can still appear in Diagnostics.
The local ZIP remains available for sharing.
