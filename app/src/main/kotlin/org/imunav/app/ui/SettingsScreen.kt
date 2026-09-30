package org.imunav.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.TravelExplore
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.AppLanguage
import org.imunav.app.BuildConfig
import org.imunav.app.MapStartMode
import org.imunav.app.MapStartPrefs
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.cells.CellSource
import org.imunav.app.maps.OfflineMapStatus
import org.imunav.app.obd.ObdStatus
import org.imunav.app.power.PowerMode
import org.imunav.app.power.PowerProfile
import org.imunav.core.cells.Radio
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.search.PhotonServer
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RADIO_CHOICES = listOf(Radio.GSM to "2G", Radio.UMTS to "3G", Radio.LTE to "4G", Radio.NR to "5G")
private const val TELEGRAM_GROUP_URL = "https://t.me/imu_nav"

/**
 * Settings: language, map start, battery, offline routing, cell towers, sharing server and diagnostics.
 * Text fields are saved when leaving the screen (see `save()`).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(ui: UiState, app: AppGraph, onBack: () -> Unit, onOpenLog: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val c = ui.cells
    val mgr = app.cells
    val busy = c.busy != null
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    var mccs by remember { mutableStateOf(c.mccs) }
    var token by remember { mutableStateOf(mgr.savedToken()) }
    var syncUrl by remember { mutableStateOf(c.syncUrl) }
    var syncKey by remember { mutableStateOf(mgr.savedSyncKey()) }
    var autoSync by remember { mutableStateOf(c.autoSync) }
    var confirmReset by remember { mutableStateOf(false) }

    /** Store the typed server settings. */
    fun save() = mgr.saveSettings(syncUrl, syncKey, autoSync, mccs)

    /** Save every editable field before either back affordance returns to the map. */
    fun leaveSettings() {
        save()
        onBack()
    }

    BackHandler(onBack = ::leaveSettings)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mgr.importFile { context.contentResolver.openInputStream(uri) }
    }
    val routing by app.offlineRouting.status.collectAsStateWithLifecycle()
    var packUrl by remember { mutableStateOf(routing.packUrl) }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.offlineRouting.importZip { context.contentResolver.openInputStream(uri) }
    }
    LaunchedEffect(c.message) { c.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(routing.message) { routing.message?.let { snackbar.showSnackbar(it) } }
    val offlineMap by app.offlineMap.status.collectAsStateWithLifecycle()
    LaunchedEffect(offlineMap.message) { offlineMap.message?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            val navigationIcon: @Composable () -> Unit = {
                IconButton(onClick = ::leaveSettings) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            }
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = navigationIcon, scrollBehavior = scroll)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                if (busy) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.busy.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (c.busyCancellable) TextButton(onClick = { mgr.cancelTask() }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }

                SettingsIntro()
                TextButton(onClick = {
                    save()
                    onSetup()
                }) { Text(stringResource(R.string.setup_title)) }

                SettingsGroup(
                    title = stringResource(R.string.settings_group_everyday),
                    summary = stringResource(R.string.settings_group_everyday_summary),
                    icon = Icons.Filled.Translate,
                ) {
                    SettingsHelp(stringResource(R.string.settings_help_everyday))

                    // ---------------- Language
                    SectionHeader(stringResource(R.string.sec_language))
                    val language by app.language.collectAsStateWithLifecycle()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.language_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.language_hint))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppLanguage.CHOICES.forEach { choice ->
                                        val label = when (choice) {
                                            AppLanguage.UKRAINIAN -> "Українська"
                                            AppLanguage.ENGLISH -> "English"
                                            AppLanguage.RUSSIAN -> "Русский"
                                            else -> stringResource(R.string.language_system)
                                        }
                                        FilterChip(
                                            selected = language == choice,
                                            onClick = {
                                                if (language != choice) {
                                                    save()
                                                    app.setLanguage(choice)
                                                    (context as? Activity)?.recreate()
                                                }
                                            },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Filled.Translate, contentDescription = null) },
                    )

                    // ---------------- Map start
                    MapStartSection(app, ui)

                    // ---------------- Navigation without GPS
                    SectionHeader(stringResource(R.string.sec_navigation_method))
                    val navigationMethod by app.navigationMethod.collectAsStateWithLifecycle()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.navigation_method_title)) },
                        supportingContent = {
                            Column {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NavigationMethod.entries.forEach { method ->
                                        FilterChip(
                                            selected = navigationMethod == method,
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
                            }
                        },
                    )
                    val voiceEnabled by app.voiceEnabled.collectAsStateWithLifecycle()
                    SwitchItem(stringResource(R.string.voice_title), stringResource(R.string.voice_summary), voiceEnabled) { app.setVoiceEnabled(it) }
                    if (app.haptics.available) {
                        val hapticsEnabled by app.haptics.enabled.collectAsStateWithLifecycle()
                        SwitchItem(stringResource(R.string.haptics_title), stringResource(R.string.haptics_summary), hapticsEnabled) { app.haptics.setEnabled(it) }
                    }

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

                SettingsGroup(
                    title = stringResource(R.string.settings_group_maps),
                    summary = stringResource(if (routing.pack == null) R.string.settings_group_maps_setup else R.string.settings_group_maps_ready),
                    icon = Icons.Filled.Route,
                ) {
                    SettingsHelp(stringResource(R.string.settings_help_maps))

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
                            packUrl = it
                            app.offlineRouting.setPackUrl(it)
                        },
                        stringResource(R.string.routing_url),
                        "https://…/graph-ukraine.zip",
                        keyboard = KeyboardType.Uri,
                        helper = stringResource(R.string.routing_url_help),
                    )
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { app.offlineRouting.download(packUrl) }, enabled = packUrl.isNotBlank() && routing.busy == null) {
                            Text(stringResource(R.string.action_download))
                        }
                        OutlinedButton(onClick = { pickPack.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = routing.busy == null) {
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

                SettingsGroup(
                    title = stringResource(R.string.settings_group_cells),
                    summary = stringResource(R.string.settings_group_cells_summary),
                    icon = Icons.Filled.CellTower,
                ) {
                    SettingsHelp(stringResource(R.string.settings_help_cells))

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
                    Field(
                        mccs,
                        {
                            mccs = it
                            save()
                        },
                        stringResource(R.string.cells_region),
                        "255",
                        keyboard = KeyboardType.Number,
                        helper = stringResource(R.string.cells_region_hint),
                    )

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
                        c.lastSync?.let {
                            Text(
                                stringResource(R.string.sync_last, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ---------------- Learning
                    SectionHeader(stringResource(R.string.sec_learning))
                    SwitchItem(stringResource(R.string.learning_title), stringResource(R.string.learning_summary), c.learning) {
                        mgr.setLearning(it)
                        app.refresh()
                    }
                }

                SettingsGroup(
                    title = stringResource(R.string.settings_group_advanced),
                    summary = stringResource(R.string.settings_group_advanced_summary),
                    icon = Icons.Filled.Settings,
                ) {
                    SettingsHelp(stringResource(R.string.settings_help_advanced))

                    // ---------------- Database
                    SectionHeader(stringResource(R.string.sec_database))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.action_export)) },
                        supportingContent = { Text(stringResource(R.string.export_summary)) },
                        modifier =
                        Modifier.clickable(enabled = !busy) {
                            mgr.exportDatabase()
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text(stringResource(R.string.reset_summary)) },
                        leadingContent = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable(enabled = !busy) { confirmReset = true },
                    )

                    // ---------------- Diagnostics
                    SectionHeader(stringResource(R.string.sec_diagnostics))
                    SwitchItem(stringResource(R.string.simulate_gps_loss), stringResource(R.string.simulate_gps_loss_summary), ui.simulateGpsLoss) { app.setSimulateGpsLoss(it) }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.trip_log)) },
                        supportingContent = { Text(stringResource(R.string.trip_log_summary)) },
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
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.telegram_group)) },
                        modifier = Modifier.clickable { uriHandler.openUri(TELEGRAM_GROUP_URL) },
                    )
                    DonationLink(stringResource(R.string.donate_monobank), BuildConfig.MONOBANK_DONATION_URL) { uriHandler.openUri(it) }
                    DonationLink(stringResource(R.string.donate_privatbank), BuildConfig.PRIVATBANK_DONATION_URL) { uriHandler.openUri(it) }
                }
            }
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

