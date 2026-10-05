# IMU Nav cell server

The reference sharing server preserves the Android app's gzip-CSV sync protocol while storing every
per-device contribution and materialized consensus in SQLite. SQLite runs in WAL mode, so downloads
and management reads can continue during uploads. Building the server requires Rust 1.88 or newer.

```bash
cargo build --release --manifest-path server/Cargo.toml
server/target/release/imu-nav-cell-server --port 8080 --data cells.sqlite3 \
    --min-devices 2 --area ukraine --trust-proxy
```

Create the first administrator locally (the password is read from standard input):

```bash
server/target/release/imu-nav-cell-server --data cells.sqlite3 --create-admin admin@example.org
```

Put the server behind a TLS reverse proxy for public deployments. Accounts replace the former
shared API key: users register with email and password in the Android app, and only signed-in
users can upload. Existing apps can continue downloading published towers but cannot upload.
`--trust-proxy` honors the first `X-Forwarded-For` address for per-IP limits and must
only be enabled when direct access to the server port is blocked. Run with `RUST_LOG=debug` for more
detailed operational logs.
`--bind 127.0.0.1` restricts the listener to loopback for local testing; the default remains
`0.0.0.0` for existing deployments.

## Browser account pages

Open `/signup` to create an account or `/login` to sign in. The `/account` panel lists the
observations uploaded by that account's devices, 100 per page, with controls to delete one
observation or all of the account's observations. Other accounts' and seed observations are not
shown or removed. Each deletion recalculates the affected tower consensus and updates management
WebSocket subscribers. Sign out on the panel revokes the browser session.

Browser sessions use a seven-day, HttpOnly, SameSite=Strict cookie. Delete and sign-out forms
require a session-specific form token. The Android JSON token endpoints remain separate. Serve
the browser pages through HTTPS before entering real credentials; the direct HTTP listener is
intended for local development or a trusted reverse proxy.

## Read-only admin interface

Open `/admin` for a server-rendered dashboard with database totals, MCC filtering, recent tower
consensuses, and individual tower details. The pages use Askama templates and Bootstrap 5.3.8 from
the jsDelivr CDN. They contain no create, edit, or delete controls.

The browser prompts for HTTP Basic credentials: enter the administrator's email and password.
The dashboard also accepts an administrator access token in the
`Authorization: Bearer <token>` header. Serve it over HTTPS so credentials are encrypted in transit.

## API

- `POST /v1/cells` accepts the app's plain or gzip OpenCellID CSV and requires `X-Device-Id`.
- `GET /v1/cells.csv.gz?mcc=255&since=0` streams confirmed towers to existing apps.
- `GET /v1/towers?mcc=255&since=0&limit=500` returns published and pending consensus as JSON;
  `GET /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` returns one tower.
- `PUT /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` creates or updates a trusted tower. Its JSON
  body contains `lat`, `lon`, `range_m`, and `samples`.
- `DELETE /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` removes the tower and all contributions.
- `GET /v1/events` upgrades to a WebSocket that emits `ready`, `tower_upserted`,
  `tower_deleted`, and `resync_required` JSON events. On `resync_required`, reload the management
  list because the client fell behind the bounded event queue.
- `GET /health` reports readiness and the number of published towers.

Register and sign in with `POST /v1/auth/register` or `/v1/auth/login`, sending JSON
`{"email":"user@example.org","password":"..."}`. Both return `access_token`, `refresh_token`,
`expires_in` (seconds), and `account`. Send the access token as `Authorization: Bearer <token>` for
uploads and, for administrators, management calls and WebSocket upgrades. Use
`POST /v1/auth/refresh` with `{"refresh_token":"..."}` to rotate a refresh token; send the same body
to `/v1/auth/logout` to revoke the session. `GET /v1/auth/me` returns the signed-in account.
Access tokens last 15 minutes and refresh tokens last 30 days.

