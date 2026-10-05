use super::*;

#[test]
fn app_like_group_uses_only_normal_driving_inputs() {
    assert_eq!(
        Scenario::AppLike.cases(),
        vec![
            Scenario::Driving,
            Scenario::Jam,
            Scenario::Delayed,
            Scenario::Stop,
            Scenario::Reroute,
        ]
    );
}

#[test]
fn advanced_group_uses_only_geometry_and_dense_sensor_inputs() {
    assert_eq!(
        Scenario::Advanced.cases(),
        vec![
            Scenario::Winding,
            Scenario::Parallel,
            Scenario::Crossing,
            Scenario::Reacquisition,
            Scenario::SensorBurst,
        ]
    );
}

#[test]
fn validation_rejects_missing_scenario_evidence() {
    let mut outcome = Outcome {
        geometry_queries: 1,
        ambiguous_queries: 1,
        accepted_geometry: 1,
        fallback_queries: 1,
        gps_good: 1,
        gps_bad: 1,
        gps_accepted: 1,
        delayed_accepted: 1,
        network_accepted: 1,
        jam_transitions: 2,
        motion_hints: 1,
        stopped_ticks: 1,
        resumed_ticks: 1,
        blind_walking_ticks: 1,
        gps_recovered: 1,
        walking_hints: 1,
        reroutes: 1,
        sessions: 1,
        ..Outcome::default()
    };
    assert!(outcome.validate(Scenario::Winding).is_ok());
    assert!(outcome.validate(Scenario::Parallel).is_ok());
    assert!(outcome.validate(Scenario::Crossing).is_ok());
    assert!(outcome.validate(Scenario::Reacquisition).is_ok());
    assert!(outcome.validate(Scenario::Driving).is_ok());
    assert!(outcome.validate(Scenario::Jam).is_ok());
    assert!(outcome.validate(Scenario::Reroute).is_ok());
    assert!(outcome.validate(Scenario::Stop).is_ok());
    assert!(outcome.validate(Scenario::Walking).is_ok());
    outcome.geometry_queries = 0;
    assert!(outcome.validate(Scenario::Winding).is_err());
    outcome.geometry_queries = 1;
    outcome.ambiguous_queries = 0;
    assert!(outcome.validate(Scenario::Parallel).is_err());
    outcome.ambiguous_queries = 1;
    outcome.fallback_queries = 0;
    assert!(outcome.validate(Scenario::Reacquisition).is_err());
    outcome.fallback_queries = 1;
    outcome.accepted_geometry = 0;
    assert!(outcome.validate(Scenario::Winding).is_err());
    outcome.accepted_geometry = 1;
    outcome.gps_accepted = 0;
    assert!(outcome.validate(Scenario::Driving).is_err());
    outcome.gps_accepted = 1;
    outcome.max_network_history = 8;
    assert!(outcome.validate(Scenario::Driving).is_err());
    outcome.max_network_history = 0;
    outcome.gps_recovered = 0;
    assert!(outcome.validate(Scenario::Jam).is_err());
    outcome.gps_recovered = 1;
    outcome.delayed_accepted = 0;
    assert!(outcome.validate(Scenario::Delayed).is_err());
    outcome.delayed_accepted = 1;
    outcome.resumed_ticks = 0;
    assert!(outcome.validate(Scenario::Stop).is_err());
    outcome.resumed_ticks = 1;
    outcome.walking_hints = 0;
    assert!(outcome.validate(Scenario::Walking).is_err());
}

#[test]
fn every_case_is_deterministic_and_exercises_its_paths() {
    let config = Config {
        duration_s: 120,
        route_points: 100,
        ..Config::default()
    };
    for scenario in Scenario::All.cases() {
        let fixture = Fixture::new(&config, scenario);
        let first = run(&fixture, scenario, Pass::Timing, None).unwrap();
        let second = run(&fixture, scenario, Pass::Timing, None).unwrap();
        assert_eq!(first.outcome, second.outcome, "{scenario:?}");
    }
}

#[test]
fn delayed_inputs_preserve_monotonic_observation_and_delivery_times() {
    let fixture = Fixture::new(&Config::default(), Scenario::Delayed);
    let mut previous_ms = -1;
    for input in fixture.inputs {
        if let Some(gps) = input.gps {
            // First delayed observations repeat startup timestamps; the estimator must ignore them.
            assert!(gps.elapsed_ms <= input.elapsed_ms);
            if input.elapsed_ms > 4000 {
                assert!(gps.elapsed_ms > previous_ms);
            }
            previous_ms = gps.elapsed_ms;
        }
    }
}
