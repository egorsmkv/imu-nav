//! Linux host laboratory for the native app lifecycle; never linked into the Android library.

#[cfg(target_os = "linux")]
mod cli;
#[cfg(target_os = "linux")]
mod measurement;
#[cfg(target_os = "linux")]
mod report;
#[cfg(target_os = "linux")]
mod workload;

#[cfg(target_os = "linux")]
fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("warn")),
        )
        .with_writer(std::io::stderr)
        .init();
    cli::execute()
}

#[cfg(not(target_os = "linux"))]
fn main() {
    eprintln!("imu-nav-sim profiling currently requires a Linux host");
    std::process::exit(1);
}
