# Cell towers and sharing server

The phone can estimate a rough position from nearby cell towers when GPS is unavailable. It needs a tower database for the area. Tower observations can also be shared with a server if you choose.

## Add tower data

1. During setup, choose **Install built-in towers** if offered. This is optional.
2. In **Settings → Cell towers**, you can also import a CSV file or download OpenCellID or Mozilla data. OpenCellID needs a token that you enter yourself.
3. Set the country codes you want to keep; the default `255` is Ukraine.
4. Open the cell status and usage history to see what the phone is using.

## Sync with a sharing server

1. Open **Settings → Cell towers → Sharing server** and enter your server's URL.
2. Register or sign in with your email and password. Do not give a test server a password you use elsewhere.
3. Tap **Sync now**, or enable automatic sync. The app uploads learned tower positions and downloads shared towers and removals.
4. Sign out when you no longer want to upload. Public downloads can still work without an account.

A tower normally needs contributions from at least two devices before the server publishes it. For a server outside your local network, use HTTPS. The [server guide](../server/README.md) covers setup, accounts and administration.

See the [glossary](GLOSSARY.md) and the [detailed cell-tower reference](reference/CELL_TOWERS.md) for data sources, privacy and moderation behaviour.
