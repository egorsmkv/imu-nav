# Trips: history, recording and replay

The app records a trip automatically while you navigate. Recordings contain your route, positions and sensor events; treat a shared file as private location data.

## Review or share a trip

1. Tap the clock icon on the map to open **History**.
2. Open a trip to see its tracks, distance, time without GPS and uncertainty.
3. Tap the share icon if you want to send a separate `.rec.gz` copy through Android's share menu. The original stays on the phone.

If the app is killed during a trip, starting it again within three hours can restore the route and recording. The estimate becomes less certain for the missing time.

## Replay a recording on a computer

1. Copy the `.rec.gz` file from a trip you own, or use a directory of recordings.
2. Run the replay tool from the project root:

   ```bash
   ./gradlew :replay:run --args="path/to/trips --hide-gps-after 60 --out replay-out"
   ```

3. Open `replay-out/summary.txt` for error totals and the CSV or GeoJSON files for the route comparison.

Replay is a research check, not proof that a live drive will be safe. The [detailed trips reference](reference/TRIPS.md) explains native comparison, restoration and all report options. See the [glossary](GLOSSARY.md) for terms such as replay and uncertainty.
