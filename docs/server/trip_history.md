# Private browser trip history

Sign in to the sharing server in the Android app. Open **History**, select a
completed trip, then choose **Upload to trip history**. Read the location-sharing
notice before confirming. Uploading is manual and separate from diagnostic uploads.

The archive stores recorded engine positions, their position source and uncertainty,
planned routes, and trip statistics. It does not store the original recording, raw
GPS fixes, sensor streams, or logs. A damaged recording can still provide a partial
playback; these trips are labelled incomplete.

## Playback in a browser

Use **Open in browser** after uploading, or visit `/trips` on your sharing server.
Sign in with your own account. Filter by date, select a trip, and use Play, Pause,
Restart, the timeline, or the playback speed selector. Download saves the playback
JSON, not a recording that can rerun the navigation engine.

Red lines show the recorded journey; blue lines show the planned route. The marker
shows a recorded position and its uncertainty circle. Positions are sampled at most
once per second, except source changes and segment endpoints. Playback keeps file
order across restarts. It does not invent time spent while the app was stopped.
Gaps longer than five seconds and recording restarts break the track. The timeline
and statistics remain available if the map cannot load.

MapLibre GL JS 5.20.0 runs from this server, including its worker and stylesheet.
OpenFreeMap supplies the map: it receives your IP address and requests identifying
the visible map area. The trip document and account credentials are not sent to it.
The page sends no referrer header. Map attribution remains visible.

## Storage and deletion

Trips remain until you delete them, withdraw archive consent, or close your account.
Deleting a server copy does not delete the phone recording. A full archive rejects
new trips and keeps existing ones. Default limits are 1,000 trips and 256 MiB per
account, with a 16 MiB limit for each upload.

Operators can configure lower upload limits or different account quotas:

```toml
[trip_archive]
account_bytes = 268435456
account_trips = 1000
upload_bytes = 16777216
```

`upload_bytes` must be between 1 and 16 MiB; the account byte limit must be at least
that large. Usage counts the canonical validated JSON stored in the database.
SQLite and PostgreSQL use the same account-scoped archive table. The database
migration utility includes that table. Existing backup retention and deletion
replay procedures also apply to trips; use purpose `trip_archive` when replaying a
consent withdrawal after restoring a backup.

The account page has **Withdraw trip archive consent and erase trips**. Account
privacy exports include playback documents. Administrators cannot browse archives
through impersonation, and impersonated sessions cannot download privacy exports.
Database operators still have technical access to stored data and must follow the
published privacy notice and access controls.

## API

Requests use account bearer authentication, or a genuine owner browser session.
Browser DELETE requests require `X-CSRF-Token`. Responses use `Cache-Control:
no-store`. Administrator impersonation is rejected.

| Request | Result |
| --- | --- |
| `GET /v1/trips?offset=0&from_ms=0&to_ms=...` | Up to 50 summaries, `more`, quota usage, limits, current consent, notice version and notice |
| `PUT /v1/trips/{client_trip_id}` | Atomically store a version 1 playback document; requires current `trip_archive` consent |
| `GET /v1/trips/{client_trip_id}` | Owner's playback JSON download |
| `DELETE /v1/trips/{client_trip_id}` | Remove only that owner's server copy; missing copies also succeed |

Grant or withdraw the independent consent through
`/v1/privacy/consents/trip_archive`. Local deployments use notice version `local`.
Identical retries succeed. A different document with the same ID returns 409;
remove the old copy explicitly before replacing it. Invalid documents return 400 or 422,
quota violations return 413, and missing or other-account trips return 404.

Version 1 contains `summary`, `incomplete`, `positions`, and `routes`. Summary times
are Unix milliseconds. Position and route `time_ms` values are milliseconds on the
relative playback timeline; `segment` identifies a continuous recording section.
Positions contain `lat`, `lon`, `uncertainty_m` and a stable source name. Route points
use GeoJSON order `[longitude, latitude]`. Arrays and numeric ranges are bounded;
unknown document fields are rejected to avoid unintentionally retaining raw data.

## Development checks

Run the Rust tests with a disposable `TEST_POSTGRES_URL` for both database backends.
Run `node --test server/tests/web/trip-player.test.cjs` for seeking and track gaps.
Core `TripPlaybackTest` covers sampling, restart order and truncated gzip recovery.
Regenerate vendored map assets with `python3 tools/vendor_trip_map.py`; the download
is pinned by version and SHA-512 checksum. Keep its licence with the assets.
