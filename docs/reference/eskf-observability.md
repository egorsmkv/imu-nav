# ESKF observability experiments

This experiment asks which **initial error-state combinations** can be distinguished from ideal
GPS position/velocity samples along specified IMU motions. It complements
[Monte Carlo consistency checks](eskf-consistency.md). Observability is a property of the model,
inputs and observations; a smaller reported covariance alone is not evidence of identifiability.

## Reproduce

The offline tool needs NumPy and SciPy in the selected Python environment. It adds no app dependency
or CI job. Its `--check` mode verifies explicit stationary null vectors, reference scenario ranks,
and integration refinement from 20 to 10 ms:

```bash
python tools/eskf_observability.py --check
./gradlew :core:test --tests '*ErrorStateEkfObservabilityTest'
```

The Python command emits JSON with versions, matrix dimensions, all singular values, relative
cutoffs and refinement errors. `--matrix-input stationary` emits a JSON request suitable for the
`compute-with-numpy` skill's `analyze.py --input` helper. The Python tool analyzes independently
written model equations; it does not execute Kotlin. The five Kotlin tests exercise the actual
predictor/corrector and are discovered by the existing core test workflow.

## Model, units and observations

The unknown initial error has 15 coordinates: position (m), velocity (m/s), right-local attitude
(rad), body accelerometer bias (m/s²), body gyro bias (rad/s). All are unknown; position/velocity
are nuisance states when interpreting bias identifiability. Gravity is fixed, and nominal biases
are zero. For this deterministic sensitivity calculation biases are constant; stochastic bias
walks, measurement noise realizations and priors are not extra unknowns or observation rows.

The continuous linearized error equations are:

```text
dp/dt     = dv
dv/dt     = -R [f]x dtheta - R dba
dtheta/dt = -[omega]x dtheta - dbg
dba/dt    = 0
dbg/dt    = 0
```

