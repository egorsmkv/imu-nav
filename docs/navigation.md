# Navigation and positioning

[Documentation index](../README.md)

The app tries to use trusted GPS first. When GPS is unreliable, it estimates progress along your planned route from phone sensors, cell towers and map information.

## Start a trip

1. In **Where to?**, choose **Car** or **Walk**.
2. Search for a destination or long-press the map. The app uses your current position as the start when it has a recent usable fix.
3. If no usable position is available, search for a starting address. Selecting an address in **From** centres the map on that place and zooms in to street level. You can also move the map crosshair to your position and tap **Start here**.
4. Review the proposed route, then tap **Start**. Tap the status pill to see positioning details, including why a GPS fix was rejected.
5. Tap **Stop** when the trip ends. The ride is saved in **History**, even if it was short.

## Map display while navigating

Open **Settings → General → Map display** to configure the phone map. Choices are saved on this
device and apply immediately, including after changing the map theme or offline pack.

| Setting | Behaviour | Default |
| --- | --- | --- |
| 3D buildings | Show the style's extruded buildings. Turning this off keeps any existing flat footprints. Styles without 3D geometry are unchanged. | On |
| Perspective while driving | Tilt the map 45° while navigating by car, with the travel direction at the top while following. Walking and stopping navigation restore a flat map. | On |
| Automatic navigation zoom | Zoom out continuously as the engine's estimated speed increases: zoom 17 at 0–10 km/h, 15.75 at 70 km/h, and 14.5 at 130 km/h or above. | On |
| Hide tower button during navigation | Hide only the tower toggle while a route is active; show it again when browsing. Tower positioning and the selected tower overlay are unchanged. | Off |
| Hide zoom buttons during navigation | Hide +/− while a route is active; show them again when browsing. Pinch zoom remains available. | Off |

Manual zoom overrides the automatic scale until you tap **Recenter**, change the automatic-zoom
setting, or start another trip. Recenter remains available after a manual zoom, even if the map is
still following your position. Panning pauses position following; Recenter resumes it and restores
the automatic scale. Walking keeps zoom 17.5; disabling automatic zoom uses zoom 16 for driving,
unless you choose a manual scale. These options currently configure the phone map, not Android Auto.

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
