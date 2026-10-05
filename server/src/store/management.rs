//! Administrator operations shared by browser and JSON interfaces.

use super::*;
use crate::auth::now_s;
use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use std::io::{Read, Seek};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

/// Minimal account data for the administrator list.
pub(crate) struct ManagedAccount {
    pub id: i64,
    pub email: String,
    pub admin: bool,
    pub suspended: bool,
}

/// A recent administrator action without credentials or session data.
pub(crate) struct AuditEntry {
    pub actor_id: i64,
    pub action: String,
    pub target: String,
    pub at_s: i64,
}

/// Last bulk operation, restored after a process restart.
pub(crate) struct JobRecord {
    pub id: i64,
    pub kind: String,
    pub status: String,
    pub processed: usize,
    pub rejected: usize,
}

impl CellStore {
    pub(crate) fn recover_admin_job(&self) -> Result<Option<JobRecord>> {
        let connection = self.connection()?;
        connection.execute(
            "UPDATE admin_jobs SET status='interrupted',finished_s=?1 WHERE status='running'",
            [now_s()],
        )?;
        connection
            .query_row(
                "SELECT j.id,j.kind,j.status,j.processed,(SELECT COUNT(*) FROM admin_import_rejections r WHERE r.job_id=j.id) FROM admin_jobs j ORDER BY j.id DESC LIMIT 1",
                [],
                |row| {
                    Ok(JobRecord {
                        id: row.get(0)?,
                        kind: row.get(1)?,
                        status: row.get(2)?,
                        processed: usize::try_from(row.get::<_, i64>(3)?).unwrap_or(0),
                        rejected: usize::try_from(row.get::<_, i64>(4)?).unwrap_or(0),
                    })
                },
            )
            .optional()
            .map_err(Into::into)
    }

    pub(crate) fn start_admin_job(&self, kind: &str) -> Result<i64> {
        let connection = self.connection()?;
        connection.execute(
            "INSERT INTO admin_jobs(kind,status,started_s) VALUES (?1,'running',?2)",
            params![kind, now_s()],
        )?;
        Ok(connection.last_insert_rowid())
    }

    pub(crate) fn finish_admin_job(&self, id: i64, status: &str, processed: usize) -> Result<()> {
        self.connection()?.execute(
            "UPDATE admin_jobs SET status=?1,processed=?2,finished_s=?3 WHERE id=?4",
            params![status, i64::try_from(processed)?, now_s(), id],
        )?;
        Ok(())
    }
    /// Import trusted seeds one row at a time. A cancelled or failed transaction publishes nothing.
    #[cfg(test)]
    pub(crate) fn import_seeds(
        &self,
        path: &Path,
        policy: &Policy,
        cancel: &AtomicBool,
        progress: &AtomicUsize,
    ) -> Result<Option<UploadResult>> {
        self.import_seeds_report(path, policy, cancel, progress, None, &AtomicUsize::new(0))
    }

