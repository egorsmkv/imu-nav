package org.imunav.app.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.search.SearchResult
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Serializes phone and car picks with initial loading so concurrent updates never lose history. */
class RecentSearches(
    private val load: () -> List<SearchResult>,
    private val save: (List<SearchResult>) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val worker: Executor = Executors.newSingleThreadExecutor(),
) {
    private val _state = MutableStateFlow<List<SearchResult>>(emptyList())
    val state = _state.asStateFlow()
    private var loaded = false

    init {
        worker.execute { safely { ensureLoaded() } }
    }

    /** Load first, then commit and publish; a failed read must never overwrite existing history. */
    fun remember(result: SearchResult) = worker.execute {
        safely {
            ensureLoaded()
            // Recent picks have no live distance, matching rows reloaded from the existing format.
            val picked = result.copy(distanceM = null, source = "recent")
            val updated = (listOf(picked) + _state.value).distinctBy { it.title to it.subtitle }.take(MAX_RECENT)
            save(updated)
            _state.value = updated
        }
    }

    private fun ensureLoaded() {
        if (!loaded) {
            _state.value = load().toList()
            loaded = true
        }
    }

    // Storage callbacks may fail with IO, JSON or platform exceptions; keep the worker and last state alive.
    @Suppress("TooGenericExceptionCaught")
    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            onFailure(failure)
        }
    }

    private companion object {
        const val MAX_RECENT = 10
    }
}
