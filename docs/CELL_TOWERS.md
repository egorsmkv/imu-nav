# Cell towers and sharing server

[Documentation index](../README.md)

The phone can estimate a rough position from nearby cell towers when GPS is unavailable. It needs a tower database for the area. Tower observations can also be shared with a server if you choose.

## Add tower data

1. During setup, choose **Install built-in towers** if offered. This is optional.
2. In **Settings → Cell towers**, you can also import a CSV file or download OpenCellID or Mozilla data. OpenCellID needs a token that you enter yourself.
3. The app initially chooses the serving network's country code (MCC), if available. Check or edit it before importing, downloading or syncing. Without a detected code, enter one in Settings.
4. Open the cell status and usage history to see what the phone is using.

## Sync with a sharing server

1. Open **Settings → Cell towers → Sharing server** and enter your server's URL.
2. Register or sign in with your email and password. Do not give a test server a password you use elsewhere.
3. Tap **Sync now**, or enable automatic sync. The app uploads learned tower positions and downloads shared towers and removals.
4. Sign out when you no longer want to upload. Public downloads can still work without an account.

A tower normally needs contributions from at least two devices before the server publishes it. For a server outside your local network, use HTTPS. The repository's Compose setup includes Caddy for HTTPS on the domain in `CELLS_PUBLIC_URL`; the [server guide](../server/README.md) covers DNS, setup, accounts and administration.
The server's public `/data-usage` page explains what uploads contain and how to review or delete your observations.
Developer diagnostics are an independent, opt-in upload. See [Trips](TRIPS.md) for setup and deletion steps; diagnostic events are never used to calculate shared towers.

See the [glossary](GLOSSARY.md) and the [detailed cell-tower reference](reference/CELL_TOWERS.md) for data sources, privacy and moderation behaviour.
