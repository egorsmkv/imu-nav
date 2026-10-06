package org.imunav.app.nativecore

/** Controls inexpensive Rust JNI timing counters for a user-requested device profile. */
object NativeProfiler {
    init {
        System.loadLibrary("imu_nav_jni")
    }

    fun start() = nativeStart()

    fun stop(): String = nativeStop()

    private external fun nativeStart()

    private external fun nativeStop(): String
}
