//! User-initiated performance ZIPs share diagnostics ownership, quotas and erasure.

use super::{MAX_ACCOUNT_BYTES, enabled, unavailable};
use crate::api::{ApiError, AppState, run_db};
use crate::auth;
use crate::db::params;
use axum::Json;
use axum::Router;
use axum::body::to_bytes;
use axum::extract::{Request, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::routing::get;
use rusqlite::{OptionalExtension, TransactionBehavior};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::io::{Cursor, Read};

const MAX_UPLOAD_BYTES: usize = 48 * 1024 * 1024;
const MAX_FILES: usize = 6;

pub(super) fn router() -> Router<AppState> {
    Router::new().route("/v1/debug/profiles", get(metadata).post(upload))
}

/// The app shows the current operator notice before asking to upload each archive.
async fn metadata(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    auth::bearer_account(&state, &headers).await?;
    let store = state.store.clone();
    let available = run_db(move || enabled(&store)).await?;
    Ok(Json(json!({
        "enabled": available,
        "upload_bytes": MAX_UPLOAD_BYTES,
        "notice_version": state.config.privacy.as_ref().map(|notice| &notice.version),
        "notice": state.config.privacy,
        "retention_days": 30
    })))
}

/// Authenticate and reserve capacity before buffering any ZIP; serialize quota checks with erasure.
async fn upload(State(state): State<AppState>, request: Request) -> Result<Json<Value>, ApiError> {
    let account = auth::bearer_account(&state, request.headers()).await?;
    if request
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        != Some("application/zip")
    {
        return Err(ApiError(
            StatusCode::UNSUPPORTED_MEDIA_TYPE,
            "PROFILE_ZIP_REQUIRED",
        ));
    }
    let permit = state
        .profile_transfers
        .clone()
        .try_acquire_owned()
        .map_err(|_| ApiError(StatusCode::TOO_MANY_REQUESTS, "PROFILE_BUSY"))?;
    let version = state
        .config
        .privacy
        .as_ref()
        .map(|notice| notice.version.clone());
    let store = state.store.clone();
    let check_version = version.clone();
    run_db(move || allowed(&store, account.id, check_version.as_deref())).await??;
    let body = tokio::time::timeout(
        std::time::Duration::from_secs(120),
        to_bytes(request.into_body(), MAX_UPLOAD_BYTES),
    )
    .await
    .map_err(|_| ApiError(StatusCode::REQUEST_TIMEOUT, "PROFILE_TIMEOUT"))?
    .map_err(|_| ApiError(StatusCode::PAYLOAD_TOO_LARGE, "PROFILE_TOO_LARGE"))?;
    let store = state.store.clone();
    // An owned guard remains held even if the request is cancelled while its DB worker runs.
    let gate = state.write_gate.clone().write_owned().await;
    run_db(move || {
        let _permit = permit;
        let _gate = gate;
        if let Err(error) = allowed(&store, account.id, version.as_deref())? {
            return Ok(Err(error));
        }
        let Ok(manifest) = validate_archive(&body) else {
            return Ok(Err(ApiError(
                StatusCode::BAD_REQUEST,
                "INVALID_PROFILE_ZIP",
            )));
        };
        store.prune_debug_sessions()?;
        save(&store, account.id, &body, &manifest)
    })
    .await?
    .map(Json)
}

fn allowed(
    store: &crate::CellStore,
    account_id: i64,
    version: Option<&str>,
) -> anyhow::Result<Result<(), ApiError>> {
    if !enabled(store)? {
        return Ok(Err(unavailable()));
    }
    if let Some(version) = version
        && !store.has_privacy_consent(account_id, "diagnostics", version)?
    {
        return Ok(Err(ApiError(StatusCode::FORBIDDEN, "CONSENT_REQUIRED")));
    }
    Ok(Ok(()))
}

/// ZIPs are inspected without extraction. Every entry is bounded and fully read to check its CRC.
fn validate_archive(body: &[u8]) -> Result<Value, ()> {
    // App bundles use ordinary single-disk ZIPs without comments or ZIP64. Bound the
    // central directory before the ZIP reader allocates its file metadata.
    let count = entry_count(body)?;
    let mut zip = zip::ZipArchive::new(Cursor::new(body)).map_err(|_| ())?;
    if zip.len() != count || zip.has_overlapping_files().map_err(|_| ())? {
        return Err(());
    }
    let mut names = HashSet::new();
    let mut manifest = None;
    for index in 0..zip.len() {
        let mut file = zip.by_index(index).map_err(|_| ())?;
        let limit: u64 = match file.name() {
            "manifest.json" => 16 * 1024,
            "memory.csv" => 1024 * 1024,
            "native-timings.json" => 64 * 1024,
            "logs.txt" => 8 * 1024 * 1024,
            "trip-events.rec" | "methods.trace" => 16 * 1024 * 1024,
            _ => return Err(()),
        };
        if !names.insert(file.name().to_owned())
            || file.is_dir()
            || file.is_symlink()
            || file.size() > limit
        {
            return Err(());
        }
        // Some ZIP readers use local filenames rather than the central directory.
        // Check both so a downloaded archive cannot hide a traversal path there.
        let start = usize::try_from(file.header_start()).map_err(|_| ())?;
        let header = body
            .get(start..start.checked_add(30).ok_or(())?)
            .ok_or(())?;
        let name_size = usize::from(u16::from_le_bytes([header[26], header[27]]));
        if &header[..4] != b"PK\x03\x04"
            || body.get(start + 30..start + 30 + name_size) != Some(file.name_raw())
        {
            return Err(());
        }
        let is_manifest = file.name() == "manifest.json";
        let declared = file.size();
        let read = if is_manifest {
            let mut bytes = Vec::new();
            file.by_ref()
                .take(limit + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| ())?;
            manifest = Some(serde_json::from_slice::<Value>(&bytes).map_err(|_| ())?);
            u64::try_from(bytes.len()).map_err(|_| ())?
        } else {
            std::io::copy(&mut file.by_ref().take(limit + 1), &mut std::io::sink())
                .map_err(|_| ())?
        };
        if read > limit || read != declared {
            return Err(());
        }
    }
    let manifest = manifest.ok_or(())?;
    if !matches!(manifest.get("schema").and_then(Value::as_u64), Some(1 | 2))
        || !manifest.get("interrupted").is_some_and(Value::is_boolean)
        || (manifest["interrupted"] == false && !names.contains("memory.csv"))
    {
        return Err(());
    }
    Ok(manifest)
}

/// Reject ZIP64, comments and oversized directories before parsing attacker-controlled metadata.
fn entry_count(body: &[u8]) -> Result<usize, ()> {
    const END_BYTES: usize = 22;
    const MAX_DIRECTORY_BYTES: usize = 8 * 1024;
    let offset = body.len().checked_sub(END_BYTES).ok_or(())?;
    let end = &body[offset..];
    if &end[..4] != b"PK\x05\x06" || end[4..8] != [0; 4] || end[20..22] != [0; 2] {
        return Err(());
    }
    let count = usize::from(u16::from_le_bytes([end[10], end[11]]));
    if !(1..=MAX_FILES).contains(&count) || end[8..10] != end[10..12] {
        return Err(());
    }
    let size = usize::try_from(u32::from_le_bytes(end[12..16].try_into().map_err(|_| ())?))
        .map_err(|_| ())?;
    let start = usize::try_from(u32::from_le_bytes(end[16..20].try_into().map_err(|_| ())?))
        .map_err(|_| ())?;
    if size > MAX_DIRECTORY_BYTES || start.checked_add(size) != Some(offset) {
        return Err(());
    }
    Ok(count)
}

/// Content hashing makes retries safe without trusting filenames or client-provided identifiers.
fn save(
    store: &crate::CellStore,
    account_id: i64,
    body: &[u8],
    manifest: &Value,
) -> anyhow::Result<Result<Value, ApiError>> {
    let client_id = format!("profile:{:x}", Sha256::digest(body));
    let mut connection = store.connection()?;
    let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    if let Some(id) = tx.query_row(
        "SELECT s.id FROM debug_sessions s JOIN debug_profiles p ON p.session_id=s.id WHERE s.account_id=?1 AND s.client_id=?2",
        params![account_id,client_id], |row| row.get::<_,String>(0),
    ).optional()? {
        return Ok(Ok(json!({"id":id})));
    }
    let (count, bytes): (i64,i64) = tx.query_row(
        "SELECT COUNT(*),CAST(COALESCE(SUM(bytes),0) AS BIGINT) FROM debug_sessions WHERE account_id=?1", [account_id],
        |row| Ok((row.get(0)?,row.get(1)?)),
    )?;
    let size = i64::try_from(body.len())?;
    if count >= 100 || bytes + size > MAX_ACCOUNT_BYTES {
        return Ok(Err(ApiError(
            StatusCode::PAYLOAD_TOO_LARGE,
            "DEBUG_QUOTA_EXCEEDED",
        )));
    }
    let id = format!("{:032x}", rand::random::<u128>());
    tx.execute("INSERT INTO debug_sessions(id,account_id,client_id,context_json,created_s,updated_s,finished_s,incomplete,bytes) VALUES (?1,?2,?3,?4,?5,?5,?5,?6,?7)",
        params![id,account_id,client_id,manifest.to_string(),auth::now_s(),manifest["interrupted"].as_bool().unwrap_or(false),size])?;
    tx.execute(
        "INSERT INTO debug_profiles(session_id,archive) VALUES (?1,?2)",
        params![id, body],
    )?;
    tx.commit()?;
    Ok(Ok(json!({"id":id})))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::write::SimpleFileOptions;

    fn archive(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
        for (name, bytes) in entries {
            writer
                .start_file(
                    *name,
                    SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored),
                )
                .unwrap();
            writer.write_all(bytes).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    #[test]
    fn complete_and_interrupted_bundles_are_valid_without_extraction() {
        for interrupted in [true, false] {
            let manifest = json!({"schema":2,"interrupted":interrupted}).to_string();
            let bytes = archive(&[
                ("manifest.json", manifest.as_bytes()),
                ("memory.csv", b"elapsed_ms\n1\n"),
                ("methods.trace", b"synthetic trace"),
            ]);
            assert_eq!(
                validate_archive(&bytes).unwrap()["interrupted"],
                interrupted
            );
            if interrupted {
                assert!(
                    validate_archive(&archive(&[("manifest.json", manifest.as_bytes())])).is_ok()
                );
            }
        }
    }

    #[test]
    fn rejects_unsafe_names_missing_metadata_and_excess_entries() {
        let manifest = br#"{"schema":2,"interrupted":false}"#;
        for entries in [
            vec![
                ("../manifest.json", manifest.as_slice()),
                ("memory.csv", b"x"),
            ],
            vec![("manifest.json", b"{}".as_slice()), ("memory.csv", b"x")],
            vec![("memory.csv", b"x".as_slice())],
            vec![("manifest.json", manifest.as_slice())],
            vec![
                ("manifest.json", manifest.as_slice()),
                ("memory.csv", b"x"),
                ("unexpected", b"x"),
            ],
        ] {
            assert!(validate_archive(&archive(&entries)).is_err());
        }
        let entries: Vec<_> = (0..7).map(|i| (format!("{i}"), vec![0])).collect();
        assert!(
            validate_archive(&archive(
                &entries
                    .iter()
                    .map(|(n, b)| (n.as_str(), b.as_slice()))
                    .collect::<Vec<_>>()
            ))
            .is_err()
        );
    }

    #[test]
    fn rejects_corruption_local_name_mismatch_and_expansion_over_limit() {
        let valid = archive(&[
            ("manifest.json", br#"{"schema":2,"interrupted":false}"#),
            ("memory.csv", b"elapsed_ms\n1\n"),
        ]);
        let mut corrupted = valid.clone();
        corrupted[30] = b'/';
        assert!(validate_archive(&corrupted).is_err());
        let mut corrupted = valid.clone();
        let offset = corrupted
            .windows(10)
            .position(|w| w == b"elapsed_ms")
            .unwrap();
        corrupted[offset] ^= 1;
        assert!(validate_archive(&corrupted).is_err());
        assert!(validate_archive(&valid[..valid.len() - 1]).is_err());
        assert!(
            validate_archive(&archive(&[("manifest.json", &vec![b' '; 16 * 1024 + 1])])).is_err()
        );
        assert!(validate_archive(b"not a zip").is_err());
    }
    #[test]
    fn quotas_are_shared_and_archive_rows_follow_retention_and_account_erasure()
    -> anyhow::Result<()> {
        let file = tempfile::NamedTempFile::new()?;
        let store = crate::CellStore::open(file.path())?;
        let mut connection = store.connection()?;
        connection.execute("INSERT INTO users(id,email,password_hash) VALUES (1,'one@example.org','synthetic'),(2,'two@example.org','synthetic')", params![])?;
        let manifest = json!({"schema":2,"interrupted":false});
        let first = save(&store, 1, b"synthetic archive", &manifest)?.unwrap();
        assert_eq!(
            save(&store, 1, b"synthetic archive", &manifest)?.unwrap(),
            first
        );
        let second = save(&store, 2, b"synthetic archive", &manifest)?.unwrap();
        assert_ne!(first, second);
        connection.execute(
            "UPDATE debug_sessions SET created_s=0 WHERE account_id=1",
            params![],
        )?;
        assert_eq!(store.prune_debug_sessions()?, 1);
        assert_eq!(
            connection.query_row("SELECT COUNT(*) FROM debug_profiles", params![], |row| row
                .get::<_, i64>(
                0
            ))?,
            1
        );
        connection.execute("DELETE FROM users WHERE id=2", params![])?;
        assert_eq!(
            connection.query_row("SELECT COUNT(*) FROM debug_profiles", params![], |row| row
                .get::<_, i64>(
                0
            ))?,
            0
        );
        connection.execute("INSERT INTO debug_sessions(id,account_id,client_id,context_json,created_s,updated_s,bytes) VALUES ('full',1,'full','{}',1,1,?1)",params![MAX_ACCOUNT_BYTES])?;
        assert_eq!(
            save(&store, 1, b"new archive", &manifest)?.unwrap_err().0,
            StatusCode::PAYLOAD_TOO_LARGE
        );
        connection.execute("DELETE FROM debug_sessions", params![])?;
        let tx = connection.transaction()?;
        for number in 0..100 {
            let id = format!("session-{number}");
            tx.execute("INSERT INTO debug_sessions(id,account_id,client_id,context_json,created_s,updated_s) VALUES (?1,1,?1,'{}',1,1)",params![id])?;
        }
        tx.commit()?;
        assert_eq!(
            save(&store, 1, b"new archive", &manifest)?.unwrap_err().0,
            StatusCode::PAYLOAD_TOO_LARGE
        );
        assert_eq!(
            connection.query_row("SELECT COUNT(*) FROM debug_profiles", params![], |row| row
                .get::<_, i64>(
                0
            ))?,
            0
        );
        Ok(())
    }
}
