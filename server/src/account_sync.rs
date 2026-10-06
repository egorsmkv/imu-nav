//! Private, revisioned account preferences and bookmark recipes. Never publishes location data.
use crate::api::{ApiError, AppState, run_db};
use crate::auth;
use crate::db::params;
use crate::store::CellStore;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{HeaderMap, StatusCode};
use axum::{Json, Router, routing::get};
use rusqlite::OptionalExtension;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeSet;

const MAX_ENTRIES: i64 = 4096;
const MAX_BATCH: usize = 128;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Entry {
    pub kind: String,
    pub key: String,
    pub revision: i64,
    pub value: Option<Value>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Update {
    version: u32,
    generation: i64,
    changes: Vec<Entry>,
}

#[derive(Serialize)]
struct Snapshot {
    version: u32,
    account_id: i64,
    generation: i64,
    enabled: bool,
    notice_version: String,
    entries: Vec<Entry>,
    conflicts: Vec<String>,
}

pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/v1/account-sync", get(read).put(write))
        .layer(DefaultBodyLimit::max(512 * 1024))
}

pub(crate) fn notice_version(state: &AppState) -> String {
    state
        .config
        .privacy
        .as_ref()
        .map_or_else(|| "local".to_owned(), |notice| notice.version.clone())
}

impl CellStore {
    /// A permanent opaque identity prevents SQLite row-ID reuse from exposing a retired local cache.
    pub(crate) fn sync_identity(&self, account: i64) -> anyhow::Result<String> {
        let connection = self.connection()?;
        let identity = format!("{:032x}", rand::random::<u128>());
        connection.execute("INSERT INTO account_sync_identity(account_id,identity) VALUES (?1,?2) ON CONFLICT(account_id) DO NOTHING", params![account,identity])?;
        Ok(connection.query_row(
            "SELECT identity FROM account_sync_identity WHERE account_id=?1",
            [account],
            |row| row.get(0),
        )?)
    }

    pub(crate) fn sync_generation(&self, account: i64) -> anyhow::Result<i64> {
        Ok(self
            .connection()?
            .query_row(
                "SELECT generation FROM account_sync_state WHERE account_id=?1",
                [account],
                |row| row.get(0),
            )
            .optional()?
            .unwrap_or(0))
    }

    pub(crate) fn sync_entries(&self, account: i64) -> anyhow::Result<Vec<Entry>> {
        let connection = self.connection()?;
        let mut statement = connection.prepare("SELECT kind,key,revision,value_json FROM account_sync_entries WHERE account_id=?1 ORDER BY kind,key")?;
        let rows = statement.query_map([account], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, Option<String>>(3)?,
            ))
        })?;
        rows.map(|row| {
            let (kind, key, revision, json) = row?;
            Ok(Entry {
                kind,
                key,
                revision,
                value: json.map(|value| serde_json::from_str(&value)).transpose()?,
            })
        })
        .collect()
    }

    /// Conditional writes share a transaction; retries compare values before reporting conflicts.
    fn update_sync(&self, account: i64, changes: &[Entry]) -> anyhow::Result<Option<Vec<String>>> {
        let mut connection = self.connection()?;
        let transaction = connection.transaction()?;
        let mut count: i64 = transaction.query_row(
            "SELECT COUNT(*) FROM account_sync_entries WHERE account_id=?1",
            [account],
            |row| row.get(0),
        )?;
        let mut conflicts = Vec::new();
        for entry in changes {
            let previous: Option<(i64,Option<String>)> = transaction.query_row("SELECT revision,value_json FROM account_sync_entries WHERE account_id=?1 AND kind=?2 AND key=?3",params![account,entry.kind,entry.key],|row| Ok((row.get(0)?,row.get(1)?))).optional()?;
            let value = entry
                .value
                .as_ref()
                .map(serde_json::to_string)
                .transpose()?;
            let revision = previous.as_ref().map_or(0, |(revision, _)| *revision);
            if previous
                .as_ref()
                .is_some_and(|(_, stored)| *stored == value)
            {
                continue;
            }
            if revision != entry.revision {
                conflicts.push(format!("{}:{}", entry.kind, entry.key));
                continue;
            }
            if previous.is_none() {
                count += 1;
                if count > MAX_ENTRIES {
                    return Ok(None);
                }
            }
            transaction.execute("INSERT INTO account_sync_entries(account_id,kind,key,revision,value_json) VALUES (?1,?2,?3,?4,?5) ON CONFLICT(account_id,kind,key) DO UPDATE SET revision=excluded.revision,value_json=excluded.value_json", params![account,entry.kind,entry.key,revision+1,value])?;
        }
        transaction.commit()?;
        Ok(Some(conflicts))
    }
}

