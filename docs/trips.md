# Trips: history, recording and replay

[Documentation index](../README.md)

The app records a trip automatically while you navigate. Recordings contain your route, positions and sensor events; treat a shared file as private location data.

Tap **Stop** to save the ride in **History**, including short rides or rides without movement.
The recording is closed and saved on the phone in the background.

## Review or share a trip

1. Tap the clock icon on the map to open **History**.
2. Open a trip to see its tracks, distance, time without GPS and uncertainty.
3. Tap the share icon to send a `.zip` archive through Android's share menu. Open the ZIP to get a plain `.rec` recording. The original stays on the phone.

If the app is killed during a trip, starting it again within three hours can restore the route and recording. The estimate becomes less certain for the missing time.

The diagnostic log can also contain precise locations and cell tower data. When you share visible log events from the log screen, the app warns you before opening Android's share menu.

## Manage diagnostic logs

Open **Settings → Advanced → Logs** to see the number of local text log files and their disk usage.
Tap **Refresh** for an updated measurement. These settings apply only to this device.

Choose a total size limit of 25, 100 or 250 MB, and optionally keep files for 7, 30 or 90 days.
Both defaults keep files until you clear them. Selecting a limit immediately removes older completed
files as needed. Cleanup also runs while writing logs and when a trip ends. The current file stays
open until rotation or trip end, so usage may temporarily exceed the selected limit.

**Clear logs** asks for confirmation, deletes local text log files and clears recent messages.
During navigation, logging continues in a new file. Saved trip recordings, exported copies and
server diagnostics have separate storage and are kept.

## Send a diagnostic trip to your server

1. Sign in under **Settings → Cell towers → Sharing server**.
2. Ask the server administrator to enable uploads on **Admin → Diagnostics**.
3. Read the selected server notice and grant separate diagnostics consent in **Settings → Diagnostics → Developer diagnostics** before starting a trip. Turning it off queues server erasure and stops local capture immediately. The app sends replay events, trip log lines and basic app/device settings while you navigate. It uses Wi-Fi or mobile data.
4. Watch **Upload status** in Settings. If the connection drops, the app keeps a bounded local queue and retries. A full queue or server quota can leave the server copy incomplete.
5. Open **My account → Developer diagnostics** on the server to review, download or delete your sessions. Administrators can inspect submitted sessions. Sessions expire after 30 days.

The recording contains precise location and sensor data. Turn the switch off to stop capture and discard unsent batches. The app requests deletion of sessions already received; if offline, it retries after sign-in and connectivity return. Passwords and account tokens are not part of the upload.

For a local performance capture that you share yourself, see [Capture app performance on Android](profiling_android.md).

## Replay a recording on a computer

1. Extract the `.rec` file from a shared ZIP, copy your own `.rec.gz` recording from app storage, or use a directory of recordings.
2. Run the replay tool from the project root:

   ```bash
   ./gradlew :replay:run --args="path/to/trips --hide-gps-after 60 --out replay-out"
   ```

3. Open `replay-out/summary.txt` for error totals and the CSV or GeoJSON files for the route comparison.

Replay is a research check, not proof that a live drive will be safe. The [detailed trips reference](reference/trips.md) explains native comparison, restoration and all report options. See the [glossary](glossary.md) for terms such as replay and uncertainty.

## Browser trip history

See [private browser trip history](server/trip_history.md) for manual uploads, playback, storage limits, consent and deletion.

## Recovery and replay input rules

The replay reader accepts plain `.rec` text and gzip recordings. If gzip reading fails, it
retains decompressed events only through the last complete newline; a cut numeric field can
still look valid and must not become a different event. An incomplete gzip header yields no
events. Clean input may omit its final newline. Repair rewrites recovered events before a
new gzip member is appended. Recovery cannot reconstruct bytes that were never flushed, and
a checksum failure does not establish that recovered data is authentic.

Unknown event types, malformed numeric fields, non-finite numbers (including Float overflow),
partial IMU vectors and unpaired waypoint coordinates are skipped. Empty optional measurements
remain supported. This is format validation, not a replacement for the positioning trust
classifier. Legacy recordings without arrival metadata are sorted by measurement elapsed time, preserving
file order for ties. New recordings follow arrival order as described below.

## Arrival-aware replay

New Android recordings place `# arrival_ms=<elapsedRealtime milliseconds>` immediately before
each event. The clock is captured when the event enters the recording session, before the
background I/O queue. Event payload timestamps remain measurement times; replay must not replace
them, because freshness, duplicate rejection and sensor integration depend on those values.
The metadata is a comment, so older readers can still parse the event lines, but cannot reproduce
arrival timing. No format-version bump or new event type is required.

Kotlin replay, native comparison and GPS track extraction use file order and arrival time when
metadata is present. Inertial comparison also uses the arrival clock while preserving its legacy
file-order behavior for old recordings. Equal arrival times retain file order. For mixed files
(e.g. a legacy trip repaired and appended by a new app), missing arrival times fall back to
measurement time; the schedule never moves backward. Repair preserves arrival metadata, and a
malformed or unknown event consumes its preceding metadata rather than attaching it to the next
event.

Legacy recordings cannot recover delays that were never recorded. Replay still uses its existing
500 ms tick convention; this change reproduces input delivery order, not exact Android callback
or navigation-tick scheduling. Arrival metadata records an elapsed clock, not an independent
wall-clock history.
