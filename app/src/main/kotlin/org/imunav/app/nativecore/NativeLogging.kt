package org.imunav.app.nativecore

/** Configures the Rust subscriber before app navigation creates native objects. */
object NativeLogging {
    init {
        System.loadLibrary("imu_nav_jni")
    }

    fun configure(diagnostics: Boolean) = nativeConfigure(diagnostics)

    private external fun nativeConfigure(diagnostics: Boolean)
}
