# Newcomer glossary for the cell server

[Server guide](readme.md) · [Project glossary](../glossary.md)

These terms describe the server's data and deployment. The linked guides contain the exact API,
limits and operational steps.

- **Cargo package** — A manifest and source tree that Cargo builds. `server/` is one package with
  a library crate and a binary crate. [Server overview](readme.md).
- **Crate** — One Rust compilation unit. Here the library crate contains reusable server logic,
  while the binary crate starts the process. [Server overview](readme.md).
- **HTTP API** — Requests and responses used by the app and management tools to upload, download
  and inspect data. [API guide](api.md).
- **WebSocket** — A connection that stays open so management clients can receive change events
  without repeatedly asking the server. [API guide](api.md).
- **CSV / gzip** — A text table format and its compressed form. The app uploads and downloads
  tower rows in these formats. [API guide](api.md).
- **Cell key** — The radio and network identifiers that name one cell. A tower position is stored
  and moderated under that key. [API guide](api.md).
- **MCC / MNC** — Country and mobile-network codes that are part of a cell key. They are identifiers,
  not coordinates. [Accounts guide](accounts.md).
- **Contribution / observation** — One device's learned position for a cell. The server stores
  contributions before deciding what to publish. [Server overview](readme.md).
- **Consensus** — The server's combined tower position based on eligible contributions. A tower
  normally needs observations from at least two devices before publication. [Administration](administration.md).
- **Seed** — An imported starting tower position, such as an OpenCellID row. It does not replace
  an account's own observation. [Administration](administration.md).
- **Manual correction** — An administrator-supplied tower position that takes priority over an
  imported seed. [Administration](administration.md).
- **Quarantine** — A moderation state that hides a tower from public downloads while retaining
  incoming observations. Restoring it requires an administrator. [Administration](administration.md).
- **Anti-poisoning policy** — Rules that limit implausible or abusive contributions before they
  affect published towers. [Administration](administration.md).
- **SQLite / WAL** — SQLite is a database kept in one file. Write-ahead logging (WAL) lets readers
  continue while an upload writes. It is the default backend. [Configuration](configuration.md).
- **PostgreSQL** — A separate database service that Compose uses for public deployments.
  [Compose deployment](deploy-with-compose.md).
- **Transaction** — A group of database changes applied together or rolled back together after
  a failure. [Administration](administration.md).
- **Migration** — Moving existing data to a new database or major database version.
  [Configuration](configuration.md) · [Compose upgrade](deploy-with-compose.md).
- **HTTPS / TLS** — Encryption for traffic between a client and the public server. Caddy provides
  it in the Compose deployment. [Compose deployment](deploy-with-compose.md).
- **Reverse proxy** — A server in front of the Rust process that accepts public HTTPS and forwards
  requests to it. Caddy is the configured proxy. [Compose deployment](deploy-with-compose.md).
- **Access token / refresh token** — App credentials: a short-lived token for requests and a
  longer-lived token for getting a new one. [API guide](api.md).
- **Browser session / cookie** — The browser's separate sign-in state, carried in an HttpOnly
  cookie. [Accounts guide](accounts.md).
- **CSRF token** — A form-specific value that helps reject unwanted browser actions submitted
  from another site. [Accounts guide](accounts.md).
- **SMTP** — The email service used for address confirmation and password recovery.
  [API guide](api.md).
- **Diagnostics** — Opt-in trip records kept separately from public cell observations.
  [Administration](administration.md).
- **Sync cursor** — A server-supplied time marker that helps an app request later tower changes
  and removals. [API guide](api.md).
- **Rate limit** — A cap on requests or uploads over time, used to protect the service.
  [Administration](administration.md).
