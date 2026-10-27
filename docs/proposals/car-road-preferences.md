# Car road preferences: investigation and proposal

Status: implemented, 2026-10-27. The investigation below describes the original behavior.
The current implementation is documented in [the routing reference](../reference/routing_and_search.md)
and uses v3 packs with separate car metrics and local approach/exit states. Country-scale
measurements remain outstanding; synthetic regression coverage is in
`native/nav-routing/tests/car_preferences.rs`.

IMU Nav is a research prototype, not a safety system.

## Findings

The reported courtyard shortcuts are consistent with the current offline routing policy.
This investigation used synthetic OSM data; it does not establish which tags, pack version,
or routing provider caused any particular user's route.

- `native/nav-routing/src/builder/profile.rs` sets car preference to `1.0` for every road.
  Modeled speeds provide some preference indirectly: primary 65 km/h, residential and
  unclassified 30 km/h, service 20 km/h, living street 5 km/h.
- `service=driveway`, `service=parking_aisle` and `service=alley` do not affect car costs.
- `native/nav-routing/src/builder/mod.rs` builds costs as
  `distance / speed / priority + distance * 0.09`, plus turn costs, in seconds before
  conversion to integer milliseconds. The extra 90 seconds per kilometre can make a shorter
  service road win even when its modeled travel time is longer.
- `destination`, `customers` and `delivery` are classified as allowed access. Their purpose
  restrictions are not retained or enforced during queries. They can therefore become shortcuts.
- Route requests and previews select car or foot and return one route. There is currently
  no independent car road preference or route-alternative selector.
- Start/end snapping selects the nearest accessible physical road. A point near a courtyard
  can therefore select that road even when a nearby main street was intended.

