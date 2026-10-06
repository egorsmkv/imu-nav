# Build and start with SQLite

[Server guide](README.md) · [Newcomer glossary](glossary.md)

This starts one server process with a local SQLite database. It is the shortest way to try the
protocol and create an administrator account. Run the commands from the repository root. Use the
[Compose deployment](deploy-with-compose.md) for a public HTTPS service.

The reference sharing server preserves the Android app's gzip-CSV sync protocol while storing every
per-device contribution and materialized consensus in SQLite or PostgreSQL. SQLite is the default
and runs in WAL mode, so downloads and management reads can continue during uploads. Building the
server requires Rust 1.99 or newer.

```bash
cargo build --release --manifest-path server/Cargo.toml
server/target/release/imu-nav-cell-server --bind 127.0.0.1 --port 8080 --data cells.sqlite3 \
    --min-devices 2 --area ukraine
```

`--bind 127.0.0.1` keeps this first run on the local computer. `--min-devices 2` means two
devices must contribute before a tower is published, and `--area ukraine` restricts accepted
observations to that area. Public service needs the [HTTPS deployment](deploy-with-compose.md).

Create the first administrator locally with the same database path used by the running server:

```bash
server/target/release/imu-nav-cell-server --data cells.sqlite3 --create-admin admin@example.org
```

The command logs its progress and prompts for a hidden password in a terminal. Enter a 12–256
character password and press **Enter**; Ctrl-D is no longer needed. When standard input is a pipe,
the command reads one line instead. It never logs the password. For example, if the server uses
`--data server/cells.sqlite3`, use that exact path for administrator setup too.
