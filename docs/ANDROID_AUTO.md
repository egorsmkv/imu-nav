# Landscape driving and Android Auto

[Documentation index](../README.md)

You can view a car trip on a compatible Android Auto screen. The phone and car screen share the same trip. Walking routes remain on the phone.

## Use the car screen

1. Finish setup on the phone. Install any offline packs you plan to use before connecting to the car.
2. Connect the phone to a compatible Android Auto host and open IMU Nav there.
3. Search for a destination, choose a recent place or bookmark, and review the route. Use **Edit route** for a fixed starting place or **Current position** for an automatic start.
4. Tap **Start**. The car screen shows turns and remaining distance and offers **Stop**, **Reroute**, pan, zoom and recenter.
5. Disconnecting the car screen leaves a running phone trip active. Reconnect to see that trip again.

For a wide phone window, the controls move beside the map. Shorter windows use expandable controls so the map remains usable.

## Try demo mode or test a build

Android Auto's host drive mode shows **DEMO**. It does not add fake locations to a real trip or recording. A real phone trip takes over from the demo.

Developers should test routes with a benchmark build because a debug build cannot load the offline routing pack. The car model test can be run with:

```bash
./gradlew :app:connectedFdroidDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.imunav.app.car.CarGuidanceTest
```

See the [glossary](GLOSSARY.md) and the [detailed Android Auto reference](reference/ANDROID_AUTO.md) for host and display behaviour.
