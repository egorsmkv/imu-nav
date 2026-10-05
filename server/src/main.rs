use anyhow::{Context, Result};
use clap::Parser;
use imu_nav_cell_server::{
    AppState, CellStore, MailConfig, Policy, ServerConfig, create_admin, router,
};
use std::io::IsTerminal;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};
use tracing_subscriber::EnvFilter;

/// Persistent cell-sharing server compatible with IMU Nav clients.
#[derive(Parser)]
#[command(version, about)]
struct Options {
    /// Default tracing level; `RUST_LOG` overrides this for per-module filters.
    #[arg(long, env = "CELLS_LOG_LEVEL", default_value = "info", value_parser = ["error", "warn", "info", "debug", "trace"])]
    log_level: String,
    /// Network interface to listen on; use 127.0.0.1 for a local traffic simulation.
    #[arg(long, default_value_t = IpAddr::V4(Ipv4Addr::UNSPECIFIED))]
    bind: IpAddr,
    #[arg(long, default_value_t = 8080)]
    port: u16,
    #[arg(long, default_value = "cells.sqlite3")]
    data: PathBuf,
    /// Create an admin locally; prompt on a terminal or read one line from standard input.
    #[arg(long)]
    create_admin: Option<String>,
    #[arg(long, env = "CELLS_PUBLIC_URL")]
    public_url: Option<String>,
    #[arg(long, env = "CELLS_SMTP_HOST")]
    smtp_host: Option<String>,
    #[arg(long, env = "CELLS_SMTP_USERNAME")]
    smtp_username: Option<String>,
    #[arg(long, env = "CELLS_SMTP_PASSWORD")]
    smtp_password: Option<String>,
    #[arg(long, env = "CELLS_SMTP_FROM")]
    smtp_from: Option<String>,
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
    /// Write an opt-in function timing report after graceful shutdown.
    #[cfg(feature = "profiling")]
    #[arg(long, env = "CELLS_PROFILE_OUTPUT")]
    profile_output: Option<PathBuf>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let options = Options::parse();
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new(&options.log_level)),
        )
        .with_writer(std::io::stderr)
        .init();
    tracing::info!(data = %options.data.display(), "opening cell database");
    #[cfg(feature = "profiling")]
    let _profile_guard = options.profile_output.as_ref().map(|path| {
        hotpath::HotpathGuardBuilder::new("imu-nav-cell-server")
            .format(hotpath::Format::JsonPretty)
            .output_path(path)
            .build()
    });
    let default_policy = Policy {
        min_devices: options.min_devices,
        max_samples_per_device: options.max_samples,
        ukraine_only: options.area == "ukraine",
        ..Policy::default()
    };
    let store = CellStore::open(&options.data)?;
    let policy = store.stored_policy()?.unwrap_or(default_policy);
    if let Some(email) = options.create_admin.as_deref() {
        tracing::info!("administrator setup started");
        let password = read_admin_password()?;
        tracing::debug!("administrator password received; creating account");
        create_admin(&store, email, &password)?;
        tracing::info!("administrator account created");
        return Ok(());
    }
    tracing::info!(bind = %options.bind, port = options.port, area = %options.area, "starting cell server");
    let mail = match (
        options.public_url,
        options.smtp_host,
        options.smtp_username,
        options.smtp_password,
        options.smtp_from,
    ) {
        (
            Some(public_url),
            Some(smtp_host),
            Some(smtp_username),
            Some(smtp_password),
            Some(smtp_from),
        ) => {
            anyhow::ensure!(
                public_url.starts_with("https://"),
                "CELLS_PUBLIC_URL must use HTTPS"
            );
            Some(MailConfig {
                public_url,
                smtp_host,
                smtp_username,
                smtp_password,
                smtp_from,
            })
        }
        (None, None, None, None, None) => None,
        _ => anyhow::bail!(
            "all CELLS_PUBLIC_URL and CELLS_SMTP_* settings must be provided together"
        ),
    };
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
            mail,
            policy: policy.clone(),
            trust_proxy: options.trust_proxy,
        },
    )?;
    let address = SocketAddr::new(options.bind, options.port);
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
    tracing::info!("cell server stopped");
    Ok(())
}

/// Read one password without echo on a terminal; a pipe still supports scripted setup.
fn read_admin_password() -> Result<String> {
    if std::io::stdin().is_terminal() {
        tracing::info!("waiting for administrator password on terminal");
        return rpassword::prompt_password("Administrator password (12–256 characters): ")
            .context("cannot read administrator password from terminal");
    }
    tracing::info!("waiting for one administrator password line on standard input");
    let mut password = String::new();
    let length = std::io::stdin().read_line(&mut password)?;
    anyhow::ensure!(
        length > 0,
        "no administrator password provided on standard input"
    );
    Ok(password.trim_end_matches(['\n', '\r']).to_owned())
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
