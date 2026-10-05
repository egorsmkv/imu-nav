package org.imunav.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.imunav.app.AppGraph
import org.imunav.app.MapStartMode
import org.imunav.app.MapStartPrefs
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.maps.OfflineMapStatus
import org.imunav.app.obd.ObdStatus
import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.PhotonServer
import java.util.Locale

/**
 * Settings → Offline map: a downloadable map pack for the whole country, and automatic saving of
 * the map along each planned route (for when there is no pack).
 */
@Composable
internal fun OfflineMapSection(app: AppGraph, state: OfflineMapStatus) {
    val context = LocalContext.current
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.offlineMap.importZip { context.contentResolver.openInputStream(uri) }
    }
    var url by remember(state.packUrl) { mutableStateOf(state.packUrl) }
    val pack = state.pack

    SectionHeader(stringResource(R.string.sec_offline_map))
    ListItem(
        headlineContent = {
            Text(
                if (pack == null) {
                    stringResource(R.string.offline_map_none)
                } else {
                    stringResource(R.string.offline_map_pack, pack.name, (pack.sizeBytes / 1_048_576).toInt(), pack.builtAt.take(10))
                },
            )
        },
        supportingContent = {
            when {
                pack == null -> Text(stringResource(R.string.offline_map_none_hint))
                state.bundled -> Text(stringResource(R.string.offline_map_bundled_hint))
            }
        },
        leadingContent = { Icon(Icons.Filled.Map, contentDescription = null) },
    )
    state.busy?.let { busy ->
        Column(Modifier.padding(horizontal = 16.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(busy, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { app.offlineMap.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
    Field(
        url,
        { url = it },
        stringResource(R.string.offline_map_url),
        "https://…/map-ukraine.zip",
        keyboard = KeyboardType.Uri,
        helper = stringResource(R.string.offline_map_url_help),
    )
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { app.offlineMap.download(url) }, enabled = url.isNotBlank() && state.busy == null) {
            Text(stringResource(R.string.action_download))
        }
        OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = state.busy == null) {
            Text(stringResource(R.string.routing_import))
        }
        if (pack != null && !state.bundled) {
            TextButton(onClick = { app.offlineMap.remove() }, enabled = state.busy == null) {
                Text(stringResource(R.string.routing_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (pack != null) {
        SwitchItem(stringResource(R.string.offline_map_use), stringResource(R.string.offline_map_use_summary), state.useOffline) { app.offlineMap.setUseOffline(it) }
    }
    SwitchItem(
        stringResource(R.string.corridor_title),
        state.corridorText ?: stringResource(if (state.offlineInUse) R.string.corridor_summary_pack else R.string.corridor_summary),
        state.corridor,
    ) { app.offlineMap.setCorridorEnabled(it) }
}

/**
 * Settings → Car speed and hills: the OBD-II adapter (pick a paired device, test the link) and
 * barometric terrain matching (needs a barometer and a routing pack with road heights).
 */
@Composable
internal fun CarSensorsSection(app: AppGraph) {
    val context = LocalContext.current
    val obd = app.obd
    val enabled by obd.enabled.collectAsStateWithLifecycle()
    val device by obd.device.collectAsStateWithLifecycle()
    val status by obd.status.collectAsStateWithLifecycle()
    val routing by app.offlineRouting.status.collectAsStateWithLifecycle()
    val tuning by app.tuning.collectAsStateWithLifecycle()
    // Re-read the permission after the system dialog answers.
    var permissionChecks by remember { mutableIntStateOf(0) }
    val hasPermission = remember(permissionChecks) { obd.hasPermission() }
    val devices = remember(permissionChecks, enabled) { obd.pairedDevices() }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionChecks++
        if (granted) obd.setEnabled(true)
    }
    // A test connection started here ends when Settings closes, unless a trip is using it.
    var testing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (testing && !app.engine.state.active) obd.stop() }
    }

    SectionHeader(stringResource(R.string.sec_car_sensors))
    if (obd.available) {
        SwitchItem(stringResource(R.string.obd_title), stringResource(R.string.obd_summary), enabled) { on ->
            val permission = obd.permission
            if (on && permission != null && !obd.hasPermission()) {
                askPermission.launch(permission)
            } else {
                obd.setEnabled(on)
            }
        }
        if (enabled && !hasPermission) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.obd_permission)) },
                supportingContent = {
                    OutlinedButton(onClick = { obd.permission?.let(askPermission::launch) }) { Text(stringResource(R.string.obd_allow)) }
                },
            )
        } else if (enabled) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.obd_adapter)) },
                supportingContent = {
                    Column {
                        if (devices.isEmpty()) {
                            Text(stringResource(R.string.obd_no_devices))
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                devices.forEach { d ->
                                    FilterChip(selected = device?.address == d.address, onClick = { obd.setDevice(d) }, label = { Text(d.name) })
                                }
                            }
                        }
                        TextButton(onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } }) {
                            Text(stringResource(R.string.obd_open_bluetooth))
                        }
                        Text(obdStatusText(status), color = if (status.state == ObdStatus.State.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (device != null && !app.engine.state.active) {
                            if (status.state == ObdStatus.State.OFF || status.state == ObdStatus.State.ERROR) {
                                OutlinedButton(onClick = {
                                    testing = true
                                    obd.stop() // clear an old error, then try again
                                    obd.start()
                                }) { Text(stringResource(R.string.obd_test)) }
                            } else if (testing) {
                                TextButton(onClick = {
                                    testing = false
                                    obd.stop()
                                }) { Text(stringResource(R.string.obd_disconnect)) }
                            }
                        }
                    }
                },
            )
        }
    }

    if (app.sensors.hasBarometer) {
        val packText = stringResource(if (routing.pack?.elevation == true) R.string.terrain_pack_yes else R.string.terrain_pack_no)
        SwitchItem(stringResource(R.string.terrain_title), stringResource(R.string.terrain_summary) + "\n" + packText, tuning.terrainMatch) { app.setTerrainMatch(it) }
    } else {
        ListItem(headlineContent = { Text(stringResource(R.string.terrain_title)) }, supportingContent = { Text(stringResource(R.string.terrain_no_barometer)) })
    }
}

