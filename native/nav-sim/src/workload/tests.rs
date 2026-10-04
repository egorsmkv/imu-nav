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
