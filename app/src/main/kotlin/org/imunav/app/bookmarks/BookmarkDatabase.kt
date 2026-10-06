package org.imunav.app.bookmarks

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.imunav.core.bookmarks.Bookmark
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.BookmarkStore
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode

/** Private, offline storage unaffected by routing-pack replacement or cell database resets. */
class BookmarkDatabase(context: Context, databaseName: String = "bookmarks.db") :
    SQLiteOpenHelper(context, databaseName, null, 1),
    BookmarkStore {
    override fun close() = super<SQLiteOpenHelper>.close()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE bookmarks (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, label TEXT, automatic INTEGER, start_lat REAL, start_lon REAL, start_label TEXT, mode TEXT)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No previous schema exists. Future versions must migrate without dropping saved places.
        error("Unsupported bookmark schema migration: $oldVersion to $newVersion")
    }

    override fun load(): List<Bookmark> = readableDatabase.query("bookmarks", null, null, null, null, null, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.bookmark()) }
    }

    override fun save(bookmark: Bookmark) {
        val values = ContentValues().apply {
            put("id", bookmark.id)
            put("name", bookmark.name)
            val endpoint = when (bookmark) {
                is SavedPlace -> {
                    put("kind", "place")
                    bookmark.endpoint
                }

                is SavedRoute -> {
                    put("kind", "route")
                    put("mode", bookmark.mode.name)
                    put("automatic", if (bookmark.origin == BookmarkOrigin.Automatic) 1 else 0)
                    (bookmark.origin as? BookmarkOrigin.Fixed)?.endpoint?.let {
                        put("start_lat", it.point.lat)
                        put("start_lon", it.point.lon)
                        put("start_label", it.label)
                    }
                    bookmark.destination
                }
            }
            put("lat", endpoint.point.lat)
            put("lon", endpoint.point.lon)
            put("label", endpoint.label)
        }
        writableDatabase.transaction {
            // Atomic replacement by stable ID makes retry safe even after an interrupted UI update.
            delete("bookmarks", "id = ?", arrayOf(bookmark.id))
            insertOrThrow("bookmarks", null, values)
        }
    }

    /** Replace a synchronized snapshot atomically, retaining the old data on write failure. */
    override fun replaceAll(items: List<Bookmark>) {
        writableDatabase.transaction {
            delete("bookmarks", null, null)
            items.forEach(::save)
        }
    }

    override fun delete(id: String) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id))
    }

    private fun Cursor.bookmark(): Bookmark {
        val id = text("id")
        val name = text("name")
        val endpoint = BookmarkPoint(GeoPoint(number("lat"), number("lon")), nullableText("label"))
        return when (text("kind")) {
            "place" -> SavedPlace(id, name, endpoint)

            "route" -> SavedRoute(
                id,
                name,
                if (getInt(getColumnIndexOrThrow("automatic")) == 1) {
                    BookmarkOrigin.Automatic
                } else {
                    BookmarkOrigin.Fixed(BookmarkPoint(GeoPoint(number("start_lat"), number("start_lon")), nullableText("start_label")))
                },
                endpoint,
                TravelMode.valueOf(text("mode")),
            )

            else -> error("Unknown bookmark kind")
        }
    }

    private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.nullableText(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.number(column: String): Double = getDouble(getColumnIndexOrThrow(column))
}
