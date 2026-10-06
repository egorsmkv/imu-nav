# Trips: history, recording and replay

[Documentation index](../README.md)

The app records a trip automatically while you navigate. Recordings contain your route, positions and sensor events; treat a shared file as private location data.

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
