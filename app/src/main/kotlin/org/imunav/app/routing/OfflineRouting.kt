package org.imunav.app.routing

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.app.R
import org.imunav.app.packs.PackTasks
import org.imunav.app.search.AndroidSearchDb
import org.imunav.app.setup.Preparation
import org.imunav.app.setup.bundledRoutingPreparation
import org.imunav.core.cells.ResumableHttpInputStream
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.SearchResult
import org.imunav.core.util.PackFiles
import org.imunav.core.util.ResourceAccess
import org.imunav.routing.GraphCoverage
import org.imunav.routing.MatchedTrack
import org.imunav.routing.OfflineGraph
import org.imunav.routing.PackInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** State of the offline routing pack, for the Settings screen. */
data class OfflineRoutingStatus(
    val preparation: Preparation = Preparation.CHECKING,
    /** The installed pack, if any. */
    val pack: PackInfo? = null,
    /** Pack shipped inside the APK (assets/routing), if any. */
    val bundled: PackInfo? = null,
    /** The installed pack was loaded successfully and can route. */
    val loaded: Boolean = false,
    val searchAvailable: Boolean = false,
    val coverage: GraphCoverage? = null,
    val modes: Set<TravelMode> = emptySet(),
    /** The loaded pack has walking data (packs built before walking support do not). */
    val walking: Boolean = false,
    /** Use OSRM online when the offline pack cannot answer. */
    val allowOnline: Boolean = true,
    /** Last URL entered for downloading a pack. */
    val packUrl: String = "",
    /** Progress text of a running install / download, null when idle. */
    val busy: String? = null,
    val message: String? = null,
)

/**
 * Owns the offline routing pack (a GraphHopper graph built with `:routing:run`): import from a
 * .zip, streamed download, load (memory-mapped) and routing.
 */
