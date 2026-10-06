package org.imunav.app.ui

import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.AppGraph
import org.imunav.app.R
import org.imunav.app.maps.mapStyle
import org.imunav.app.trips.TripSummary
import org.imunav.app.trips.TripTracks
import org.imunav.app.trips.extractTracks
import org.imunav.app.trips.tripShareIntent
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Date
import android.graphics.Color as AColor

private val GpsGreen = Color(0xFF1E8E3E)
private val EngineRed = Color(0xFFD93025)
private val MatchedBlue = Color(0xFF1A73E8)

/** List of saved trips with totals at the top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(app: AppGraph, onBack: () -> Unit, onOpen: (TripSummary) -> Unit) {
    val res = LocalResources.current
    val trips by app.trips.history.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        if (trips.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val t = app.trips.totals()
                Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(formatDistance(res, t.drivenM), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            pluralStringResource(
                                R.plurals.history_totals,
                                t.trips,
                                t.trips,
                                formatDuration(res, t.durationS),
                                stringResource(R.string.history_blind_total, formatDistance(res, t.blindM)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            items(trips, key = { it.id }) { t -> TripRow(t) { onOpen(t) } }
        }
    }
}

/** One trip in the history list: date, distance, duration and time without GPS. */
@Composable
private fun TripRow(t: TripSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val date = DateFormat.getMediumDateFormat(context).format(Date(t.startWallMs)) + " " +
        DateFormat.getTimeFormat(context).format(Date(t.startWallMs))
    val blindPct = if (t.durationS > 0) (t.blindS / t.durationS * 100).toInt().coerceIn(0, 100) else 0
    ListItem(
        headlineContent = { Text(date) },
        supportingContent = { Text(stringResource(R.string.trip_line, formatDistance(res, t.drivenM), formatDuration(res, t.durationS), blindPct)) },
        leadingContent = {
            Icon(
                when {
                    t.arrived -> Icons.Filled.CheckCircle
                    t.mode == TravelMode.FOOT -> Icons.AutoMirrored.Filled.DirectionsWalk
                    else -> Icons.Filled.Timeline
                },
                contentDescription = stringResource(if (t.arrived) R.string.trip_arrived else R.string.trip_not_arrived),
                tint = if (t.arrived) GpsGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** One trip: recorded tracks, statistics, map matching, sharing the recording and deletion. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(app: AppGraph, trip: TripSummary, onBack: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<TripTracks?>(null) }
    var matched by remember { mutableStateOf<List<GeoPoint>?>(null) }
    var matchedLength by remember { mutableStateOf(trip.matchedLengthM) }
    var matching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(trip.id) {
        tracks = withContext(Dispatchers.IO) {
            runCatching { extractTracks(app.trips.recordingFile(trip), app.serviceArea) }.getOrNull() ?: TripTracks(emptyList(), emptyList(), 0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        DateFormat.getMediumDateFormat(context).format(Date(trip.startWallMs)) + " " +
                            DateFormat.getTimeFormat(context).format(Date(trip.startWallMs)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                actions = {
                    IconButton(enabled = !sharing, onClick = {
                        sharing = true
                        scope.launch {
                            try {
                                runCatching {
                                    val intent = tripShareIntent(context, app.trips.recordingFile(trip))
                                    context.startActivity(Intent.createChooser(intent, res.getString(R.string.trip_share)))
                                }.onFailure { error ->
                                    if (error is CancellationException) throw error
                                    app.tripLog.write("trip_share_failed type=${error.javaClass.simpleName}")
                                    snackbar.showSnackbar(res.getString(R.string.trip_share_failed))
                                }
                            } finally {
                                sharing = false
                            }
                        }
                    }) {
                        if (sharing) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Filled.Share, stringResource(R.string.trip_share))
                        }
                    }
                    IconButton(enabled = !sharing, onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val t = tracks
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                if (t == null) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(Modifier.width(160.dp))
                        Text(stringResource(R.string.trip_loading), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    TrackMap(t.gps, t.engine, matched.orEmpty(), Modifier.fillMaxSize(), offlineStyle = app.offlineMap::styleJson)
                }
            }
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendDot(GpsGreen, stringResource(R.string.trip_legend_gps))
                LegendDot(EngineRed, stringResource(R.string.trip_legend_engine))
                if (matched != null) LegendDot(MatchedBlue, stringResource(R.string.trip_legend_matched))
            }
            TripArchiveControls(app, trip)
            Stat(stringResource(R.string.trip_stat_distance), formatDistance(res, trip.drivenM))
            matchedLength?.let { Stat(stringResource(R.string.trip_stat_matched), formatDistance(res, it)) }
            Stat(stringResource(R.string.trip_stat_duration), formatDuration(res, trip.durationS))
            Stat(stringResource(R.string.trip_stat_moving), formatDuration(res, trip.movingS))
            Stat(stringResource(R.string.trip_stat_avg), stringResource(R.string.unit_kmh, trip.avgMovingKmh.toInt()))
            Stat(stringResource(R.string.trip_stat_blind), stringResource(R.string.trip_stat_blind_value, formatDuration(res, trip.blindS), formatDistance(res, trip.blindM)))
            Stat(stringResource(R.string.trip_stat_uncertainty), formatAccuracy(res, trip.maxUncertaintyM))
            Stat(stringResource(R.string.trip_stat_route), formatDistance(res, trip.routeLengthM))
            Stat(stringResource(R.string.trip_stat_reroutes), trip.reroutes.toString())
            Stat(
                stringResource(if (trip.arrived) R.string.trip_arrived else R.string.trip_not_arrived),
                "",
            )
            Column(Modifier.padding(16.dp)) {
                if (matching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.map_matching), style = MaterialTheme.typography.bodySmall)
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = {
                        val gps = tracks?.gps.orEmpty()
                        if (gps.size < 2) {
                            note = res.getString(R.string.map_match_no_gps)
                            return@Button
                        }
                        matching = true
                        note = null
                        scope.launch {
                            val result = runCatching { app.offlineRouting.mapMatch(gps, trip.mode) }
                            matching = false
                            result.onSuccess { m ->
                                if (m == null) {
                                    note = res.getString(R.string.map_match_no_pack)
                                } else {
                                    matched = m.geometry
                                    matchedLength = m.lengthM
                                    app.trips.setMatchedLength(trip, m.lengthM)
                                }
                            }.onFailure { note = res.getString(R.string.task_failed, it.message ?: it.javaClass.simpleName) }
                        }
                    },
                    enabled = !matching && tracks != null,
                ) { Text(stringResource(R.string.action_map_match)) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.trip_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.trips.delete(trip)
                    onBack()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Values wrap below their labels when either translation or system text needs more room. */
@Composable
private fun Stat(label: String, value: String) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, modifier = Modifier.padding(end = 16.dp))
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** A coloured line sample with a label, for the map legend. */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 18.dp, height = 4.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Read-only map showing a trip's tracks, zoomed to fit them. */
@Composable
private fun TrackMap(gps: List<GeoPoint>, engine: List<GeoPoint>, matched: List<GeoPoint>, modifier: Modifier, offlineStyle: (dark: Boolean) -> String? = { null }) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = isSystemInDarkTheme()
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var style by remember { mutableStateOf<Style?>(null) }
    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.setStyle(mapStyle(offlineStyle(dark), dark)) { s ->
                for ((id, color, width) in listOf(Triple("matched", "#1A73E8", 7f), Triple("gps", "#1E8E3E", 4f), Triple("engine", "#D93025", 3f))) {
                    s.addSource(GeoJsonSource(id))
                    s.addLayer(
                        LineLayer("$id-line", id).withProperties(
                            PropertyFactory.lineColor(color.toColorInt()),
                            PropertyFactory.lineWidth(width),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                            PropertyFactory.lineOpacity(if (id == "matched") 0.6f else 0.9f),
                        ).also { if (id == "engine") it.setProperties(PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))) },
                    )
                }
                mapRef = m
                style = s
            }
        }
    }

    LaunchedEffect(style, gps, engine, matched) {
        val s = style ?: return@LaunchedEffect

        /** Replace the line drawn for source [id]. */
        fun set(id: String, pts: List<GeoPoint>) {
            val src = s.getSourceAs<GeoJsonSource>(id) ?: return
            if (pts.size < 2) {
                src.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
            } else {
                src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })))
            }
        }
        set("gps", gps)
        set("engine", engine)
        set("matched", matched)
        // Fit the camera to whatever tracks we have.
        val all = gps + engine + matched
        val m = mapRef ?: return@LaunchedEffect
        val lats = all.map { it.lat }
        val lons = all.map { it.lon }
        if (all.isNotEmpty() && (lats.max() - lats.min() > 1e-4 || lons.max() - lons.min() > 1e-4)) {
            val b = LatLngBounds.Builder().apply { all.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 60))
        } else if (all.isNotEmpty()) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].lat, all[0].lon), 15.0))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
