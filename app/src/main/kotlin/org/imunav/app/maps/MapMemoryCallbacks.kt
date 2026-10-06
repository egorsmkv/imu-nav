package org.imunav.app.maps

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

/** Each map owns its registration; closing before MapView destruction prevents retained screens. */
internal class MapMemoryCallbacks(context: Context, private val trim: () -> Unit) :
    ComponentCallbacks2,
    AutoCloseable {
    private val application = context.applicationContext
    private var closed = false

    init {
        application.registerComponentCallbacks(this)
    }

    // Older supported Android versions deliver these callbacks; MapLibre still requires forwarding them.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        if (!closed) trim()
    }

    // Keep legacy pressure-level handling for the supported Android versions that still send it.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            onLowMemory()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun close() {
        if (closed) return
        closed = true
        application.unregisterComponentCallbacks(this)
    }
}
