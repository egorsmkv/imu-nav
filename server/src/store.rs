mod consensus;

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
            "CREATE TABLE IF NOT EXISTS users (
               id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE,
               password_hash TEXT NOT NULL, admin INTEGER NOT NULL DEFAULT 0
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
        Ok(store)
    }

    pub(crate) fn connection(&self) -> Result<Connection> {
        let connection = Connection::open(&self.path)
            .with_context(|| format!("cannot open {}", self.path.display()))?;
        connection.busy_timeout(std::time::Duration::from_secs(5))?;
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
        transaction.commit()?;
        Ok(removed > 0)
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
                        COALESCE(SUM(CASE WHEN seeded=1 OR devices>=?1 THEN 1 ELSE 0 END), 0),
                        COALESCE(SUM(CASE WHEN seeded=1 THEN 1 ELSE 0 END), 0)
                 FROM consensus",
                [i64::try_from(policy.min_devices)?],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )?;
        Ok(StoreCounts {
            published: usize::try_from(published_count)?,
            consensus: usize::try_from(consensus_count)?,
            contributions: usize::try_from(contributions_count)?,
            seeded: usize::try_from(seeded_count)?,
        })
    }
}

#[cfg_attr(feature = "profiling", hotpath::measure)]
fn recompute(transaction: &Transaction<'_>, key: &CellKey, policy: &Policy) -> Result<Consensus> {
    let mut statement = transaction.prepare_cached(
        "SELECT device,lat,lon,range_m,samples,updated_s FROM contributions
         WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
    )?;
    let contributions = statement
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
    if contributions.is_empty() {
        anyhow::bail!("cannot recompute a cell without contributions");
    }

    Ok(consensus::calculate(key, &contributions, policy))
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
