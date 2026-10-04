//! Exercise the shipped executable, including SQLite import and graceful profile flushing.
#![cfg(unix)]
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::time::Duration;

struct ServerProcess(Child);
impl Drop for ServerProcess {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[tokio::test]
async fn imports_filtered_cells_and_shuts_down_cleanly() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    let csv = directory.path().join("cells.csv");
    std::fs::write(
        &csv,
        "LTE,255,1,10,20,,30.5,50.4,100,3\nLTE,260,1,10,21,,30.5,50.4,100,3\n",
    )?;
    for signal in ["-TERM", "-INT"] {
        #[cfg(feature = "profiling")]
        let profile_path = directory.path().join(format!("{signal}-profile.json"));
        let mut command = Command::new(env!("CARGO_BIN_EXE_imu-nav-cell-server"));
        command
            .args([
                "--port",
                "0",
                "--area",
                "ukraine",
                "--mcc",
                "255",
                "--trust-proxy",
                "--data",
            ])
            .arg(directory.path().join("cells.sqlite3"))
            .arg("--import")
            .arg(&csv)
            .env_remove("CELLS_API_KEY")
            .env("RUST_LOG", "info")
            .env("NO_COLOR", "1")
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());
        #[cfg(feature = "profiling")]
        command.arg("--profile-output").arg(&profile_path);
        let mut server = ServerProcess(command.spawn()?);
        let stdout = server.0.stdout.take().unwrap();
        let (sender, receiver) = mpsc::channel();
        let reader = std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                if line.contains("cell server ready") {
                    let _ = sender.send(line);
                }
            }
        });
        let line = receiver.recv_timeout(Duration::from_secs(20))?;
        // Disable ANSI escapes at the process boundary in CI and locally.
        let plain = line
            .split("address=")
            .nth(1)
            .ok_or_else(|| anyhow::anyhow!("missing address: {line}"))?;
        let address = plain
            .split_whitespace()
            .next()
            .unwrap()
            .replace("0.0.0.0", "127.0.0.1");
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(5))
            .build()?;
        assert_eq!(
            client
                .get(format!("http://{address}/health"))
                .send()
                .await?
                .text()
                .await?,
            "ok 1\n"
        );
        let towers: serde_json::Value = client
            .get(format!("http://{address}/v1/towers"))
            .send()
            .await?
            .json()
            .await?;
        assert_eq!(towers["towers"].as_array().unwrap().len(), 1);
        assert!(
            Command::new("kill")
                .args([signal, &server.0.id().to_string()])
                .status()?
                .success()
        );
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        loop {
            if let Some(status) = server.0.try_wait()? {
                assert!(status.success());
                break;
            }
            anyhow::ensure!(
                std::time::Instant::now() < deadline,
                "graceful shutdown timed out"
            );
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        reader.join().unwrap();
        #[cfg(feature = "profiling")]
        {
            let profile: serde_json::Value = serde_json::from_slice(&std::fs::read(profile_path)?)?;
            assert!(profile["functions_timing"]["data"].as_array().is_some());
        }
    }
    Ok(())
}

#[test]
fn rejects_invalid_policy_missing_import_and_unwritable_database() -> anyhow::Result<()> {
    let directory = tempfile::tempdir()?;
    for arguments in [
        vec!["--min-devices", "0"],
        vec!["--max-samples", "0"],
        vec!["--import", "/nonexistent/imu-cells.csv"],
        vec!["--area", "invalid"],
    ] {
        let output = Command::new(env!("CARGO_BIN_EXE_imu-nav-cell-server"))
            .arg("--data")
            .arg(directory.path().join("cells.sqlite3"))
            .args(arguments)
            .env_remove("CELLS_API_KEY")
            .output()?;
        assert!(!output.status.success());
        assert_ne!(output.stderr, [] as [u8; 0]);
    }
    let output = Command::new(env!("CARGO_BIN_EXE_imu-nav-cell-server"))
        .arg("--data")
        .arg(directory.path())
        .output()?;
    assert!(!output.status.success());
    Ok(())
}
