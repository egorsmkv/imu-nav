package org.imunav.core.imu.eskf

import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixed-seed consistency checks of the linearized vertical position/velocity marginal.
 * Truth comes from an independent continuous-time solution, never from the filter's covariance.
 * See docs/reference/eskf-consistency.md for assumptions, statistical bounds and limitations.
 */
class ErrorStateEkfConsistencyTest {
    @Test
    fun predictionMatchesContinuousTimeVerticalCovariance() {
        val expected = verticalTruthCovariance(MATCHED_NOISE)
        for (stepNs in longArrayOf(10_000_000L, 20_000_000L)) {
            val filter = filter(MATCHED_NOISE)
            var timestamp = stepNs
            while (timestamp <= HORIZON_NS) {
                assertTrue(filter.predict(timestamp, GRAVITY, Vector3.ZERO))
                timestamp += stepNs
            }
            val actual = filter.covariance()
            assertEquals(expected.position, actual[2][2], 1e-9)
            assertEquals(expected.cross, actual[2][5], 1e-9)
            assertEquals(expected.velocity, actual[5][5], 1e-9)
        }
    }

    @Test
    fun matchedModelHasConsistentPriorNeesAndPregateNis() {
        for (seed in longArrayOf(1711L, 2711L)) {
            val report = ensemble(MATCHED_NOISE, MATCHED_NOISE, seed)
            assertTrue(report.meanPriorNees in NEES_MEAN_BOUNDS, report.toString())
            assertTrue(report.meanPregateNis in NIS_MEAN_BOUNDS, report.toString())
            assertTrue(report.covered95 in 228..254, report.toString())
        }
    }

    @Test
    fun understatedProcessNoiseIsDetected() {
        val report = ensemble(MATCHED_NOISE, MISMATCHED_NOISE, 1711L)
        assertTrue(report.meanPriorNees > NEES_MEAN_BOUNDS.endInclusive, report.toString())
        assertTrue(report.meanPregateNis > NIS_MEAN_BOUNDS.endInclusive, report.toString())
        assertTrue(report.covered95 < 228, report.toString())
        assertTrue(report.rejected > 0, "negative control must exercise rejection: $report")
    }

    @Test
    fun overstatedProcessNoiseIsDetected() {
        val report = ensemble(MISMATCHED_NOISE, MATCHED_NOISE, 1711L)
        assertTrue(report.meanPriorNees < NEES_MEAN_BOUNDS.start, report.toString())
        assertTrue(report.meanPregateNis < NIS_MEAN_BOUNDS.start, report.toString())
    }

    @Test
    fun neesOracleIncludesPositionVelocityCorrelation() {
        val covariance = VerticalCovariance(4.0, 3.0, 9.0)
        assertEquals(4.0 / 3.0, covariance.nees(2.0, 0.0), 1e-14)
        assertEquals(0.0, covariance.nees(0.0, 0.0))
    }

    /** One terminal sample per independent episode: no pooling of correlated time samples. */
    private fun ensemble(assumedNoise: Double, actualNoise: Double, seed: Long): ConsistencyReport {
        val random = Random(seed)
        val truth = verticalTruthCovariance(actualNoise)
        val expectedPrior = verticalTruthCovariance(assumedNoise)
        val report = ConsistencyReport(seed, assumedNoise, actualNoise)
        repeat(EPISODES) {
            // Closed-form joint Gaussian endpoint, including initial and process uncertainty.
            val positionNormal = random.nextGaussian()
            val positionError = sqrt(truth.position) * positionNormal
            val velocityError = truth.cross / sqrt(truth.position) * positionNormal +
                sqrt(truth.velocity - truth.cross * truth.cross / truth.position) * random.nextGaussian()
            val observation = velocityError + sqrt(MEASUREMENT_VARIANCE) * random.nextGaussian()
            val filter = filter(assumedNoise)
            assertTrue(filter.predict(HORIZON_NS, GRAVITY, Vector3.ZERO))
            val priorState = filter.state
            val prior = filter.covariance()
            val priorMarginal = VerticalCovariance(prior[2][2], prior[2][5], prior[5][5])
            val nees = priorMarginal.nees(positionError - priorState.position.z, velocityError - priorState.velocity.z)
            val innovation = observation - priorState.velocity.z
            val nis = innovation * innovation / (prior[5][5] + MEASUREMENT_VARIANCE)
            report.priorNees += nees
            report.pregateNis += nis // Include every observation, even if correct() later rejects it.
            if (nees <= NEES_95) report.covered95++
            val accepted = filter.correct(intArrayOf(5), doubleArrayOf(observation), doubleArrayOf(MEASUREMENT_VARIANCE))
            assertEquals(nis <= VELOCITY_GATE, accepted, "seed=$seed nis=$nis")
            if (accepted) {
                checkScalarPosterior(filter, expectedPrior, observation)
            } else {
                report.rejected++
                assertEquals(priorState, filter.state)
                val unchanged = filter.covariance()
                for (row in prior.indices) assertTrue(prior[row].contentEquals(unchanged[row]))
            }
            val posterior = filter.covariance()
            report.gatedPosteriorNees += VerticalCovariance(posterior[2][2], posterior[2][5], posterior[5][5]).nees(
                positionError - filter.state.position.z,
                velocityError - filter.state.velocity.z,
            )
        }
        println(report)
        return report
    }

