# Cell server API

[Server guide](readme.md) · [Newcomer glossary](glossary.md)

The Android app uploads tower observations and downloads published towers over HTTP. Accounts use
short-lived access tokens, and management clients can subscribe to WebSocket events. These
endpoints are the server-side contract for those clients.

## Endpoints

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
- `GET /v1/air-alerts/preferences` returns `enabled` and `available` for the signed-in account;
  `PUT` with `{"enabled":true|false}` changes its opt-in. New accounts default to off. Enabling
  returns 503 when the operator has not configured the UkraineAlarm provider.
- `GET /v1/air-alerts/stream` is a separate bearer-authenticated WebSocket available only to
  opted-in accounts. It starts with a `snapshot` message (`active`, `updated_s`, `stale`,
  `sequence`), followed by `changes` messages (`started`, `cleared`, `updated_s`, `sequence`).
  Regions carry `region_id`, `name_uk`, and `name_en`. A snapshot does not imply a new alert.
- `POST /v1/air-alerts/provider/{secret}` is the operator's private UkraineAlarm callback. Its
  payload is ignored; it requests an authenticated provider refresh. Never expose its secret in
  client apps or logs.
- `GET /health` reports readiness and the number of published towers.
- `GET /v1/privacy` returns the selected operator's current versioned notice in English,
  Ukrainian and Russian. `GET /v1/privacy/me` returns that notice and the signed-in account's
  current `tower_upload` and `diagnostics` consent states.
- `PUT /v1/privacy/consents/{purpose}` with `{"notice_version":"..."}` records one
  account-level consent. Purposes are `tower_upload` and `diagnostics`; the version must match
  the current notice. `DELETE` at the same path withdraws consent and erases that purpose's
  live records. Both methods require a bearer token. Public deployments return 403
  `CONSENT_REQUIRED` for tower and diagnostic uploads without a current receipt.

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
that account.

With SMTP configured, new accounts must confirm a one-use link within 24 hours
before uploading cell observations; the account panel can resend it. Email changes also require
confirmation at the new address and revoke all sessions on completion. Without SMTP, registration
remains immediately verified for local deployments, while recovery and email changes are unavailable
(`503 MAIL_UNAVAILABLE` for recovery).

The account JSON includes `email_verified` and
`sharing_enabled`, and uploads return 403 while either condition blocks sharing.
All uploads also require a valid 8–64 character `X-Device-Id` made of letters, digits
or hyphens. The server no longer uses the client IP as an identifier when this header is absent.

See [settings and bookmark synchronization](account-sync.md) for account restore, offline edits and deletion.

## Browser trip history

See [private browser trip history](trip_history.md) for manual uploads, playback, storage limits, consent and deletion.
