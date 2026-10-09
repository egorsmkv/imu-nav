# Synthetic ESKF consistency checks

`ErrorStateEkfConsistencyTest` checks whether the experimental filter's **vertical position/velocity
covariance** agrees with a specified linear Gaussian model. It also checks that deliberately wrong
process noise is detected. It does not calibrate a phone or validate the full nonlinear 15-state
filter. No production tuning or navigation behaviour is changed by this experiment.

## Reproduce

Run the Kotlin implementation and its independent analytical oracle:

```bash
./gradlew :core:test --tests '*ErrorStateEkfConsistencyTest' --info
```

The five tests run through the existing core test/coverage workflow; no additional CI job or
dependency is required. Each ensemble prints an `eskf_consistency` line, also captured in
`core/build/test-results/test/TEST-org.imunav.core.imu.eskf.ErrorStateEkfConsistencyTest.xml`.
Record the commit, JDK, Kotlin version and these lines when comparing runs.

| Parameter | Value |
| --- | --- |
| Independent episodes per ensemble | 256 |
| Matched-model seeds (`java.util.Random`) | 1711, 2711 |
| Negative-control seed | 1711 (paired samples across scenarios) |
| Terminal time | 0.2 seconds, prediction internally split into 20 ms steps |
| Independent initial standard deviations | Position 30 m, velocity 5 m/s, accelerometer bias 0.3 m/s² |
| Accelerometer bias random-walk density | 0.005, in the filter's SI convention |
| Assumed/actual accelerometer noise densities | Matched 10/10; understated 10/40; overstated 40/10 |
| Terminal vertical-velocity observation variance | 1 (m/s)² |

The deliberately large synthetic acceleration-noise densities make propagation errors visible over
a short, inexpensive test. They are **not recommended device settings**. The matched test uses two
preselected seeds; failures must not be fixed by choosing more favourable seeds or widening bounds
after inspecting results.

## Model and independent truth

Nominal attitude is identity, specific force is `(0, 0, g)` and angular rate is zero. In the current
first-order ESKF equations, the vertical error subsystem decouples from horizontal/attitude errors.
Its continuous-time model is

```text
dp = v dt
dv = -b dt + a dW
db = w dB
```

Here `p` is vertical position error (m), `v` velocity error (m/s), `b` accelerometer bias error
(m/s²), and `W`, `B` are independent standard Wiener processes. Initial errors are independent,
zero-mean Gaussian with variances 900, 25 and 0.09. Measurement noise is independent Gaussian.
These are supplied synthetic assumptions, not distributions inferred from recordings.

Integrating the model gives the following marginal covariance at time `t`, with `qa=a²`, `qb=w²`:

```text
Ppp = 900 + 25 t² + 0.09 t⁴/4 + qa t³/3 + qb t⁵/20
Ppv = 25 t + 0.09 t³/2 + qa t²/2 + qb t⁴/8
Pvv = 25 + 0.09 t² + qa t + qb t³/3
```

For example, the bias-noise contributions follow from integrating kernels
`-(t-s)²/2` and `-(t-s)` for position and velocity, giving `t⁵/20`, `t⁴/8` and `t³/3`.
The test samples this **joint** endpoint distribution, retaining position/velocity correlation.
It does not sample from the filter's own covariance or reuse its matrix solver.
Each endpoint is a new independent experiment; there is no pooling of correlated timestamps.
This is an endpoint Monte Carlo experiment, not a simulation of Android sensor delivery.

The deterministic test compares the ESKF covariance with this solution at both 10 and 20 ms steps,
using absolute tolerance `1e-9` for each covariance entry (in its respective units). The transition
series is exact for this nilpotent subsystem. Simpson noise integration has a small `Ppp` truncation
term from bias walk: at 20 ms its accumulated excess is `10 * qb * 0.02⁵ / 180`, about `4.4e-15` m².
Thus this tolerance covers that known approximation and floating-point rounding, not arbitrary
model discrepancy.

