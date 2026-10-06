package org.imunav.app.bookmarks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.core.bookmarks.Bookmark
import org.imunav.core.bookmarks.BookmarkStore
import org.imunav.core.bookmarks.matching
import org.imunav.core.bookmarks.renamed

/** Published only after successful disk operations; old entries survive failed writes. */
data class BookmarkState(val items: List<Bookmark> = emptyList(), val loaded: Boolean = false, val busy: Boolean = false, val failed: Boolean = false)

/** Kept in the app graph so an open name dialog survives activity recreation. */
data class BookmarkEdit(val bookmark: Bookmark, val name: String = bookmark.name)

/** Serializes local operations on the main scope, with blocking persistence on IO. */
class Bookmarks(private var store: BookmarkStore, private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val _state = MutableStateFlow(BookmarkState())
    val state = _state.asStateFlow()
    private val _editor = MutableStateFlow<BookmarkEdit?>(null)
    val editor = _editor.asStateFlow()
    private val _deleting = MutableStateFlow<Bookmark?>(null)
    val deleting = _deleting.asStateFlow()

    init {
        reload()
    }

    /** Retry initial loading without replacing stored data with an empty collection on failure. */
    fun reload() = execute { _state.value = _state.value.copy(items = withContext(io) { store.load() }.matching(""), loaded = true) }

    fun edit(bookmark: Bookmark) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(failed = false)
        _editor.value = BookmarkEdit(bookmark)
    }

    fun nameChanged(name: String) {
        if (!_state.value.busy) _editor.value = _editor.value?.copy(name = name)
    }

    fun dismissEditor() {
        if (!_state.value.busy) _editor.value = null
    }

    /** Stable IDs and a synchronous busy guard prevent duplicate taps from creating extra entries. */
    fun save() {
        val edit = _editor.value ?: return
        if (!_state.value.loaded || edit.name.isBlank()) return
        execute {
            val bookmark = edit.bookmark.renamed(edit.name)
            withContext(io) { store.save(bookmark) }
            _state.value = _state.value.copy(items = (_state.value.items.filterNot { it.id == bookmark.id } + bookmark).matching(""))
            _editor.value = null
        }
    }

    fun requestDelete(bookmark: Bookmark) {
        if (!_state.value.busy) {
            _state.value = _state.value.copy(failed = false)
            _deleting.value = bookmark
        }
    }

    fun dismissDelete() {
        if (!_state.value.busy) _deleting.value = null
    }

    fun delete() {
        val bookmark = _deleting.value ?: return
        if (!_state.value.loaded) return
        execute {
            withContext(io) { store.delete(bookmark.id) }
            _state.value = _state.value.copy(items = _state.value.items.filterNot { it.id == bookmark.id })
            _deleting.value = null
        }
    }

    /** Switch only between completed edits so writes cannot cross account boundaries. */
    // Any storage failure must restore the previous visible profile before propagating the error.
    @Suppress("TooGenericExceptionCaught")
    suspend fun selectStore(next: BookmarkStore, seed: List<Bookmark>? = null) {
        check(!_state.value.busy)
        val previous = _state.value
        _state.value = BookmarkState(busy = true)
        _editor.value = null
        _deleting.value = null
        try {
            val items = withContext(io) {
                if (seed != null) next.replaceAll(seed)
                next.load()
            }
            withContext(io) { store.close() }
            store = next
            _state.value = BookmarkState(items = items.matching(""), loaded = true)
        } catch (error: Exception) {
            _state.value = previous.copy(failed = true)
            withContext(io) { next.close() }
            throw error
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    /** Server updates and user edits share the same busy guard and transactional persistence. */
    suspend fun replaceAll(items: List<Bookmark>) {
        check(!_state.value.busy)
        _state.value = _state.value.copy(busy = true)
        try {
            withContext(io) { store.replaceAll(items) }
            _state.value = _state.value.copy(items = items.matching(""))
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun execute(operation: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, failed = false)
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistence errors are shown locally; they must never interrupt navigation.
                _state.value = _state.value.copy(failed = true)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }
}
