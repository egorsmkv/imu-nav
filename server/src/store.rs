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
        Ok(store)
    }

    fn connection(&self) -> Result<Connection> {
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
            transaction.execute(
                "INSERT INTO contributions
                 (radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)
                 ON CONFLICT(radio,mcc,mnc,area,cid,device) DO UPDATE SET
                 lat=excluded.lat,lon=excluded.lon,range_m=excluded.range_m,
                 samples=excluded.samples,updated_s=excluded.updated_s",
                params![
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
                ],
            )?;
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
            .query_row(
                "SELECT lat,lon FROM contributions WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5 AND device=?6",
                params![tower.key.radio.to_string(), tower.key.mcc, tower.key.mnc, tower.key.area, tower.key.cid, device],
                |row| Ok((row.get::<_, f64>(0)?, row.get::<_, f64>(1)?)),
            )
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
        self.query_internal(mccs, since_s, limit, Some(policy))
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
        self.query_internal(mccs, since_s, limit, None)
    }

    fn query_internal(
        &self,
        mccs: Option<&HashSet<i64>>,
        since_s: i64,
        limit: Option<usize>,
        publication_policy: Option<&Policy>,
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
        query.push_str(" ORDER BY updated_s,radio,mcc,mnc,area,cid");
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
        let connection = self.connection()?;
        let contributions =
            connection.query_row("SELECT COUNT(*) FROM contributions", [], |row| row.get(0))?;
        let published = connection.query_row(
            "SELECT COUNT(*) FROM consensus WHERE seeded=1 OR devices>=?1",
            [i64::try_from(policy.min_devices)?],
            |row| row.get(0),
        )?;
        Ok((published, contributions))
    }
}

fn recompute(transaction: &Transaction<'_>, key: &CellKey, policy: &Policy) -> Result<Consensus> {
    let mut statement = transaction.prepare(
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

    let median_lat = weighted_median(
        contributions
            .iter()
            .map(|item| (item.lat, vote(item)))
            .collect(),
    );
    let median_lon = weighted_median(
        contributions
            .iter()
            .map(|item| (item.lon, vote(item)))
            .collect(),
    );
    let inliers = select_inliers(&contributions, median_lat, median_lon, policy);
    let weight_sum: f64 = inliers.iter().map(|item| weight(item)).sum();
    let lat = inliers
        .iter()
        .map(|item| item.lat * weight(item))
        .sum::<f64>()
        / weight_sum;
    let lon = inliers
        .iter()
        .map(|item| item.lon * weight(item))
        .sum::<f64>()
        / weight_sum;
    let spread = inliers
        .iter()
        .map(|item| distance_m(lat, lon, item.lat, item.lon))
        .fold(0.0, f64::max);
    let mut ranges = inliers.iter().map(|item| item.range_m).collect::<Vec<_>>();
    ranges.sort_by(f64::total_cmp);

    Ok(Consensus {
        tower: CellTower {
            key: key.clone(),
            lat,
            lon,
            range_m: ranges[ranges.len() / 2].max(spread),
            samples: inliers
                .iter()
                .fold(0_i64, |total, item| total.saturating_add(item.samples))
                .min(1_000_000),
        },
        devices: inliers
            .iter()
            .filter(|item| item.device != SEED_DEVICE)
            .count(),
        seeded: inliers.iter().any(|item| item.device == SEED_DEVICE),
        updated_s: inliers
            .iter()
            .map(|item| item.updated_s)
            .max()
            .unwrap_or_default(),
    })
}

fn select_inliers<'a>(
    items: &'a [Contribution],
    median_lat: f64,
    median_lon: f64,
    policy: &Policy,
) -> Vec<&'a Contribution> {
    if items.len() >= 3 {
        let distances = items
            .iter()
            .map(|item| distance_m(median_lat, median_lon, item.lat, item.lon))
            .collect::<Vec<_>>();
        let mut sorted = distances.clone();
        sorted.sort_by(f64::total_cmp);
        let limit = (3.0 * sorted[sorted.len() / 2]).max(policy.outlier_min_m);
        let selected = items
            .iter()
            .zip(distances)
            .filter_map(|(item, distance)| (distance <= limit).then_some(item))
            .collect::<Vec<_>>();
        if !selected.is_empty() {
            return selected;
        }
    } else if items.len() == 2
        && distance_m(items[0].lat, items[0].lon, items[1].lat, items[1].lon)
            > policy.outlier_min_m * 2.0
    {
        return vec![if weight(&items[0]) >= weight(&items[1]) {
            &items[0]
        } else {
            &items[1]
        }];
    }
    items.iter().collect()
}

