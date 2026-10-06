package org.imunav.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline and concurrent histories must converge without resurrecting deleted bookmarks. */
class SyncMergeTest {
    @Test fun initialRestoreKeepsCloudSettingsAndMergesDistinctBookmarks() {
        val remote = listOf(SyncEntry("setting", "voice", 3, "false"), SyncEntry("bookmark", "home", 4, "cloud"), SyncEntry("bookmark", "deleted", 5, null))
        val local = mapOf("setting:voice" to "true", "bookmark:home" to "local", "bookmark:work" to "work", "bookmark:deleted" to "stale", "setting:language" to "uk")
        assertEquals(listOf(SyncEntry("bookmark", "work", 0, "work"), SyncEntry("setting", "language", 0, "uk")), SyncMerge.changes(local, emptyList(), remote, true))
    }

    @Test fun offlineEditsAndDeletesCarryAcknowledgedVersions() {
        val baseline = listOf(SyncEntry("setting", "voice", 2, "true"), SyncEntry("bookmark", "home", 4, "home"), SyncEntry("bookmark", "old", 8, null))
        val local = mapOf("setting:voice" to "false", "bookmark:new" to "new")
        assertEquals(
            setOf(SyncEntry("setting", "voice", 2, "false"), SyncEntry("bookmark", "home", 4, null), SyncEntry("bookmark", "new", 0, "new")),
            SyncMerge.changes(local, baseline, baseline, false).toSet(),
        )
    }

    @Test fun remoteUpdatesDoNotBecomeLocalEdits() {
        val baseline = listOf(SyncEntry("setting", "voice", 2, "true"))
        val remote = listOf(SyncEntry("setting", "voice", 3, "false"))
        val local = mapOf("setting:voice" to "true")
        assertTrue(SyncMerge.changes(local, baseline, remote, false).isEmpty())
        assertEquals(mapOf("setting:voice" to "false"), SyncMerge.apply(remote, local, local))
    }

    @Test fun editsDuringRequestsSurviveAcknowledgement() {
        val sent = mapOf("bookmark:home" to "before", "setting:voice" to "true")
        val now = mapOf("bookmark:new" to "new", "setting:voice" to "false")
        val remote = listOf(SyncEntry("bookmark", "home", 2, "cloud"), SyncEntry("setting", "voice", 2, "true"), SyncEntry("bookmark", "other", 1, "other"))
        assertEquals(mapOf("bookmark:new" to "new", "setting:voice" to "false", "bookmark:other" to "other"), SyncMerge.apply(remote, sent, now))
    }

    @Test fun cloudDeletionRemovesUnchangedLocalCopy() {
        val sent = mapOf("bookmark:home" to "home")
        assertTrue(SyncMerge.apply(listOf(SyncEntry("bookmark", "home", 2, null)), sent, sent).isEmpty())
    }

    @Test fun acceptedRetryIsCleanAfterRestart() {
        val baseline = listOf(SyncEntry("bookmark", "home", 2, "updated"))
        assertTrue(SyncMerge.changes(mapOf("bookmark:home" to "updated"), baseline, baseline, false).isEmpty())
    }
}
