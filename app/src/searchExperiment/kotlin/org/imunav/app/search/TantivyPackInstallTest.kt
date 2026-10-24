package org.imunav.app.search

import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.imunav.app.routing.OfflineRouting
import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.TantivySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs the production staging/validation/commit path in isolated app storage with a public graph. */
class TantivyPackInstallTest {
    @Test
    fun importNativePackAndRejectDamagedReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue(assets.list("").orEmpty().contains("native-pack.zip"))
        val root = File(instrumentation.targetContext.cacheDir, "pack-install-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${root.name}-$name", mode)
        }
        context.getSharedPreferences("routing", Context.MODE_PRIVATE).edit().putBoolean("bundled_enabled", false).commit()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        lateinit var routing: OfflineRouting
        try {
            instrumentation.runOnMainSync { routing = OfflineRouting(context, scope) {} }
            await { routing.status.value.preparation.name != "CHECKING" }
            instrumentation.runOnMainSync { routing.importZip { assets.open("native-pack.zip") } }
            await { routing.status.value.busy == null && routing.status.value.loaded }
            assertTrue(routing.status.value.searchAvailable)
            val directory = File(root, "routing/current")
            assertTrue(File(directory, TantivySearch.DIRECTORY).isDirectory)
            assertTrue(!File(directory, "search.db").exists())
            val expected = routing.search("Rue Bel Respiro 1", GeoPoint(43.7384, 7.4246), CancellationSignal())
            assertEquals("Rue Bel Respiro, 1", expected.first().title)
            // A malformed replacement must leave the installed graph and its live search reader usable.
            instrumentation.runOnMainSync { routing.importZip { byteArrayOf(1, 2, 3).inputStream() } }
            await { routing.status.value.busy == null && routing.status.value.message != null }
            assertEquals(expected, routing.search("Rue Bel Respiro 1", GeoPoint(43.7384, 7.4246), CancellationSignal()))
            instrumentation.runOnMainSync { routing.remove() }
            await { routing.status.value.busy == null && !routing.status.value.loaded }
        } finally {
            runBlocking { job.cancelAndJoin() }
            root.deleteRecursively()
            context.getSharedPreferences("routing", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Pack operation timed out" }
            Thread.sleep(20)
        }
    }
}
