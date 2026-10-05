use anyhow::{Context, Result};
use clap::Parser;
use imu_nav_cell_server::{
    AppState, CellStore, MailConfig, Policy, ServerConfig, create_admin, router,
};
use std::io::IsTerminal;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};
use tracing_subscriber::EnvFilter;

mod config;
use config::{DatabaseSettings, Settings};

/// Persistent cell-sharing server compatible with IMU Nav clients.
#[derive(Parser)]
#[command(version, about)]
struct Options {
    /// Read server settings from this TOML file.
    #[arg(long)]
    config: Option<PathBuf>,
    /// Default tracing level; `RUST_LOG` overrides this for per-module filters.
    #[arg(long, env = "CELLS_LOG_LEVEL", value_parser = ["error", "warn", "info", "debug", "trace"])]
    log_level: Option<String>,
    /// Network interface to listen on; use 127.0.0.1 for a local traffic simulation.
    #[arg(long)]
    bind: Option<IpAddr>,
    #[arg(long)]
    port: Option<u16>,
    #[arg(long)]
    data: Option<PathBuf>,
    /// Copy a stopped SQLite database into the empty PostgreSQL database selected by --config.
    #[arg(long)]
    migrate_from_sqlite: Option<PathBuf>,
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
    #[arg(long)]
    min_devices: Option<usize>,
    #[arg(long)]
    max_samples: Option<i64>,
    #[arg(long, value_parser = ["ukraine", "any"])]
    area: Option<String>,
    #[arg(long)]
    import: Option<PathBuf>,
    #[arg(long, value_delimiter = ',')]
    mcc: Vec<i64>,
    /// Write an opt-in function timing report after graceful shutdown.
    #[cfg(feature = "profiling")]
    #[arg(long, env = "CELLS_PROFILE_OUTPUT")]
    profile_output: Option<PathBuf>,
}

fn main() -> Result<()> {
    let options = Options::parse();
    let settings = Settings::load(&options)?;
    if options.migrate_from_sqlite.is_some() {
        anyhow::ensure!(
            matches!(settings.database, DatabaseSettings::Postgres(_)),
            "migration target must be PostgreSQL"
        );
    }
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new(&settings.log_level)),
        )
        .with_writer(std::io::stderr)
        .init();
    match &settings.database {
        DatabaseSettings::Sqlite(path) => {
            tracing::info!(backend = "sqlite", data = %path.display(), "opening cell database");
        }
        DatabaseSettings::Postgres(_) => {
            tracing::info!(backend = "postgres", "opening cell database");
        }
    }
    #[cfg(feature = "profiling")]
    let _profile_guard = options.profile_output.as_ref().map(|path| {
        hotpath::HotpathGuardBuilder::new("imu-nav-cell-server")
            .format(hotpath::Format::JsonPretty)
            .output_path(path)
            .build()
    });
    let default_policy = Policy {
        min_devices: settings.min_devices,
        max_samples_per_device: settings.max_samples,
        ukraine_only: settings.area == "ukraine",
        ..Policy::default()
    };
    let store = match &settings.database {
        DatabaseSettings::Sqlite(path) => CellStore::open(path)?,
        DatabaseSettings::Postgres(url) => CellStore::open_postgres(url)?,
    };
    if let Some(path) = options.migrate_from_sqlite.as_ref() {
        anyhow::ensure!(
            options.create_admin.is_none() && options.import.is_none(),
            "migration cannot be combined with admin setup or seed import"
        );
        store.migrate_from_sqlite(path)?;
        tracing::info!("SQLite to PostgreSQL migration completed");
        return Ok(());
    }
    let policy = store.stored_policy()?.unwrap_or(default_policy);
    if let Some(email) = options.create_admin.as_deref() {
        tracing::info!("administrator setup started");
        let password = read_admin_password()?;
        tracing::debug!("administrator password received; creating account");
        create_admin(&store, email, &password)?;
        tracing::info!("administrator account created");
        return Ok(());
    }
    tracing::info!(bind = %settings.bind, port = settings.port, area = %settings.area, "starting cell server");
    let mail = mail_config(&settings)?;
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
    let cleanup_store = store.clone();
    let state = AppState::new(
        store,
        ServerConfig {
            mail,
            policy: policy.clone(),
            trust_proxy: settings.trust_proxy,
        },
    )?;
    let address = SocketAddr::new(settings.bind, settings.port);
    let runtime = tokio::runtime::Runtime::new()?;
    let pool_guard = cleanup_store.clone();
    let result = runtime.block_on(serve_http(
        state,
        cleanup_store,
        address,
        (published, contributions),
        policy.min_devices,
    ));
    drop(runtime);
    drop(pool_guard);
    result
}

/// Build complete mail settings only when every required value was supplied.
fn mail_config(settings: &Settings) -> Result<Option<MailConfig>> {
    let mail = match (
        settings.public_url.clone(),
        settings.smtp_host.clone(),
        settings.smtp_username.clone(),
        settings.smtp_password.clone(),
        settings.smtp_from.clone(),
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
    Ok(mail)
}

/// Bind the HTTP listener after database initialization has completed on the ordinary thread.
async fn serve_http(
    state: AppState,
    cleanup_store: CellStore,
    address: SocketAddr,
    counts: (usize, usize),
    min_devices: usize,
) -> Result<()> {
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .context("cannot bind server socket")?;
    tracing::info!(address = %listener.local_addr()?, published = counts.0, contributions = counts.1, min_devices, "cell server ready");
    let cleanup = spawn_debug_cleanup(cleanup_store);
    axum::serve(
        listener,
        router(state).into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await?;
    cleanup.abort();
    tracing::info!("cell server stopped");
    Ok(())
}

/// Expire private diagnostics even when nobody opens the account pages.
fn spawn_debug_cleanup(cleanup_store: CellStore) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(6 * 60 * 60));
        loop {
            interval.tick().await;
            let store = cleanup_store.clone();
            match tokio::task::spawn_blocking(move || store.prune_debug_sessions()).await {
                Ok(Ok(removed)) if removed > 0 => {
                    tracing::info!(removed, "expired diagnostic sessions removed");
                }
                Ok(Ok(_)) => {}
                Ok(Err(error)) => tracing::warn!(%error, "diagnostic cleanup failed"),
                Err(error) => tracing::warn!(%error, "diagnostic cleanup worker failed"),
            }
        }
    })
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
