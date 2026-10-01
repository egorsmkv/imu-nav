//! Regression scenarios for timestamp handling and trust gates, independent of Android/JNI.

use super::*;

fn estimator(position_sigma_m: f64) -> NavigationEstimator {
    let route = Arc::new(
        RouteGeometry::new(vec![
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.1,
                longitude_deg: 30.0,
            },
        ])
        .unwrap(),
    );
    NavigationEstimator::new(
        route,
        InitialEstimate {
            position_m: 0.0,
            speed_mps: 10.0,
            position_sigma_m,
            speed_sigma_mps: 2.0,
            systematic_drift_m: 0.0,
        },
        TravelMode::Car,
        0,
    )
    .unwrap()
}

fn gps(elapsed_ms: i64, latitude_deg: f64) -> GpsObservation {
    GpsObservation {
        point: GeoPoint {
            latitude_deg,
            longitude_deg: 30.0,
        },
        elapsed_ms,
        position_accuracy_m: Some(5.0),
        speed_mps: Some(12.0),
        speed_accuracy_mps: Some(0.5),
        trust: ObservationTrust::Good,
    }
}

#[test]
fn delayed_fix_matches_chronological_fusion_including_covariance_and_drift() {
    let mut timely = estimator(20.0);
    let mut delayed = timely.clone();
    let observation = gps(750, 50.0001);
    timely.tick(500, None).unwrap();
    delayed.tick(500, None).unwrap();
    timely.tick(1_000, Some(observation)).unwrap();
    delayed.tick(1_000, None).unwrap();
    for time in [1_200, 1_400, 1_600] {
        timely.on_vehicle_speed(43.2, time).unwrap();
        delayed.on_vehicle_speed(43.2, time).unwrap();
    }
    timely.tick(2_000, None).unwrap();
    let outcome = delayed.tick(2_000, Some(observation)).unwrap();
    assert!(outcome.position_accepted && outcome.speed_accepted);
    assert_eq!(timely.estimate(), delayed.estimate());
    assert!(outcome.estimate.position_m > outcome.projection.unwrap().position_m + 10.0);
    assert!(outcome.estimate.systematic_drift_m > 0.0);
}

#[test]
fn delayed_fix_replays_later_obd_rejection_and_its_freshness() {
    let mut timely = estimator(20.0);
    let mut delayed = timely.clone();
    let mut observation = gps(500, 50.000_045);
    observation.speed_mps = Some(0.0);
    observation.speed_accuracy_mps = Some(0.2);
    timely.tick(500, Some(observation)).unwrap();
    delayed.tick(500, None).unwrap();
    assert!(!timely.on_vehicle_speed(36.0, 600).unwrap());
    assert!(delayed.on_vehicle_speed(36.0, 600).unwrap());
    timely.tick(1_000, None).unwrap();
    delayed.tick(1_000, Some(observation)).unwrap();
    assert_eq!(timely.estimate(), delayed.estimate());
    assert_eq!(delayed.state.last_vehicle_speed_ms, -1);
}

#[test]
fn delayed_suspect_gate_uses_measurement_time_position() {
    let mut timely = estimator(100.0);
    let mut delayed = timely.clone();
    let mut observation = gps(0, 50.0);
    observation.trust = ObservationTrust::Suspect;
    // A valid old position must not be tested against a much newer position.
    timely.state.filter = RouteFilter::new(0.0, 100.0, 100.0, 2.0, 0.0).unwrap();
    timely.history.clear();
    timely.remember(None);
    delayed.clone_from(&timely);
    timely.tick(0, Some(observation)).unwrap();
    delayed.tick(4_000, None).unwrap();
    let outcome = delayed.tick(4_000, Some(observation)).unwrap();
    timely.tick(4_000, None).unwrap();
    assert!(outcome.position_accepted);
    assert_eq!(timely.estimate(), delayed.estimate());
}

#[test]
fn rejected_obd_neither_starts_nor_extends_freshness() {
    let mut navigation = estimator(10.0);
    assert!(!navigation.on_vehicle_speed(250.0, 0).unwrap());
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    assert!(navigation.on_vehicle_speed(36.0, 1_000).unwrap());
    assert!(!navigation.on_vehicle_speed(250.0, 2_000).unwrap());
    assert_eq!(navigation.state.last_vehicle_speed_ms, 1_000);
    let estimate = navigation.tick(4_000, None).unwrap().estimate;
    // 1 s estimated + 2.5 s accepted OBD + 0.5 s expired OBD, all at 10 m/s.
    assert!((estimate.systematic_drift_m - 1.7).abs() < 1.0e-9);
}

#[test]
fn duplicate_and_stale_obd_do_not_change_state_or_freshness() {
    let mut navigation = estimator(10.0);
    navigation.on_vehicle_speed(36.0, 1_000).unwrap();
    navigation.tick(1_500, None).unwrap();
    let before = navigation.estimate();
    for time in [-1, 1_000, 1_200] {
        assert!(!navigation.on_vehicle_speed(40.0, time).unwrap());
        assert_eq!(navigation.estimate(), before);
        assert_eq!(navigation.state.last_vehicle_speed_ms, 1_000);
    }
}