## Metrics and acceptance rules

- **Prior NEES:** `eᵀ P⁻¹ e` for the two-dimensional position/velocity marginal at the terminal
  prediction. Its matched-model reference has 2 degrees of freedom, not 15.
- **Pre-gate NIS:** `innovation² / (Pvv + R)` for every terminal observation, including those later
  rejected. Its matched-model reference has 1 degree of freedom.
- **95% coverage:** count of prior NEES values at most `5.991464547107979`, the 95th percentile of
  chi-square(2). This describes a joint error ellipse, not two independent coordinate intervals.
- **Gated posterior NEES and rejection count:** descriptive diagnostics for all episodes, without
  applying an ordinary chi-square acceptance rule. Gating changes the resulting distribution.

For `N=256` independent matched-model episodes, the mean-statistic bounds are
`chi2.ppf([alpha/2, 1-alpha/2], N*d) / N`, with `alpha=1e-4`. The rounded-outward bounds are
`[1.550030, 2.523540]` for NEES and `[0.692262, 1.381249]` for NIS. Coverage must be between 228 and
254 episodes, from the corresponding binomial bounds with probability 0.95. These are per-check
acceptance regions under the specified model, not posterior probabilities that the filter is correct
or a simultaneous confidence guarantee. The fixed seeds make CI reproducible; they do not prove
calibration for arbitrary seeds or operating conditions.

Generate the constants independently (computed with SciPy 1.17.0; SciPy is not a test dependency):

```python
from scipy.stats import binom, chi2

n, alpha = 256, 1e-4
for dimension in (1, 2):
    print(chi2.ppf([alpha / 2, 1 - alpha / 2], n * dimension) / n)
print(chi2.ppf(0.95, 2))
print(binom.ppf([alpha / 2, 1 - alpha / 2], n, 0.95))
```

The understated-noise control must exceed both upper mean bounds and lose coverage; the
overstated-noise control must fall below both lower mean bounds. No chi-square distribution is
assumed for the mismatched controls: these are tests that the diagnostics can detect these particular
constructed failures. Shared seeds across scenarios do not make the scenarios independent.

Each accepted correction is additionally compared to scalar Gaussian conditioning using the
analytical prior; rejected corrections must preserve the entire state and covariance. No samples
are silently removed to improve consistency scores.

## Evidence scope and remaining work

The covariance formula above is a derivation for this linear subsystem. Kotlin results from the
command above are computational evidence for the implementation. A passing mean/coverage test is
not proof of a chi-square distribution, full filter consistency, observability or real-drive accuracy.
Gaussian model assumptions exclude outliers, coloured noise, unknown parameter distributions,
sensor timing errors and nonlinear attitude effects. Parameter uncertainty and model discrepancy
are not included in the reported intervals. Monte Carlo sampling error is covered only by the
stated ensemble acceptance rules; numerical error is checked separately against the analytical
covariance.

Next experiments should cover rotating trajectories, quaternion error coordinates, long GPS outages,
innovation whiteness and recorded sensor timing. Real-data NIS must use all eligible pre-gate
innovations and inspect temporal dependence. Real-data NEES needs trustworthy independent ground
truth; ordinary GPS is not automatically such a reference.

[Observability experiments](eskf-observability.md) now compare fixed motion, turns and missing GPS
using scaled sensitivity matrices and explicit ambiguity tests. They address identifiability,
not statistical calibration of the full nonlinear filter.

Reference: Zhaozhong Chen, Harel Biggie, Nisar Ahmed, Simon Julier and Christoffer Heckman,
*Kalman Filter Auto-tuning through Enforcing Chi-Squared Normalized Error Distributions with
Bayesian Optimization*, [arXiv:2306.07225v1, 12 June 2023](https://arxiv.org/html/2306.07225v1),
sections II-B and III-A–B, equations (4)–(14), for consistency conditions and NEES/NIS statistics;
section V discusses why matching means alone is insufficient. This PR does not implement the
paper's automatic tuning algorithm.
