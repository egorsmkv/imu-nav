# IMU Nav cell server

The reference sharing server preserves the Android app's gzip-CSV sync protocol while storing every
per-device contribution and materialized consensus in SQLite. SQLite runs in WAL mode, so downloads
and management reads can continue during uploads.

```bash
cargo build --release --manifest-path server/Cargo.toml
server/target/release/imu-nav-cell-server --port 8080 --data cells.sqlite3 \
    --api-key "$CELLS_API_KEY" --min-devices 2 --area ukraine --trust-proxy
```

Put the server behind a TLS reverse proxy for public deployments. `CELLS_API_KEY` may replace
`--api-key`; when set, the same bearer token protects uploads, management calls, and WebSocket
connections. `--trust-proxy` honors the first `X-Forwarded-For` address for per-IP limits and must
only be enabled when direct access to the server port is blocked. Run with `RUST_LOG=debug` for more
detailed operational logs.

## API

- `POST /v1/cells` accepts the app's plain or gzip OpenCellID CSV and requires `X-Device-Id`.
- `GET /v1/cells.csv.gz?mcc=255&since=0` streams confirmed towers to existing apps.
- `GET /v1/towers?mcc=255&since=0&limit=500` returns published and pending consensus as JSON;
  `GET /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` returns one tower.
- `PUT /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` creates or updates a trusted tower. Its JSON
  body contains `lat`, `lon`, `range_m`, and `samples`.
- `DELETE /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` removes the tower and all contributions.
- `GET /v1/events` upgrades to a WebSocket that emits `ready`, `tower_upserted`, and
  `tower_deleted` JSON events.
- `GET /health` reports readiness and the number of published towers.

The management list, mutations, and WebSocket use `Authorization: Bearer <key>` when an API key is
configured. WebSocket clients must send the header during the HTTP upgrade.

## Seed import and checks

`--import towers.csv.gz [--mcc 255,256]` imports trusted OpenCellID-format rows before listening.
The SQLite database replaces the Kotlin server's contribution gzip and cannot use that old internal
file as a database; re-import the original OpenCellID/Mozilla seed export when migrating.

```bash
cargo test --manifest-path server/Cargo.toml
cargo clippy --manifest-path server/Cargo.toml --all-targets -- -W clippy::pedantic -D warnings
```