#[test]
fn suspect_off_route_or_large_jump_cannot_update_position_or_speed() {
    for (latitude, longitude) in [(50.0, 30.002), (50.004, 30.0)] {
        let mut navigation = estimator(1_000.0);
        let before = navigation.estimate();
        let mut observation = gps(0, latitude);
        observation.point.longitude_deg = longitude;
        observation.trust = ObservationTrust::Suspect;
        let outcome = navigation.tick(0, Some(observation)).unwrap();
        assert!(!outcome.position_accepted && !outcome.speed_accepted);
        assert!(outcome.projection.is_some());
        assert_eq!(navigation.estimate(), before);
    }
}

#[test]
fn consistent_suspect_updates_but_does_not_clear_systematic_drift() {
    let mut navigation = estimator(20.0);
    let mut observation = gps(1_000, 50.0001);
    observation.trust = ObservationTrust::Suspect;
    let outcome = navigation.tick(1_000, Some(observation)).unwrap();
    assert!(outcome.position_accepted && outcome.speed_accepted);
    assert!((outcome.estimate.systematic_drift_m - 0.8).abs() < 1.0e-9);
}

#[test]
fn good_fix_can_recover_beyond_suspect_jump_gate() {
    let mut navigation = estimator(1_000.0);
    let outcome = navigation.tick(0, Some(gps(0, 50.004))).unwrap();
    assert!(outcome.position_accepted && outcome.speed_accepted);
    assert!(outcome.estimate.position_m > SUSPECT_MAX_JUMP_M);
}

#[test]
fn future_fix_does_not_poison_timestamp_watermark() {
    let mut navigation = estimator(20.0);
    let mut reference = navigation.clone();
    let future = gps(2_000, 50.0002);
    let outcome = navigation.tick(1_000, Some(future)).unwrap();
    reference.tick(1_000, None).unwrap();
    assert!(outcome.projection.is_none());
    assert_eq!(navigation.estimate(), reference.estimate());
    let valid = gps(1_500, 50.00015);
    assert!(
        navigation
            .tick(2_000, Some(valid))
            .unwrap()
            .position_accepted
    );
    reference.tick(2_000, Some(valid)).unwrap();
    assert_eq!(navigation.estimate(), reference.estimate());
}

#[test]
fn duplicate_out_of_order_and_expired_fixes_are_ignored() {
    let mut navigation = estimator(20.0);
    navigation.tick(1_000, Some(gps(1_000, 50.0001))).unwrap();
    navigation.tick(7_000, None).unwrap();
    let before = navigation.estimate();
    for time in [500, 1_000, 1_500] {
        let outcome = navigation.tick(7_000, Some(gps(time, 50.01))).unwrap();
        assert!(outcome.projection.is_none());
        assert_eq!(navigation.estimate(), before);
    }
    navigation.tick(6_000, None).unwrap();
    assert_eq!(navigation.estimate(), before);
}

#[test]
fn reroute_discards_old_coordinate_history() {
    let mut navigation = estimator(20.0);
    navigation.tick(2_000, None).unwrap();
    navigation
        .replace_route(navigation.route.clone(), 1_000.0, 10.0)
        .unwrap();
    let before = navigation.estimate();
    let outcome = navigation.tick(2_000, Some(gps(1_500, 50.00015))).unwrap();
    assert!(outcome.projection.is_none());
    assert_eq!(navigation.estimate(), before);
}

#[test]
fn invalid_delayed_fix_leaves_history_and_watermarks_usable() {
    let mut navigation = estimator(20.0);
    navigation.tick(1_000, None).unwrap();
    let mut reference = navigation.clone();
    let mut invalid = gps(500, f64::NAN);
    assert!(navigation.tick(2_000, Some(invalid)).is_err());
    assert_eq!(navigation.estimate(), reference.estimate());
    invalid.point.latitude_deg = 50.00005;
    navigation.tick(2_000, Some(invalid)).unwrap();
    reference.tick(2_000, Some(invalid)).unwrap();
    assert_eq!(navigation.estimate(), reference.estimate());
}

#[test]
fn history_is_bounded_and_old_fixes_cannot_rewind_past_it() {
    let mut navigation = estimator(20.0);
    for time in 1..=1_000 {
        navigation.tick(time, None).unwrap();
    }
    assert!(navigation.history.len() <= MAX_HISTORY_FRAMES);
    let before = navigation.estimate();
    let outcome = navigation.tick(1_000, Some(gps(1, 50.0))).unwrap();
    assert!(outcome.projection.is_none());
    assert_eq!(navigation.estimate(), before);
}

#[test]
fn rejected_delayed_fix_does_not_change_covariance_or_later_obd() {
    let mut navigation = estimator(20.0);
    navigation.tick(500, None).unwrap();
    navigation.on_vehicle_speed(36.0, 1_200).unwrap();
    navigation.tick(1_500, None).unwrap();
    let mut reference = navigation.clone();
    let mut observation = gps(750, 50.0001);
    observation.trust = ObservationTrust::Suspect;
    observation.point.longitude_deg = 30.002;
    let outcome = navigation.tick(2_000, Some(observation)).unwrap();
    reference.tick(2_000, None).unwrap();
    assert!(!outcome.position_accepted && !outcome.speed_accepted);
    assert_eq!(navigation.estimate(), reference.estimate());
}

#[test]
fn long_tick_gap_accounts_for_all_elapsed_travel() {
    let mut navigation = estimator(20.0);
    let outcome = navigation.tick(12_000, None).unwrap();
    assert!((outcome.estimate.position_m - 120.0).abs() < 1.0e-9);
    assert!((outcome.estimate.systematic_drift_m - 9.6).abs() < 1.0e-9);
}
