# Share a navigation position

[Documentation index](../README.md)

IMU Nav can send its current navigation estimate to compatible apps on another phone using NMEA over TCP.
The [Windows desktop viewer](desktop.md) can also mirror the route and guidance over the same local
or private-VPN endpoint when **Share route and guidance** is enabled for the current trip.
It works with or without a trip, including while the Android screen is off. Route and guidance require active car or walking navigation.
Android supplies the position and voice guidance; the receiving app displays or records the position.
The instructions below use Mergin Maps as an example receiver.
This is a research prototype, not a safety system.

## Local connection in a car (Mergin Maps example)

1. Download routing, map and tower data to Android before leaving coverage. Prepare and download
   an offline Mergin project to the iPhone; online map layers still need internet.
2. Enable a password-protected Android hotspot yourself and connect the iPhone to it.
3. Open **Settings → General → Share navigation position**.
4. Choose **Local**, refresh addresses, and select the hotspot's private IPv4 address. If several
   addresses appear, use the hotspot interface, not a VPN or another Wi-Fi interface.
5. Tap **Start sharing**. IMU Nav displays the receiver address and port **10110**.
6. In Mergin, open a project and choose **Settings → Manage GPS receivers → Connect new receiver →
   Network (TCP, UDP)**. Enter the displayed IPv4 address and port. Allow iOS local-network access
   if requested. Use a Mergin release with network receiver support (2026.3.0 or later).

The stream travels over the local network; it does not need cellular coverage. Some phones/carriers
restrict hotspot use without mobile data. If the hotspot cannot remain active, connect both phones
to a private router that permits communication between clients.

Anyone who can connect to the selected local address can read your precise position. NMEA has no
login or encryption. Use a private, password-protected network. IMU Nav binds only the address you
select, not every interface. Losing that address stops local sharing; reselect it and start again.
Port conflicts are reported rather than silently choosing another port.

## Internet connection through your sharing server

The operator must configure the relay below. Sign in to that server in IMU Nav, read its current
privacy notice and grant **live-position** consent when prompted. This is separate from tower,
diagnostics and trip-history consent. Internet publishing requires HTTPS even for a private server.

Install and configure the private VPN on the iPhone yourself. Select **Internet** or **Both** in
IMU Nav and start sharing. Enter the displayed **VPN server IPv4 address and account-specific port**
in Mergin. The iPhone does not need to connect to the Android hotspot for Internet mode, but both
phones need an internet connection and the iPhone must keep its VPN connected.

**Both** runs the two streams independently. An internet outage does not stop local sharing. Mergin
uses the receiver you selected; it does not automatically switch between the two addresses.
Internet sharing reconnects with bounded backoff and sends only the newest fresh estimate.

Sharing must be explicitly enabled for each session. No trip is required: choose a mode and tap
**Start sharing** to use fresh trusted GPS or network/cell fixes. The foreground notification
stays visible, and sharing continues with the screen off. Without a usable fix the listener
waits; manual starts, fused fixes and rejected/mock GPS are not exported. Route and guidance
are unavailable without navigation. Starting a trip keeps sharing enabled but does not enable
route/guidance automatically. Stop sharing explicitly or end navigation; service teardown and
process restart also turn it off. **Withdraw live-position consent** stops sharing and blocks future
internet publishing until you grant consent again. Changing accounts requires a new sharing session.
Unreachable servers expire the stream automatically even if Android cannot deliver the stop request.

## What the receiving phone sees

The sender exports an **estimated** RMC position, with UTC, speed in knots and route bearing. Even
during navigation, the exported coordinate is navigation output rather than raw GPS. Without a
trip, it uses the trusted planning position and preserves the original measurement age; absent
speed/bearing are encoded as zero.
When GPS is unreliable, IMU Nav continues using the selected navigation model and available inputs.
The phones should travel together if the iPhone position is intended to represent its own location.

Altitude, satellite counts, HDOP and GST statistical accuracy are omitted. IMU Nav's uncertainty is
a navigation allowance, not a calibrated GPS error standard deviation; its numerical value remains
visible on Android. Mergin's unknown altitude/accuracy must not be interpreted as zero uncertainty.
Mergin is not supplied the route, voice instructions or regional map packs. Apple Maps and other
iOS apps do not receive this stream as system GPS.

### Desktop route and guidance

**Share route and guidance** is off by default for every trip. Enabling it exposes your destination,
planned route, maneuvers and current navigation/GPS status to mirror receivers, including through
the relay if Internet sharing is selected. Turning it off reconnects receivers and clears the old
relay session before position-only sharing resumes. Android remains responsible for route planning,
navigation and voice guidance; the desktop cannot issue navigation commands.