These match the dynamics blocks in `ErrorStateEkf.propagateCovariance`. `R` maps body to ENU,
`f` is bias-corrected body specific force including gravity, and `[x]x` is the cross-product matrix.
The source convention is Joan Solà, *Quaternion kinematics for the error-state Kalman filter*,
[arXiv:1711.02508v1, 3 November 2017](https://arxiv.org/html/1711.02508v1), section 5.3.3 and
appendix B.3, equations (376)–(378). This app omits the paper's gravity-error state.

For the experiment, body x is forward, y left and z up. This is a prescribed mounting for generating
motion, **not an extra vehicle constraint supplied to the estimator**. The GPS observation matrix
selects position/velocity only; there is no direct heading, orientation or bias observation.

Each 10-second experiment samples GPS at 0, 0.5, ..., 10 seconds. The outage variant retains only the
initial sample. The sensitivity matrix stacks `W H Phi(t,0) D`, where:

- `Phi` integrates the equations using `expm(A(t_mid) dt)` at 20 ms; it is an independent integration
  of the continuous model, not the production third-order transition series.
- `D` scales columns by `[5,5,30,5,5,5,.15,.15,.5,.3,.3,.3,.03,.03,.03]` in the coordinate units above.
  These are reference magnitudes matching initial filter sigmas, not prior-information rows.
- `W` scales observation rows by inverse standard deviations: 3 m for position, 0.5 m/s for velocity.
  Independent diagonal observation noise is a synthetic weighting assumption. No uncertainty or
  confidence intervals are inferred from the singular values.

The scaled matrix is dimensionless. Numerical rank counts singular values above `1e-8 * sigma_max`;
the report also repeats the decision at `1e-6` and `1e-10`. Scaling and cutoff must accompany any
comparison. Full column rank here concerns a finite-horizon local linearization, not global
uniqueness or reliable estimation under arbitrary noise.

## Measured model results

Executed with Python 3.12.14, NumPy 2.3.5 and SciPy 1.17.0, float64, deterministic inputs (no RNG).
The same ranks were obtained at all three cutoffs and at both integration steps:

| Scenario | Prescribed motion | Matrix shape | Rank / 15 | Smallest retained singular value |
| --- | --- | --- | --- | --- |
| Stationary | Identity attitude, gravity-only force | 126 × 15 | 11 | 5.727 |
| Constant velocity | Fixed attitude, no acceleration | 126 × 15 | 11 | 5.727 |
| Constant turn | Speed 5 m/s, yaw rate 0.2 rad/s | 126 × 15 | 12 | 0.1984 |
| Varying turn | Speed and yaw rate vary, as specified below | 126 × 15 | 15 | 0.4181 |
| GPS outage | Initial position/velocity sample only | 6 × 15 | 6 | 1.667 |

The varying maneuver uses speed `5 + 2 sin(0.9 t)` m/s and yaw
`0.2 t + 0.3 (1 - cos(0.7 t))` rad. Body force is `[speed_derivative, speed*yaw_rate, g]`.
This synthetic maneuver was specified before computing ranks. A steady turn adds information in
this experiment but still leaves three locally unobservable combinations. The varying maneuver's
full numerical rank does not establish practical calibration on real trips.

The stationary and constant-velocity matrices are identical: nominal translational velocity does
not enter these dynamics. The maximum residual of the four explicit stationary null vectors was
`4.6e-12` or less. Halving the integration step changed the matrices by at most `1.6e-6` in relative
Frobenius norm. These are computational checks, not certified error bounds.

## Explicit ambiguities and Kotlin regression checks

For stationary identity attitude, the outputs constrain the initial six position/velocity errors,
the three acceleration-error combinations
`g*dtheta_y - dba_x`, `-g*dtheta_x - dba_y`, `-dba_z`, and the two horizontal gyro biases through
their effect on acceleration derivatives. Four independent invisible directions remain:

1. Initial yaw error.
2. Vertical gyro bias, which only drifts the unobserved yaw.
3. Roll error paired with `dba_y = -g*dtheta_x`.
4. Pitch error paired with `dba_x = g*dtheta_y`.

This explains rank 11 for the ideal stationary linear model, separately from the SVD result.
There is also an exact nonlinear stationary ambiguity: for any constant rotation `R`, choose
`ba = measured_force - Rᵀ*g_up`. Then `R*(measured_force-ba) = g_up`, so the same accelerometer,
zero gyro and stationary GPS samples fit different tilts and accelerometer biases. Arbitrary yaw
also leaves gravity unchanged. No optimizer can resolve this family without additional information.

The Kotlin tests check:

- Two distinct tilt/bias hypotheses preserve identical stationary position/velocity observations.
- A different yaw and vertical gyro bias fit identical straight-line GPS motion; GPS course does
  not automatically identify phone yaw without a mounting/vehicle constraint.
- A turn makes a selected initial-yaw perturbation visible in velocity; this is a discriminating
  example, not a full-rank test for all 15 coordinates.
- With the default diagonal prior and stationary zero-residual GPS, the yaw/vertical-gyro covariance
  block evolves exactly as in an unaided filter while position covariance is reduced.
- During six seconds without GPS, continuous IMU prediction increases horizontal sigma. Returning
  GPS reduces position uncertainty but does not collapse the stationary unobserved yaw variance.

The last two assertions are scoped to their priors and motions. In general, prior correlations may
allow an observation to reduce a marginal variance even without structural identifiability.

## Scope and next evidence

The rank experiment excludes Earth rotation, scale/lever-arm errors, magnetic observations,
uncertain mounting constraints and stochastic driving noise. Nonlinear linearization error,
finite-noise practical ambiguity and long-term consistency remain separate questions. An outage
adds no new GPS rows; process noise can still grow state uncertainty even though it is absent from
the deterministic rank model.

Before tuning real-device noise, evaluate recorded turns/accelerations, changing orientations and
sensor timing with independent reference data. Track singular-value scales as well as ranks;
then combine that information with NIS/NEES and innovation-whiteness experiments. No production
algorithm changes are justified solely by the numerical ranks reported here.
