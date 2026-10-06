use super::*;
use tempfile::NamedTempFile;

#[test]
fn privacy_withdrawal_erases_only_its_purpose_and_exports_receipts() -> Result<()> {
    let file = NamedTempFile::new()?;
    let store = CellStore::open(file.path())?;
    let policy = Policy::default();
    store.connection()?.execute(
        "INSERT INTO users(id,email,password_hash) VALUES (1,'driver@example.org','secret-hash')",
        params![],
    )?;
    let item = tower(900, 50.3, 30.4, 5);
    store.contribute(
        "account:1:phone-aaaa",
        std::slice::from_ref(&item),
        current_time_s()?,
        &policy,
    )?;
    store.connection()?.execute("INSERT INTO debug_sessions(id,account_id,client_id,context_json,created_s,updated_s) VALUES ('trip',1,'phone','{}',?1,?1)", [current_time_s()?])?;
    assert!(!store.has_privacy_consent(1, "tower_upload", "v1")?);
    store.grant_privacy_consent(1, "tower_upload", "v1")?;
    store.grant_privacy_consent(1, "diagnostics", "v1")?;
    assert!(store.has_privacy_consent(1, "tower_upload", "v1")?);
    assert!(!store.has_privacy_consent(1, "tower_upload", "v2")?);
    let archive = NamedTempFile::new()?;
    store.export_account_to_path(1, archive.path())?;
    let mut contents = String::new();
    std::io::Read::read_to_string(
        &mut flate2::read::GzDecoder::new(std::fs::File::open(archive.path())?),
        &mut contents,
    )?;
    assert!(contents.contains("tower_observation"));
    assert!(contents.contains("diagnostic_session"));
    assert!(contents.contains("notice_version"));
    assert!(!contents.contains("secret-hash"));
    store.withdraw_privacy_consent(1, "tower_upload", &policy)?;
    assert!(!store.has_privacy_consent(1, "tower_upload", "v1")?);
    assert_eq!(store.counts(&policy)?.1, 0);
    assert!(store.has_privacy_consent(1, "diagnostics", "v1")?);
    store.withdraw_privacy_consent(1, "diagnostics", &policy)?;
    let sessions: i64 =
        store
            .connection()?
            .query_row("SELECT COUNT(*) FROM debug_sessions", params![], |row| {
                row.get(0)
            })?;
    assert_eq!(sessions, 0);
    store.replay_withdrawal(1, "tower_upload", &policy)?;
    store.replay_account_closure(1, &policy)?;
    store.replay_withdrawal(1, "diagnostics", &policy)?;
    store.replay_account_closure(1, &policy)?;
    let accounts: i64 = store.connection()?.query_row(
        "SELECT COUNT(*) FROM users WHERE id=1",
        params![],
        |row| row.get(0),
    )?;
    assert_eq!(accounts, 0);
    Ok(())
}

#[test]
fn retention_recomputes_consensus_and_prunes_transient_rows() -> Result<()> {
    let file = NamedTempFile::new()?;
    let store = CellStore::open(file.path())?;
    let policy = Policy::default();
    let item = tower(901, 50.3, 30.4, 5);
    store.contribute(
        "account:1:phone-aaaa",
        std::slice::from_ref(&item),
        current_time_s()?,
        &policy,
    )?;
    store.contribute(
        "account:2:phone-bbbb",
        std::slice::from_ref(&item),
        current_time_s()?,
        &policy,
    )?;
    assert_eq!(store.query(None, 0, None, &policy)?.len(), 1);
    store.connection()?.execute(
        "UPDATE contributions SET updated_s=1 WHERE device='account:1:phone-aaaa'",
        params![],
    )?;
    store.prune_retained_data(&policy)?;
    assert!(store.query(None, 0, None, &policy)?.is_empty());
    assert_eq!(store.removals(None, 0)?, vec![item.key]);
    Ok(())
}

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

#[test]
fn deleting_an_observation_updates_incremental_sync() -> Result<()> {
    let file = NamedTempFile::new()?;
    let store = CellStore::open(file.path())?;
    let policy = Policy::default();
    let item = tower(44, 50.3, 30.4, 5);
    store.contribute("account:1:phone", std::slice::from_ref(&item), 10, &policy)?;
    store.contribute("account:2:phone", std::slice::from_ref(&item), 11, &policy)?;
    assert_eq!(store.query(None, 0, None, &policy)?.len(), 1);
    store.delete_own_contribution(2, &item.key, "account:2:phone", &policy)?;
    assert!(store.query(None, 0, None, &policy)?.is_empty());
    assert_eq!(store.removals(None, 0)?, vec![item.key.clone()]);
    store.contribute(
        "account:3:phone",
        std::slice::from_ref(&item),
        crate::auth::now_s(),
        &policy,
    )?;
    assert_eq!(store.query(None, 0, None, &policy)?.len(), 1);
    assert_eq!(store.removals(None, 0)?, []);
    store.delete_own_contribution(1, &item.key, "account:1:phone", &policy)?;
    assert_eq!(store.removals(None, 0)?, vec![item.key]);
    Ok(())
}
