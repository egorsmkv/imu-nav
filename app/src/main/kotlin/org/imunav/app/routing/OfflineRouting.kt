package org.imunav.app.routing

import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.imunav.app.R
import org.imunav.app.packs.PackTasks
import org.imunav.app.search.PackSearch
import org.imunav.app.setup.Preparation
import org.imunav.app.setup.bundledRoutingPreparation
import org.imunav.core.cells.ResumableHttpInputStream
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.TravelMode
import org.imunav.core.search.SearchResult
import org.imunav.core.search.TantivySearch
import org.imunav.core.util.PackFiles
import org.imunav.core.util.ResourceAccess
import org.imunav.routing.GraphCoverage
import org.imunav.routing.MatchedTrack
import org.imunav.routing.OfflineGraph
import org.imunav.routing.PackInfo
import org.imunav.routing.PackStamp
import org.imunav.routing.RoutingRequest
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** State of the offline routing pack, for the Settings screen. */
data class OfflineRoutingStatus(
    val preparation: Preparation = Preparation.CHECKING,
    /** The installed pack, if any. */
    val pack: PackInfo? = null,
    /** Pack shipped inside the APK (assets/routing), if any. */
    val bundled: PackInfo? = null,
    /** The installed pack was loaded successfully and can route. */
    val loaded: Boolean = false,
    /**
     * The installed pack is being opened. Opening validates every routing record, which takes
     * seconds for a country pack; until it ends `loaded == false` means "not yet", not "failed".
     */
    val loading: Boolean = false,
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
    /** 0..1 share of [busy] work done, or null when it cannot be measured (indeterminate bar). */
    val busyFraction: Float? = null,
    val message: String? = null,
)

/**
 * Owns the offline routing pack (a Rust graph built with `:routing:run`): import from a
 * .zip, streamed download, load (memory-mapped) and routing.
 */
