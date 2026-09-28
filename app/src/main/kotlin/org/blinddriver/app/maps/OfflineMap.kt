package org.blinddriver.app.maps

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blinddriver.app.R
import org.blinddriver.core.cells.ResumableHttpInputStream
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.RouteCorridor
import org.json.JSONException
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** The online map styles (OpenFreeMap). Used when no offline map pack is installed. */
object MapStyles {
    const val LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    const val DARK = "https://tiles.openfreemap.org/styles/dark"

    fun online(dark: Boolean) = if (dark) DARK else LIGHT
}

/** The style to load: the offline pack's style if given, else the online OpenFreeMap style. */
fun mapStyle(offlineStyleJson: String?, dark: Boolean): Style.Builder =
    if (offlineStyleJson != null) Style.Builder().fromJson(offlineStyleJson) else Style.Builder().fromUri(MapStyles.online(dark))

/** Description of an installed map display pack (its `pack.json`, written by `tools/make_map_pack.py`). */
data class MapPackInfo(val name: String, val builtAt: String, val sizeBytes: Long, val maxZoom: Int) {
    companion object {
        fun parse(json: String): MapPackInfo? = try {
            val o = JSONObject(json)
            if (o.optString("kind") != "map") null else MapPackInfo(o.getString("name"), o.optString("builtAt"), o.optLong("sizeBytes"), o.optInt("maxzoom", 14))
        } catch (_: JSONException) {
            null
        }
    }
}

/** State of offline map display, for the Settings screen and the map. */
data class OfflineMapStatus(
    /** The installed pack, if any. */
    val pack: MapPackInfo? = null,
    /** Draw the map from the pack (instead of the online style) when one is installed. */
    val useOffline: Boolean = true,
    /** Save the map along each planned route while online. */
    val corridor: Boolean = true,
    /** Progress / result of the latest route download, for Settings. */
    val corridorText: String? = null,
    val packUrl: String = "",
    /** Progress of a running pack install, null when idle. */
    val busy: String? = null,
    /** Result of the last install / removal. */
    val message: String? = null,
) {
    /** The map is currently drawn from the offline pack. */
    val offlineInUse: Boolean get() = pack != null && useOffline
}

/**
 * Makes the drawn map (streets, labels) available without internet, in two ways:
 *
 * 1. **Map pack** — a zip with vector tiles for a whole country plus styles, icons and fonts
 *    (see `tools/make_map_pack.py`). Once installed, [styleJson] returns a style that reads
 *    everything from the phone's storage.
 * 2. **Route corridor** — when navigation starts without a pack, [saveCorridor] asks MapLibre to
 *    download the online map ~1 km either side of the route (zoom 10–14) into its offline database,
 *    so the map keeps drawing if the signal is lost on the way. The last few corridors are kept.
 */