async fn snapshot(
    state: &AppState,
    account: i64,
    conflicts: Vec<String>,
) -> Result<Json<Snapshot>, ApiError> {
    let notice_version = notice_version(state);
    let version = notice_version.clone();
    let store = state.store.clone();
    let (enabled, generation, entries) = run_db(move || {
        let enabled = store.has_privacy_consent(account, "account_sync", &version)?;
        Ok((
            enabled,
            store.sync_generation(account)?,
            if enabled {
                store.sync_entries(account)?
            } else {
                Vec::new()
            },
        ))
    })
    .await?;
    Ok(Json(Snapshot {
        version: 1,
        account_id: account,
        generation,
        enabled,
        notice_version,
        entries,
        conflicts,
    }))
}

async fn read(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Snapshot>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    let _gate = state.write_gate.read().await;
    snapshot(&state, account.id, Vec::new()).await
}

async fn write(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(update): Json<Update>,
) -> Result<Json<Snapshot>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    let mut keys = BTreeSet::new();
    if update.version != 1
        || update.generation < 0
        || update.changes.len() > MAX_BATCH
        || update
            .changes
            .iter()
            .any(|entry| !valid_entry(entry) || !keys.insert((&entry.kind, &entry.key)))
    {
        return Err(ApiError(StatusCode::BAD_REQUEST, "INVALID_SYNC_DATA"));
    }
    let _gate = state.write_gate.write().await;
    let store = state.store.clone();
    let version = notice_version(&state);
    let permitted = run_db(move || {
        Ok(
            store.has_privacy_consent(account.id, "account_sync", &version)?
                && store.sync_generation(account.id)? == update.generation,
        )
    })
    .await?;
    if !permitted {
        return Err(ApiError(StatusCode::CONFLICT, "SYNC_CONSENT_CHANGED"));
    }
    let store = state.store.clone();
    let conflicts = run_db(move || store.update_sync(account.id, &update.changes))
        .await?
        .ok_or(ApiError(
            StatusCode::PAYLOAD_TOO_LARGE,
            "SYNC_QUOTA_EXCEEDED",
        ))?;
    snapshot(&state, account.id, conflicts).await
}

fn valid_entry(entry: &Entry) -> bool {
    if entry.revision < 0
        || entry.key.is_empty()
        || entry.key.len() > 128
        || !entry
            .key
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_-".contains(&byte))
    {
        return false;
    }
    match entry.kind.as_str() {
        "setting" => entry
            .value
            .as_ref()
            .is_some_and(|value| valid_setting(&entry.key, value)),
        "bookmark" => entry.value.as_ref().is_none_or(valid_bookmark),
        _ => false,
    }
}

fn valid_setting(key: &str, value: &Value) -> bool {
    let choices: &[&str] = match key {
        "language" => &["system", "en", "uk", "ru"],
        "travel_mode" => &["CAR", "FOOT"],
        "navigation_method" => &["DEAD_RECKONING", "CELL_TOWERS", "HYBRID"],
        "navigation_estimator" => &["KOTLIN", "NATIVE_KALMAN"],
        "power_mode" => &["AUTO", "PERFORMANCE", "BALANCED", "SAVER"],
        "map_start" => {
            return value.as_object().is_some_and(|object| {
                object.len() == 2
                    && matches!(value["mode"].as_str(), Some("GPS" | "FIXED"))
                    && (value["point"].is_null() && value["mode"] == "GPS"
                        || valid_point(&value["point"]))
            });
        }
        "radios" => {
            return value.as_array().is_some_and(|values| {
                values.len() <= 4
                    && values
                        .iter()
                        .all(|radio| matches!(radio.as_str(), Some("GSM" | "UMTS" | "LTE" | "NR")))
            });
        }
        "voice" | "haptics" | "terrain" | "screen_on" | "offline_map" | "corridor"
        | "online_routing" | "show_towers" => return value.is_boolean(),
        _ => return false,
    };
    value
        .as_str()
        .is_some_and(|choice| choices.contains(&choice))
}