    /** Scalar Gaussian conditioning is an independent oracle for each accepted ESKF correction. */
    private fun checkScalarPosterior(filter: ErrorStateEkf, prior: VerticalCovariance, observation: Double) {
        val innovationVariance = prior.velocity + MEASUREMENT_VARIANCE
        assertEquals(prior.cross / innovationVariance * observation, filter.state.position.z, 1e-9)
        assertEquals(prior.velocity / innovationVariance * observation, filter.state.velocity.z, 1e-9)
        val posterior = filter.covariance()
        assertEquals(prior.position - prior.cross * prior.cross / innovationVariance, posterior[2][2], 1e-9)
        assertEquals(prior.cross * MEASUREMENT_VARIANCE / innovationVariance, posterior[2][5], 1e-9)
        assertEquals(prior.velocity * MEASUREMENT_VARIANCE / innovationVariance, posterior[5][5], 1e-9)
    }

    private fun filter(accelerationNoise: Double) = ErrorStateEkf(
        InertialState(0, Vector3.ZERO, Vector3.ZERO, Attitude.IDENTITY),
        positionSigmaM = 5.0,
        tuning = InertialTuning(accelerometerNoise = accelerationNoise, accelerometerBiasWalk = BIAS_WALK),
    )

    /** Integrate dp=v dt, dv=-b dt + a dW, db=w dB with independent initial errors and Wiener drivers. */
    private fun verticalTruthCovariance(accelerationNoise: Double): VerticalCovariance {
        val time = HORIZON_NS / 1e9
        val squared = time * time
        val cubed = squared * time
        val fourth = cubed * time
        val fifth = fourth * time
        val accelerationVariance = accelerationNoise * accelerationNoise
        val biasWalkVariance = BIAS_WALK * BIAS_WALK
        // These independently specified initial vertical sigmas (30 m, 5 m/s, 0.3 m/s²) are part
        // of the experiment contract; do not read them from the filter being checked.
        return VerticalCovariance(
            900.0 + 25.0 * squared + 0.09 * fourth / 4 + accelerationVariance * cubed / 3 + biasWalkVariance * fifth / 20,
            25.0 * time + 0.09 * cubed / 2 + accelerationVariance * squared / 2 + biasWalkVariance * fourth / 8,
            25.0 + 0.09 * squared + accelerationVariance * time + biasWalkVariance * cubed / 3,
        )
    }

    /** Two-dimensional whitening, independent of the production Matrix/Cholesky implementation. */
    private data class VerticalCovariance(val position: Double, val cross: Double, val velocity: Double) {
        fun nees(positionError: Double, velocityError: Double): Double {
            val whitenedPosition = positionError / sqrt(position)
            val whitenedVelocity = (velocityError - cross * positionError / position) / sqrt(velocity - cross * cross / position)
            return whitenedPosition * whitenedPosition + whitenedVelocity * whitenedVelocity
        }
    }

    /** Posterior numbers describe the gated algorithm and deliberately have no chi-square assertion. */
    private data class ConsistencyReport(val seed: Long, val assumedNoise: Double, val actualNoise: Double) {
        var priorNees = 0.0
        var pregateNis = 0.0
        var gatedPosteriorNees = 0.0
        var covered95 = 0
        var rejected = 0
        val meanPriorNees get() = priorNees / EPISODES
        val meanPregateNis get() = pregateNis / EPISODES

        override fun toString() = "eskf_consistency seed=$seed episodes=$EPISODES assumed_noise=$assumedNoise actual_noise=$actualNoise " +
            "prior_nees=$meanPriorNees pregate_nis=$meanPregateNis covered95=$covered95 rejected=$rejected " +
            "gated_posterior_nees=${gatedPosteriorNees / EPISODES}"
    }

    private companion object {
        const val EPISODES = 256
        const val HORIZON_NS = 200_000_000L
        const val MATCHED_NOISE = 10.0
        const val MISMATCHED_NOISE = 40.0
        const val BIAS_WALK = 0.005
        const val MEASUREMENT_VARIANCE = 1.0
        const val VELOCITY_GATE = 10.828 // Existing scalar 99.9% ESKF gate.
        const val NEES_95 = 5.991464547107979 // Chi-square(2) 95% ellipse, not a mean-test bound.

        // Two-sided alpha=1e-4: chi2.ppf([alpha/2, 1-alpha/2], EPISODES*dof) / EPISODES.
        // Rounded outward. Derivation and SciPy reproduction command are in the reference guide.
        val NEES_MEAN_BOUNDS = 1.550030..2.523540
        val NIS_MEAN_BOUNDS = 0.692262..1.381249
        val GRAVITY = Vector3(0.0, 0.0, ErrorStateEkf.GRAVITY_MPS2)
    }
}