class OfflineMap(private val context: Context, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("offline_map", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "map")
    private val current = File(root, "current")
    private var task: Job? = null

    /** The pack's two styles with real paths filled in, read once when the pack loads. */
    @Volatile private var styles: Pair<String, String>? = null

    private val _status = MutableStateFlow(
        OfflineMapStatus(
            useOffline = prefs.getBoolean(KEY_USE_OFFLINE, true),
            corridor = prefs.getBoolean(KEY_CORRIDOR, true),
            packUrl = prefs.getString(KEY_URL, "").orEmpty(),
        ),
    )
    val status: StateFlow<OfflineMapStatus> = _status.asStateFlow()

    init {
        scope.launch { withContext(Dispatchers.IO) { loadInfo() } }
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Read the installed pack's description and styles (IO thread). */
    private fun loadInfo() {
        val info = File(current, PACK_JSON).takeIf { it.exists() }?.let { MapPackInfo.parse(it.readText()) }
        styles = info?.let {
            val packUri = "file://${current.absolutePath}"
            fun style(name: String) = File(current, name).readText().replace("{PACK_URI}", packUri)
            style("style-light.json") to style("style-dark.json")
        }
        _status.update { it.copy(pack = info) }
    }

    /**
     * The style to draw with when the offline pack is in use: the pack's style with its
     * `{PACK_URI}` placeholder replaced by the pack folder. Null = use the online style.
     */
    fun styleJson(dark: Boolean): String? {
        if (!_status.value.offlineInUse) return null
        val (light, darkStyle) = styles ?: return null
        return if (dark) darkStyle else light
    }

    fun setUseOffline(on: Boolean) {
        prefs.edit { putBoolean(KEY_USE_OFFLINE, on) }
        _status.update { it.copy(useOffline = on) }
    }

    fun setCorridorEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_CORRIDOR, on) }
        _status.update { it.copy(corridor = on) }
    }

    fun setPackUrl(url: String) {
        prefs.edit { putString(KEY_URL, url.trim()) }
        _status.update { it.copy(packUrl = url.trim()) }
    }

    // ------------------------------------------------------------------ map pack install

    /** Download and install a pack .zip from [url] (unpacked while streaming, resumes after drops). */
    fun download(url: String) {
        setPackUrl(url)
        runTask(str(R.string.task_connecting)) {
            withContext(Dispatchers.IO) {
                var lastReport = 0L
                ResumableHttpInputStream(url.trim(), onProgress = { bytes, total ->
                    if (bytes - lastReport >= PROGRESS_STEP_BYTES) {
                        lastReport = bytes
                        val percent = if (total > 0) (bytes * 100 / total).toInt() else 0
                        progress(str(R.string.routing_downloading, (bytes / MB).toInt(), (total / MB).toInt().coerceAtLeast(0), percent))
                    }
                }).use { install(it) }
            }
        }
    }

    /** Install a pack .zip the user picked. */
    fun importZip(open: () -> InputStream?) = runTask(str(R.string.routing_installing, 0)) {
        withContext(Dispatchers.IO) { (open() ?: throw IOException("cannot open file")).use { install(it) } }
    }

    /** Delete the installed pack (the online map is used again). */
    fun remove() = runTask(str(R.string.routing_removing)) {
        withContext(Dispatchers.IO) {
            current.deleteRecursively()
            loadInfo()
        }
        str(R.string.offline_map_removed)
    }

    fun cancel() {
        task?.cancel()
    }

    /**
     * Unpack into a staging folder, check it really is a map pack, then swap it in. Paths inside
     * the zip are kept (sprites/, fonts/…) but may not escape the folder ("zip slip").
     */
    private suspend fun install(input: InputStream): String {
        val job = coroutineContext
        val staging = File(root, "staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val stagingPath = staging.canonicalPath + File.separator
        var bytes = 0L
        var lastReport = 0L
        ZipInputStream(input.buffered(BUFFER_BYTES)).use { zip ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!job.isActive) throw InterruptedException()
                val target = File(staging, entry.name)
                if (!target.canonicalPath.startsWith(stagingPath)) throw IOException("bad path in pack: ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        bytes += n
                        if (bytes - lastReport >= PROGRESS_STEP_BYTES) {
                            lastReport = bytes
                            progress(str(R.string.routing_installing, (bytes / MB).toInt()))
                        }
                    }
                }
            }
        }
        val info = File(staging, PACK_JSON).takeIf { it.exists() }?.let { MapPackInfo.parse(it.readText()) }
        val complete = REQUIRED_FILES.all { File(staging, it).exists() }
        if (info == null || !complete) {
            staging.deleteRecursively()
            throw IOException(str(R.string.offline_map_not_a_pack))
        }
        current.deleteRecursively()
        if (!staging.renameTo(current)) throw IOException("cannot install map pack")
        loadInfo()
        log("offline_map_installed ${info.name} ${bytes / MB}MB")
        return str(R.string.offline_map_installed, info.name)
    }

    private fun progress(text: String) {
        _status.update { it.copy(busy = text) }
    }

    /** One install / download at a time; the returned text is shown when it finishes. */
    private fun runTask(start: String, block: suspend () -> String) {
        if (task?.isActive == true) return
        task = scope.launch {
            _status.update { it.copy(busy = start, message = null) }
            val message = try {
                block()
            } catch (_: kotlinx.coroutines.CancellationException) {
                str(R.string.task_cancelled)
            } catch (_: InterruptedException) {
                str(R.string.task_cancelled)
            } catch (e: IOException) {
                log("offline_map_task_failed ${e.message}")
                str(R.string.task_failed, e.message ?: e.javaClass.simpleName)
            }
            withContext(NonCancellable) {
                File(root, "staging").takeIf { it.exists() }?.deleteRecursively()
                _status.update { it.copy(busy = null, message = message) }
            }
        }
    }

    // ------------------------------------------------------------------ route corridor

    /**
     * Download the online map along [route] (main thread; MapLibre does the work in the
     * background). Skipped when the offline pack draws the map or the setting is off.
     */
    fun saveCorridor(route: Route, dark: Boolean) {
        val state = _status.value
        if (!state.corridor || state.offlineInUse || route.geometry.size < 2) return
        val boxes = RouteCorridor.boxes(route, radiusM = CORRIDOR_RADIUS_M)
        val tiles = RouteCorridor.tileCount(boxes, CORRIDOR_MIN_ZOOM, CORRIDOR_MAX_ZOOM)
        if (tiles > MAX_CORRIDOR_TILES) {
            log("corridor_skipped tiles=$tiles")
            _status.update { it.copy(corridorText = str(R.string.corridor_too_long)) }
            return
        }
        val geometry = MultiPolygon.fromLngLats(
            boxes.map { b ->
                listOf(
                    listOf(
                        Point.fromLngLat(b.minLon, b.minLat),
                        Point.fromLngLat(b.maxLon, b.minLat),
                        Point.fromLngLat(b.maxLon, b.maxLat),
                        Point.fromLngLat(b.minLon, b.maxLat),
                        Point.fromLngLat(b.minLon, b.minLat),
                    ),
                )
            },
        )
        val definition = OfflineGeometryRegionDefinition(
            MapStyles.online(dark),
            geometry,
            CORRIDOR_MIN_ZOOM.toDouble(),
            CORRIDOR_MAX_ZOOM.toDouble(),
            context.resources.displayMetrics.density,
            false,
        )
        val metadata = JSONObject().put("kind", "corridor").put("created", System.currentTimeMillis()).put("tiles", tiles).toString().toByteArray()
        val manager = OfflineManager.getInstance(context)
        manager.setOfflineMapboxTileCountLimit(MAX_CORRIDOR_TILES * 2)
        log("corridor_start tiles=$tiles length_km=${(route.length / 1000).toInt()}")
        deleteOldCorridors(manager) {
            manager.createOfflineRegion(
                definition,
                metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) = startDownload(offlineRegion)

                    override fun onError(error: String) {
                        log("corridor_error $error")
                    }
                },
            )
        }
    }

    private fun startDownload(region: OfflineRegion) {
        region.setObserver(
            object : OfflineRegion.OfflineRegionObserver {
                private var lastPercent = -1

                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val required = status.requiredResourceCount.coerceAtLeast(1)
                    val percent = (status.completedResourceCount * 100 / required).toInt()
                    if (status.isComplete) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        val mb = status.completedResourceSize / MB
                        log("corridor_done resources=${status.completedResourceCount} MB=$mb")
                        _status.update { it.copy(corridorText = str(R.string.corridor_done, mb.toInt())) }
                    } else if (percent != lastPercent) {
                        lastPercent = percent
                        _status.update { it.copy(corridorText = str(R.string.corridor_progress, percent)) }
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    // Usually "no network": MapLibre retries on its own when connectivity returns.
                    log("corridor_error ${error.reason} ${error.message}")
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    log("corridor_limit $limit")
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                }
            },
        )
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /** Keep only the newest [KEEP_CORRIDORS] − 1 corridors, then run [then]. */
    private fun deleteOldCorridors(manager: OfflineManager, then: () -> Unit) {
        manager.listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val corridors = offlineRegions.orEmpty()
                        .mapNotNull { region -> corridorCreated(region)?.let { region to it } }
                        .sortedByDescending { it.second }
                    corridors.drop(KEEP_CORRIDORS - 1).forEach { (region, _) ->
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        region.delete(
                            object : OfflineRegion.OfflineRegionDeleteCallback {
                                override fun onDelete() = Unit

                                override fun onError(error: String) {
                                    log("corridor_delete_error $error")
                                }
                            },
                        )
                    }
                    then()
                }

                override fun onError(error: String) {
                    log("corridor_list_error $error")
                    then()
                }
            },
        )
    }

    /** Creation time of a corridor region we made, or null for anything else. */
    private fun corridorCreated(region: OfflineRegion): Long? = try {
        JSONObject(region.metadata.decodeToString()).takeIf { it.optString("kind") == "corridor" }?.optLong("created")
    } catch (_: JSONException) {
        null
    }

    private companion object {
        const val PACK_JSON = "pack.json"
        val REQUIRED_FILES = listOf("tiles.pmtiles", "style-light.json", "style-dark.json", PACK_JSON)
        const val KEY_USE_OFFLINE = "use_offline"
        const val KEY_CORRIDOR = "corridor"
        const val KEY_URL = "pack_url"
        const val MB = 1_048_576L
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_STEP_BYTES = 4L * MB

        /** Map this far either side of the route. */
        const val CORRIDOR_RADIUS_M = 1_000.0
        const val CORRIDOR_MIN_ZOOM = 10

        /** OpenMapTiles vector tiles go up to zoom 14; closer zooms are drawn from them. */
        const val CORRIDOR_MAX_ZOOM = 14

        /** Roughly a 2,000 km drive; longer routes are skipped. */
        const val MAX_CORRIDOR_TILES = 5_000L

        /** Corridors kept on the phone (the newest ones). */
        const val KEEP_CORRIDORS = 3
    }
}
