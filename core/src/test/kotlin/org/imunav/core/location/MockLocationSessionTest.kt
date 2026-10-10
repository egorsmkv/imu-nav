package org.imunav.core.location

import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Fake provider verifies lifecycle failures without relying on an Android device or GPS. */
class MockLocationSessionTest {
    private val state = GuidanceState(active = true, position = GeoPoint(50.45, 30.52), source = PositionSource.DR, speedKmh = 36f, uncertaintyM = 123.0, bearingDeg = -10f)
    private val sample = requireNotNull(MockLocationSample.from(state, 1000))

    @Test
    fun exportsNavigationUncertaintySpeedBearingAndOriginalTime() {
        assertEquals(state.position, sample.point)
        assertEquals(123f, sample.accuracyM)
        assertEquals(10f, sample.speedMps)
        assertEquals(350f, sample.bearingDeg)
        assertEquals(1000L, sample.elapsedMs)
        assertEquals(1f, MockLocationSample.from(state.copy(uncertaintyM = 0.0), 1000)?.accuracyM)
    }

    @Test
    fun refusesIdleMissingAndInvalidNavigation() {
        val invalid = listOf(
            state.copy(active = false), state.copy(position = null), state.copy(source = PositionSource.NONE),
            state.copy(position = GeoPoint(Double.NaN, 30.0)), state.copy(position = GeoPoint(91.0, 30.0)),
            state.copy(position = GeoPoint(50.0, 181.0)), state.copy(uncertaintyM = Double.POSITIVE_INFINITY),
            state.copy(uncertaintyM = -1.0), state.copy(uncertaintyM = Double.MAX_VALUE),
            state.copy(speedKmh = Float.NaN), state.copy(speedKmh = -1f), state.copy(bearingDeg = Float.NaN),
        )
        invalid.forEach { assertNull(MockLocationSample.from(it, 1000), it.toString()) }
        assertNull(MockLocationSample.from(state, -1))
        assertNotNull(MockLocationSample.from(state.copy(position = GeoPoint(-90.0, -180.0)), 0))
    }

    @Test
    fun offAndIdleNeverInstallProvider() {
        val provider = FakeProvider()
        val session = MockLocationSession(provider)
        assertEquals(MockLocationStatus.OFF, session.update(false, sample))
        assertEquals(MockLocationStatus.WAITING, session.update(true, null))
        assertEquals(emptyList(), provider.calls)
    }

    @Test
    fun permissionIsRequiredEvenBeforeNavigation() {
        val provider = FakeProvider().apply { allowed = false }
        val session = MockLocationSession(provider)
        assertEquals(MockLocationStatus.PERMISSION_REQUIRED, session.update(true, null))
        assertEquals(MockLocationStatus.PERMISSION_REQUIRED, session.update(true, sample))
        assertEquals(emptyList(), provider.calls)
        provider.allowed = true
        assertEquals(MockLocationStatus.ACTIVE, session.update(true, sample))
    }

    @Test
    fun disableRemovesProviderAndReenableCreatesItAgain() {
        val provider = FakeProvider()
        val session = MockLocationSession(provider)
        repeat(2) { assertEquals(MockLocationStatus.ACTIVE, session.update(true, sample)) }
        assertEquals(MockLocationStatus.OFF, session.update(false, sample))
        assertEquals(MockLocationStatus.OFF, session.update(false, null))
        assertEquals(MockLocationStatus.ACTIVE, session.update(true, sample))
        assertEquals(listOf("install", "publish", "publish", "remove", "install", "publish"), provider.calls)
    }

    @Test
    fun stopOrInvalidSampleRestoresProvider() {
        val provider = FakeProvider()
        val session = MockLocationSession(provider)
        session.update(true, sample)
        assertEquals(MockLocationStatus.WAITING, session.update(true, null))
        assertEquals(listOf("install", "publish", "remove"), provider.calls)
    }

    @Test
    fun publicationFailureRollsBackInstalledProvider() {
        val provider = FakeProvider().apply { publishFailure = IllegalStateException("provider unavailable") }
        assertEquals(MockLocationStatus.ERROR, MockLocationSession(provider).update(true, sample))
        assertEquals(listOf("install", "publish", "remove"), provider.calls)
    }

    @Test
    fun installationDenialDoesNotRemoveAnUnownedProvider() {
        val provider = FakeProvider().apply { installFailure = SecurityException("permission revoked") }
        assertEquals(MockLocationStatus.PERMISSION_REQUIRED, MockLocationSession(provider).update(true, sample))
        assertEquals(listOf("install"), provider.calls)
    }

    @Test
    fun revocationDuringPublicationCleansUp() {
        val provider = FakeProvider()
        val session = MockLocationSession(provider)
        session.update(true, sample)
        provider.publishFailure = SecurityException("permission revoked")
        assertEquals(MockLocationStatus.PERMISSION_REQUIRED, session.update(true, sample))
        assertEquals(listOf("install", "publish", "publish", "remove"), provider.calls)
    }

    @Test
    fun failedCleanupRetainsOwnershipForRetry() {
        val provider = FakeProvider()
        val session = MockLocationSession(provider)
        session.update(true, sample)
        provider.removeFailure = SecurityException("permission revoked")
        assertEquals(MockLocationStatus.CLEANUP_FAILED, session.update(false, null))
        provider.removeFailure = null
        assertEquals(MockLocationStatus.OFF, session.update(false, null))
        assertEquals(listOf("install", "publish", "remove", "remove"), provider.calls)
    }

    @Test
    fun processRestartCleansUpRememberedOwnershipEvenWhenDisabled() {
        val provider = FakeProvider()
        assertEquals(MockLocationStatus.OFF, MockLocationSession(provider, initiallyInstalled = true).update(false, null))
        assertEquals(listOf("remove"), provider.calls)
    }

    private class FakeProvider : MockLocationProvider {
        val calls = mutableListOf<String>()
        var allowed = true
        var installFailure: Exception? = null
        var publishFailure: Exception? = null
        var removeFailure: Exception? = null

        override fun isAllowed() = allowed

        override fun install() {
            calls += "install"
            installFailure?.let { throw it }
        }

        override fun publish(sample: MockLocationSample) {
            calls += "publish"
            publishFailure?.let { throw it }
        }

        override fun remove() {
            calls += "remove"
            removeFailure?.let { throw it }
        }
    }
}
