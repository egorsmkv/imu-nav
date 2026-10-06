# Privacy deployment and data requests

[Server guide](readme.md) · [Accounts](accounts.md) · [Configuration](configuration.md)

A public cell server is operated by the person or organization that deploys it. Before enabling public mode, identify that controller, a contact for privacy requests, the actual hosting region, recipients and processors, any international transfers, and the lawful bases used for account and security records.

Review the English, Ukrainian, and Russian notice text in `server/config.production.toml` for the real deployment. Increase `CELLS_PRIVACY_VERSION` whenever a material notice change requires new consent. The server rejects uploads under an older version; downloads and sign-in remain available.

## Data and controls

| Record | Purpose | Default live retention | User control |
| --- | --- | --- | --- |
| Account email, password hash and session metadata | Sign-in, recovery and account security | Until account closure; expired tokens removed daily | Account panel; close account |
| Tower observations with random app device ID | Optional sharing and public consensus | 12 calendar months after last accepted update | App Settings or account panel withdrawal, observation deletion, account closure |
| Deleted tower-key markers | Prevent an old phone copy from restoring erased observations | Until account closure | Included in export; removed on closure |
| Precise-location diagnostic sessions | Optional troubleshooting, visible to user and admins | 30 days | App Settings or account panel withdrawal, `/debug` deletion, account closure |
| Versioned purpose consent receipts | Proof of the separate upload decisions | Until account closure | Account export and closure |
| Air-alert account preference | Optional nationwide notifications; no phone location sent | Until account closure | App Settings or account panel switch, export and closure |
| Admin audit records | Abuse investigation and operator accountability | 90 days | Operator review; relevant records in account export |
| Import rejection input | Import troubleshooting | 30 days after job completion | Operator access |
| Rate-limit IPs | Abuse prevention | Process memory only | Expire with in-memory rate windows |

The public `/v1/privacy` endpoint returns the operator notice. `/v1/privacy/me` returns the current account's two consent states. Each purpose has a separate authenticated `PUT` with the current notice version and `DELETE` for withdrawal. Deletion immediately removes the purpose's live data in one database transaction.

Tower consensus and the removal feed are updated, although downloaded copies and preexisting exports cannot be recalled. Old clients with no receipt receive `CONSENT_REQUIRED` when uploading; they may still sign in and download. The Android app stops locally first and retries an offline withdrawal after a session is available. A pending withdrawal needs the user to sign in again if they signed out before the server received it.

The account panel exports a gzip-compressed NDJSON file with account fields, the air-alert preference, tower observations, deleted-key markers, consent history, diagnostic sessions and entries, session metadata and audit entries made by the account. It excludes password hashes and token values. The browser's existing CSRF token protects withdrawal forms. Other access, correction, restriction or objection requests go to the configured rights contact; verify identity before disclosing data and record the response deadline. The [European Commission guidance on individual requests](https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/dealing-requests-individuals_en) describes the process.

## Operator checklist

1. Set `server.mode = "public"`, HTTPS `public_url`, secure cookies and complete `CELLS_PRIVACY_*` fields. Public startup rejects missing or placeholder values. Keep the direct port behind the TLS proxy and restrict database and export access.
2. Set reverse proxy and container log retention to 30 days or less. Do not log request bodies, cookies, authorization headers, full IPs or device IDs. The app server's own request log records only route, status and duration; in-memory IPs are used for rate limiting.
3. Encrypt backups, limit access, and delete each backup within 30 days. Keep the server's structured `privacy withdrawal committed`, `privacy erasure committed`, and `privacy account closed` events for at least as long as the oldest retained backup, within the 30-day log limit.

   Before restoring, extract events newer than the snapshot into a reviewed CSV in chronological order. Use `account_id,purpose` as headers; purposes are `tower_upload`, `diagnostics`, `account_sync`, or `account` for closure.

   With the server stopped, restore the snapshot, then run `imu-nav-cell-server --config server/config.toml --replay-deletions /secure/replay.csv` before reopening traffic. The command reapplies consent withdrawals, stored-data deletion, and tower consensus changes, and invalidates all restored sessions and one-use links; users must sign in again.

   For an individual observation or session deletion, replay conservatively erases all of that account's records for that purpose and revokes that purpose's consent; the person can opt in again. This avoids logging device IDs, cell keys, or diagnostic session IDs. Restrict and delete the replay CSV after verification. A restore without this replay can reintroduce erased data.
4. Document every processor, hosting region, international transfer mechanism and access role. Check whether a data protection impact assessment is required for precise-location diagnostics or large-scale monitoring; record the assessment and mitigations before deployment. [EDPB DPIA guidance](https://www.edpb.europa.eu/topics/accountability-and-compliance-tools/data-protection-impact-assessment_en).
5. Maintain an incident response contact and record. If personal data is breached, assess risk promptly and follow applicable notification duties, including the GDPR's 72-hour supervisory-authority rule when notification is required. [GDPR Articles 33–34](https://eur-lex.europa.eu/eli/reg/2016/679).

This template provides controls but does not determine a deployment's lawful bases, processor agreements, transfer safeguards, or local legal obligations. The operator must supply and verify those facts.

Account synchronization has its own `account_sync` consent. It stores private preferences and bookmark recipes until withdrawal or account closure. Account exports include the records. Withdrawing erases values and deletion markers; a non-location generation counter prevents old devices from republishing them. Update the deployment notice version before offering this new purpose.

## Browser trip history

See [private browser trip history](trip_history.md) for manual uploads, playback, storage limits, consent and deletion.
