# Administration and moderation

[Server guide](README.md) · [Newcomer glossary](glossary.md)

Administrators review contributions, moderate towers, set publication policy and manage accounts.
A quarantined tower stays out of public downloads until an administrator restores it. Seed imports
provide an initial source of tower positions.

## Administrator panel

Sign in at `/login` with an administrator account; the browser opens `/admin`. The panel shows
tower totals, MCC and status filtering, paginated towers and observations, account status,
administrator actions, and the current anti-poisoning policy. Observations can be filtered by
device, accounts by email, and audit entries by action. Forms use the browser session and CSRF token.
The pages use Askama templates and Bootstrap 5.3.8 from the jsDelivr CDN.

Open `/debug` from the admin panel to enable diagnostic uploads. The switch is off on a new or
upgraded server. Users must also turn on **Developer diagnostics** in the app. The server accepts
ordered, idempotent JSON batches at `/v1/debug/sessions`, separate from tower contributions.

Administrators can inspect the timeline and download `.rec.gz` replay recordings, trip logs and
context. A trip ID makes session creation safe to retry after a process restart. Users can access
and remove only their own sessions. Sessions expire after 30 days and
are deleted when an account closes.

A batch is limited to 8 MiB, a session to 64 MiB, and an
account to 256 MiB. A full or interrupted session may be incomplete. See `/data-usage` for the
data fields and retention rules.

For an active ordinary account, use **View as user** in the Accounts table to open its account
panel without its password. The delegated browser session lasts at most one hour and depends on
the original administrator session remaining active. A banner names the account and acting
administrator; **Return to administrator panel** restores the administrator session.

A user cannot
reach `/admin` through the delegated session. Starting, stopping, exporting, and successful
account actions made during impersonation are recorded with the administrator's ID in the audit
log. Signing out while impersonating signs out both browser sessions. Suspended accounts and other
administrators cannot be impersonated.

Administrators can correct a tower position, remove individual observations, quarantine or restore
a tower, and delete its current data after quarantine and typing `DELETE`. Quarantined towers are
hidden from public downloads while new observations continue to be stored. Deletion retains the
quarantine marker, so later uploads cannot republish the tower without an explicit restore.

Manual corrections take precedence over imported seeds; remove the `manual` observation to return
to normal consensus. Account suspension revokes all sessions and blocks login and uploads while
retaining existing observations. The acting administrator and final active administrator cannot be
suspended.

The panel accepts OpenCellID CSV/gzip seed imports up to 512 MiB compressed, 1 GiB decoded, and
two million rows. Upload staging and import progress update automatically, and administrators can
cancel during either phase. Rejected rows are available as a CSV report after a successful import.
Imports are streamed to a temporary file and applied in one cancellable database
transaction.

It can export all consensuses (including quarantine status) and raw observations as
gzip CSV; exports omit account emails and credentials.

Policy changes are persisted and activate
after an atomic full recalculation.
During the apply transaction, public reads continue against
committed data and writes wait. Job progress and cancellation are available on the dashboard.
Recalculation filters stored observations against the new Ukraine-only and maximum-range rules;
relaxing those rules can restore the stored observations.

Maximum-jump checks apply when a device
uploads an observation: the database retains only its latest observation per tower, so earlier
movement cannot be reconstructed during recalculation.
Interrupted jobs roll back; they do not resume after restart. CLI policy values initialize new
databases and saved policy overrides them on later starts.

## Seed import and checks

`--import towers.csv.gz [--mcc 255,256]` imports trusted OpenCellID-format rows before listening.
The SQLite database replaces the Kotlin server's contribution gzip and cannot use that old internal
file as a database; re-import the original OpenCellID/Mozilla seed export when migrating.
