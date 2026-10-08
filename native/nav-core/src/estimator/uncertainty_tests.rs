//! Invalid uncertainties must fail before flooring, with complete transactional rollback.
use super::regression_tests::{estimator, gps};
use super::*;

fn invalid_sigmas() -> [(f64, FilterError); 4] {
    [
        (f64::NAN, FilterError::NonFinite),
        (f64::INFINITY, FilterError::NonFinite),
        (f64::NEG_INFINITY, FilterError::NonFinite),
        (-1.0, FilterError::InvalidSigma),
    ]
}

#[test]
fn initial_position_uncertainty_is_validated_before_flooring() {
    for mode in [TravelMode::Car, TravelMode::Foot] {
        for (sigma, error) in invalid_sigmas() {
            let reference = estimator(20.0);
            let result = NavigationEstimator::new(
                reference.route,
                InitialEstimate {
                    position_m: 0.0,
                    speed_mps: 10.0,
                    position_sigma_m: sigma,
                    speed_sigma_mps: 2.0,
                    systematic_drift_m: 5.0,
                },
                mode,
                0,
            );
            assert_eq!(result.unwrap_err(), error);
        }
    }
}

#[test]
fn invalid_reroute_uncertainty_preserves_route_history_and_filter() {
    let mut navigation = estimator(20.0);
    navigation.on_vehicle_speed(36.0, 500).unwrap();
    navigation.tick(1_000, None).unwrap();
    let replacement = Arc::new(
        RouteGeometry::new(vec![
            GeoPoint {
                latitude_deg: 49.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 49.1,
                longitude_deg: 30.0,
            },
        ])
        .unwrap(),
    );
    let before = navigation.clone();
    for (sigma, error) in invalid_sigmas() {
        assert_eq!(
            navigation.replace_route(replacement.clone(), 100.0, sigma),
            Err(error)
        );
        assert_eq!(navigation, before);
        assert!(Arc::ptr_eq(&navigation.route, &before.route));
    }
}

#[test]
fn invalid_gps_uncertainty_rolls_back_delayed_replay_and_watermarks() {
    for speed_accuracy in [false, true] {
        for trust in [ObservationTrust::Good, ObservationTrust::Suspect] {
            for (sigma, error) in invalid_sigmas() {
                let mut navigation = estimator(20.0);
                navigation.tick(500, None).unwrap();
                navigation.on_vehicle_speed(36.0, 800).unwrap();
                navigation.tick(1_000, None).unwrap();
                let before = navigation.clone();
                let mut observation = gps(750, 50.0001);
                observation.trust = trust;
                if speed_accuracy {
                    observation.speed_accuracy_mps = Some(sigma);
                } else {
                    observation.position_accuracy_m = Some(sigma);
                }
                assert_eq!(navigation.tick(1_200, Some(observation)), Err(error));
                assert_eq!(navigation, before);
                // The rejected timestamp must still accept a corrected observation.
                let mut valid = gps(750, 50.0001);
                valid.trust = trust;
                let actual = navigation.tick(1_200, Some(valid)).unwrap();
                let mut reference = before;
                assert_eq!(actual, reference.tick(1_200, Some(valid)).unwrap());
            }
        }
    }
}

#[test]
fn zero_small_and_missing_uncertainties_keep_existing_defaults_and_floors() {
    for accuracy in [Some(0.0), Some(0.01), None] {
        let mut navigation = estimator(20.0);
        let mut reference = navigation.clone();
        let mut observation = gps(1_000, 50.0001);
        observation.position_accuracy_m = accuracy;
        observation.speed_accuracy_mps = accuracy;
        let mut explicit = observation;
        explicit.position_accuracy_m = Some(
            accuracy
                .unwrap_or(DEFAULT_GPS_POSITION_SIGMA_M)
                .max(MIN_POSITION_SIGMA_M),
        );
        explicit.speed_accuracy_mps = Some(
            accuracy
                .unwrap_or(DEFAULT_GPS_SPEED_SIGMA_MPS)
                .max(MIN_SPEED_SIGMA_MPS),
        );
        assert_eq!(
            navigation.tick(1_000, Some(observation)).unwrap(),
            reference.tick(1_000, Some(explicit)).unwrap()
        );
    }
    assert_eq!(
        estimator(0.0).estimate().covariance.position,
        MIN_POSITION_SIGMA_M * MIN_POSITION_SIGMA_M
    );
}
