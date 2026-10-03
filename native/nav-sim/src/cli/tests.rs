use super::*;

#[test]
fn cli_rejects_invalid_sizes_and_accepts_stress() {
    assert!(Cli::try_parse_from(["sim", "run", "--out", "unused", "--duration-s", "0"]).is_err());
    assert!(Cli::try_parse_from(["sim", "run", "--out", "unused", "--route-points", "1"]).is_err());
    assert!(Cli::try_parse_from(["sim", "run", "--out", "unused", "--stress"]).is_ok());
}

#[test]
fn existing_output_is_never_overwritten() {
    let path = std::env::temp_dir().join(format!("imu-sim-output-{}", std::process::id()));
    create_output(&path).unwrap();
    assert!(create_output(&path).is_err());
    std::fs::remove_dir(path).unwrap();
}

#[test]
fn child_failure_retains_partial_output_without_completion_marker() {
    let root = std::env::temp_dir().join(format!("imu-sim-child-failure-{}", std::process::id()));
    create_output(&root).unwrap();
    let error = run_children_with_executable(
        &Config {
            stress: true,
            ..Config::default()
        },
        &root,
        Path::new("/bin/false"),
    )
    .unwrap_err();
    assert!(
        error
            .to_string()
            .contains("heap pass failed; partial output retained")
    );
    assert!(root.join("imu-nav-sim").is_file());
    assert!(!root.join("COMPLETE").exists());
    std::fs::remove_dir_all(root).unwrap();
}
