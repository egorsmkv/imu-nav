package org.imunav.app.search

import android.os.CancellationSignal
import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.SearchResult
import org.imunav.core.search.TantivyRequest
import org.imunav.core.search.TantivySearch
import org.imunav.routing.PackInfo
import java.io.File
import java.io.IOException

/** Prefers native search; SQLite is retained solely for packs built before Tantivy support. */
class PackSearch private constructor(private val native: TantivySearch?, private val legacy: AndroidSearchDb?) : AutoCloseable {
    companion object {
        /** A present but invalid native index must fail validation, never silently downgrade. */
        fun open(directory: File): PackSearch? {
            val index = File(directory, TantivySearch.DIRECTORY)
            val format = File(directory, PackInfo.FILE).takeIf(File::isFile)?.let { PackInfo.parse(it.readText())?.searchFormat }
            if (format != null && format != TantivySearch.FORMAT) throw IOException("Unsupported offline search format")
            if (format == TantivySearch.FORMAT && !index.isDirectory) throw IOException("Native search index is missing")
            if (index.exists()) return PackSearch(TantivySearch(index), null)
            val database = File(directory, "search.db")
            return if (database.exists()) PackSearch(null, AndroidSearchDb(database)) else null
        }
    }

    /** Called under OfflineRouting's resource read lock, away from the main thread. */
    fun search(query: String, near: GeoPoint?, cancellation: CancellationSignal): List<SearchResult> {
        cancellation.throwIfCanceled()
        val index = native ?: return legacy?.search(query, near, cancellation).orEmpty()
        return TantivyRequest().use { request ->
            cancellation.setOnCancelListener(request::cancel)
            try {
                index.search(query, near, request, cancellation::throwIfCanceled)
            } finally {
                // Wait for an in-flight cancel callback before releasing its native handle.
                cancellation.setOnCancelListener(null)
            }
        }
    }

    override fun close() {
        native?.close()
        legacy?.close()
    }
}
