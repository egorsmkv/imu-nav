package org.imunav.app.nativecore

import org.imunav.core.nav.NetSample
import org.imunav.core.nav.NetworkPositionTracker
import org.imunav.core.nav.NetworkTracker
import org.imunav.core.speed.SpeedEstimate
import java.io.Closeable

/** Android network/cell reachability gate and speed regression backed by Rust. */
class NativeNetworkTracker private constructor(private var handle: Long) :
    NetworkPositionTracker,
    Closeable {
    override val recent: List<NetSample> get() = samples(history = false)
    override val history: List<NetSample> get() = samples(history = true)

    override fun reset() = checkResult(nativeReset(requireHandle()))

    override fun clearSamples() = checkResult(nativeClearSamples(requireHandle()))

    override fun gate(elapsedMs: Long, s: Double, acc: Double): NetworkTracker.GateResult = when (
        val result = nativeGate(requireHandle(), elapsedMs, s, acc)
    ) {
        GATE_ACCEPTED -> NetworkTracker.GateResult.ACCEPTED
        GATE_REANCHORED -> NetworkTracker.GateResult.REANCHORED
        GATE_REJECTED -> NetworkTracker.GateResult.REJECTED
        else -> error("native network gate error=$result")
    }

    override fun record(sample: NetSample, lat: Double, lon: Double) {
        checkResult(nativeRecord(requireHandle(), sample.elapsedMs, doubleArrayOf(sample.s, sample.accM, sample.offsetM, lat, lon)))
    }

    override fun pruneHistory(nowMs: Long) = checkResult(nativePruneHistory(requireHandle(), nowMs))

    override fun lastTwoConsistent(): Boolean = when (val result = nativeLastTwoConsistent(requireHandle())) {
        RESULT_FALSE -> false
        RESULT_TRUE -> true
        else -> error("native network consistency error=$result")
    }

    override fun speedEstimate(nowMs: Long): SpeedEstimate? = estimate(nowMs, strict = false)

    override fun strictSpeedEstimate(nowMs: Long): SpeedEstimate? = estimate(nowMs, strict = true)

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        checkResult(nativeDestroy(current))
    }

    private fun estimate(nowMs: Long, strict: Boolean): SpeedEstimate? {
        val values = nativeEstimate(requireHandle(), nowMs, if (strict) 1 else 0) ?: return null
        check(values.size == ESTIMATE_SIZE) { "native network estimate returned ${values.size} values" }
        return SpeedEstimate(values[0], values[1], values[2].toInt(), values[3])
    }

    private fun samples(history: Boolean): List<NetSample> {
        val values = nativeSamples(requireHandle(), if (history) 1 else 0) ?: error("native network samples failed")
        check(values.size % SAMPLE_SIZE == 0) { "native network samples returned ${values.size} values" }
        return values.asList().chunked(SAMPLE_SIZE).map { NetSample(it[0].toLong(), it[1], it[2], it[3]) }
    }

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "native network tracker is closed" } }

    private fun checkResult(code: Int) {
        check(code == RESULT_OK) { "native network tracker error=$code" }
    }

    companion object {
        private const val RESULT_OK = 0
        private const val RESULT_FALSE = 0
        private const val RESULT_TRUE = 1
        private const val GATE_ACCEPTED = 0
        private const val GATE_REANCHORED = 1
        private const val GATE_REJECTED = 2
        private const val ESTIMATE_SIZE = 4
        private const val SAMPLE_SIZE = 4

        init {
            System.loadLibrary("imu_nav_jni")
        }

        fun create(): NativeNetworkTracker {
            val handle = nativeCreate()
            check(handle != 0L) { "could not create native network tracker" }
            return NativeNetworkTracker(handle)
        }

        @JvmStatic private external fun nativeCreate(): Long

        @JvmStatic private external fun nativeDestroy(handle: Long): Int

        @JvmStatic private external fun nativeReset(handle: Long): Int

        @JvmStatic private external fun nativeClearSamples(handle: Long): Int

        @JvmStatic private external fun nativeGate(handle: Long, elapsedMs: Long, positionM: Double, accuracyM: Double): Int

        @JvmStatic private external fun nativeRecord(handle: Long, elapsedMs: Long, values: DoubleArray): Int

        @JvmStatic private external fun nativePruneHistory(handle: Long, nowMs: Long): Int

        @JvmStatic private external fun nativeLastTwoConsistent(handle: Long): Int

        @JvmStatic private external fun nativeEstimate(handle: Long, nowMs: Long, strict: Int): DoubleArray?

        @JvmStatic private external fun nativeSamples(handle: Long, history: Int): DoubleArray?
    }
}
