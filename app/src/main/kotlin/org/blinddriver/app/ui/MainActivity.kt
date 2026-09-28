package org.blinddriver.app.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.blinddriver.app.AppGraph
import org.blinddriver.app.graph
import org.blinddriver.app.ui.theme.BlindDriverTheme

private enum class Screen { MAP, SETTINGS, LOG, HISTORY, TRIP }

class MainActivity : ComponentActivity() {
    private var hasLocation by mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocation) graph.startSensing()
    }

    private fun requestPermissions() = permissions.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    )

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(org.blinddriver.app.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) requestPermissions()
        val g = graph
        setContent {
            BlindDriverTheme {
                AppRoot(g, hasLocation, ::requestPermissions, ::setKeepScreenOn)
            }
        }
    }

    /** Keep the display on only while navigating. */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        if (hasLocation) graph.startSensing()
    }

    override fun onStop() {
        super.onStop()
        if (!graph.engine.state.active) graph.stopSensing()
    }
}

@Composable
private fun AppRoot(g: AppGraph, hasLocation: Boolean, requestPermission: () -> Unit, keepScreenOn: (Boolean) -> Unit) {
    val ui by g.ui.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.MAP) }
    var tripId by rememberSaveable { mutableStateOf<String?>(null) }

    // While no navigation runs, keep position and diagnostics fresh (the service drives it otherwise).
    LaunchedEffect(Unit) {
        while (true) {
            if (!g.engine.state.active) g.refresh()
            delay(1000)
        }
    }
    LaunchedEffect(ui.guidance.active) { keepScreenOn(ui.guidance.active) }

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
            g = g,
            hasLocation = hasLocation,
            onRequestPermission = requestPermission,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenLog = { screen = Screen.LOG },
            onOpenHistory = { screen = Screen.HISTORY },
        )
        Screen.HISTORY -> HistoryScreen(g, onBack = { screen = Screen.MAP }, onOpen = { tripId = it.id; screen = Screen.TRIP })
        Screen.TRIP -> {
            val trip = g.trips.history.value.firstOrNull { it.id == tripId }
            if (trip == null) screen = Screen.HISTORY else TripDetailScreen(g, trip, onBack = { screen = Screen.HISTORY })
        }
        Screen.SETTINGS -> SettingsScreen(ui, g, onBack = { screen = Screen.MAP }, onOpenLog = { screen = Screen.LOG })
        Screen.LOG -> LogScreen(g, onBack = { screen = Screen.SETTINGS })
    }
}
