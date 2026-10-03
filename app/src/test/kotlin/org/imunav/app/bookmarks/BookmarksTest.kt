package org.imunav.app.bookmarks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.imunav.core.bookmarks.Bookmark
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.BookmarkStore
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class BookmarksTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val io = QueuedDispatcher()
    private val disk = MemoryStore()
    private val place = SavedPlace("id", "Home", BookmarkPoint(GeoPoint(50.0, 30.0)))

    @After
    fun cleanup() = scope.cancel()

    @Test
    fun initialReadFailureCanRetryWithoutOverwritingDisk() {
        disk.items = listOf(place)
        disk.fail = true
        val bookmarks = Bookmarks(disk, scope, io)
        assertTrue(bookmarks.state.value.busy)
        io.drain()
        assertFalse(bookmarks.state.value.loaded)
        assertTrue(bookmarks.state.value.failed)
        disk.fail = false
        bookmarks.reload()
        io.drain()
        assertEquals(listOf(place), bookmarks.state.value.items)
        assertTrue(bookmarks.state.value.loaded)
    }

    @Test
    fun failedSaveKeepsDialogAndPublishedStateUntilRetryCommits() {
        val bookmarks = loaded()
        bookmarks.edit(place)
        bookmarks.nameChanged(" Renamed ")
        disk.fail = true
        bookmarks.save()
        assertTrue(bookmarks.state.value.items.isEmpty())
        io.drain()
        assertNotNull(bookmarks.editor.value)
        assertTrue(bookmarks.state.value.failed)
        assertTrue(disk.items.isEmpty())
        disk.fail = false
        bookmarks.save()
        io.drain()
        assertNull(bookmarks.editor.value)
        assertEquals("Renamed", bookmarks.state.value.items.single().name)
        val reopened = Bookmarks(disk, scope, io)
        io.drain()
        assertEquals(bookmarks.state.value.items, reopened.state.value.items)
    }

    @Test
    fun duplicateSaveAndConcurrentDeleteAreGuardedWhileWriting() {
        val bookmarks = loaded()
        bookmarks.edit(place)
        bookmarks.save()
        bookmarks.save()
        bookmarks.requestDelete(place)
        assertNull(bookmarks.deleting.value)
        io.drain()
        assertEquals(1, disk.saves)
        bookmarks.requestDelete(place)
        disk.fail = true
        bookmarks.delete()
        io.drain()
        assertEquals(listOf(place), bookmarks.state.value.items)
        assertNotNull(bookmarks.deleting.value)
        disk.fail = false
        bookmarks.delete()
        io.drain()
        assertTrue(bookmarks.state.value.items.isEmpty())
        assertNull(bookmarks.deleting.value)
    }

    private fun loaded(): Bookmarks = Bookmarks(disk, scope, io).also { io.drain() }

    /** Deterministically pauses disk work so duplicate UI submissions can be exercised. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }
        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    /** The fake commits atomically, matching the store contract; IO failures leave records intact. */
    private class MemoryStore : BookmarkStore {
        var items = emptyList<Bookmark>()
        var fail = false
        var saves = 0
        override fun load(): List<Bookmark> {
            checkDisk()
            return items.toList()
        }
        override fun save(bookmark: Bookmark) {
            checkDisk()
            saves++
            items = items.filterNot { it.id == bookmark.id } + bookmark
        }
        override fun delete(id: String) {
            checkDisk()
            items = items.filterNot { it.id == id }
        }
        private fun checkDisk() {
            if (fail) throw IOException("test disk failure")
        }
    }
}
