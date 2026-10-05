# Bookmarks

> Detailed reference. For easy steps, see the [guide](../BOOKMARKS.md) or the [glossary](../GLOSSARY.md).

Tap the bookmark button on the map for **Places** and **Routes**. Save a search result with its
bookmark button, or use **Save start**, **Save destination** and **Save route** in the route editor.
Long-pressing the map selects a destination that can then be saved. Give each bookmark a name;
the library supports filtering, renaming and confirmed deletion. Saved places also appear above
recent searches and can fill either route endpoint without an address-search pack or network.

A saved route remembers the endpoints and car/walking mode, then calculates a fresh preview when
opened. **My location** remains automatic and uses the permitted position available at that time;
a manually selected start stays fixed. Without a usable position, select a manual start or wait for
positioning. Opening a bookmark never starts navigation. Stop an active trip before applying a
bookmark; browsing and management remain available during navigation.

Bookmarks persist locally in a separate SQLite database and survive routing-pack replacement.
Saved routes contain their own endpoint copies, so renaming or deleting a place does not change
any route. There is no synchronization, file import/export or backup; existing Android backup
exclusions apply. This remains a research prototype, not a safety system.
