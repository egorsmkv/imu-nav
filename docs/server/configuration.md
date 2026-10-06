# Manual configuration, storage and logging

[Server guide](readme.md) · [Newcomer glossary](glossary.md)

Use a TOML file when running the server directly or choosing between SQLite and PostgreSQL. The
same database settings must be used for startup and administrator commands. Public installations
need HTTPS through a trusted reverse proxy.

## Manual configuration

Copy the [example configuration](../../server/config.example.toml), edit it, and use the same file for server
startup and administrator setup:

```bash
cp server/config.example.toml server/config.toml
chmod 600 server/config.toml
server/target/release/imu-nav-cell-server --config server/config.toml --create-admin admin@example.org
server/target/release/imu-nav-cell-server --config server/config.toml
```

The server reads TOML only when `--config` is supplied. `[database] backend = "sqlite"` uses its
`path`; relative paths in TOML are relative to the config file. To use PostgreSQL, set
`backend = "postgres"`, remove `path`, and set `url` to a PostgreSQL connection URL.

Configure
TLS and the database server's certificate trust for remote connections. The PostgreSQL backend
uses a bounded connection pool and supports one running server process per database; the live
WebSocket and job state is process-local. Never commit the real config: `server/config.toml` is
gitignored, and it should be readable only by the server operator.

Explicit command-line options override corresponding environment variables, which override TOML
values, which override built-in defaults. `CELLS_DATABASE_URL` can override the PostgreSQL URL
without putting a credential in command-line arguments. The database backend itself is selected
by TOML. A policy saved by an administrator overrides the initial `[policy]` values after a
restart. Existing `--data` SQLite commands continue to work without a config file.

To move an existing server, stop writes to SQLite, take a backup, and point a PostgreSQL config
at an empty database. Then run:

```bash
server/target/release/imu-nav-cell-server --config server/config.toml \
    --migrate-from-sqlite backup.sqlite3
```

The command copies accounts, consent receipts, sessions, tower data, settings, audit records, and diagnostics in one
PostgreSQL transaction. It refuses a nonempty target and leaves the SQLite source untouched. Start
the server with the PostgreSQL config and check its startup counts. Keep the SQLite
backup until the new server has been checked; rollback is selecting the original SQLite database.
Do not run both servers against the same live user traffic during cutover.

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
For public HTTPS behind a reverse proxy, set `server.secure_cookies = true` in TOML or pass
`--secure-cookies`. This marks browser sessions `Secure` without trusting forwarded client IPs.

The server also recognizes `X-Forwarded-Proto: https` for the cookie flag; have the proxy replace
that header. Local mode binds to loopback by default and rejects non-loopback binds. Public mode
requires explicit `server.mode = "public"`, HTTPS `public_url`, secure cookies and complete privacy
metadata; see [privacy operations](privacy.md).

Public cell downloads are streamed from consistent database snapshots in bounded pages; at most
two run at once, and excess requests receive HTTP 429. The `/health` body remains `ok <count>`,
with counts cached for up to 30 seconds to keep frequent checks cheap.

Optional nationwide air-raid alerts use a dedicated UkraineAlarm API key and a public HTTPS
webhook. Configure them as described in [air-raid alerts](air-alerts.md); the feature remains off
without both private environment variables.
