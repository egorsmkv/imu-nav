//! Embed the compiled core's identity so a preserved baseline binary cannot claim new source metadata.
use std::path::Path;
use std::process::Command;

fn hash_sources(path: &Path, hash: &mut u64) {
    let mut paths: Vec<_> = std::fs::read_dir(path)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .collect();
    paths.sort();
    for path in paths {
        if path.is_dir() {
            hash_sources(&path, hash);
        } else {
            for byte in path
                .file_name()
                .unwrap()
                .as_encoded_bytes()
                .iter()
                .copied()
                .chain(std::fs::read(&path).unwrap())
            {
                *hash = hash.wrapping_mul(0x0000_0100_0000_01b3) ^ u64::from(byte);
            }
        }
    }
}

fn main() {
    println!("cargo:rerun-if-changed=../nav-core/src");
    println!("cargo:rerun-if-changed=src");
    println!("cargo:rerun-if-changed=../Cargo.lock");
    println!("cargo:rerun-if-env-changed=CARGO_ENCODED_RUSTFLAGS");
    println!(
        "cargo:rustc-env=SIM_RUSTFLAGS={}",
        std::env::var("CARGO_ENCODED_RUSTFLAGS")
            .unwrap_or_default()
            .replace('\x1f', " ")
    );
    let mut core_hash = 0xcbf2_9ce4_8422_2325;
    hash_sources(Path::new("../nav-core/src"), &mut core_hash);
    let mut harness_hash = 0xcbf2_9ce4_8422_2325;
    hash_sources(Path::new("src"), &mut harness_hash);
    println!("cargo:rustc-env=SIM_CORE_HASH={core_hash:016x}");
    println!("cargo:rustc-env=SIM_HARNESS_HASH={harness_hash:016x}");
    let compiler = Command::new(std::env::var("RUSTC").unwrap())
        .arg("--version")
        .output()
        .unwrap();
    println!(
        "cargo:rustc-env=SIM_RUSTC={}",
        String::from_utf8_lossy(&compiler.stdout).trim()
    );
    println!(
        "cargo:rustc-env=SIM_OPT_LEVEL={}",
        std::env::var("OPT_LEVEL").unwrap()
    );
}
