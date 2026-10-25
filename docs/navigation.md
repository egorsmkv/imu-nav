# Navigation and positioning

[Documentation index](../README.md)

The app tries to use trusted GPS first. When GPS is unreliable, it estimates progress along your planned route from phone sensors, cell towers and map information.

## Start a trip

1. In **Where to?**, choose **Car** or **Walk**.
2. Search for a destination or long-press the map. The app uses your current position as the start when it has a recent usable fix.
3. If no usable position is available, search for a starting address. Selecting an address in **From** centres the map on that place and zooms in to street level. You can also move the map crosshair to your position and tap **Start here**.
   If Android Location is switched off (for example when you navigate by cell towers and OBD-II only), a hand-placed start is enough to begin a trip.
   The "Location is off" warning offers **Don’t show again**; to see it again, turn off **Settings → General → Map display → Hide the “Location is off” warning**.
4. To pass through other places on the way, tap **Add stop** in the route panel (up to five stops), or long-press the map once a destination is set and choose **Add as stop**. Stops appear in driving order between **From** and **To**; tap a stop to replace it, or its × to remove it. Reroutes keep the stops that are still ahead.
5. Review the proposed route, then tap **Start**. Its length and driving time appear on a label above the destination pin and as **Route: …** in the panel. Tap the status pill to see positioning details, including why a GPS fix was rejected.
6. Tap **Stop** (the red ✕ in the bottom panel) when the trip ends. The ride is saved in **History**, even if it was short. The fork button next to it is **Reroute**.

## Map display while navigating

Open **Settings → General → Map display** to configure the phone map. Choices are saved on this
device and apply immediately, including after changing the map theme or offline pack.

| Setting | Behaviour | Default |
| --- | --- | --- |
| 3D buildings | Show the style's buildings in 3D. Turning this off draws the same buildings flat (height zero) instead of hiding them. Styles without 3D geometry are unchanged. | On |
| Position arrow | Draw your position as a flat blue arrow lying on the (tilted) map and turned to the heading: the route direction while navigating, the phone's compass (gyroscope-fused rotation sensor) otherwise. Without a known heading a dot is shown. Off: a dot with a direction beam. | On |
| Perspective while driving | Tilt the map while navigating by car, with the travel direction at the top while following. Walking and stopping navigation restore a flat map. | On |
| Map tilt | Shown while the perspective is on: 30–60° in 5° steps. More tilt shows more road ahead; less keeps street names easier to read. | 45° |
| Automatic navigation zoom | Zoom out continuously as the engine's estimated speed increases: zoom 17 at 0–10 km/h, 15.75 at 70 km/h, and 14.5 at 130 km/h or above. | On |
| Hide tower button during navigation | Hide only the tower toggle while a route is active; show it again when browsing. Tower positioning and the selected tower overlay are unchanged. | Off |
| Hide zoom buttons during navigation | Hide +/− while a route is active; show them again when browsing. Pinch zoom remains available. | Off |
| Hide the “Location is off” warning | For navigating with Android Location switched off on purpose. Positioning is unchanged. | Off |

Manual zoom overrides the automatic scale until you tap **Recenter**, change the automatic-zoom
setting, or start another trip. Recenter remains available after a manual zoom, even if the map is
still following your position. Panning pauses position following; Recenter resumes it and restores
the automatic scale. Walking keeps zoom 17.5; disabling automatic zoom uses zoom 16 for driving,
unless you choose a manual scale. These options currently configure the phone map, not Android Auto.

While navigating, the destination label shows the distance still to drive, and the bottom panel shows time left,
"12 km of 45 km" with a thin progress bar, the arrival time, speed, Reroute and Stop. The next-turn banner and that panel
each take a single row so most of the screen stays map, even with large fonts. When the map area is
too short for one column of map buttons, they wrap into a second column instead of scrolling.

While navigating, the part of the route already driven is drawn grey. Your position is a flat blue
arrow pointing the direction you face: the road direction during navigation, the phone's compass
heading otherwise. Without a heading (phones without a rotation sensor) it is a dot. With
**Position arrow** off, a translucent beam around the blue dot shows the direction instead.

The app follows the phone's light/dark theme unless you choose **Light** or **Dark** in
**Settings → General → Theme**. The map style follows the same choice.

## Correct your position while driving

When the sensors have drifted and the marker is clearly in the wrong place, tap **Correct my
position** (the pin-with-pencil button on the map), move the map until the cross is where you are,
then tap **I am here**. You can also long-press the map and confirm **I am here**. The marker jumps
to the nearest point of the route and the uncertainty restarts at about 50 m. A point farther from
the route than the off-route distance starts a new route from there instead. Corrections are saved
in the trip recording, so replays apply them too.

## Choose how to navigate without GPS

1. Open **Settings → General → Navigation without GPS**.
2. **Classic** is selected by default on a new install. Its **Hybrid** fallback combines route progress with cell and network corrections. To use the other estimator, choose **Kalman filter**.
3. Keep a route active. The app's position estimate follows that route; free-drive dead reckoning is not available.

The app labels fixes **GOOD**, **SUSPECT** or **BAD**. Only a GOOD fix can anchor navigation. The [glossary](glossary.md) explains these labels, dead reckoning and route progress.

## Walk or improve car positioning

- For a walking route, choose **Walk** before starting. Allow Physical activity so the step detector can help. Walking routes require a pack that includes foot paths.
- For car speed, pair a classic Bluetooth ELM327 adapter, then open **Settings → General → Car speed and hills** to select and test it. The app reads vehicle speed; it does not write to the car.
- A phone barometer can help on hilly roads when the routing pack includes road heights.

For sensor rules, turn matching, uncertainty and settings behaviour, read the [detailed navigation reference](reference/navigation.md).

GPS diagnostics explain each rejected or suspicious fix in the selected app language. For example,
an implausible speed or a GPS timestamp that differs from the phone clock is shown as plain text.
Trip recordings and developer logs retain the original reason codes for analysis.

The **Sensors** row also uses the selected app language for missing hardware and its effect on
turn and stop detection. Sensor names in developer logs stay unchanged.
