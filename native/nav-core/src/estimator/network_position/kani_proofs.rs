//! Complete state inspection without slice-equality loops inflating unrelated algorithm bounds.
//! This compares the same fields as derived PartialEq; no production operation is replaced.
use super::NetworkEvidence;

impl PartialEq for NetworkEvidence {
    fn eq(&self, other: &Self) -> bool {
        // Exhaustive destructuring forces a proof update whenever a state field is added.
        let Self {
            valid_after_ms,
            last_input_ms,
            last_evaluated_ms,
            seen,
            next_seen,
            candidate,
            speed,
        } = self;
        (
            valid_after_ms,
            last_input_ms,
            last_evaluated_ms,
            (
                &seen[0], &seen[1], &seen[2], &seen[3], &seen[4], &seen[5], &seen[6], &seen[7],
            ),
            next_seen,
            candidate,
            speed,
        ) == (
            &other.valid_after_ms,
            &other.last_input_ms,
            &other.last_evaluated_ms,
            (
                &other.seen[0],
                &other.seen[1],
                &other.seen[2],
                &other.seen[3],
                &other.seen[4],
                &other.seen[5],
                &other.seen[6],
                &other.seen[7],
            ),
            &other.next_seen,
            &other.candidate,
            &other.speed,
        )
    }
}

use super::*;
use crate::estimator::kani_proofs::{estimator, snapshot};

fn observation(elapsed_ms: i64) -> NetworkObservation {
    NetworkObservation {
        point: GeoPoint {
            latitude_deg: 50.0,
            longitude_deg: 30.0,
        },
        elapsed_ms,
        accuracy_m: 30.0,
    }
}

/// Populated bookkeeping checks that rejected ingress preserves candidates, caches and samples.
fn populated() -> NetworkEvidence {
    let mut evidence = NetworkEvidence::new(1000);
    evidence.last_input_ms = 9000;
    evidence.last_evaluated_ms = 9000;
    evidence.seen[0] = Some(observation(9000).point);
    evidence.next_seen = 1;
    evidence.candidate = Some(candidate(9000, 100.0));
    evidence.speed = speed::kani_proofs::partial_batch();
    evidence
}

fn candidate(last_ms: i64, position_m: f64) -> Candidate {
    Candidate {
        first_ms: 1000,
        last_ms,
        position_m,
        accuracy_m: 30.0,
        residual_m: 100.0,
        count: 3,
    }
}

#[kani::proof]
#[kani::unwind(10)]
fn invalid_network_ingress_preserves_all_state() {
    let mut navigation = estimator();
    navigation.state.elapsed_ms = 11_500;
    navigation.state.network_evidence = populated();
    let walking: bool = kani::any();
    if walking {
        navigation.mode = TravelMode::Foot;
    }
    let input = NetworkObservation {
        point: GeoPoint {
            latitude_deg: f64::from_bits(kani::any()),
            longitude_deg: f64::from_bits(kani::any()),
        },
        elapsed_ms: kani::any(),
        accuracy_m: f64::from_bits(kani::any()),
    };
    let age = 11_500_i128 - i128::from(input.elapsed_ms);
    let invalid = walking
        || !(0..=2500).contains(&age)
        || input.elapsed_ms <= 9000
        || !(input.accuracy_m > 0.0 && input.accuracy_m <= 200.0)
        || !(-90.0..=90.0).contains(&input.point.latitude_deg)
        || !(-180.0..=180.0).contains(&input.point.longitude_deg);
    kani::assume(invalid);
    let before = snapshot(&navigation);
    assert!(!navigation.apply_network(Some(input), None).unwrap());
    assert!(snapshot(&navigation) == before);
    kani::cover!(walking, "walking");
    kani::cover!(input.elapsed_ms > 11_500, "future");
    kani::cover!(input.elapsed_ms == 9000, "duplicate time");
    kani::cover!(age > 2500, "stale");
    kani::cover!(input.accuracy_m.is_nan(), "invalid number");
}