fn weight(item: &Contribution) -> f64 {
    if item.device == SEED_DEVICE {
        SEED_WEIGHT
    } else {
        f64::from(u32::try_from(item.samples).unwrap_or(u32::MAX))
    }
}

fn vote(item: &Contribution) -> f64 {
    if item.device == SEED_DEVICE {
        SEED_VOTE
    } else {
        1.0
    }
}

fn weighted_median(mut values: Vec<(f64, f64)>) -> f64 {
    values.sort_by(|left, right| left.0.total_cmp(&right.0));
    let half = values.iter().map(|item| item.1).sum::<f64>() / 2.0;
    let mut accumulated = 0.0;
    for (value, value_weight) in &values {
        accumulated += value_weight;
        if accumulated >= half {
            return *value;
        }
    }
    values.last().map_or(0.0, |item| item.0)
}

fn save_consensus(transaction: &Transaction<'_>, consensus: &Consensus) -> Result<()> {
    let tower = &consensus.tower;
    transaction.execute(
        "INSERT INTO consensus
         (radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded,updated_s)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)
         ON CONFLICT(radio,mcc,mnc,area,cid) DO UPDATE SET
         lat=excluded.lat,lon=excluded.lon,range_m=excluded.range_m,samples=excluded.samples,
         devices=excluded.devices,seeded=excluded.seeded,updated_s=excluded.updated_s",
        params![
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
        ],
    )?;
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
        devices: row.get(9)?,
        seeded: row.get(10)?,
        updated_s: row.get(11)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;

    fn tower(cid: i64, lat: f64, lon: f64, samples: i64) -> CellTower {
        CellTower {
            key: CellKey {
                radio: Radio::Lte,
                mcc: 255,
                mnc: 1,
                area: 1864,
                cid,
            },
            lat,
            lon,
            range_m: 500.0,
            samples,
        }
    }

    #[test]
    fn consensus_is_robust_and_persistent() -> Result<()> {
        let file = NamedTempFile::new()?;
        let store = CellStore::open(file.path())?;
        let policy = Policy::default();
        store.contribute(
            "honest-aaaa",
            &[tower(7, 50.4500, 30.5200, 30)],
            10,
            &policy,
        )?;
        store.contribute(
            "honest-bbbb",
            &[tower(7, 50.4508, 30.5210, 12)],
            11,
            &policy,
        )?;
        store.contribute(
            "honest-cccc",
            &[tower(7, 50.4495, 30.5195, 25)],
            12,
            &policy,
        )?;
        let before = store
            .consensus(&tower(7, 0.0, 0.0, 1).key)?
            .expect("consensus");
        store.contribute(
            "attacker-dddd",
            &[tower(7, 50.62, 30.70, 1_000_000)],
            13,
            &policy,
        )?;
        let reopened = CellStore::open(file.path())?;
        let after = reopened
            .consensus(&before.tower.key)?
            .expect("persisted consensus");
        assert!(
            distance_m(
                before.tower.lat,
                before.tower.lon,
                after.tower.lat,
                after.tower.lon
            ) < 50.0
        );
        assert_eq!(after.devices, 3);
        assert_eq!(reopened.counts(&policy)?, (1, 4));
        Ok(())
    }

    #[test]
    fn seed_is_published_immediately_and_delete_is_durable() -> Result<()> {
        let file = NamedTempFile::new()?;
        let store = CellStore::open(file.path())?;
        let policy = Policy::default();
        store.seed(&[tower(5, 50.3, 30.4, 100)], 20, &policy)?;
        assert_eq!(store.query(None, 0, None, &policy)?.len(), 1);
        assert!(store.delete(&tower(5, 0.0, 0.0, 1).key)?);
        assert_eq!(CellStore::open(file.path())?.counts(&policy)?, (0, 0));
        Ok(())
    }
}
