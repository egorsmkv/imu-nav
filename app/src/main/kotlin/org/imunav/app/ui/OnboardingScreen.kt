package org.imunav.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.setup.Preparation

/** One feature's grant, with API-gated permission names and a localized explanation. */
private data class SetupPermission(val title: Int, val explanation: Int, val permissions: List<String>)

/** Visual urgency for a checklist item, kept independent from its localized status text. */
private enum class SetupStatus { READY, ATTENTION, WORKING, OPTIONAL, ERROR }

/** Only runtime permissions supported by this Android version are offered. */
private fun setupPermissions(): List<SetupPermission> = buildList {
    add(SetupPermission(R.string.setup_location, R.string.setup_location_hint, listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
    if (Build.VERSION.SDK_INT >= 33) add(SetupPermission(R.string.setup_notifications, R.string.setup_notifications_hint, listOf(Manifest.permission.POST_NOTIFICATIONS)))
    if (Build.VERSION.SDK_INT >= 29) add(SetupPermission(R.string.setup_activity, R.string.setup_activity_hint, listOf(Manifest.permission.ACTIVITY_RECOGNITION)))
    if (Build.VERSION.SDK_INT >= 31) add(SetupPermission(R.string.setup_bluetooth, R.string.setup_bluetooth_hint, listOf(Manifest.permission.BLUETOOTH_CONNECT)))
}

/** First-run checklist; preparation belongs to AppGraph, so rotation never restarts an import. */
@Composable
fun OnboardingScreen(app: AppGraph, onPermissionsChanged: () -> Unit, onContinue: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val activity = context as Activity
    val preferences = remember(context) { context.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    val permissionItems = remember { setupPermissions() }
    var permissionRevision by remember { mutableIntStateOf(0) }
    var batteryUnrestricted by remember { mutableStateOf(false) }
    var locationEnabled by remember { mutableStateOf(false) }
    var systemUnavailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val routing by app.offlineRouting.status.collectAsStateWithLifecycle()
    val cells by app.cells.status.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()

    DisposableEffect(activity, darkTheme) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val previousStatus = bars.isAppearanceLightStatusBars
        val previousNavigation = bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = !darkTheme
        bars.isAppearanceLightNavigationBars = !darkTheme
        onDispose {
            bars.isAppearanceLightStatusBars = previousStatus
            bars.isAppearanceLightNavigationBars = previousNavigation
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
        onPermissionsChanged()
    }
    val systemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRevision++ }

    /** A missing settings Activity is reported in the checklist rather than crashing setup. */
    fun openSystem(intent: Intent) {
        systemUnavailable = runCatching { systemLauncher.launch(intent) }.isFailure
    }

    /** Request only missing grants; location always includes coarse alongside fine. */
    fun request(names: List<String>) {
        val missing = names.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        val requestable = missing.filter { !preferences.getBoolean(it, false) || ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (requestable.isEmpty()) {
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        } else {
            preferences.edit { requestable.forEach { putBoolean(it, true) } }
            val requested = if (Manifest.permission.ACCESS_FINE_LOCATION in requestable) (requestable + Manifest.permission.ACCESS_COARSE_LOCATION).distinct() else requestable
            permissionLauncher.launch(requested.toTypedArray())
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRevision++
                scope.launch {
                    batteryUnrestricted = withContext(Dispatchers.IO) { isBatteryUnrestricted(context) }
                    locationEnabled = withContext(Dispatchers.IO) { app.sensors.locationEnabled }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        scope.launch {
            batteryUnrestricted = withContext(Dispatchers.IO) { isBatteryUnrestricted(context) }
            locationEnabled = withContext(Dispatchers.IO) { app.sensors.locationEnabled }
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Back may leave the Activity, but must not mark onboarding complete or expose the map.
    BackHandler { activity.moveTaskToBack(true) }

    val granted = remember(permissionRevision) {
        permissionItems.associateWith { item -> item.permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
    }
    val allReady = granted.values.all { it } && locationEnabled && batteryUnrestricted && routing.preparation == Preparation.READY
    val readyCount = granted.values.count { it } + listOf(locationEnabled, batteryUnrestricted, routing.preparation == Preparation.READY).count { it }
    val requiredCount = permissionItems.size + REQUIRED_NON_PERMISSION_ITEMS
    Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.padding(14.dp).size(28.dp))
                    }
                    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LanguageSelector(app)
                ReadinessCard(readyCount, requiredCount)

                SectionHeader(number = 1, title = stringResource(R.string.setup_access))
                if (granted.values.any { !it }) {
                    Button(
                        onClick = { request(permissionItems.filter { granted[it] != true }.flatMap { it.permissions }) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_enable_permissions))
                    }
                }
                SetupCard(
                    title = stringResource(R.string.setup_location_services),
                    explanation = stringResource(R.string.setup_location_services_hint),
                    status = stringResource(if (locationEnabled) R.string.setup_ready else R.string.setup_location_off),
                    statusKind = if (locationEnabled) SetupStatus.READY else SetupStatus.ATTENTION,
                ) {
                    if (!locationEnabled) {
                        OutlinedButton(
                            onClick = { openSystem(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setup_open_settings))
                        }
                    }
                }
                permissionItems.forEach { item ->
                    val ready = granted[item] == true
                    val blocked = item.permissions.any {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED &&
                            preferences.getBoolean(it, false) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                    }
                    SetupCard(
                        title = stringResource(item.title),
                        explanation = stringResource(item.explanation),
                        status = stringResource(if (ready) R.string.setup_granted else R.string.setup_not_granted),
                        statusKind = if (ready) SetupStatus.READY else SetupStatus.ATTENTION,
                    ) {
                        if (!ready) {
                            if (item.permissions.first() == Manifest.permission.ACCESS_FINE_LOCATION &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            ) {
                                Text(stringResource(R.string.setup_approximate), color = MaterialTheme.colorScheme.error)
                            }
                            OutlinedButton(onClick = { request(item.permissions) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (blocked) R.string.setup_open_settings else R.string.action_allow))
                            }
                        }
                    }
                }
                SetupCard(
                    title = stringResource(R.string.battery_title),
                    explanation = stringResource(R.string.setup_battery_hint),
                    status = stringResource(if (batteryUnrestricted) R.string.setup_granted else R.string.setup_not_granted),
                    statusKind = if (batteryUnrestricted) SetupStatus.READY else SetupStatus.ATTENTION,
                ) {
                    if (!batteryUnrestricted) {
                        OutlinedButton(
                            onClick = {
                                @android.annotation.SuppressLint("BatteryLife") // Background navigation needs uninterrupted positioning; the user explicitly opts in.
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                                openSystem(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_allow)) }
                    }
                }

                SectionHeader(number = 2, title = stringResource(R.string.setup_data))
                PreparationCard(
                    title = R.string.setup_routing,
                    explanation = stringResource(R.string.setup_routing_hint),
                    preparation = routing.preparation,
                    progress = routing.busy,
                    message = routing.message,
                    canRetry = routing.busy == null,
                    optionalAction = routing.bundled?.let { stringResource(R.string.routing_install_builtin, it.name) },
                    retry = app.offlineRouting::retryBundled,
                )
                PreparationCard(
                    title = R.string.setup_cells,
                    preparation = cells.preparation,
                    progress = cells.busy,
                    message = cells.message,
                    canRetry = cells.busy == null,
                    optionalAction = stringResource(R.string.setup_cells_install),
                    retry = app.cells::retryBundled,
                )
                Text(stringResource(R.string.setup_cells_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSettings) { Text(stringResource(R.string.setup_cells_server)) }
                if (systemUnavailable) Text(stringResource(R.string.setup_system_unavailable), color = MaterialTheme.colorScheme.error)
                if (!allReady) Text(stringResource(R.string.setup_limited_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { uriHandler.openUri(TELEGRAM_GROUP_URL) }) { Text(stringResource(R.string.telegram_group)) }
            }
        }
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 8.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(onClick = onContinue, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    Text(stringResource(if (allReady) R.string.setup_continue else R.string.setup_continue_limited))
                }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.setup_manage_data)) }
            }
        }
    }
}