#[kani::proof]
#[kani::unwind(10)]
fn cached_and_too_frequent_fixes_cannot_add_evidence() {
    let mut navigation = estimator();
    navigation.state.elapsed_ms = 11_500;
    navigation.state.network_evidence = populated();
    let cached: bool = kani::any();
    let elapsed_ms = i64::from(kani::any::<u16>());
    kani::assume((9001..=11_500).contains(&elapsed_ms));
    let mut input = observation(elapsed_ms);
    if !cached {
        input.point.latitude_deg = 50.01;
    }
    let before = snapshot(&navigation);
    assert!(!navigation.apply_network(Some(input), None).unwrap());
    assert_eq!(navigation.state.network_evidence.last_input_ms, elapsed_ms);
    // Ingress consumes its watermark even when duplicate/rate protection rejects evidence.
    navigation.state.network_evidence.last_input_ms = 9000;
    assert!(snapshot(&navigation) == before);
    kani::cover!(cached, "cached coordinate");
    kani::cover!(!cached, "rate limited");
}

#[kani::proof]
#[kani::unwind(10)]
fn reroute_discards_learning_but_keeps_duplicate_protection() {
    let mut evidence = populated();
    let now: i64 = kani::any();
    kani::assume(now >= evidence.last_input_ms);
    let before = evidence.clone();
    evidence.reroute(now);
    assert_eq!(evidence.valid_after_ms, now);
    assert!(evidence.candidate.is_none());
    assert!(evidence.speed == SpeedBatch::new());
    evidence.valid_after_ms = before.valid_after_ms;
    evidence.candidate = before.candidate;
    evidence.speed = before.speed.clone();
    assert!(evidence == before);
    kani::cover!(now == i64::MAX, "integer limit");
    kani::cover!(now == 9000, "same time");
}

#[kani::proof]
#[kani::unwind(10)]
fn measured_speed_and_motion_veto_discard_network_learning() {
    let mut navigation = estimator();
    navigation.state.elapsed_ms = 31_000;
    navigation.network_speed_enabled = true;
    navigation.state.network_evidence.speed = speed::kani_proofs::partial_batch();
    let reason: u8 = kani::any();
    kani::assume(reason < 5);
    let age = i64::from(kani::any::<u16>());
    kani::assume(age <= 2500 && (reason != 2 || age < 2500));
    let measured = 31_000 - age;
    match reason {
        0 => navigation.state.last_gps_position_ms = measured,
        1 => navigation.state.last_gps_speed_ms = measured,
        2 => navigation.state.last_vehicle_speed_ms = measured,
        3 => (),
        _ => navigation.network_speed_enabled = false,
    }
    let motion = (reason == 3).then_some(MotionObservation {
        factor: 0.0,
        cruise_speed_mps: 10.0,
        valid_until_ms: 32_000,
        network_moving: false,
    });
    let before = navigation.state.clone();
    let accepted = navigation
        .apply_network_estimates(candidate(31_000, 100.0), 0, motion)
        .unwrap();
    assert!(navigation.state.network_evidence.speed == SpeedBatch::new());
    assert_eq!(
        navigation.estimate().speed_mps,
        before.filter.estimate().speed_mps
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        before.filter.estimate().systematic_drift_m
    );
    assert_eq!(
        navigation.state.last_gps_position_ms,
        before.last_gps_position_ms
    );
    assert_eq!(navigation.state.last_gps_speed_ms, before.last_gps_speed_ms);
    assert_eq!(
        navigation.state.last_vehicle_speed_ms,
        before.last_vehicle_speed_ms
    );
    assert_eq!(accepted, reason != 0);
    kani::cover!(reason == 0 && age == 2500, "gps position boundary");
    kani::cover!(reason == 1 && age == 2500, "gps speed boundary");
    kani::cover!(reason == 2 && age == 2499, "obd boundary");
    kani::cover!(reason == 3, "stop");
    kani::cover!(reason == 4, "disabled");
}