class OfflineRouting(private val context: Context, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("routing", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "routing")
    private val current = File(root, "current")
    private var graph: OfflineGraph? = null
    private val tasks = PackTasks(scope)
    private val resources = ResourceAccess()

    /** Address search index shipped inside the pack (`search.db`), if present. */
    private var searchDb: AndroidSearchDb? = null

    private val _status = MutableStateFlow(
        OfflineRoutingStatus(allowOnline = prefs.getBoolean("allow_online", true), packUrl = prefs.getString("pack_url", "").orEmpty()),
    )
    val status: StateFlow<OfflineRoutingStatus> = _status.asStateFlow()

    /** Metadata of the pack bundled in the APK (assets/routing/pack.json next to pack.zip). */
    private var bundledInfo: PackInfo? = null

    init {
        scope.launch { prepareBundled() }
    }

    /** Discover assets and load existing data off the main thread before deciding to extract. */
    private suspend fun prepareBundled() {
        runCatching {
            bundledInfo = withContext(Dispatchers.IO) {
                if (context.assets.list("routing")?.contains("pack.zip") == true) {
                    context.assets.open(BUNDLED_INFO).use { PackInfo.parse(it.readBytes().decodeToString()) }
                        ?: throw IOException(str(R.string.routing_not_a_pack))
                } else {
                    null
                }
            }
            _status.update { it.copy(bundled = bundledInfo) }
            withContext(Dispatchers.IO) { load() }
            installBundledIfNeeded()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log("offline_routing_load_failed ${error.message}")
            // A failed load must remain visible. Expanding a large bundled archive is only allowed
            // after the user explicitly chooses it from onboarding or Settings.
            _status.update { it.copy(preparation = Preparation.FAILED, message = str(R.string.task_failed, error.message.orEmpty())) }
        }
    }

    /** Opt in to the bundled pack, or retry its discovery and install, without overlapping a task. */
    fun retryBundled() {
        if (tasks.running || _status.value.preparation in setOf(Preparation.CHECKING, Preparation.PREPARING)) return
        prefs.edit { putBoolean("bundled_enabled", true) }
        _status.update { it.copy(preparation = Preparation.CHECKING) }
        scope.launch { prepareBundled() }
    }

    /**
     * After explicit opt-in, unpack the bundled pack into app storage and keep it current.
     * Skipped when a pack at least as new is installed, or the user has not opted in.
     */
    private fun installBundledIfNeeded() {
        val bundled = bundledInfo
        if (bundled == null) {
            _status.update {
                it.copy(
                    preparation = when {
                        it.loaded -> Preparation.READY
                        it.pack != null -> Preparation.FAILED
                        else -> Preparation.UNAVAILABLE
                    },
                    message = if (it.pack != null && !it.loaded) str(R.string.routing_load_failed) else it.message,
                )
            }
            return
        }
        val installed = _status.value.pack
        if (installed != null && installed.builtAt > bundled.builtAt && !_status.value.loaded) {
            // A load failure must not silently downgrade a user's newer imported pack.
            _status.update { it.copy(preparation = Preparation.FAILED, message = str(R.string.routing_load_failed)) }
            return
        }
        if (installed != null && installed.builtAt >= bundled.builtAt && _status.value.loaded) {
            _status.update { it.copy(preparation = Preparation.READY) }
            return
        }
        val preparation = bundledRoutingPreparation(
            enabled = prefs.getBoolean("bundled_enabled", false),
            loaded = _status.value.loaded,
            removed = prefs.getString("bundled_declined", null) == bundled.builtAt,
        )
        if (preparation != Preparation.CHECKING) {
            _status.update { it.copy(preparation = preparation) }
            return
        }
        installBundled()
    }

    /** Unpack the pack shipped with the app (also offered in Settings after it was removed). */
    fun installBundled() {
        val bundled = bundledInfo ?: return
        if (tasks.running) return
        prefs.edit {
            putBoolean("bundled_enabled", true)
            remove("bundled_declined")
        }
        _status.update { it.copy(preparation = Preparation.PREPARING) }
        runTask(str(R.string.routing_preparing_builtin, 0, (bundled.sizeBytes / 1_048_576).toInt()), bundled = true) {
            withContext(Dispatchers.IO) {
                context.assets.open(BUNDLED_ZIP).use { install(it, totalBytes = bundled.sizeBytes) }
            }
        }
    }

    /** A string resource in the current app language. */
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Open the installed pack (graph + search index); runs in the background. */
    private fun load(strict: Boolean = false) = resources.write {
        closeResources()
        searchDb = File(current, "search.db").takeIf { it.exists() }?.let { f ->
            runCatching { AndroidSearchDb(f) }.onFailure {
                log("search_db_open_failed ${it.message}")
                if (strict) throw it
            }.getOrNull()
        }
        val info = File(current, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText()) }
        if (info == null) {
            _status.update { it.copy(pack = null, loaded = false, walking = false, searchAvailable = searchDb != null) }
            return@write
        }
        val g = runCatching { OfflineGraph.load(current) }
            .onFailure {
                log("offline_routing_load_failed ${it.message}")
                if (strict) throw it
            }.getOrNull()
        graph = g
        _status.update {
            it.copy(
                pack = info,
                loaded = g != null,
                walking = g?.supports(TravelMode.FOOT) == true,
                searchAvailable = searchDb != null,
                coverage = g?.coverage,
                modes = TravelMode.entries.filter { mode -> g?.supports(mode) == true }.toSet(),
            )
        }
        if (g != null) log("offline_routing_loaded ${info.name}")
    }

    /** Close only under exclusive worker ownership, after every route/search reader has finished. */
    private fun closeResources() {
        _status.update { it.copy(loaded = false, walking = false, searchAvailable = false, coverage = null, modes = emptySet()) }
        graph?.close()
        graph = null
        searchDb?.close()
        searchDb = null
    }

    /** Main-thread availability checks use immutable metadata and never wait for a worker lock. */
    fun covers(points: List<GeoPoint>): Boolean = _status.value.coverage?.let { coverage -> points.all(coverage::covers) } == true

    /** Route holds read ownership throughout GraphHopper access. */
    suspend fun route(points: List<GeoPoint>, mode: TravelMode = TravelMode.CAR): Route = withContext(Dispatchers.Default) {
        resources.read { (graph ?: throw IOException("no offline routing pack")).route(points, mode) }
    }

    fun supports(mode: TravelMode): Boolean = mode in _status.value.modes

    /** Snapping and address search cannot outlive the graph/index they use. */
    suspend fun mapMatch(points: List<GeoPoint>, mode: TravelMode = TravelMode.CAR): MatchedTrack? = withContext(Dispatchers.Default) {
        resources.read { graph?.mapMatch(points, mode = mode) }
    }

    /** Called by PlaceSearch on its I/O dispatcher; the database reference never escapes. */
    fun search(query: String, near: GeoPoint?): List<SearchResult> = resources.read {
        searchDb?.let { AddressSearch.search(it, query, near) }.orEmpty()
    }

    /** Allow or forbid the online OSRM fallback. */
    fun setAllowOnline(on: Boolean) {
        prefs.edit { putBoolean("allow_online", on) }
        _status.update { it.copy(allowOnline = on) }
    }

    /** Remember the pack download URL. */
    fun setPackUrl(url: String) {
        prefs.edit { putString("pack_url", url.trim()) }
        _status.update { it.copy(packUrl = url.trim()) }
    }

    /** Cancel a running install / download. */
    fun cancel() {
        tasks.cancel()
    }

    /** Import a pack .zip picked by the user. */
    fun importZip(open: () -> InputStream?) = runTask(str(R.string.routing_installing, 0)) {
        withContext(Dispatchers.IO) { (open() ?: throw IOException("cannot open file")).use { install(it) } }
    }

    /** Download and install a pack .zip, unpacking while streaming (resumes after network drops). */
    fun download(url: String) {
        setPackUrl(url)
        runTask(str(R.string.task_connecting)) {
            withContext(Dispatchers.IO) {
                var last = 0L
                ResumableHttpInputStream(url.trim(), onProgress = { bytes, total ->
                    if (bytes - last >= 2L * 1024 * 1024) {
                        last = bytes
                        val pct = if (total > 0) (bytes * 100 / total).toInt() else 0
                        progress(str(R.string.routing_downloading, (bytes / 1_048_576).toInt(), (total / 1_048_576).toInt().coerceAtLeast(0), pct))
                    }
                }).use { install(it) }
            }
        }
    }

    /** Delete the installed pack (and do not re-install the built-in one automatically). */
    fun remove() = runTask(str(R.string.routing_removing)) {
        // Do not silently reinstall the bundled pack the user just removed.
        prefs.edit {
            putBoolean("bundled_enabled", false)
            bundledInfo?.let { putString("bundled_declined", it.builtAt) }
        }
        withContext(Dispatchers.IO) {
            resources.write {
                tasks.current.beginCommit()
                closeResources()
                current.deleteRecursively()
                load()
            }
        }
        _status.update { it.copy(preparation = Preparation.REMOVED) }
        str(R.string.routing_removed)
    }

    /** Unzip into a staging folder, validate, then atomically replace the current pack. */
    private suspend fun install(input: InputStream, totalBytes: Long = 0): String {
        val job = coroutineContext // to notice cancellation inside the blocking loop
        val staging = File(root, "staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val operation = tasks.current
        fun checkCancellation() {
            job.ensureActive()
            operation.checkCancelled()
        }
        var bytes = 0L
        var lastReport = 0L
        PackFiles.extract(input, staging, flat = true, check = ::checkCancellation) { copied ->
            bytes = copied
            if (bytes - lastReport >= 4L * 1_048_576) {
                lastReport = bytes
                progress(
                    if (totalBytes > 0) {
                        str(R.string.routing_preparing_builtin, (bytes / 1_048_576).toInt(), (totalBytes / 1_048_576).toInt())
                    } else {
                        str(R.string.routing_installing, (bytes / 1_048_576).toInt())
                    },
                )
            }
        }
        checkCancellation()
        val info = File(staging, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText()) }
        if (info == null || !File(staging, "properties").exists()) {
            staging.deleteRecursively()
            throw IOException(str(R.string.routing_not_a_pack))
        }
        // Validate by loading before replacing the working pack.
        OfflineGraph.load(staging).close()
        checkCancellation()
        File(staging, "search.db").takeIf { it.exists() }?.let { AndroidSearchDb(it).use { /* Verify the index opens before replacing the pack. */ } }
        withContext(NonCancellable + Dispatchers.IO) {
            resources.write {
                checkCancellation()
                operation.beginCommit()
                PackFiles.replace(staging, current, ::closeResources) { load(strict = true) }
                operation.result = str(R.string.routing_installed, info.name)
            }
        }
        log("offline_routing_installed ${info.name} ${bytes / 1_048_576}MB")
        return str(R.string.routing_installed, info.name)
    }

    /** Update the progress text (any thread). */
    private fun progress(text: String) {
        _status.update { it.copy(busy = text) }
    }

    /** Run one install / download at a time; the returned text is shown when it finishes. */
    private fun runTask(start: String, bundled: Boolean = false, block: suspend () -> String) {
        if (tasks.running) return
        tasks.launch { operation ->
            _status.update { it.copy(busy = start, message = null) }
            var succeeded = false
            val msg = try {
                block().also { succeeded = true }
            } catch (_: CancellationException) {
                operation.result?.also { succeeded = true } ?: str(R.string.task_cancelled)
            } catch (_: InterruptedException) {
                str(R.string.task_cancelled)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                log("offline_routing_task_failed ${e.javaClass.simpleName}: ${e.message}")
                Log.w("OfflineRouting", "task failed", e)
                str(R.string.task_failed, e.message ?: e.javaClass.simpleName)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // e.g. NoSuchMethodError / VerifyError from a library on this Android version.
                log("offline_routing_task_error ${e.javaClass.simpleName}: ${e.message}")
                Log.e("OfflineRouting", "task error", e)
                str(R.string.task_failed, e.javaClass.simpleName)
            }
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { File(root, "staging").takeIf { it.exists() }?.deleteRecursively() }
                _status.update {
                    it.copy(
                        busy = null,
                        message = msg,
                        preparation = if (bundled) {
                            if (succeeded && it.loaded) Preparation.READY else Preparation.FAILED
                        } else {
                            if (it.loaded) Preparation.READY else it.preparation
                        },
                    )
                }
            }
        }
    }
}

