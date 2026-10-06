# Deploy with Compose, PostgreSQL and Caddy

[Server guide](README.md) · [Newcomer glossary](glossary.md)

Compose runs the Rust server, a PostgreSQL database and Caddy for HTTPS on the domain in
`CELLS_PUBLIC_URL`. This guide covers initial deployment, backups and upgrading a PostgreSQL 16
deployment. The credentials belong in a local `.env` file.

## Production with Compose

The repository's [Compose file](../../compose.yml) builds the Rust server, starts PostgreSQL 18,
and runs Caddy as the HTTPS reverse proxy. PostgreSQL files and Caddy's TLS state live in named
volumes. The server reads the checked-in [production TOML](../../server/config.production.toml);
it contains no credentials. PostgreSQL and SMTP credentials come from a local `.env` file:

```bash
cp .env.example .env
chmod 600 .env
# Edit .env: set passwords, URL, SMTP and every CELLS_PRIVACY_* field. Review the notice text in server/config.production.toml.
docker compose up -d --build
docker compose run --rm server --config /etc/imu-nav/config.toml --create-admin admin@example.org
docker compose ps
curl http://127.0.0.1:8080/health
```

Run these commands from the repository root. Generate a URL-safe database password with
`openssl rand -hex 32`; the same value is used by PostgreSQL and the server connection URL.
The URL and SMTP settings are required so registration can verify email and users can reset
passwords. `CELLS_PUBLIC_URL` must be the public `https://` domain with no trailing path, for
example `https://cells.example.org`. Point that domain's DNS record at this host and allow inbound
TCP ports 80 and 443; Caddy uses them to obtain and renew HTTPS certificates. UDP port 443 enables
HTTP/3. Caddy's [Caddyfile](../../Caddyfile) reads the same URL and proxies to `server:8080`.
The administrator command asks for the password on the terminal and exits after creating the
account. To follow logs, run `docker compose logs -f server caddy`.

Compose binds the HTTP server to host `127.0.0.1:8080` for local health checks and does not
publish PostgreSQL. Caddy reaches the server over the Compose network and replaces incoming
`X-Forwarded-For` and `X-Forwarded-Proto` headers. Keep port 8080 inaccessible from outside the
host. The TOML enables `trust_proxy` for per-IP limits and `secure_cookies` for HTTPS browser
sessions. The server's `/tmp` is a 1 GiB temporary filesystem for
imports and downloads; it is not persistent. Back up the PostgreSQL volume regularly, for
example with `docker compose exec -T postgres pg_dump -U imu_nav -d imu_nav -Fc > /secure/backup/cells.dump`.
Keep encrypted backups outside this repository, restrict access, and expire them within 30 days. Follow the [privacy operations guide](privacy.md) to replay withdrawals and account closures after a restore, before reopening the server.
After restoring PostgreSQL while `server` and `caddy` are stopped, grant the server's UID 10001
read access to the reviewed deletion CSV, mount it into a one-off server container, and run:

```bash
docker compose run --rm --no-deps -v /secure/replay.csv:/secure/replay.csv:ro \
  server --config /etc/imu-nav/config.toml --replay-deletions /secure/replay.csv
docker compose up -d server caddy
```

The replay command revokes all restored sessions; users sign in again.
Changing `POSTGRES_PASSWORD` in `.env` after PostgreSQL has initialized does not rotate the
password stored in its database.

**Upgrading an existing Compose deployment from PostgreSQL 16:** [PostgreSQL 18 requires a major-version migration](https://www.postgresql.org/docs/18/release-18.html),
and the [official container changed its data mount](https://github.com/docker-library/docs/blob/master/postgres/content.md#pgdata).
Stop the server before exporting the old database, and
keep the old volume and a separate backup until the restored server has been checked. Run these
commands while the old PostgreSQL 16 container is still running:

```bash
docker compose stop server
docker compose exec -T postgres pg_dump -U imu_nav -d imu_nav -Fc > /secure/backup/cells-pg16.dump
test -s /secure/backup/cells-pg16.dump
```

After updating this repository, start only PostgreSQL 18, restore into its empty database, then
start the server. Wait until `docker compose ps` shows PostgreSQL as healthy before restoring:

```bash
docker compose rm -sf postgres
docker compose up -d postgres
docker compose exec -T postgres pg_restore -U imu_nav -d imu_nav --no-owner --no-privileges --single-transaction --exit-on-error < /secure/backup/cells-pg16.dump
docker compose up -d --build server caddy
curl http://127.0.0.1:8080/health
```

The new volume name prevents the PostgreSQL 18 container from silently initializing over the
old volume. `rm -sf` removes only the old container, leaving its data volume intact. Do not run
`docker compose down -v` during migration; it removes named volumes.
If the old database has additional roles or databases beyond this Compose setup, migrate them
separately before switching traffic.

If your installation uses the standalone `docker-compose` command, substitute it for
`docker compose` in these examples.
