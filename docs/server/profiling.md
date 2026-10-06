# Profiling and traffic simulation

[Server guide](readme.md) · [Newcomer glossary](glossary.md)

Use synthetic local traffic to find expensive operations. Use ordinary release builds to compare
speed: instrumentation adds overhead, and loopback timings do not represent production latency.

## Capture function timings

The optional `profiling` feature enables the pinned hotpath-rs dependency. Ordinary builds do not
include it. Existing cell-sharing probes are complemented by trip validation and storage, account
sync, and finite air-alert provider and delivery operations.

`blocking_queue` measures admission to Tokio's blocking pool; `blocking_work` measures the closure
executed there. `read_gate_wait` and `write_gate_wait` measure lock admission for trips and sync.
Handler spans include those waits. Overlapping spans are not additive, and elapsed time includes
I/O waiting rather than only CPU execution. Long-lived WebSocket sessions and provider loops are
not measured as single operations.

```bash
cargo build --release --manifest-path server/Cargo.toml --features profiling
HOTPATH_METRICS_SERVER_OFF=true server/target/release/imu-nav-cell-server \
    --bind 127.0.0.1 --port 8080 --data cells.sqlite3 --profile-output server-hotpath.json
```

Stop with SIGTERM or Ctrl-C to flush the local JSON report. Disable the metrics listener with
`HOTPATH_METRICS_SERVER_OFF=true`. Probes use static function names, without arguments, credentials,
account IDs, SQL parameters, provider URLs, or trip coordinates. Reports are not uploaded.

## Repeatable HTTP workloads

The Python driver starts the actual server on loopback. Four synthetic accounts concurrently upload
cells, synchronize bookmarks, and archive trips. It also exercises browser login and CSRF,
diagnostics, moderation, exports, trip playback downloads, paginated lists, deletions, retries,
revision conflicts, and tombstones. All fixtures are synthetic.

Each repetition uses a new database. Payload preparation, registration, consent, and pagination
fixtures precede the timed phase. One warm-up repetition is excluded by default. Stored results
are fingerprinted, excluding wall-clock timestamps, and HTTP responses are checked for correctness.
Coordinate fixtures use exactly representable fractions, so concurrent upload order does not change
floating-point sums. Moderation targets a fixed shared tower instead of choosing by update time.
The driver requires Linux and Python 3 with its standard library.

```bash
python3 server/tools/traffic_sim.py --binary server/target/release/imu-nav-cell-server \
    --out captures/server-profile --repetitions 1 --profile

# PostgreSQL runs additionally require Docker and use postgres:18-bookworm.
python3 server/tools/traffic_sim.py --binary server/target/release/imu-nav-cell-server \
    --backend postgres --out captures/server-profile-pg --repetitions 1 --profile
```

PostgreSQL uses a disposable container bound only to loopback with trust authentication and synthetic
data. Use `--docker-runtime runc` if the host needs an explicit OCI runtime. It accepts no external database URL and stops the container on exit. Each repetition gets a
separate database. Never use this trust configuration for a deployed server.

Tune the workload with `--rounds`, `--rows`, `--trip-points`, and `--sync-batch`. Defaults are four
rounds, 1,000 cell rows, 10,000 trip positions, and 128 sync entries per batch. Bounds keep fixture
sizes within server limits. Use a fresh output directory for each invocation.

## What the current optimizations do

Account synchronization reuses one connection for each snapshot or update operation. A batch
prepares its lookup and upsert statements once. PostgreSQL retains the driver's prepared statement,
so repeated executions avoid parsing the same SQL again. Values are encoded using the statement's
parameter types, including nullable bookmark values.

Trip uploads validate and serialize their documents on blocking workers before waiting for the
write gate. Consent and quota enforcement remain inside the protected database operation. The
existing transaction and write-gate ordering still coordinates privacy changes and concurrent writes.

## Compare an optimization

Save the baseline binary as `captures/server-before` before editing the implementation, then build the candidate with
the same toolchain and release configuration. `--baseline` alternates run order and checks baseline and
candidate fingerprints together. Use identical workload arguments for both backends.

```bash
cargo build --release --manifest-path server/Cargo.toml
python3 server/tools/traffic_sim.py --binary server/target/release/imu-nav-cell-server \
    --baseline captures/server-before --out captures/server-timing --repetitions 7
python3 server/tools/traffic_sim.py --binary server/target/release/imu-nav-cell-server \
    --baseline captures/server-before --backend postgres --out captures/server-timing-pg --repetitions 7
```

Reports include binary hash, source revision, toolchain, workload configuration, throughput, and
per-endpoint median/p95/max latency. Source revision and dirty status describe the checkout at
invocation; the binary hash identifies the exact executable. Compare fingerprints between baseline and candidate as well as
across repetitions. Investigate a reproducible regression on either backend before keeping a change.
Avoid running unrelated CPU-intensive jobs during measurement.

Server CPU and peak RSS are sampled from `/proc` after the measured phase. They include server
startup and fixture setup, excluding the separate administrator setup process and shutdown/profile
flushing. CPU uses kernel clock ticks, so small differences below that resolution are not meaningful.
Wall time and throughput cover only measured requests. PostgreSQL CPU covers the measured phase plus
the small cost of container probes. Its peak memory is the container's cumulative cgroup peak,
including database cache and earlier repetitions. These are separate from server process costs.
Do not compare SQLite server CPU with PostgreSQL server CPU as total database cost.

Keep machine-specific measurements and profiles in gitignored `captures/`, rather than treating
historical timings as universal crate documentation. CI runs correctness smoke workloads without
hardware-dependent latency thresholds.

## Air-alert delivery

A bounded test uses a local mock provider and four WebSocket subscribers. It alternates twenty
synthetic alerts and clears, checking duplicate suppression and delivery. It never contacts the
real provider. Run it alone when collecting a report so unrelated tests cannot enter its profile.

```bash
mkdir -p captures
HOTPATH_METRICS_SERVER_OFF=true AIR_ALERT_PROFILE_OUTPUT="$PWD/captures/air-alerts.json" \
    cargo test --manifest-path server/Cargo.toml --features profiling --lib \
    air_alerts::tests::profile_air_alert_delivery -- --exact
```

## Correctness checks

```bash
cargo test --manifest-path server/Cargo.toml
cargo test --manifest-path server/Cargo.toml --features profiling
cargo clippy --manifest-path server/Cargo.toml --all-targets --all-features -- -D warnings
```

Set `TEST_POSTGRES_URL` to a disposable test database to exercise PostgreSQL contracts. CI provides
PostgreSQL 18 for these tests and enforces the server coverage gate.
