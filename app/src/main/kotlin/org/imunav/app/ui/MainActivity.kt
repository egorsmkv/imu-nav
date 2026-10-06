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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.imunav.app.AppGraph
import org.imunav.app.AppLanguage
import org.imunav.app.UiState
import org.imunav.app.graph
import org.imunav.app.trips.TripSummary
import org.imunav.app.ui.theme.BlindDriverTheme

/**
 * The app's screens. Navigation between them is a simple state variable in [AppRoot]
 * (no navigation library needed for these screens).
 */
private enum class Screen { MAP, SETTINGS, LOG, HISTORY, TRIP, BOOKMARKS }

/**
 * The only Activity. It asks for permissions, tells the [AppGraph] when the app is visible, and
 * shows the Compose UI ([AppRoot]). All real work lives in [AppGraph], which outlives the
 * Activity (Android re-creates Activities on rotation, language change, etc.).
 */
class MainActivity : ComponentActivity() {
    /** Compose state: changing it redraws the UI that reads it. */
    private var hasLocation by mutableStateOf(false)

    /** The system permission dialog; the lambda runs with the user's answers. */
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
    }

    /** Read actual grants, including changes made outside the app in Android Settings. */
    private fun refreshPermissions() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val changed = granted != hasLocation
        hasLocation = granted
        if (granted) {
            graph.startSensing()
        } else if (changed && !graph.engine.state.active) {
            graph.stopSensing()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
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
        val app = graph
        val languageAtCreation = AppLanguage.get(this)
        setContent {
            val language by app.language.collectAsStateWithLifecycle()
            LaunchedEffect(language) { if (language != languageAtCreation) recreate() }
            BlindDriverTheme {
                HapticFeedbackProvider(app.haptics) {
                    AppRoot(app, hasLocation, ::requestPermissions, ::setKeepScreenOn, ::refreshPermissions)
                }
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
        graph.setPhoneVisible(true)
        if (hasLocation) graph.startSensing()
    }

    /** The app went to the background: save battery unless a trip is running. */
    override fun onStop() {
        super.onStop()
        graph.setPhoneVisible(false)
        if (!graph.engine.state.active) graph.stopSensing()
    }
}

/** Picks the screen to show and wires the back button. */
@Composable
private fun AppRoot(app: AppGraph, hasLocation: Boolean, requestPermission: () -> Unit, keepScreenOn: (Boolean) -> Unit, refreshPermissions: () -> Unit) {
    val ui by app.ui.collectAsStateWithLifecycle()
    // rememberSaveable: survives Activity re-creation (rotation, language switch).
    var screen by rememberSaveable { mutableStateOf(Screen.MAP) }
    var tripId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val setupPreferences = remember(context) { context.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    var showSetup by rememberSaveable { mutableStateOf(!setupPreferences.getBoolean("completed", false)) }

    val history by app.trips.history.collectAsStateWithLifecycle()
    val screenOnSetting by app.keepScreenOn.collectAsStateWithLifecycle()
    LaunchedEffect(ui.guidance.active, screenOnSetting) { keepScreenOn(ui.guidance.active && screenOnSetting) }

    BackHandler(enabled = screen != Screen.MAP && !showSetup) {
        screen = when (screen) {
            Screen.LOG -> Screen.SETTINGS
            Screen.TRIP -> Screen.HISTORY
            else -> Screen.MAP
        }
    }
    var overlayWidth by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val besideMap = screen == Screen.BOOKMARKS && drivingPaneWidth(maxWidth, maxHeight) != null
        // The map screen stays composed under every other screen. Rebuilding it cost ~0.5 s (a new
        // MapView, style and tiles) each time the user came back; while covered it only stops drawing.
        MapScreen(
            ui = ui,
            app = app,
            hasLocation = hasLocation,
            onRequestPermission = requestPermission,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenLog = { screen = Screen.LOG },
            onOpenHistory = { screen = Screen.HISTORY },
            onOpenBookmarks = { screen = Screen.BOOKMARKS },
            mapActive = (screen == Screen.MAP || besideMap) && !showSetup,
            controlsVisible = screen == Screen.MAP,
            overlayStartPx = if (besideMap) overlayWidth else 0,
        )
        if (screen == Screen.BOOKMARKS) {
            DrivingOverlay(onWidth = { overlayWidth = it }) {
                BookmarksScreen(app) { screen = Screen.MAP }
            }
        } else if (screen != Screen.MAP) {
            Surface(Modifier.fillMaxSize().blockTouchesBelow(), color = MaterialTheme.colorScheme.background) {
                OtherScreen(app, ui, screen, tripId, history, onScreen = { screen = it }, onTrip = { tripId = it }, onSetup = { showSetup = true })
            }
        }
        BookmarkDialogs(app.bookmarks)
        if (showSetup) {
            Surface(Modifier.fillMaxSize().blockTouchesBelow(), color = MaterialTheme.colorScheme.background) {
                OnboardingScreen(
                    app = app,
                    onPermissionsChanged = refreshPermissions,
                    onContinue = {
                        setupPreferences.edit { putBoolean("completed", true) }
                        showSetup = false
                    },
                    onSettings = {
                        setupPreferences.edit { putBoolean("completed", true) }
                        showSetup = false
                        screen = Screen.SETTINGS
                    },
                )
            }
        }
    }
}

/** Every screen except the map (drawn on top of it). */
@Composable
private fun OtherScreen(
    app: AppGraph,
    ui: UiState,
    screen: Screen,
    tripId: String?,
    history: List<TripSummary>,
    onScreen: (Screen) -> Unit,
    onTrip: (String) -> Unit,
    onSetup: () -> Unit,
) {
    when (screen) {
        Screen.MAP -> Unit

        Screen.BOOKMARKS -> BookmarksScreen(app, onBack = { onScreen(Screen.MAP) })

        Screen.HISTORY -> HistoryScreen(app, onBack = { onScreen(Screen.MAP) }, onOpen = {
            onTrip(it.id)
            onScreen(Screen.TRIP)
        })

        Screen.TRIP -> {
            val trip = history.firstOrNull { it.id == tripId }
            if (trip == null) {
                LaunchedEffect(Unit) { onScreen(Screen.HISTORY) }
            } else {
                TripDetailScreen(app, trip, onBack = { onScreen(Screen.HISTORY) })
            }
        }

        Screen.SETTINGS -> SettingsScreen(ui, app, onBack = { onScreen(Screen.MAP) }, onOpenLog = { onScreen(Screen.LOG) }, onSetup = onSetup)

        Screen.LOG -> LogScreen(app, onBack = { onScreen(Screen.SETTINGS) })
    }
}

/**
 * Stops touches from reaching what is drawn underneath (the map stays composed below other screens).
 * A pointer-input target makes this overlay win hit testing over its map sibling. Do not consume
 * events: even consumption in the final pass cancels the child scroll detector before a drag starts.
 */
internal fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
