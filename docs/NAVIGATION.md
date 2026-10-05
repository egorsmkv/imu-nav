# Navigation and positioning

[Documentation index](../README.md)

The app tries to use trusted GPS first. When GPS is unreliable, it estimates progress along your planned route from phone sensors, cell towers and map information. This is a research prototype, not a safety system.

## Start a trip

1. In **Where to?**, choose **Car** or **Walk**.
2. Search for a destination or long-press the map. The app uses your current position as the start when it has a recent usable fix.
3. If no usable position is available, search for a starting address. You can also move the map crosshair to your position and tap **Start here**.
4. Review the proposed route, then tap **Start**. Tap the status pill to see positioning details, including why a GPS fix was rejected.
5. Tap **Stop** when the trip ends.

## Choose how to navigate without GPS

1. Open **Settings → Everyday settings → Navigation without GPS**.
2. **Classic navigation** is selected by default on a new install. Its **Hybrid** fallback combines route progress with cell and network corrections. To use the other estimator, choose **Kalman filter**.
3. Keep a route active. The app's position estimate follows that route; free-drive dead reckoning is not available.

The app labels fixes **GOOD**, **SUSPECT** or **BAD**. Only a GOOD fix can anchor navigation. The [glossary](GLOSSARY.md) explains these labels, dead reckoning and route progress.

## Walk or improve car positioning

- For a walking route, choose **Walk** before starting. Allow Physical activity so the step detector can help. Walking routes require a pack that includes foot paths.
- For car speed, pair a classic Bluetooth ELM327 adapter, then open **Settings → Everyday settings → Car speed and hills** to select and test it. The app reads vehicle speed; it does not write to the car.
- A phone barometer can help on hilly roads when the routing pack includes road heights.

For sensor rules, turn matching, uncertainty and settings behaviour, read the [detailed navigation reference](reference/NAVIGATION.md).
