use super::*;

fn route() -> Arc<RouteGeometry> {
    Arc::new(
        RouteGeometry::new(vec![
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.02,
                longitude_deg: 30.0,
            },
        ])
        .unwrap(),
    )
}

#[test]
fn trusted_gps_then_blind_prediction_updates_state_and_safety() {
    let initial = InitialEstimate {
        position_m: 0.0,
        speed_mps: 10.0,
        position_sigma_m: 10.0,
        speed_sigma_mps: 2.0,
        systematic_drift_m: 0.0,
    };
    let mut estimator = NavigationEstimator::new(route(), initial, TravelMode::Car, 0).unwrap();
    let gps = GpsObservation {
        point: GeoPoint {
            latitude_deg: 50.0001,
            longitude_deg: 30.0,
        },
        elapsed_ms: 1_000,
        position_accuracy_m: Some(5.0),
        speed_mps: Some(10.0),
        speed_accuracy_mps: Some(0.5),
        trust: ObservationTrust::Good,
    };
    let anchored = estimator.tick(1_000, Some(gps)).unwrap();
    assert!(anchored.position_accepted);
    assert!(anchored.speed_accepted);
    assert!(anchored.estimate.systematic_drift_m.abs() < f64::EPSILON);
    let blind = estimator.tick(6_000, None).unwrap();
    assert!(blind.estimate.position_m > anchored.estimate.position_m + 45.0);
    assert!(blind.estimate.systematic_drift_m > 3.5);
}

#[test]
fn obd_reduces_systematic_drift_rate() {
    let initial = InitialEstimate {
        position_m: 0.0,
        speed_mps: 10.0,
        position_sigma_m: 10.0,
        speed_sigma_mps: 2.0,
        systematic_drift_m: 0.0,
    };
    let mut estimator = NavigationEstimator::new(route(), initial, TravelMode::Car, 0).unwrap();
    assert!(estimator.on_vehicle_speed(36.0, 1_000).unwrap());
    let estimate = estimator.tick(2_000, None).unwrap().estimate;
    // Travel before the first accepted OBD reading still uses the estimated-speed allowance.
    assert!((estimate.systematic_drift_m - 1.0).abs() < 0.05);
}
