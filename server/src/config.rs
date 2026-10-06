//! Explicit TOML startup settings with command-line and environment overrides.

use anyhow::{Context, Result, anyhow, ensure};
use serde::Deserialize;
use std::net::{IpAddr, Ipv4Addr};
use std::path::{Path, PathBuf};

use crate::Options;
use imu_nav_cell_server::PrivacyNotice;

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct ConfigFile {
    server: Option<ServerFile>,
    database: Option<DatabaseFile>,
    mail: Option<MailFile>,
    policy: Option<PolicyFile>,
    logging: Option<LoggingFile>,
    privacy: Option<PrivacyFile>,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct PrivacyFile {
    version: Option<String>,
    controller: Option<String>,
    contact: Option<String>,
    rights_contact: Option<String>,
    region: Option<String>,
    recipients: Option<String>,
    transfers: Option<String>,
    account_basis: Option<String>,
    security_basis: Option<String>,
    notice_en: Option<String>,
    notice_uk: Option<String>,
    notice_ru: Option<String>,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct ServerFile {
    mode: Option<String>,
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
    pub(crate) privacy: Option<PrivacyNotice>,
}

impl Settings {
    pub(crate) fn load(options: &Options) -> Result<Self> {
        let file = load_file(options.config.as_deref())?;
        let server = file.server.unwrap_or_default();
        let mail = file.mail.unwrap_or_default();
        let policy = file.policy.unwrap_or_default();
        let logging = file.logging.unwrap_or_default();
        let mode = server.mode.as_deref().unwrap_or("local");
        ensure!(
            matches!(mode, "local" | "public"),
            "server.mode must be local or public"
        );
        let privacy = load_privacy_notice(mode, file.privacy)?;
        let database = load_database(options, file.database)?;
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
                .unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST)),
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
            privacy,
        };
        if mode == "local" {
            ensure!(
                settings.bind.is_loopback(),
                "local mode must bind to loopback; set server.mode='public' for network access"
            );
        } else {
            ensure!(
                settings
                    .public_url
                    .as_ref()
                    .is_some_and(|url| valid_public_url(url)),
                "public mode requires an HTTPS origin in public_url"
            );
            ensure!(
                settings.secure_cookies,
                "public mode requires secure_cookies=true"
            );
        }
        ensure!(
            settings.min_devices > 0 && settings.max_samples > 0,
            "invalid initial policy limits"
        );
        Ok(settings)
    }
}

fn load_database(options: &Options, database: Option<DatabaseFile>) -> Result<DatabaseSettings> {
    match database {
        None => Ok(DatabaseSettings::Sqlite(
            options
                .data
                .clone()
                .unwrap_or_else(|| PathBuf::from("cells.sqlite3")),
        )),
        Some(DatabaseFile {
            backend: DatabaseKind::Sqlite,
            path,
            url,
        }) => {
            ensure!(
                url.is_none(),
                "SQLite configuration cannot contain database.url"
            );
            let path = options
                .data
                .clone()
                .or_else(|| path.map(|path| resolve_config_path(options.config.as_deref(), path)))
                .unwrap_or_else(|| PathBuf::from("cells.sqlite3"));
            Ok(DatabaseSettings::Sqlite(path))
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
                .ok_or_else(|| anyhow!("PostgreSQL requires database.url or CELLS_DATABASE_URL"))?;
            ensure!(
                !url.trim().is_empty(),
                "PostgreSQL connection URL cannot be empty"
            );
            Ok(DatabaseSettings::Postgres(url))
        }
    }
}

fn load_privacy_notice(mode: &str, privacy: Option<PrivacyFile>) -> Result<Option<PrivacyNotice>> {
    if mode == "local" {
        return Ok(None);
    }
    let data = privacy.ok_or_else(|| anyhow!("public mode requires [privacy]"))?;
    let required = |value: Option<String>, name: &str| -> Result<String> {
        let env_name = format!("CELLS_PRIVACY_{}", name.to_ascii_uppercase());
        let value = std::env::var(env_name).ok().or(value);
        let value = value.ok_or_else(|| anyhow!("privacy.{name} is required"))?;
        ensure!(
            !value.trim().is_empty() && !value.contains("CHANGE_ME"),
            "privacy.{name} must be set"
        );
        Ok(value)
    };
    Ok(Some(PrivacyNotice {
        version: required(data.version, "version")?,
        controller: required(data.controller, "controller")?,
        contact: required(data.contact, "contact")?,
        rights_contact: required(data.rights_contact, "rights_contact")?,
        region: required(data.region, "region")?,
        recipients: required(data.recipients, "recipients")?,
        transfers: required(data.transfers, "transfers")?,
        account_basis: required(data.account_basis, "account_basis")?,
        security_basis: required(data.security_basis, "security_basis")?,
        notice_en: required(data.notice_en, "notice_en")?,
        notice_uk: required(data.notice_uk, "notice_uk")?,
        notice_ru: required(data.notice_ru, "notice_ru")?,
        tower_retention_months: 12,
        diagnostics_retention_days: 30,
    }))
}

pub(crate) fn valid_public_url(value: &str) -> bool {
    let Ok(uri) = value.parse::<axum::http::Uri>() else {
        return false;
    };
    uri.scheme_str() == Some("https")
        && uri.authority().is_some_and(|authority| {
            !authority.host().is_empty() && !authority.as_str().contains('@')
        })
        && uri.path() == "/"
        && uri.query().is_none()
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

    #[test]
    fn public_mode_requires_operator_notice_and_safe_origin() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("config.toml");
        std::fs::write(
            &path,
            "[server]\nmode='public'\nbind='0.0.0.0'\npublic_url='https://cells.example.org'\nsecure_cookies=true\n",
        )?;
        let options = Options::try_parse_from(["server", "--config", path.to_str().unwrap()])?;
        assert!(Settings::load(&options).is_err());
        assert!(valid_public_url("https://cells.example.org"));
        assert!(!valid_public_url("https://cells.example.org@evil.example"));
        assert!(!valid_public_url("http://cells.example.org"));
        assert!(!valid_public_url("https://cells.example.org/path"));
        Ok(())
    }
}
