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
        .output()
        .unwrap();
    assert!(
        compared.status.success(),
        "{}",
        String::from_utf8_lossy(&compared.stderr)
    );
    let repeated = Command::new(executable)
        .args(["run", "--out"])
        .arg(&root)
        .output()
        .unwrap();
    assert!(!repeated.status.success());
    std::fs::remove_dir_all(root).unwrap();
}