@Composable
private fun obdStatusText(status: ObdStatus): String = when (status.state) {
    ObdStatus.State.OFF -> stringResource(R.string.obd_status_off)
    ObdStatus.State.CONNECTING -> stringResource(R.string.obd_status_connecting)
    ObdStatus.State.CONNECTED -> status.speedKmh?.let { stringResource(R.string.obd_status_connected, it) } ?: stringResource(R.string.obd_status_connected_waiting)
    ObdStatus.State.ERROR -> stringResource(R.string.obd_status_error, status.message.orEmpty())
}

/**
 * Settings → Address search: which Photon server answers searches the offline index cannot.
 * The user can type a self-hosted server, test it, or go back to the public one.
 */
@Composable
internal fun SearchServerSection(app: AppGraph, onlineAllowed: Boolean) {
    val saved by app.search.photonUrl.collectAsStateWithLifecycle()
    var text by remember(saved) { mutableStateOf(if (saved == PhotonServer.DEFAULT_URL) "" else saved) }
    val normalized = PhotonServer.normalize(text)
    val scope = rememberCoroutineScope()
    // Result of the last "Test" (null = not tested since the text changed).
    var testResult by remember(text) { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val res = LocalResources.current

    SectionHeader(stringResource(R.string.sec_search))
    ListItem(
        headlineContent = { Text(stringResource(R.string.search_server_title)) },
        supportingContent = {
            Text(
                stringResource(if (onlineAllowed) R.string.search_server_hint else R.string.search_server_hint_offline),
                color = if (onlineAllowed) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        },
        leadingContent = { Icon(Icons.Filled.TravelExplore, contentDescription = null) },
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.search_server_url)) },
        placeholder = { Text(PhotonServer.DEFAULT_URL) },
        singleLine = true,
        isError = normalized == null,
        supportingText = {
            when {
                normalized == null -> Text(stringResource(R.string.search_server_invalid))
                testResult != null -> Text(testResult.orEmpty())
                else -> Text(stringResource(R.string.search_server_in_use, saved))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { app.search.setPhotonUrl(text) }, enabled = normalized != null && normalized != saved) {
            Text(stringResource(R.string.action_apply))
        }
        OutlinedButton(
            onClick = {
                testing = true
                scope.launch {
                    val result = app.search.testPhoton(text)
                    testing = false
                    testResult = result.fold(
                        onSuccess = { count -> res.getQuantityString(R.plurals.search_server_ok, count, count) },
                        onFailure = { error -> res.getString(R.string.search_server_failed, error.message ?: error.javaClass.simpleName) },
                    )
                }
            },
            enabled = normalized != null && !testing,
        ) {
            Text(stringResource(if (testing) R.string.search_server_testing else R.string.search_server_test))
        }
        if (saved != PhotonServer.DEFAULT_URL) {
            TextButton(onClick = {
                app.search.setPhotonUrl("")
                text = ""
            }) { Text(stringResource(R.string.search_server_reset)) }
        }
    }
}

