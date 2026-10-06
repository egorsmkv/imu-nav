mod consensus;
pub(crate) mod management;

use crate::db::{Connection, Transaction, params, params_from_iter};
use crate::model::{distance_m, plausible};
use crate::{CellKey, CellTower, Consensus, Policy, Radio, UploadResult};
use anyhow::{Context, Result};
use flate2::{Compression, write::GzEncoder};
use rusqlite::types::{Type, Value};
use rusqlite::{OptionalExtension, TransactionBehavior};
use std::collections::{BTreeSet, HashSet};
use std::fmt::Write as _;
use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::str::FromStr;

const SEED_DEVICE: &str = "seed";
const MANUAL_DEVICE: &str = "manual";
const SEED_WEIGHT: f64 = 200.0;
const SEED_VOTE: f64 = 3.0;
const EXPORT_PAGE_SIZE: usize = 512;
// Five bind values per key; keep each query under SQLite's older 999-variable limit.
const MODERATION_BATCH_SIZE: usize = 100;

// Parents precede children so PostgreSQL verifies all foreign keys during the copy.
const MIGRATION_TABLES: &[(&str, &str)] = &[
    (
        "users",
        "id,email,password_hash,admin,suspended,sharing_enabled,email_verified",
    ),
    (
        "contributions",
        "radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s",
    ),
    (
        "consensus",
        "radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded,updated_s",
    ),
    ("tower_moderation", "radio,mcc,mnc,area,cid,quarantined"),
    ("tower_removals", "radio,mcc,mnc,area,cid,updated_s"),
    ("server_settings", "key,value"),
    ("admin_audit", "id,actor_id,action,target,at_s"),
    (
        "admin_jobs",
        "id,kind,status,processed,started_s,finished_s",
    ),
    ("admin_import_rejections", "job_id,row_number,reason,input"),
    (
        "account_deleted_keys",
        "account_id,radio,mcc,mnc,area,cid,device",
    ),
    ("email_verifications", "token_hash,user_id,email,expires_s"),
    (
        "auth_tokens",
        "token_hash,user_id,session_id,kind,expires_s",
    ),
    (
        "web_impersonations",
        "token_hash,admin_token_hash,actor_id,target_id",
    ),
    ("password_resets", "token_hash,user_id,expires_s"),
    (
        "privacy_consents",
        "id,account_id,purpose,notice_version,granted,at_s",
    ),
    (
        "debug_sessions",
        "id,account_id,client_id,context_json,created_s,updated_s,finished_s,incomplete,bytes",
    ),
    ("debug_batches", "session_id,seq,digest,bytes"),
    ("debug_entries", "session_id,seq,item,kind,elapsed_ms,line"),
];

#[derive(Clone, Debug)]
struct Contribution {
    device: String,
    lat: f64,
    lon: f64,
    range_m: f64,
    samples: i64,
    updated_s: i64,
}

/// Persistent contribution and consensus store.
#[derive(Clone, Debug)]
pub struct CellStore {
    backend: StoreBackend,
}

#[derive(Clone)]
enum StoreBackend {
    Sqlite(PathBuf),
    Postgres(crate::db::PgPool),
}

impl std::fmt::Debug for StoreBackend {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Sqlite(path) => formatter.debug_tuple("Sqlite").field(path).finish(),
            Self::Postgres(_) => formatter.write_str("Postgres(<redacted>)"),
        }
    }
}

/// Aggregate database counts displayed by operational and management views.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct StoreCounts {
    pub published: usize,
    pub consensus: usize,
    pub contributions: usize,
    pub seeded: usize,
    pub quarantined: usize,
}

/// A single observation uploaded by one of an account's devices.
#[derive(Clone, Debug)]
pub(crate) struct OwnContribution {
    pub key: CellKey,
    pub device: String,
    pub lat: f64,
    pub lon: f64,
    pub range_m: f64,
    pub samples: i64,
    pub updated_s: i64,
}

/// A page of account observations and the total number available for pagination.
pub(crate) struct OwnContributionPage {
    pub rows: Vec<OwnContribution>,
    pub total: usize,
}

/// Account-owned observation filters shared by the table and personal export.
#[derive(Clone, Default)]
pub(crate) struct OwnFilter {
    pub device: String,
    pub mcc: Option<i64>,
    pub from_s: Option<i64>,
    pub to_s: Option<i64>,
}

/// The consensus change caused by removing an account observation.
pub(crate) enum OwnContributionChange {
    Updated(Consensus),
    Removed(CellKey),
}

fn initialize_schema(connection: &Connection) -> Result<()> {
    connection.execute_batch(
        "PRAGMA journal_mode=WAL;
         PRAGMA foreign_keys=ON;
         CREATE TABLE IF NOT EXISTS contributions (
           radio TEXT NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL,
           area INTEGER NOT NULL, cid INTEGER NOT NULL, device TEXT NOT NULL,
           lat REAL NOT NULL, lon REAL NOT NULL, range_m REAL NOT NULL,
           samples INTEGER NOT NULL, updated_s INTEGER NOT NULL,
           PRIMARY KEY (radio, mcc, mnc, area, cid, device)
         );
         CREATE TABLE IF NOT EXISTS consensus (
           radio TEXT NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL,
           area INTEGER NOT NULL, cid INTEGER NOT NULL, lat REAL NOT NULL,
           lon REAL NOT NULL, range_m REAL NOT NULL, samples INTEGER NOT NULL,
           devices INTEGER NOT NULL, seeded INTEGER NOT NULL, updated_s INTEGER NOT NULL,
           PRIMARY KEY (radio, mcc, mnc, area, cid)
         );
         CREATE INDEX IF NOT EXISTS consensus_sync ON consensus(mcc, updated_s);",
    )?;
    connection.execute_batch(
        "CREATE INDEX IF NOT EXISTS contributions_expiry ON contributions(updated_s);",
    )?;
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS tower_moderation (
           radio TEXT NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL,
           area INTEGER NOT NULL, cid INTEGER NOT NULL, quarantined INTEGER NOT NULL DEFAULT 1,
           PRIMARY KEY (radio,mcc,mnc,area,cid)
         );
         CREATE TABLE IF NOT EXISTS server_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
         CREATE TABLE IF NOT EXISTS admin_audit (
           id INTEGER PRIMARY KEY, actor_id INTEGER NOT NULL, action TEXT NOT NULL,
           target TEXT NOT NULL, at_s INTEGER NOT NULL
         );
         CREATE TABLE IF NOT EXISTS admin_jobs (
           id INTEGER PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL,
           processed INTEGER NOT NULL DEFAULT 0, started_s INTEGER NOT NULL,
           finished_s INTEGER
         );
         CREATE TABLE IF NOT EXISTS tower_removals (
           radio TEXT NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL,
           area INTEGER NOT NULL, cid INTEGER NOT NULL, updated_s INTEGER NOT NULL,
           PRIMARY KEY (radio,mcc,mnc,area,cid)
         );
         CREATE INDEX IF NOT EXISTS tower_removals_sync ON tower_removals(mcc,updated_s);
         CREATE TABLE IF NOT EXISTS admin_import_rejections (
           job_id INTEGER NOT NULL REFERENCES admin_jobs(id) ON DELETE CASCADE,
           row_number INTEGER NOT NULL, reason TEXT NOT NULL, input TEXT NOT NULL,
           PRIMARY KEY(job_id,row_number)
         );",
    )?;
    connection.execute_batch(
        "CREATE INDEX IF NOT EXISTS contributions_device ON contributions(device);",
    )?;
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS users (
           id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE,
           password_hash TEXT NOT NULL, admin INTEGER NOT NULL DEFAULT 0,
           suspended INTEGER NOT NULL DEFAULT 0,
           sharing_enabled INTEGER NOT NULL DEFAULT 1,
           email_verified INTEGER NOT NULL DEFAULT 1
         );
         CREATE TABLE IF NOT EXISTS account_deleted_keys (
           account_id INTEGER NOT NULL,
           radio TEXT NOT NULL,mcc INTEGER NOT NULL,mnc INTEGER NOT NULL,
           area INTEGER NOT NULL,cid INTEGER NOT NULL,device TEXT NOT NULL,
           PRIMARY KEY(account_id,radio,mcc,mnc,area,cid,device)
         );
         CREATE TABLE IF NOT EXISTS email_verifications (
           token_hash TEXT PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
           email TEXT NOT NULL,expires_s INTEGER NOT NULL
         );
         CREATE TABLE IF NOT EXISTS auth_tokens (
           token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
           session_id TEXT NOT NULL, kind TEXT NOT NULL, expires_s INTEGER NOT NULL
         );
         CREATE TABLE IF NOT EXISTS web_impersonations (
           token_hash TEXT PRIMARY KEY REFERENCES auth_tokens(token_hash) ON DELETE CASCADE,
           admin_token_hash TEXT NOT NULL REFERENCES auth_tokens(token_hash) ON DELETE CASCADE,
           actor_id INTEGER NOT NULL REFERENCES users(id),
           target_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE
         );
         CREATE INDEX IF NOT EXISTS web_impersonations_admin ON web_impersonations(admin_token_hash);
         CREATE INDEX IF NOT EXISTS auth_tokens_user ON auth_tokens(user_id);
         CREATE INDEX IF NOT EXISTS auth_tokens_session ON auth_tokens(session_id);
         CREATE INDEX IF NOT EXISTS auth_tokens_expiry ON auth_tokens(expires_s);
         CREATE TABLE IF NOT EXISTS password_resets (
           token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
           expires_s INTEGER NOT NULL
         );
         CREATE INDEX IF NOT EXISTS password_resets_expiry ON password_resets(expires_s);",
    )?;
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS privacy_consents (
           id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
           purpose TEXT NOT NULL, notice_version TEXT NOT NULL, granted INTEGER NOT NULL, at_s INTEGER NOT NULL
         );
         CREATE INDEX IF NOT EXISTS privacy_consents_current ON privacy_consents(account_id,purpose,id);",
    )?;
    initialize_debug_schema(connection)?;
    Ok(())
}

