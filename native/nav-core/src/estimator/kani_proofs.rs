//! Compositional transaction proofs plus public ingress rollback checks.
//! Numerical bodies and real-geometry replay sequences have separate proofs/regression tests.
use super::*;

fn estimator() -> NavigationEstimator {
    let mut navigation = NavigationEstimator::new(
        Arc::new(crate::route::kani_proofs::straight_route()),
        InitialEstimate {
            position_m: 0.0,
            speed_mps: 10.0,
            position_sigma_m: 20.0,
            speed_sigma_mps: 2.0,
            systematic_drift_m: 5.0,
        },
        TravelMode::Car,
        0,
    )
    .unwrap();
    // Reserve bounded fixture storage before inserting frames; allocator byte-reallocation
    // is outside this logical-state contract and makes CBMC formulas needlessly enormous.
    let initial = navigation.history.pop_front().unwrap();
    navigation.history = VecDeque::with_capacity(4);
    navigation.history.push_back(initial);
    navigation
}

/// Exhaustive destructuring and nested equality cover all logical state, excluding spare capacity.
fn unchanged(actual: &NavigationEstimator, expected: &NavigationEstimator) {
    let NavigationEstimator {
        state,
        route,
        mode,
        last_gps_ms,
        last_vehicle_input_ms,
        network_speed_enabled,
        history,
    } = actual;
    assert!(state == &expected.state);
    assert_eq!(*mode, expected.mode);
    assert_eq!(*last_gps_ms, expected.last_gps_ms);
    assert_eq!(*last_vehicle_input_ms, expected.last_vehicle_input_ms);
    assert_eq!(*network_speed_enabled, expected.network_speed_enabled);
    assert_eq!(history.len(), expected.history.len());
    assert!(history.len() <= 3);
    assert!(history.get(0) == expected.history.get(0));
    assert!(history.get(1) == expected.history.get(1));
    assert!(history.get(2) == expected.history.get(2));
    assert!(Arc::ptr_eq(route, &expected.route));
}

/// Mutate the real pending state, including learned inputs, before choosing success or error.
fn change_pending(state: &mut FilterState, elapsed_ms: i64, position: u8) {
    state
        .filter
        .anchor_position(f64::from(position), 5.0)
        .unwrap();
    state.elapsed_ms = elapsed_ms;
    state.last_vehicle_speed_ms = elapsed_ms;
    state.vehicle_speed_scale = 1.01;
    state.stable_vehicle_speed = Some(StableVehicleSpeed::new(elapsed_ms, 10.0));
    state.last_gps_speed_ms = elapsed_ms;
    state.last_gps_position_ms = elapsed_ms;
    state.motion_control = Some(MotionControl {
        cruise_speed_mps: 10.0,
        valid_until_ms: elapsed_ms + 100,
    });
    state.walking_valid_until_ms = Some(elapsed_ms + 100);
    state.network_evidence.reroute(elapsed_ms);
    state.turn_state = TurnState::new(elapsed_ms);
}

/// The production tick transaction is independent of how its pending computation obtains Err.
#[kani::proof]
#[kani::unwind(3)]
fn tick_transactions_commit_only_success() {
    let mut navigation = estimator();
    for event in 1..=2 {
        let before = navigation.copy_for_tick();
        let accepted: bool = kani::any();
        let position: u8 = kani::any();
        let result = navigation.try_tick(|pending| {
            change_pending(&mut pending.state, event, position);
            pending.last_gps_ms = event;
            pending.last_vehicle_input_ms = event;
            pending.network_speed_enabled = !pending.network_speed_enabled;
            pending.mode = TravelMode::Foot;
            pending.route = Arc::new(crate::route::kani_proofs::straight_route());
            pending.history.clear();
            pending.remember(None);
            if accepted {
                Ok(())
            } else {
                Err(FilterError::NonFinite)
            }
        });
        if result.is_err() {
            unchanged(&navigation, &before);
        } else {
            assert_eq!(navigation.state.elapsed_ms, event);
            assert_eq!(navigation.last_gps_ms, event);
            assert_eq!(navigation.last_vehicle_input_ms, event);
            assert_eq!(navigation.estimate().position_m, f64::from(position));
        }
        kani::cover!(result.is_ok(), "committed");
        kani::cover!(result.is_err(), "rolled back");
    }
}

