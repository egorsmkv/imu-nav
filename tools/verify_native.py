#!/usr/bin/env python3
"""Run the pinned bounded native proofs and fail closed on incomplete evidence."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.68.0'
RUSTC = 'rustc 1.100.0-nightly (8925ea358 2026-08-20)'
OUTPUT = ROOT / 'build/native-verification'
# Names and cover labels are intentional inventory, not discovery: deleting a proof must fail CI.
REQUIRED = {
    'estimator::turn::kani_proofs::landmark_isolation_accepts_the_exact_distance_boundary': {'inside isolation', 'isolation boundary'},
    'estimator::turn::kani_proofs::invalid_turn_ingress_preserves_all_state': {'duplicate', 'future', 'stale', 'old route', 'invalid angle'},
    'estimator::turn::kani_proofs::measured_position_motion_and_quality_gates_block_turns': {'gps boundary', 'motion control', 'walking', 'stationary', 'fast', 'uncertain'},
    'estimator::turn::kani_proofs::cooldown_accepts_only_at_or_after_exact_boundary': {'before cooldown', 'cooldown boundary'},
    'estimator::turn::kani_proofs::a_used_landmark_cannot_be_reused_after_cooldown': {'same landmark rejected', 'later reuse rejected'},
    'estimator::turn::kani_proofs::ambiguous_and_nearby_landmarks_cannot_correct_position': {'ambiguous', 'nearby rival', 'no landmark'},
    'estimator::turn::kani_proofs::isolated_turn_correction_is_bounded_and_never_refreshes_sensors': {'backward correction', 'forward correction', 'correction cap'},

    'estimator::network_position::speed::kani_proofs::failed_batches_are_disjoint_and_position_slots_are_inert': {'failed fits consumed'},
    'estimator::network_position::speed::kani_proofs::fit_finalization_always_preserves_storage_capacity': {'extended', 'capacity limit', 'span limit', 'usable speed'},
    'estimator::network_position::kani_proofs::stop_between_network_scans_discards_the_partial_batch': {'active control', 'new hint'},
    'estimator::network_position::kani_proofs::speed_allocations_never_fall_through_to_position': {'accepted fit', 'rejected fit', 'restored prior'},
    'estimator::network_position::speed::kani_proofs::departure_confirmation_requires_consistent_evidence': {'standing prior', 'confirmed', 'opposite departure'},
    'estimator::network_position::kani_proofs::invalid_network_ingress_preserves_all_state': {'walking', 'future', 'duplicate time', 'stale', 'invalid number'},
    'estimator::network_position::kani_proofs::cached_and_too_frequent_fixes_cannot_add_evidence': {'cached coordinate', 'rate limited'},
    'estimator::network_position::kani_proofs::reroute_discards_learning_but_keeps_duplicate_protection': {'integer limit', 'same time'},
    'estimator::network_position::kani_proofs::measured_speed_and_motion_veto_discard_network_learning': {'gps position boundary', 'gps speed boundary', 'obd boundary', 'stop', 'disabled'},
    'estimator::network_position::kani_proofs::position_correction_requires_count_and_span': {'insufficient count', 'insufficient span', 'eligible boundary'},
    'estimator::network_position::speed::kani_proofs::completed_batches_are_disjoint_and_position_slots_are_inert': {'second complete batch'},
    'estimator::network_position::speed::kani_proofs::extended_batch_reaches_capacity_and_clears_storage': {'capacity reached'},
    'estimator::network_position::speed::kani_proofs::failed_batch_never_returns_reserved_fixes_to_position': {'cooldown', 'fresh batch'},
    'estimator::network_position::speed::kani_proofs::recovery_restores_original_prior_and_starts_without_old_samples': {'cooldown', 'recovery boundary'},

    'estimator::motion::kani_proofs::fresh_measured_speed_blocks_all_motion_hints': {'gps wins', 'obd wins'},
    'estimator::motion::kani_proofs::measured_priority_ends_at_exact_freshness_boundary': {'gps fresh boundary', 'obd fresh boundary', 'gps expired boundary', 'obd expired boundary'},
    'estimator::motion::kani_proofs::walking_mode_ignores_car_motion_hints': {'hint ignored', 'missing hint'},
    'estimator::motion::kani_proofs::repeated_motion_hint_preserves_position_and_cruise': {'stop', 'ramp', 'cruise'},
    'estimator::motion::kani_proofs::repeated_motion_hint_preserves_fallback_cruise': {'stop', 'ramp', 'cruise'},
    'estimator::motion::kani_proofs::repeated_motion_hint_preserves_capped_cruise': {'stop', 'ramp', 'cruise'},
    'estimator::motion::kani_proofs::vetoed_motion_restores_cruise_without_an_anchor': {'missing', 'network moving', 'invalid'},
    'estimator::motion::kani_proofs::measured_speed_recovery_clears_control_only_when_accepted': {'trusted recovery', 'suspect rejected'},
    'estimator::walking::kani_proofs::car_mode_ignores_all_walking_hints': {'hint ignored', 'missing hint'},
    'estimator::walking::kani_proofs::fresh_gps_preserves_walking_speed_and_original_deadline': {'gps wins', 'gps without hint', 'saturated deadline'},
    'estimator::walking::kani_proofs::walking_hint_takes_over_at_gps_expiry': {'fresh boundary', 'expired boundary', 'expired'},
    'estimator::walking::kani_proofs::repeated_walking_hints_are_bounded_and_never_anchor_position': {'moving', 'stopped', 'invalid', 'missing'},

    'estimator::kani_proofs::prediction_boundaries_advance_and_split_at_expiry': {'fresh', 'expired', 'motion boundary', 'walking boundary', 'integer limit'},
    'estimator::kani_proofs::gps_time_gate_obeys_history_and_watermarks': {'accepted', 'future', 'duplicate', 'history boundary', 'initial checkpoint'},
    'estimator::kani_proofs::vehicle_time_gate_rejects_stale_samples': {'duplicate', 'stale', 'same time accepted'},
    'estimator::kani_proofs::calibration_blend_stays_in_valid_interval': {'increased', 'decreased'},
    'estimator::kani_proofs::calibration_requires_accepted_precise_fresh_good_gps': {'learned', 'suspect', 'position rejected', 'speed rejected', 'freshness boundary'},
    'estimator::kani_proofs::obd_plateau_restarts_after_gap_or_acceleration': {'gap', 'acceleration', 'stable boundary'},
    'estimator::kani_proofs::walking_hint_expiry_cannot_refresh_itself': {'expires', 'rejected'},
    'estimator::kani_proofs::motion_hint_expiry_cannot_refresh_itself': {'expires', 'rejected'},
    'estimator::kani_proofs::prediction_drops_obd_allowance_at_expiry': {'crossed expiry'},
    'estimator::kani_proofs::calibration_speed_and_ratio_gates': {'lower ratio', 'upper ratio', 'low speed', 'outlier'},

    'estimator::kani_proofs::failed_tick_transaction_preserves_state': {'rolled back'},
    'estimator::kani_proofs::successful_tick_transaction_commits_state': {'committed'},
    'estimator::kani_proofs::state_transactions_commit_only_success': {'committed', 'rolled back'},
    'estimator::kani_proofs::checkpoint_copy_preserves_complete_state': {'copied'},

    'kani_proofs::diagonal_success_is_finite': {'accepted', 'rejected'},
    'kani_proofs::commit_is_atomic_and_finite': {'accepted', 'rejected'},
    'kani_proofs::constructor_success_is_finite': {'accepted', 'rejected'},
    'kani_proofs::anchor_success_is_finite': {'accepted', 'rejected'},
    'kani_proofs::speed_prior_success_is_finite': {'accepted', 'rejected'},
    'kani_proofs::restored_prior_success_is_finite': {'accepted', 'rejected'},
    'kani_proofs::prediction_success_is_finite': {'accepted', 'rejected'},
    'kani_proofs::position_update_success_is_finite': {'accepted', 'gated', 'error'},
    'kani_proofs::speed_update_success_is_finite': {'accepted', 'gated', 'error'},
    'kani_proofs::coarse_position_success_is_finite': {'accepted', 'gated', 'error'},
    'kani_proofs::coarse_speed_success_is_finite': {'accepted', 'gated', 'error'},
    'kani_proofs::safety_radius_success_is_finite': {'accepted', 'rejected'},

    'kani_proofs::constructor_rejects_invalid_inputs': {'rejected'},
    'kani_proofs::invalid_correction_limit_preserves_state': {'rejected'},
    'kani_proofs::validators_reject_invalid_numbers': {'nan', 'infinity', 'negative zero'},
    'kani_proofs::nonfinite_measurement_preserves_state': {'rejected'},
    'kani_proofs::invalid_position_update_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_speed_update_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_coarse_position_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_coarse_speed_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_anchor_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_speed_prior_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_restore_prior_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_prediction_uncertainty_preserves_state': {'rejected'},
    'kani_proofs::invalid_gate_preserves_state': {'rejected'},
    'kani_proofs::invalid_prediction_preserves_state': {'rejected'},
    'kani_proofs::anchor_and_reset_preserve_speed': {'accepted'},
    'kani_proofs::restore_prior_never_reduces_variance': {'accepted'},
    'kani_proofs::coarse_position_is_conservative': {'accepted', 'rejected'},
    'kani_proofs::coarse_speed_is_conservative': {'accepted', 'rejected'},
    'trust::kani_proofs::first_fix_hard_reasons_cannot_be_good': {'accepted', 'rejected'},
    'trust::kani_proofs::only_good_promotes_anchor': {'accepted', 'suspect', 'rejected'},
    'trust::kani_proofs::classifier_reset_removes_history': {'reset'},
    'trust::kani_proofs::jam_sequence_reports_changes_and_ignores_missing': {'changed', 'unchanged'},
    'trust::kani_proofs::jam_recovery_honors_hold_boundary': {'before boundary', 'at boundary'},
    'trust::kani_proofs::jam_interruption_restarts_hold': {'interrupted'},
    'network::kani_proofs::invalid_gate_preserves_network_state': {'rejected'},
    'network::kani_proofs::coarse_fix_cannot_establish_anchor': {'rejected'},
    'network::kani_proofs::reset_and_clear_remove_samples': {'reset'},
}


def indexed(items, key):
    """Reject duplicates as well as missing or unexpected harnesses."""
    result = {item[key]: item for item in items}
    if len(result) != len(items) or set(result) != set(REQUIRED):
        raise ValueError(f'Incomplete harness inventory: missing={set(REQUIRED) - set(result)}, '
                         f'unexpected={set(result) - set(REQUIRED)}')
    return result


def validate_report(report):
    """Require proofs AND reachable witnesses, including checks Kani does not gate by default."""
    try:
        if report['metadata']['version'] != '1.0' or report['metadata']['kani_version'] != VERSION:
            raise ValueError('Unexpected Kani report version')
        if report['tools']['rustc'] != RUSTC or report['tools']['kani'] != VERSION:
            raise ValueError('Unexpected verifier compiler')
        if report['project']['crate_name'] != ['imu_nav_core']:
            raise ValueError('Unexpected verified crate')
        summary = report['verification_results']['summary']
        count = len(REQUIRED)
        if (summary['status'] != 'completed' or summary['failed'] != 0 or
                any(summary[key] != count for key in ('total_harnesses', 'executed', 'successful'))):
            raise ValueError('Incomplete or failed verification')
        metadata = indexed(report['harness_metadata'], 'pretty_name')
        results = indexed(report['verification_results']['results'], 'harness_id')
        errors = indexed(report['error_details'], 'harness_id')
        for name, labels in REQUIRED.items():
            attributes = metadata[name]['attributes']
            if attributes['kind'] != 'Proof' or attributes['should_panic'] is not False:
                raise ValueError(f'{name}: expected an ordinary proof')
            result = results[name]
            if result['status'] != 'Success' or errors[name]['has_errors'] is not False:
                raise ValueError(f'{name}: failed or timed out')
            checks = result['checks']
            if not checks or not any(check['category'] == 'assertion' and check['status'] == 'Success'
                                     for check in checks):
                raise ValueError(f'{name}: no successful assertions')
            covers = set()
            for check in checks:
                if check['category'] == 'cover':
                    if check['status'] != 'Satisfied':
                        raise ValueError(f'{name}: unreachable witness {check["description"]}')
                    covers.add(check['description'])
                elif check['status'] not in ('Success', 'Unreachable'):
                    raise ValueError(f'{name}: unsuccessful check {check["description"]}')
            if not labels <= covers:
                raise ValueError(f'{name}: missing reachability checks {labels - covers}')
        return {'passed': True, 'kani_version': VERSION, 'harnesses': count,
                'required_witnesses': sum(map(len, REQUIRED.values()))}
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError(f'Malformed Kani report: {error}') from error


def run_logged(command, env, path, timeout):
    """Keep diagnostics on failure; terminate the whole solver process group on deadline."""
    with path.open('w') as log:
        log.write('Command: ' + ' '.join(map(str, command)) + '\n')
        log.flush()
        with subprocess.Popen(command, cwd=ROOT / 'native', env=env, stdout=log,
                              stderr=subprocess.STDOUT, start_new_session=True) as process:
            try:
                code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired as error:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise RuntimeError(f'Verification timed out; see {path}') from error
            if code:
                raise RuntimeError(f'Command failed ({code}); see {path}')
    return path.read_text()


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report_path = OUTPUT / 'results.json'
    summary_path = OUTPUT / 'summary.json'
    # Never accept a successful report left by an earlier run after a compiler failure.
    report_path.unlink(missing_ok=True)
    summary_path.unlink(missing_ok=True)
    env = os.environ.copy()
    for key in ('RUSTFLAGS', 'CARGO_ENCODED_RUSTFLAGS', 'RUSTC', 'RUSTC_WRAPPER',
                'RUSTC_WORKSPACE_WRAPPER', 'RUSTUP_TOOLCHAIN', 'CARGO_BUILD_TARGET'):
        env.pop(key, None)
    try:
        version = run_logged(['cargo', 'kani', '--version'], env, OUTPUT / 'version.log', 60)
        if f'Kani Rust Verifier {VERSION} (cargo plugin)\n' not in version:
            raise RuntimeError(f'Install kani-verifier {VERSION}; see docs/NATIVE_VERIFICATION.md')
        print(f'Verifying {len(REQUIRED)} harnesses with Kani {VERSION}; logs: {OUTPUT}', flush=True)
        run_logged(['cargo', 'kani', '-p', 'imu-nav-core', '--lib', '--target-dir', str(OUTPUT / 'target'),
                    '-Z', 'unstable-options', '--export-json', str(report_path),
                    '--harness-timeout', '5m', '-j', '2', '--output-format', 'terse'],
                   env, OUTPUT / 'verification.log', 25 * 60)
        summary = validate_report(json.loads(report_path.read_text()))
    except (OSError, ValueError, RuntimeError) as error:
        summary_path.write_text(json.dumps({'passed': False, 'error': str(error)}, indent=2) + '\n')
        print(str(error), file=sys.stderr)
        return 1
    summary_path.write_text(json.dumps(summary, indent=2) + '\n')
    print(f'All {summary["harnesses"]} bounded proofs and required witnesses passed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
