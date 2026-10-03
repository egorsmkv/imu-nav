package org.imunav.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.bookmarks.Bookmarks
import org.imunav.core.bookmarks.Bookmark
import org.imunav.core.bookmarks.BookmarkOrigin
import org.imunav.core.bookmarks.BookmarkPoint
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.bookmarks.matching
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import java.util.Locale
import java.util.UUID

/** No reverse geocoding is needed to save a point chosen offline on the map. */
private fun BookmarkPoint.displayLabel(): String = label?.trim()?.takeIf { it.isNotBlank() } ?: String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon)

/** Search and map selections share one naming dialog and persistence path. */
fun Bookmarks.savePlace(point: GeoPoint, label: String?) {
    val endpoint = BookmarkPoint(point, label)
    edit(SavedPlace(UUID.randomUUID().toString(), endpoint.displayLabel(), endpoint))
}

/** Save actions belong to the idle editor; an automatic origin stays automatic. */
@Composable
fun BookmarkSaveActions(ui: UiState, mode: TravelMode, bookmarks: Bookmarks) {
    val state by bookmarks.state.collectAsStateWithLifecycle()
    val automaticLabel = stringResource(R.string.route_current_position)
    FlowRow {
        ui.manualStart?.let { point ->
            TextButton(enabled = !state.busy, onClick = { bookmarks.savePlace(point, ui.manualStartLabel) }) { Text(stringResource(R.string.bookmark_save_start)) }
        }
        ui.destination?.let { point ->
            TextButton(enabled = !state.busy, onClick = { bookmarks.savePlace(point, ui.destinationLabel) }) { Text(stringResource(R.string.bookmark_save_destination)) }
            TextButton(enabled = !state.busy, onClick = {
                val start = ui.manualStart?.let { BookmarkPoint(it, ui.manualStartLabel) }
                val destination = BookmarkPoint(point, ui.destinationLabel)
                bookmarks.edit(
                    SavedRoute(
                        UUID.randomUUID().toString(),
                        "${start?.displayLabel() ?: automaticLabel} → ${destination.displayLabel()}",
                        start?.let { BookmarkOrigin.Fixed(it) } ?: BookmarkOrigin.Automatic,
                        destination,
                        mode,
                    ),
                )
            }) { Text(stringResource(R.string.bookmark_save_route)) }
        }
    }
}

/** One dialog host above all screens; its state survives rotation through the app graph. */
@Composable
fun BookmarkDialogs(bookmarks: Bookmarks) {
    val edit by bookmarks.editor.collectAsStateWithLifecycle()
    val deleting by bookmarks.deleting.collectAsStateWithLifecycle()
    val state by bookmarks.state.collectAsStateWithLifecycle()
    edit?.let { draft ->
        AlertDialog(
            onDismissRequest = bookmarks::dismissEditor,
            title = { Text(stringResource(R.string.bookmark_name)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = bookmarks::nameChanged,
                        singleLine = true,
                        enabled = !state.busy,
                        label = { Text(stringResource(R.string.bookmark_name)) },
                        isError = draft.name.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.failed) BookmarkFailure(bookmarks::reload, !state.busy && !state.loaded)
                }
            },
            confirmButton = {
                TextButton(onClick = bookmarks::save, enabled = state.loaded && !state.busy && draft.name.isNotBlank()) { Text(stringResource(R.string.bookmark_save)) }
            },
            dismissButton = { TextButton(onClick = bookmarks::dismissEditor, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleting?.let { bookmark ->
        AlertDialog(
            onDismissRequest = bookmarks::dismissDelete,
            title = { Text(stringResource(R.string.action_delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.bookmark_delete_confirm, bookmark.name))
                    if (state.failed) Text(stringResource(R.string.bookmark_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = bookmarks::delete, enabled = !state.busy) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = bookmarks::dismissDelete, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Saved places and route recipes remain usable without search data or online services. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(app: AppGraph, onBack: () -> Unit) {
    val state by app.bookmarks.state.collectAsStateWithLifecycle()
    val ui by app.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val items = state.items.matching(query).filter { if (tab == 0) it is SavedPlace else it is SavedRoute }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.bookmarks)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.bookmark_places, R.string.bookmark_routes).forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.bookmark_filter)) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failed) BookmarkFailure(app.bookmarks::reload, !state.busy)
            if (ui.guidance.active) Text(stringResource(R.string.bookmark_active), Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (state.loaded && items.isEmpty()) {
                    item { Text(stringResource(if (query.isBlank()) R.string.bookmark_empty else R.string.search_no_results), Modifier.padding(16.dp)) }
                }
                items(items, key = { it.id }) { bookmark ->
                    Column {
                        ListItem(
                            headlineContent = { Text(bookmark.name) },
                            supportingContent = { Text(bookmarkDescription(bookmark)) },
                            leadingContent = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                        )
                        FlowRow(Modifier.padding(horizontal = 16.dp)) {
                            when (bookmark) {
                                is SavedPlace -> {
                                    TextButton(enabled = !ui.guidance.active, onClick = {
                                        if (app.useBookmark(bookmark, true)) onBack()
                                    }) { Text(stringResource(R.string.bookmark_use_start)) }
                                    TextButton(enabled = !ui.guidance.active, onClick = {
                                        if (app.useBookmark(bookmark, false)) onBack()
                                    }) { Text(stringResource(R.string.bookmark_use_destination)) }
                                }

                                is SavedRoute -> TextButton(enabled = !ui.guidance.active, onClick = {
                                    if (app.openBookmark(bookmark)) onBack()
                                }) { Text(stringResource(R.string.bookmark_open)) }
                            }
                            TextButton(enabled = !state.busy, onClick = { app.bookmarks.edit(bookmark) }) { Text(stringResource(R.string.bookmark_rename)) }
                            TextButton(enabled = !state.busy, onClick = { app.bookmarks.requestDelete(bookmark) }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
}

/** Route descriptions distinguish a dynamic origin from saved coordinates. */
@Composable
private fun bookmarkDescription(bookmark: Bookmark): String = when (bookmark) {
    is SavedPlace -> bookmark.endpoint.displayLabel()

    is SavedRoute -> {
        val origin = (bookmark.origin as? BookmarkOrigin.Fixed)?.endpoint?.displayLabel() ?: stringResource(R.string.route_current_position)
        val mode = stringResource(if (bookmark.mode == TravelMode.CAR) R.string.mode_car else R.string.mode_walk)
        "$origin → ${bookmark.destination.displayLabel()} · $mode"
    }
}

/** Read failures offer retry; write failures leave the dialog open so the user can save again. */
@Composable
fun BookmarkFailure(onRetry: () -> Unit, retryEnabled: Boolean) {
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.bookmark_error), color = MaterialTheme.colorScheme.error)
        if (retryEnabled) TextButton(onClick = onRetry) { Text(stringResource(R.string.bookmark_retry)) }
    }
}
