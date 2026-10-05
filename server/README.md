# IMU Nav cell server

The reference sharing server preserves the Android app's gzip-CSV sync protocol while storing every
per-device contribution and materialized consensus in SQLite. SQLite runs in WAL mode, so downloads
and management reads can continue during uploads. Building the server requires Rust 1.88 or newer.

```bash
cargo build --release --manifest-path server/Cargo.toml
server/target/release/imu-nav-cell-server --port 8080 --data cells.sqlite3 \
    --min-devices 2 --area ukraine --trust-proxy
```

Create the first administrator locally with the same database path used by the running server:

```bash
server/target/release/imu-nav-cell-server --data cells.sqlite3 --create-admin admin@example.org
```

The command logs its progress and prompts for a hidden password in a terminal. Enter a 12–256
character password and press **Enter**; Ctrl-D is no longer needed. When standard input is a pipe,
the command reads one line instead. It never logs the password. For example, if the server uses
`--data server/cells.sqlite3`, use that exact path for administrator setup too.

Set `CELLS_LOG_LEVEL=debug` or pass `--log-level debug` to see setup and startup details.
`RUST_LOG` takes precedence and also accepts module filters, for example
`RUST_LOG=imu_nav_cell_server=debug,info`. The default level is `info`.
Startup, successful sign-ins, administrator actions and jobs, uploads, and WebSocket sessions appear at
`info`. Request outcomes appear at `debug` with the route, status, and elapsed time; health
checks appear at `trace`. Server failures appear at `warn` or `error`. Logs go to standard
error and omit passwords, tokens, request bodies, and URL query strings. For example:

```bash
RUST_LOG=imu_nav_cell_server=debug,info server/target/release/imu-nav-cell-server --data cells.sqlite3
```

Put the server behind a TLS reverse proxy for public deployments. Accounts replace the former
shared API key: users register with email and password in the Android app, and only signed-in
users can upload. Existing apps can continue downloading published towers but cannot upload.
`--trust-proxy` honors the first `X-Forwarded-For` address for per-IP limits and must
only be enabled when direct access to the server port is blocked.
`--bind 127.0.0.1` restricts the listener to loopback for local testing; the default remains
`0.0.0.0` for existing deployments.

## Browser account pages

Open `/data-usage` for a public explanation of what the server receives, stores, publishes, and
lets users remove. Open `/signup` to create an account or `/login` to sign in. `/forgot-password`
requests a reset email when SMTP is configured.
The web pages include a language selector for English, Ukrainian, and Russian. They initially use
the browser's preferred language, then remember the selected language in a one-year cookie.
JavaScript applies the translations in the browser; API responses and exported files are unchanged.

The `/account` panel filters the account's cell
observations by device, MCC, and update time; pages through 100 results at a time; and downloads
matching rows as gzip CSV. It can delete individual observations or all observations, pause and
resume uploads, change password or email, revoke other sessions, and close the account. Optional
developer diagnostics are kept separately at `/debug`, where a user can review, download, and
delete their own sessions. Other accounts' and seed observations are never included in
account exports or deletions. Deletion recalculates shared consensus and updates management WebSocket subscribers.
Web pages show observation, session, tower, and administrator activity times in UTC. The account
page accepts UTC date and time filters with second precision; existing links with Unix-second
`from_s` and `to_s` values still work. CSV and JSON timestamps remain Unix seconds.
Deleted cell keys remain blocked for that account so a phone with an old local copy cannot
silently reupload them. Deleting all also pauses uploads; resuming needs the account password.

Browser sessions use a seven-day, HttpOnly, SameSite=Strict cookie. Account forms require a
session-specific form token; destructive actions require typed confirmation. The Android JSON
token endpoints remain separate. Serve
the browser pages through HTTPS before entering real credentials; the direct HTTP listener is
intended for local development or a trusted reverse proxy.

## Administrator panel

Sign in at `/login` with an administrator account; the browser opens `/admin`. The panel shows
tower totals, MCC and status filtering, paginated towers and observations, account status,
administrator actions, and the current anti-poisoning policy. Observations can be filtered by
device, accounts by email, and audit entries by action. Forms use the browser session and CSRF token.
The pages use Askama templates and Bootstrap 5.3.8 from the jsDelivr CDN.

Open `/debug` from the admin panel to enable diagnostic uploads. The switch is off on a new or
upgraded server. Users must also turn on **Developer diagnostics** in the app. The server accepts
ordered, idempotent JSON batches at `/v1/debug/sessions`, separate from tower contributions.
Administrators can inspect the timeline and download `.rec.gz` replay recordings, trip logs and
context. A trip ID makes session creation safe to retry after a process restart. Users can access
and remove only their own sessions. Sessions expire after 30 days and
are deleted when an account closes. A batch is limited to 8 MiB, a session to 64 MiB, and an
account to 256 MiB. A full or interrupted session may be incomplete. See `/data-usage` for the
data fields and retention rules.