fn valid_point(value: &Value) -> bool {
    value.as_object().is_some_and(|object| {
        object.len() == 2
            && value["lat"]
                .as_f64()
                .is_some_and(|lat| lat.is_finite() && (-90.0..=90.0).contains(&lat))
            && value["lon"]
                .as_f64()
                .is_some_and(|lon| lon.is_finite() && (-180.0..=180.0).contains(&lon))
    })
}

fn valid_endpoint(value: &Value) -> bool {
    value.as_object().is_some_and(|object| {
        object.len() == 2
            && valid_point(&value["point"])
            && (value["label"].is_null()
                || value["label"]
                    .as_str()
                    .is_some_and(|label| label.len() <= 1024))
    })
}

fn valid_bookmark(value: &Value) -> bool {
    let Some(object) = value.as_object() else {
        return false;
    };
    if !value["name"]
        .as_str()
        .is_some_and(|name| !name.is_empty() && name.trim() == name && name.len() <= 512)
    {
        return false;
    }
    match value["type"].as_str() {
        Some("place") => object.len() == 3 && valid_endpoint(&value["endpoint"]),
        Some("route") => {
            object.len() == 5
                && valid_endpoint(&value["destination"])
                && (value["origin"].is_null() || valid_endpoint(&value["origin"]))
                && matches!(value["mode"].as_str(), Some("CAR" | "FOOT"))
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tempfile::NamedTempFile;

    fn entry(key: &str, revision: i64, value: Option<Value>) -> Entry {
        Entry {
            kind: "bookmark".into(),
            key: key.into(),
            revision,
            value,
        }
    }
    fn place(name: &str) -> Value {
        json!({"type":"place","name":name,"endpoint":{"point":{"lat":50.0,"lon":30.0},"label":null}})
    }

    #[test]
    fn revisions_retry_merge_delete_and_account_erasure() -> anyhow::Result<()> {
        let file = NamedTempFile::new()?;
        let store = CellStore::open(file.path())?;
        store.connection()?.execute("INSERT INTO users(id,email,password_hash) VALUES (1,'one@example.org','hash'),(2,'two@example.org','hash')",params![])?;
        let identity = store.sync_identity(1)?;
        assert_eq!(identity, store.sync_identity(1)?);
        let deleted_identity = store.sync_identity(2)?;
        assert_ne!(identity, deleted_identity);
        store.grant_privacy_consent(1, "account_sync", "local")?;
        let first = entry("home", 0, Some(place("Home")));
        assert_eq!(
            store.update_sync(1, std::slice::from_ref(&first))?,
            Some(vec![])
        );
        assert_eq!(
            store.update_sync(1, std::slice::from_ref(&first))?,
            Some(vec![])
        );
        assert_eq!(store.sync_entries(1)?[0].revision, 1);
        assert!(store.sync_entries(2)?.is_empty());
        assert_eq!(
            store.update_sync(
                1,
                &[
                    entry("home", 0, Some(place("Stale"))),
                    entry("work", 0, Some(place("Work")))
                ]
            )?,
            Some(vec!["bookmark:home".into()])
        );
        assert_eq!(store.sync_entries(1)?.len(), 2);
        store.update_sync(1, &[entry("home", 1, None)])?;
        assert_eq!(
            store.update_sync(1, &[first])?,
            Some(vec!["bookmark:home".into()])
        );
        assert!(store.sync_entries(1)?[0].value.is_none());
        let archive = NamedTempFile::new()?;
        store.export_account_to_path(1, archive.path())?;
        let mut exported = String::new();
        std::io::Read::read_to_string(
            &mut flate2::read::GzDecoder::new(std::fs::File::open(archive.path())?),
            &mut exported,
        )?;
        assert!(exported.contains("account_sync"));
        assert!(!exported.contains("password_hash"));
        assert_eq!(store.sync_generation(1)?, 0);
        store.replay_withdrawal(1, "account_sync", &crate::Policy::default())?;
        assert!(store.sync_entries(1)?.is_empty());
        assert_eq!(store.sync_generation(1)?, 1);
        assert!(!store.has_privacy_consent(1, "account_sync", "local")?);
        store.update_sync(2, &[entry("other", 0, Some(place("Other")))])?;
        store.close_own_account(2, &crate::Policy::default())?;
        assert!(store.sync_entries(2)?.is_empty());
        store.connection()?.execute(
            "INSERT INTO users(id,email,password_hash) VALUES (2,'replacement@example.org','hash')",
            params![],
        )?;
        assert_ne!(deleted_identity, store.sync_identity(2)?);
        Ok(())
    }

    #[test]
    fn validate_allowlist_and_bookmark_shapes() {
        for (key, value) in [
            ("language", json!("uk")),
            ("travel_mode", json!("FOOT")),
            ("navigation_method", json!("HYBRID")),
            ("navigation_estimator", json!("NATIVE_KALMAN")),
            ("power_mode", json!("SAVER")),
            ("voice", json!(true)),
            ("radios", json!(["LTE", "NR"])),
            ("map_start", json!({"mode":"GPS","point":null})),
        ] {
            assert!(valid_setting(key, &value), "{key}");
            assert!(!valid_setting(key, &json!("invalid")), "{key}");
        }
        for key in ["password", "proxy", "token", "diagnostics", "server_url"] {
            assert!(!valid_setting(key, &json!("secret")));
        }
        assert!(valid_bookmark(&place("Home")));
        assert!(!valid_bookmark(&place("")));
        assert!(!valid_bookmark(&place(" untrimmed")));
        assert!(!valid_bookmark(&json!(true)));
        assert!(!valid_bookmark(&json!({"type":"unknown","name":"x"})));
        let mut route = json!({"type":"route","name":"Work","origin":null,"destination":{"point":{"lat":50.0,"lon":30.0},"label":"Work"},"mode":"CAR"});
        assert!(valid_bookmark(&route));
        route["origin"] = json!({"point":{"lat":49.0,"lon":29.0},"label":null});
        assert!(valid_bookmark(&route));
        route["destination"]["point"]["lat"] = json!(91);
        assert!(!valid_bookmark(&route));
        assert!(!valid_entry(&entry("bad/key", 0, None)));
        assert!(!valid_entry(&entry("key", -1, None)));
        assert!(!valid_entry(&Entry {
            kind: "secret".into(),
            key: "key".into(),
            revision: 0,
            value: None
        }));
        assert!(!valid_entry(&Entry {
            kind: "setting".into(),
            key: "voice".into(),
            revision: 0,
            value: None
        }));
        assert!(valid_entry(&entry("deleted", 0, None)));
    }

    #[test]
    fn quota_rejects_entire_transaction() -> anyhow::Result<()> {
        let file = NamedTempFile::new()?;
        let store = CellStore::open(file.path())?;
        store.connection()?.execute(
            "INSERT INTO users(id,email,password_hash) VALUES (1,'one@example.org','hash')",
            params![],
        )?;
        let entries = (0..MAX_ENTRIES)
            .map(|i| entry(&format!("b{i}"), 0, None))
            .collect::<Vec<_>>();
        assert!(store.update_sync(1, &entries)?.is_some());
        assert!(
            store
                .update_sync(
                    1,
                    &[
                        entry("b0", 1, Some(place("Changed"))),
                        entry("overflow", 0, None)
                    ]
                )?
                .is_none()
        );
        assert!(store.sync_entries(1)?.iter().all(|e| e.value.is_none()));
        Ok(())
    }
}
