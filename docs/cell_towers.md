# Cell towers and sharing server

[Documentation index](../README.md)

The phone can estimate a rough position from nearby cell towers when GPS is unavailable. It needs a tower database for the area. Tower observations can also be shared with a server if you choose.

If the phone has no cellular hardware, no SIM, an unavailable SIM, or no fresh usable
cell observations, the app explains that it cannot determine a position from cell towers.
Failed scans and towers missing from the database also have separate explanations in
positioning diagnostics and **Settings → Cell towers**. The status is rechecked at the
normal scan interval, so the warning clears when positioning becomes available again.
Mobile internet is not required for offline tower positioning.

When there is no trusted GPS or network/cell position, the map displays the reason.
Before navigation starts, it explicitly says **Cannot determine your position** and
asks you to select your starting position with **Start here**. A manual start or an
active dead-reckoned route remains usable; its warning concerns cell positioning.
Trusted GPS or a fresh network/cell fix takes precedence on the map.

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

A tower normally needs contributions from at least two devices before the server publishes it. For a server outside your local network, use HTTPS. The repository's Compose setup includes Caddy for HTTPS on the domain in `CELLS_PUBLIC_URL`; the [server guide](server/readme.md) covers DNS, setup, accounts and administration.

The server's public `/data-usage` page explains what uploads contain and how to review or delete your observations.
Developer diagnostics are an independent, opt-in upload. See [Trips](trips.md) for setup and deletion steps; diagnostic events are never used to calculate shared towers.

See the [glossary](glossary.md) and the [detailed cell-tower reference](reference/cell_towers.md) for data sources, privacy and moderation behaviour.