/** Settings → Map start: open the map at the phone's position, or at a fixed place. */
@Composable
internal fun MapStartSection(app: AppGraph, ui: UiState) {
    val mode by app.mapStartMode.collectAsStateWithLifecycle()
    val fixed by app.mapStartFixed.collectAsStateWithLifecycle()
    var text by remember(fixed) { mutableStateOf(fixed?.let { formatLatLon(it) }.orEmpty()) }
    val parsed = MapStartPrefs.parse(text)
    val position = ui.currentPosition.takeIf { ui.hasTrustedPosition }

    SectionHeader(stringResource(R.string.sec_map_start))
    ListItem(
        headlineContent = { Text(stringResource(R.string.map_start_title)) },
        supportingContent = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == MapStartMode.GPS,
                        onClick = { app.setMapStart(MapStartMode.GPS, fixed) },
                        label = { Text(stringResource(R.string.map_start_gps)) },
                    )
                    FilterChip(
                        selected = mode == MapStartMode.FIXED,
                        onClick = {
                            // Picking "fixed" without a place yet: start from where the user is or looks.
                            val p = fixed ?: position ?: app.lastMapCenter
                            app.setMapStart(MapStartMode.FIXED, p)
                        },
                        label = { Text(stringResource(R.string.map_start_fixed)) },
                    )
                }
                Text(
                    stringResource(
                        if (mode == MapStartMode.GPS) R.string.map_start_gps_hint else R.string.map_start_fixed_hint,
                    ),
                )
            }
        },
        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
    )
    if (mode != MapStartMode.FIXED) return

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.map_start_coords)) },
        placeholder = { Text("50.4501, 30.5234") },
        singleLine = true,
        isError = text.isNotBlank() && parsed == null,
        supportingText = { if (text.isNotBlank() && parsed == null) Text(stringResource(R.string.map_start_coords_invalid)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { parsed?.let { app.setMapStart(MapStartMode.FIXED, it) } },
            enabled =
            parsed != null && formatLatLon(parsed) != fixed?.let { formatLatLon(it) },
        ) {
            Text(stringResource(R.string.action_apply))
        }
        OutlinedButton(onClick = { position?.let { app.setMapStart(MapStartMode.FIXED, it) } }, enabled = position != null) {
            Text(stringResource(R.string.map_start_use_position))
        }
        OutlinedButton(onClick = { app.lastMapCenter?.let { app.setMapStart(MapStartMode.FIXED, it) } }, enabled = app.lastMapCenter != null) {
            Text(stringResource(R.string.map_start_use_center))
        }
    }
}

private fun formatLatLon(p: GeoPoint) = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)