fn initialize_debug_schema(connection: &Connection) -> Result<()> {
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS debug_sessions (
           id TEXT PRIMARY KEY, account_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
           client_id TEXT NOT NULL,
           context_json TEXT NOT NULL, created_s INTEGER NOT NULL, updated_s INTEGER NOT NULL,
           finished_s INTEGER, incomplete INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0
         );
         CREATE INDEX IF NOT EXISTS debug_sessions_account ON debug_sessions(account_id,created_s);
         CREATE INDEX IF NOT EXISTS debug_sessions_expiry ON debug_sessions(created_s);
         CREATE TABLE IF NOT EXISTS debug_batches (
           session_id TEXT NOT NULL REFERENCES debug_sessions(id) ON DELETE CASCADE,
           seq INTEGER NOT NULL, digest TEXT NOT NULL, bytes INTEGER NOT NULL,
           PRIMARY KEY(session_id,seq)
         );
         CREATE TABLE IF NOT EXISTS debug_entries (
           session_id TEXT NOT NULL REFERENCES debug_sessions(id) ON DELETE CASCADE,
           seq INTEGER NOT NULL, item INTEGER NOT NULL, kind TEXT NOT NULL,
           elapsed_ms INTEGER NOT NULL, line TEXT NOT NULL,
           PRIMARY KEY(session_id,seq,item)
         );
         CREATE INDEX IF NOT EXISTS debug_entries_kind ON debug_entries(session_id,kind,seq,item);",
    )?;
    let has_client_id = connection
        .prepare("PRAGMA table_info(debug_sessions)")?
        .query_map(params![], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "client_id");
    if !has_client_id {
        connection.execute_batch(
            "ALTER TABLE debug_sessions ADD COLUMN client_id TEXT NOT NULL DEFAULT '';
            UPDATE debug_sessions SET client_id=id WHERE client_id='';",
        )?;
    }
    connection.execute_batch("CREATE UNIQUE INDEX IF NOT EXISTS debug_sessions_client ON debug_sessions(account_id,client_id);")?;
    Ok(())
}

