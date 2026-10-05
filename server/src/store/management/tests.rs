use super::*;
use crate::auth;
use tempfile::NamedTempFile;

fn tower(cid: i64, lat: f64) -> CellTower {
    CellTower {
        key: CellKey {
            radio: Radio::Lte,
            mcc: 255,
            mnc: 1,
            area: 10,
            cid,
        },
        lat,
        lon: 30.5,
        range_m: 500.0,
        samples: 5,
    }
}

#[test]
fn existing_account_schema_is_migrated_without_losing_accounts() -> Result<()> {
    let database = NamedTempFile::new()?;
    let connection = rusqlite::Connection::open(database.path())?;
    connection.execute_batch("CREATE TABLE users (id INTEGER PRIMARY KEY,email TEXT NOT NULL UNIQUE,password_hash TEXT NOT NULL,admin INTEGER NOT NULL DEFAULT 0);
        INSERT INTO users(email,password_hash,admin) VALUES ('admin@example.org','old-hash',1);")?;
    drop(connection);
    let store = CellStore::open(database.path())?;
    let accounts = store.accounts("")?;
    assert_eq!(accounts.len(), 1);
    assert!(accounts[0].admin);
    assert!(!accounts[0].suspended);
    Ok(())
}

#[test]
fn unfinished_job_is_reported_after_restart() -> Result<()> {
    let database = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    let job = store.start_admin_job("seed import")?;
    let reopened = CellStore::open(database.path())?;
    let recovered = reopened.recover_admin_job()?.unwrap();
    assert_eq!(recovered.id, job);
    assert_eq!(recovered.status, "interrupted");
    reopened.finish_admin_job(job, "complete", 42)?;
    assert_eq!(reopened.recover_admin_job()?.unwrap().processed, 42);
    Ok(())
}

#[test]
fn manual_correction_import_and_quarantine() -> Result<()> {
    let database = NamedTempFile::new()?;
    let import = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    let policy = Policy::default();
    let original = tower(1, 50.4);
    std::fs::write(
        import.path(),
        "radio,mcc,net,area,cell,unit,lon,lat,range,samples\nLTE,255,1,10,1,,30.5,50.4,500,5\n",
    )?;
    let cancel = AtomicBool::new(false);
    let progress = AtomicUsize::new(0);
    assert_eq!(
        store
            .import_seeds(import.path(), &policy, &cancel, &progress)?
            .unwrap()
            .accepted,
        1
    );
    assert_eq!(store.query(None, 0, None, &policy)?.len(), 1);
    assert_eq!(
        store
            .correct_tower(1, &tower(1, 50.8), &policy)?
            .unwrap()
            .tower
            .lat,
        50.8
    );
    store.set_quarantined(1, &original.key, true)?;
    assert!(store.query(None, 0, None, &policy)?.is_empty());
    store.contribute("account:2:phone", &[tower(1, 50.4)], 100, &policy)?;
    assert!(store.query(None, 0, None, &policy)?.is_empty());
    store.import_seeds(import.path(), &policy, &cancel, &progress)?;
    assert_eq!(store.consensus(&original.key)?.unwrap().tower.lat, 50.8);
    assert!(store.delete_quarantined(1, &original.key)?);
    assert!(store.consensus(&original.key)?.is_none());
    store.contribute("account:2:phone", &[tower(1, 50.4)], 101, &policy)?;
    assert!(store.query(None, 0, None, &policy)?.is_empty());
    store.set_quarantined(1, &original.key, false)?;
    assert!(store.query(None, 0, None, &policy)?.is_empty());
    Ok(())
}

#[test]
fn cancelled_jobs_roll_back_and_policy_persists() -> Result<()> {
    let database = NamedTempFile::new()?;
    let import = NamedTempFile::new()?;
    std::fs::write(import.path(), "LTE,255,1,10,1,,30.5,50.4,500,5\n")?;
    let store = CellStore::open(database.path())?;
    let policy = Policy::default();
    let cancel = AtomicBool::new(true);
    let progress = AtomicUsize::new(0);
    assert!(
        store
            .import_seeds(import.path(), &policy, &cancel, &progress)?
            .is_none()
    );
    assert_eq!(store.counts(&policy)?, (0, 0));
    store.seed(&[tower(1, 50.4)], 1, &policy)?;
    let modified = Policy {
        min_devices: 4,
        ..policy.clone()
    };
    let visibility = tokio::sync::RwLock::new(());
    let active = std::sync::RwLock::new(policy);
    assert!(!store.apply_policy_live(&modified, &cancel, &progress, &visibility, &active)?);
    assert!(store.stored_policy()?.is_none());
    cancel.store(false, Ordering::Relaxed);
    assert!(store.apply_policy_live(&modified, &cancel, &progress, &visibility, &active)?);
    assert_eq!(
        CellStore::open(database.path())?
            .stored_policy()?
            .unwrap()
            .min_devices,
        4
    );
    Ok(())
}

#[tokio::test]
async fn policy_and_results_activate_after_readers_finish() -> Result<()> {
    let database = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    let original = Policy::default();
    store.seed(&[tower(1, 50.4)], 1, &original)?;
    let changed = Policy {
        min_devices: 4,
        ..original.clone()
    };
    let gate = std::sync::Arc::new(tokio::sync::RwLock::new(()));
    let active = std::sync::Arc::new(std::sync::RwLock::new(original));
    let cancel = std::sync::Arc::new(AtomicBool::new(false));
    let progress = std::sync::Arc::new(AtomicUsize::new(0));
    let reader = gate.read().await;
    let worker_store = store.clone();
    let worker_gate = gate.clone();
    let worker_active = active.clone();
    let worker_progress = progress.clone();
    let worker = tokio::task::spawn_blocking(move || {
        worker_store.apply_policy_live(
            &changed,
            &cancel,
            &worker_progress,
            &worker_gate,
            &worker_active,
        )
    });
    for _ in 0..100 {
        if progress.load(Ordering::Relaxed) > 0 {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    assert_eq!(progress.load(Ordering::Relaxed), 1);
    assert!(store.stored_policy()?.is_none());
    assert_eq!(active.read().unwrap().min_devices, 2);
    drop(reader);
    assert!(worker.await??);
    assert_eq!(store.stored_policy()?.unwrap().min_devices, 4);
    assert_eq!(active.read().unwrap().min_devices, 4);
    Ok(())
}

#[test]
fn recalculation_reweights_existing_device_samples() -> Result<()> {
    let database = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    let original = Policy::default();
    store.contribute(
        "account:1:first",
        &[CellTower {
            samples: 50,
            ..tower(1, 50.4)
        }],
        1,
        &original,
    )?;
    store.contribute(
        "account:2:second",
        &[CellTower {
            samples: 1,
            ..tower(1, 50.401)
        }],
        2,
        &original,
    )?;
    let before = store.consensus(&tower(1, 50.4).key)?.unwrap().tower.lat;
    let changed = Policy {
        max_samples_per_device: 1,
        ..original.clone()
    };
    let gate = tokio::sync::RwLock::new(());
    let active = std::sync::RwLock::new(original);
    assert!(store.apply_policy_live(
        &changed,
        &AtomicBool::new(false),
        &AtomicUsize::new(0),
        &gate,
        &active
    )?);
    let after = store.consensus(&tower(1, 50.4).key)?.unwrap().tower.lat;
    assert!(after > before + 0.0004);
    Ok(())
}

#[test]
fn suspension_revokes_tokens_and_retains_observations() -> Result<()> {
    let database = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    auth::create_admin(&store, "admin@example.org", "correct horse battery staple")?;
    let admin =
        auth::login_account(&store, "admin@example.org", "correct horse battery staple")?.unwrap();
    let user =
        auth::register_account(&store, "driver@example.org", "correct horse battery staple")?
            .unwrap();
    store.seed(&[tower(1, 50.4)], 1, &Policy::default())?;
    store.contribute(
        &format!("account:{}:phone", user.id),
        &[tower(1, 50.4)],
        2,
        &Policy::default(),
    )?;
    store.connection()?.execute("INSERT INTO auth_tokens(token_hash,user_id,session_id,kind,expires_s) VALUES (?1,?2,'session','access',?3)",
        params![auth::digest("test-token"), user.id, auth::now_s()+100])?;
    assert!(store.set_suspended(admin.id, user.id, true)?);
    assert!(
        auth::login_account(&store, "driver@example.org", "correct horse battery staple")?
            .is_none()
    );
    assert!(auth::account_for_token(&store, "test-token", "access")?.is_none());
    assert_eq!(store.consensus(&tower(1, 50.4).key)?.unwrap().devices, 1);
    assert!(store.set_suspended(admin.id, user.id, false)?);
    assert!(
        auth::login_account(&store, "driver@example.org", "correct horse battery staple")?
            .is_some()
    );
    assert!(!store.set_suspended(admin.id, admin.id, true)?);
    assert_eq!(store.accounts("")?.len(), 2);
    assert!(!store.recent_audit()?.is_empty());
    Ok(())
}

#[test]
fn exports_include_quarantine_but_not_credentials() -> Result<()> {
    let database = NamedTempFile::new()?;
    let export = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    let item = tower(1, 50.4);
    store.seed(std::slice::from_ref(&item), 1, &Policy::default())?;
    store.set_quarantined(1, &item.key, true)?;
    store.export_to_path(export.path(), false)?;
    let mut plain = String::new();
    GzDecoder::new(std::fs::File::open(export.path())?).read_to_string(&mut plain)?;
    assert!(plain.contains("quarantined"));
    assert!(plain.contains("LTE,255,1,10,1"));
    store.export_to_path(export.path(), true)?;
    plain.clear();
    GzDecoder::new(std::fs::File::open(export.path())?).read_to_string(&mut plain)?;
    assert!(plain.contains("device"));
    assert!(!plain.contains("password_hash"));
    Ok(())
}
