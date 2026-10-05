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
