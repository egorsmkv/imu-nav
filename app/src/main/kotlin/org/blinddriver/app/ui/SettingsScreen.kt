package org.blinddriver.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.blinddriver.app.AppGraph
import org.blinddriver.app.R
import org.blinddriver.app.UiState
import org.blinddriver.app.cells.CellSource
import org.blinddriver.core.cells.Radio
import java.text.NumberFormat

private val RADIO_CHOICES = listOf(Radio.GSM to "2G", Radio.UMTS to "3G", Radio.LTE to "4G", Radio.NR to "5G")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(ui: UiState, g: AppGraph, onBack: () -> Unit, onOpenLog: () -> Unit) {
    val context = LocalContext.current
    val c = ui.cells
    val mgr = g.cells
    val busy = c.busy != null
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var mccs by remember { mutableStateOf(c.mccs) }
    var token by remember { mutableStateOf(mgr.savedToken()) }
    var syncUrl by remember { mutableStateOf(c.syncUrl) }
    var syncKey by remember { mutableStateOf(mgr.savedSyncKey()) }
    var autoSync by remember { mutableStateOf(c.autoSync) }
    var confirmReset by remember { mutableStateOf(false) }
    fun save() = mgr.saveSettings(syncUrl, syncKey, autoSync, mccs)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mgr.importFile { context.contentResolver.openInputStream(uri) }
    }
    val routing by g.offlineRouting.status.collectAsStateWithLifecycle()
    var packUrl by remember { mutableStateOf(routing.packUrl) }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) g.offlineRouting.importZip { context.contentResolver.openInputStream(uri) }
    }
    LaunchedEffect(c.message) { c.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(routing.message) { routing.message?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        save()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (busy) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.busy.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (c.busyCancellable) TextButton(onClick = { mgr.cancelTask() }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }

            // ---------------- Language
            SectionHeader(stringResource(R.string.sec_language))
            val language by g.language.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text(stringResource(R.string.language_title)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.language_hint))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            org.blinddriver.app.AppLanguage.CHOICES.forEach { choice ->
                                val label = when (choice) {
                                    org.blinddriver.app.AppLanguage.UKRAINIAN -> "Українська"
                                    org.blinddriver.app.AppLanguage.ENGLISH -> "English"
                                    else -> stringResource(R.string.language_system)
                                }
                                FilterChip(
                                    selected = language == choice,
                                    onClick = {
                                        if (language != choice) {
                                            save()
                                            g.setLanguage(choice)
                                            (context as? android.app.Activity)?.recreate()
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                },
                leadingContent = { Icon(androidx.compose.material.icons.Icons.Filled.Translate, contentDescription = null) },
            )

            // ---------------- Map start
            MapStartSection(g, ui)

            // ---------------- Battery
            SectionHeader(stringResource(R.string.sec_power))
            val powerMode by g.powerMode.collectAsStateWithLifecycle()
            val profile by g.powerProfile.collectAsStateWithLifecycle()
            val screenOn by g.keepScreenOn.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text(stringResource(R.string.power_title)) },
                supportingContent = {
                    Column {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            org.blinddriver.app.power.PowerMode.entries.forEach { m ->
                                FilterChip(selected = powerMode == m, onClick = { g.setPowerMode(m) }, label = { Text(powerModeName(m)) })
                            }
                        }
                        Text(
                            stringResource(
                                when (powerMode) {
                                    org.blinddriver.app.power.PowerMode.AUTO -> R.string.power_auto_hint
                                    org.blinddriver.app.power.PowerMode.PERFORMANCE -> R.string.power_performance_hint
                                    org.blinddriver.app.power.PowerMode.BALANCED -> R.string.power_balanced_hint
                                    org.blinddriver.app.power.PowerMode.SAVER -> R.string.power_saver_hint
                                },
                            ),
                        )
                        if (powerMode == org.blinddriver.app.power.PowerMode.AUTO) {
                            val active = when (profile) {
                                org.blinddriver.app.power.PowerProfile.PERFORMANCE -> org.blinddriver.app.power.PowerMode.PERFORMANCE
                                org.blinddriver.app.power.PowerProfile.SAVER -> org.blinddriver.app.power.PowerMode.SAVER
                                else -> org.blinddriver.app.power.PowerMode.BALANCED
                            }
                            Text(
                                stringResource(R.string.power_auto_now, powerModeName(active), g.power.batteryPercent()?.let { "$it %" } ?: "—"),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                leadingContent = { Icon(androidx.compose.material.icons.Icons.Filled.BatteryChargingFull, contentDescription = null) },
            )
            SwitchItem(stringResource(R.string.power_screen_on), stringResource(R.string.power_screen_on_summary), screenOn) { g.setKeepScreenOn(it) }

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
                leadingContent = { Icon(androidx.compose.material.icons.Icons.Filled.Route, contentDescription = null) },
            )
            routing.busy?.let {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { g.offlineRouting.cancel() }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
            Field(packUrl, {
                packUrl = it
                g.offlineRouting.setPackUrl(it)
            }, stringResource(R.string.routing_url), "https://…/graph-ukraine.zip", keyboard = KeyboardType.Uri)
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { g.offlineRouting.download(packUrl) }, enabled = packUrl.isNotBlank() && routing.busy == null) {
                    Text(stringResource(R.string.action_download))
                }
                OutlinedButton(onClick = { pickPack.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = routing.busy == null) {
                    Text(stringResource(R.string.routing_import))
                }
                val bundled = routing.bundled
                if (pack == null && bundled != null) {
                    OutlinedButton(onClick = { g.offlineRouting.installBundled() }, enabled = routing.busy == null) {
                        Text(stringResource(R.string.routing_install_builtin, bundled.name))
                    }
                }
                if (pack != null) {
                    TextButton(onClick = { g.offlineRouting.remove() }, enabled = routing.busy == null) {
                        Text(stringResource(R.string.routing_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            SwitchItem(stringResource(R.string.routing_allow_online), stringResource(R.string.routing_allow_online_summary), routing.allowOnline) {
                g.offlineRouting.setAllowOnline(it)
            }

            // ---------------- Cell towers
            SectionHeader(stringResource(R.string.sec_cells))
            SwitchItem(stringResource(R.string.cells_show_map), null, c.showTowers) { mgr.setShowTowers(it) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.cells_types)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.cells_types_hint))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RADIO_CHOICES.forEach { (radio, label) ->
                                FilterChip(selected = radio in c.radios, onClick = { mgr.setRadioEnabled(radio, radio !in c.radios) }, label = { Text(label) })
                            }
                        }
                        if (c.radios.isEmpty()) Text(stringResource(R.string.cells_none_warning), color = MaterialTheme.colorScheme.error)
                    }
                },
            )
            val nf = NumberFormat.getIntegerInstance()
            val names = CellSource.entries.associateWith { sourceName(it) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.cells_total, nf.format(c.total))) },
                supportingContent = {
                    Text(
                        CellSource.entries.filter { (c.counts[it] ?: 0) > 0 }.joinToString(" · ") { "${names[it]}: ${nf.format(c.counts[it] ?: 0)}" },
                    )
                },
            )
            Field(mccs, {
                mccs = it
                save()
            }, stringResource(R.string.cells_region), stringResource(R.string.cells_region_hint), keyboard = KeyboardType.Number)

            // ---------------- Data sources
            SectionHeader(stringResource(R.string.sec_sources))
            ListItem(
                headlineContent = { Text(stringResource(R.string.mozilla_title)) },
                supportingContent = { Text(stringResource(R.string.mozilla_summary)) },
                trailingContent = {
                    TextButton(onClick = {
                        save()
                        mgr.downloadMozilla()
                    }, enabled = !busy) { Text(stringResource(R.string.action_download)) }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.ocid_title)) },
                supportingContent = { Text(stringResource(R.string.ocid_summary)) },
            )
            Field(token, { token = it }, stringResource(R.string.ocid_token), null, secret = true)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    save()
                    mgr.downloadOpenCellId(token)
                }, enabled = token.isNotBlank() && !busy) { Text(stringResource(R.string.action_download)) }
                OutlinedButton(onClick = {
                    save()
                    pickFile.launch(arrayOf("*/*"))
                }, enabled = !busy) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_import_file))
                }
            }

            // ---------------- Sharing server
            SectionHeader(stringResource(R.string.sec_sync))
            Text(
                stringResource(R.string.sync_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Field(syncUrl, {
                syncUrl = it
                save()
            }, stringResource(R.string.sync_url), "https://cells.example.org", keyboard = KeyboardType.Uri)
            Field(syncKey, {
                syncKey = it
                save()
            }, stringResource(R.string.sync_key), null, secret = true)
            if (syncUrl.trim().startsWith("http://") && syncKey.isNotBlank()) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sync_http_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            SwitchItem(stringResource(R.string.sync_auto), stringResource(R.string.sync_auto_summary), autoSync) {
                autoSync = it
                save()
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    save()
                    mgr.sync()
                }, enabled = syncUrl.isNotBlank() && !busy) { Text(stringResource(R.string.action_sync_now)) }
                Spacer(Modifier.size(12.dp))
                c.lastSync?.let { Text(stringResource(R.string.sync_last, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            // ---------------- Learning
            SectionHeader(stringResource(R.string.sec_learning))
            SwitchItem(stringResource(R.string.learning_title), stringResource(R.string.learning_summary), c.learning) {
                mgr.setLearning(it)
                g.refresh()
            }

            // ---------------- Database
            SectionHeader(stringResource(R.string.sec_database))
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_export)) },
                supportingContent = { Text(stringResource(R.string.export_summary)) },
                modifier = Modifier.clickable(enabled = !busy) { mgr.exportDatabase() },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text(stringResource(R.string.reset_summary)) },
                leadingContent = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(enabled = !busy) { confirmReset = true },
            )

            // ---------------- Diagnostics
            SectionHeader(stringResource(R.string.sec_diagnostics))
            var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) unrestricted = isBatteryUnrestricted(context)
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.battery_title)) },
                supportingContent = {
                    Text(
                        stringResource(if (unrestricted) R.string.battery_unrestricted else R.string.battery_restricted),
                        color = if (unrestricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.clickable(enabled = !unrestricted) {
                    runCatching {
                        @android.annotation.SuppressLint("BatteryLife")
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${context.packageName}".toUri(),
                        )
                        context.startActivity(intent)
                    }
                },
            )
            SwitchItem(stringResource(R.string.simulate_gps_loss), stringResource(R.string.simulate_gps_loss_summary), ui.simulateGpsLoss) { g.setSimulateGpsLoss(it) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.trip_log)) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLog),
            )

            // ---------------- About
            SectionHeader(stringResource(R.string.sec_about))
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_version, version)) },
                supportingContent = { Text(stringResource(R.string.about_credits) + "\n\n" + stringResource(R.string.about_disclaimer)) },
            )
        }
    }

    if (confirmReset) {
        var deleteLearned by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text(stringResource(R.string.reset_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reset_text))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deleteLearned = !deleteLearned }) {
                        Checkbox(checked = deleteLearned, onCheckedChange = { deleteLearned = it })
                        Text((c.counts[CellSource.LEARNED] ?: 0L).toInt().let { n -> pluralStringResource(R.plurals.reset_learned, n, n) })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    mgr.resetDatabase(deleteLearned)
                }) {
                    Text(stringResource(R.string.action_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun powerModeName(m: org.blinddriver.app.power.PowerMode): String = stringResource(
    when (m) {
        org.blinddriver.app.power.PowerMode.AUTO -> R.string.power_auto
        org.blinddriver.app.power.PowerMode.PERFORMANCE -> R.string.power_performance
        org.blinddriver.app.power.PowerMode.BALANCED -> R.string.power_balanced
        org.blinddriver.app.power.PowerMode.SAVER -> R.string.power_saver
    },
)

@Composable
private fun sourceName(s: CellSource): String = stringResource(
    when (s) {
        CellSource.SHARED -> R.string.src_label_shared
        CellSource.OPENCELLID -> R.string.src_label_ocid
        CellSource.LEARNED -> R.string.src_label_learned
        CellSource.MOZILLA -> R.string.src_label_mozilla
        CellSource.BUNDLED -> R.string.src_label_bundled
    },
)

@Composable
private fun SectionHeader(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SwitchItem(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String?, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Settings → Map start: open the map at the phone's position, or at a fixed place. */
@Composable
private fun MapStartSection(g: AppGraph, ui: UiState) {
    val mode by g.mapStartMode.collectAsStateWithLifecycle()
    val fixed by g.mapStartFixed.collectAsStateWithLifecycle()
    var text by remember(fixed) { mutableStateOf(fixed?.let { formatLatLon(it) }.orEmpty()) }
    val parsed = org.blinddriver.app.MapStartPrefs.parse(text)
    val position = ui.currentPosition.takeIf { ui.hasTrustedPosition }

    SectionHeader(stringResource(R.string.sec_map_start))
    ListItem(
        headlineContent = { Text(stringResource(R.string.map_start_title)) },
        supportingContent = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == org.blinddriver.app.MapStartMode.GPS,
                        onClick = { g.setMapStart(org.blinddriver.app.MapStartMode.GPS, fixed) },
                        label = { Text(stringResource(R.string.map_start_gps)) },
                    )
                    FilterChip(
                        selected = mode == org.blinddriver.app.MapStartMode.FIXED,
                        onClick = {
                            // Picking "fixed" without a place yet: start from where the user is or looks.
                            val p = fixed ?: position ?: g.lastMapCenter
                            g.setMapStart(org.blinddriver.app.MapStartMode.FIXED, p)
                        },
                        label = { Text(stringResource(R.string.map_start_fixed)) },
                    )
                }
                Text(
                    stringResource(
                        if (mode == org.blinddriver.app.MapStartMode.GPS) R.string.map_start_gps_hint else R.string.map_start_fixed_hint,
                    ),
                )
            }
        },
        leadingContent = { Icon(androidx.compose.material.icons.Icons.Filled.Place, contentDescription = null) },
    )
    if (mode != org.blinddriver.app.MapStartMode.FIXED) return

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
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { parsed?.let { g.setMapStart(org.blinddriver.app.MapStartMode.FIXED, it) } },
            enabled =
            parsed != null && formatLatLon(parsed) != fixed?.let { formatLatLon(it) },
        ) {
            Text(stringResource(R.string.action_apply))
        }
        OutlinedButton(onClick = { position?.let { g.setMapStart(org.blinddriver.app.MapStartMode.FIXED, it) } }, enabled = position != null) {
            Text(stringResource(R.string.map_start_use_position))
        }
        OutlinedButton(onClick = { g.lastMapCenter?.let { g.setMapStart(org.blinddriver.app.MapStartMode.FIXED, it) } }, enabled = g.lastMapCenter != null) {
            Text(stringResource(R.string.map_start_use_center))
        }
    }
}

private fun formatLatLon(p: org.blinddriver.core.geo.GeoPoint) = String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lon)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(g: AppGraph, onBack: () -> Unit) {
    var lines by remember { mutableStateOf(g.tripLog.recent) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        while (true) {
            lines = g.tripLog.recent
            delay(1000)
        }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trip_log)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Text(stringResource(R.string.trip_log_empty), modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(lines) { line ->
                    Text(line.substringAfter("] "), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}
