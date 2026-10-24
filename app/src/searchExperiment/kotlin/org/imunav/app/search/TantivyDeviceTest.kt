package org.imunav.app.search

import android.content.res.AssetManager
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.TantivyRequest
import org.imunav.core.search.TantivySearch
import org.imunav.search.CountingSearchDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

/** Opt-in R8 benchmark instrumentation; native assets and library live only in the test APK. */
class TantivyDeviceTest {
    @Test
    fun installedPackPrefersNativeAndRetainsLegacyCompatibility() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "native-pack-${System.nanoTime()}")
        val assets = instrumentation.context.assets
        val near = GeoPoint(43.7384, 7.4246)
        try {
            copyAssets(assets, "regional.db", File(root, "search.db"))
            val expected = requireNotNull(PackSearch.open(root)).use { it.search("Rue Bel Respiro 1", near, CancellationSignal()) }
            assertTrue(expected.isNotEmpty())
            copyAssets(assets, "regional.tantivy", File(root, TantivySearch.DIRECTORY))
            File(root, "search.db").writeText("not SQLite: native must be preferred")
            requireNotNull(PackSearch.open(root)).use { index ->
                assertEquals(expected, index.search("Rue Bel Respiro 1", near, CancellationSignal()))
                val signal = CancellationSignal().apply { cancel() }
                try {
                    index.search("Rue Bel Respiro 1", near, signal)
                    throw AssertionError("Cancelled request returned results")
                } catch (_: OperationCanceledException) {
                    // Already-cancelled requests must not enter either backend.
                }
            }
            File(root, "pack.json").writeText("""{"bounds":[0,1,0,1],"searchFormat":"tantivy-1"}""")
            val hidden = File(root, "saved-index")
            check(File(root, TantivySearch.DIRECTORY).renameTo(hidden))
            try {
                PackSearch.open(root)?.close()
                throw AssertionError("Missing declared native index accepted")
            } catch (_: IOException) {
                // A damaged new pack cannot silently fall back to SQLite.
            }
            check(hidden.renameTo(File(root, TantivySearch.DIRECTORY)))
            File(root, "${TantivySearch.DIRECTORY}/search-format").writeText("unsupported")
            try {
                PackSearch.open(root)?.close()
                throw AssertionError("Invalid native index silently accepted")
            } catch (_: IOException) {
                // Validation rejects a damaged native pack before it can replace the working pack.
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun compareCompleteSearches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "tantivy-experiment-${System.nanoTime()}")
        try {
            val assets = instrumentation.context.assets
            val datasets = assets.list("").orEmpty().filter { it.endsWith(".db") && (it.startsWith("synthetic-") || it.startsWith("regional")) }
            assertTrue("Experiment fixtures are required", datasets.isNotEmpty())
            for (name in datasets) {
                copyAssets(assets, name, File(root, name))
                val indexName = name.removeSuffix(".db") + ".tantivy"
                copyAssets(assets, indexName, File(root, indexName))
            }
            for (database in root.listFiles().orEmpty().filter { it.extension == "db" }.sortedBy { it.name }) {
                val regional = database.name.startsWith("regional")
                val queries = if (regional) {
                    listOf("av", "Monaco", "Rue Bel Respiro 1", "Rue Bel Respiro 9999", "La Condamine Rue Imberty 6")
                } else {
                    listOf("ma", "го", "Main 22", "Main 22 к1", "New Town Main 22", "Far Away Town Main 22", "absent")
                }
                val near = if (regional) GeoPoint(43.7384, 7.4246) else GeoPoint(45.0, 30.0)
                val index = File(root, "${database.nameWithoutExtension}.tantivy")
                val sqliteOpen = measureNanoTime { AndroidSearchDb(database).use { AddressSearch.search(it, queries.first(), near) } } / 1e6
                val nativeOpen = measureNanoTime { TantivySearch(index).use { native -> TantivyRequest().use { native.search(queries.first(), near, it) } } } / 1e6
                Log.i("SearchExperiment", "open dataset=${database.name} sqlite_ms=$sqliteOpen native_ms=$nativeOpen")
                AndroidSearchDb(database).use { sqlite ->
                    TantivySearch(index).use { native ->
                        for ((case, query) in queries.withIndex()) {
                            for (reference in listOf(near, null, GeoPoint(0.0, 179.999))) {
                                assertEquals(AddressSearch.search(sqlite, query, reference), TantivyRequest().use { native.search(query, reference, it) })
                            }
                            repeat(15) {
                                AddressSearch.search(sqlite, query, near)
                                TantivyRequest().use { native.search(query, near, it) }
                            }
                            val sqlTimes = mutableListOf<Double>()
                            val nativeTimes = mutableListOf<Double>()
                            repeat(50) { iteration ->
                                val sqlRun = { sqlTimes.add(measureNanoTime { AddressSearch.search(sqlite, query, near) } / 1e6) }
                                val nativeRun = { nativeTimes.add(measureNanoTime { TantivyRequest().use { native.search(query, near, it) } } / 1e6) }
                                if (iteration % 2 == 0) {
                                    sqlRun()
                                    nativeRun()
                                } else {
                                    nativeRun()
                                    sqlRun()
                                }
                            }
                            val counted = CountingSearchDb(sqlite)
                            AddressSearch.search(counted, query, near)
                            sqlTimes.sort()
                            nativeTimes.sort()
                            Log.i(
                                "SearchExperiment",
                                "warm dataset=${database.name} case=$case sqlite_calls=${counted.calls} house_calls=${counted.houseCalls} retrieval_jni_calls=1 sqlite_p50=${sqlTimes[24]} sqlite_p95=${sqlTimes[47]} native_p50=${nativeTimes[24]} native_p95=${nativeTimes[47]}",
                            )
                        }
                        verifyCancellation(native, near)
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** A cancelled request must not poison the following request on the same reader. */
    private fun verifyCancellation(native: TantivySearch, near: GeoPoint) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            TantivyRequest().use { request ->
                val future = executor.submit<Boolean> {
                    try {
                        repeat(1000) { native.search("Main New Town 22", near, request) }
                        false
                    } catch (_: CancellationException) {
                        true
                    }
                }
                Thread.sleep(10)
                request.cancel()
                assertEquals(true, future.get(5, TimeUnit.SECONDS))
            }
            TantivyRequest().use { native.search("Main", near, it) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun copyAssets(assets: AssetManager, path: String, destination: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(path).use { source -> destination.outputStream().use(source::copyTo) }
        } else {
            destination.mkdirs()
            children.forEach { child -> copyAssets(assets, if (path.isEmpty()) child else "$path/$child", File(destination, child)) }
        }
    }
}
