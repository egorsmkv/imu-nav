package org.blinddriver.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.blinddriver.app.AppGraph
import org.blinddriver.app.UiState
import org.blinddriver.app.graph
import org.blinddriver.app.cells.CellSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.blinddriver.app.service.NavService
import org.blinddriver.core.gnss.GpsState
import org.blinddriver.core.gnss.TrustLevel
import org.blinddriver.core.nav.EnglishPhrases
import org.blinddriver.core.nav.PositionSource
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var hasLocation by mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocation) graph.startSensing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        }
        val g = graph
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by g.ui.collectAsStateWithLifecycle()
                // While no navigation runs, keep the UI (position, GNSS diagnostics) fresh.
                LaunchedEffect(Unit) {
                    while (true) {
                        if (!g.engine.state.active) g.refresh()
                        delay(1000)
                    }
                }
                MainScreen(ui, g, hasLocation)
            }
        }
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
private fun MainScreen(ui: UiState, g: AppGraph, hasLocation: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    var showCells by remember { mutableStateOf(false) }
    val pickCellFile = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) g.cells.importFile { context.contentResolver.openInputStream(uri) }
    }
    var mapCenter by remember { mutableStateOf<org.blinddriver.core.geo.GeoPoint?>(null) }
    // Offer manual start when there is no trusted position or only a coarse one (e.g. a single cell tower).
    val pickStart = !ui.guidance.active && (!ui.hasTrustedPosition || (ui.trustedAccuracyM ?: 0.0) > 500.0)
    val nav = ui.guidance

    Box(Modifier.fillMaxSize()) {
        NavMap(
            route = nav.route,
            position = ui.currentPosition,
            bearingDeg = nav.bearingDeg,
            uncertaintyM = nav.uncertaintyM,
            destination = ui.destination ?: nav.destination,
            follow = nav.active,
            onLongPress = { if (!nav.active) g.setDestination(it) },
            modifier = Modifier.fillMaxSize(),
            onCenterChanged = { mapCenter = it },
        )
        if (pickStart) {
            // Crosshair for choosing the start manually when no trusted position exists.
            Text("+", color = Color(0xFF1E3A5F), fontSize = 44.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.safeDrawingPadding().padding(12.dp).fillMaxWidth()) {
            if (!ui.locationEnabled) {
                LocationOffCard(onOpenSettings = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                })
                Spacer(Modifier.padding(4.dp))
            }
            if (nav.active) GuidanceCard(ui) else Hint(ui, hasLocation)
            Spacer(Modifier.padding(4.dp))
            StatusStrip(ui)
            if (nav.blindDeviation) {
                Spacer(Modifier.padding(4.dp))
                DeviationCard(nav.blindDeviationSecLeft, onConfirm = { g.engine.confirmDeviation(android.os.SystemClock.elapsedRealtime()) }, onDismiss = { g.engine.dismissDeviation(android.os.SystemClock.elapsedRealtime()) })
            }
            ui.sensorWarning?.let {
                Spacer(Modifier.padding(4.dp))
                Panel { Text(it, color = Color(0xFFFFCC80), fontSize = 12.sp) }
            }
            ui.error?.let {
                Spacer(Modifier.padding(4.dp))
                Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
            }
            if (showCells) {
                Spacer(Modifier.padding(4.dp))
                CellsPanel(ui, g, onPickFile = { pickCellFile.launch(arrayOf("*/*")) })
            }
            if (showLog) {
                Spacer(Modifier.padding(4.dp))
                LogPanel(ui.log)
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp).fillMaxWidth()
                .background(Color(0xE0101820), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nav.active) {
                Button(onClick = {
                    g.stopNavigation()
                    NavService.stop(context)
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)) { Text("Stop") }
                OutlinedButton(onClick = { g.engine.requestManualReroute() }, enabled = !nav.rerouting) { Text(if (nav.rerouting) "Routing…" else "Reroute") }
            } else {
                Button(
                    onClick = { g.startNavigation { NavService.start(context) } },
                    enabled = ui.destination != null && !ui.planning && hasLocation && (ui.hasTrustedPosition || ui.manualStart != null),
                ) {
                    Text(if (ui.planning) "Planning…" else "Start")
                }
                if (pickStart) {
                    OutlinedButton(onClick = { mapCenter?.let { g.setManualStart(it) } }) {
                        Text(if (ui.manualStart == null) "Start here" else "Move start", softWrap = false)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = ui.simulateGpsLoss, onClick = { g.setSimulateGpsLoss(!ui.simulateGpsLoss) }, label = { Text("No GPS", softWrap = false) })
            FilterChip(selected = showCells, onClick = { showCells = !showCells }, label = { Text("Cells", softWrap = false) })
            FilterChip(selected = showLog, onClick = { showLog = !showLog }, label = { Text("Log", softWrap = false) })
        }
    }
}

@Composable
private fun Hint(ui: UiState, hasLocation: Boolean) {
    Panel {
        Text(
            when {
                !hasLocation -> "Location permission is required."
                !ui.hasTrustedPosition && ui.gpsRejectReasons.isNotEmpty() && ui.manualStart == null ->
                    "GPS looks spoofed (${ui.gpsRejectReasons.joinToString()}). Put the crosshair on your real position and tap “Start here”."
                !ui.hasTrustedPosition && ui.manualStart == null -> "Waiting for a first GPS or network fix… or put the crosshair on your position and tap “Start here”."
                (ui.trustedAccuracyM ?: 0.0) > 500.0 && ui.manualStart == null && ui.destination == null ->
                    "Position from cell towers (±${ui.trustedAccuracyM?.toInt()} m). Long-press to choose a destination; optionally refine your start with the crosshair."
                ui.destination == null -> "Long-press the map to choose a destination."
                else -> "Destination set. Tap Start."
            },
            color = Color.White,
        )
    }
}

@Composable
private fun GuidanceCard(ui: UiState) {
    val nav = ui.guidance
    Panel {
        val step = nav.nextStep
        if (nav.arrived) {
            Text("Arrived", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            return@Panel
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDistance(nav.distToNextM), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(step?.let { EnglishPhrases.maneuver(it.copy(name = ""), null) } ?: "", color = Color(0xFFFFD500), fontSize = 18.sp)
                Text(step?.name.orEmpty(), color = Color.White, fontSize = 15.sp)
            }
        }
        Text(
            "${formatDistance(nav.remainingM)} left · ${(nav.remainingS / 60).toInt()} min · ${nav.speedKmh.toInt()} km/h" +
                (nav.speedLimitKmh?.let { " · limit $it" } ?: ""),
            color = Color(0xFFB0BEC5),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StatusStrip(ui: UiState) {
    val nav = ui.guidance
    val sourceColor = when (nav.source) {
        PositionSource.GPS -> Color(0xFF66BB6A)
        PositionSource.GPS_SUSPECT -> Color(0xFFFFA726)
        PositionSource.NONE -> Color.Gray
        else -> Color(0xFF42A5F5)
    }
    val verdict = ui.lastVerdict
    val verdictColor = when (verdict?.level) {
        TrustLevel.GOOD -> Color(0xFF66BB6A)
        TrustLevel.SUSPECT -> Color(0xFFFFA726)
        TrustLevel.BAD -> Color(0xFFEF5350)
        null -> Color.Gray
    }
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (nav.active) {
                Text(nav.source.label, color = sourceColor, fontWeight = FontWeight.Bold)
                Text("±${nav.uncertaintyM.toInt()} m", color = Color.White)
                if (nav.blindS > 3 && !nav.source.isGps) Text("blind ${nav.blindS}s", color = Color(0xFFB0BEC5))
            }
            Text(
                "GPS ${ui.gpsState.name.lowercase(Locale.US)}",
                color = if (ui.gpsState == GpsState.OK) Color(0xFF66BB6A) else Color(0xFFFFA726),
            )
            Text(verdict?.level?.name ?: "—", color = verdictColor)
            if (ui.jammed) Text("JAM", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
        }
        val gn = ui.gnss
        Text(
            "sats ${gn.satellitesUsed}/${gn.satellitesVisible}  cn0 ${gn.meanCn0Used?.let { "%.0f".format(it) } ?: "—"}±${gn.cn0SpreadUsed?.let { "%.1f".format(it) } ?: "—"}" +
                "  agc ${gn.agcDb?.let { "%.1f".format(it) } ?: "—"}" +
                (verdict?.reasons?.takeIf { it.isNotEmpty() }?.let { "  [${it.joinToString(",")}]" } ?: ""),
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        val c = ui.cells
        Text(
            "cells ${c.located}/${c.seen}" + (c.accuracyM?.let { " ±${it.toInt()} m" } ?: "") +
                "  db ${c.total}" + (if (c.total == 0L) " (empty — tap Cells)" else "") + (c.busy?.let { "  · $it" } ?: ""),
            color = if (c.located > 0) Color(0xFF80CBC4) else Color(0xFFB0BEC5),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun CellsPanel(ui: UiState, g: AppGraph, onPickFile: () -> Unit) {
    val c = ui.cells
    val mgr = g.cells
    var token by remember { mutableStateOf(mgr.savedToken()) }
    var syncUrl by remember { mutableStateOf(c.syncUrl) }
    var syncKey by remember { mutableStateOf(mgr.savedSyncKey()) }
    var autoSync by remember { mutableStateOf(c.autoSync) }
    var mccs by remember { mutableStateOf(c.mccs) }
    val busy = c.busy != null
    val grey = Color(0xFFB0BEC5)
    Surface(color = Color(0xE0101820), shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Offline cell-tower positioning", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                CellSource.entries.joinToString(" · ") { "${it.label} ${c.counts[it] ?: 0}" } +
                    "\nVisible now: ${c.seen}, located: ${c.located}" + (c.accuracyM?.let { " (±${it.toInt()} m)" } ?: ""),
                color = grey,
                fontSize = 13.sp,
            )
            c.busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it, color = Color(0xFFFFD500), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (c.busyCancellable) OutlinedButton(onClick = { mgr.cancelTask() }) { Text("Cancel", softWrap = false) }
                }
            }
            c.message?.let { Text(it, color = Color(0xFF80CBC4), fontSize = 13.sp) }

            SectionTitle("Region")
            androidx.compose.material3.OutlinedTextField(
                value = mccs,
                onValueChange = { mccs = it },
                label = { Text("Country codes (MCC), e.g. 255 = Ukraine") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Mozilla Location Service (public domain, 2024)")
            Text("Streams the 1.5 GB world export and keeps only your region. Use Wi-Fi; resumes after drops.", color = grey, fontSize = 12.sp)
            Button(onClick = { mgr.saveSettings(syncUrl, syncKey, autoSync, mccs); mgr.downloadMozilla() }, enabled = !busy) {
                Text("Download Mozilla data", softWrap = false)
            }

            SectionTitle("OpenCellID")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { mgr.saveSettings(syncUrl, syncKey, autoSync, mccs); onPickFile() }, enabled = !busy) { Text("Import file", softWrap = false) }
                Text(".csv / .csv.gz", color = grey, fontSize = 12.sp)
            }
            androidx.compose.material3.OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("OpenCellID API token") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { mgr.saveSettings(syncUrl, syncKey, autoSync, mccs); mgr.downloadOpenCellId(token) }, enabled = token.isNotBlank() && !busy) {
                Text("Download OpenCellID", softWrap = false)
            }

            SectionTitle("Sharing server")
            Text("Uploads towers this phone located from trusted GPS (tower positions only, never your track) and downloads everyone's merged data.", color = grey, fontSize = 12.sp)
            androidx.compose.material3.OutlinedTextField(
                value = syncUrl,
                onValueChange = { syncUrl = it },
                label = { Text("Server URL, e.g. https://cells.example.org") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.OutlinedTextField(
                value = syncKey,
                onValueChange = { syncKey = it },
                label = { Text("API key (optional)") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (syncUrl.trim().startsWith("http://") && syncKey.isNotBlank()) {
                Text("Warning: http:// sends the API key unencrypted. Use https:// outside your own network.", color = Color(0xFFFF8A80), fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = autoSync, onCheckedChange = { autoSync = it })
                Spacer(Modifier.width(8.dp))
                Text("Sync automatically (every 6 h, after trips)", color = Color.White, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { mgr.saveSettings(syncUrl, syncKey, autoSync, mccs) }) { Text("Save", softWrap = false) }
                Button(onClick = { mgr.saveSettings(syncUrl, syncKey, autoSync, mccs); mgr.sync() }, enabled = syncUrl.isNotBlank() && !busy) {
                    Text("Sync now", softWrap = false)
                }
            }
            c.lastSync?.let { Text("Last: $it", color = grey, fontSize = 12.sp) }

            SectionTitle("Export")
            Text("Write all towers (deduplicated) to Android/data/org.blinddriver.app/files/cells-export.csv.gz", color = grey, fontSize = 12.sp)
            OutlinedButton(onClick = { mgr.exportDatabase() }, enabled = !busy) { Text("Export database", softWrap = false) }

            SectionTitle("Learning")
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = c.learning, onCheckedChange = { mgr.setLearning(it) })
                Spacer(Modifier.width(8.dp))
                Text("Learn tower positions from trusted GPS", color = Color.White, fontSize = 13.sp)
            }
            Text("Cell data: OpenCellID contributors (CC BY-SA 4.0); Mozilla Location Service (public domain).", color = Color(0xFF78909C), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color(0xFFFFD500), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun LocationOffCard(onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C))) {
        Column(Modifier.padding(12.dp)) {
            Text("Location is turned off on this phone. No GPS or network fixes can arrive.", color = Color.White)
            Button(onClick = onOpenSettings) { Text("Open location settings") }
        }
    }
}

@Composable
private fun DeviationCard(secondsLeft: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF6D4C41))) {
        Column(Modifier.padding(12.dp)) {
            Text("Looks like you left the route. Rerouting in $secondsLeft s", color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("Reroute now") }
                OutlinedButton(onClick = onDismiss) { Text("I'm on route") }
            }
        }
    }
}

@Composable
private fun LogPanel(lines: List<String>) {
    Panel {
        lines.takeLast(14).forEach {
            Text(it.substringAfter("] "), color = Color(0xFFCFD8DC), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(color = Color(0xE0101820), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.background(Color.Transparent).padding(12.dp)) { content() }
    }
}

private fun formatDistance(m: Double): String =
    if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000) else "${((m / 10).toInt() * 10)} m"
