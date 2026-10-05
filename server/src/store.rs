mod consensus;
pub(crate) mod management;

use crate::model::{distance_m, plausible};
use crate::{CellKey, CellTower, Consensus, Policy, Radio, UploadResult};
use anyhow::{Context, Result};
use rusqlite::types::{Type, Value};
use rusqlite::{
    Connection, OptionalExtension, Transaction, TransactionBehavior, params, params_from_iter,
};
use std::collections::{BTreeSet, HashSet};
use std::path::{Path, PathBuf};
use std::str::FromStr;

const SEED_DEVICE: &str = "seed";
const MANUAL_DEVICE: &str = "manual";
const SEED_WEIGHT: f64 = 200.0;
const SEED_VOTE: f64 = 3.0;

#[derive(Clone, Debug)]
struct Contribution {
    device: String,
    lat: f64,
    lon: f64,
    range_m: f64,
    samples: i64,
    updated_s: i64,
}

/// SQLite-backed contribution and consensus store.
#[derive(Clone, Debug)]
pub struct CellStore {
    path: PathBuf,
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

/// The consensus change caused by removing an account observation.
pub(crate) enum OwnContributionChange {
    Updated(Consensus),
    Removed(CellKey),
}

impl CellStore {
    /// Open the database, create its schema, and enable WAL for concurrent readers.
    ///
    /// # Errors
    ///
    /// Returns an error when the database cannot be opened or initialized.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let store = Self {
            path: path.as_ref().to_path_buf(),
        };
        let connection = store.connection()?;
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
               suspended INTEGER NOT NULL DEFAULT 0
             );
             CREATE TABLE IF NOT EXISTS auth_tokens (
               token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
               session_id TEXT NOT NULL, kind TEXT NOT NULL, expires_s INTEGER NOT NULL
             );
             CREATE INDEX IF NOT EXISTS auth_tokens_user ON auth_tokens(user_id);
             CREATE INDEX IF NOT EXISTS auth_tokens_session ON auth_tokens(session_id);
             CREATE INDEX IF NOT EXISTS auth_tokens_expiry ON auth_tokens(expires_s);
             CREATE TABLE IF NOT EXISTS password_resets (
               token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
               expires_s INTEGER NOT NULL
             );
             CREATE INDEX IF NOT EXISTS password_resets_expiry ON password_resets(expires_s);",
        )?;
        let has_suspended = connection
            .prepare("PRAGMA table_info(users)")?
            .query_map([], |row| row.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?
            .iter()
            .any(|column| column == "suspended");
        if !has_suspended {
            connection.execute(
                "ALTER TABLE users ADD COLUMN suspended INTEGER NOT NULL DEFAULT 0",
                [],
            )?;
        }
        Ok(store)
    }

    pub(crate) fn connection(&self) -> Result<Connection> {
        let connection = Connection::open(&self.path)
            .with_context(|| format!("cannot open {}", self.path.display()))?;
        // National seed imports and policy recalculations may hold the writer slot for minutes.
        // SQLite WAL still serves readers while other writers wait for the atomic commit.
        connection.busy_timeout(std::time::Duration::from_secs(3600))?;
        Ok(connection)
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

        // One connection handles the batch; cached statements avoid recompiling identical SQL
        // for each tower while keeping the same transaction and per-device movement checks.
        for tower in towers {
            if !plausible(tower, policy)
                || !Self::accepts_movement(&transaction, device, tower, policy)?
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
    pub(crate) fn admin_tower_page(
        &self,
        mccs: Option<&HashSet<i64>>,
        status: &str,
        limit: usize,
        offset: usize,
        policy: &Policy,
    ) -> Result<Vec<Consensus>> {
        let connection = self.connection()?;
        let mut sql = String::from(
            "SELECT c.radio,c.mcc,c.mnc,c.area,c.cid,c.lat,c.lon,c.range_m,c.samples,c.devices,c.seeded,c.updated_s FROM consensus c WHERE 1=1",
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
        let hidden = "EXISTS (SELECT 1 FROM tower_moderation m WHERE m.radio=c.radio AND m.mcc=c.mcc AND m.mnc=c.mnc AND m.area=c.area AND m.cid=c.cid AND m.quarantined=1)";
        match status {
            "quarantined" => sql.push_str(&format!(" AND {hidden}")),
            "seeded" => sql.push_str(&format!(" AND NOT {hidden} AND c.seeded=1")),
            "published" => {
                sql.push_str(&format!(
                    " AND NOT {hidden} AND c.seeded=0 AND c.devices>=?"
                ));
                values.push(Value::Integer(i64::try_from(policy.min_devices)?));
            }
            "pending" => {
                sql.push_str(&format!(" AND NOT {hidden} AND c.seeded=0 AND c.devices<?"));
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
            .query_map(params_from_iter(values), row_to_consensus)?
            .collect::<rusqlite::Result<Vec<_>>>()?)
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
    pub(crate) fn own_contributions(
        &self,
        account_id: i64,
        limit: usize,
        offset: usize,
    ) -> Result<OwnContributionPage> {
        let connection = self.connection()?;
        let pattern = account_device_pattern(account_id);
        let total: i64 = connection.query_row(
            "SELECT COUNT(*) FROM contributions WHERE device GLOB ?1",
            [&pattern],
            |row| row.get(0),
        )?;
        let mut statement = connection.prepare(
            "SELECT radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s
             FROM contributions WHERE device GLOB ?1
             ORDER BY updated_s DESC,radio,mcc,mnc,area,cid,device LIMIT ?2 OFFSET ?3",
        )?;
        let rows = statement
            .query_map(
                params![pattern, i64::try_from(limit)?, i64::try_from(offset)?],
                row_to_own_contribution,
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(OwnContributionPage {
            rows,
            total: usize::try_from(total)?,
        })
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
            connection.query_row("SELECT COUNT(*) FROM contributions", [], |row| row.get(0))?;
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
            [], |row| row.get(0),
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

fn row_to_key(row: &rusqlite::Row<'_>) -> rusqlite::Result<CellKey> {
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

fn row_to_own_contribution(row: &rusqlite::Row<'_>) -> rusqlite::Result<OwnContribution> {
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

fn row_to_consensus(row: &rusqlite::Row<'_>) -> rusqlite::Result<Consensus> {
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
