package org.imunav.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import org.imunav.app.nativecore.NativeLogging
import org.imunav.core.net.Http
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/** The Application object: created once per process, before any screen. It builds the [AppGraph]. */
class ImuNavApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate() {
        super.onCreate()
        NativeLogging.configure(BuildConfig.DIAGNOSTICS)
        if (BuildConfig.DIAGNOSTICS) {
            enableStrictMode()
            MainThreadWatchdog.start()
        }
        MapLibre.getInstance(this)
        // HttpRequestImpl initializes its User-Agent from MapLibre's context when this setter is called.
        HttpRequestUtil.setOkHttpClient(Http.callFactory)
        graph = AppGraph(this)
    }
}

/**
 * Debug and benchmark builds only: log every disk read/write and network call made on the main thread (the
 * causes of UI freezes), with a stack trace, under the `StrictMode` Logcat tag.
 */
private fun enableStrictMode() {
    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().penaltyLog().build())
    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build())
}

/** Shortcut to the process-wide [AppGraph] from any Context (`context.graph`). */
val Context.graph: AppGraph get() = (applicationContext as ImuNavApp).graph
