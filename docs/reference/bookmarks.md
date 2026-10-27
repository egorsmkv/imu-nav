# Bookmarks

> Detailed reference. For easy steps, see the [guide](../bookmarks.md) or the [glossary](../glossary.md).

Tap the bookmark button on the map for **Places** and **Routes**. Save a search result with its
bookmark button, or use **Save start**, **Save destination** and **Save route** in the route editor.
Long-pressing the map selects a destination that can then be saved. Give each bookmark a name;
the library supports filtering, renaming and confirmed deletion. Saved places also appear above
recent searches and can fill either route endpoint without an address-search pack or network.

A saved route remembers the endpoints, car/walking mode and car road preference, then calculates a fresh preview when
opened. **My location** remains automatic and uses the permitted position available at that time;
a manually selected start stays fixed. Without a usable position, select a manual start or wait for
positioning. Opening a bookmark never starts navigation. Stop an active trip before applying a
bookmark; browsing and management remain available during navigation.

Bookmarks persist locally in a separate SQLite database and survive routing-pack replacement.
Saved routes contain their own endpoint copies, so renaming or deleting a place does not change
any route.

Optional [account synchronization](../server/account-sync.md) merges bookmarks with the sharing
server. Each account has a separate local database; signing out restores the original local profile.
File import/export and Android backups remain disabled.

The bookmark database migrates version 1 to version 2 by adding `road_preference`, retaining
all saved endpoints. Old records and synchronized recipes without the field default to main
roads. New recipes round-trip the choice through SQLite and account synchronization.