    pub(crate) fn import_seeds_report(
        &self,
        path: &Path,
        policy: &Policy,
        cancel: &AtomicBool,
        progress: &AtomicUsize,
        job_id: Option<i64>,
        rejected_progress: &AtomicUsize,
    ) -> Result<Option<UploadResult>> {
        anyhow::ensure!(
            std::fs::metadata(path)?.len() <= 512 * 1024 * 1024,
            "import exceeds 512 MiB compressed"
        );
        let mut file = std::fs::File::open(path)?;
        let mut prefix = [0u8; 2];
        let read = file.read(&mut prefix)?;
        file.rewind()?;
        let input: Box<dyn Read> = if read == 2 && prefix == [0x1f, 0x8b] {
            Box::new(GzDecoder::new(file))
        } else {
            Box::new(file)
        };
        let mut reader = csv::ReaderBuilder::new()
            .has_headers(false)
            .flexible(true)
            .from_reader(input.take(1024 * 1024 * 1024 + 1));
        let mut connection = self.connection()?;
        connection.busy_timeout(std::time::Duration::from_secs(3600))?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut accepted = 0usize;
        let mut rejected = 0usize;
        transaction.execute_batch("CREATE TEMP TABLE IF NOT EXISTS import_keys (
            radio TEXT NOT NULL,mcc INTEGER NOT NULL,mnc INTEGER NOT NULL,area INTEGER NOT NULL,cid INTEGER NOT NULL,
            PRIMARY KEY(radio,mcc,mnc,area,cid)); DELETE FROM import_keys;")?;
        for (index, record) in reader.records().enumerate() {
            if cancel.load(Ordering::Relaxed) {
                return Ok(None);
            }
            let row_number = i64::try_from(index + 1)?;
            let record = match record {
                Ok(record) => record,
                Err(error) => {
                    rejected += 1;
                    rejected_progress.store(rejected, Ordering::Relaxed);
                    if let Some(job_id) = job_id {
                        transaction.execute("INSERT INTO admin_import_rejections(job_id,row_number,reason,input) VALUES (?1,?2,?3,'')",
                            params![job_id, row_number, format!("CSV error: {error}")])?;
                    }
                    anyhow::ensure!(
                        accepted + rejected <= 2_000_000,
                        "import exceeds two million rows"
                    );
                    progress.store(accepted + rejected, Ordering::Relaxed);
                    continue;
                }
            };
            if record
                .get(0)
                .is_some_and(|value| value.eq_ignore_ascii_case("radio"))
            {
                continue;
            }
            if let Some(tower) =
                crate::csv_format::parse_tower(&record).filter(|tower| plausible(tower, policy))
            {
                transaction.prepare_cached("INSERT INTO contributions(radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s)
                    VALUES (?1,?2,?3,?4,?5,'seed',?6,?7,?8,?9,?10)
                    ON CONFLICT(radio,mcc,mnc,area,cid,device) DO UPDATE SET lat=excluded.lat,lon=excluded.lon,range_m=excluded.range_m,samples=excluded.samples,updated_s=excluded.updated_s")?
                    .execute(params![tower.key.radio.to_string(),tower.key.mcc,tower.key.mnc,tower.key.area,tower.key.cid,tower.lat,tower.lon,tower.range_m,tower.samples,now_s()])?;
                transaction.prepare_cached("INSERT OR IGNORE INTO import_keys(radio,mcc,mnc,area,cid) VALUES (?1,?2,?3,?4,?5)")?
                    .execute(params![tower.key.radio.to_string(),tower.key.mcc,tower.key.mnc,tower.key.area,tower.key.cid])?;
                accepted += 1;
            } else {
                rejected += 1;
                rejected_progress.store(rejected, Ordering::Relaxed);
                if let Some(job_id) = job_id {
                    let reason = if crate::csv_format::parse_tower(&record).is_none() {
                        "Invalid OpenCellID row"
                    } else {
                        "Rejected by current coordinate or range policy"
                    };
                    transaction.execute("INSERT INTO admin_import_rejections(job_id,row_number,reason,input) VALUES (?1,?2,?3,?4)",
                        params![job_id, row_number, reason, record.iter().collect::<Vec<_>>().join(",")])?;
                }
            }
            anyhow::ensure!(
                accepted + rejected <= 2_000_000,
                "import exceeds two million rows"
            );
            progress.store(accepted + rejected, Ordering::Relaxed);
        }
        anyhow::ensure!(
            reader.into_inner().limit() > 0,
            "import exceeds 1 GiB decoded"
        );
        let mut key_statement =
            transaction.prepare("SELECT radio,mcc,mnc,area,cid FROM import_keys")?;
        let mut keys = key_statement.query([])?;
        while let Some(row) = keys.next()? {
            if cancel.load(Ordering::Relaxed) {
                return Ok(None);
            }
            let key = row_to_key(row)?;
            let consensus = recompute(&transaction, &key, policy)?;
            save_consensus(&transaction, &consensus)?;
            let hidden: bool = transaction.query_row(
                "SELECT COALESCE((SELECT quarantined FROM tower_moderation WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5),0)",
                params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid], |row| row.get(0),
            )?;
            if !hidden {
                clear_removal(&transaction, &key)?;
            }
        }
        drop(keys);
        drop(key_statement);
        if cancel.load(Ordering::Relaxed) {
            return Ok(None);
        }
        transaction.commit()?;
        Ok(Some(UploadResult { accepted, rejected }))
    }

