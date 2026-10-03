//! Exercise the actual child-process orchestration and decode both profile formats.
#![cfg(target_os = "linux")]

use std::io::Read;
use std::process::Command;

use pprof::protos::{Message, Profile};

#[test]
fn capture_outputs_are_readable_and_comparable() {
    let root = std::env::temp_dir().join(format!("imu-sim-capture-{}", std::process::id()));
    let executable = env!("CARGO_BIN_EXE_imu-nav-sim");
    let output = Command::new(executable)
        .args([
            "run",
            "--scenario",
            "all",
            "--duration-s",
            "123",
            "--route-points",
            "1000",
            "--repetitions",
            "1",
            "--out",
        ])
        .arg(&root)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let cpu = Profile::decode(
        std::fs::read(root.join("cpu/driving/cpu.pb"))
            .unwrap()
            .as_slice(),
    )
    .unwrap();
    assert_ne!(cpu.sample, [] as [pprof::protos::Sample; 0]);
    let mut heap = Vec::new();
    flate2::read::GzDecoder::new(
        std::fs::File::open(root.join("heap/driving/session-0-steady.pb.gz")).unwrap(),
    )
    .read_to_end(&mut heap)
    .unwrap();
    let heap = Profile::decode(heap.as_slice()).unwrap();
    assert!(heap.string_table.iter().any(|name| name == "inuse_space"));
    let compared = Command::new(executable)
        .arg("compare")
        .arg(&root)
        .arg(&root)
        .arg("--out")
        .arg(root.join("comparison.md"))
        .output()
        .unwrap();
    assert!(
        compared.status.success(),
        "{}",
        String::from_utf8_lossy(&compared.stderr)
    );
    assert!(
        std::fs::read_to_string(root.join("comparison.md"))
            .unwrap()
            .contains("All deterministic outputs match")
    );
    let repeated = Command::new(executable)
        .args(["run", "--out"])
        .arg(&root)
        .output()
        .unwrap();
    assert!(!repeated.status.success());
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn invalid_cli_and_profiler_environment_fail_without_complete_marker() {
    let root = std::env::temp_dir().join(format!("imu-sim-invalid-{}", std::process::id()));
    let executable = env!("CARGO_BIN_EXE_imu-nav-sim");
    for (index, configuration) in ["prof:false", "prof:true,prof_active:true"]
        .into_iter()
        .enumerate()
    {
        let out = root.join(index.to_string());
        let result = Command::new(executable)
            .args(["run", "--out"])
            .arg(&out)
            .env("_RJEM_MALLOC_CONF", configuration)
            .output()
            .unwrap();
        assert!(!result.status.success());
        assert!(!out.join("COMPLETE").exists());
        assert!(
            String::from_utf8_lossy(&result.stderr).contains("jemalloc prof")
                || String::from_utf8_lossy(&result.stderr).contains("sampling must start inactive")
        );
    }
    let result = Command::new(executable)
        .arg("compare")
        .arg(&root)
        .arg(&root)
        .output()
        .unwrap();
    assert!(!result.status.success());
    for argument in ["--duration-s", "--route-points", "--repetitions"] {
        let result = Command::new(executable)
            .args(["run", "--out"])
            .arg(root.join("invalid"))
            .args([argument, "0"])
            .output()
            .unwrap();
        assert!(!result.status.success());
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn stress_mode_expands_route_without_enabling_heap_sampling() {
    let root = std::env::temp_dir().join(format!("imu-sim-stress-{}", std::process::id()));
    let output = Command::new(env!("CARGO_BIN_EXE_imu-nav-sim"))
        .args([
            "run",
            "--pass",
            "alloc",
            "--scenario",
            "driving",
            "--duration-s",
            "120",
            "--route-points",
            "100",
            "--repetitions",
            "1",
            "--stress",
            "--out",
        ])
        .arg(&root)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let report: serde_json::Value =
        serde_json::from_slice(&std::fs::read(root.join("report.json")).unwrap()).unwrap();
    assert_eq!(report["config"]["route_points"], 100_000);
    assert_eq!(report["config"]["stress"], true);
    assert_eq!(report["runs"].as_array().unwrap().len(), 1);
    std::fs::remove_dir_all(root).unwrap();
}
