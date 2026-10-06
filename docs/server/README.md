# Cell-sharing server

[Documentation index](../../README.md) · [Newcomer glossary](glossary.md)

The server lets phones share cell-tower observations. A phone uploads what it learned, the server
keeps each device's contribution, and a consensus decides which tower positions are ready for
public download. Accounts control uploads; administrators can review and moderate the data.
IMU Nav remains a research prototype, not a safety system.

`server/` is **one Cargo package**, `imu-nav-cell-server`. It builds two Rust crates, or targets:

| Target | In plain English |
| --- | --- |
| [`src/main.rs`](../../server/src/main.rs) | The executable: reads settings, opens the database, creates an administrator when asked, and starts the HTTP server. |
| [`src/lib.rs`](../../server/src/lib.rs) | The reusable server logic: routes, accounts, storage, consensus and browser pages. Tests call this library directly. |

The package is separate from the [native navigation workspace](../core/README.md). Caddy and
PostgreSQL are deployment services, not Rust crates: Caddy handles public HTTPS, while SQLite or
PostgreSQL stores persistent server data.

## Where to find the code

| Files | Responsibility |
| --- | --- |
| `main.rs`, `config.rs` | Start the process and combine TOML, environment and command-line settings. |
| `api.rs`, `auth.rs`, `privacy.rs` | Handle HTTP/WebSocket requests, sign-in, sessions, email and purpose consent. |
| `model.rs`, `csv_format.rs` | Define tower records and read or write the app-compatible CSV format. |
| `db.rs`, `store.rs`, `store/` | Use SQLite or PostgreSQL, store observations and calculate consensus. |
| `web.rs`, `admin.rs`, `debug.rs`, `templates/` | Serve account pages, administration and opt-in trip diagnostics. |
| `tests/` | Exercise the CLI, HTTP/WebSocket API and PostgreSQL backend. |

An upload travels from the API through validation into the store. The store keeps observations
and updates consensus. Public downloads expose only eligible towers; management actions can
quarantine a tower or change the publication policy. [API details](api.md) and the
[administration guide](administration.md) describe those boundaries.

## Read by task

1. [Build and start with SQLite](quick-start.md) for a first local process and administrator.
2. [Deploy with Compose](deploy-with-compose.md) for PostgreSQL, Caddy, DNS and upgrades.
3. [Configure by hand](configuration.md) for TOML, database migration, logging and proxy settings.
4. [Privacy deployment and requests](privacy.md) for notices, retention, consent and operator duties.
5. [Browser accounts](accounts.md) for personal data review, exports and deletion.
6. [Administration](administration.md) for moderation, policy and seed imports.
7. [HTTP and WebSocket API](api.md) for client endpoints and authentication.
8. [Profiling and traffic simulation](profiling.md) for tests and repeatable performance checks.

Start with the [newcomer glossary](glossary.md) for *contribution*, *consensus*, *quarantine*,
*SQLite WAL*, *WebSocket* and other server terms. The [project glossary](../GLOSSARY.md) covers
the app as a whole.
