//! Explicit TOML startup settings with command-line and environment overrides.

use anyhow::{Context, Result, anyhow, ensure};
use serde::Deserialize;
use std::net::{IpAddr, Ipv4Addr};
use std::path::{Path, PathBuf};

use crate::Options;

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct ConfigFile {
    server: Option<ServerFile>,
    database: Option<DatabaseFile>,
    mail: Option<MailFile>,
    policy: Option<PolicyFile>,
    logging: Option<LoggingFile>,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct ServerFile {
    bind: Option<IpAddr>,
    port: Option<u16>,
    public_url: Option<String>,
    trust_proxy: Option<bool>,
    secure_cookies: Option<bool>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DatabaseFile {
    backend: DatabaseKind,
    path: Option<PathBuf>,
    url: Option<String>,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
enum DatabaseKind {
    Sqlite,
    Postgres,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct MailFile {
    #[serde(rename = "smtp_host")]
    host: Option<String>,
    #[serde(rename = "smtp_username")]
    username: Option<String>,
    #[serde(rename = "smtp_password")]
    password: Option<String>,
    #[serde(rename = "smtp_from")]
    from: Option<String>,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct PolicyFile {
    min_devices: Option<usize>,
    max_samples: Option<i64>,
    area: Option<String>,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct LoggingFile {
    level: Option<String>,
}

pub(crate) enum DatabaseSettings {
    Sqlite(PathBuf),
    Postgres(String),
}

pub(crate) struct Settings {
    pub(crate) bind: IpAddr,
    pub(crate) port: u16,
    pub(crate) database: DatabaseSettings,
    pub(crate) log_level: String,
    pub(crate) public_url: Option<String>,
    pub(crate) smtp_host: Option<String>,
    pub(crate) smtp_username: Option<String>,
    pub(crate) smtp_password: Option<String>,
    pub(crate) smtp_from: Option<String>,
    pub(crate) trust_proxy: bool,
    pub(crate) secure_cookies: bool,
    pub(crate) min_devices: usize,
    pub(crate) max_samples: i64,
    pub(crate) area: String,
}

impl Settings {
    pub(crate) fn load(options: &Options) -> Result<Self> {
        let file = load_file(options.config.as_deref())?;
        let server = file.server.unwrap_or_default();
        let mail = file.mail.unwrap_or_default();
        let policy = file.policy.unwrap_or_default();
        let logging = file.logging.unwrap_or_default();
        let database = match file.database {
            None => DatabaseSettings::Sqlite(
                options
                    .data
                    .clone()
                    .unwrap_or_else(|| PathBuf::from("cells.sqlite3")),
            ),
            Some(DatabaseFile {
                backend: DatabaseKind::Sqlite,
                path,
                url,
            }) => {
                ensure!(
                    url.is_none(),
                    "SQLite configuration cannot contain database.url"
                );
                let path = if let Some(path) = &options.data {
                    path.clone()
                } else if let Some(path) = path {
                    resolve_config_path(options.config.as_deref(), path)
                } else {
                    PathBuf::from("cells.sqlite3")
                };
                DatabaseSettings::Sqlite(path)
            }
            Some(DatabaseFile {
                backend: DatabaseKind::Postgres,
                path,
                url,
            }) => {
                ensure!(
                    path.is_none() && options.data.is_none(),
                    "PostgreSQL configuration cannot use --data or database.path"
                );
                let url = std::env::var("CELLS_DATABASE_URL")
                    .ok()
                    .or(url)
                    .ok_or_else(|| {
                        anyhow!("PostgreSQL requires database.url or CELLS_DATABASE_URL")
                    })?;
                ensure!(
                    !url.trim().is_empty(),
                    "PostgreSQL connection URL cannot be empty"
                );
                DatabaseSettings::Postgres(url)
            }
        };
        let log_level = options
            .log_level
            .clone()
            .or(logging.level)
            .unwrap_or_else(|| "info".to_owned());
        ensure!(
            ["error", "warn", "info", "debug", "trace"].contains(&log_level.as_str()),
            "invalid logging level"
        );
        let area = options
            .area
            .clone()
            .or(policy.area)
            .unwrap_or_else(|| "any".to_owned());
        ensure!(
            area == "any" || area == "ukraine",
            "policy.area must be 'any' or 'ukraine'"
        );
        let settings = Self {
            bind: options
                .bind
                .or(server.bind)
                .unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED)),
            port: options.port.or(server.port).unwrap_or(8080),
            database,
            log_level,
            public_url: options.public_url.clone().or(server.public_url),
            smtp_host: options.smtp_host.clone().or(mail.host),
            smtp_username: options.smtp_username.clone().or(mail.username),
            smtp_password: options.smtp_password.clone().or(mail.password),
            smtp_from: options.smtp_from.clone().or(mail.from),
            trust_proxy: options.trust_proxy || server.trust_proxy.unwrap_or(false),
            secure_cookies: options.secure_cookies || server.secure_cookies.unwrap_or(false),
            min_devices: options.min_devices.or(policy.min_devices).unwrap_or(2),
            max_samples: options.max_samples.or(policy.max_samples).unwrap_or(50),
            area,
        };
        ensure!(
            settings.min_devices > 0 && settings.max_samples > 0,
            "invalid initial policy limits"
        );
        Ok(settings)
    }
}

fn load_file(path: Option<&Path>) -> Result<ConfigFile> {
    let Some(path) = path else {
        return Ok(ConfigFile::default());
    };
    let text = std::fs::read_to_string(path)
        .with_context(|| format!("cannot read configuration file {}", path.display()))?;
    // TOML parser diagnostics may quote a secret, so report the path without the input.
    toml::from_str::<ConfigFile>(&text)
        .map_err(|_| anyhow!("invalid TOML configuration in {}", path.display()))
}

fn resolve_config_path(config: Option<&Path>, path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        return path;
    }
    config
        .and_then(Path::parent)
        .unwrap_or_else(|| Path::new("."))
        .join(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[test]
    fn config_sets_defaults_and_cli_overrides_them() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("config.toml");
        std::fs::write(
            &path,
            "[server]\nport = 9000\nsecure_cookies = true\n[database]\nbackend = 'sqlite'\npath = 'local.sqlite3'\n[policy]\narea = 'ukraine'\n",
        )?;
        let options = Options::try_parse_from([
            "server",
            "--config",
            path.to_str().unwrap(),
            "--port",
            "9001",
        ])?;
        let settings = Settings::load(&options)?;
        assert_eq!(settings.port, 9001);
        assert_eq!(settings.area, "ukraine");
        assert!(settings.secure_cookies);
        assert!(
            matches!(settings.database, DatabaseSettings::Sqlite(ref path) if path == &directory.path().join("local.sqlite3"))
        );
        Ok(())
    }
}
