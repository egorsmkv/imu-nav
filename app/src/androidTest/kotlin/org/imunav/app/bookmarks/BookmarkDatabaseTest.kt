package org.imunav.app.bookmarks

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SQLite round trips, isolated from the user's bookmarks and independent of routing packs. */
@RunWith(AndroidJUnit4::class)
class BookmarkDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "bookmarks-test-${UUID.randomUUID()}.db"
    private val point = BookmarkPoint(GeoPoint(50.45, 30.52), "Київ")

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun routesAndPlacesSurviveReopeningAndRemainIndependent() {
        val place = SavedPlace("place", "Home", point)
        val automatic = SavedRoute("auto", "Drive", BookmarkOrigin.Automatic, point, TravelMode.CAR)
        val fixed = SavedRoute("fixed", "Walk", BookmarkOrigin.Fixed(BookmarkPoint(GeoPoint(49.0, 28.0), "Start")), point, TravelMode.FOOT)
        BookmarkDatabase(context, databaseName).use { db -> listOf(place, automatic, fixed).forEach(db::save) }
        BookmarkDatabase(context, databaseName).use { db ->
            assertEquals(setOf(place, automatic, fixed), db.load().toSet())
            db.save(place.copy(name = "Office"))
            assertEquals("Office", db.load().single { it.id == place.id }.name)
            db.delete(place.id)
        }
        BookmarkDatabase(context, databaseName).use { db ->
            assertEquals(setOf(automatic, fixed), db.load().toSet())
            db.delete(automatic.id)
            db.delete(fixed.id)
            assertTrue(db.load().isEmpty())
        }
    }

    @Test
    fun repeatedSaveReplacesByIdWithoutMergingDifferentBookmarks() {
        val first = SavedPlace("one", "Home", point)
        val second = first.copy(id = "two")
        BookmarkDatabase(context, databaseName).use { db ->
            db.save(first)
            db.save(first)
            db.save(second)
            assertEquals(setOf(first, second), db.load().toSet())
        }
    }

    @Test
    fun failedSnapshotRestorePreservesAllOriginalBookmarks() {
        val first = SavedPlace("first", "Home", point)
        val second = SavedPlace("second", "Work", point)
        BookmarkDatabase(context, databaseName).use { db ->
            db.replaceAll(listOf(first, second))
            db.writableDatabase.execSQL("CREATE TRIGGER fail_snapshot BEFORE INSERT ON bookmarks BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            try {
                db.replaceAll(listOf(first.copy(name = "Changed")))
                fail("Expected snapshot rollback")
            } catch (_: SQLiteException) {
                assertEquals(setOf(first, second), db.load().toSet())
            }
        }
    }

    @Test
    fun failedReplacementRollsBackTheOriginalRow() {
        val place = SavedPlace("place", "Home", point)
        BookmarkDatabase(context, databaseName).use { db ->
            db.save(place)
            db.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON bookmarks BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            try {
                db.save(place.copy(name = "Changed"))
                fail("Expected a failed insert")
            } catch (_: SQLiteException) {
                assertEquals(listOf(place), db.load())
            }
        }
    }
}