fn migrate_user_columns(connection: &Connection) -> Result<()> {
    let user_columns = connection
        .prepare("PRAGMA table_info(users)")?
        .query_map(params![], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    if !user_columns.iter().any(|column| column == "suspended") {
        connection.execute(
            "ALTER TABLE users ADD COLUMN suspended INTEGER NOT NULL DEFAULT 0",
            params![],
        )?;
    }
    if !user_columns
        .iter()
        .any(|column| column == "sharing_enabled")
    {
        connection.execute(
            "ALTER TABLE users ADD COLUMN sharing_enabled INTEGER NOT NULL DEFAULT 1",
            params![],
        )?;
    }
    if !user_columns.iter().any(|column| column == "email_verified") {
        connection.execute(
            "ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 1",
            params![],
        )?;
    }
    Ok(())
}

impl CellStore {
    /// Open the database, create its schema, and enable WAL for concurrent readers.
    ///
    /// # Errors
    ///
    /// Returns an error when the database cannot be opened or initialized.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let store = Self {
            backend: StoreBackend::Sqlite(path.as_ref().to_path_buf()),
        };
        let connection = store.connection()?;
        initialize_schema(&connection)?;
        migrate_user_columns(&connection)?;
        Ok(store)
    }

    /// Open a PostgreSQL database and create the server's relational schema when absent.
    /// The connection URL is never included in errors or diagnostic output.
    ///
    /// # Errors
    ///
    /// Returns an error for an invalid URL, TLS setup failure, unsupported schema version, or connection failure.
    pub fn open_postgres(url: &str) -> Result<Self> {
        let config = postgres::Config::from_str(url)
            .map_err(|_| anyhow::anyhow!("invalid PostgreSQL connection URL"))?;
        let tls = postgres_native_tls::MakeTlsConnector::new(
            native_tls::TlsConnector::builder()
                .build()
                .context("cannot configure PostgreSQL TLS")?,
        );
        let manager = r2d2_postgres::PostgresConnectionManager::new(config, tls);
        let pool = r2d2::Pool::builder()
            .max_size(16)
            .build(manager)
            .context("cannot connect to PostgreSQL")?;
        let store = Self {
            backend: StoreBackend::Postgres(pool),
        };
        let connection = store.connection()?;
        connection.execute_batch("SET client_min_messages=warning")?;
        connection.execute_batch(include_str!("postgres_schema.sql"))?;
        let version: i64 = connection.query_row(
            "SELECT MAX(version) FROM server_schema_version",
            params![],
            |row| row.get(0),
        )?;
        anyhow::ensure!(
            version == 1,
            "unsupported PostgreSQL schema version {version}"
        );
        Ok(store)
    }

    /// Copy a stopped SQLite server's full state into an empty PostgreSQL database.
    /// The PostgreSQL transaction rolls back if any row or verification fails.
    ///
    /// # Errors
    ///
    /// Returns an error unless this store is PostgreSQL, the source exists and is readable,
    /// the target is empty, and every source row can be copied and counted.
    pub fn migrate_from_sqlite(&self, source_path: impl AsRef<Path>) -> Result<()> {
        anyhow::ensure!(
            matches!(self.backend, StoreBackend::Postgres(_)),
            "migration target must be PostgreSQL"
        );
        let source = rusqlite::Connection::open_with_flags(
            source_path,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        )
        .context("cannot open SQLite migration source")?;
        source.execute_batch("BEGIN; PRAGMA query_only=ON")?;
        let mut target = self.connection()?;
        let transaction = target.transaction()?;
        for (table, columns) in MIGRATION_TABLES {
            let count: i64 = transaction.query_row(
                &format!("SELECT COUNT(*) FROM {table}"),
                params![],
                |row| row.get(0),
            )?;
            anyhow::ensure!(count == 0, "PostgreSQL target is not empty: {table}");
            // A stopped database from before purpose consent has no receipt table yet.
            if *table == "privacy_consents" {
                let exists: i64 = source.query_row("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='privacy_consents'", [], |row| row.get(0))?;
                if exists == 0 {
                    continue;
                }
            }
            let column_count = columns.split(',').count();
            let select = format!("SELECT {columns} FROM {table}");
            let insert = format!(
                "INSERT INTO {table}({columns}) VALUES ({})",
                (1..=column_count)
                    .map(|index| format!("?{index}"))
                    .collect::<Vec<_>>()
                    .join(",")
            );
            let mut statement = source.prepare(&select)?;
            let mut rows = statement.query([])?;
            let mut copied = 0i64;
            while let Some(row) = rows.next()? {
                let values = (0..column_count)
                    .map(|index| row.get_ref(index).map(crate::db::value_to_owned))
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                transaction.execute(&insert, values)?;
                copied += 1;
            }
            let stored: i64 = transaction.query_row(
                &format!("SELECT COUNT(*) FROM {table}"),
                params![],
                |row| row.get(0),
            )?;
            anyhow::ensure!(
                stored == copied,
                "PostgreSQL migration count mismatch in {table}"
            );
        }
        for table in ["users", "admin_jobs", "admin_audit", "privacy_consents"] {
            transaction.query_row(
                &format!("SELECT setval(pg_get_serial_sequence('{table}','id'), COALESCE(MAX(id),1), MAX(id) IS NOT NULL) FROM {table}"),
                params![],
                |row| row.get::<_, i64>(0),
            )?;
        }
        transaction.commit()?;
        source.execute_batch("COMMIT")?;
        Ok(())
    }

    pub(crate) fn connection(&self) -> Result<Connection> {
        let connection = match &self.backend {
            StoreBackend::Sqlite(path) => {
                let connection = Connection::open(path)
                    .with_context(|| format!("cannot open {}", path.display()))?;
                connection.execute_batch("PRAGMA foreign_keys=ON")?;
                connection.busy_timeout(std::time::Duration::from_secs(3600))?;
                connection
            }
            StoreBackend::Postgres(pool) => Connection::postgres(pool)?,
        };
        Ok(connection)
    }

    /// Remove expired opt-in diagnostics, including their batches and events by foreign-key cascade.
    ///
    /// # Errors
    ///
    /// Returns an error when the database cannot be opened or cleanup fails.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    pub fn prune_debug_sessions(&self) -> Result<usize> {
        let now_s = i64::try_from(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)?
                .as_secs(),
        )?;
        Ok(self.connection()?.execute(
            "DELETE FROM debug_sessions WHERE created_s<?1",
            [now_s - 30 * 24 * 60 * 60],
        )?)
    }

    /// Keep receipt history while enforcing independent, versioned purpose consent.
    pub(crate) fn has_privacy_consent(
        &self,
        account_id: i64,
        purpose: &str,
        version: &str,
    ) -> Result<bool> {
        let connection = self.connection()?;
        let current: Option<(String, bool)> = connection.query_row(
            "SELECT notice_version,granted FROM privacy_consents WHERE account_id=?1 AND purpose=?2 ORDER BY id DESC LIMIT 1",
            params![account_id,purpose], |row| Ok((row.get(0)?,row.get(1)?)),
        ).optional()?;
        Ok(current.is_some_and(|(accepted, granted)| granted && accepted == version))
    }

    pub(crate) fn grant_privacy_consent(
        &self,
        account_id: i64,
        purpose: &str,
        version: &str,
    ) -> Result<()> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
            "INSERT INTO privacy_consents(account_id,purpose,notice_version,granted,at_s) VALUES (?1,?2,?3,1,?4)",
            params![account_id,purpose,version,current_time_s()?],
        )?;
        if purpose == "tower_upload" {
            transaction.execute(
                "UPDATE users SET sharing_enabled=1 WHERE id=?1 AND email_verified=1",
                [account_id],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }

    /// Withdraw consent and erase that purpose's stored data in one transaction.
    pub(crate) fn withdraw_privacy_consent(
        &self,
        account_id: i64,
        purpose: &str,
        policy: &Policy,
    ) -> Result<Vec<OwnContributionChange>> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
            "INSERT INTO privacy_consents(account_id,purpose,notice_version,granted,at_s)
             VALUES (?1,?2,COALESCE((SELECT notice_version FROM privacy_consents WHERE account_id=?1 AND purpose=?2 ORDER BY id DESC LIMIT 1),''),0,?3)",
            params![account_id,purpose,current_time_s()?],
        )?;
        let mut changes = Vec::new();
        if purpose == "tower_upload" {
            let pattern = account_device_pattern(account_id);
            let keys = {
                let mut statement = transaction.prepare("SELECT DISTINCT radio,mcc,mnc,area,cid FROM contributions WHERE device GLOB ?1")?;
                statement
                    .query_map([&pattern], row_to_key)?
                    .collect::<rusqlite::Result<Vec<_>>>()?
            };
            transaction.execute("INSERT OR IGNORE INTO account_deleted_keys(account_id,radio,mcc,mnc,area,cid,device)
                SELECT ?1,radio,mcc,mnc,area,cid,'*' FROM contributions WHERE device GLOB ?2", params![account_id,pattern])?;
            transaction.execute("DELETE FROM contributions WHERE device GLOB ?1", [&pattern])?;
            transaction.execute(
                "UPDATE users SET sharing_enabled=0 WHERE id=?1",
                [account_id],
            )?;
            changes = keys
                .iter()
                .map(|key| update_consensus_after_deletion(&transaction, key, policy))
                .collect::<Result<Vec<_>>>()?;
        } else {
            transaction.execute(
                "DELETE FROM debug_sessions WHERE account_id=?1",
                [account_id],
            )?;
        }
        transaction.commit()?;
        Ok(changes)
    }

    /// Reapply a recorded withdrawal to a restored database before it serves traffic.
    ///
    /// # Errors
    ///
    /// Returns an error for an invalid purpose or a failed database transaction.
    pub fn replay_withdrawal(&self, account_id: i64, purpose: &str, policy: &Policy) -> Result<()> {
        anyhow::ensure!(
            account_id > 0 && matches!(purpose, "tower_upload" | "diagnostics"),
            "invalid deletion replay entry"
        );
        let exists: bool = self.connection()?.query_row(
            "SELECT EXISTS(SELECT 1 FROM users WHERE id=?1)",
            [account_id],
            |row| row.get(0),
        )?;
        if !exists {
            return Ok(());
        }
        self.withdraw_privacy_consent(account_id, purpose, policy)?;
        Ok(())
    }

    /// Reapply an account closure to a restored database before it serves traffic.
    ///
    /// # Errors
    ///
    /// Returns an error if the database transaction fails or the identifier is invalid.
    pub fn replay_account_closure(&self, account_id: i64, policy: &Policy) -> Result<()> {
        anyhow::ensure!(account_id > 0, "invalid deletion replay entry");
        self.close_own_account(account_id, policy)?;
        Ok(())
    }

    /// Invalidate sessions and one-use links from a restored snapshot before public traffic resumes.
    ///
    /// # Errors
    ///
    /// Returns an error if the database transaction fails.
    pub fn revoke_restored_sessions(&self) -> Result<()> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute("DELETE FROM auth_tokens", params![])?;
        transaction.execute("DELETE FROM password_resets", params![])?;
        transaction.execute("DELETE FROM email_verifications", params![])?;
        transaction.commit()?;
        Ok(())
    }

    /// Run bounded, daily retention for all transient server records and live tower data.
    pub(crate) fn prune_retained_data(
        &self,
        policy: &Policy,
    ) -> Result<(usize, Vec<OwnContributionChange>)> {
        let now = current_time_s()?;
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let current = time::OffsetDateTime::now_utc();
        let previous_year = current.year() - 1;
        let cutoff_date = current.date().replace_year(previous_year).or_else(|_| {
            time::Date::from_calendar_date(previous_year, time::Month::February, 28)
        })?;
        let cutoff = cutoff_date
            .with_time(current.time())
            .assume_utc()
            .unix_timestamp();
        let keys = {
            let mut statement = transaction.prepare("SELECT DISTINCT radio,mcc,mnc,area,cid FROM contributions WHERE updated_s<?1 AND device!='seed' AND device!='manual'")?;
            statement
                .query_map([cutoff], row_to_key)?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        let removed = transaction.execute(
            "DELETE FROM contributions WHERE updated_s<?1 AND device!='seed' AND device!='manual'",
            [cutoff],
        )?;
        let changes = keys
            .iter()
            .map(|key| update_consensus_after_deletion(&transaction, key, policy))
            .collect::<Result<Vec<_>>>()?;
        transaction.execute(
            "DELETE FROM debug_sessions WHERE created_s<?1",
            [now - 30 * 24 * 60 * 60],
        )?;
        transaction.execute("DELETE FROM auth_tokens WHERE expires_s<?1", [now])?;
        transaction.execute("DELETE FROM email_verifications WHERE expires_s<?1", [now])?;
        transaction.execute("DELETE FROM password_resets WHERE expires_s<?1", [now])?;
        transaction.execute("DELETE FROM admin_import_rejections WHERE job_id IN (SELECT id FROM admin_jobs WHERE COALESCE(finished_s,started_s)<?1)", [now - 30*24*60*60])?;
        transaction.execute(
            "DELETE FROM admin_audit WHERE at_s<?1",
            [now - 90 * 24 * 60 * 60],
        )?;
        transaction.commit()?;
        Ok((removed, changes))
    }

    /// Account sharing and email status are checked again under the upload gate before writing.
    pub(crate) fn account_sharing_status(&self, account_id: i64) -> Result<(bool, bool)> {
        Ok(self.connection()?.query_row(
            "SELECT sharing_enabled,email_verified FROM users WHERE id=?1",
            [account_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?)
    }

    pub(crate) fn set_account_sharing(&self, account_id: i64, enabled: bool) -> Result<bool> {
        Ok(self.connection()?.execute(
            "UPDATE users SET sharing_enabled=?1 WHERE id=?2 AND (email_verified=1 OR ?1=0)",
            params![enabled, account_id],
        )? > 0)
    }

    /// Remove an ordinary account and all its observations, tokens, and deletion markers atomically.
    pub(crate) fn close_own_account(
        &self,
        account_id: i64,
        policy: &Policy,
    ) -> Result<Option<Vec<OwnContributionChange>>> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let ordinary: bool = transaction.query_row(
            "SELECT COALESCE((SELECT CASE WHEN admin=0 THEN 1 ELSE 0 END FROM users WHERE id=?1),0)",
            [account_id],
            |row| row.get(0),
        )?;
        if !ordinary {
            return Ok(None);
        }
        let pattern = account_device_pattern(account_id);
        let keys = {
            let mut statement = transaction.prepare(
                "SELECT DISTINCT radio,mcc,mnc,area,cid FROM contributions WHERE device GLOB ?1",
            )?;
            statement
                .query_map([&pattern], row_to_key)?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        transaction.execute("DELETE FROM contributions WHERE device GLOB ?1", [&pattern])?;
        let changes = keys
            .iter()
            .map(|key| update_consensus_after_deletion(&transaction, key, policy))
            .collect::<Result<Vec<_>>>()?;
        transaction.execute(
            "DELETE FROM account_deleted_keys WHERE account_id=?1",
            [account_id],
        )?;
        transaction.execute("DELETE FROM users WHERE id=?1", [account_id])?;
        transaction.commit()?;
        Ok(Some(changes))
    }

    /// Merge one device's upload atomically and return all changed consensuses for live management.
    ///
    /// # Errors
    ///
    /// Returns an error when the transaction cannot read or persist its changes.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    pub fn contribute(
        &self,
        device: &str,
        towers: &[CellTower],
        now_s: i64,
        policy: &Policy,
    ) -> Result<(UploadResult, Vec<Consensus>)> {
        policy.validate()?;
        let mut connection = self.connection()?;
        // Acquire the single SQLite writer slot before doing any reads. A deferred transaction can
        // otherwise fail while upgrading its lock when two uploads arrive together.
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut accepted = 0;
        let mut rejected = 0;
        let mut changed_keys = BTreeSet::new();
        let account_id = device
            .strip_prefix("account:")
            .and_then(|value| value.split_once(':'))
            .and_then(|(id, _)| id.parse::<i64>().ok());
        if let Some(id) = account_id {
            let allowed: bool = transaction.query_row(
                "SELECT COALESCE((SELECT CASE WHEN sharing_enabled=1 AND email_verified=1 THEN 1 ELSE 0 END FROM users WHERE id=?1),1)",
                [id], |row| row.get(0),
            )?;
            if !allowed {
                return Ok((
                    UploadResult {
                        accepted: 0,
                        rejected: towers.len(),
                    },
                    Vec::new(),
                ));
            }
        }

        // One connection handles the batch; cached statements avoid recompiling identical SQL
        // for each tower while keeping the same transaction and per-device movement checks.
        for tower in towers {
            let blocked = if let Some(id) = account_id {
                transaction.query_row(
                    "SELECT EXISTS(SELECT 1 FROM account_deleted_keys WHERE account_id=?1 AND radio=?2 AND mcc=?3 AND mnc=?4 AND area=?5 AND cid=?6 AND device IN (?7,'*'))",
                    params![id, tower.key.radio.to_string(), tower.key.mcc, tower.key.mnc, tower.key.area, tower.key.cid, device],
                    |row| row.get::<_, bool>(0),
                )?
            } else {
                false
            };
            if !plausible(tower, policy)
                || !Self::accepts_movement(&transaction, device, tower, policy)?
                || blocked
            {
                rejected += 1;
                continue;
            }
            let samples = if device == SEED_DEVICE {
                tower.samples
            } else {
                tower.samples.clamp(1, policy.max_samples_per_device)
            };
            transaction
                .prepare_cached(
                    "INSERT INTO contributions
                 (radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)
                 ON CONFLICT(radio,mcc,mnc,area,cid,device) DO UPDATE SET
                 lat=excluded.lat,lon=excluded.lon,range_m=excluded.range_m,
                 samples=excluded.samples,updated_s=excluded.updated_s",
                )?
                .execute(params![
                    tower.key.radio.to_string(),
                    tower.key.mcc,
                    tower.key.mnc,
                    tower.key.area,
                    tower.key.cid,
                    device,
                    tower.lat,
                    tower.lon,
                    tower.range_m,
                    samples,
                    now_s,
                ])?;
            changed_keys.insert(tower.key.clone());
            accepted += 1;
        }

        let mut changed = Vec::new();
        for key in changed_keys {
            let consensus = recompute(&transaction, &key, policy)?;
            save_consensus(&transaction, &consensus)?;
            if consensus.seeded || consensus.devices >= policy.min_devices {
                let hidden: bool = transaction.query_row(
                    "SELECT COALESCE((SELECT quarantined FROM tower_moderation WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5),0)",
                    params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
                    |row| row.get(0),
                )?;
                if !hidden {
                    clear_removal(&transaction, &key)?;
                }
            }
            changed.push(consensus);
        }
        transaction.commit()?;
        Ok((UploadResult { accepted, rejected }, changed))
    }

    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    fn accepts_movement(
        transaction: &Transaction<'_>,
        device: &str,
        tower: &CellTower,
        policy: &Policy,
    ) -> Result<bool> {
        if device == SEED_DEVICE {
            return Ok(true);
        }
        let previous = transaction
            .prepare_cached(
                "SELECT lat,lon FROM contributions WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5 AND device=?6",
            )?
            .query_row(params![tower.key.radio.to_string(), tower.key.mcc, tower.key.mnc, tower.key.area, tower.key.cid, device], |row| {
                Ok((row.get::<_, f64>(0)?, row.get::<_, f64>(1)?))
            })
            .optional()?;
        Ok(previous.is_none_or(|(lat, lon)| {
            distance_m(lat, lon, tower.lat, tower.lon) <= policy.max_jump_m
        }))
    }

    /// Add trusted seed data, which is immediately published.
    ///
    /// # Errors
    ///
    /// Returns an error when the database transaction fails.
    pub fn seed(
        &self,
        towers: &[CellTower],
        now_s: i64,
        policy: &Policy,
    ) -> Result<(UploadResult, Vec<Consensus>)> {
        self.contribute(SEED_DEVICE, towers, now_s, policy)
    }

    /// Return published consensuses for sync or management, ordered deterministically.
    ///
    /// # Errors
    ///
    /// Returns an error when the database query fails or a stored radio value is invalid.
    pub fn query(
        &self,
        mccs: Option<&HashSet<i64>>,
        since_s: i64,
        limit: Option<usize>,
        policy: &Policy,
    ) -> Result<Vec<Consensus>> {
        self.query_internal(mccs, since_s, limit, Some(policy), false)
    }

    /// Write a consistent published snapshot without retaining the full response in memory.
    pub(crate) fn write_published_gzip<W: std::io::Write>(
        &self,
        output: W,
        mccs: Option<&HashSet<i64>>,
        since_s: i64,
        policy: &Policy,
        on_snapshot: impl FnOnce(),
    ) -> Result<()> {
        let mut connection = self.connection()?;
        let transaction = connection.read_transaction()?;
        let encoder = GzEncoder::new(output, Compression::default());
        let mut writer = csv::WriterBuilder::new()
            .has_headers(false)
            .from_writer(encoder);
        writer.write_record(crate::csv_format::HEADER)?;
        let sorted_mccs = mccs.map(|mccs| {
            let mut sorted = mccs.iter().copied().collect::<Vec<_>>();
            sorted.sort_unstable();
            sorted
        });
        let mut cursor: Option<(i64, String, i64, i64, i64, i64)> = None;
        let mut on_snapshot = Some(on_snapshot);
        loop {
            let mut sql = String::from("SELECT radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded,updated_s
                FROM consensus WHERE updated_s>=? AND (seeded=1 OR devices>=?)
                AND NOT EXISTS (SELECT 1 FROM tower_moderation m WHERE m.radio=consensus.radio AND m.mcc=consensus.mcc
                AND m.mnc=consensus.mnc AND m.area=consensus.area AND m.cid=consensus.cid AND m.quarantined=1)");
            let mut values = vec![
                Value::Integer(since_s),
                Value::Integer(i64::try_from(policy.min_devices)?),
            ];
            if let Some(mccs) = &sorted_mccs {
                sql.push_str(" AND mcc IN (");
                sql.push_str(
                    &std::iter::repeat_n("?", mccs.len())
                        .collect::<Vec<_>>()
                        .join(","),
                );
                sql.push(')');
                values.extend(mccs.iter().copied().map(Value::Integer));
            }
            if let Some((updated, radio, mcc, mnc, area, cid)) = &cursor {
                sql.push_str(" AND (updated_s,radio,mcc,mnc,area,cid)>(?,?,?,?,?,?)");
                values.extend([
                    Value::Integer(*updated),
                    Value::Text(radio.clone()),
                    Value::Integer(*mcc),
                    Value::Integer(*mnc),
                    Value::Integer(*area),
                    Value::Integer(*cid),
                ]);
            }
            sql.push_str(" ORDER BY updated_s,radio,mcc,mnc,area,cid LIMIT ?");
            values.push(Value::Integer(i64::try_from(EXPORT_PAGE_SIZE)?));
            let mut statement = transaction.prepare(&sql)?;
            let page = statement
                .query_map(params_from_iter(values), row_to_consensus)?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            if let Some(release) = on_snapshot.take() {
                release();
            }
            if page.is_empty() {
                break;
            }
            for item in &page {
                crate::csv_format::write_tower_record(&mut writer, item)?;
            }
            let last = page.last().expect("nonempty page");
            cursor = Some((
                last.updated_s,
                last.tower.key.radio.to_string(),
                last.tower.key.mcc,
                last.tower.key.mnc,
                last.tower.key.area,
                last.tower.key.cid,
            ));
            if page.len() < EXPORT_PAGE_SIZE {
                break;
            }
        }
        let encoder = writer
            .into_inner()
            .map_err(csv::IntoInnerError::into_error)?;
        let mut output = encoder.finish()?;
        std::io::Write::flush(&mut output)?;
        transaction.commit()?;
        Ok(())
    }

    /// Return published and pending consensuses for authenticated management clients.
    ///
    /// # Errors
    ///
    /// Returns an error when the database query fails or a stored radio value is invalid.
    pub fn query_all(
        &self,
        mccs: Option<&HashSet<i64>>,
        since_s: i64,
        limit: Option<usize>,
    ) -> Result<Vec<Consensus>> {
        self.query_internal(mccs, since_s, limit, None, false)
    }

    /// Return the most recently updated consensuses for the read-only management interface.
    ///
    /// # Errors
    ///
    /// Returns an error when the database query fails or a stored radio value is invalid.
    pub fn query_recent_all(
        &self,
        mccs: Option<&HashSet<i64>>,
        limit: usize,
    ) -> Result<Vec<Consensus>> {
        self.query_internal(mccs, 0, Some(limit), None, true)
    }

    /// Page through all consensuses, including pending and moderated towers.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    pub(crate) fn admin_tower_page(
        &self,
        mccs: Option<&HashSet<i64>>,
        status: &str,
        limit: usize,
        offset: usize,
        policy: &Policy,
    ) -> Result<Vec<(Consensus, bool)>> {
        let connection = self.connection()?;
        let hidden = "EXISTS (SELECT 1 FROM tower_moderation m WHERE m.radio=c.radio AND m.mcc=c.mcc AND m.mnc=c.mnc AND m.area=c.area AND m.cid=c.cid AND m.quarantined=1)";
        let mut sql = format!(
            "SELECT c.radio,c.mcc,c.mnc,c.area,c.cid,c.lat,c.lon,c.range_m,c.samples,c.devices,c.seeded,c.updated_s,{hidden} FROM consensus c WHERE 1=1"
        );
        let mut values = Vec::new();
        if let Some(mccs) = mccs {
            sql.push_str(" AND c.mcc IN (");
            sql.push_str(
                &std::iter::repeat_n("?", mccs.len())
                    .collect::<Vec<_>>()
                    .join(","),
            );
            sql.push(')');
            values.extend(mccs.iter().copied().map(Value::Integer));
        }
        match status {
            "quarantined" => write!(sql, " AND {hidden}")?,
            "seeded" => write!(sql, " AND NOT {hidden} AND c.seeded=1")?,
            "published" => {
                write!(sql, " AND NOT {hidden} AND c.seeded=0 AND c.devices>=?")?;
                values.push(Value::Integer(i64::try_from(policy.min_devices)?));
            }
            "pending" => {
                write!(sql, " AND NOT {hidden} AND c.seeded=0 AND c.devices<?")?;
                values.push(Value::Integer(i64::try_from(policy.min_devices)?));
            }
            _ => {}
        }
        sql.push_str(
            " ORDER BY c.updated_s DESC,c.radio,c.mcc,c.mnc,c.area,c.cid LIMIT ? OFFSET ?",
        );
        values.push(Value::Integer(i64::try_from(limit)?));
        values.push(Value::Integer(i64::try_from(offset)?));
        let mut statement = connection.prepare(&sql)?;
        Ok(statement
            .query_map(params_from_iter(values), |row| {
                Ok((row_to_consensus(row)?, row.get(12)?))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Find moderated keys in bounded batches so an upload does not open one connection per tower.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    pub(crate) fn visible_changes(&self, changed: Vec<Consensus>) -> Result<Vec<Consensus>> {
        if changed.is_empty() {
            return Ok(changed);
        }
        let connection = self.connection()?;
        let mut hidden = HashSet::new();
        for batch in changed.chunks(MODERATION_BATCH_SIZE) {
            let placeholders = std::iter::repeat_n("(?,?,?,?,?)", batch.len())
                .collect::<Vec<_>>()
                .join(",");
            let sql = format!(
                "SELECT radio,mcc,mnc,area,cid FROM tower_moderation WHERE quarantined=1 AND (radio,mcc,mnc,area,cid) IN ({placeholders})"
            );
            let values = batch
                .iter()
                .flat_map(|consensus| {
                    let key = &consensus.tower.key;
                    [
                        Value::Text(key.radio.to_string()),
                        Value::Integer(key.mcc),
                        Value::Integer(key.mnc),
                        Value::Integer(key.area),
                        Value::Integer(key.cid),
                    ]
                })
                .collect::<Vec<_>>();
            let mut statement = connection.prepare(&sql)?;
            hidden.extend(
                statement
                    .query_map(params_from_iter(values), row_to_key)?
                    .collect::<rusqlite::Result<Vec<_>>>()?,
            );
        }
        Ok(changed
            .into_iter()
            .filter(|consensus| !hidden.contains(&consensus.tower.key))
            .collect())
    }

    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    fn query_internal(
        &self,
        mccs: Option<&HashSet<i64>>,
        since_s: i64,
        limit: Option<usize>,
        publication_policy: Option<&Policy>,
        newest_first: bool,
    ) -> Result<Vec<Consensus>> {
        let connection = self.connection()?;
        let mut query = String::from(
            "SELECT radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded,updated_s
             FROM consensus WHERE updated_s>=?",
        );
        let mut values = vec![Value::Integer(since_s)];
        if let Some(policy) = publication_policy {
            query.push_str(" AND (seeded=1 OR devices>=?)");
            values.push(Value::Integer(i64::try_from(policy.min_devices)?));
            query.push_str(" AND NOT EXISTS (SELECT 1 FROM tower_moderation m WHERE m.radio=consensus.radio AND m.mcc=consensus.mcc AND m.mnc=consensus.mnc AND m.area=consensus.area AND m.cid=consensus.cid AND m.quarantined=1)");
        }
        if let Some(mccs) = mccs {
            query.push_str(" AND mcc IN (");
            query.push_str(
                &std::iter::repeat_n("?", mccs.len())
                    .collect::<Vec<_>>()
                    .join(","),
            );
            query.push(')');
            let mut sorted_mccs = mccs.iter().copied().collect::<Vec<_>>();
            sorted_mccs.sort_unstable();
            values.extend(sorted_mccs.into_iter().map(Value::Integer));
        }
        if newest_first {
            query.push_str(" ORDER BY updated_s DESC,radio,mcc,mnc,area,cid");
        } else {
            query.push_str(" ORDER BY updated_s,radio,mcc,mnc,area,cid");
        }
        if let Some(limit) = limit {
            query.push_str(" LIMIT ?");
            values.push(Value::Integer(i64::try_from(limit)?));
        }
        let mut statement = connection.prepare(&query)?;
        let rows = statement.query_map(params_from_iter(values), row_to_consensus)?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    /// Return one consensus, including an unpublished one, for diagnostics and tests.
    ///
    /// # Errors
    ///
    /// Returns an error when the database query fails or the stored row is invalid.
    pub fn consensus(&self, key: &CellKey) -> Result<Option<Consensus>> {
        self.connection()?
            .query_row(
                "SELECT radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded,updated_s
                 FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
                params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
                row_to_consensus,
            )
            .optional()
            .map_err(Into::into)
    }

    /// Remove all device contributions and the consensus for one cell.
    ///
    /// # Errors
    ///
    /// Returns an error when the delete transaction cannot be committed.
    pub fn delete(&self, key: &CellKey) -> Result<bool> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
            "DELETE FROM contributions WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
        )?;
        let removed = transaction.execute(
            "DELETE FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
        )?;
        if removed > 0 {
            record_removal(&transaction, key)?;
        }
        transaction.commit()?;
        Ok(removed > 0)
    }

    /// List only contributions from devices linked to this account, newest first.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    pub(crate) fn own_contributions(
        &self,
        account_id: i64,
        filter: &OwnFilter,
        limit: usize,
        offset: usize,
    ) -> Result<OwnContributionPage> {
        let connection = self.connection()?;
        let pattern = account_device_pattern(account_id);
        let device = device_filter_pattern(&filter.device);
        let total: i64 = connection.query_row(
            "SELECT COUNT(*) FROM contributions WHERE device GLOB ?1 AND device LIKE ?2 ESCAPE '\\'
             AND (CAST(?3 AS BIGINT) IS NULL OR mcc=?3) AND (CAST(?4 AS BIGINT) IS NULL OR updated_s>=?4) AND (CAST(?5 AS BIGINT) IS NULL OR updated_s<=?5)",
            params![pattern, device, filter.mcc, filter.from_s, filter.to_s],
            |row| row.get(0),
        )?;
        let mut statement = connection.prepare(
            "SELECT radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s
             FROM contributions WHERE device GLOB ?1 AND device LIKE ?2 ESCAPE '\\'
             AND (CAST(?3 AS BIGINT) IS NULL OR mcc=?3) AND (CAST(?4 AS BIGINT) IS NULL OR updated_s>=?4) AND (CAST(?5 AS BIGINT) IS NULL OR updated_s<=?5)
             ORDER BY updated_s DESC,radio,mcc,mnc,area,cid,device LIMIT ?6 OFFSET ?7",
        )?;
        let rows = statement
            .query_map(
                params![
                    pattern,
                    device,
                    filter.mcc,
                    filter.from_s,
                    filter.to_s,
                    i64::try_from(limit)?,
                    i64::try_from(offset)?
                ],
                row_to_own_contribution,
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(OwnContributionPage {
            rows,
            total: usize::try_from(total)?,
        })
    }

    /// Stream a machine-readable account archive without password hashes or bearer tokens.
    pub(crate) fn export_account_to_path(&self, account_id: i64, path: &Path) -> Result<()> {
        let connection = self.connection()?;
        let mut out = GzEncoder::new(std::fs::File::create(path)?, Compression::default());
        let mut emit = |record: serde_json::Value| -> Result<()> {
            serde_json::to_writer(&mut out, &record)?;
            out.write_all(b"\n")?;
            Ok(())
        };
        let (email, admin, sharing, verified): (String, bool, bool, bool) = connection.query_row(
            "SELECT email,admin,sharing_enabled,email_verified FROM users WHERE id=?1",
            [account_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )?;
        emit(
            serde_json::json!({"type":"account","email":email,"admin":admin,"sharing_enabled":sharing,"email_verified":verified}),
        )?;
        let mut statement = connection.prepare("SELECT purpose,notice_version,granted,at_s FROM privacy_consents WHERE account_id=?1 ORDER BY id")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"consent","purpose":row.get::<_,String>(0)?,"notice_version":row.get::<_,String>(1)?,"granted":row.get::<_,bool>(2)?,"at_s":row.get::<_,i64>(3)?}),
            )?;
        }
        let mut statement = connection.prepare("SELECT radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s FROM contributions WHERE device GLOB ?1")?;
        let pattern = account_device_pattern(account_id);
        let mut rows = statement.query([&pattern])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"tower_observation","radio":row.get::<_,String>(0)?,"mcc":row.get::<_,i64>(1)?,"mnc":row.get::<_,i64>(2)?,"area":row.get::<_,i64>(3)?,"cid":row.get::<_,i64>(4)?,"device":row.get::<_,String>(5)?,"lat":row.get::<_,f64>(6)?,"lon":row.get::<_,f64>(7)?,"range_m":row.get::<_,f64>(8)?,"samples":row.get::<_,i64>(9)?,"updated_s":row.get::<_,i64>(10)?}),
            )?;
        }
        let mut statement = connection.prepare(
            "SELECT radio,mcc,mnc,area,cid,device FROM account_deleted_keys WHERE account_id=?1",
        )?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"deleted_tower_key","radio":row.get::<_,String>(0)?,"mcc":row.get::<_,i64>(1)?,"mnc":row.get::<_,i64>(2)?,"area":row.get::<_,i64>(3)?,"cid":row.get::<_,i64>(4)?,"device":row.get::<_,String>(5)?}),
            )?;
        }
        let mut statement = connection.prepare("SELECT id,client_id,context_json,created_s,updated_s,finished_s,incomplete FROM debug_sessions WHERE account_id=?1")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"diagnostic_session","id":row.get::<_,String>(0)?,"client_id":row.get::<_,String>(1)?,"context":serde_json::from_str::<serde_json::Value>(&row.get::<_,String>(2)?)?,"created_s":row.get::<_,i64>(3)?,"updated_s":row.get::<_,i64>(4)?,"finished_s":row.get::<_,Option<i64>>(5)?,"incomplete":row.get::<_,bool>(6)?}),
            )?;
        }
        let mut statement = connection.prepare("SELECT e.session_id,e.seq,e.item,e.kind,e.elapsed_ms,e.line FROM debug_entries e JOIN debug_sessions s ON s.id=e.session_id WHERE s.account_id=?1 ORDER BY e.session_id,e.seq,e.item")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"diagnostic_entry","session_id":row.get::<_,String>(0)?,"seq":row.get::<_,i64>(1)?,"item":row.get::<_,i64>(2)?,"kind":row.get::<_,String>(3)?,"elapsed_ms":row.get::<_,i64>(4)?,"line":row.get::<_,String>(5)?}),
            )?;
        }
        let mut statement = connection.prepare("SELECT b.session_id,b.seq,b.bytes FROM debug_batches b JOIN debug_sessions s ON s.id=b.session_id WHERE s.account_id=?1 ORDER BY b.session_id,b.seq")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"diagnostic_batch","session_id":row.get::<_,String>(0)?,"seq":row.get::<_,i64>(1)?,"bytes":row.get::<_,i64>(2)?}),
            )?;
        }
        let mut statement =
            connection.prepare("SELECT kind,expires_s FROM auth_tokens WHERE user_id=?1")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"session_metadata","kind":row.get::<_,String>(0)?,"expires_s":row.get::<_,i64>(1)?}),
            )?;
        }
        let mut statement = connection
            .prepare("SELECT email,expires_s FROM email_verifications WHERE user_id=?1")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"email_verification","email":row.get::<_,String>(0)?,"expires_s":row.get::<_,i64>(1)?}),
            )?;
        }
        let mut statement =
            connection.prepare("SELECT expires_s FROM password_resets WHERE user_id=?1")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(serde_json::json!({"type":"password_reset","expires_s":row.get::<_,i64>(0)?}))?;
        }
        let mut statement =
            connection.prepare("SELECT actor_id FROM web_impersonations WHERE target_id=?1")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"administrator_impersonation","actor_id":row.get::<_,i64>(0)?}),
            )?;
        }
        let mut statement =
            connection.prepare("SELECT action,target,at_s FROM admin_audit WHERE actor_id=?1")?;
        let mut rows = statement.query([account_id])?;
        while let Some(row) = rows.next()? {
            emit(
                serde_json::json!({"type":"admin_audit","action":row.get::<_,String>(0)?,"target":row.get::<_,String>(1)?,"at_s":row.get::<_,i64>(2)?}),
            )?;
        }
        out.finish()?;
        Ok(())
    }

    /// Delete one account-owned device observation and recalculate that cell's consensus.
    pub(crate) fn delete_own_contribution(
        &self,
        account_id: i64,
        key: &CellKey,
        device: &str,
        policy: &Policy,
    ) -> Result<Option<OwnContributionChange>> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let removed = transaction.execute(
            "DELETE FROM contributions WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5
             AND device=?6 AND device GLOB ?7",
            params![
                key.radio.to_string(),
                key.mcc,
                key.mnc,
                key.area,
                key.cid,
                device,
                account_device_pattern(account_id)
            ],
        )?;
        if removed == 0 {
            return Ok(None);
        }
        transaction.execute(
            "INSERT OR IGNORE INTO account_deleted_keys(account_id,radio,mcc,mnc,area,cid,device) VALUES (?1,?2,?3,?4,?5,?6,?7)",
            params![account_id, key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid, device],
        )?;
        let change = update_consensus_after_deletion(&transaction, key, policy)?;
        transaction.commit()?;
        Ok(Some(change))
    }

    /// Remove every observation owned by one account in one transaction.
    pub(crate) fn delete_all_own_contributions(
        &self,
        account_id: i64,
        policy: &Policy,
    ) -> Result<Vec<OwnContributionChange>> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let pattern = account_device_pattern(account_id);
        let keys = {
            let mut statement = transaction.prepare(
                "SELECT DISTINCT radio,mcc,mnc,area,cid FROM contributions WHERE device GLOB ?1",
            )?;
            statement
                .query_map([&pattern], row_to_key)?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        transaction.execute(
            "INSERT OR IGNORE INTO account_deleted_keys(account_id,radio,mcc,mnc,area,cid,device)
             SELECT DISTINCT CAST(?1 AS BIGINT),radio,mcc,mnc,area,cid,'*' FROM contributions WHERE device GLOB ?2",
            params![account_id, pattern],
        )?;
        transaction.execute(
            "UPDATE users SET sharing_enabled=0 WHERE id=?1",
            [account_id],
        )?;
        transaction.execute("DELETE FROM contributions WHERE device GLOB ?1", [&pattern])?;
        let changes = keys
            .iter()
            .map(|key| update_consensus_after_deletion(&transaction, key, policy))
            .collect::<Result<Vec<_>>>()?;
        transaction.commit()?;
        Ok(changes)
    }

    /// Counts used by health checks and startup logs.
    ///
    /// # Errors
    ///
    /// Returns an error when the database cannot be queried.
    pub fn counts(&self, policy: &Policy) -> Result<(usize, usize)> {
        let counts = self.management_counts(policy)?;
        Ok((counts.published, counts.contributions))
    }

    /// Return aggregate counts without loading individual tower rows.
    ///
    /// # Errors
    ///
    /// Returns an error when the database cannot be queried or a count cannot fit in `usize`.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "CellStore"))]
    pub fn management_counts(&self, policy: &Policy) -> Result<StoreCounts> {
        let connection = self.connection()?;
        let contributions_count: i64 =
            connection.query_row("SELECT COUNT(*) FROM contributions", params![], |row| {
                row.get(0)
            })?;
        let (consensus_count, published_count, seeded_count): (i64, i64, i64) = connection
            .query_row(
                "SELECT COUNT(*),
                        COALESCE(SUM(CASE WHEN (seeded=1 OR devices>=?1) AND NOT EXISTS (SELECT 1 FROM tower_moderation m WHERE m.radio=consensus.radio AND m.mcc=consensus.mcc AND m.mnc=consensus.mnc AND m.area=consensus.area AND m.cid=consensus.cid AND m.quarantined=1) THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN seeded=1 THEN 1 ELSE 0 END), 0)
                 FROM consensus",
                [i64::try_from(policy.min_devices)?],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )?;
        let quarantined_count: i64 = connection.query_row(
            "SELECT COUNT(*) FROM consensus c JOIN tower_moderation m ON m.radio=c.radio AND m.mcc=c.mcc AND m.mnc=c.mnc AND m.area=c.area AND m.cid=c.cid WHERE m.quarantined=1",
            params![], |row| row.get(0),
        )?;
        Ok(StoreCounts {
            published: usize::try_from(published_count)?,
            consensus: usize::try_from(consensus_count)?,
            contributions: usize::try_from(contributions_count)?,
            seeded: usize::try_from(seeded_count)?,
            quarantined: usize::try_from(quarantined_count)?,
        })
    }
}

