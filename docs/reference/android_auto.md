# Landscape driving and Android Auto

> Detailed reference. For easy steps, see the [guide](../android_auto.md) or the [glossary](../glossary.md).

In a window at least 600 dp wide and wider than it is tall, driving controls use a left pane
capped at 320 dp and 40% of the width. Search and bookmarks open alongside the existing map;
its camera padding follows the measured panel. Shorter, narrow windows have expandable route
controls. Stop and Reroute remain accessible. Bookmark row menus contain endpoint selection,
rename and delete; compact route editors put bookmark saves under **More options**.

Both Play and F-Droid builds include projected **Android Auto** navigation using AndroidX Car
App 1.7.0 (host Car App API 7 or newer). This is not a standalone Android Automotive app.
Finish setup and install offline packs on the phone, then use the car screen to search addresses,
choose recent places or bookmarks, review a route and press **Start**.

Use **Edit route** to choose
an explicit origin, or **Current position** for an automatic origin. Saved routes retain fixed
starts. Walking routes remain phone-only. Incoming `geo:` navigation intents open a preview or
search; they never start driving automatically. Stop the current trip before changing endpoints.

The phone and car share one engine, sensor subscription set, foreground service and recording.
The car shows maneuvers, remaining distance/time, arrival and positioning uncertainty, and offers
Stop, Reroute, pan, zoom and recenter. Disconnecting releases the car map while navigation continues
on the phone; reconnecting attaches to that trip.

MapLibre draws through a virtual display directly
onto the host surface, sharing map styles and layers with the phone. Host visible/stable areas
control camera padding, and power profiles cap rendering rates. Android restrictions or missing
permissions produce a phone-setup action instead of starting an unprotected trip.

Android Auto host auto-drive mode simulates a reviewed route in the car session only. It is labelled
**DEMO**, does not inject fixes or write trips/learning data, and ends on Stop or session teardown.
A real trip started on the phone supersedes the demo. Release builds validate hosts; debug and
benchmark builds permit development hosts.

For device validation, use a benchmark build (debug GraphHopper cannot load routing packs) with
Android Auto's Desktop Head Unit. Exercise phone-visible/hidden, connect/disconnect/reconnect,
GPS loss/manual origin, saved fixed-origin routes, reroute/arrival, surface resize/day-night changes,
missing location permission, and host auto-drive mode. Rotate the phone and test split-screen,
large fonts and the keyboard while search/bookmarks are open. Car model instrumentation tests:

```bash
./gradlew :app:connectedFdroidDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.imunav.app.car.CarGuidanceTest
```
