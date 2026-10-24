# Desktop navigation mirror

[Documentation index](../README.md) · [Position sharing](position_sharing.md)

IMU Nav Desktop is a display-only Windows x64 application. Android plans the route, estimates the
position and supplies voice guidance. The desktop displays the route, upcoming maneuvers, remaining
distance/time, speed, GPS status, positioning source and navigation allowance. It is a research
prototype, not a safety system or a replacement for driver attention.

## Build and run

Install Rust 1.99+ and the Visual Studio C++ build tools, then run from the repository root:

```powershell
cargo build --manifest-path desktop/Cargo.toml -p imu-nav-desktop --release --locked
desktop/target/release/imu-nav-desktop.exe
```

The **Desktop Windows** GitHub Actions workflow also produces an unsigned portable Windows x64
artifact with the executable, guide and dependency notices. It does not publish a release.
The desktop has its own Cargo workspace and lockfile; it needs no Android SDK. Other desktop
platforms are not yet validated.

## Connect to Android

1. Open Android IMU Nav. A trip is optional for position-only testing; start a car or walking route for full mirroring.
2. Open **Settings → General → Share navigation position**, select **Local**, choose the private
   hotspot/Wi-Fi address, and enable **Share route and guidance** if navigating.
3. Start sharing. Connect the desktop to the same private network.
4. Enter the displayed IPv4 address and port (normally **10110**) in the desktop and click **Connect**.

For Internet sharing, use the existing account-specific **VPN address and port** instead. The
sharing server must support navigation protocol version 1, and the desktop must be the configured
VPN peer. Provision the VPN yourself; the desktop does not sign in, store account tokens or establish
a VPN. See [operator setup](position_sharing.md#operator-setup).

Full mirroring requires updated Android and relay software. With an older endpoint or route/guidance
sharing disabled, the desktop shows **Position only**, with position, speed and bearing from RMC.
An estimated NMEA fix never establishes GPS trust. Unknown trust and uncertainty are left unknown.

Detail sharing defaults off for each trip. Turning it off closes existing receiver connections and
clears the relay session before position-only sharing resumes. Receivers reconnect automatically.
Without a trip, sharing uses fresh trusted GPS or network/cell fixes and keeps running with the
screen off until **Stop sharing**. It waits when no usable position is available; a manual start
and rejected/mock GPS are never broadcast as live positions. Ending navigation, withdrawing
live-position consent, service teardown or process restart stops sharing. Starting navigation
keeps position sharing enabled; route and guidance still require a separate opt-in.

The desktop starts disconnected. Failed connections retry with backoff from one to thirty seconds;
**Disconnect** cancels reads and retries. Three seconds without valid position updates marks the
display stale and freezes the marker. Old route/guidance is cleared on reconnect. **Follow** resumes
tracking after panning; **Fit route** shows the complete route. The traveled trail retains at most
10,000 distinct received points in memory. English, Ukrainian and Russian labels are available in
**Settings**, along with a light/dark theme.

## Online and offline maps

The application opens in **Offline** mode, which issues no map-network requests. **Open MBTiles**
selects a local raster map. Files are opened read-only; supported formats are PNG/JPEG tiles at 256
or 512 pixels, standard TMS rows, and zooms 0–22. Metadata supplies the name, bounds, zoom range and
attribution. Vector MBTiles and Android's vector PMTiles/map ZIP packs are unsupported. Obtain raster
maps from a provider or tooling that permits offline use. A missing tile leaves the navigation
overlays visible; there is no automatic online fallback.

**Online** explicitly enables HTTPS XYZ tiles. The default is OpenStreetMap; **Settings** can change
the template containing `{z}`, `{x}`, `{y}` and the visible attribution. Do not put credentials in
the URL. Choosing Online reveals the viewed area to that tile provider. HTTP tiles are cached under
the user's cache directory, respecting HTTP cache headers. The app identifies itself as
`IMU-Nav-Desktop/0.1.0`; it provides no bulk tile downloading or offline prefetching from OSM's public
service. Follow the [tile policy](https://operations.osmfoundation.org/policies/tiles/).

Only connection, theme, language, tile-template/attribution and selected map-path preferences are
saved locally. Routes, precise positions and trails are never saved or uploaded by the desktop.
Plain sharing TCP has no encryption or login; use a private network or the configured VPN.

## Developer verification

Kotlin and Rust share the synthetic fixtures in `desktop/protocol/fixtures/`.
The `.nmea` fixture contains the exact RMC wire bytes, including its CRLF terminator.
Git line-ending conversion is disabled for these files so checkouts and source archives
preserve the same protocol bytes on every host.

```powershell
cargo fmt --manifest-path desktop/Cargo.toml --all --check
cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets --locked -- -W clippy::pedantic -D warnings
cargo test --manifest-path desktop/Cargo.toml --workspace --locked
desktop/target/release/imu-nav-desktop.exe --smoke-test captures/desktop.png
```

The opt-in smoke command renders a synthetic route, writes a screenshot and closes. It reads no
saved connection preferences, makes no network requests and does not save preferences. It is a
rendering check, not proof of a real phone or VPN deployment. Run Android's
`PositionSharingLifecycleTest` on a disposable emulator for screen-off service checks. Test your
actual private network/VPN receiver, map coverage, rerouting and GPS-loss transitions before use.

## Mirror protocol version 1

Default connections receive only the original checksummed estimated RMC sentences. An interested
receiver sends exactly `IMUNAV NAV/1\r\n`. After finishing the current line, the sender replies with
`{"type":"hello","version":1}` and switches that connection to UTF-8 newline-delimited JSON. No
command can start, stop or otherwise control Android navigation.

- `state`: `{type, version, state}`. State fields are `sequence`, `elapsed_ms`, `utc_ms`, `age_ms`,
  `nmea` (the original RMC sentence including CRLF) and nullable `guidance`. Ordering uses source
  monotonic elapsed time; receiver freshness uses its own monotonic clock, not synchronized clocks.
- Guidance contains nullable `route_revision`, `route_unavailable`, nullable `next`/`then`,
  `distance_to_next_m`, `remaining_m`, `remaining_s`, `progress_m`, `travel_mode`, nullable
  `speed_limit_kmh`, `uncertainty_m`, `source`, `gps_state`, nullable `gps_verdict`, and independent
  `jammed`, `off_route`, `rerouting`, `arrived` flags. Verdict describes the latest classified fix;
  GPS state describes current receiver availability. Maneuvers have `kind`, nullable `modifier`,
  `name` and nullable `roundabout_exit`.
- `route_start`: `{type, revision, points, summary, destination}`; destination is `[lat, lon]`.
  Sequential `route_chunk` messages carry `{type, revision, offset, geometry}`, with geometry as
  `[lat, lon]` pairs. `route_end` contains `{type, revision}`. Only a completely validated matching
  revision replaces the visible route. State updates can interleave route chunks.

Frames, including newline, are capped at 16 KiB; full serialized routes at 1 MiB and 100,000 points.
Chunks have at most 128 points. Coordinates, numeric fields, enums, text sizes, UTC and checksums
are validated. Unsupported versions and malformed JSON disconnect and display a status-only error.
Oversized geometry yields unavailable-route guidance, while valid position and maneuvers continue.
Route revisions are scoped to the sharing session and reset with it. No authentication session
secret is ever included in receiver messages.
