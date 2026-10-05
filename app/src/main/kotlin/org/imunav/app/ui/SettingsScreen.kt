package org.imunav.app.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.imunav.app.AppGraph
import org.imunav.app.BuildConfig
import org.imunav.app.R
import org.imunav.app.UiState
import org.imunav.app.cells.CellSource
import org.imunav.core.cells.Radio
import java.text.NumberFormat

private val RADIO_CHOICES = listOf(Radio.GSM to "2G", Radio.UMTS to "3G", Radio.LTE to "4G", Radio.NR to "5G")

/**
 * Settings: language, map start, battery, offline routing, cell towers, sharing server and diagnostics.
 * Text fields are saved when leaving the screen (see `save()`).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(ui: UiState, app: AppGraph, onBack: () -> Unit, onOpenLog: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val telegramGroupUrl = stringResource(R.string.telegram_group_url)
    val c = ui.cells
    val mgr = app.cells
    val busy = c.busy != null
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    var mccs by remember { mutableStateOf(c.mccs) }
    LaunchedEffect(c.mccs) { if (mccs.isBlank()) mccs = c.mccs }
    var token by remember { mutableStateOf(mgr.savedToken()) }
    var syncUrl by remember { mutableStateOf(c.syncUrl) }
    var accountEmail by remember { mutableStateOf(c.accountEmail.orEmpty()) }
    var accountPassword by remember { mutableStateOf("") }
    var autoSync by remember { mutableStateOf(c.autoSync) }
    var confirmReset by remember { mutableStateOf(false) }
    val accountUrlAllowed = syncUrl.trim().startsWith("https://") || syncUrl.trim().startsWith("http://localhost:") ||
        syncUrl.trim().startsWith("http://127.0.0.1:") || syncUrl.trim().startsWith("http://10.0.2.2:")

    /** Store the typed server settings. */
    fun save() = mgr.saveSettings(syncUrl, autoSync, mccs)

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
            TopAppBar(title = {
                Text(stringResource(R.string.settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }, navigationIcon = navigationIcon, scrollBehavior = scroll)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
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

                EverydaySettings(app, ui, context, ::save)

                SettingsGroup(
                    title = stringResource(R.string.proxy_title),
                    summary = stringResource(R.string.proxy_summary),
                    icon = Icons.Filled.Settings,
                ) {
                    ProxySettingsSection(app.proxySettings)
                }

                MapsSettings(app, routing, offlineMap, packUrl, onPackUrlChange = { packUrl = it }, onPickPack = {
                    pickPack.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                })

                SettingsGroup(
                    title = stringResource(R.string.settings_group_cells),
                    summary = stringResource(R.string.settings_group_cells_summary),
                    icon = Icons.Filled.CellTower,
                ) {
                    SettingsHelp(stringResource(R.string.settings_help_cells))

                    // ---------------- Cell towers
                    SectionHeader(stringResource(R.string.sec_cells))
                    CellHistorySection(app.cells.usageHistory)
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
                        "310, 311",
                        keyboard = KeyboardType.Number,
                        helper = stringResource(R.string.cells_region_hint),
                    )
                    if (mccs.isBlank()) Text(stringResource(R.string.cells_region_required), color = MaterialTheme.colorScheme.error)

                    // ---------------- Data sources
                    SectionHeader(stringResource(R.string.sec_sources))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.mozilla_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.mozilla_summary))
                                TextButton(onClick = {
                                    save()
                                    mgr.downloadMozilla()
                                }, enabled = !busy) { Text(stringResource(R.string.action_download)) }
                            }
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.ocid_title)) },
                        supportingContent = { Text(stringResource(R.string.ocid_summary)) },
                    )
                    Field(token, { token = it }, stringResource(R.string.ocid_token), null, secret = true)
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (syncUrl.trim().startsWith("http://") && !accountUrlAllowed) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.sync_http_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (c.accountEmail == null) {
                        Field(accountEmail, { accountEmail = it }, stringResource(R.string.auth_email), null, keyboard = KeyboardType.Email)
                        Field(accountPassword, { accountPassword = it }, stringResource(R.string.auth_password), null, secret = true)
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                save()
                                mgr.authenticate(accountEmail, accountPassword, register = false)
                                accountPassword = ""
                            }, enabled = accountUrlAllowed && accountEmail.isNotBlank() && accountPassword.isNotBlank() && !busy) {
                                Text(stringResource(R.string.auth_sign_in))
                            }
                            OutlinedButton(onClick = {
                                save()
                                mgr.authenticate(accountEmail, accountPassword, register = true)
                                accountPassword = ""
                            }, enabled = accountUrlAllowed && accountEmail.isNotBlank() && accountPassword.isNotBlank() && !busy) {
                                Text(stringResource(R.string.auth_register))
                            }
                            TextButton(onClick = {
                                save()
                                mgr.requestPasswordReset(accountEmail)
                            }, enabled = accountUrlAllowed && accountEmail.isNotBlank() && !busy) {
                                Text(stringResource(R.string.auth_forgot_password))
                            }
                        }
                    } else {
                        ListItem(headlineContent = { Text(stringResource(R.string.auth_account, c.accountEmail)) })
                        TextButton(onClick = { mgr.signOut() }, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.auth_sign_out))
                        }
                    }
                    SwitchItem(stringResource(R.string.sync_auto), stringResource(R.string.sync_auto_summary), autoSync) {
                        autoSync = it
                        save()
                    }
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            save()
                            mgr.sync()
                        }, enabled = syncUrl.isNotBlank() && !busy) { Text(stringResource(R.string.action_sync_now)) }
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
                }

                SettingsGroup(
                    title = stringResource(R.string.settings_group_links),
                    summary = stringResource(R.string.settings_group_links_summary),
                    icon = Icons.Filled.Info,
                    initiallyExpanded = true,
                ) {
                    SettingsHelp(stringResource(R.string.offline_packs_telegram_summary))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.telegram_group)) },
                        supportingContent = { Text(telegramGroupUrl) },
                        modifier = Modifier.clickable { uriHandler.openUri(telegramGroupUrl) },
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
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
