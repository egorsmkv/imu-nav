package org.imunav.app.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression for older Android invoking provider callbacks without interface defaults. */
class FixLocationListenerTest {
    @Test
    fun providerChangesDoNotCrashOrCreateFixes() {
        var fixes = 0
        val listener = FixLocationListener { fixes++ }
        for (provider in listOf("gps", "network")) {
            listener.onProviderDisabled(provider)
            listener.onProviderEnabled(provider)
            listener.onStatusChanged(provider, 0, null)
        }
        assertEquals(0, fixes)
    }
}
