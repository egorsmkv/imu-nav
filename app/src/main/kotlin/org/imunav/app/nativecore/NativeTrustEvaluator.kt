package org.imunav.app.nativecore

import org.imunav.core.geo.ServiceArea
import org.imunav.core.gnss.GnssSnapshot
import org.imunav.core.gnss.JammingDetector
import org.imunav.core.gnss.RawFix
import org.imunav.core.gnss.TrustEvaluator
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.gnss.Verdict

/** Android implementation of the stateful GPS trust firewall backed by `imu-nav-core` in Rust. */
class NativeTrustEvaluator(private val area: ServiceArea) :
    TrustEvaluator,
    JammingDetector,
    AutoCloseable {
    private var handle = nativeCreate().also { check(it != 0L) { "could not create native trust evaluator" } }
    override var jammed = false
        private set

    override fun reset() {
        checkResult(nativeReset(requireHandle()))
        jammed = false
    }

    override fun update(agcDb: Float?, nowMs: Long): Boolean {
        val result = nativeUpdateAgc(requireHandle(), agcDb?.toDouble() ?: Double.NaN, nowMs)
        check(result >= 0) { "native jamming detector error=$result" }
        jammed = result and JAMMED_BIT != 0
        return result and CHANGED_BIT != 0
    }

    override fun evaluate(fix: RawFix, lastGood: RawFix?, lastNet: RawFix?, gnss: GnssSnapshot, jammed: Boolean, compassDeg: Float?, wallNowMs: Long): Verdict {
        // Rust owns its trusted anchor; lastGood remains for the JVM replay implementation.
        val doubles = doubleArrayOf(
            fix.lat,
            fix.lon,
            fix.altitudeM ?: Double.NaN,
            fix.speedMps?.toDouble() ?: Double.NaN,
            fix.bearingDeg?.toDouble() ?: Double.NaN,
            fix.accuracyM?.toDouble() ?: Double.NaN,
            fix.verticalAccuracyM?.toDouble() ?: Double.NaN,
            lastNet?.lat ?: Double.NaN,
            lastNet?.lon ?: Double.NaN,
            lastNet?.accuracyM?.toDouble() ?: Double.NaN,
            gnss.meanCn0Used?.toDouble() ?: Double.NaN,
            gnss.cn0SpreadUsed?.toDouble() ?: Double.NaN,
            gnss.agcDb?.toDouble() ?: Double.NaN,
            compassDeg?.toDouble() ?: Double.NaN,
        )
        val longs = longArrayOf(
            fix.timeMs,
            fix.elapsedMs,
            if (fix.isMock) 1 else 0,
            if (area.contains(fix.lat, fix.lon)) 1 else 0,
            if (lastNet != null) 1 else 0,
            lastNet?.elapsedMs ?: 0,
            gnss.satellitesVisible.toLong(),
            gnss.satellitesUsed.toLong(),
            gnss.dualFrequencyUsed.toLong(),
            gnss.elapsedMs,
            wallNowMs,
        )
        val result = nativeEvaluate(requireHandle(), doubles, longs) ?: error("native trust evaluator failed")
        check(result.isNotEmpty()) { "native trust evaluator returned no verdict" }
        val level = when (result[0]) {
            0 -> TrustLevel.GOOD
            1 -> TrustLevel.SUSPECT
            2 -> TrustLevel.BAD
            else -> error("invalid native trust level=${result[0]}")
        }
        return Verdict(level, result.drop(1).map(::reason))
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        checkResult(nativeDestroy(current))
    }

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "native trust evaluator is closed" } }

    private fun checkResult(code: Int) {
        check(code == 0) { "native trust evaluator error=$code" }
    }

    private fun reason(code: Int): String = REASONS.getOrNull(code) ?: "native_reason_$code"

    private companion object {
        const val JAMMED_BIT = 1
        const val CHANGED_BIT = 2
        val REASONS = listOf(
            "invalid",
            "mock",
            "outside_area",
            "altitude",
            "speed",
            "accuracy",
            "clock_skew",
            "duplicate_time",
            "jump",
            "speed_mismatch",
            "frozen",
            "network_difference",
            "jam",
            "jam_weak",
            "jam_strong",
            "no_satellites",
            "few_satellites",
            "weak_signal",
            "flat_signal",
            "heading_difference",
        )

        init {
            System.loadLibrary("imu_nav_jni")
        }

        @JvmStatic private external fun nativeCreate(): Long

        @JvmStatic private external fun nativeDestroy(handle: Long): Int

        @JvmStatic private external fun nativeReset(handle: Long): Int

        @JvmStatic private external fun nativeUpdateAgc(handle: Long, agcDb: Double, nowMs: Long): Int

        @JvmStatic private external fun nativeEvaluate(handle: Long, doubles: DoubleArray, longs: LongArray): IntArray?
    }
}
