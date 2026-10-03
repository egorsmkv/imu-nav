package org.imunav.app.car

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.imunav.app.BuildConfig
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.graph
import org.imunav.core.car.CarDemo
import org.imunav.core.car.CarDestination
import org.imunav.core.nav.NavigationEngine
import org.imunav.core.route.TravelMode
import java.util.UUID

/** Projected Android Auto entry point, included in both distribution flavors. */
class NavigationCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) {
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
        HostValidator.Builder(this).addAllowedHosts(R.array.car_hosts).build()
    }

    override fun onCreateSession(): Session = NavigationCarSession()
}

/** Owns host resources only; the application graph and foreground service retain the actual trip. */
class NavigationCarSession :
    Session(),
    DefaultLifecycleObserver,
    NavigationManagerCallback {
    private val id = UUID.randomUUID().toString()
    private lateinit var root: CarNavigationScreen
    private lateinit var renderer: CarMapRenderer
    private lateinit var manager: NavigationManager
    private var publishedNavigation = false
    private var autoDrive = false
    private var sessionVisible = false
    private var mapVisible = false
    private var demo: CarDemo? = null
    private var demoJob: Job? = null
    private val graph get() = carContext.graph
    val demonstration get() = demo != null
    var ui = UiState()
        private set

    override fun onCreateScreen(intent: Intent): Screen {
        manager = carContext.getCarService(NavigationManager::class.java)
        manager.setNavigationManagerCallback(this)
        root = CarNavigationScreen(carContext, this)
        renderer = CarMapRenderer(carContext, graph, lifecycleScope, { graph.setCarVisible(id, it) }, {
            root.message = carContext.getString(R.string.car_map_error)
            root.invalidate()
        })
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        lifecycle.addObserver(this)
        graph.setCarVisible(id, false)
        lifecycleScope.launch {
            graph.ui.collect {
                if (it.guidance.active && demo != null) {
                    demoJob?.cancel()
                    demo = null
                }
                if (demo == null) publish(it)
            }
        }
        lifecycleScope.launch { graph.powerProfile.collect { renderer.update(ui) } }
        lifecycleScope.launch { graph.offlineMap.status.collect { renderer.update(ui) } }
        handleIntent(intent)
        return root
    }

    override fun onStart(owner: LifecycleOwner) {
        sessionVisible = true
        renderer.setVisible(mapVisible)
    }
    override fun onStop(owner: LifecycleOwner) {
        sessionVisible = false
        renderer.setVisible(false)
    }

    /** Non-map templates can cover the surface without stopping the whole car session. */
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        renderer.setVisible(sessionVisible && visible)
    }
    override fun onCarConfigurationChanged(newConfiguration: Configuration) = renderer.update(ui)

    override fun onDestroy(owner: LifecycleOwner) {
        demoJob?.cancel()
        demo = null
        // A disconnected host must not prevent local surface and sensor ownership cleanup.
        try {
            if (publishedNavigation) manager.navigationEnded()
            manager.clearNavigationManagerCallback()
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        } finally {
            renderer.close()
            graph.disconnectCar(id)
        }
    }

    override fun onNewIntent(intent: Intent) = handleIntent(intent)

    private fun handleIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        if (graph.engine.state.active || graph.ui.value.startingNavigation || demonstration) {
            root.message = carContext.getString(R.string.bookmark_active)
        } else {
            when (val destination = CarDestination.parse(intent.dataString)) {
                is CarDestination.Point -> {
                    graph.setTravelMode(TravelMode.CAR)
                    graph.setDestination(destination.point)
                }

                is CarDestination.Query -> root.pendingQuery = destination.text

                null -> root.message = carContext.getString(R.string.car_invalid_destination)
            }
        }
        root.invalidate()
    }

    /** Host updates and both maps use exactly the same snapshot. */
    private fun publish(state: UiState) {
        ui = state
        renderer.update(state)
        val active = state.guidance.active && !state.guidance.arrived && state.guidance.travelMode == TravelMode.CAR
        if (active != publishedNavigation) {
            if (active) {
                carContext.getCarService(ScreenManager::class.java).popToRoot()
                manager.navigationStarted()
            } else {
                manager.navigationEnded()
            }
            publishedNavigation = active
        }
        if (active) {
            val adapter = CarGuidance(carContext)
            manager.updateTrip(adapter.trip(state.guidance, state.destinationLabel ?: carContext.getString(R.string.app_name), status()))
        }
        root.invalidate()
    }

    fun status(): String {
        val status = when {
            demonstration -> carContext.getString(R.string.car_demo)
            ui.guidance.blindDeviation -> carContext.getString(R.string.deviation_title)
            ui.guidance.arrived -> carContext.getString(R.string.arrived)
            ui.guidance.active && !ui.guidance.source.isGps -> carContext.getString(R.string.car_uncertainty, ui.guidance.uncertaintyM.toInt())
            !ui.guidance.active && !ui.hasTrustedPosition && ui.manualStart == null -> carContext.getString(R.string.car_choose_start)
            else -> carContext.getString(R.string.car_ready)
        }

        return if (ui.guidance.active) "$status · ${carContext.getString(R.string.unit_kmh, ui.guidance.speedKmh.toInt())}" else status
    }

    /** Starting from the car uses the same guarded transaction as the phone. */
    fun start() {
        if (graph.engine.state.active || graph.ui.value.startingNavigation) return
        if (autoDrive) {
            val preview = graph.ui.value
            val route = preview.previewRoute ?: return
            demo = CarDemo(route, SystemClock.elapsedRealtime())
            demoJob = lifecycleScope.launch {
                while (isActive) {
                    val state = demo?.state(SystemClock.elapsedRealtime()) ?: break
                    publish(preview.copy(guidance = state, currentPosition = state.position, destination = state.destination))
                    delay(NavigationEngine.TICK_MS * graph.powerProfile.value.uiEveryTicks)
                }
            }
        } else {
            graph.startNavigation()
        }
    }

    override fun onStopNavigation() {
        if (demo != null) {
            demoJob?.cancel()
            demo = null
            publish(graph.ui.value)
        } else {
            graph.stopNavigation()
        }
    }

    override fun onAutoDriveEnabled() {
        autoDrive = true
        root.message = carContext.getString(R.string.car_demo)
        root.invalidate()
    }

    fun zoom(delta: Double) = renderer.zoom(delta)
    fun recenter() = renderer.recenter()
}
