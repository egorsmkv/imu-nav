# Profiling and traffic simulation

[Server guide](README.md) · [Newcomer glossary](glossary.md)

Use repeatable local traffic to find expensive server operations and compare changes. The optional
profiling feature records function timing; the uninstrumented release build is used for speed
comparisons. These synthetic loopback numbers are not production latency.

## Measure server work

The optional `profiling` feature adds hotpath-rs function timing around CSV parsing, upload
transactions, movement checks, consensus recomputation, moderation visibility, account queries,
diagnostic cleanup, and downloads. It is absent from ordinary server builds. Give the profiled
server `--profile-output path.json` and stop it with SIGTERM or
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
removals CSV, tower list and detail, authenticated account and administrator pages, account export,
authentication status, diagnostic session upload and retry, diagnostic pages, Data Usage, and health.
The first round also quarantines one published tower. Browser pages use actual login cookies and
the diagnostics setting uses its administrator form and CSRF token. The driver
prepares payloads before timing, starts a fresh SQLite database for each repetition, and checks a
stable database fingerprint covering towers, contributions, moderation and diagnostic entries while
excluding wall-clock update timestamps. It reports wall time and server process CPU time separately.
It needs Python 3 on Linux and uses only the standard library.

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

An expanded route profile found repeated moderation lookups after uploads and while rendering the
administrator tower page. Upload notifications now fetch quarantined keys in bounded batches on one
database connection, and the dashboard query returns moderation status with each tower. With the same
expanded driver, compiler and host, three uninstrumented release repetitions of two rounds with
1,000 rows per phone reduced median wall time from 1,997 to 512 ms (74%). All runs produced the
same 7,920 accepted and 80 rejected rows, 200 stored diagnostic entries, and database fingerprint.
These measurements are synthetic loopback traffic on a development host.

```bash
cargo test --manifest-path server/Cargo.toml
cargo clippy --manifest-path server/Cargo.toml --all-targets -- -W clippy::pedantic -D warnings
```

PostgreSQL integration tests also run when `TEST_POSTGRES_URL` points at a disposable database.
The CI coverage job supplies PostgreSQL 18 and includes these tests in the server coverage gate.
