# Responsive UI checks

Use the **available app window in dp**, not the physical display resolution. Split-screen,
system bars and the keyboard can leave much less space than the display dimensions suggest.
Use an isolated test install and synthetic places/trips for screenshots.

## Automated boundary checks

`DrivingLayoutTest` covers portrait/square windows, the 800 dp landscape breakpoint at default
font size, smaller fonts, font scales up to 2.0 and a matrix of resizable windows. It checks
that a side pane never becomes narrower than its readable width or takes more than 40% of
the window. The map, search overlay and bookmark overlay use the same decision and font scale.

```sh
./gradlew :app:testFdroidDebugUnitTest --tests '*DrivingLayoutTest*'
./gradlew spotlessApply check
./gradlew :app:assembleFdroidRelease :app:assembleFdroidBenchmark
```

These unit tests verify the layout decision, not rendered Compose bounds. Run the following
device checks before treating the UI as visually verified. Use the benchmark APK for route
packs: debug builds cannot load GraphHopper's desugared records (see `AGENTS.md`).

## Device matrix

| App window | Font scale | Expected layout / main risk |
| --- | --- | --- |
| 320 × 640 dp | 1.0, 2.0 | Full-width panels; long labels and speed-limit sign remain readable |
| 360 × 800 dp | 1.0, 1.3, 2.0 | Portrait map with independently scrollable warnings, controls and route panel |
| 640 × 360 dp | 1.0, 2.0 | Bottom panel; search and bookmarks cover the map |
| 800 × 360 dp | 1.0 | 320 dp side pane; map controls clear system bars |
| 800 × 360 dp | 1.5, 2.0 | Full-screen search/bookmarks because enlarged text needs a wider pane |
| 1200 × 800 dp | 1.5 | 480 dp side pane with the majority of the width reserved for the map |
| 800 × 800 dp | 1.0, 2.0 | No side pane in a square window |

Repeat with Ukrainian and English, gesture and three-button navigation, a display cutout,
and the keyboard open in search, bookmark filtering and settings. Resize the window and
rotate while each overlay is open. Russian is an additional useful translation check.

1. **Map:** check idle, active navigation and arrival; long route names; GPS warnings; tower
   legend; all five map buttons. Scroll both the route content and the action area in a short
   window. Stop/Reroute and Expand/Collapse must stay reachable. The crosshair/camera should
   track the area not covered by the route or search/bookmark pane.
2. **Search/bookmarks:** type a filter with the keyboard open. Scroll the bounded controls
   to reach the filter and the independent result list to reach its final item. Check error
   and active-navigation messages as well as normal results. No duplicate navigation-bar
   gap should appear above the keyboard. Open Rename/Delete with a long saved name and check
   that dialog text can scroll without losing confirmation/cancellation actions.
3. **Onboarding:** long numbered section headings wrap beside their number; the checklist
   and Continue area remain independently reachable at large font sizes.
4. **Settings/log/history/trip details:** scroll to the last item; open menus and dialogs;
   check long translations, trip statistics and the map preview. Settings and log inputs
   remain above the keyboard. Back and toolbar actions remain tappable.
5. **Layer lifetime:** close overlays and return to the map. It must retain its existing
   MapView, with no taps leaking through an opaque screen to the map below.

## Validation scope of this change

The source audit covered the map overlays/panels, search, bookmarks, onboarding, settings,
diagnostics, logs and trip screens. Changes address the narrow side-pane policy, bookmark
keyboard/header sizing, short-window map actions, landscape control insets and speed-indicator wrapping.

In the authoring environment, Gradle could not download its distribution (`Network is
unreachable`), and no Android SDK/emulator was available. The Gradle checks and device matrix
above were therefore **not run locally**; CI build/unit-test/lint results and device checks
must be evaluated separately. Do not interpret this matrix as a list of passing screenshots.
