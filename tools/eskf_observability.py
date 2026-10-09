#!/usr/bin/env python3
"""Offline local ESKF observability experiment; requires NumPy and SciPy, not used by the app.

Run with --check for analytical nullspace and integration-refinement checks, or
--matrix-input SCENARIO to export an input for the compute-with-numpy skill.
See docs/reference/eskf-observability.md for model, scaling and limitations.
"""
import argparse
import json
import math
import platform

import numpy as np
import scipy
from scipy.linalg import expm

GRAVITY = 9.80665
DURATION = 10.0
OBSERVATION_PERIOD = 0.5
SCALES = np.array([5., 5., 30., 5., 5., 5., .15, .15, .5, .3, .3, .3, .03, .03, .03])
OBSERVATION_SIGMAS = np.array([3., 3., 3., .5, .5, .5])
RCOND = 1e-8
SCENARIOS = ('stationary', 'constant_velocity', 'constant_turn', 'varying_turn', 'gps_outage')


def skew(vector):
    """Cross-product operator in the right-local attitude convention."""
    x, y, z = vector
    return np.array([[0., -z, y], [z, 0., -x], [-y, x, 0.]])


def motion(scenario, time):
    """Prescribed level planar motion: body x forward, y left, z up; no vehicle constraints fused."""
    if scenario in ('stationary', 'constant_velocity', 'gps_outage'):
        yaw, rate, speed, acceleration = 0., 0., 0. if scenario == 'stationary' else 5., 0.
    elif scenario == 'constant_turn':
        yaw, rate, speed, acceleration = .2 * time, .2, 5., 0.
    elif scenario == 'varying_turn':
        yaw = .2 * time + .3 * (1 - math.cos(.7 * time))
        rate = .2 + .21 * math.sin(.7 * time)
        speed = 5 + 2 * math.sin(.9 * time)
        acceleration = 1.8 * math.cos(.9 * time)
    else:
        raise ValueError(f'unknown scenario: {scenario}')
    cosine, sine = math.cos(yaw), math.sin(yaw)
    rotation = np.array([[cosine, -sine, 0.], [sine, cosine, 0.], [0., 0., 1.]])
    return rotation, np.array([acceleration, speed * rate, GRAVITY]), np.array([0., 0., rate])


def dynamics(scenario, time):
    """Continuous deterministic error dynamics; bias random walks are excluded from rank analysis."""
    rotation, force, rate = motion(scenario, time)
    matrix = np.zeros((15, 15), dtype=np.float64)
    matrix[:3, 3:6] = np.eye(3)
    matrix[3:6, 6:9] = -rotation @ skew(force)
    matrix[3:6, 9:12] = -rotation
    matrix[6:9, 6:9] = -skew(rate)
    matrix[6:9, 12:15] = -np.eye(3)
    return matrix


def observation_matrix(scenario, step_ms):
    """Stack W H Phi(t,0) D at GPS times, using midpoint frozen-dynamics exponentials."""
    dt = step_ms / 1000
    stride = round(OBSERVATION_PERIOD / dt)
    transition = np.eye(15)
    rows = []
    for step in range(round(DURATION / dt) + 1):
        # Outage starts after the initial fix: no fictitious zero-error GPS observations.
        if step % stride == 0 and (scenario != 'gps_outage' or step == 0):
            rows.append(transition[:6, :] * SCALES[None, :] / OBSERVATION_SIGMAS[:, None])
        if step < round(DURATION / dt):
            transition = expm(dynamics(scenario, (step + .5) * dt) * dt) @ transition
    return np.vstack(rows)


def diagnostics(matrix):
    """Finite-precision rank with explicit relative cutoff and dimensionless column scaling."""
    if matrix.ndim != 2 or matrix.shape[1] != 15 or not np.isfinite(matrix).all():
        raise ValueError('expected finite observation matrix with 15 columns')
    singular = np.linalg.svd(matrix, compute_uv=False)
    cutoff = RCOND * singular[0]
    rank = int(np.count_nonzero(singular > cutoff))
    return {'shape': list(matrix.shape), 'rank': rank, 'nullity': 15 - rank,
            'cutoff': float(cutoff), 'singular_values': singular.tolist(),
            'rank_by_rcond': {str(tolerance): int(np.count_nonzero(singular > tolerance * singular[0]))
                              for tolerance in (1e-6, 1e-8, 1e-10)}}


def check(matrices):
    """Analytical stationary nullspace plus step refinement; never a proof for arbitrary motions."""
    stationary = matrices['stationary']
    np.testing.assert_allclose(stationary, matrices['constant_velocity'], atol=0, rtol=0)
    nullspace = np.zeros((15, 4))
    nullspace[8, 0] = 1.  # yaw
    nullspace[14, 1] = 1.  # yaw gyro bias; attitude drifts only in the unobserved yaw direction
    nullspace[6, 2], nullspace[10, 2] = 1., -GRAVITY  # roll / accelerometer-y bias
    nullspace[7, 3], nullspace[9, 3] = 1., GRAVITY  # pitch / accelerometer-x bias
    scaled_nullspace = nullspace / SCALES[:, None]
    residual = float(np.max(np.abs(stationary @ scaled_nullspace)))
    if residual > 1e-9:
        raise AssertionError(f'stationary nullspace residual: {residual}')
    expected_ranks = dict(zip(SCENARIOS, (11, 11, 12, 15, 6)))
    for scenario, expected_rank in expected_ranks.items():
        if diagnostics(matrices[scenario])['rank'] != expected_rank:
            raise AssertionError(f'reference scenario rank changed: {scenario}')
    refinement = {}
    for scenario, matrix in matrices.items():
        fine = observation_matrix(scenario, 10)
        relative = float(np.linalg.norm(matrix - fine) / np.linalg.norm(fine))
        if relative > 1e-4 or diagnostics(matrix)['rank'] != diagnostics(fine)['rank']:
            raise AssertionError(f'integration refinement failed: {scenario}: {relative}')
        refinement[scenario] = relative
    return {'stationary_nullspace_max_abs': residual, 'relative_matrix_change_20_to_10_ms': refinement}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--matrix-input', choices=SCENARIOS)
    args = parser.parse_args()
    if args.matrix_input:
        print(json.dumps({'operation': 'matrix', 'matrix': observation_matrix(args.matrix_input, 20).tolist(), 'rcond': RCOND}))
        return
    matrices = {scenario: observation_matrix(scenario, 20) for scenario in SCENARIOS}
    report = {'evidence': 'approximate_numeric_local_linearized_model',
              'versions': {'python': platform.python_version(), 'numpy': np.__version__, 'scipy': scipy.__version__},
              'duration_s': DURATION, 'step_ms': 20, 'observation_period_s': OBSERVATION_PERIOD,
              'state_scales': SCALES.tolist(), 'observation_sigmas': OBSERVATION_SIGMAS.tolist(), 'rcond': RCOND,
              'scenarios': {scenario: diagnostics(matrix) for scenario, matrix in matrices.items()}}
    if args.check:
        report['checks'] = check(matrices)
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
