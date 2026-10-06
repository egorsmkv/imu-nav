package org.imunav.app.maps

import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.imunav.app.ui.prepareTrackMap
import org.imunav.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses synthetic geometry and a private callback registry, without touching user files. */
@RunWith(AndroidJUnit4::class)
class MapResourcesTest {
    @Test
    // These legacy pressure levels still apply on supported Android 8–13 devices.
    @Suppress("DEPRECATION")
    fun pressureRegistrationIsRemovedAndLateCallbacksCannotReachDestroyedMap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val callbacks = mutableSetOf<ComponentCallbacks>()
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun getApplicationContext(): Context = this
                override fun registerComponentCallbacks(callback: ComponentCallbacks) {
                    callbacks.add(callback)
                }
                override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
                    callbacks.remove(callback)
                }
            }
            var trims = 0
            val registration = MapMemoryCallbacks(context) { trims++ }
            assertEquals(1, callbacks.size)
            registration.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            registration.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
            assertEquals(0, trims)
            registration.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            registration.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            registration.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            registration.onLowMemory()
            assertEquals(4, trims)
            registration.close()
            registration.close()
            assertTrue(callbacks.isEmpty())
            registration.onLowMemory()
            registration.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            assertEquals(4, trims)
        }
    }

    @Test
    fun preparedTracksKeepGeometryAndComputeBoundsAcrossAllSources() = runBlocking {
        val result = prepareTrackMap(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.1, 30.2)), emptyList(), listOf(GeoPoint(49.0, 31.0)))
        assertEquals(50.1, requireNotNull(result.bounds).latitudeNorth, 0.000001)
        assertEquals(49.0, requireNotNull(result.bounds).latitudeSouth, 0.000001)
        assertEquals(31.0, requireNotNull(result.bounds).longitudeEast, 0.000001)
        assertEquals(30.0, requireNotNull(result.bounds).longitudeWest, 0.000001)
        assertEquals(1, result.features.getValue("gps").features()?.size)
        assertTrue(result.features.getValue("engine").features().orEmpty().isEmpty())
        assertTrue(result.features.getValue("matched").features().orEmpty().isEmpty())
        val empty = prepareTrackMap(emptyList(), emptyList(), emptyList())
        assertNull(empty.first)
        assertNull(empty.bounds)
        val point = prepareTrackMap(emptyList(), listOf(GeoPoint(50.0, 30.0)), emptyList())
        assertNull(point.bounds)
        assertEquals(50.0, requireNotNull(point.first).latitude, 0.000001)
    }
}