/** Reassures users that the defaults are safe and explains when edits are stored. */
@Composable
private fun SettingsIntro() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_intro_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_intro_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Expandable group that keeps the settings screen short and explains what each group controls. */
@Composable
private fun SettingsGroup(title: String, summary: String, icon: ImageVector, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val toggleDescription = stringResource(if (expanded) R.string.settings_collapse_section else R.string.settings_expand_section)
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = toggleDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(content = content)
            }
        }
    }
}

/** Short plain-language guidance shown at the start of an expanded settings group. */
@Composable
private fun SettingsHelp(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shows whether Android may stop navigation in the background and opens the system fix. */
@Composable
private fun BatteryOptimizationItem(context: Context) {
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unrestricted = isBatteryUnrestricted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${context.packageName}".toUri(),
                )
                context.startActivity(intent)
            }
        },
    )
}

/** Shows a donation destination only when its URL was configured for this build. */
@Composable
private fun DonationLink(title: String, url: String, openUrl: (String) -> Unit) {
    if (url.isNotBlank()) {
        ListItem(
            headlineContent = { Text(title) },
            modifier = Modifier.clickable { openUrl(url) },
        )
    }
}

/** Localised name of a navigation fallback method. */
@Composable
private fun navigationMethodName(method: NavigationMethod): String = stringResource(
    when (method) {
        NavigationMethod.DEAD_RECKONING -> R.string.navigation_method_dr
        NavigationMethod.CELL_TOWERS -> R.string.navigation_method_cells
        NavigationMethod.HYBRID -> R.string.navigation_method_hybrid
    },
)

