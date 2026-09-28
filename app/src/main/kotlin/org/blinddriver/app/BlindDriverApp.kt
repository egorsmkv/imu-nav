package org.blinddriver.app

import android.app.Application
import android.content.Context
import org.maplibre.android.MapLibre

/** The Application object: created once per process, before any screen. It builds the [AppGraph]. */
class BlindDriverApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        graph = AppGraph(this)
    }
}

/** Shortcut to the process-wide [AppGraph] from any Context (`context.graph`). */
val Context.graph: AppGraph get() = (applicationContext as BlindDriverApp).graph
