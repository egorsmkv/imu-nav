package org.imunav.app.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.imunav.app.R
import org.imunav.app.graph
import org.imunav.app.ui.MainActivity
import org.imunav.app.ui.formatDistance
import org.imunav.app.ui.formatDuration
import org.imunav.core.route.TravelMode

/** A single map screen switches from route review to guidance without creating another engine. */
class CarNavigationScreen(context: CarContext, private val session: NavigationCarSession) : Screen(context) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = session.setMapVisible(true)
            override fun onStop(owner: LifecycleOwner) = session.setMapVisible(false)
        })
    }

    var message: String? = null
    var pendingQuery: String? = null
    private val graph get() = carContext.graph
    private fun text(id: Int) = carContext.getString(id)

    override fun onGetTemplate(): Template {
        val ui = session.ui
        val nav = ui.guidance
        if (nav.active && nav.travelMode == TravelMode.FOOT) {
            return MessageTemplate.Builder(text(R.string.car_walking)).setHeader(header()).build()
        }
        if (!hasLocation()) return setup()
        if (nav.active) return navigation()
        val error = message ?: ui.error
        if (error != null) {
            return MessageTemplate.Builder(error).setHeader(header())
                .addAction(
                    action(R.string.car_dismiss) {
                        message = null
                        graph.clearError()
                        invalidate()
                    },
                )
                .addAction(phoneAction()).build()
        }
        val content = if (ui.destination == null || pendingQuery != null) editor() else preview()
        return MapWithContentTemplate.Builder().setContentTemplate(content)
            .setMapController(MapController.Builder().setMapActionStrip(mapActions()).build()).build()
    }

    private fun hasLocation(): Boolean = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun setup(): Template = MessageTemplate.Builder(text(R.string.car_setup)).setHeader(header()).addAction(phoneAction()).build()

    private fun phoneAction(): Action = Action.Builder().setTitle(text(R.string.car_phone)).setOnClickListener(
        ParkedOnlyOnClickListener.create {
            if (!hasLocation()) {
                carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { _, _ ->
                    graph.startSensing()
                    invalidate()
                }
            } else {
                runCatching { carContext.startActivity(Intent(carContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
    ).build()

    private fun header(): Header = Header.Builder().setTitle(text(R.string.app_name)).setStartHeaderAction(Action.APP_ICON).build()

    private fun editor(): Template {
        val items = ItemList.Builder()
            .addItem(row(R.string.search_to_hint) { search(false) })
            .addItem(row(R.string.search_from_hint) { search(true) })
            .addItem(
                row(R.string.route_current_position) {
                    graph.setManualStart(null)
                    invalidate()
                },
            )
            .addItem(row(R.string.bookmarks) { screenManager.push(CarBookmarksScreen(carContext)) })
        return ListTemplate.Builder().setHeader(header()).setSingleList(items.build()).build()
    }

    private fun search(start: Boolean) {
        val query = if (start) "" else pendingQuery.orEmpty()
        pendingQuery = null
        screenManager.push(CarSearchScreen(carContext, start, query))
    }

    private fun preview(): Template {
        val ui = session.ui
        val route = ui.previewRoute
        val pane = Pane.Builder()
        if (ui.planning) {
            pane.setLoading(true)
        } else {
            pane.addRow(
                Row.Builder().setTitle(ui.destinationLabel ?: text(R.string.car_destination))
                    .addText(ui.manualStartLabel ?: if (ui.manualStart != null) text(R.string.car_fixed_start) else text(R.string.route_current_position)).build(),
            )
            pane.addRow(
                Row.Builder().setTitle(session.status()).apply {
                    route?.let { addText("${formatDistance(carContext.resources, it.length)} · ${formatDuration(carContext.resources, it.durationS)}") }
                }.build(),
            )
            if (route != null && graph.travelMode.value == TravelMode.CAR) pane.addAction(action(R.string.action_start, session::start))
            pane.addAction(action(R.string.car_edit_route) { screenManager.push(CarEditorScreen(carContext)) })
        }
        return PaneTemplate.Builder(pane.build()).setHeader(header()).build()
    }

    private fun navigation(): Template {
        val nav = session.ui.guidance
        val adapter = CarGuidance(carContext)
        val actions = ActionStrip.Builder().addAction(action(R.string.action_stop, session::onStopNavigation))
        if (!session.demonstration) {
            actions.addAction(
                iconAction(R.drawable.ic_car_reroute) {
                    if (nav.blindDeviation) graph.engine.confirmDeviation(SystemClock.elapsedRealtime()) else graph.engine.requestManualReroute()
                },
            )
            if (nav.blindDeviation) actions.addAction(iconAction(R.drawable.ic_car_on_route) { graph.engine.dismissDeviation(SystemClock.elapsedRealtime()) })
        }
        return NavigationTemplate.Builder().setNavigationInfo(adapter.routing(nav))
            .setDestinationTravelEstimate(adapter.estimate(nav.remainingM, nav.remainingS, message ?: session.status()))
            .setActionStrip(actions.build()).setMapActionStrip(mapActions()).build()
    }

    private fun mapActions(): ActionStrip = ActionStrip.Builder().addAction(Action.PAN)
        .addAction(iconAction(R.drawable.ic_car_add) { session.zoom(1.0) })
        .addAction(iconAction(R.drawable.ic_car_remove) { session.zoom(-1.0) })
        .addAction(iconAction(R.drawable.ic_car_recenter, session::recenter)).build()

    private fun action(label: Int, callback: () -> Unit): Action = Action.Builder().setTitle(text(label)).setOnClickListener(callback).build()
    private fun row(label: Int, callback: () -> Unit): Row = Row.Builder().setTitle(text(label)).setOnClickListener(callback).build()
    private fun iconAction(icon: Int, callback: () -> Unit): Action = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build()).setOnClickListener(callback).build()
}

/** Changing endpoints always returns to preview; an active trip cannot be silently replaced. */
class CarEditorScreen(context: CarContext) : Screen(context) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        listOf(false, true).forEach { start ->
            items.addItem(
                Row.Builder().setTitle(carContext.getString(if (start) R.string.search_from_hint else R.string.search_to_hint))
                    .setOnClickListener { screenManager.push(CarSearchScreen(carContext, start)) }.build(),
            )
        }
        items.addItem(
            Row.Builder().setTitle(carContext.getString(R.string.route_current_position)).setOnClickListener {
                carContext.graph.setManualStart(null)
                screenManager.popToRoot()
            }.build(),
        )
        items.addItem(
            Row.Builder().setTitle(carContext.getString(R.string.bookmarks)).setOnClickListener {
                screenManager.push(CarBookmarksScreen(carContext))
            }.build(),
        )
        return ListTemplate.Builder().setHeader(Header.Builder().setTitle(carContext.getString(R.string.car_edit_route)).setStartHeaderAction(Action.BACK).build())
            .setSingleList(items.build()).build()
    }
}