/** Localised name of a power mode. */
@Composable
private fun powerModeName(m: PowerMode): String = stringResource(
    when (m) {
        PowerMode.AUTO -> R.string.power_auto
        PowerMode.PERFORMANCE -> R.string.power_performance
        PowerMode.BALANCED -> R.string.power_balanced
        PowerMode.SAVER -> R.string.power_saver
    },
)

/** Localised name of a tower database source. */
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

/** Section title with a divider, in the Material 3 settings style. */
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

/** A settings row with a title, optional summary and a switch. */
@Composable
private fun SwitchItem(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** A single-line text field; [secret] hides the text (for keys and tokens). */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    helper: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = helper?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * Settings → Offline map: a downloadable map pack for the whole country, and automatic saving of
 * the map along each planned route (for when there is no pack).
 */
@Composable
private fun OfflineMapSection(app: AppGraph, state: OfflineMapStatus) {
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
        supportingContent = { if (pack == null) Text(stringResource(R.string.offline_map_none_hint)) },
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
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { app.offlineMap.download(url) }, enabled = url.isNotBlank() && state.busy == null) {
            Text(stringResource(R.string.action_download))
        }
        OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = state.busy == null) {
            Text(stringResource(R.string.routing_import))
        }
        if (pack != null) {
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
private fun CarSensorsSection(app: AppGraph) {
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
private fun SearchServerSection(app: AppGraph, onlineAllowed: Boolean) {
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
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun MapStartSection(app: AppGraph, ui: UiState) {
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
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** Categories that reduce a busy diagnostic log to the subsystem the user is investigating. */
private enum class LogFilter(@get:StringRes val label: Int) {
    ALL(R.string.trip_log_filter_all),
    PROBLEMS(R.string.trip_log_filter_problems),
    POSITION(R.string.trip_log_filter_position),
    NAVIGATION(R.string.trip_log_filter_navigation),
}

/** Shows, searches and copies the latest trip-log lines without forcing the user back to the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(app: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lines by remember { mutableStateOf(app.tripLog.recent) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var followNewest by remember { mutableStateOf(true) }
    val list = rememberLazyListState()
    val visibleLines = remember(lines, query, filter) {
        lines.filter { line -> filter.matches(line) && (query.isBlank() || line.contains(query.trim(), ignoreCase = true)) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            lines = app.tripLog.recent
            delay(1000)
        }
    }
    LaunchedEffect(visibleLines.lastOrNull(), followNewest, query, filter) {
        if (followNewest && visibleLines.isNotEmpty()) list.scrollToItem(visibleLines.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trip_log)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } },
                actions = {
                    IconButton(
                        enabled = visibleLines.isNotEmpty(),
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText(resources.getString(R.string.trip_log), visibleLines.joinToString("\n")),
                            )
                            scope.launch {
                                snackbar.showSnackbar(resources.getQuantityString(R.plurals.trip_log_copied, visibleLines.size, visibleLines.size))
                            }
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.trip_log_copy))
                    }
                    IconButton(
                        enabled = visibleLines.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                val file = runCatching { createSharedLogFile(context, visibleLines) }.getOrElse {
                                    snackbar.showSnackbar(resources.getString(R.string.trip_log_share_failed))
                                    return@launch
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(share, resources.getString(R.string.trip_log_share_title)))
                                }.onFailure {
                                    snackbar.showSnackbar(resources.getString(R.string.trip_log_share_failed))
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.trip_log_share))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.trip_log_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { choice ->
                    FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(stringResource(choice.label)) })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    pluralStringResource(R.plurals.trip_log_count, visibleLines.size, visibleLines.size, lines.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = followNewest,
                    onClick = { followNewest = !followNewest },
                    label = { Text(stringResource(R.string.trip_log_follow)) },
                )
            }
            when {
                lines.isEmpty() -> Text(stringResource(R.string.trip_log_empty), modifier = Modifier.padding(16.dp))

                visibleLines.isEmpty() -> Text(stringResource(R.string.trip_log_no_matches), modifier = Modifier.padding(16.dp))

                else -> SelectionContainer {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(visibleLines) { line -> LogLine(line) }
                    }
                }
            }
        }
    }
}

/** A compact timestamp and message row that highlights failures without changing the log text. */
@Composable
private fun LogLine(line: String) {
    val hasPrefix = "] " in line
    val message = if (hasPrefix) line.substringAfter("] ") else line
    val prefix = if (hasPrefix) line.substringBefore("] ") + "]" else ""
    Surface(color = if (isProblemLog(message)) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f) else Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (prefix.isNotEmpty()) {
                Text(prefix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Text(message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    HorizontalDivider()
}

/** True when [line] belongs to this high-level diagnostic category. */
private fun LogFilter.matches(line: String): Boolean {
    val message = line.substringAfter("] ").lowercase(Locale.US)
    return when (this) {
        LogFilter.ALL -> true
        LogFilter.PROBLEMS -> isProblemLog(message)
        LogFilter.POSITION -> listOf("gps", "cell", "signal", "compass", "position", "agps", "net_").any(message::contains)
        LogFilter.NAVIGATION -> listOf("nav", "route", "turn", "trip", "blind", "reroute", "manual_start", "start_accuracy").any(message::contains)
    }
}

/** Detect log keys that normally require attention, for filtering and a subtle warning background. */
private fun isProblemLog(line: String): Boolean = listOf("fail", "error", "reject", "lost", "off_route", "discard", "spoof").any(line.lowercase(Locale.US)::contains)

/** Writes the filtered log snapshot off the main thread so Android can share it as a text attachment. */
private suspend fun createSharedLogFile(context: Context, lines: List<String>): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "log-shares").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    File(directory, "imu-nav-log-$stamp.txt").apply {
        bufferedWriter().use { writer ->
            lines.forEach { line ->
                writer.appendLine(line)
            }
        }
    }
}