/** Shared readable card layout leaves enough space for translations and large font sizes. */
@Composable
private fun SetupCard(title: String, explanation: String, status: String, statusKind: SetupStatus, actions: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusPill(status, statusKind)
            if (explanation.isNotEmpty()) Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

/** Compact overview helps users understand that every item can also be completed later. */
@Composable
private fun ReadinessCard(ready: Int, total: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.setup_progress, ready, total), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { ready.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
            )
        }
    }
}

/** Numbered section labels make the long checklist easier to scan and resume. */
@Composable
private fun SectionHeader(number: Int, title: String) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Text(number.toString(), modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Status text and icon use semantic Material containers instead of color-only signaling. */
@Composable
private fun StatusPill(text: String, kind: SetupStatus) {
    val colors = when (kind) {
        SetupStatus.READY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SetupStatus.ATTENTION -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SetupStatus.WORKING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SetupStatus.OPTIONAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        SetupStatus.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when (kind) {
        SetupStatus.READY -> Icons.Filled.CheckCircle
        SetupStatus.ATTENTION -> Icons.Filled.Warning
        SetupStatus.WORKING -> Icons.Filled.Sync
        SetupStatus.OPTIONAL -> Icons.Filled.Info
        SetupStatus.ERROR -> Icons.Filled.Warning
    }
    Surface(shape = CircleShape, color = colors.first, contentColor = colors.second) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Progress and retries use the managers' explicit lifecycle, never their last result text. */
@Composable
private fun PreparationCard(
    title: Int,
    preparation: Preparation,
    progress: String?,
    message: String?,
    canRetry: Boolean,
    explanation: String = "",
    optionalAction: String? = null,
    retry: () -> Unit,
) {
    val status = when (preparation) {
        Preparation.CHECKING -> R.string.setup_checking
        Preparation.PREPARING -> R.string.setup_preparing
        Preparation.READY -> R.string.setup_ready
        Preparation.OPTIONAL -> R.string.setup_optional
        Preparation.UNAVAILABLE -> R.string.setup_unavailable
        Preparation.REMOVED -> R.string.setup_removed
        Preparation.FAILED -> R.string.setup_failed
    }
    val statusKind = when (preparation) {
        Preparation.READY -> SetupStatus.READY
        Preparation.CHECKING, Preparation.PREPARING -> SetupStatus.WORKING
        Preparation.OPTIONAL, Preparation.UNAVAILABLE, Preparation.REMOVED -> SetupStatus.OPTIONAL
        Preparation.FAILED -> SetupStatus.ERROR
    }
    SetupCard(stringResource(title), explanation, stringResource(status), statusKind) {
        if (preparation == Preparation.CHECKING || preparation == Preparation.PREPARING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            progress?.let { Text(it) }
        }
        if (preparation == Preparation.FAILED) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = retry, enabled = canRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_retry)) }
        }
        if (preparation == Preparation.OPTIONAL && optionalAction != null) {
            OutlinedButton(onClick = retry, enabled = canRetry, modifier = Modifier.fillMaxWidth()) { Text(optionalAction) }
        }
    }
}

private const val REQUIRED_NON_PERMISSION_ITEMS = 3
