use super::*;

#[test]
fn incompatible_inputs_are_rejected() {
    let first = Report::new(Config::default(), Pass::Alloc, vec![]).unwrap();
    let mut second = Report::new(Config::default(), Pass::Alloc, vec![]).unwrap();
    assert!(compatible(&first, &second).is_ok());
    second.config.seed += 1;
    assert!(compatible(&first, &second).is_err());
}

#[test]
fn comparison_rejects_changed_metadata_counts_and_navigation_results() {
    let config = Config {
        duration_s: 120,
        route_points: 100,
        scenario: Scenario::Driving,
        ..Config::default()
    };
    let fixture = crate::workload::Fixture::new(&config, Scenario::Driving);
    let run = crate::workload::run(&fixture, Scenario::Driving, Pass::Timing, None).unwrap();
    let first = Report::new(config, Pass::Timing, vec![run]).unwrap();
    let serialized = serde_json::to_value(&first).unwrap();
    for key in ["harness_hash", "rustc", "optimization", "rustflags", "host"] {
        let mut value = serialized.clone();
        value[key] = serde_json::json!("different");
        let other = serde_json::from_value(value).unwrap();
        assert!(compatible(&first, &other).is_err(), "{key}");
    }
    let mut second: Report = serde_json::from_value(serialized.clone()).unwrap();
    second.runs.clear();
    assert!(compatible(&first, &second).is_err());
    let mut second: Report = serde_json::from_value(serialized).unwrap();
    second.runs[0].scenario = Scenario::All;
    assert!(compatible(&first, &second).is_err());
}

#[test]
fn rejects_incomplete_reports_missing_profiles_and_existing_output() {
    let root = std::env::temp_dir().join(format!("imu-report-{}", std::process::id()));
    std::fs::create_dir(&root).unwrap();
    assert!(load(&root, Pass::Alloc).is_err());
    write_new(&root.join("COMPLETE"), b"done").unwrap();
    assert!(write_new(&root.join("COMPLETE"), b"replacement").is_err());
    assert!(load(&root, Pass::Alloc).is_err());
    std::fs::create_dir(root.join("alloc")).unwrap();
    let report = Report::new(Config::default(), Pass::Alloc, vec![]).unwrap();
    write_new(
        &root.join("alloc/report.json"),
        &serde_json::to_vec(&report).unwrap(),
    )
    .unwrap();
    assert!(
        load(&root, Pass::Alloc)
            .unwrap_err()
            .to_string()
            .contains("incomplete scenario")
    );
    assert!(sampled_bytes(&root, Scenario::Driving, "steady").is_err());
    std::fs::remove_dir_all(root).unwrap();
}
