package org.imunav.app.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.imunav.core.geo.GeoPoint
import org.imunav.core.location.MockLocationSample
import org.imunav.core.location.MockLocationSession
import org.imunav.core.location.MockLocationStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Platform contracts, including Android 9's legacy overload and Android 12's properties builder. */
@RunWith(AndroidJUnit4::class)
class AndroidMockLocationProviderTest {
    private val device = MockLocationDevice()
    private val prefs = device.context.getSharedPreferences("mock_location_provider_test", Context.MODE_PRIVATE)
    private val provider = AndroidMockLocationProvider(device.context, prefs)

    @Before
    fun setUp() {
        device.prepare()
        prefs.edit(commit = true) { clear() }
    }

    @After
    fun tearDown() {
        device.allowMock(true)
        provider.remove()
        device.assertOriginalProvider()
        prefs.edit(commit = true) { clear() }
        device.restore()
    }

    @Test
    fun firstFixEnablesGpsAndPreservesCoordinatesMetadataAndSamplingTime() = device.withForeground {
        val sample = sample()
        provider.install()
        assertTrue(installed())
        // Make the first publication responsible for enabling GPS, regardless of inherited state.
        device.manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        MockGpsObserver(device).use { observer ->
            provider.publish(sample)
            val location = observer.next(sample.point)
            assertTrue(device.manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            assertEquals(LocationManager.GPS_PROVIDER, location.provider)
            assertEquals(sample.accuracyM, location.accuracy, 0f)
            assertEquals(sample.speedMps, location.speed, 0f)
            assertEquals(sample.bearingDeg, location.bearing, 0f)
            assertEquals(sample.elapsedMs * 1_000_000L, location.elapsedRealtimeNanos)
            assertTrue(abs(System.currentTimeMillis() - location.time) < MockLocationDevice.TIMEOUT_MS)
            assertTrue(isMock(location))
            val next = sample().copy(point = GeoPoint(48.126, 28.251), speedMps = 12f)
            provider.publish(next)
            assertEquals(12f, observer.next(next.point).speed, 0f)
        }
    }

    @Test
    fun deniedMockAppOpDoesNotInstallOrClaimOwnership() {
        device.allowMock(false)
        assertFalse(provider.isAllowed())
        assertEquals(MockLocationStatus.PERMISSION_REQUIRED, MockLocationSession(provider).update(true, sample()))
        assertFalse(installed())
        device.assertOriginalProvider()
        device.allowMock(true)
        assertTrue(provider.isAllowed())
    }

    @Test
    fun revocationRetainsOwnershipUntilCleanupCanActuallyRun() {
        val session = MockLocationSession(provider)
        assertEquals(MockLocationStatus.ACTIVE, session.update(true, sample()))
        device.allowMock(false)
        assertEquals(MockLocationStatus.CLEANUP_FAILED, session.update(false, null))
        assertTrue("Denied cleanup must keep its durable retry marker", installed())
        device.allowMock(true)
        assertEquals(MockLocationStatus.OFF, session.update(false, null))
        assertFalse(installed())
        device.assertOriginalProvider()
    }

    @Test
    fun disableAndRepeatedRemovalRestoreGpsAndAllowANewSession() {
        val session = MockLocationSession(provider)
        repeat(2) {
            assertEquals(MockLocationStatus.ACTIVE, session.update(true, sample()))
            assertEquals(MockLocationStatus.OFF, session.update(false, null))
            provider.remove()
            assertFalse(installed())
            device.assertOriginalProvider()
        }
    }

    @Test
    fun rememberedOwnershipIsSafeWhenThePlatformAlreadyRemovedTheProvider() {
        provider.install()
        device.manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        assertTrue(installed())
        val restored = MockLocationSession(provider, initiallyInstalled = installed())
        assertEquals(MockLocationStatus.OFF, restored.update(false, null))
        assertFalse(installed())
        device.assertOriginalProvider()
    }

    private fun installed() = prefs.getBoolean(AndroidMockLocationProvider.INSTALLED, false)
    private fun sample() = MockLocationSample(GeoPoint(48.125, 28.25), 75f, 10f, 350f, SystemClock.elapsedRealtime())

    @Suppress("DEPRECATION") // isMock is only available from Android 12.
    private fun isMock(location: Location) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
}
