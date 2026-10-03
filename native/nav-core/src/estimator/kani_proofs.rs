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

/// Every signed timestamp, restricted only by the scheduler's reachable-state preconditions.
#[kani::proof]
#[kani::unwind(2)]
fn prediction_boundaries_advance_and_split_at_expiry() {
    let now: i64 = kani::any();
    let target: i64 = kani::any();
    let last_obd: i64 = kani::any();
    let motion: Option<i64> = kani::any();
    let walking: Option<i64> = kani::any();
    kani::assume(now < target);
    kani::assume(last_obd == -1 || (last_obd >= 0 && last_obd <= now));
    kani::assume(motion.is_none_or(|expiry| expiry > now));
    kani::assume(walking.is_none_or(|expiry| expiry > now));
    let (end, fresh) = timing::prediction_step(now, target, last_obd, motion, walking);
    assert!(now < end && end <= target);
    assert!(i128::from(end) - i128::from(now) <= i128::from(MAX_PREDICTION_MS));
    assert!(motion.is_none_or(|expiry| end <= expiry));
    assert!(walking.is_none_or(|expiry| end <= expiry));
    let expiry = last_obd.saturating_add(OBD_MAX_AGE_MS);
    if fresh {
        assert!(last_obd >= 0 && now < expiry && end <= expiry);
    } else {
        assert!(last_obd == -1 || now >= expiry);
    }
    kani::cover!(fresh, "fresh");
    kani::cover!(last_obd >= 0 && now == expiry && !fresh, "expired");
    kani::cover!(motion == Some(end), "motion boundary");
    kani::cover!(walking == Some(end), "walking boundary");
    kani::cover!(end == i64::MAX, "integer limit");
}

#[kani::proof]
#[kani::unwind(2)]
fn gps_time_gate_obeys_history_and_watermarks() {
    let measurement: i64 = kani::any();
    let now: i64 = kani::any();
    let last: i64 = kani::any();
    let oldest: i64 = kani::any();
    let initial: bool = kani::any();
    let eligible = timing::gps_time_is_eligible(measurement, now, last, oldest, initial);
    let age = i128::from(now) - i128::from(measurement);
    let expected = measurement > last
        && (0..=i128::from(GPS_HISTORY_MS)).contains(&age)
        && (measurement > oldest || (measurement == oldest && initial));
    assert_eq!(eligible, expected);
    kani::cover!(eligible, "accepted");
    kani::cover!(!eligible && measurement > now, "future");
    kani::cover!(!eligible && measurement == last, "duplicate");
    kani::cover!(
        eligible && age == i128::from(GPS_HISTORY_MS),
        "history boundary"
    );
    kani::cover!(
        eligible && measurement == oldest && initial,
        "initial checkpoint"
    );
}

#[kani::proof]
#[kani::unwind(3)]
fn vehicle_time_gate_rejects_stale_samples() {
    let now: i64 = kani::any();
    let last: i64 = kani::any();
    let observation: i64 = kani::any();
    let eligible = timing::vehicle_time_is_eligible(observation, now, last);
    if observation < now || observation <= last {
        assert!(!eligible);
    } else {
        assert!(eligible);
    }
    kani::cover!(observation == last && !eligible, "duplicate");
    kani::cover!(observation < now && !eligible, "stale");
    kani::cover!(observation == now && eligible, "same time accepted");
}

/// The arithmetic step is proved separately from measurement eligibility and projection.
#[kani::proof]
#[kani::unwind(2)]
fn calibration_blend_stays_in_valid_interval() {
    let previous = f64::from_bits(kani::any());
    let ratio = f64::from_bits(kani::any());
    kani::assume((SCALE_MIN_RATIO..=SCALE_MAX_RATIO).contains(&previous));
    kani::assume((SCALE_MIN_RATIO..=SCALE_MAX_RATIO).contains(&ratio));
    let updated = calibration::blend_scale(previous, ratio);
    assert!(updated.is_finite());
    assert!((SCALE_MIN_RATIO..=SCALE_MAX_RATIO).contains(&updated));
    assert!(updated >= previous.min(ratio) && updated <= previous.max(ratio));
    kani::cover!(updated > previous, "increased");
    kani::cover!(updated < previous, "decreased");
}

fn calibration_observation(elapsed_ms: i64) -> GpsObservation {
    GpsObservation {
        point: GeoPoint {
            latitude_deg: 50.0,
            longitude_deg: 30.0,
        },
        elapsed_ms,
        position_accuracy_m: Some(5.0),
        speed_mps: Some(16.0),
        speed_accuracy_mps: Some(0.5),
        trust: ObservationTrust::Good,
    }
}

