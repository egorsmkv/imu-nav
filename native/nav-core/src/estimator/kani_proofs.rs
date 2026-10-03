//! Compositional transaction and checkpoint ownership proofs.
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

/// Fixed-size inspection avoids allocating another deque just to remember expected values.
fn snapshot(navigation: &NavigationEstimator) -> impl PartialEq + use<> {
    let NavigationEstimator {
        state,
        route,
        mode,
        last_gps_ms,
        last_vehicle_input_ms,
        network_speed_enabled,
        history,
    } = navigation;
    assert!(history.len() <= 3);
    (
        state.clone(),
        Arc::as_ptr(route),
        *mode,
        *last_gps_ms,
        *last_vehicle_input_ms,
        *network_speed_enabled,
        history.len(),
        (
            history.front().cloned(),
            history.get(1).cloned(),
            history.get(2).cloned(),
        ),
    )
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
fn tick_transaction(accepted: bool) {
    let mut navigation = estimator();
    let event = 1;
    let before = snapshot(&navigation);
    let position: u8 = kani::any();
    let result = navigation.try_tick(|pending| {
        change_pending(&mut pending.state, event, position);
        pending.last_gps_ms = event;
        pending.last_vehicle_input_ms = event;
        pending.network_speed_enabled = !pending.network_speed_enabled;
        pending.mode = TravelMode::Foot;
        pending.history.front_mut().unwrap().state = pending.state.clone();
        if accepted {
            Ok(())
        } else {
            Err(FilterError::NonFinite)
        }
    });
    if result.is_err() {
        assert!(snapshot(&navigation) == before);
    } else {
        assert_eq!(navigation.state.elapsed_ms, event);
        assert_eq!(navigation.last_gps_ms, event);
        assert_eq!(navigation.last_vehicle_input_ms, event);
        assert_eq!(navigation.estimate().position_m, f64::from(position));
    }
    assert_eq!(result.is_ok(), accepted);
}

#[kani::proof]
#[kani::unwind(3)]
fn failed_tick_transaction_preserves_state() {
    tick_transaction(false);
    kani::cover!(true, "rolled back");
}

#[kani::proof]
#[kani::unwind(3)]
fn successful_tick_transaction_commits_state() {
    tick_transaction(true);
    kani::cover!(true, "committed");
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
#[kani::unwind(3)]
fn checkpoint_copy_preserves_complete_state() {
    let navigation = history_fixture();
    unchanged(&navigation.copy_for_tick(), &navigation);
    kani::cover!(true, "copied");
}
