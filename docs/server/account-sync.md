# Settings and bookmark synchronization

[Server guide](readme.md) · [Privacy](privacy.md)

After signing in, the app asks before storing selected settings and bookmarks on the sharing server.
Accept once on each device and account. Later logins restore the account profile automatically.
Declining keeps the phone local; enable synchronization later in Settings.

Account settings replace matching phone preferences on initial restore. Saved places and saved
routes merge by ID, so different bookmarks can share names. Existing server deletion markers win
over stale local copies. Route geometry is recalculated on the phone, not stored on the server.

## Included data

Synchronization includes language, voice, vibration, travel mode, navigation algorithm and method,
terrain matching, power mode, screen-on preference, map-start choice and fixed location, offline-map
preference, route-map caching, online routing, tower visibility and selected radio types.

Bookmarks contain names, coordinates, endpoint labels, travel modes and automatic or fixed starts.
The server keeps these records private to the authenticated account. They are not tower observations
and do not contribute to public downloads.

Credentials, proxy configuration, service addresses, Bluetooth devices, permissions, map files,
learned calibration, search history, recordings, experiments and diagnostic settings stay local.
Air-alert preferences continue using their separate account API.

## Offline use and account changes

Edits are saved locally first. Sync runs after local changes and when the app returns to the foreground;
**Sync now** retries manually. Network failures preserve local changes. Closing the app stops network
work; the next foreground session retries. Restoration waits until navigation and route planning stop.
A bookmark restore commits only if the local revision still matches the snapshot used for merging.
A completed rename, deletion or profile switch rejects a stale restore and schedules a fresh merge.

Edits to separate items merge. Concurrent edits to the same item use the version already accepted by
the server, and the app reports the conflict. Phone clocks do not decide which edit wins. Deletions
remain as revisioned markers until synchronization data is erased.

Signing out hides account bookmarks and restores the original local preferences and bookmarks.
Private account caches remain on that device for its next login. Changing server or account uses a
separate cache and cannot upload another account's bookmarks.

## Disable and erase

Turning off synchronization stops transfers immediately and requests deletion of the server copy.
If offline, the app saves the withdrawal and retries after signing in to that account. Local account
copies remain on the phone. The browser account panel can also withdraw consent and erase the copy.

Account exports include synchronized entries. Account closure deletes them. After restoring a backup,
replay `account_sync` withdrawals before reopening traffic, as described in the privacy guide.
Older servers return an unavailable status without preventing login or local bookmark use.

## API

Login and token refresh responses add an opaque `sync_identity`. It remains stable across email
changes and differs after account deletion, even if SQLite reuses the numeric account ID. Clients
scope local profiles by server URL, account ID and this identity.

`GET /v1/account-sync` requires an account bearer token and returns protocol `version: 1`,
`account_id`, `generation`, `enabled`, `notice_version`, `entries`, and `conflicts`.
Without current consent it returns metadata and no entries. Local deployments use notice version
`local`; public deployments use the operator's notice version.

Grant or withdraw the `account_sync` purpose through `PUT` or `DELETE` on
`/v1/privacy/consents/account_sync`. Grant requests contain `notice_version`.

`PUT /v1/account-sync` accepts `version`, the observed `generation`, and `changes`. Each change has
`kind` (`setting` or `bookmark`), `key`, its last acknowledged `revision` (zero for a new item), and
`value`. A null bookmark value is a deletion marker. Settings use an explicit allowlist. Bookmark
values describe a `place` with an `endpoint`, or a `route` with `origin`, `destination` and `mode`.
A null origin means automatic start. Each endpoint has `point: {lat, lon}` and a nullable `label`.

Requests are limited to 128 changes and 512 KiB. An account can retain 4,096 entries, including
deletion markers. Keys are at most 128 ASCII letters, digits, underscores or hyphens; bookmark
names are at most 512 UTF-8 bytes and endpoint labels at most 1,024 bytes. Invalid data is rejected
before writes. Exceeding the account quota rolls back the batch.

Matching revisions advance by one. Repeating an already accepted value is idempotent. Conflicting
entries are returned as `kind:key` identifiers while independent changes are accepted. The response
includes the authoritative snapshot. Withdrawal increments `generation`, so stale requests remain
invalid even if synchronization is enabled again.

## Typed preference restoration

The Android client decodes all known synchronized settings into typed values before invoking setters.
Malformed known settings reject the snapshot without applying any preference setters. Travel mode
is applied last because it can start route planning. After bookmark replacement, the client decodes
the final merge again so local preference edits made during the operation are preserved.

Unknown setting keys are retained verbatim in their account profile but are not applied by this
version. They remain present in later sync captures, so an older client does not upload accidental
deletions for settings introduced by a newer client. Keys, canonical JSON and protocol version 1
remain unchanged.

Route bookmark recipes optionally carry `roadPreference` (`MAIN_ROADS` or `BALANCED`).
Missing values default to main roads in current apps. Update the server with the app to
synchronize recipes containing this field; earlier servers reject unknown recipe fields.
