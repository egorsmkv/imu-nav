package org.imunav.app.ui

import android.content.Context
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.maps.OfflineMapStatus
import org.imunav.app.power.PowerMode
import org.imunav.app.power.PowerProfile
import org.imunav.app.routing.OfflineRoutingStatus
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod

private const val OFFLINE_PACK_FREE_SPACE_BYTES = 2_000_000_000L
private const val BYTES_PER_GB = 1_000_000_000.0

/** Everyday controls for navigation, sensors, voice, and battery policy. */
@Composable
internal fun EverydaySettings(app: AppGraph, ui: UiState, context: Context, save: () -> Unit) {
    SettingsGroup(
        title = stringResource(R.string.settings_group_everyday),
        summary = stringResource(R.string.settings_group_everyday_summary),
        icon = Icons.Filled.Translate,
    ) {
        SettingsHelp(stringResource(R.string.settings_help_everyday))

        // ---------------- Language
        SectionHeader(stringResource(R.string.sec_language))
        LanguageSelector(app, onBeforeChange = save)

        // ---------------- Map start
        MapStartSection(app, ui)

        // ---------------- Navigation without GPS
        SectionHeader(stringResource(R.string.sec_navigation_method))
        val navigationEstimator by app.navigationEstimator.collectAsStateWithLifecycle()
        val selectedEstimator = if (ui.guidance.active) app.engine.estimator else navigationEstimator
        ListItem(
            headlineContent = { Text(stringResource(R.string.navigation_estimator_title)) },
            supportingContent = {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavigationEstimator.entries.forEach { estimator ->
                            FilterChip(
                                selected = selectedEstimator == estimator,
                                enabled = !ui.guidance.active && !ui.planning,
                                onClick = { app.setNavigationEstimator(estimator) },
                                label = { Text(navigationEstimatorName(estimator)) },
                            )
                        }
                    }
                    Text(stringResource(R.string.navigation_estimator_summary))
                    if (ui.guidance.active) Text(stringResource(R.string.navigation_estimator_locked))
                }
            },
        )
        val inertialExperiment by app.inertialExperiment.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text(stringResource(R.string.inertial_experiment_title)) },
            supportingContent = { Text(stringResource(R.string.inertial_experiment_summary)) },
            trailingContent = {
                Switch(
                    checked = inertialExperiment,
                    onCheckedChange = app::setInertialExperiment,
                    enabled = !ui.guidance.active && !ui.planning,
                )
            },
        )
        val navigationMethod by app.navigationMethod.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text(stringResource(R.string.navigation_method_title)) },
            supportingContent = {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavigationMethod.entries.forEach { method ->
                            FilterChip(
                                selected = navigationMethod == method,
                                enabled = selectedEstimator == NavigationEstimator.KOTLIN,
                                onClick = { app.setNavigationMethod(method) },
                                label = { Text(navigationMethodName(method)) },
                            )
                        }
                    }
                    Text(
                        stringResource(
                            when (navigationMethod) {
                                NavigationMethod.DEAD_RECKONING -> R.string.navigation_method_dr_summary
                                NavigationMethod.CELL_TOWERS -> R.string.navigation_method_cells_summary
                                NavigationMethod.HYBRID -> R.string.navigation_method_hybrid_summary
                            },
                        ),
                    )
                    if (selectedEstimator == NavigationEstimator.NATIVE_KALMAN) Text(stringResource(R.string.navigation_method_classic_only))
                }
            },
        )
        val voiceEnabled by app.voiceEnabled.collectAsStateWithLifecycle()
        SwitchItem(stringResource(R.string.voice_title), stringResource(R.string.voice_summary), voiceEnabled) { app.setVoiceEnabled(it) }
        val hapticsEnabled by app.haptics.enabled.collectAsStateWithLifecycle()
        SwitchItem(stringResource(R.string.haptics_title), stringResource(R.string.haptics_summary), hapticsEnabled) { app.haptics.setEnabled(it) }

        // ---------------- Car speed (OBD-II) and barometer
        CarSensorsSection(app)

        // ---------------- Battery
        SectionHeader(stringResource(R.string.sec_power))
        val powerMode by app.powerMode.collectAsStateWithLifecycle()
        val profile by app.powerProfile.collectAsStateWithLifecycle()
        val screenOn by app.keepScreenOn.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text(stringResource(R.string.power_title)) },
            supportingContent = {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PowerMode.entries.forEach { m ->
                            FilterChip(selected = powerMode == m, onClick = { app.setPowerMode(m) }, label = { Text(powerModeName(m)) })
                        }
                    }
                    Text(
                        stringResource(
                            when (powerMode) {
                                PowerMode.AUTO -> R.string.power_auto_hint
                                PowerMode.PERFORMANCE -> R.string.power_performance_hint
                                PowerMode.BALANCED -> R.string.power_balanced_hint
                                PowerMode.SAVER -> R.string.power_saver_hint
                            },
                        ),
                    )
                    if (powerMode == PowerMode.AUTO) {
                        // Reading the battery is a system call: once per screen visit, not on every redraw.
                        val battery = remember { app.power.batteryPercent() }
                        val active = when (profile) {
                            PowerProfile.PERFORMANCE -> PowerMode.PERFORMANCE
                            PowerProfile.SAVER -> PowerMode.SAVER
                            else -> PowerMode.BALANCED
                        }
                        Text(
                            stringResource(R.string.power_auto_now, powerModeName(active), battery?.let { "$it %" } ?: "—"),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            leadingContent = { Icon(Icons.Filled.BatteryChargingFull, contentDescription = null) },
        )
        SwitchItem(stringResource(R.string.power_screen_on), stringResource(R.string.power_screen_on_summary), screenOn) { app.setKeepScreenOn(it) }
        BatteryOptimizationItem(context)
    }
}

