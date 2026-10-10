package org.imunav.core.imu

import kotlin.math.exp

/** Keeps a sensor vector's measurement time; queued callbacks cannot make old data fresh. */
class TimedSensorVector(private val maximumAgeNs: Long = 250_000_000L) {
    private var timestampNs = -1L
    private var values: FloatArray? = null

    /** Own a copy of Android's reusable array; invalid input must not consume its clock. */
    fun update(next: FloatArray, timeNs: Long) {
        if (timeNs < 0 || timeNs <= timestampNs || next.size < 3 || (0..2).any { !next[it].isFinite() }) return
        timestampNs = timeNs
        values = next.copyOf(3)
    }

    /** Future and expired samples must not participate in another sensor's measurement. */
    fun at(timeNs: Long): FloatArray? = values?.takeIf { timeNs >= timestampNs && timeNs - timestampNs <= maximumAgeNs }

    fun reset() {
        timestampNs = -1L
        values = null
    }
}

/** Gravity removal with a one-second time constant, independent of the requested sensor rate. */
class GravityFilter {
    private var timestampNs = -1L
    private val gravity = FloatArray(3)

    /** Reject reordered/invalid samples and re-anchor after a gap rather than inventing motion. */
    fun linearAcceleration(values: FloatArray, timeNs: Long): FloatArray? {
        if (timeNs < 0 || timeNs <= timestampNs || values.size < 3 || (0..2).any { !values[it].isFinite() }) return null
        val gap = timeNs - timestampNs
        if (timestampNs < 0 || gap > MAX_GAP_NS) {
            values.copyInto(gravity, endIndex = 3)
        } else {
            val retained = exp(-gap.toDouble() / TIME_CONSTANT_NS).toFloat()
            for (axis in 0..2) gravity[axis] = retained * gravity[axis] + (1f - retained) * values[axis]
        }
        timestampNs = timeNs
        return FloatArray(3) { values[it] - gravity[it] }
    }

    fun reset() {
        timestampNs = -1L
    }

    private companion object {
        const val TIME_CONSTANT_NS = 1_000_000_000.0
        const val MAX_GAP_NS = 1_000_000_000L
    }
}