Default TCP receivers still receive exactly the same estimated RMC sentences. Desktop receivers
request version 1 with a bounded subscription line and then receive newline-delimited JSON with
embedded RMC and separately chunked geometry. See the [wire contract](desktop.md#mirror-protocol-version-1).
Older Android/server endpoints provide position-only viewing. Desktop maps are configured separately.

Samples older than two seconds are not sent. Invalid output disconnects receivers. The relay expires
its cached sample after three seconds without a fresh upload; Mergin may take additional time to
update its disconnected display. No old positions are replayed after reconnecting.

Mergin 2026.4.1's conversion treats coordinates exactly on latitude or longitude zero as missing;
IMU Nav does not offset valid coordinates to work around that receiver limitation. Check the receiver's
status when using the equator or prime meridian.

## Operator setup

Live sharing is disabled unless `[live_position]` is present. Run **one relay process** for these
ports: session and position state are in memory and cannot be load-balanced between processes.
An operator privacy notice is required, including disclosure of transient live position processing
and VPN recipients. Change the notice version when enabling this purpose and inform existing users.
Before deploying desktop mirroring, disclose optional route, destination and guidance processing in
all notice languages, increase the operator notice version, and obtain current `live_position`
consent. No additional listener, VPN peer or database schema is introduced.

Example additions to the ignored server configuration (replace example IDs and VPN addresses):

```toml
[live_position]
bind = "10.77.0.1" # Actual WireGuard/private VPN interface on the server.

[[live_position.receivers]]
account_id = 1
port = 10111
peer = "10.77.0.2" # This account's iPhone VPN address, as seen by the TCP listener.
```

Use separate ports and VPN peers for additional accounts. Provision the VPN separately, restrict
the TCP ports to that interface with the firewall, and disable peer-to-peer forwarding between VPN
clients. Do not expose the NMEA ports through a public reverse proxy, NAT, port forwarding or a
TCP proxy. The listener checks the actual socket peer; forwarded headers cannot authorize it.
Loopback and private/CGNAT IPv4 addresses are accepted, wildcard/public binds and privileged ports
are rejected. Binding failure fails startup rather than advertising an unavailable receiver.

For Docker, make the VPN interface visible inside the process's network namespace and restrict
access there, or run the relay directly on the VPN host. The existing Compose HTTP proxy does not
automatically expose these ports. Keep the ordinary account API behind HTTPS.

The relay permits 128 active sessions, one per account, and four receiver connections per session.
It retains only the newest sample in RAM. Nothing is added to tower, trip or diagnostic storage.
Sessions expire after 30 seconds without publishing. Slow writes have a two-second deadline;
account/consent eligibility is rechecked before writes and at most every 500 ms while idle. A write
already in progress can complete before revocation is observed. Consent withdrawal immediately
clears that account's cache and wakes receivers. Logs omit positions, credentials and session IDs.

## HTTP contract

All calls use the existing bearer account token. Only configured accounts have receiver details.

| Endpoint | Behavior |
| --- | --- |
| `GET /v1/live` | Returns `{address, port, receivers}` for this account, or capability/configuration error. |
| `POST /v1/live/session` | Creates an explicitly enabled sharing session; returns `{session, receiver: {address, port, receivers}}`. Requires eligibility and current `live_position` consent. |
| `PUT /v1/live/session` | With `X-Live-Session`, replaces the latest sample and returns receiver details. |
| `DELETE /v1/live/session` | With `X-Live-Session`, idempotently ends the caller's matching session. |
| `PUT /v1/live/session/navigation` | With `X-Live-Session`, atomically publishes `{sample, guidance}` under the existing ownership/consent gates; 8 KiB limit. `guidance: null` clears cached route/details. |
| `PUT /v1/live/session/route` | With `X-Live-Session`, replaces `{revision, geometry, summary, destination}`; 1 MiB limit, at most 100,000 geographic points. Retries of identical geometry/revision are idempotent. |

Receiver metadata additionally advertises `navigation_version: 1`. The original position-only
endpoint retains its 2048-byte limit and input shape. Navigation updates retain the same sample
ordering and maximum four-per-second rate; geometry is uploaded only when the route changes.
The server retains only the newest route/state in memory, discarding them on sample/session expiry,
stop, consent revocation or account/login invalidation. Slow receiver writes retain the two-second
deadline. The mirror's numeric uncertainty is labelled a navigation allowance, not GPS accuracy.

Publish JSON fields are `sequence`, `lat`, `lon`, `speed_mps`, `bearing_deg`, `uncertainty_m`, `source`,
`elapsed_ms`, `utc_ms`, and `age_ms`. Sequence and elapsed sample time must strictly increase.
Coordinates must be in geographic bounds; speed is finite in `0..1000` m/s, bearing in `[0,360)`,
uncertainty finite and nonnegative, source one of the navigation source enum names other than `NONE`,
and age in `0..2000` ms. UTC is Unix milliseconds through the end of 2099. Requests are capped at
2048 bytes; accepted publishing is limited to at most four samples per second.

`401` requires sign-in, `403` indicates missing consent/eligibility or receiver provisioning, `404`
indicates an expired session, `409` indicates an existing session or unordered sample, `429` indicates
a resource/rate limit, and `503` indicates disabled capability. Never log the session header/body.
The existing privacy endpoints accept the new `live_position` purpose and return its receipt in
`GET /v1/privacy/me`. Existing receipts do not grant the new purpose; no database schema migration
is required. Access-token refresh retains the owning login session; logout revokes it.

## Verification

Run `./gradlew check`, then build release and benchmark APKs. The synthetic host tests cover RMC
encoding, coordinate/date boundaries, validity/freshness, connection limits, teardown and consent.
The Android instrumentation test exercises the foreground-service path with the activity stopped
and the emulator screen off.

On a real iPhone, test both local and VPN receiver connections, estimated-fix status, offline maps,
GPS-to-dead-reckoning transitions, reconnecting, and stopping/withdrawing sharing. These receiver
checks are a separate acceptance step; automated Android/JVM tests do not establish iOS compatibility.

See [Mergin's network receiver instructions](https://merginmaps.com/docs/field/external_gps/) and
[offline project setup](https://merginmaps.com/docs/field/offline-use/).