/// Projection is separate; test the position path's evidence minimum and channel isolation.
#[kani::proof]
#[kani::unwind(3)]
fn position_correction_requires_count_and_span() {
    let mut navigation = estimator();
    navigation.network_speed_enabled = false;
    navigation.state.elapsed_ms = 31_000;
    let count: u8 = kani::any();
    let span = i64::from(kani::any::<u16>());
    kani::assume(span <= 30_000);
    let mut input = candidate(31_000, 100.0);
    input.first_ms = 31_000 - span;
    input.count = count;
    let before = navigation.estimate();
    let accepted = navigation.apply_network_estimates(input, 0, None).unwrap();
    let after = navigation.estimate();
    assert_eq!(accepted, count >= 3 && span >= 10_000);
    assert_eq!(after.speed_mps, before.speed_mps);
    assert_eq!(after.systematic_drift_m, before.systematic_drift_m);
    assert!(after.position_m - before.position_m <= 50.0);
    if !accepted {
        assert_eq!(after, before);
    }
    assert_eq!(navigation.state.last_gps_position_ms, -1);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    kani::cover!(count == 2 && span == 10_000, "insufficient count");
    kani::cover!(count == 3 && span == 9999, "insufficient span");
    kani::cover!(
        count == 3 && span == 10_000 && accepted,
        "eligible boundary"
    );
}

#[kani::proof]
#[kani::unwind(3)]
fn speed_allocations_never_fall_through_to_position() {
    let mut navigation = estimator();
    let choice: u8 = kani::any();
    kani::assume(choice < 4);
    let before = navigation.estimate();
    let allocation = match choice {
        0 => NetworkUse::Reserved,
        1 | 2 => NetworkUse::Speed(crate::speed::SpeedEstimate {
            speed_mps: if choice == 1 { 12.0 } else { 200.0 },
            sigma_mps: 2.0,
            samples: 4,
            span_s: 30.0,
        }),
        _ => NetworkUse::RestorePrior(15.0),
    };
    let result = navigation.apply_network_speed(allocation, 0).unwrap();
    assert!(result.is_some());
    assert_eq!(result, Some(choice == 1));
    let after = navigation.estimate();
    assert_eq!(after.position_m, before.position_m);
    assert_eq!(after.covariance.position, before.covariance.position);
    assert_eq!(after.systematic_drift_m, before.systematic_drift_m);
    assert_eq!(navigation.state.last_gps_position_ms, -1);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    match choice {
        0 | 2 => assert_eq!(after, before),
        1 => {
            assert!(
                after.speed_mps > before.speed_mps && after.speed_mps - before.speed_mps <= 2.0
            );
        }
        _ => {
            assert_eq!(after.speed_mps, 15.0);
            assert!(after.covariance.speed >= before.covariance.speed);
        }
    }
    kani::cover!(choice == 1, "accepted fit");
    kani::cover!(choice == 2, "rejected fit");
    kani::cover!(choice == 3, "restored prior");
}

#[kani::proof]
#[kani::unwind(10)]
fn stop_between_network_scans_discards_the_partial_batch() {
    let mut navigation = estimator();
    navigation.state.network_evidence.speed = speed::kani_proofs::partial_batch();
    let active: bool = kani::any();
    if active {
        navigation.state.motion_control = Some(crate::estimator::MotionControl {
            cruise_speed_mps: 10.0,
            valid_until_ms: 2000,
        });
    }
    let hint = (!active).then_some(MotionObservation {
        factor: 0.0,
        cruise_speed_mps: 10.0,
        valid_until_ms: 2000,
        network_moving: false,
    });
    let batch = navigation.state.network_evidence.speed.clone();
    let before = snapshot(&navigation);
    assert!(!navigation.apply_network(None, hint).unwrap());
    assert!(navigation.state.network_evidence.speed == SpeedBatch::new());
    navigation.state.network_evidence.speed = batch;
    assert!(snapshot(&navigation) == before);
    kani::cover!(active, "active control");
    kani::cover!(!active, "new hint");
}
