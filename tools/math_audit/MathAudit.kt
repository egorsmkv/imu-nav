package org.imunav.audit

import org.imunav.core.geo.Geo
import org.imunav.core.geo.GeoPoint
import org.imunav.core.imu.eskf.Attitude
import org.imunav.core.imu.eskf.ErrorStateEkf
import org.imunav.core.imu.eskf.InertialState
import org.imunav.core.imu.eskf.Vector3
import org.imunav.core.nav.ElevationMatcher
import org.imunav.core.route.Route
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Synthetic audit witnesses; printed diagnostics are observations, not passing correctness checks. */
fun main() {
    inspectRotatingCovariance()
    inspectConstantGrade()
    inspectDateLineGeometry()
}

/** Compare a decoupled attitude marginal with the continuous-time solution, independently of Matrix. */
private fun inspectRotatingCovariance() {
    val angularRate = 35.0
    val durationS = 1.0
    // d(theta) = -skew(omega) theta dt - bias dt + gyroNoise dW; d(bias) = biasWalk dB.
    // Isotropic initial roll/pitch and gyro-bias marginals make this scalar solution exact.
    val angularRateSquared = angularRate * angularRate
    val referenceVariance = 0.15 * 0.15 +
        0.03 * 0.03 * 2 * (1 - cos(angularRate * durationS)) / angularRateSquared +
        0.01 * 0.01 * durationS +
        0.0005 * 0.0005 * (2 * durationS / angularRateSquared - 2 * sin(angularRate * durationS) / (angularRateSquared * angularRate))
    for (stepNs in listOf(20_000_000L, 10_000_000L, 5_000_000L, 2_000_000L)) {
        val filter = ErrorStateEkf(InertialState(0, Vector3.ZERO, Vector3.ZERO, Attitude.IDENTITY), 5.0)
        var timestampNs = stepNs
        while (timestampNs <= 1_000_000_000L) {
            check(filter.predict(timestampNs, Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2), Vector3(0.0, 0.0, angularRate))) {
                "audit input was rejected: step_ns=$stepNs timestamp_ns=$timestampNs"
            }
            timestampNs += stepNs
        }
        val variance = filter.covariance()[6][6]
        println("eskf_rotation step_ns=$stepNs variance=$variance reference=$referenceVariance relative_error=${variance / referenceVariance - 1}")
    }
}

/** A constant grade has relief but, after subtracting its mean, cannot identify route position. */
private fun inspectConstantGrade() {
    val points = (0..100).map { GeoPoint(50.0 + it * 0.0001, 30.0) }
    val geometry = Route(points, emptyList(), 100.0)
    val heights = DoubleArray(points.size) { 100.0 + 0.06 * geometry.cumulative[it] }
    val route = Route(points, emptyList(), 100.0, elevationM = heights)
    val matcher = ElevationMatcher()
    val trueStartM = 200.0
    for (index in 0..40) {
        val odometerM = index * 10.0
        val heightM = 100.0 + 0.06 * (trueStartM + odometerM)
        val pressureHpa = 1013.25 * (1 - heightM / 44_330.0).pow(5.255)
        matcher.onPressure(pressureHpa, index * 1000L)
        matcher.onTravel(odometerM, index * 1000L)
    }
    for (centerM in listOf(240.0, 250.0, 300.0, 600.0)) {
        val match = matcher.match(route, centerM, 150.0, 40_000, ElevationMatcher.VEHICLE_SPEED_SCALES)
        println("terrain_linear true_newest_m=600.0 center_m=$centerM match=$match")
    }
}

/** Valid nearby coordinates straddling 180 degrees exercise the declared worldwide geometry domain. */
private fun inspectDateLineGeometry() {
    val route = Route(listOf(GeoPoint(10.0, 179.999), GeoPoint(10.0, -179.999)), emptyList(), 100.0)
    val expectedMidpoint = GeoPoint(10.0, 180.0)
    val midpoint = route.pointAt(route.length / 2).point
    println("route_dateline length_m=${route.length} midpoint=$midpoint midpoint_error_m=${Geo.distance(midpoint, expectedMidpoint)}")
}