/** Offline routing pack, map pack, and online search controls. */
@Composable
internal fun MapsSettings(app: AppGraph, routing: OfflineRoutingStatus, offlineMap: OfflineMapStatus, packUrl: String, onPackUrlChange: (String) -> Unit, onPickPack: () -> Unit) {
    val context = LocalContext.current
    var availableStorageBytes by remember { mutableStateOf<Long?>(null) }
    var storageChecked by remember { mutableStateOf(false) }
    var storageCheck by remember { mutableIntStateOf(0) }
    LaunchedEffect(routing.busy == null, offlineMap.busy == null, storageCheck) {
        // Both packs use filesDir. Read its available space off the UI thread after installs and on request.
        storageChecked = false
        availableStorageBytes = withContext(Dispatchers.IO) {
            runCatching {
                val storageManager = context.getSystemService(StorageManager::class.java) ?: return@runCatching null
                storageManager.getAllocatableBytes(storageManager.getUuidForPath(context.filesDir))
            }.getOrNull()
        }
        storageChecked = true
    }
    SettingsGroup(
        title = stringResource(R.string.settings_group_maps),
        summary = stringResource(if (routing.pack == null) R.string.settings_group_maps_setup else R.string.settings_group_maps_ready),
        icon = Icons.Filled.Route,
    ) {
        SettingsHelp(stringResource(R.string.settings_help_maps))
        ListItem(
            headlineContent = { Text(stringResource(R.string.offline_storage_title)) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.offline_storage_required))
                    when (val bytes = availableStorageBytes) {
                        null -> Text(stringResource(if (storageChecked) R.string.offline_storage_unavailable else R.string.offline_storage_checking))

                        else -> {
                            Text(stringResource(R.string.offline_storage_available, bytes / BYTES_PER_GB))
                            if (bytes < OFFLINE_PACK_FREE_SPACE_BYTES) {
                                Text(stringResource(R.string.offline_storage_low), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            trailingContent = {
                TextButton(onClick = { storageCheck++ }) { Text(stringResource(R.string.offline_storage_check_again)) }
            },
        )

        // ---------------- Offline routing
        SectionHeader(stringResource(R.string.sec_routing))
        val pack = routing.pack
        ListItem(
            headlineContent = {
                Text(
                    if (pack == null) {
                        stringResource(R.string.routing_none)
                    } else {
                        stringResource(R.string.routing_pack, pack.name, (pack.sizeBytes / 1_048_576).toInt(), pack.builtAt.take(10))
                    },
                )
            },
            supportingContent = {
                when {
                    pack != null && !routing.loaded -> Text(stringResource(R.string.routing_load_failed), color = MaterialTheme.colorScheme.error)
                    pack != null -> Text(stringResource(R.string.routing_coverage, pack.bounds[0], pack.bounds[1], pack.bounds[2], pack.bounds[3]))
                    else -> {}
                }
            },
            leadingContent = { Icon(Icons.Filled.Route, contentDescription = null) },
        )
        routing.busy?.let {
            Column(Modifier.padding(horizontal = 16.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { app.offlineRouting.cancel() }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
        Field(
            packUrl,
            {
                onPackUrlChange(it)
                app.offlineRouting.setPackUrl(it)
            },
            stringResource(R.string.routing_url),
            "https://…/graph-ukraine.zip",
            keyboard = KeyboardType.Uri,
            helper = stringResource(R.string.routing_url_help),
        )
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.offlineRouting.download(packUrl) }, enabled = packUrl.isNotBlank() && routing.busy == null) {
                Text(stringResource(R.string.action_download))
            }
            OutlinedButton(onClick = { onPickPack() }, enabled = routing.busy == null) {
                Text(stringResource(R.string.routing_import))
            }
            val bundled = routing.bundled
            if (pack == null && bundled != null) {
                OutlinedButton(onClick = { app.offlineRouting.installBundled() }, enabled = routing.busy == null) {
                    Text(stringResource(R.string.routing_install_builtin, bundled.name))
                }
            }
            if (pack != null) {
                TextButton(onClick = { app.offlineRouting.remove() }, enabled = routing.busy == null) {
                    Text(stringResource(R.string.routing_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        SwitchItem(stringResource(R.string.routing_allow_online), stringResource(R.string.routing_allow_online_summary), routing.allowOnline) {
            app.offlineRouting.setAllowOnline(it)
        }

        // ---------------- Offline map display (tiles pack + route corridors)
        OfflineMapSection(app, offlineMap)

        // ---------------- Address search (online fallback server)
        SearchServerSection(app, onlineAllowed = routing.allowOnline)
    }
}
