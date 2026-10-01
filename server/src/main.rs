use anyhow::{Context, Result};
use clap::Parser;
use imu_nav_cell_server::{AppState, CellStore, Policy, ServerConfig, router};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};
use tracing_subscriber::EnvFilter;

/// Persistent cell-sharing server compatible with IMU Nav clients.
#[derive(Parser)]
#[command(version, about)]
struct Options {
    #[arg(long, default_value_t = 8080)]
    port: u16,
    #[arg(long, default_value = "cells.sqlite3")]
    data: PathBuf,
    #[arg(long, env = "CELLS_API_KEY")]
    api_key: Option<String>,
    /// Trust X-Forwarded-For from the reverse proxy connected to this process.
    #[arg(long)]
    trust_proxy: bool,
    #[arg(long, default_value_t = 2)]
    min_devices: usize,
    #[arg(long, default_value_t = 50)]
    max_samples: i64,
    #[arg(long, default_value = "any", value_parser = ["ukraine", "any"])]
    area: String,
    #[arg(long)]
    import: Option<PathBuf>,
    #[arg(long, value_delimiter = ',')]
    mcc: Vec<i64>,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    let options = Options::parse();
    let policy = Policy {
        min_devices: options.min_devices,
        max_samples_per_device: options.max_samples,
        ukraine_only: options.area == "ukraine",
        ..Policy::default()
    };
    let store = CellStore::open(&options.data)?;
    if let Some(path) = options.import {
        let mut towers = imu_nav_cell_server::read_import(&path, usize::MAX)?;
        if !options.mcc.is_empty() {
            towers.retain(|tower| options.mcc.contains(&tower.key.mcc));
        }
        let now = i64::try_from(SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs())?;
        let (result, _) = store.seed(&towers, now, &policy)?;
        tracing::info!(path = %path.display(), accepted = result.accepted, rejected = result.rejected, "seed import");
    }
    let (published, contributions) = store.counts(&policy)?;
    let state = AppState::new(
        store,
        ServerConfig {
            api_key: options.api_key,
            policy: policy.clone(),
            trust_proxy: options.trust_proxy,
        },
    )?;
    let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), options.port);
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .context("cannot bind server socket")?;
    tracing::info!(address = %listener.local_addr()?, published, contributions, min_devices = policy.min_devices, "cell server ready");
    axum::serve(
        listener,
        router(state).into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await?;
    Ok(())
}

async fn shutdown_signal() {
    let control_c = async {
        tokio::signal::ctrl_c().await.ok();
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut signal) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            signal.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! { () = control_c => {}, () = terminate => {} }
}
