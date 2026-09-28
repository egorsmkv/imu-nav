package org.blinddriver.app.routing

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blinddriver.app.R
import org.blinddriver.core.cells.ResumableHttpInputStream
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.route.Route
import org.blinddriver.routing.OfflineGraph
import org.blinddriver.routing.PackInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class OfflineRoutingStatus(
    val pack: PackInfo? = null,
    /** Pack shipped inside the APK (assets/routing), if any. */
    val bundled: PackInfo? = null,
    val loaded: Boolean = false,
    val allowOnline: Boolean = true,
    val packUrl: String = "",
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
    private var task: Job? = null

    private val _status = MutableStateFlow(
        OfflineRoutingStatus(allowOnline = prefs.getBoolean("allow_online", true), packUrl = prefs.getString("pack_url", "").orEmpty())
    )
    val status: StateFlow<OfflineRoutingStatus> = _status.asStateFlow()

    /** Metadata of the pack bundled in the APK (assets/routing/pack.json next to pack.zip). */
    private val bundledInfo: PackInfo? = runCatching {
        context.assets.open(BUNDLED_ZIP).close() // the pack itself must be present, not just its description
        context.assets.open(BUNDLED_INFO).use { PackInfo.parse(it.readBytes().decodeToString()) }
    }.getOrNull()

    init {
        _status.update { it.copy(bundled = bundledInfo) }
        scope.launch {
            withContext(Dispatchers.IO) { load() }
            installBundledIfNeeded()
        }
    }

    /**
     * First start (or an app update shipping a newer pack): unpack the bundled pack into app storage.
     * Skipped when a pack at least as new is installed, or the user removed this bundled pack.
     */
    private fun installBundledIfNeeded() {
        val bundled = bundledInfo ?: return
        val installed = _status.value.pack
        if (installed != null && installed.builtAt >= bundled.builtAt) return
        if (prefs.getString("bundled_declined", null) == bundled.builtAt) return
        installBundled()
    }

    /** Unpack the pack shipped with the app (also offered in Settings after it was removed). */
    fun installBundled() {
        val bundled = bundledInfo ?: return
        prefs.edit().remove("bundled_declined").apply()
        runTask(str(R.string.routing_preparing_builtin, 0, (bundled.sizeBytes / 1_048_576).toInt())) {
            withContext(Dispatchers.IO) {
                context.assets.open(BUNDLED_ZIP).use { install(it, totalBytes = bundled.sizeBytes) }
            }
        }
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    @Synchronized
    private fun load() {
        graph?.close()
        graph = null
        val info = File(current, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText()) }
        if (info == null) {
            _status.update { it.copy(pack = null, loaded = false) }
            return
        }
        val g = runCatching { OfflineGraph.load(current) }
            .onFailure { log("offline_routing_load_failed ${it.message}") }
            .getOrNull()
        graph = g
        _status.update { it.copy(pack = info, loaded = g != null) }
        if (g != null) log("offline_routing_loaded ${info.name}")
    }

    /** True if the loaded pack covers every point. */
    fun covers(points: List<GeoPoint>): Boolean = graph?.let { g -> points.all { g.covers(it) } } == true

    /** Route offline (CPU-bound, run off the main thread). */
    suspend fun route(points: List<GeoPoint>): Route = withContext(Dispatchers.Default) {
        val g = synchronized(this@OfflineRouting) { graph } ?: throw IOException("no offline routing pack")
        g.route(points)
    }

    fun setAllowOnline(on: Boolean) {
        prefs.edit().putBoolean("allow_online", on).apply()
        _status.update { it.copy(allowOnline = on) }
    }

    fun setPackUrl(url: String) {
        prefs.edit().putString("pack_url", url.trim()).apply()
        _status.update { it.copy(packUrl = url.trim()) }
    }

    fun cancel() {
        task?.cancel()
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

    fun remove() = runTask(str(R.string.routing_removing)) {
        // Do not silently reinstall the bundled pack the user just removed.
        bundledInfo?.let { prefs.edit().putString("bundled_declined", it.builtAt).apply() }
        withContext(Dispatchers.IO) {
            synchronized(this@OfflineRouting) {
                graph?.close()
                graph = null
            }
            current.deleteRecursively()
            load()
        }
        str(R.string.routing_removed)
    }

    /** Unzip into a staging folder, validate, then atomically replace the current pack. */
    private suspend fun install(input: InputStream, totalBytes: Long = 0): String {
        val ctx = coroutineContext
        val staging = File(root, "staging").apply { deleteRecursively(); mkdirs() }
        var bytes = 0L
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val e = zip.nextEntry ?: break
                if (!ctx.isActive) throw InterruptedException()
                val name = File(e.name).name // flat pack; ignore any folder structure
                if (e.isDirectory || name.isBlank()) continue
                File(staging, name).outputStream().use { out ->
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                    }
                }
                progress(
                    if (totalBytes > 0) str(R.string.routing_preparing_builtin, (bytes / 1_048_576).toInt(), (totalBytes / 1_048_576).toInt())
                    else str(R.string.routing_installing, (bytes / 1_048_576).toInt())
                )
            }
        }
        val info = File(staging, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText()) }
        if (info == null || !File(staging, "properties").exists()) {
            staging.deleteRecursively()
            throw IOException(str(R.string.routing_not_a_pack))
        }
        // Validate by loading before replacing the working pack.
        OfflineGraph.load(staging).close()
        synchronized(this) {
            graph?.close()
            graph = null
            current.deleteRecursively()
            if (!staging.renameTo(current)) throw IOException("cannot install pack")
        }
        load()
        log("offline_routing_installed ${info.name} ${bytes / 1_048_576}MB")
        return str(R.string.routing_installed, info.name)
    }

    private fun progress(text: String) {
        _status.update { it.copy(busy = text) }
    }

    private fun runTask(start: String, block: suspend () -> String) {
        if (task?.isActive == true) return
        task = scope.launch {
            _status.update { it.copy(busy = start, message = null) }
            val msg = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                str(R.string.task_cancelled)
            } catch (e: InterruptedException) {
                str(R.string.task_cancelled)
            } catch (e: Exception) {
                log("offline_routing_task_failed ${e.javaClass.simpleName}: ${e.message}")
                android.util.Log.w("OfflineRouting", "task failed", e)
                str(R.string.task_failed, e.message ?: e.javaClass.simpleName)
            } catch (e: Throwable) {
                // e.g. NoSuchMethodError / VerifyError from a library on this Android version.
                log("offline_routing_task_error ${e.javaClass.simpleName}: ${e.message}")
                android.util.Log.e("OfflineRouting", "task error", e)
                str(R.string.task_failed, e.javaClass.simpleName)
            }
            File(root, "staging").takeIf { it.exists() }?.deleteRecursively()
            _status.update { it.copy(busy = null, message = msg) }
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
) : Router {
    override suspend fun route(from: GeoPoint, to: GeoPoint, via: List<GeoPoint>): Route {
        val points = listOf(from) + via + to
        val allowOnline = offline.status.value.allowOnline
        if (offline.covers(points)) {
            try {
                return offline.route(points).also { log("route_via offline len=${it.length.toInt()}") }
            } catch (e: Exception) {
                log("offline_route_failed ${e.message}")
                if (!allowOnline) throw e
            }
        } else if (!allowOnline) {
            throw IOException(noOfflineMessage())
        }
        return online.route(from, to, via).also { log("route_via osrm len=${it.length.toInt()}") }
    }
}
