# Cell towers and sharing server

> Detailed reference. For easy steps, see the [guide](../cell_towers.md) or the [glossary](../glossary.md).

## Cell tower database

Cell positioning preserves negative dBm signal readings so stronger cells receive more weight;
Android's unavailable signal values are treated as missing.
Incoming scans discard cells with missing, future or more than 10-second-old modem timestamps before
positioning and tower learning. Multi-SIM scans keep the newest measurement for each cell. Fix timestamps reflect
the oldest contributing measurement, and repeated cached measurements do not create new fixes or
usage-history entries. Scan frequency still follows the selected power profile.

Tower locations come from four sources, each in its own table and looked up in this order:

| Source          | How it gets there                                                                                                                                                                                                                                                                                                                                                                   |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Sync server** | Merged data downloaded from a cell-sharing server you configure (see below).                                                                                                                                                                                                                                                                                                        |
| **OpenCellID**  | _Download OpenCellID_ with your own API token, or _Import file_ (`.csv` / `.csv.gz`). CC BY-SA 4.0.                                                                                                                                                                                                                                                                                 |
| **Learned**     | While GPS is GOOD (≤ 30 m), every visible cell's position is refined from the fix.                                                                                                                                                                                                                                                                                                  |
| **Mozilla**     | _Download Mozilla data_ streams the Mozilla Location Service final export (1.5 GB, public domain, March 2024) from archive.org, keeping only your region's MCCs; resumes after network drops, nothing large is stored.                                                                                                                                                              |
| **Built-in**    | Shipped inside the APK (`app/src/main/assets/cells/bundled-cells.csv.gz`, ~530k Ukrainian towers compiled from OpenCellID + Mozilla). Optional: choose **Install built-in towers** in onboarding (or reopen **Settings → Set up IMU Nav**) to import it (~20 s). After opting in, changed archives are imported on app updates. Lowest priority, so anything downloaded later wins. |

Imports, downloads and sync use the configured country codes. On a new installation the serving
network's MCC is selected when a cell scan provides one. Existing saved codes stay unchanged;
without a detected or entered MCC these operations wait for a country selection. The bundled
Ukraine tower database remains optional and does not set the MCC filter.

**Usage history:** **Settings → Cell towers → Cell tower usage history** shows the latest 20 tower
contributions and can share the complete history as a UTF-8 CSV file through Android's share sheet.
Recording starts with this version and runs whenever cell scanning produces a fix, including outside
active trips.

Each scan records only towers actually used after outlier filtering, not unknown or
disabled cells. The CSV includes app-session id, Unix/elapsed timestamps in ms, radio, MCC/MNC,
area/cell id, signal, serving flag, timing advance, tower geometry and the estimated fix/accuracy.
Coordinates can reflect a cell-id or site match rather than an exact tower match.

History persists
across app restarts and positioning-database resets; old trips cannot be reconstructed retroactively.
It stays on the device unless you explicitly share it. The file reveals approximate locations and
times: share only with trusted recipients.

### Updating the built-in database

1. On a phone with the data you want (after _Download Mozilla data_ / _Download OpenCellID_ / syncs), open **Cells → Export database**.
   It writes every tower once — choosing the entry lookups would use — to `Android/data/org.imunav.app/files/cells-export.csv.gz`.
2. Copy it into the project and rebuild:

   ```bash
   adb pull /sdcard/Android/data/org.imunav.app/files/cells-export.csv.gz app/src/main/assets/cells/bundled-cells.csv.gz
   ./gradlew :app:assembleRelease
   ```