fn account_device_pattern(account_id: i64) -> String {
    format!("account:{account_id}:*")
}

fn current_time_s() -> Result<i64> {
    Ok(i64::try_from(
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_secs(),
    )?)
}

fn device_filter_pattern(value: &str) -> String {
    let escaped = value
        .trim()
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_");
    format!("%{escaped}%")
}

fn row_to_key(row: &crate::db::Row) -> rusqlite::Result<CellKey> {
    let radio_text: String = row.get(0)?;
    Ok(CellKey {
        radio: Radio::from_str(&radio_text).map_err(|error| {
            rusqlite::Error::FromSqlConversionFailure(0, Type::Text, Box::new(error))
        })?,
        mcc: row.get(1)?,
        mnc: row.get(2)?,
        area: row.get(3)?,
        cid: row.get(4)?,
    })
}

fn row_to_own_contribution(row: &crate::db::Row) -> rusqlite::Result<OwnContribution> {
    Ok(OwnContribution {
        key: row_to_key(row)?,
        device: row.get(5)?,
        lat: row.get(6)?,
        lon: row.get(7)?,
        range_m: row.get(8)?,
        samples: row.get(9)?,
        updated_s: row.get(10)?,
    })
}

fn update_consensus_after_deletion(
    transaction: &Transaction<'_>,
    key: &CellKey,
    policy: &Policy,
) -> Result<OwnContributionChange> {
    let remaining: i64 = transaction.query_row(
        "SELECT COUNT(*) FROM contributions WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
        params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
        |row| row.get(0),
    )?;
    if remaining == 0 {
        transaction.execute(
            "DELETE FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
        )?;
        record_removal(transaction, key)?;
        return Ok(OwnContributionChange::Removed(key.clone()));
    }
    let Some(mut consensus) = recompute_filtered(transaction, key, policy)? else {
        transaction.execute(
            "DELETE FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
        )?;
        record_removal(transaction, key)?;
        return Ok(OwnContributionChange::Removed(key.clone()));
    };
    consensus.updated_s = consensus.updated_s.max(crate::auth::now_s());
    save_consensus(transaction, &consensus)?;
    // The publication threshold may no longer be met after a contributor leaves.
    if !consensus.seeded && consensus.devices < policy.min_devices {
        record_removal(transaction, key)?;
    } else {
        let hidden: bool = transaction.query_row(
            "SELECT COALESCE((SELECT quarantined FROM tower_moderation WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5),0)",
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
            |row| row.get(0),
        )?;
        if !hidden {
            clear_removal(transaction, key)?;
        }
    }
    Ok(OwnContributionChange::Updated(consensus))
}

