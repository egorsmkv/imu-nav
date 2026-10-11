package org.imunav.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.imunav.app.R
import org.imunav.app.bookmarks.Bookmarks
import org.imunav.app.search.PlaceSearch
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.matching
import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchBatch
import org.imunav.core.search.SearchResult
import kotlin.time.Duration.Companion.milliseconds

/** Full-screen search: type-ahead offline results (online fallback), recent picks when empty. */
@Composable
fun SearchScreen(
    search: PlaceSearch,
    bookmarks: Bookmarks,
    near: GeoPoint?,
    hint: String,
    onPick: (SearchResult) -> Unit,
    onBookmarkPick: (SavedPlace) -> Unit,
    onClose: () -> Unit,
) {
    val res = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    var batch by remember { mutableStateOf<SearchBatch?>(null) }
    val results = batch?.takeIf { it.query == query && it.near == near }?.results.orEmpty()
    var loading by remember { mutableStateOf(false) }
    val bookmarkState by bookmarks.state.collectAsStateWithLifecycle()
    val recent by search.recent.collectAsStateWithLifecycle()
    val savedPlaces = remember(bookmarkState.items, query) { bookmarkState.items.matching(query).filterIsInstance<SavedPlace>() }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, near) {
        batch = null
        loading = query.trim().length >= 2
        if (query.trim().length < 2) {
            batch = null
            loading = false
            return@LaunchedEffect
        }
        delay(250.milliseconds) // debounce typing
        loading = true
        batch = SearchBatch(query, near, search.search(query, near))
        loading = false
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.cd_clear)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val saved = bookmarks.state.value.items.matching(query).filterIsInstance<SavedPlace>().firstOrNull()
                    if (saved != null) onBookmarkPick(saved) else batch?.takeIf { it.query == query && it.near == near }?.results?.firstOrNull()?.let(onPick)
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(4.dp).focusRequester(focus),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            val showRecent = query.trim().length < 2
            val list = if (showRecent) recent else results
            LazyColumn(Modifier.weight(1f)) {
                if (bookmarkState.failed) item { BookmarkFailure(bookmarks::reload, !bookmarkState.busy) }
                if (savedPlaces.isNotEmpty()) {
                    item { Text(stringResource(R.string.bookmark_places), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
                    items(savedPlaces, key = { "bookmark:${it.id}" }) { place ->
                        ListItem(
                            headlineContent = { Text(place.name) },
                            supportingContent = { place.endpoint.label?.let { Text(it) } },
                            leadingContent = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                            modifier = Modifier.clickable { onBookmarkPick(place) },
                        )
                    }
                }
                item {
                    if (!search.hasOffline) {
                        Text(
                            stringResource(R.string.search_offline_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (showRecent && list.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.search_recent),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (!showRecent && !loading && list.isEmpty() && savedPlaces.isEmpty()) {
                    item { Text(stringResource(R.string.search_no_results), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(list) { r ->
                    val kindLabel = kindLabel(r)
                    ListItem(
                        headlineContent = { Text(r.title) },
                        supportingContent = {
                            val parts = listOfNotNull(
                                r.subtitle.takeIf { it.isNotBlank() && r.kind != ResultKind.PLACE },
                                kindLabel,
                                r.distanceM?.let { formatDistance(res, it) },
                                if (r.source == "online") stringResource(R.string.search_online_badge) else null,
                            )
                            Text(parts.joinToString(" · "))
                        },
                        leadingContent = {
                            Icon(
                                when {
                                    r.source == "recent" -> Icons.Filled.History
                                    r.kind == ResultKind.PLACE -> Icons.Filled.LocationCity
                                    r.kind == ResultKind.STREET -> Icons.Filled.Route
                                    else -> Icons.Filled.Place
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            IconButton(enabled = !bookmarkState.busy, onClick = {
                                if (if (showRecent) query.trim().length < 2 else batch?.query == query && batch?.near == near) bookmarks.savePlace(r.point, r.routePointLabel())
                            }) {
                                Icon(Icons.Filled.BookmarkAdd, stringResource(R.string.bookmark_save_place))
                            }
                        },
                        modifier = Modifier.clickable {
                            if (if (showRecent) query.trim().length < 2 else batch?.query == query && batch?.near == near) onPick(r)
                        },
                    )
                }
            }
        }
    }
}

/** Subtitle for a result: its town, or the kind of place ("village"). */
@Composable
private fun kindLabel(r: SearchResult): String? {
    if (r.kind != ResultKind.PLACE) return null
    val id = when (r.subtitle) {
        "city" -> R.string.kind_city
        "town" -> R.string.kind_town
        "village" -> R.string.kind_village
        "hamlet" -> R.string.kind_hamlet
        "suburb" -> R.string.kind_suburb
        "quarter" -> R.string.kind_quarter
        "neighbourhood" -> R.string.kind_neighbourhood
        else -> return r.subtitle.ifBlank { null }
    }
    return stringResource(id)
}