Password recovery needs all five environment variables: `CELLS_PUBLIC_URL` (an HTTPS base URL),
`CELLS_SMTP_HOST`, `CELLS_SMTP_USERNAME`, `CELLS_SMTP_PASSWORD`, and `CELLS_SMTP_FROM`. The app
calls `POST /v1/auth/password-reset/request` with an email. The one-use email link opens a
server-hosted form and expires after 30 minutes. A successful reset revokes every session for
that account. Registration does not verify email ownership. If SMTP is unconfigured, recovery
requests return `503 MAIL_UNAVAILABLE`.

## Seed import and checks

`--import towers.csv.gz [--mcc 255,256]` imports trusted OpenCellID-format rows before listening.
The SQLite database replaces the Kotlin server's contribution gzip and cannot use that old internal
file as a database; re-import the original OpenCellID/Mozilla seed export when migrating.

## Profiling and traffic simulation

The optional `profiling` feature adds hotpath-rs function timing around CSV parsing, upload
transactions, movement checks, consensus recomputation, and downloads. It is absent from ordinary
server builds. Give the profiled server `--profile-output path.json` and stop it with SIGTERM or
Ctrl-C to flush the report. Set `HOTPATH_METRICS_SERVER_OFF=true` to disable hotpath's optional
local metrics listener; the JSON stays on disk and is not uploaded. Concurrent request spans
overlap, so hotpath function percentages are not additive.

```bash
cargo build --release --manifest-path server/Cargo.toml --features profiling
HOTPATH_METRICS_SERVER_OFF=true server/target/release/imu-nav-cell-server \
    --bind 127.0.0.1 --port 8080 --data cells.sqlite3 --profile-output server-hotpath.json
```

`tools/traffic_sim.py` drives the **actual HTTP server** on loopback with app-compatible gzip CSV.
Four simulated phones make concurrent uploads over four sync rounds. Each 1,000-row upload mixes
LTE, UMTS, GSM and NR cells across MCC 255/256, mostly shared cells, private cells, small repeat
updates and 1% invalid ranges. Each round follows uploads with a full or incremental gzip download,
management list, and health read; a final dashboard read exercises the management page. The driver
prepares payloads before timing, starts a fresh SQLite database for each repetition, and checks a
stable database fingerprint that excludes wall-clock update timestamps. It reports wall time and
server process CPU time separately. It needs Python 3 on Linux and uses only the standard library.

```bash
python3 server/tools/traffic_sim.py --binary server/target/release/imu-nav-cell-server \
    --out captures/server-profile --repetitions 1 --profile
# Rebuild without --features profiling for comparable uninstrumented timing:
cargo build --release --manifest-path server/Cargo.toml
python3 server/tools/traffic_sim.py --binary server/target/release/imu-nav-cell-server \
    --out captures/server-timing --repetitions 7
```

The initial profile put upload transactions well ahead of CSV decoding and consensus arithmetic.
The transaction previously prepared the same movement, contribution, recomputation and consensus
SQL again for each row. Those statements now use rusqlite's per-connection statement cache within
the existing transaction. A before/after run used the same Python driver, host, compiler, inputs,
seven repetitions and uninstrumented release builds. All repetitions matched the same accepted /
rejected totals (15,840 / 160), contribution count (13,464), consensus count (6,342), and database
fingerprint.

| Upload size | Median wall time before → after | Median server CPU before → after |
|---|---:|---:|
| 1,000 rows per upload | 1,251 → 782 ms (38% less) | 775 → 373 ms (52% less) |
| 100 rows per upload | 392 → 233 ms (41% less) | 252 → 126 ms (50% less) |

Local gitignored evidence is in `captures/server-traffic-reviewed-{before,after}/`,
`captures/server-traffic-reviewed-small-{before,after}/` and the hotpath captures under
`captures/server-traffic-profile-{before,after}/`. These are synthetic local protocol loads, not
observed production traffic or Internet latency measurements.

```bash
cargo test --manifest-path server/Cargo.toml
cargo clippy --manifest-path server/Cargo.toml --all-targets -- -W clippy::pedantic -D warnings
```
