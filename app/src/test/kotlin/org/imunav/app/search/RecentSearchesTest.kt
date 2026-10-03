package org.imunav.app.search

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecentSearchesTest {
    @Test
    fun pickQueuedBeforeLoadingPreservesOldRowsAndMovesDuplicatesToFront() {
        val queue = ArrayDeque<Runnable>()
        var saved = listOf(result("old"), result("other"))
        val history = RecentSearches({ saved }, { saved = it }, { throw AssertionError(it) }, Executor(queue::addLast))
        history.remember(result("new"))
        history.remember(result("old"))
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(listOf("old", "new", "other"), saved.map { it.title })
        assertEquals(saved, history.state.value)
    }

    @Test
    fun concurrentPhoneAndCarPicksAreSerializedWithoutLosingRows() {
        val worker = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(2)
        var saved = emptyList<SearchResult>()
        val history = RecentSearches({ saved }, { saved = it }, { throw AssertionError(it) }, worker)
        try {
            val picks = (0 until 10).map { index -> callers.submit { history.remember(result("pick $index")) } }
            picks.forEach { it.get(5, TimeUnit.SECONDS) }
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals((0 until 10).map { "pick $it" }.toSet(), saved.map { it.title }.toSet())
            assertEquals(saved, history.state.value)
            history.remember(result("last"))
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(10, saved.size)
            assertEquals("last", saved.first().title)
        } finally {
            callers.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test
    fun cachedPickMatchesReloadedHistoryInsteadOfKeepingAnOldSearchDistance() {
        val queue = ArrayDeque<Runnable>()
        var saved = emptyList<SearchResult>()
        val history = RecentSearches({ saved }, { saved = it }, { throw AssertionError(it) }, Executor(queue::addLast))
        history.remember(result("picked").copy(distanceM = 500.0, source = "photon"))
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(listOf(result("picked")), history.state.value)
        assertEquals(saved, history.state.value)
    }

    @Test
    fun failedInitialReadDoesNotOverwriteHistoryAndCanRetryOnNextPick() {
        val queue = ArrayDeque<Runnable>()
        var failRead = true
        var saved = listOf(result("existing"))
        var failures = 0
        val history = RecentSearches(
            { if (failRead) error("read failed") else saved },
            { saved = it },
            { failures++ },
            Executor(queue::addLast),
        )
        history.remember(result("first"))
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(listOf("existing"), saved.map { it.title })
        assertEquals(2, failures)
        failRead = false
        history.remember(result("second"))
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(listOf("second", "existing"), saved.map { it.title })
    }

    private fun result(title: String) = SearchResult(ResultKind.PLACE, title, "", GeoPoint(50.0, 30.0), null, "recent")
}