/// OBD computation receives only pending FilterState; it cannot mutate history or watermarks.
#[kani::proof]
#[kani::unwind(3)]
fn state_transactions_commit_only_success() {
    let mut navigation = estimator();
    for event in 1..=2 {
        let before = navigation.copy_for_tick();
        let accepted: bool = kani::any();
        let position: u8 = kani::any();
        let result = navigation.state.try_update(|pending| {
            change_pending(pending, event, position);
            if accepted {
                Ok(())
            } else {
                Err(FilterError::InvalidCovariance)
            }
        });
        if result.is_err() {
            unchanged(&navigation, &before);
        } else {
            assert_eq!(navigation.state.elapsed_ms, event);
            assert_eq!(navigation.estimate().position_m, f64::from(position));
            assert_eq!(navigation.history.len(), before.history.len());
            assert!(navigation.history.front() == before.history.front());
            assert_eq!(
                navigation.last_vehicle_input_ms,
                before.last_vehicle_input_ms
            );
            assert_eq!(navigation.last_gps_ms, before.last_gps_ms);
        }
        kani::cover!(result.is_ok(), "committed");
        kani::cover!(result.is_err(), "rolled back");
    }
}

/// One retained checkpoint at time zero, with a current state at 500 ms, isolates rewind bookkeeping.
fn history_fixture() -> NavigationEstimator {
    let mut navigation = estimator();
    navigation.state.elapsed_ms = 500;
    navigation.state.vehicle_speed_scale = 1.01;
    navigation.state.filter.anchor_position(5.0, 20.0).unwrap();
    navigation
}

#[kani::proof]
#[kani::unwind(4)]
fn delayed_gps_error_restores_history_and_watermarks() {
    let mut navigation = history_fixture();
    let before = navigation.copy_for_tick();
    // Boundary float bit patterns are proved separately; this fixture isolates rollback.
    let coordinate = f64::NAN;
    let observation = GpsObservation {
        point: GeoPoint {
            latitude_deg: coordinate,
            longitude_deg: 30.0,
        },
        elapsed_ms: 0,
        position_accuracy_m: Some(5.0),
        speed_mps: Some(10.0),
        speed_accuracy_mps: Some(0.5),
        trust: ObservationTrust::Good,
    };
    let result = navigation.tick(500, Some(observation));
    assert!(result.is_err());
    unchanged(&navigation, &before);
    kani::cover!(result.is_err(), "rolled back");
}

fn ignored_gps(elapsed_ms: i64) {
    let mut navigation = history_fixture();
    let mut reference = navigation.copy_for_tick();
    let observation = GpsObservation {
        point: GeoPoint {
            latitude_deg: 50.0,
            longitude_deg: 30.0,
        },
        elapsed_ms,
        position_accuracy_m: Some(5.0),
        speed_mps: Some(10.0),
        speed_accuracy_mps: Some(0.5),
        trust: ObservationTrust::Good,
    };
    let result = navigation.tick(500, Some(observation)).unwrap();
    reference.tick(500, None).unwrap();
    assert!(!result.position_accepted && !result.speed_accepted && result.projection.is_none());
    unchanged(&navigation, &reference);
    kani::cover!(true, "ignored");
}

#[kani::proof]
#[kani::unwind(4)]
fn future_gps_matches_no_observation() {
    ignored_gps(2000);
}

#[kani::proof]
#[kani::unwind(4)]
fn old_gps_matches_no_observation() {
    ignored_gps(-1);
}

#[kani::proof]
#[kani::unwind(3)]
fn checkpoint_copy_preserves_complete_state() {
    let navigation = history_fixture();
    unchanged(&navigation.copy_for_tick(), &navigation);
    kani::cover!(true, "copied");
}
