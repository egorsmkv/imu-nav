# Browser accounts and personal data

[Server guide](readme.md) · [Newcomer glossary](glossary.md)

A person can sign in through browser pages to inspect, export or delete their own tower
observations. Browser sessions use cookies; Android app sessions use separate JSON tokens.
Account exports and deletion operate on that account's data.

## Browser account pages

Open `/data-usage` for a public explanation of what the server receives, stores, publishes, and
lets users remove. Open `/signup` to create an account or `/login` to sign in. `/forgot-password`
requests a reset email when SMTP is configured.

The web pages include a language selector for English, Ukrainian, and Russian.
They initially use
the browser's preferred language, then remember the selected language in a one-year cookie.
JavaScript applies the translations in the browser; API responses and exported files are unchanged.
The shared footer links to the English Telegram group for English pages and the other group for
Ukrainian or Russian pages.

The pages share one navigation bar and footer. The account panel starts with sharing and
diagnostics, then provides observation filters, downloads, account security, and session controls.
Destructive account actions are grouped behind expandable sections.

The `/account` panel filters the account's cell
observations by device, MCC, and update time; pages through 100 results at a time. It also downloads a complete compressed NDJSON account archive. It can delete individual observations or all observations, pause and
resume uploads, change password or email, revoke other sessions, and close the account.

Optional
developer diagnostics are kept separately at `/debug`, where a user can review, download, and
delete their own sessions. Other accounts' and seed observations are never included in
account exports or deletions. Deletion recalculates shared consensus and updates management WebSocket subscribers.

Web pages show observation, session, tower, and administrator activity times in UTC. The account
page accepts UTC date and time filters with second precision; existing links with Unix-second
`from_s` and `to_s` values still work. NDJSON timestamps remain Unix seconds.

Public deployments require a separate current receipt for tower uploads and diagnostics.
Users may withdraw each purpose in the Android app or browser panel; the server erases the corresponding live data. The account archive includes consent history, diagnostic entries, session metadata and the account fields but no password hashes or token values. See [privacy operations](privacy.md).

Deleted cell keys remain blocked for that account so a phone with an old local copy cannot
silently reupload them. Deleting all also pauses uploads; resuming needs the account password.

Browser sessions use a seven-day, HttpOnly, SameSite=Strict cookie. Account forms require a
session-specific form token; bulk deletion and account closure require typed confirmation. The Android JSON
token endpoints remain separate. Serve
the browser pages through HTTPS before entering real credentials; the direct HTTP listener is
intended for local development or a trusted reverse proxy.