class OfflineRouting(private val context: Context, private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("routing", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "routing")
    private val current = File(root, "current")
    private var graph: OfflineGraph? = null
    private val tasks = PackTasks(scope)
    private val resources = ResourceAccess()

    /** Address search index shipped inside the pack, with legacy SQLite compatibility. */
    private var searchDb: PackSearch? = null

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
                context.assets.open(BUNDLED_ZIP).use { install(it, bundledBytes = bundled.sizeBytes) }
            }
        }
    }

    /** A string resource in the current app language. */
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Imported native errors become a localized action without exposing index internals. */
    private fun openSearch(directory: File): PackSearch? = try {
        PackSearch.open(directory)
    } catch (failure: IOException) {
        throw IOException(str(R.string.routing_search_invalid), failure)
    }

    /**
     * Open the installed pack (graph + search index); runs in the background. [prepared] is a graph
     * already validated from the same files (before they were moved into place), so an install
     * does not pay for validating a country pack twice.
     */
    private fun load(strict: Boolean = false, prepared: OfflineGraph? = null) = resources.write {
        closeResources()
        val info = File(current, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText()) }
        // Show the installed pack at once; Settings must not call it broken while it is still opening.
        _status.update { it.copy(pack = info ?: it.pack, loading = info != null) }
        try {
            open(info, strict, prepared)
        } finally {
            _status.update { it.copy(loading = false) }
        }
    }

    private fun open(info: PackInfo?, strict: Boolean, prepared: OfflineGraph?) {
        val started = System.nanoTime()
        searchDb = runCatching { openSearch(current) }.onFailure {
            log("search_db_open_failed type=${it.javaClass.simpleName}")
            if (strict) throw it
        }.getOrNull()
        if (info == null) {
            _status.update { it.copy(pack = null, loaded = false, walking = false, searchAvailable = searchDb != null) }
            return
        }
        if (info.routingFormat != PackInfo.FORMAT) {
            _status.update { it.copy(pack = info, loaded = false, message = str(R.string.routing_requires_rust_pack), searchAvailable = searchDb != null) }
            if (strict) throw IOException(str(R.string.routing_requires_rust_pack))
            return
        }
        // A pack validated at install (or on an earlier start) opens without the full record scan.
        val trusted = prepared == null && PackStamp.matches(current)
        val g = prepared ?: runCatching { OfflineGraph.load(current, validate = !trusted) }
            .onSuccess { if (!trusted) runCatching { PackStamp.write(current) } }
            .onFailure {
                log("offline_routing_load_failed type=${it.javaClass.simpleName} ${it.message}")
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
        if (g != null) log("offline_routing_loaded ${info.name} ms=${(System.nanoTime() - started) / 1_000_000} full_check=${prepared == null && !trusted}")
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

    /** Route holds read ownership throughout Rust access. */
    suspend fun route(points: List<GeoPoint>, mode: TravelMode = TravelMode.CAR): Route = nativeRequest { request ->
        resources.read { (graph ?: throw IOException("no offline routing pack")).route(points, mode, request = request) }
    }

    fun supports(mode: TravelMode): Boolean = mode in _status.value.modes

    /** Snapping and address search cannot outlive the graph/index they use. */
    suspend fun mapMatch(points: List<GeoPoint>, mode: TravelMode = TravelMode.CAR): MatchedTrack? = nativeRequest { request ->
        resources.read { graph?.mapMatch(points, mode = mode, request = request) }
    }

    /** Cancellation reaches Rust immediately while the worker retains read ownership until it returns. */
    private suspend fun <T> nativeRequest(work: (RoutingRequest) -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            RoutingRequest().use { request ->
                continuation.invokeOnCancellation { request.cancel() }
                try {
                    continuation.resume(work(request))
                } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                    // Forward native I/O and cancellation failures through the suspended request.
                    continuation.resumeWithException(failure)
                }
            }
        }
    }

    /** Called by PlaceSearch on its I/O dispatcher; the database reference never escapes. */
    fun search(query: String, near: GeoPoint?, cancellation: CancellationSignal): List<SearchResult> = resources.read {
        cancellation.throwIfCanceled()
        searchDb?.search(query, near, cancellation).orEmpty()
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

    /**
     * Import a pack .zip picked by the user. [size] (asked on the I/O thread, 0 when unknown) turns
     * the progress into a percentage. A GitHub Actions artifact that wraps the pack's .zip works too.
     */
    fun importZip(size: () -> Long = { 0 }, open: () -> InputStream?) = runTask(str(R.string.routing_installing, 0)) {
        withContext(Dispatchers.IO) {
            val archiveSize = size()
            (open() ?: throw IOException("cannot open file")).use { install(it, archiveSize = archiveSize) }
        }
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
                        progress(
                            str(R.string.routing_downloading, (bytes / 1_048_576).toInt(), (total / 1_048_576).toInt().coerceAtLeast(0), pct),
                            fraction = if (total > 0) bytes.toFloat() / total else null,
                        )
                    }
                }).use { install(it, streamed = true) }
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

    /**
     * Unzip into a staging folder, validate, then atomically replace the current pack.
     * Progress: [bundledBytes] is the extracted size of the built-in pack, [archiveSize] the size of
     * a picked file; a [streamed] download already reports its own percentage.
     */
    private suspend fun install(input: InputStream, bundledBytes: Long = 0, archiveSize: Long = 0, streamed: Boolean = false): String {
        val job = currentCoroutineContext() // to notice cancellation inside the blocking loop
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
        PackFiles.extract(
            input,
            staging,
            flat = true,
            preserveDirectories = setOf(TantivySearch.DIRECTORY),
            expandNested = true,
            limits = PackFiles.limitsFor(staging, MAX_ROUTING_PACK_BYTES, MAX_ROUTING_PACK_ENTRIES),
            check = ::checkCancellation,
        ) { step ->
            bytes = step.extractedBytes
            if (bytes - lastReport >= 4L * 1_048_576 && !streamed) {
                lastReport = bytes
                val percent = step.percent(archiveSize)
                when {
                    bundledBytes > 0 -> progress(
                        str(R.string.routing_preparing_builtin, (bytes / 1_048_576).toInt(), (bundledBytes / 1_048_576).toInt()),
                        fraction = (bytes.toFloat() / bundledBytes).coerceAtMost(1f),
                    )

                    percent != null -> progress(str(R.string.pack_installing_percent, percent, (bytes / 1_048_576).toInt()), fraction = percent / 100f)

                    else -> progress(str(R.string.routing_installing, (bytes / 1_048_576).toInt()))
                }
            }
        }
        checkCancellation()
        val info = File(staging, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText()) }
        if (info == null) {
            staging.deleteRecursively()
            throw IOException(str(R.string.routing_not_a_pack))
        }
        if (info.routingFormat != PackInfo.FORMAT) {
            staging.deleteRecursively()
            throw IOException(str(R.string.routing_requires_rust_pack))
        }
        // Validate by loading before replacing the working pack. Validation reads every record of a
        // country pack, so the graph is kept and handed over instead of being validated again:
        // the memory mappings stay valid when the staging folder is renamed into place.
        progress(str(R.string.routing_verifying))
        val validated = OfflineGraph.load(staging)
        // Renaming keeps sizes and modification times, so the stamp stays valid in `current`.
        PackStamp.write(staging)
        try {
            checkCancellation()
            openSearch(staging)?.use { /* Validate native or legacy search before replacing the working pack. */ }
            withContext(NonCancellable + Dispatchers.IO) {
                resources.write {
                    checkCancellation()
                    operation.beginCommit()
                    try {
                        commit(staging, validated)
                    } catch (failure: IOException) {
                        // Windows (host tests) cannot rename a folder with mapped files; Android can.
                        // The previous pack is back in place, so retry once and validate from disk.
                        if (!staging.exists()) throw failure
                        validated.close()
                        commit(staging, null)
                    }
                    operation.result = str(R.string.routing_installed, info.name)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { resources.write { if (graph !== validated) validated.close() } }
        }
        log("offline_routing_installed ${info.name} ${bytes / 1_048_576}MB")
        return str(R.string.routing_installed, info.name)
    }

    /** Move [staging] into place under exclusive ownership and activate it, adopting [validated] if given. */
    private fun commit(staging: File, validated: OfflineGraph?) {
        var handedOver = false
        // Hand over only right after staging became current. A failed backup or a rollback
        // activates the previous pack, which must be opened from disk.
        PackFiles.replace(staging, current, ::closeResources) {
            val prepared = validated.takeUnless { handedOver || staging.exists() }
            handedOver = true
            load(strict = true, prepared = prepared)
        }
    }

    /** Update the progress text and, when measurable, the bar (any thread). */
    private fun progress(text: String, fraction: Float? = null) {
        _status.update { it.copy(busy = text, busyFraction = fraction) }
    }

    /** Run one install / download at a time; the returned text is shown when it finishes. */
    private fun runTask(start: String, bundled: Boolean = false, block: suspend () -> String) {
        if (tasks.running) return
        tasks.launch { operation ->
            _status.update { it.copy(busy = start, busyFraction = null, message = null) }
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
                        busyFraction = null,
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
private const val MAX_ROUTING_PACK_BYTES = 8L * 1024 * 1024 * 1024
private const val MAX_ROUTING_PACK_ENTRIES = 20_000

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
        val plan = routingPlan(mode, offline.covers(points), offline.supports(mode), offline.status.value.allowOnline)
        when (plan) {
            RoutingPlan.WALKING_UNAVAILABLE -> throw IOException(noWalkingMessage())
            RoutingPlan.OFFLINE_UNAVAILABLE -> throw IOException(noOfflineMessage())
            else -> Unit
        }
        if (plan == RoutingPlan.OFFLINE_ONLY || plan == RoutingPlan.OFFLINE_WITH_FALLBACK) {
            try {
                return offline.route(points, mode).also { log("route_via offline len=${it.length.toInt()} mode=$mode") }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (e is CancellationException) throw e
                // Rust throws plain RuntimeExceptions (no path, point not found, …).
                log("offline_route_failed ${e.message}")
                if (plan != RoutingPlan.OFFLINE_WITH_FALLBACK) throw e
            }
        }
        return online.route(from, to, via, mode).also { log("route_via osrm len=${it.length.toInt()}") }
    }
}
