package org.imunav.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.imunav.app.R
import org.imunav.app.graph
import org.imunav.app.ui.routePointLabel
import org.imunav.core.bookmarks.SavedPlace
import org.imunav.core.bookmarks.SavedRoute
import org.imunav.core.bookmarks.matching
import org.imunav.core.route.TravelMode
import org.imunav.core.search.SearchResult

/** Cancellable typeahead shares the phone's offline search and recent selections. */
class CarSearchScreen(context: CarContext, private val start: Boolean, initialQuery: String = "") :
    Screen(context),
    SearchTemplate.SearchCallback {
    private var query = initialQuery
    private var results = emptyList<SearchResult>()
    private var loading = true
    private var job: Job? = null
    private val graph get() = carContext.graph

    init {
        search(query)
        lifecycleScope.launch { graph.bookmarks.state.collect { invalidate() } }
        lifecycleScope.launch {
            graph.search.recent.collect {
                if (query.isBlank()) {
                    results = it
                    invalidate()
                }
            }
        }
    }

    override fun onSearchTextChanged(searchText: String) = search(searchText)
    override fun onSearchSubmitted(searchText: String) = search(searchText)

    private fun search(value: String) {
        query = value.take(256)
        job?.cancel()
        loading = true
        invalidate()
        job = lifecycleScope.launch {
            delay(250)
            results =
                if (query.isBlank()) {
                    graph.search.recent.value
                } else {
                    graph.search.search(
                        query,
                        graph.ui.value.manualStart ?: graph.ui.value.currentPosition,
                    )
                }
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(this).setHeaderAction(Action.BACK).setInitialSearchText(query)
            .setSearchHint(carContext.getString(if (start) R.string.search_from_hint else R.string.search_to_hint)).setLoading(loading)
        if (!loading) {
            val list = ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.search_no_results))
            val limit = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
            val saved = graph.bookmarks.state.value.items.matching(query).filterIsInstance<SavedPlace>().take(limit)
            saved.forEach { place ->
                list.addItem(
                    Row.Builder().setTitle(place.name).addText(carContext.getString(R.string.bookmarks)).setOnClickListener {
                        graph.setTravelMode(TravelMode.CAR)
                        if (graph.useBookmark(place, start)) screenManager.popToRoot()
                    }.build(),
                )
            }
            results.take((limit - saved.size).coerceAtLeast(0)).forEach { result ->
                list.addItem(
                    Row.Builder().setTitle(result.title).addText(result.subtitle).setOnClickListener {
                        if (!graph.engine.state.active && !graph.ui.value.startingNavigation) {
                            graph.setTravelMode(TravelMode.CAR)
                            if (start) graph.setManualStart(result.point, result.routePointLabel()) else graph.setDestination(result.point, result.routePointLabel())
                            graph.search.remember(result)
                            screenManager.popToRoot()
                        }
                    }.build(),
                )
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }
}

/** Saved car routes preserve fixed starts; walking recipes remain available only on the phone. */
class CarBookmarksScreen(context: CarContext) : Screen(context) {
    init {
        lifecycleScope.launch { carContext.graph.bookmarks.state.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val graph = carContext.graph
        val state = graph.bookmarks.state.value
        val limit = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val list = ItemList.Builder().setNoItemsMessage(carContext.getString(if (state.failed) R.string.bookmark_error else R.string.bookmark_empty))
        state.items.matching("").filter { it is SavedPlace || (it is SavedRoute && it.mode == TravelMode.CAR) }.take(limit).forEach { bookmark ->
            list.addItem(
                Row.Builder().setTitle(bookmark.name).setOnClickListener {
                    graph.setTravelMode(TravelMode.CAR)
                    val selected = when (bookmark) {
                        is SavedPlace -> graph.useBookmark(bookmark, false)
                        is SavedRoute -> graph.openBookmark(bookmark)
                    }
                    if (selected) screenManager.popToRoot()
                }.build(),
            )
        }
        return ListTemplate.Builder().setHeader(Header.Builder().setTitle(carContext.getString(R.string.bookmarks)).setStartHeaderAction(Action.BACK).build())
            .apply { if (!state.loaded && !state.failed) setLoading(true) else setSingleList(list.build()) }.build()
    }
}
