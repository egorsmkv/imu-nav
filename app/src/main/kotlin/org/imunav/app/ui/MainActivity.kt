package org.imunav.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.imunav.app.AppGraph
import org.imunav.app.AppLanguage
import org.imunav.app.graph
import org.imunav.app.ui.theme.BlindDriverTheme

/**
 * The app's screens. Navigation between them is a simple state variable in [AppRoot]
 * (no navigation library needed for five screens).
 */
private enum class Screen { MAP, SETTINGS, LOG, HISTORY, TRIP }

/**
 * The only Activity. It asks for permissions, tells the [AppGraph] when the app is visible, and
 * shows the Compose UI ([AppRoot]). All real work lives in [AppGraph], which outlives the
 * Activity (Android re-creates Activities on rotation, language change, etc.).
 */
class MainActivity : ComponentActivity() {
    /** Compose state: changing it redraws the UI that reads it. */
    private var hasLocation by mutableStateOf(false)

    /** The system permission dialog; the lambda runs with the user's answers. */
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocation) graph.startSensing()
    }

    /** Show the system dialog for location (and, on Android 13+, notification) permission. */
    private fun requestPermissions() = permissions.launch(
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Runtime notification permission exists only on Android 13+.
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    /** Apply the in-app language before any resources are loaded. */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) requestPermissions()
        val app = graph
        setContent {
            BlindDriverTheme {
                AppRoot(app, hasLocation, ::requestPermissions, ::setKeepScreenOn)
            }
        }
    }

    /** Keep the display on only while navigating. */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** The app became visible: start sensors (they run without navigation only while visible). */
    override fun onStart() {
        super.onStart()
        graph.uiVisible = true
        if (hasLocation) graph.startSensing()
    }

    /** The app went to the background: save battery unless a trip is running. */
    override fun onStop() {
        super.onStop()
        graph.uiVisible = false
        if (!graph.engine.state.active) graph.stopSensing()
    }
}

/** Picks the screen to show and wires the back button. */
@Composable
private fun AppRoot(app: AppGraph, hasLocation: Boolean, requestPermission: () -> Unit, keepScreenOn: (Boolean) -> Unit) {
    val ui by app.ui.collectAsStateWithLifecycle()
    // rememberSaveable: survives Activity re-creation (rotation, language switch).
    var screen by rememberSaveable { mutableStateOf(Screen.MAP) }
    var tripId by rememberSaveable { mutableStateOf<String?>(null) }

    // While no navigation runs, keep position and diagnostics fresh (the service drives it otherwise).
    // Only while the app is visible: a hidden composition must not keep waking the CPU.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!app.engine.state.active) app.refresh()
                delay(1000)
            }
        }
    }
    val history by app.trips.history.collectAsStateWithLifecycle()
    val screenOnSetting by app.keepScreenOn.collectAsStateWithLifecycle()
    LaunchedEffect(ui.guidance.active, screenOnSetting) { keepScreenOn(ui.guidance.active && screenOnSetting) }

    BackHandler(enabled = screen != Screen.MAP) {
        screen = when (screen) {
            Screen.LOG -> Screen.SETTINGS
            Screen.TRIP -> Screen.HISTORY
            else -> Screen.MAP
        }
    }
    when (screen) {
        Screen.MAP -> MapScreen(
            ui = ui,
            app = app,
            hasLocation = hasLocation,
            onRequestPermission = requestPermission,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenLog = { screen = Screen.LOG },
            onOpenHistory = { screen = Screen.HISTORY },
        )

        Screen.HISTORY -> HistoryScreen(app, onBack = { screen = Screen.MAP }, onOpen = {
            tripId = it.id
            screen = Screen.TRIP
        })

        Screen.TRIP -> {
            val trip = history.firstOrNull { it.id == tripId }
            if (trip == null) {
                LaunchedEffect(Unit) { screen = Screen.HISTORY }
            } else {
                TripDetailScreen(app, trip, onBack = { screen = Screen.HISTORY })
            }
        }

        Screen.SETTINGS -> SettingsScreen(ui, app, onBack = { screen = Screen.MAP }, onOpenLog = { screen = Screen.LOG })

        Screen.LOG -> LogScreen(app, onBack = { screen = Screen.SETTINGS })
    }
}
