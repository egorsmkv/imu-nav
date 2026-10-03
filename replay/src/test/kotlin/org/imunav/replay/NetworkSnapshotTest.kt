package org.imunav.replay

import org.imunav.app.nativecore.NativeNetworkTracker
import org.imunav.core.nav.NetSample
import org.imunav.core.nav.NetworkTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NetworkSnapshotTest {
    @Test
    fun snapshotsAreReusedUntilMutationAndRetainedSnapshotsNeverChange() {
        NativeNetworkTracker.create().use { native ->
            val kotlin = NetworkTracker()
            val empty = native.recent
            assertSame(empty, native.recent)
            for (index in 0..10) {
                val sample = NetSample(index * 5000L, index * 20.0, 30.0, 2.0)
                native.record(sample, 50.0 + index / 1000.0, 30.0)
                kotlin.record(sample, 50.0 + index / 1000.0, 30.0)
                assertEquals(kotlin.recent, native.recent)
                assertEquals(kotlin.history, native.history)
                assertSame(native.recent, native.recent)
                assertSame(native.history, native.history)
            }
            assertEquals(emptyList(), empty)
            val oldHistory = native.history
            native.pruneHistory(50_000)
            kotlin.pruneHistory(50_000)
            assertEquals(kotlin.history, native.history)
            assertEquals(11, oldHistory.size)
            native.clearSamples()
            assertEquals(emptyList(), native.recent)
            assertEquals(emptyList(), native.history)
            native.record(NetSample(60_000, 1.0, 1.0, 0.0), 50.0, 30.0)
            native.reset()
            assertEquals(emptyList(), native.history)
        }
    }

    @Test
    fun cachedSnapshotDoesNotBypassClosedHandleCheck() {
        val native = NativeNetworkTracker.create()
        native.history
        native.close()
        assertFailsWith<IllegalStateException> { native.history }
    }
}
