package org.imunav.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tests for the stream decisions that must not depend on Android notifications. */
class AirAlertFeedTest {
    private val kyiv = AirAlertRegion("14", "Київська", "Kyiv")
    private val lviv = AirAlertRegion("27", "Львівська", "Lviv")
    private val odesa = AirAlertRegion("16", "Одеська", "Odesa")
    private val kharkiv = AirAlertRegion("22", "Харківська", "Kharkiv")

    @Test
    fun initialSnapshotShowsExistingAlertsWithoutPostingStartNotifications() {
        val feed = AirAlertFeed()
        assertNull(feed.changes(1, listOf(kyiv), emptyList()))
        assertNull(feed.snapshot(-1, listOf(kyiv), stale = false))
        val snapshot = requireNotNull(feed.snapshot(7, listOf(kyiv, kyiv), stale = false))
        assertEquals(listOf(kyiv), snapshot.active)
        assertTrue(snapshot.started.isEmpty())
        assertTrue(snapshot.cleared.isEmpty())
        assertFalse(snapshot.stale)
        assertNull(feed.changes(7, listOf(lviv), emptyList()))
        assertNull(feed.snapshot(6, listOf(lviv), stale = true))
    }

    @Test
    fun laterStartsAndAllClearsUpdateTheDisplayedOblastsOnce() {
        val feed = AirAlertFeed()
        feed.snapshot(1, listOf(kyiv), stale = false)
        val start = requireNotNull(feed.changes(2, listOf(kyiv, lviv), emptyList()))
        assertEquals(listOf(kyiv, lviv), start.active)
        assertEquals(listOf(lviv), start.started)
        assertTrue(start.cleared.isEmpty())
        assertNull(feed.changes(2, listOf(lviv), emptyList()))

        val clear = requireNotNull(feed.changes(3, emptyList(), listOf(kyiv, odesa)))
        assertEquals(listOf(lviv), clear.active)
        assertEquals(listOf(kyiv), clear.cleared)
        assertTrue(clear.started.isEmpty())
        assertFalse(clear.stale)
    }

    @Test
    fun resyncSnapshotsAndStaleRecoveryNeverInventTransitions() {
        val feed = AirAlertFeed()
        feed.snapshot(3, listOf(kyiv), stale = false)
        val stale = requireNotNull(feed.snapshot(4, listOf(kyiv), stale = true))
        assertTrue(stale.stale)
        assertTrue(stale.started.isEmpty())
        val recovered = requireNotNull(feed.snapshot(5, listOf(kyiv, lviv), stale = false))
        assertEquals(listOf(kyiv, lviv), recovered.active)
        assertTrue(recovered.started.isEmpty())
        assertNull(feed.changes(4, listOf(odesa), emptyList()))
        val clear = requireNotNull(feed.changes(6, emptyList(), listOf(kyiv)))
        assertEquals(listOf(lviv), clear.active)
    }

    @Test
    fun notificationPreviewDistinguishesStartsClearsAndMixedUpdates() {
        assertNull(airAlertNotice(emptyList(), emptyList(), english = true, maxNames = 3))
        val start = requireNotNull(airAlertNotice(listOf(kyiv), emptyList(), english = true, maxNames = 3))
        assertEquals(AirAlertNoticeKind.START, start.kind)
        assertEquals("Kyiv", start.names)
        assertFalse(start.truncated)
        val clear = requireNotNull(airAlertNotice(emptyList(), listOf(lviv), english = false, maxNames = 3))
        assertEquals(AirAlertNoticeKind.CLEAR, clear.kind)
        assertEquals("Львівська", clear.names)
        val mixed = requireNotNull(airAlertNotice(listOf(kyiv, lviv, odesa), listOf(kharkiv, kyiv), english = true, maxNames = 3))
        assertEquals(AirAlertNoticeKind.UPDATE, mixed.kind)
        assertEquals("Kyiv, Lviv, Odesa", mixed.names)
        assertTrue(mixed.truncated)
    }
}