fn record_removal(transaction: &Transaction<'_>, key: &CellKey) -> Result<()> {
    transaction.execute(
        "INSERT INTO tower_removals(radio,mcc,mnc,area,cid,updated_s) VALUES (?1,?2,?3,?4,?5,?6)
         ON CONFLICT(radio,mcc,mnc,area,cid) DO UPDATE SET updated_s=excluded.updated_s",
        params![
            key.radio.to_string(),
            key.mcc,
            key.mnc,
            key.area,
            key.cid,
            crate::auth::now_s()
        ],
    )?;
    Ok(())
}

fn clear_removal(transaction: &Transaction<'_>, key: &CellKey) -> Result<()> {
    transaction.execute(
        "DELETE FROM tower_removals WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
        params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
    )?;
    Ok(())
}

impl CellStore {
    /// Return keys that were withdrawn from public sync since the requested epoch second.
    ///
    /// # Errors
    ///
    /// Returns an error if the removal query or row decoding fails.
    pub fn removals(&self, mccs: Option<&HashSet<i64>>, since_s: i64) -> Result<Vec<CellKey>> {
        let connection = self.connection()?;
        let mut sql =
            String::from("SELECT radio,mcc,mnc,area,cid FROM tower_removals WHERE updated_s>=?");
        let mut values = vec![Value::Integer(since_s)];
        if let Some(mccs) = mccs {
            sql.push_str(" AND mcc IN (");
            sql.push_str(
                &std::iter::repeat_n("?", mccs.len())
                    .collect::<Vec<_>>()
                    .join(","),
            );
            sql.push(')');
            values.extend(mccs.iter().copied().map(Value::Integer));
        }
        sql.push_str(" ORDER BY updated_s,radio,mcc,mnc,area,cid");
        let mut statement = connection.prepare(&sql)?;
        Ok(statement
            .query_map(params_from_iter(values), row_to_key)?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Stream withdrawn keys from one database snapshot in the existing CSV order.
    pub(crate) fn write_removals_csv<W: std::io::Write>(
        &self,
        output: W,
        mccs: Option<&HashSet<i64>>,
        since_s: i64,
        on_snapshot: impl FnOnce(),
    ) -> Result<()> {
        let mut connection = self.connection()?;
        let transaction = connection.read_transaction()?;
        let mut writer = csv::Writer::from_writer(output);
        writer.write_record(["radio", "mcc", "mnc", "area", "cid"])?;
        let sorted_mccs = mccs.map(|mccs| {
            let mut sorted = mccs.iter().copied().collect::<Vec<_>>();
            sorted.sort_unstable();
            sorted
        });
        let mut cursor: Option<(i64, String, i64, i64, i64, i64)> = None;
        let mut on_snapshot = Some(on_snapshot);
        loop {
            let mut sql = String::from(
                "SELECT radio,mcc,mnc,area,cid,updated_s FROM tower_removals WHERE updated_s>=?",
            );
            let mut values = vec![Value::Integer(since_s)];
            if let Some(mccs) = &sorted_mccs {
                sql.push_str(" AND mcc IN (");
                sql.push_str(
                    &std::iter::repeat_n("?", mccs.len())
                        .collect::<Vec<_>>()
                        .join(","),
                );
                sql.push(')');
                values.extend(mccs.iter().copied().map(Value::Integer));
            }
            if let Some((updated, radio, mcc, mnc, area, cid)) = &cursor {
                sql.push_str(" AND (updated_s,radio,mcc,mnc,area,cid)>(?,?,?,?,?,?)");
                values.extend([
                    Value::Integer(*updated),
                    Value::Text(radio.clone()),
                    Value::Integer(*mcc),
                    Value::Integer(*mnc),
                    Value::Integer(*area),
                    Value::Integer(*cid),
                ]);
            }
            sql.push_str(" ORDER BY updated_s,radio,mcc,mnc,area,cid LIMIT ?");
            values.push(Value::Integer(i64::try_from(EXPORT_PAGE_SIZE)?));
            let mut statement = transaction.prepare(&sql)?;
            let page = statement
                .query_map(params_from_iter(values), |row| {
                    Ok((row_to_key(row)?, row.get::<_, i64>(5)?))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            if let Some(release) = on_snapshot.take() {
                release();
            }
            if page.is_empty() {
                break;
            }
            for (key, _) in &page {
                writer.write_record([
                    key.radio.to_string(),
                    key.mcc.to_string(),
                    key.mnc.to_string(),
                    key.area.to_string(),
                    key.cid.to_string(),
                ])?;
            }
            let (last, updated) = page.last().expect("nonempty page");
            cursor = Some((
                *updated,
                last.radio.to_string(),
                last.mcc,
                last.mnc,
                last.area,
                last.cid,
            ));
            if page.len() < EXPORT_PAGE_SIZE {
                break;
            }
        }
        let mut output = writer
            .into_inner()
            .map_err(csv::IntoInnerError::into_error)?;
        std::io::Write::flush(&mut output)?;
        transaction.commit()?;
        Ok(())
    }
}

#[cfg_attr(feature = "profiling", hotpath::measure)]
fn recompute(transaction: &Transaction<'_>, key: &CellKey, policy: &Policy) -> Result<Consensus> {
    recompute_filtered(transaction, key, policy)?
        .ok_or_else(|| anyhow::anyhow!("cannot recompute a cell without valid contributions"))
}

fn recompute_filtered(
    transaction: &Transaction<'_>,
    key: &CellKey,
    policy: &Policy,
) -> Result<Option<Consensus>> {
    let mut statement = transaction.prepare_cached(
        "SELECT device,lat,lon,range_m,samples,updated_s FROM contributions
         WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
    )?;
    let mut contributions = statement
        .query_map(
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
            |row| {
                Ok(Contribution {
                    device: row.get(0)?,
                    lat: row.get(1)?,
                    lon: row.get(2)?,
                    range_m: row.get(3)?,
                    samples: row.get(4)?,
                    updated_s: row.get(5)?,
                })
            },
        )?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    contributions.retain(|contribution| {
        plausible(
            &CellTower {
                key: key.clone(),
                lat: contribution.lat,
                lon: contribution.lon,
                range_m: contribution.range_m,
                samples: contribution.samples,
            },
            policy,
        )
    });
    if contributions.is_empty() {
        return Ok(None);
    }
    for contribution in &mut contributions {
        if contribution.device != SEED_DEVICE && contribution.device != MANUAL_DEVICE {
            contribution.samples = contribution.samples.clamp(1, policy.max_samples_per_device);
        }
    }

    Ok(Some(consensus::calculate(key, &contributions, policy)))
}

#[cfg_attr(feature = "profiling", hotpath::measure)]
fn save_consensus(transaction: &Transaction<'_>, consensus: &Consensus) -> Result<()> {
    let tower = &consensus.tower;
    transaction
        .prepare_cached(
            "INSERT INTO consensus
         (radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded,updated_s)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)
         ON CONFLICT(radio,mcc,mnc,area,cid) DO UPDATE SET
         lat=excluded.lat,lon=excluded.lon,range_m=excluded.range_m,samples=excluded.samples,
         devices=excluded.devices,seeded=excluded.seeded,updated_s=excluded.updated_s",
        )?
        .execute(params![
            tower.key.radio.to_string(),
            tower.key.mcc,
            tower.key.mnc,
            tower.key.area,
            tower.key.cid,
            tower.lat,
            tower.lon,
            tower.range_m,
            tower.samples,
            i64::try_from(consensus.devices)?,
            consensus.seeded,
            consensus.updated_s,
        ])?;
    Ok(())
}

fn row_to_consensus(row: &crate::db::Row) -> rusqlite::Result<Consensus> {
    let radio_text: String = row.get(0)?;
    let radio = Radio::from_str(&radio_text).map_err(|error| {
        rusqlite::Error::FromSqlConversionFailure(0, Type::Text, Box::new(error))
    })?;
    Ok(Consensus {
        tower: CellTower {
            key: CellKey {
                radio,
                mcc: row.get(1)?,
                mnc: row.get(2)?,
                area: row.get(3)?,
                cid: row.get(4)?,
            },
            lat: row.get(5)?,
            lon: row.get(6)?,
            range_m: row.get(7)?,
            samples: row.get(8)?,
        },
        devices: usize::try_from(row.get::<_, i64>(9)?).map_err(|error| {
            rusqlite::Error::FromSqlConversionFailure(9, Type::Integer, Box::new(error))
        })?,
        seeded: row.get(10)?,
        updated_s: row.get(11)?,
    })
}

#[cfg(test)]
mod tests;