For an active ordinary account, use **View as user** in the Accounts table to open its account
panel without its password. The delegated browser session lasts at most one hour and depends on
the original administrator session remaining active. A banner names the account and acting
administrator; **Return to administrator panel** restores the administrator session. A user cannot
reach `/admin` through the delegated session. Starting, stopping, exporting, and successful
account actions made during impersonation are recorded with the administrator's ID in the audit
log. Signing out while impersonating signs out both browser sessions. Suspended accounts and other
administrators cannot be impersonated.

Administrators can correct a tower position, remove individual observations, quarantine or restore
a tower, and delete its current data after quarantine and typing `DELETE`. Quarantined towers are
hidden from public downloads while new observations continue to be stored. Deletion retains the
quarantine marker, so later uploads cannot republish the tower without an explicit restore.
Manual corrections take precedence over imported seeds; remove the `manual` observation to return
to normal consensus. Account suspension revokes all sessions and blocks login and uploads while
retaining existing observations. The acting administrator and final active administrator cannot be
suspended.

The panel accepts OpenCellID CSV/gzip seed imports up to 512 MiB compressed, 1 GiB decoded, and
two million rows. Upload staging and import progress update automatically, and administrators can
cancel during either phase. Rejected rows are available as a CSV report after a successful import.
Imports are streamed to a temporary file and applied in one cancellable SQLite
transaction. It can export all consensuses (including quarantine status) and raw observations as
gzip CSV; exports omit account emails and credentials. Policy changes are persisted and activate
after an atomic full recalculation. During the apply transaction, public reads continue against
committed data and writes wait. Job progress and cancellation are available on the dashboard.
Recalculation filters stored observations against the new Ukraine-only and maximum-range rules;
relaxing those rules can restore the stored observations. Maximum-jump checks apply when a device
uploads an observation: the database retains only its latest observation per tower, so earlier
movement cannot be reconstructed during recalculation.
Interrupted jobs roll back; they do not resume after restart. CLI policy values initialize new
databases and saved policy overrides them on later starts.

## API

- `POST /v1/cells` accepts the app's plain or gzip OpenCellID CSV and requires `X-Device-Id`.
- `GET /v1/cells.csv.gz?mcc=255&since=0` streams confirmed towers to existing apps.
- `GET /v1/cells/removals.csv?mcc=255&since=0` returns exact keys withdrawn by quarantine,
  deletion, or publication policy. Updated apps apply these removals to their shared-tower source
  during sync. The `X-Cell-Sync-Time` response header supplies a server-side cursor. On the first
  sync after upgrading, the app replaces its prior shared snapshot to remove older stale entries.
  Restored or republished towers receive a new update timestamp.
- `GET /v1/towers?mcc=255&since=0&limit=500` returns published and pending consensus as JSON;
  `GET /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` returns one tower.
- `PUT /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` creates or updates a manual correction. Its JSON
  body contains `lat`, `lon`, `range_m`, and `samples`.
- `POST /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}/quarantine` accepts
  `{"quarantined":true|false}`. `DELETE /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` removes
  current tower data only after quarantine; otherwise it returns `409 QUARANTINE_REQUIRED`.
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
that account. With SMTP configured, new accounts must confirm a one-use link within 24 hours
before uploading cell observations; the account panel can resend it. Email changes also require
confirmation at the new address and revoke all sessions on completion. Without SMTP, registration
remains immediately verified for local deployments, while recovery and email changes are unavailable
(`503 MAIL_UNAVAILABLE` for recovery). The account JSON includes `email_verified` and
`sharing_enabled`, and uploads return 403 while either condition blocks sharing.

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

| Upload size           | Median wall time before → after | Median server CPU before → after |
| --------------------- | ------------------------------: | -------------------------------: |
| 1,000 rows per upload |       1,251 → 782 ms (38% less) |          775 → 373 ms (52% less) |
| 100 rows per upload   |         392 → 233 ms (41% less) |          252 → 126 ms (50% less) |

Local gitignored evidence is in `captures/server-traffic-reviewed-{before,after}/`,
`captures/server-traffic-reviewed-small-{before,after}/` and the hotpath captures under
`captures/server-traffic-profile-{before,after}/`. These are synthetic local protocol loads, not
observed production traffic or Internet latency measurements.

```bash
cargo test --manifest-path server/Cargo.toml
cargo clippy --manifest-path server/Cargo.toml --all-targets -- -W clippy::pedantic -D warnings
```