    pub(crate) fn export_rejections_to_path(&self, job_id: i64, path: &Path) -> Result<()> {
        let connection = self.connection()?;
        let mut writer = csv::Writer::from_path(path)?;
        writer.write_record(["row_number", "reason", "input"])?;
        let mut statement = connection.prepare("SELECT row_number,reason,input FROM admin_import_rejections WHERE job_id=?1 ORDER BY row_number")?;
        let mut rows = statement.query([job_id])?;
        while let Some(row) = rows.next()? {
            writer.write_record([row.get::<_, i64>(0)?.to_string(), row.get(1)?, row.get(2)?])?;
        }
        writer.flush()?;
        Ok(())
    }

    #[cfg(test)]
    pub(crate) fn rejection_count(&self, job_id: i64) -> Result<usize> {
        let count: i64 = self.connection()?.query_row(
            "SELECT COUNT(*) FROM admin_import_rejections WHERE job_id=?1",
            [job_id],
            |row| row.get(0),
        )?;
        Ok(usize::try_from(count)?)
    }

    /// Stream one admin export to a file without collecting the database in memory.
    pub(crate) fn export_to_path(&self, path: &Path, observations: bool) -> Result<()> {
        let connection = self.connection()?;
        let encoder = GzEncoder::new(std::fs::File::create(path)?, Compression::default());
        let mut writer = csv::Writer::from_writer(encoder);
        if observations {
            writer.write_record([
                "radio",
                "mcc",
                "mnc",
                "area",
                "cid",
                "device",
                "lat",
                "lon",
                "range_m",
                "samples",
                "updated_s",
            ])?;
            let mut statement = connection.prepare("SELECT radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s FROM contributions ORDER BY radio,mcc,mnc,area,cid,device")?;
            let mut rows = statement.query([])?;
            while let Some(row) = rows.next()? {
                writer.write_record([
                    row.get::<_, String>(0)?,
                    row.get::<_, i64>(1)?.to_string(),
                    row.get::<_, i64>(2)?.to_string(),
                    row.get::<_, i64>(3)?.to_string(),
                    row.get::<_, i64>(4)?.to_string(),
                    row.get::<_, String>(5)?,
                    row.get::<_, f64>(6)?.to_string(),
                    row.get::<_, f64>(7)?.to_string(),
                    row.get::<_, f64>(8)?.to_string(),
                    row.get::<_, i64>(9)?.to_string(),
                    row.get::<_, i64>(10)?.to_string(),
                ])?;
            }
        } else {
            writer.write_record([
                "radio",
                "mcc",
                "mnc",
                "area",
                "cid",
                "lat",
                "lon",
                "range_m",
                "samples",
                "devices",
                "seeded",
                "updated_s",
                "quarantined",
            ])?;
            let mut statement = connection.prepare("SELECT c.radio,c.mcc,c.mnc,c.area,c.cid,c.lat,c.lon,c.range_m,c.samples,c.devices,c.seeded,c.updated_s,
                COALESCE(m.quarantined,0) FROM consensus c LEFT JOIN tower_moderation m ON m.radio=c.radio AND m.mcc=c.mcc AND m.mnc=c.mnc AND m.area=c.area AND m.cid=c.cid ORDER BY c.radio,c.mcc,c.mnc,c.area,c.cid")?;
            let mut rows = statement.query([])?;
            while let Some(row) = rows.next()? {
                writer.write_record([
                    row.get::<_, String>(0)?,
                    row.get::<_, i64>(1)?.to_string(),
                    row.get::<_, i64>(2)?.to_string(),
                    row.get::<_, i64>(3)?.to_string(),
                    row.get::<_, i64>(4)?.to_string(),
                    row.get::<_, f64>(5)?.to_string(),
                    row.get::<_, f64>(6)?.to_string(),
                    row.get::<_, f64>(7)?.to_string(),
                    row.get::<_, i64>(8)?.to_string(),
                    row.get::<_, i64>(9)?.to_string(),
                    row.get::<_, i64>(10)?.to_string(),
                    row.get::<_, i64>(11)?.to_string(),
                    row.get::<_, i64>(12)?.to_string(),
                ])?;
            }
        }
        writer.flush()?;
        writer
            .into_inner()
            .map_err(|error| error.into_error())?
            .finish()?;
        Ok(())
    }
    pub(crate) fn audit(&self, actor: i64, action: &str, target: &str) -> Result<()> {
        self.connection()?.execute(
            "INSERT INTO admin_audit(actor_id,action,target,at_s) VALUES (?1,?2,?3,?4)",
            params![actor, action, target, now_s()],
        )?;
        Ok(())
    }

    #[cfg(test)]
    pub(crate) fn recent_audit(&self) -> Result<Vec<AuditEntry>> {
        let connection = self.connection()?;
        let mut statement = connection.prepare(
            "SELECT actor_id,action,target,at_s FROM admin_audit ORDER BY id DESC LIMIT 30",
        )?;
        Ok(statement
            .query_map([], |row| {
                Ok(AuditEntry {
                    actor_id: row.get(0)?,
                    action: row.get(1)?,
                    target: row.get(2)?,
                    at_s: row.get(3)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub(crate) fn audit_page(
        &self,
        action: &str,
        limit: usize,
        offset: usize,
    ) -> Result<Vec<AuditEntry>> {
        let connection = self.connection()?;
        let mut statement = connection.prepare(
            "SELECT actor_id,action,target,at_s FROM admin_audit WHERE action LIKE ?1 ORDER BY id DESC LIMIT ?2 OFFSET ?3",
        )?;
        Ok(statement
            .query_map(
                params![
                    format!("%{}%", action.trim()),
                    i64::try_from(limit)?,
                    i64::try_from(offset)?
                ],
                |row| {
                    Ok(AuditEntry {
                        actor_id: row.get(0)?,
                        action: row.get(1)?,
                        target: row.get(2)?,
                        at_s: row.get(3)?,
                    })
                },
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    #[cfg(test)]
    pub(crate) fn accounts(&self, email: &str) -> Result<Vec<ManagedAccount>> {
        let connection = self.connection()?;
        let mut statement = connection
            .prepare("SELECT id,email,admin,suspended FROM users WHERE email LIKE ?1 ORDER BY id DESC LIMIT 500")?;
        Ok(statement
            .query_map([format!("%{}%", email.trim())], |row| {
                Ok(ManagedAccount {
                    id: row.get(0)?,
                    email: row.get(1)?,
                    admin: row.get(2)?,
                    suspended: row.get(3)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub(crate) fn account_page(
        &self,
        email: &str,
        limit: usize,
        offset: usize,
    ) -> Result<Vec<ManagedAccount>> {
        let connection = self.connection()?;
        let mut statement = connection.prepare(
            "SELECT id,email,admin,suspended FROM users WHERE email LIKE ?1 ORDER BY id DESC LIMIT ?2 OFFSET ?3",
        )?;
        Ok(statement
            .query_map(
                params![
                    format!("%{}%", email.trim()),
                    i64::try_from(limit)?,
                    i64::try_from(offset)?
                ],
                |row| {
                    Ok(ManagedAccount {
                        id: row.get(0)?,
                        email: row.get(1)?,
                        admin: row.get(2)?,
                        suspended: row.get(3)?,
                    })
                },
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub(crate) fn set_suspended(&self, actor: i64, account: i64, suspended: bool) -> Result<bool> {
        if actor == account && suspended {
            return Ok(false);
        }
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let target: Option<(bool, bool)> = transaction
            .query_row(
                "SELECT admin,suspended FROM users WHERE id=?1",
                [account],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?;
        let Some((admin, current)) = target else {
            return Ok(false);
        };
        if suspended && admin && !current {
            let active: i64 = transaction.query_row(
                "SELECT COUNT(*) FROM users WHERE admin=1 AND suspended=0",
                [],
                |row| row.get(0),
            )?;
            if active <= 1 {
                return Ok(false);
            }
        }
        transaction.execute(
            "UPDATE users SET suspended=?1 WHERE id=?2",
            params![suspended, account],
        )?;
        if suspended {
            transaction.execute("DELETE FROM auth_tokens WHERE user_id=?1", [account])?;
            transaction.execute("DELETE FROM password_resets WHERE user_id=?1", [account])?;
        }
        transaction.execute(
            "INSERT INTO admin_audit(actor_id,action,target,at_s) VALUES (?1,?2,?3,?4)",
            params![
                actor,
                if suspended {
                    "suspend_account"
                } else {
                    "restore_account"
                },
                account.to_string(),
                now_s()
            ],
        )?;
        transaction.commit()?;
        Ok(true)
    }

    pub(crate) fn quarantined(&self, key: &CellKey) -> Result<bool> {
        Ok(self.connection()?.query_row(
            "SELECT quarantined FROM tower_moderation WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
            params![key.radio.to_string(),key.mcc,key.mnc,key.area,key.cid], |row| row.get(0),
        ).optional()?.unwrap_or(false))
    }

    pub(crate) fn set_quarantined(
        &self,
        actor: i64,
        key: &CellKey,
        quarantined: bool,
        policy: &Policy,
    ) -> Result<bool> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let exists: bool = transaction.query_row(
            "SELECT EXISTS(SELECT 1 FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5)",
            params![key.radio.to_string(),key.mcc,key.mnc,key.area,key.cid], |row| row.get(0))?;
        if !exists && !self.quarantined(key)? {
            return Ok(false);
        }
        transaction.execute("INSERT INTO tower_moderation(radio,mcc,mnc,area,cid,quarantined) VALUES (?1,?2,?3,?4,?5,?6)
             ON CONFLICT(radio,mcc,mnc,area,cid) DO UPDATE SET quarantined=excluded.quarantined",
            params![key.radio.to_string(),key.mcc,key.mnc,key.area,key.cid,quarantined])?;
        if quarantined {
            record_removal(&transaction, key)?;
        } else {
            transaction.execute(
                "UPDATE consensus SET updated_s=?6 WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
                params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid, now_s()],
            )?;
            let published: bool = transaction.query_row(
                "SELECT COALESCE((SELECT seeded=1 OR devices>=?6 FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5),0)",
                params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid, i64::try_from(policy.min_devices)?], |row| row.get(0),
            )?;
            if published {
                clear_removal(&transaction, key)?;
            }
        }
        audit_in_transaction(
            &transaction,
            actor,
            if quarantined {
                "quarantine_tower"
            } else {
                "restore_tower"
            },
            &key_text(key),
        )?;
        transaction.commit()?;
        Ok(true)
    }

    pub(crate) fn delete_quarantined(&self, actor: i64, key: &CellKey) -> Result<bool> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let quarantined: bool = transaction.query_row(
            "SELECT COALESCE((SELECT quarantined FROM tower_moderation WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5),0)",
            params![key.radio.to_string(),key.mcc,key.mnc,key.area,key.cid], |row| row.get(0))?;
        if !quarantined {
            return Ok(false);
        }
        transaction.execute(
            "DELETE FROM contributions WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
        )?;
        let removed = transaction.execute(
            "DELETE FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
            params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
        )?;
        record_removal(&transaction, key)?;
        audit_in_transaction(&transaction, actor, "delete_tower", &key_text(key))?;
        transaction.commit()?;
        Ok(removed > 0)
    }

    pub(crate) fn correct_tower(
        &self,
        actor: i64,
        tower: &CellTower,
        policy: &Policy,
    ) -> Result<Option<Consensus>> {
        if !plausible(tower, policy) {
            return Ok(None);
        }
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute("INSERT INTO contributions(radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s)
             VALUES (?1,?2,?3,?4,?5,'manual',?6,?7,?8,?9,?10)
             ON CONFLICT(radio,mcc,mnc,area,cid,device) DO UPDATE SET lat=excluded.lat,lon=excluded.lon,range_m=excluded.range_m,samples=excluded.samples,updated_s=excluded.updated_s",
             params![tower.key.radio.to_string(),tower.key.mcc,tower.key.mnc,tower.key.area,tower.key.cid,tower.lat,tower.lon,tower.range_m,tower.samples,now_s()])?;
        let consensus = recompute(&transaction, &tower.key, policy)?;
        save_consensus(&transaction, &consensus)?;
        let hidden: bool = transaction.query_row(
            "SELECT COALESCE((SELECT quarantined FROM tower_moderation WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5),0)",
            params![tower.key.radio.to_string(), tower.key.mcc, tower.key.mnc, tower.key.area, tower.key.cid], |row| row.get(0),
        )?;
        if !hidden {
            clear_removal(&transaction, &tower.key)?;
        }
        audit_in_transaction(&transaction, actor, "correct_tower", &key_text(&tower.key))?;
        transaction.commit()?;
        Ok(Some(consensus))
    }

    pub(crate) fn tower_contribution_page(
        &self,
        key: &CellKey,
        device: &str,
        limit: usize,
        offset: usize,
    ) -> Result<Vec<OwnContribution>> {
        let connection = self.connection()?;
        let mut statement = connection.prepare("SELECT radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples,updated_s FROM contributions
             WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5 AND device LIKE ?6 ORDER BY updated_s DESC,device LIMIT ?7 OFFSET ?8")?;
        Ok(statement
            .query_map(
                params![
                    key.radio.to_string(),
                    key.mcc,
                    key.mnc,
                    key.area,
                    key.cid,
                    format!("%{}%", device.trim()),
                    i64::try_from(limit)?,
                    i64::try_from(offset)?
                ],
                row_to_own_contribution,
            )?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub(crate) fn remove_tower_contribution(
        &self,
        actor: i64,
        key: &CellKey,
        device: &str,
        policy: &Policy,
    ) -> Result<Option<OwnContributionChange>> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let removed = transaction.execute("DELETE FROM contributions WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5 AND device=?6",
            params![key.radio.to_string(),key.mcc,key.mnc,key.area,key.cid,device])?;
        if removed == 0 {
            return Ok(None);
        }
        let change = update_consensus_after_deletion(&transaction, key, policy)?;
        audit_in_transaction(&transaction, actor, "delete_observation", &key_text(key))?;
        transaction.commit()?;
        Ok(Some(change))
    }

    pub fn stored_policy(&self) -> Result<Option<Policy>> {
        self.connection()?
            .query_row(
                "SELECT value FROM server_settings WHERE key='policy'",
                [],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .map(|json| serde_json::from_str(&json).map_err(Into::into))
            .transpose()
    }

    /// Recalculate all tower results and publish a new policy in one SQLite transaction.
    pub(crate) fn apply_policy_live(
        &self,
        policy: &Policy,
        cancel: &AtomicBool,
        progress: &AtomicUsize,
        visibility: &tokio::sync::RwLock<()>,
        active: &std::sync::RwLock<Policy>,
    ) -> Result<bool> {
        policy.validate()?;
        let mut connection = self.connection()?;
        connection.busy_timeout(std::time::Duration::from_secs(3600))?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut statement =
            transaction.prepare("SELECT DISTINCT radio,mcc,mnc,area,cid FROM contributions")?;
        let mut rows = statement.query([])?;
        let mut index = 0;
        while let Some(row) = rows.next()? {
            if cancel.load(Ordering::Relaxed) {
                return Ok(false);
            }
            let key = row_to_key(row)?;
            let hidden: bool = transaction.query_row(
                "SELECT COALESCE((SELECT quarantined FROM tower_moderation WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5),0)",
                params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid], |row| row.get(0),
            )?;
            match recompute_filtered(&transaction, &key, policy)? {
                Some(mut consensus) => {
                    // Force incremental clients to receive the result even if the input rows are old.
                    consensus.updated_s = consensus.updated_s.max(now_s());
                    save_consensus(&transaction, &consensus)?;
                    if hidden || !(consensus.seeded || consensus.devices >= policy.min_devices) {
                        record_removal(&transaction, &key)?;
                    } else {
                        clear_removal(&transaction, &key)?;
                    }
                }
                None => {
                    transaction.execute(
                        "DELETE FROM consensus WHERE radio=?1 AND mcc=?2 AND mnc=?3 AND area=?4 AND cid=?5",
                        params![key.radio.to_string(), key.mcc, key.mnc, key.area, key.cid],
                    )?;
                    record_removal(&transaction, &key)?;
                }
            }
            index += 1;
            progress.store(index, Ordering::Relaxed);
        }
        drop(rows);
        drop(statement);
        if cancel.load(Ordering::Relaxed) {
            return Ok(false);
        }
        transaction.execute(
            "INSERT INTO server_settings(key,value) VALUES ('policy',?1)
             ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            [serde_json::to_string(policy)?],
        )?;
        let _guard = visibility.blocking_write();
        transaction.commit()?;
        *active
            .write()
            .map_err(|_| anyhow::anyhow!("policy lock poisoned"))? = policy.clone();
        Ok(true)
    }
}

fn key_text(key: &CellKey) -> String {
    format!(
        "{}:{}:{}:{}:{}",
        key.radio, key.mcc, key.mnc, key.area, key.cid
    )
}

fn audit_in_transaction(
    transaction: &Transaction<'_>,
    actor: i64,
    action: &str,
    target: &str,
) -> Result<()> {
    transaction.execute(
        "INSERT INTO admin_audit(actor_id,action,target,at_s) VALUES (?1,?2,?3,?4)",
        params![actor, action, target, now_s()],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests;
