//! Synthetic observations of the public production APIs. Completion is not a correctness verdict.
use imu_nav_core::RouteFilter;
use imu_nav_core::estimator::{
    GpsObservation, InitialEstimate, NavigationEstimator, ObservationTrust, TravelMode,
};
use imu_nav_core::route::{GeoPoint, RouteGeometry};
use std::sync::Arc;

fn main() {
    inspect_prediction_partition();
    inspect_stationary_speed();
    inspect_constructor_range();
    inspect_date_line_projection();
}

/// A stationary GPS residual still produces signed velocity; the app bridge publishes forward speed.
fn inspect_stationary_speed() {
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
    for measured_speed in [None, Some(0.0)] {
        let mut estimator = NavigationEstimator::new(
            Arc::clone(&route),
            InitialEstimate {
                position_m: 100.0,
                speed_mps: 0.0,
                position_sigma_m: 3.0,
                speed_sigma_mps: 6.0,
                systematic_drift_m: 0.0,
            },
            TravelMode::Car,
            0,
        )
        .unwrap();
        let location = route
            .project(
                GeoPoint {
                    latitude_deg: 50.0 + 90.0 / 111_194.926_644_558_74,
                    longitude_deg: 30.0,
                },
                90.0,
                1000.0,
                1000.0,
                120.0,
            )
            .unwrap();
        let outcome = estimator
            .tick(
                5000,
                Some(GpsObservation {
                    point: location.point,
                    elapsed_ms: 5000,
                    position_accuracy_m: Some(3.0),
                    speed_mps: measured_speed,
                    speed_accuracy_mps: Some(0.2),
                    trust: ObservationTrust::Good,
                }),
            )
            .unwrap();
        println!(
            "native_stationary measured_speed={measured_speed:?} position_accepted={} speed_accepted={} estimate={:?}",
            outcome.position_accepted, outcome.speed_accepted, outcome.estimate
        );
    }
}

/// Identical deterministic motion with no measurements isolates callback-dependent process noise.
fn inspect_prediction_partition() {
    let route = Arc::new(
        RouteGeometry::new(vec![
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 51.0,
                longitude_deg: 30.0,
            },
        ])
        .unwrap(),
    );
    for step_ms in [100, 200, 500, 1000, 5000] {
        let initial = InitialEstimate {
            position_m: 0.0,
            speed_mps: 15.0,
            position_sigma_m: 25.0,
            speed_sigma_mps: 6.0,
            systematic_drift_m: 0.0,
        };
        let mut estimator =
            NavigationEstimator::new(Arc::clone(&route), initial, TravelMode::Car, 0).unwrap();
        for now_ms in (step_ms..=60_000).step_by(usize::try_from(step_ms).unwrap()) {
            estimator.tick(now_ms, None).unwrap();
        }
        let estimate = estimator.estimate();
        println!(
            "native_estimator step_ms={step_ms} position_m={} position_variance={} speed_variance={} drift_m={} radius_m={}",
            estimate.position_m,
            estimate.covariance.position,
            estimate.covariance.speed,
            estimate.systematic_drift_m,
            estimate.safety_radius_m(2.0).unwrap()
        );
    }
}

/// Both variances are finite individually; their product overflows the validator's determinant.
fn inspect_constructor_range() {
    let mut filter = RouteFilter::new(0.0, 1.0, 1.0e100, 1.0e100, 0.0).unwrap();
    println!(
        "route_initial_huge covariance={:?} zero_predict={:?}",
        filter.estimate().covariance,
        filter.predict(0.0, 0.0, 0.0)
    );
}

/// Inspect the midpoint projection of a valid 219-metre date-line segment.
fn inspect_date_line_projection() {
    let route = RouteGeometry::new(vec![
        GeoPoint {
            latitude_deg: 10.0,
            longitude_deg: 179.999,
        },
        GeoPoint {
            latitude_deg: 10.0,
            longitude_deg: -179.999,
        },
    ])
    .unwrap();
    let projection = route
        .project(
            GeoPoint {
                latitude_deg: 10.0,
                longitude_deg: 180.0,
            },
            route.length_m() / 2.0,
            route.length_m(),
            route.length_m(),
            120.0,
        )
        .unwrap();
    println!(
        "native_dateline length_m={} projection={projection:?}",
        route.length_m()
    );
}