3. Installed apps re-import it once after updating (detected by the file's SHA-256).

The compiled file contains OpenCellID data and is therefore distributed under CC BY-SA 4.0 (see `assets/cells/LICENSE.txt`).

## Cell-sharing server

Phones upload towers they learned from trusted GPS — tower positions only, never the device track —
and download everyone's merged data. Protocol (gzip CSV in OpenCellID columns):

- `POST /v1/cells` — upload; requires a signed-in account's bearer access token
- `GET /v1/cells.csv.gz?mcc=255&since=<epoch seconds>` — incremental download
- `GET /v1/towers` plus `PUT` / `DELETE /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` — JSON management API
- `GET /v1/events` — WebSocket stream of tower upserts and deletions for realtime management tools
- `GET /admin` — administrator dashboard for tower moderation, accounts, imports, exports, and policy
- `GET /data-usage` — public explanation of tower uploads, publication, logs, and account controls
- `GET /health`

The Rust reference server in `server/` persists per-device contributions and materialized consensus
in SQLite (WAL mode) or PostgreSQL. SQLite is the default. It is built to
resist **poisoning** (a phone or a script uploading fake tower positions):

- Every upload carries an `X-Device-Id`; contributions are stored per account and device (at most 50 samples each).
- A tower's position is a **one-device-one-vote weighted median**, so one device cannot outvote others
  by uploading many samples; positions far from the consensus (MAD-based) are dropped as outliers.
- A new tower is published only after `--min-devices` (default 2) independent devices agree, or if it
  came from the seed import (which counts as a strong vote).
- Rows outside an optional administrator-configured service area, jumps > 5 km from the consensus,
  and absurd ranges are rejected. The server default accepts coordinates worldwide.
- Rate limits per device and per IP, and a cap on new device ids per IP per day.

```bash
cargo build --release --manifest-path server/Cargo.toml
server/target/release/imu-nav-cell-server --port 8080 --data cells.sqlite3 \
    [--min-devices 2] [--max-samples 50] [--area ukraine|any] [--trust-proxy]
# optionally seed it once from an export, e.g. OpenCellID/Mozilla filtered to Ukraine:
server/target/release/imu-nav-cell-server --data cells.sqlite3 --import 255.csv.gz --mcc 255
```

Create the first admin with `--data cells.sqlite3 --create-admin admin@example.org` (password from
standard input). To select PostgreSQL or configure the server with TOML, use
`--config server/config.toml` as described in the
[server configuration guide](../server/configuration.md). The repository's Compose
setup includes Caddy as a TLS reverse proxy for use outside your own network.

Account sign-in
in the app requires HTTPS outside local loopback development (including the emulator's `10.0.2.2`
host alias). Public downloads still work without signing in.
Browser users can register at `/signup`, sign in at `/login`, and manage their uploaded cell
observations at `/account`.

The panel filters and exports the account's observations, supports
individual or full deletion, pauses uploads, changes credentials, revokes sessions, and closes the
account. Deleted cell keys cannot be reuploaded by the same account. The server stores cell
observations by default; opt-in developer diagnostics store trip recordings separately.

Email
verification and browser password recovery use the
configured SMTP server; local deployments without SMTP verify new accounts immediately.
Administrators sign in through `/login` and are directed to `/admin`. Tower deletion requires
quarantine first; the panel also supports account suspension and atomic seed imports and policy
recalculation.

The dashboard pages through towers, accounts, audit activity, and observations;
imports show live staging/progress, can be cancelled, and provide a rejected-row CSV report.
Administrators can use **View as user** on an active ordinary account to open its account panel
in a short lived, audited browser session, then return to the administrator panel.

Phones now receive tower removals on their next sync when a shared tower is quarantined, deleted,
or withdrawn by policy. Recalculation applies Ukraine-only and maximum-range rules to stored
observations; maximum-jump checks still apply to new uploads because earlier positions are not
retained.

Saved policy values override CLI defaults on later starts.
The old Kotlin server's internal contribution gzip is not a
SQLite migration source; re-import the original seed export when moving to this server. See
[server API guide](../server/api.md) for the HTTP, management and WebSocket API.

The
[profiling guide](../server/profiling.md) covers optional instrumentation and the loopback traffic
simulator.
In the app: **Cells → Sharing server**, enter the URL, register or sign in with email and password,
then _Sync now_ or enable automatic sync (every 6 h and after trips). Signing out still allows
public downloads. Password-reset email needs SMTP configuration; see the
[server configuration guide](../server/configuration.md).

Main navigation thresholds live in `core/.../Tuning.kt` (defaults = factory preset) and `TrustConfig`.
The separate inertial experiment keeps its provisional noise densities in `InertialTuning`.
