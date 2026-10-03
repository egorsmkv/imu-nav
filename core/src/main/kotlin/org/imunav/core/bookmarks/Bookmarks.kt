package org.imunav.core.bookmarks

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import java.util.Locale

/** A copied endpoint, independent of search packs and other bookmarks. */
data class BookmarkPoint(val point: GeoPoint, val label: String? = null) {
    init {
        require(point.lat.isFinite() && point.lat in -90.0..90.0)
        require(point.lon.isFinite() && point.lon in -180.0..180.0)
    }
}

/** Automatic starts are resolved when opening a route, never frozen to an old GPS fix. */
sealed interface BookmarkOrigin {
    data object Automatic : BookmarkOrigin

    /** An explicit map/search selection that remains fixed when the route is reopened. */
    data class Fixed(val endpoint: BookmarkPoint) : BookmarkOrigin
}

/** Stable identity allows distinct bookmarks to share a name or coordinates. */
sealed interface Bookmark {
    val id: String
    val name: String
}

/** A named coordinate that can be reused at either end of a route. */
data class SavedPlace(override val id: String, override val name: String, val endpoint: BookmarkPoint) : Bookmark {
    init {
        validateBookmark(id, name)
    }
}

/** A route recipe: routing data may change, so geometry and instructions are recalculated. */
data class SavedRoute(override val id: String, override val name: String, val origin: BookmarkOrigin, val destination: BookmarkPoint, val mode: TravelMode) : Bookmark {
    init {
        validateBookmark(id, name)
    }
}

/** Storage operations are blocking and must be dispatched away from the Android main thread. */
interface BookmarkStore {
    fun load(): List<Bookmark>
    fun save(bookmark: Bookmark)
    fun delete(id: String)
}

/** Renaming preserves identity, coordinates and all routing choices. */
fun Bookmark.renamed(name: String): Bookmark = when (this) {
    is SavedPlace -> copy(name = name.trim())
    is SavedRoute -> copy(name = name.trim())
}

/** Local filtering works without a routing pack or network access. */
fun Iterable<Bookmark>.matching(query: String): List<Bookmark> {
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
    return filter { bookmark ->
        val labels = when (bookmark) {
            is SavedPlace -> listOf(bookmark.endpoint.label)
            is SavedRoute -> listOf((bookmark.origin as? BookmarkOrigin.Fixed)?.endpoint?.label, bookmark.destination.label)
        }
        val text = (listOf(bookmark.name) + labels).filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
        words.all { it in text }
    }.sortedWith(compareBy<Bookmark> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
}

private fun validateBookmark(id: String, name: String) {
    require(id.isNotBlank())
    require(name.isNotBlank() && name == name.trim())
}