private const val BUNDLED_ZIP = "routing/pack.zip"
private const val BUNDLED_INFO = "routing/pack.json"

/** Offline first; online OSRM only when allowed and the offline pack cannot answer. */
class SmartRouter(
    private val offline: OfflineRouting,
    private val online: Router,
    private val log: (String) -> Unit,
    private val noOfflineMessage: () -> String,
    /** Message when a walking route is asked for but the offline pack cannot give one. */
    private val noWalkingMessage: () -> String,
) : Router {
    override suspend fun route(from: GeoPoint, to: GeoPoint, via: List<GeoPoint>, mode: TravelMode): Route {
        val points = listOf(from) + via + to
        // Walking routes come only from the offline pack (the public OSRM server offers driving only).
        if (mode == TravelMode.FOOT && !(offline.covers(points) && offline.supports(mode))) throw IOException(noWalkingMessage())
        val allowOnline = offline.status.value.allowOnline && mode == TravelMode.CAR
        if (offline.covers(points)) {
            try {
                return offline.route(points, mode).also { log("route_via offline len=${it.length.toInt()} mode=$mode") }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (e is CancellationException) throw e
                // GraphHopper throws plain RuntimeExceptions (no path, point not found, …).
                log("offline_route_failed ${e.message}")
                if (!allowOnline) throw e
            }
        } else if (!allowOnline) {
            throw IOException(noOfflineMessage())
        }
        return online.route(from, to, via, mode).also { log("route_via osrm len=${it.length.toInt()}") }
    }
}