/// Symbolic eligibility inputs exercise the same method called after both GPS fusion results.
#[kani::proof]
#[kani::unwind(3)]
fn calibration_requires_accepted_precise_fresh_good_gps() {
    let mut state = estimator().state;
    let since: i64 = kani::any();
    let latest: i64 = kani::any();
    kani::assume(since >= 0 && latest >= since);
    let stable = StableVehicleSpeed {
        since_ms: since,
        latest_ms: latest,
        latest_mps: 15.0,
        min_mps: 15.0,
        max_mps: 15.0,
    };
    state.stable_vehicle_speed = if kani::any() { Some(stable) } else { None };
    let mut observation = calibration_observation(kani::any());
    observation.trust = if kani::any() {
        ObservationTrust::Good
    } else {
        ObservationTrust::Suspect
    };
    observation.position_accuracy_m = kani::any::<Option<u64>>().map(f64::from_bits);
    observation.speed_accuracy_mps = kani::any::<Option<u64>>().map(f64::from_bits);
    if kani::any() {
        observation.speed_mps = None;
    }
    let mode = if kani::any() {
        TravelMode::Car
    } else {
        TravelMode::Foot
    };
    let accepted: (bool, bool) = kani::any();
    let offset = f64::from(kani::any::<u8>());
    let before = state.clone();
    let learned = state.learn_vehicle_speed_scale(mode, observation, offset, accepted);
    let age = i128::from(observation.elapsed_ms) - i128::from(latest);
    let eligible = accepted == (true, true)
        && observation.trust == ObservationTrust::Good
        && mode == TravelMode::Car
        && before.stable_vehicle_speed.is_some()
        && observation.speed_mps.is_some()
        && observation.speed_accuracy_mps.is_some_and(|sigma| {
            sigma.is_finite() && (0.0..=SCALE_MAX_GPS_SIGMA_MPS).contains(&sigma)
        })
        && observation.position_accuracy_m.is_some_and(|sigma| {
            sigma.is_finite() && (0.0..=SCALE_MAX_GPS_SIGMA_M).contains(&sigma)
        })
        && offset < SCALE_MAX_OFFSET_M
        && i128::from(latest) - i128::from(since) >= i128::from(SCALE_STABLE_MS)
        && (0..=i128::from(SCALE_MAX_SAMPLE_AGE_MS)).contains(&age);
    assert_eq!(learned, eligible);
    if learned {
        assert!(state.vehicle_speed_scale > before.vehicle_speed_scale);
        assert!(state.vehicle_speed_scale <= SCALE_MAX_RATIO);
        state.vehicle_speed_scale = before.vehicle_speed_scale;
    }
    assert!(state == before);
    kani::cover!(learned, "learned");
    kani::cover!(
        !learned && observation.trust == ObservationTrust::Suspect,
        "suspect"
    );
    kani::cover!(!learned && !accepted.0, "position rejected");
    kani::cover!(!learned && !accepted.1, "speed rejected");
    kani::cover!(learned && age == 250, "freshness boundary");
}

#[kani::proof]
#[kani::unwind(2)]
fn obd_plateau_restarts_after_gap_or_acceleration() {
    let since: i64 = kani::any();
    let latest: i64 = kani::any();
    let elapsed: i64 = kani::any();
    kani::assume(0 <= since && since <= latest && latest < elapsed);
    let speed = f64::from(kani::any::<u8>()) / 4.0;
    let stable = StableVehicleSpeed {
        since_ms: since,
        latest_ms: latest,
        latest_mps: 15.0,
        min_mps: 15.0,
        max_mps: 15.0,
    };
    let next = stable.add(elapsed, speed);
    let gap = i128::from(elapsed) - i128::from(latest);
    let restart =
        gap > i128::from(SCALE_MAX_OBD_GAP_MS) || (speed - 15.0).abs() > SCALE_STABLE_RANGE_MPS;
    assert_eq!(next.latest_ms, elapsed);
    assert_eq!(next.latest_mps, speed);
    if restart {
        assert_eq!(next.since_ms, elapsed);
        assert_eq!(next.min_mps, speed);
        assert_eq!(next.max_mps, speed);
    } else {
        assert_eq!(next.since_ms, since);
        assert!(next.min_mps <= speed && next.max_mps >= speed);
        assert!(next.max_mps - next.min_mps <= SCALE_STABLE_RANGE_MPS);
    }
    kani::cover!(restart && gap > 1000, "gap");
    kani::cover!(restart && gap <= 1000, "acceleration");
    kani::cover!(!restart && gap == 1000, "stable boundary");
}

/// Exercise the actual hint setter, expiry handler and replay of the same expired hint.
#[kani::proof]
#[kani::unwind(3)]
fn walking_hint_expiry_cannot_refresh_itself() {
    let mut navigation = estimator();
    navigation.mode = TravelMode::Foot;
    let now: i64 = kani::any();
    let expiry: i64 = kani::any();
    kani::assume(now >= 0);
    navigation.state.elapsed_ms = now;
    let before = navigation.estimate();
    let hint = WalkingObservation {
        speed_mps: 2.0,
        valid_until_ms: expiry,
    };
    navigation.apply_walking(Some(hint)).unwrap();
    let remaining = i128::from(expiry) - i128::from(now);
    let valid = (1..=2500).contains(&remaining);
    assert_eq!(
        navigation.state.walking_valid_until_ms,
        valid.then_some(expiry)
    );
    assert_eq!(
        navigation.estimate().speed_mps,
        if valid { 2.0 } else { 0.0 }
    );
    if valid {
        navigation.apply_walking(Some(hint)).unwrap();
        assert_eq!(navigation.state.walking_valid_until_ms, Some(expiry));
        navigation.state.elapsed_ms = expiry;
        navigation.state.expire_walking(TravelMode::Foot).unwrap();
        navigation.apply_walking(Some(hint)).unwrap();
        assert_eq!(navigation.estimate().speed_mps, 0.0);
        assert!(navigation.state.walking_valid_until_ms.is_none());
    }
    assert_eq!(navigation.estimate().position_m, before.position_m);
    assert_eq!(
        navigation.estimate().covariance.position,
        before.covariance.position
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        before.systematic_drift_m
    );
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    kani::cover!(valid, "expires");
    kani::cover!(!valid, "rejected");
}