OSM distinguishes [service-road subtypes](https://wiki.openstreetmap.org/wiki/Tag:highway%3Dservice)
and [purpose-limited access](https://wiki.openstreetmap.org/wiki/Key:access).
In particular, [delivery access](https://wiki.openstreetmap.org/wiki/Tag:access%3Ddelivery)
does not authorize an ordinary driving trip. Road preference and access eligibility need
separate treatment.

## Synthetic reproduction

A temporary Rust probe outside the repository called the production `builder::build`,
`Network::open` and `route::route` functions. The release library was checked against the
current source with:

```powershell
cargo build --locked --release --manifest-path native/Cargo.toml -p imu-nav-routing --features builder --lib
```

The synthetic network has entry and exit roads, a straight service shortcut, and a longer
primary road between the same junctions. Coordinates below are artificial, in latitude/longitude
order. Entry, primary and exit ways have `highway=primary`; the shortcut has `highway=service`.
All are bidirectional and have no posted speed or access tags except the tested shortcut tag.

| Node | Latitude | Longitude |
| --- | --- | --- |
| 1 | 0 | -0.001 |
| 2 | 0 | 0 |
| 3 | 0 | 0.0001 |
| 4 | 0.004 | 0.0018 |
| 5 | 0 | 0.0035 |
| 6 | 0 | 0.0036 |
| 7 | 0 | 0.0046 |

Entry: 1-2; primary: 2-3-4-5-6; exit: 6-7; shortcut: 2-6.
Build with minimum network size zero and search/elevation disabled. Route from
`[0, -0.0009]` to `[0, 0.0045]`. Compare with the same request through node 4.
Each row below is a separately built pack.

| Additional shortcut tag | Default route | Modeled time | Route through node 4 |
| --- | --- | --- | --- |
| None | Courtyard shortcut | 83.14 s | Main road, 65.85 s |
| `service=driveway` | Courtyard shortcut | 83.14 s | Main road, 65.85 s |
| `access=destination` | Courtyard shortcut | 83.14 s | Main road, 65.85 s |
| `access=customers` | Courtyard shortcut | 83.14 s | Main road, 65.85 s |
| `access=delivery` | Courtyard shortcut | 83.14 s | Main road, 65.85 s |

These are modeled durations, not measured traffic times. Displayed duration excludes routing
penalties, so the currently selected route must not be described as the fastest route.

## Proposed user experience

Add a car-only **Road preference** control beside the car/walk selector in the route preview.
Offer two choices initially:

| Choice | Behavior |
| --- | --- |
| **Prefer main roads** (recommended default) | Prefer through streets; strongly discourage courtyard, parking and driveway shortcuts. Keep local access to the start, stops and destination. |
| **Balanced** | Allow useful shortcuts on eligible public streets, with lighter road-class penalties. |

Use "Balanced" for the current-style option rather than promising "Fastest" while distance
and preference penalties remain in the objective. A true fastest option would need its own
time-based metric and validation.

Recalculate the preview when the preference changes and remember the selection. When the
two choices yield meaningfully different routes, show both on the map with selectable cards
containing distance, modeled duration and useful road names. If both produce the same route,
show one route. Selecting a card must also select the policy used during subsequent reroutes.
Time differences must come from the computed alternatives rather than fixed UI examples.

For a specific preferred street, retain the existing ability to add a stop on that street.
A later "Route via this road" action could place a shaping point on the selected legal road;
that is separate from the general road preference.

## Proposed routing behavior

1. Correct access handling for both preferences. Prevent through-routing across destination-only
   components; allow necessary entry/exit when a route endpoint or explicit stop belongs to that
   component. Customer access also needs an eligible visit, and delivery-only roads must remain
   unavailable to ordinary driving. A nearby endpoint alone must not grant every access purpose.
   Preserve mode-specific/directional precedence, one-way rules, barriers and turn restrictions.
2. Add moderate residential-street penalties and stronger service, alley, driveway, parking-aisle
   and living-street penalties for **Prefer main roads**. Start with calibrated, bounded costs,
   then evaluate detours against representative urban and rural routes. Do not exclude all
   residential roads or assume `unclassified` means a courtyard: rural connectivity depends on
   these roads. Legal local access and routes with no reasonable through-road alternative must
   remain possible.
3. Reassess the 90 s/km distance term using the reproduced case and representative journeys.
   Keep modeled travel speed separate from preference costs; inflating or reducing speed to
   encode preference would also distort durations and navigation speed data.
4. Preserve the selected policy through preview reuse, navigation start, manual/automatic
   rerouting, and saved route recipes. Preference and pack identity belong in preview cache keys.
5. Handle ambiguous road selection separately: show the snapped access point and let users
   choose an entrance or nearby road. Do not silently move a real start onto a parallel road
   simply because it has a higher road class.

## Implementation constraints and rollout

The contraction hierarchy is prepared for fixed costs. Changing a multiplier in a runtime
request while querying the existing hierarchy would give incorrect results. Current pack
states also omit residential/service classes and purpose-restricted access metadata.

For an initial implementation, prepare two car cost variants sharing geometry and names.
Keep `TravelMode.CAR` separate from a new car preference. Extend pack capabilities and selection
to declare the available variants; the current `Profile` enum and loader support only car/foot.
Version any binary layout/semantics changes, rebuild affected packs and update validation,
single-mode pack extraction and CI packaging together. Measure additional pack size, build
time, Android memory use and routing latency before committing to this storage design.

Purpose-restricted access additionally needs endpoint-aware routing support, retaining legal
restriction history. Separate static hierarchies alone do not implement those exceptions;
design and validate this query behavior explicitly. A large soft penalty is not access enforcement.

Old packs and online OSRM responses must expose their capabilities. The current online client
does not request these preferences. Do not present an unsupported result as "Prefer main roads"
or silently lose the preference on fallback. Show the limitation and offer a supported choice.

Implement the access correction and default cost improvement first, followed by the two-choice
control and alternative cards. Add English, Ukrainian and Russian strings with the UI change,
and update the routing guide to explain the options and pack requirements.

## Acceptance checks for implementation

- Turn the synthetic network above into regression coverage for road choice and ETA separation.
- Cover parking aisles, alleys, ordinary residential streets and restricted-access components,
  including multi-edge components, starts/stops/destinations inside them and car-specific overrides.
- Verify rural connectivity, unavoidable local access, reachable courtyard destinations, nearby
  parallel roads, same-route deduplication and reasonable detour behavior.
- Preserve preference on reroute; prevent stale previews after preference or pack changes.
- Compare each metric with independent shortest-path results and retain forbidden-turn coverage.
- Run native routing tests and `./gradlew check`; build and exercise the R8 benchmark APK on
  Android with newly prepared packs. Existing host checks do not validate the proposed UI or JNI.

## Implementation verification (2026-10-27)

- `gradlew.bat --max-workers=1 --no-parallel spotlessApply check :app:assemblePlayRelease :app:assemblePlayBenchmark` passed.
  One preceding concurrent run hit an intermittent loopback-sharing socket test; that test
  passed in isolation and the complete serial run passed.
- Native coverage includes both car metrics, independent CH/Dijkstra comparisons, service
  subtypes, local starts/stops/destinations, directional access and retained turn history.
- All eight routing-archive verifier tests passed. Synthetic combined and car-only v3 packs
  passed archive validation and the native loader; the car-only download retained both metrics.
- Replay accuracy remained `p95=20 max=25 rms=10 m` for the blind drive.
- The R8 benchmark APK was installed on the API 37 emulator. A synthetic v3 pack imported through
  Settings produced a 1.1 km main-road choice and a 510 m courtyard alternative. Both choices
  were exercised during navigation/rerouting. Card switching reused completed routes without
  moving the preview controls. A cold start retained Balanced, and reopening a saved route
  restored its own Balanced preference after changing the global preference to main roads.
- Both final APKs passed ELF/RELRO and ZIP alignment checks for 16 KiB pages, including all four
  application Rust libraries and MapLibre in the packaged 64-bit ABI.

Regional pack size/build-time measurements and real urban route comparisons remain outstanding.