#[kani::proof]
#[kani::unwind(3)]
fn motion_hint_expiry_cannot_refresh_itself() {
    let mut navigation = estimator();
    let now: i64 = kani::any();
    let expiry: i64 = kani::any();
    kani::assume(now >= 0);
    navigation.state.elapsed_ms = now;
    let before = navigation.estimate();
    let hint = MotionObservation {
        factor: 0.0,
        cruise_speed_mps: 10.0,
        valid_until_ms: expiry,
        network_moving: false,
    };
    navigation.apply_motion(Some(hint)).unwrap();
    let remaining = i128::from(expiry) - i128::from(now);
    let valid = (1..=2000).contains(&remaining);
    assert_eq!(
        navigation
            .state
            .motion_control
            .map(|control| control.valid_until_ms),
        valid.then_some(expiry)
    );
    if valid {
        assert_eq!(navigation.estimate().speed_mps, 0.0);
        navigation.apply_motion(Some(hint)).unwrap();
        assert_eq!(
            navigation.state.motion_control.unwrap().valid_until_ms,
            expiry
        );
        navigation.state.elapsed_ms = expiry;
        navigation.apply_motion(Some(hint)).unwrap();
        assert!(navigation.state.motion_control.is_none());
        assert_eq!(navigation.estimate().speed_mps, before.speed_mps);
    }
    assert_eq!(navigation.estimate().position_m, before.position_m);
    assert_eq!(
        navigation.estimate().covariance.position,
        before.covariance.position
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        before.systematic_drift_m
    );
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    kani::cover!(valid, "expires");
    kani::cover!(!valid, "rejected");
}

/// Real numerical prediction crosses the OBD boundary: 0.5 s trusted, then 0.5 s estimated.
#[kani::proof]
#[kani::unwind(3)]
fn prediction_drops_obd_allowance_at_expiry() {
    let mut state = estimator().state;
    state.elapsed_ms = 2000;
    state.last_vehicle_speed_ms = 0;
    let before = state.filter.estimate();
    state.predict_to(3000, TravelMode::Car).unwrap();
    let after = state.filter.estimate();
    assert_eq!(state.elapsed_ms, 3000);
    assert_eq!(state.last_vehicle_speed_ms, 0);
    assert_eq!(after.position_m - before.position_m, 10.0);
    assert!((after.systematic_drift_m - before.systematic_drift_m - 0.5).abs() < 1.0e-12);
    kani::cover!(true, "crossed expiry");
}

/// Bounded physical speed domain includes low speed, both ratio limits and outliers.
#[kani::proof]
#[kani::unwind(3)]
fn calibration_speed_and_ratio_gates() {
    let mut state = estimator().state;
    let obd_speed = f64::from(kani::any::<u8>()) / 4.0;
    let gps_speed = f64::from(kani::any::<u8>()) / 4.0;
    state.stable_vehicle_speed = Some(StableVehicleSpeed {
        since_ms: 0,
        latest_ms: 3000,
        latest_mps: obd_speed,
        min_mps: obd_speed,
        max_mps: obd_speed,
    });
    let mut observation = calibration_observation(3000);
    observation.speed_mps = Some(gps_speed);
    let before = state.clone();
    let learned = state.learn_vehicle_speed_scale(TravelMode::Car, observation, 0.0, (true, true));
    if learned {
        assert!(obd_speed >= 5.0 && gps_speed >= 5.0);
        assert!((0.8..=1.2).contains(&(gps_speed / obd_speed)));
        assert!(state.vehicle_speed_scale.is_finite());
        assert!((0.8..=1.2).contains(&state.vehicle_speed_scale));
        state.vehicle_speed_scale = before.vehicle_speed_scale;
    } else if obd_speed >= 5.0 && gps_speed >= 5.0 {
        assert!(!(0.8..=1.2).contains(&(gps_speed / obd_speed)));
    }
    assert!(state == before);
    kani::cover!(
        learned && gps_speed == 8.0 && obd_speed == 10.0,
        "lower ratio"
    );
    kani::cover!(
        learned && gps_speed == 12.0 && obd_speed == 10.0,
        "upper ratio"
    );
    kani::cover!(!learned && obd_speed < 5.0, "low speed");
    kani::cover!(
        !learned && obd_speed >= 5.0 && gps_speed > 2.0 * obd_speed,
        "outlier"
    );
}
