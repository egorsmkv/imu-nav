//! Opt-in trip diagnostics, kept apart from the public cell database.

use crate::api::{ApiError, AppState, run_db};
use crate::auth;
use crate::db::params;
use crate::web;
use askama::Template;
use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Form, Json, Router};
use flate2::{Compression, write::GzEncoder};
use rusqlite::{OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Write;

const MAX_BATCH_BYTES: usize = 8 * 1024 * 1024;
const MAX_SESSION_BYTES: i64 = 64 * 1024 * 1024;
const MAX_ACCOUNT_BYTES: i64 = 256 * 1024 * 1024;

pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/v1/debug/sessions", post(start))
        .route("/v1/debug/sessions/{id}", get(status))
        .route("/v1/debug/sessions/{id}/batches/{seq}", put(batch))
        .route("/v1/debug/sessions/{id}/finish", post(finish))
        .route("/debug", get(list_page))
        .route("/debug/{id}", get(detail_page))
        .route("/debug/{id}/download/{kind}", get(download))
        .route("/debug/{id}/delete", post(delete))
        .route("/debug/delete-all", post(delete_all))
        .route("/admin/debug/enable", post(set_enabled))
}

fn prune(store: &crate::CellStore) -> anyhow::Result<()> {
    store.prune_debug_sessions()?;
    Ok(())
}

fn enabled(store: &crate::CellStore) -> anyhow::Result<bool> {
    Ok(store
        .connection()?
        .query_row(
            "SELECT value FROM server_settings WHERE key='debug_upload_enabled'",
            params![],
            |row| row.get::<_, String>(0),
        )
        .optional()?
        .is_some_and(|value| value == "1"))
}

fn unavailable() -> ApiError {
    ApiError(StatusCode::FORBIDDEN, "DEBUG_DISABLED")
}
fn bad_request() -> ApiError {
    ApiError(StatusCode::BAD_REQUEST, "INVALID_DEBUG_DATA")
}

#[derive(Deserialize)]
struct StartRequest {
    client_id: String,
    context: serde_json::Value,
}

#[derive(Serialize)]
struct SessionStatus {
    id: String,
    next_seq: i64,
    finished: bool,
    incomplete: bool,
}

async fn start(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<StartRequest>,
) -> Result<Json<SessionStatus>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    let context = serde_json::to_string(&request.context).map_err(|_| bad_request())?;
    if context.len() > 4096
        || !request.context.is_object()
        || request.client_id.is_empty()
        || request.client_id.len() > 80
        || !request
            .client_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    {
        return Err(bad_request());
    }
    let store = state.store.clone();
    let id = format!("{:032x}", rand::random::<u128>());
    let id_for_db = id.clone();
    let _gate = state.write_gate.write().await;
    let session = run_db(move || -> anyhow::Result<Result<Option<SessionStatus>,ApiError>> {
        prune(&store)?;
        if !enabled(&store)? { return Ok(Ok(None)); }
        let mut connection = store.connection()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let existing: Option<(String,bool,bool)> = tx.query_row(
            "SELECT id,finished_s IS NOT NULL,incomplete FROM debug_sessions WHERE account_id=?1 AND client_id=?2",
            params![account.id,request.client_id],
            |row| Ok((row.get(0)?,row.get(1)?,row.get(2)?)),
        ).optional()?;
        if let Some((id,finished,incomplete)) = existing {
            let next_seq = tx.query_row("SELECT COALESCE(MAX(seq)+1,0) FROM debug_batches WHERE session_id=?1", [&id], |row| row.get(0))?;
            tx.commit()?;
            return Ok(Ok(Some(SessionStatus { id,next_seq,finished,incomplete })));
        }
        let count: i64 = tx.query_row("SELECT COUNT(*) FROM debug_sessions WHERE account_id=?1",[account.id],|row| row.get(0))?;
        if count >= 100 { return Ok(Err(ApiError(StatusCode::PAYLOAD_TOO_LARGE,"DEBUG_QUOTA_EXCEEDED"))); }
        tx.execute("INSERT INTO debug_sessions(id,account_id,client_id,context_json,created_s,updated_s) VALUES (?1,?2,?3,?4,?5,?5)",
            params![id_for_db, account.id, request.client_id, context, auth::now_s()])?;
        tx.commit()?;
        Ok(Ok(Some(SessionStatus { id: id_for_db, next_seq: 0, finished: false, incomplete: false })))
    }).await??;
    let session = session.ok_or_else(unavailable)?;
    tracing::info!(account_id=account.id, session=%session.id, "debug session accepted");
    Ok(Json(session))
}

fn owned_status(
    store: &crate::CellStore,
    id: &str,
    account_id: i64,
) -> anyhow::Result<Option<SessionStatus>> {
    let connection = store.connection()?;
    connection.query_row(
        "SELECT id,finished_s IS NOT NULL,incomplete,COALESCE((SELECT MAX(seq)+1 FROM debug_batches WHERE session_id=debug_sessions.id),0)
         FROM debug_sessions WHERE id=?1 AND account_id=?2",
        params![id,account_id],
        |row| Ok(SessionStatus { id: row.get(0)?, finished: row.get(1)?, incomplete: row.get(2)?, next_seq: row.get(3)? }),
    ).optional().map_err(Into::into)
}

async fn status(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<SessionStatus>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    let store = state.store.clone();
    run_db(move || owned_status(&store, &id, account.id))
        .await?
        .map(Json)
        .ok_or(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"))
}

#[derive(Deserialize)]
struct Entry {
    kind: String,
    elapsed_ms: i64,
    line: String,
}
#[derive(Deserialize)]
struct Batch {
    entries: Vec<Entry>,
}

async fn batch(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((id, seq)): Path<(String, i64)>,
    body: Bytes,
) -> Result<Json<SessionStatus>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    if body.len() > MAX_BATCH_BYTES || seq < 0 {
        return Err(ApiError(
            StatusCode::PAYLOAD_TOO_LARGE,
            "DEBUG_BATCH_TOO_LARGE",
        ));
    }
    let parsed: Batch = serde_json::from_slice(&body).map_err(|_| bad_request())?;
    if parsed.entries.is_empty()
        || parsed.entries.len() > 500
        || parsed.entries.iter().any(|entry| {
            !matches!(entry.kind.as_str(), "event" | "log")
                || entry.line.len() > 7 * 1024 * 1024
                || entry.line.contains('\0')
                || entry.elapsed_ms < 0
        })
    {
        return Err(bad_request());
    }
    let digest = format!("{:x}", Sha256::digest(&body));
    let store = state.store.clone();
    let _gate = state.write_gate.read().await;
    let outcome = run_db(move || -> anyhow::Result<Result<SessionStatus, ApiError>> {
        if !enabled(&store)? { return Ok(Err(unavailable())); }
        let mut connection = store.connection()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let Some((session_bytes, finished, incomplete)) = tx.query_row(
            "SELECT bytes,finished_s IS NOT NULL,incomplete FROM debug_sessions WHERE id=?1 AND account_id=?2",
            params![id,account.id], |row| Ok((row.get::<_, i64>(0)?,row.get::<_, bool>(1)?,row.get::<_, bool>(2)?)),
        ).optional()? else { return Ok(Err(ApiError(StatusCode::NOT_FOUND,"NOT_FOUND"))); };
        if let Some(previous) = tx.query_row("SELECT digest FROM debug_batches WHERE session_id=?1 AND seq=?2",
            params![id,seq], |row| row.get::<_,String>(0)).optional()? {
            if previous != digest { return Ok(Err(ApiError(StatusCode::CONFLICT,"DEBUG_BATCH_CONFLICT"))); }
            return Ok(Ok(SessionStatus { id, next_seq: seq+1, finished, incomplete }));
        }
        let next: i64 = tx.query_row("SELECT COALESCE(MAX(seq)+1,0) FROM debug_batches WHERE session_id=?1", [&id], |row| row.get(0))?;
        if finished || seq != next { return Ok(Err(ApiError(StatusCode::CONFLICT,"DEBUG_SEQUENCE_CONFLICT"))); }
        let account_bytes: i64 = tx.query_row("SELECT CAST(COALESCE(SUM(bytes),0) AS BIGINT) FROM debug_sessions WHERE account_id=?1", [account.id], |row| row.get(0))?;
        let size = i64::try_from(body.len())?;
        if session_bytes + size > MAX_SESSION_BYTES || account_bytes + size > MAX_ACCOUNT_BYTES {
            tx.execute("UPDATE debug_sessions SET incomplete=1 WHERE id=?1",[&id])?;
            tx.commit()?;
            return Ok(Err(ApiError(StatusCode::PAYLOAD_TOO_LARGE,"DEBUG_QUOTA_EXCEEDED")));
        }
        tx.execute("INSERT INTO debug_batches(session_id,seq,digest,bytes) VALUES (?1,?2,?3,?4)", params![id,seq,digest,size])?;
        for (item, entry) in parsed.entries.iter().enumerate() {
            let item = i64::try_from(item)?;
            tx.execute("INSERT INTO debug_entries(session_id,seq,item,kind,elapsed_ms,line) VALUES (?1,?2,?3,?4,?5,?6)",
                params![id,seq,item,entry.kind,entry.elapsed_ms,entry.line])?;
        }
        tx.execute("UPDATE debug_sessions SET bytes=bytes+?2,updated_s=?3 WHERE id=?1", params![id,size,auth::now_s()])?;
        tx.commit()?;
        Ok(Ok(SessionStatus { id, next_seq: seq+1, finished: false, incomplete }))
    }).await?;
    outcome.map(Json)
}

#[derive(Deserialize)]
struct FinishRequest {
    next_seq: i64,
    #[serde(default)]
    incomplete: bool,
}

async fn finish(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(request): Json<FinishRequest>,
) -> Result<Json<SessionStatus>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    let store = state.store.clone();
    let _gate = state.write_gate.write().await;
    let outcome = run_db(move || -> anyhow::Result<Result<SessionStatus, ApiError>> {
        let Some(status) = owned_status(&store, &id, account.id)? else { return Ok(Err(ApiError(StatusCode::NOT_FOUND,"NOT_FOUND"))); };
        if status.next_seq != request.next_seq { return Ok(Err(ApiError(StatusCode::CONFLICT,"DEBUG_SEQUENCE_CONFLICT"))); }
        store.connection()?.execute("UPDATE debug_sessions SET finished_s=COALESCE(finished_s,?2),updated_s=?2,incomplete=MAX(incomplete,?3) WHERE id=?1", params![id,auth::now_s(),request.incomplete])?;
        Ok(Ok(SessionStatus { finished: true, incomplete: status.incomplete || request.incomplete, ..status }))
    }).await?;
    outcome.map(Json)
}

#[derive(Template)]
#[template(path = "debug_sessions.html")]
struct SessionsPage {
    nav: web::SiteChrome,
    admin: bool,
    enabled: bool,
    csrf: String,
    rows: Vec<SessionRow>,
    page: i64,
    has_next: bool,
}
#[derive(Deserialize)]
struct ListQuery {
    page: Option<i64>,
}
struct SessionRow {
    id: String,
    email: String,
    created: web::WebTime,
    updated: web::WebTime,
    finished: bool,
    incomplete: bool,
    bytes: i64,
}

async fn list_page(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ListQuery>,
) -> Result<Response, ApiError> {
    let (account, raw) = browser(&state, &headers).await?;
    let page = query.page.unwrap_or(0).clamp(0, 100_000);
    let store = state.store.clone();
    let (enabled, mut rows) = run_db(move || -> anyhow::Result<_> {
        prune(&store)?;
        let connection = store.connection()?;
        let mut query = connection.prepare("SELECT ds.id,u.email,ds.created_s,ds.updated_s,ds.finished_s IS NOT NULL,ds.incomplete,ds.bytes
            FROM debug_sessions ds JOIN users u ON u.id=ds.account_id
            WHERE (?1=1 OR ds.account_id=?2) ORDER BY ds.created_s DESC,ds.id DESC LIMIT 101 OFFSET ?3")?;
        let rows = query.query_map(params![account.admin,account.id,page*100], |row| Ok(SessionRow {
            id: row.get(0)?, email: row.get(1)?, created: web::utc_time(row.get(2)?), updated: web::utc_time(row.get(3)?),
            finished: row.get(4)?, incomplete: row.get(5)?, bytes: row.get(6)?,
        }))?.collect::<Result<Vec<_>,_>>()?;
        Ok((enabled(&store)?,rows))
    }).await?;
    let has_next = rows.len() > 100;
    rows.truncate(100);
    html(&SessionsPage {
        nav: web::SiteChrome::account(account.admin, web::csrf_token(&raw)),
        admin: account.admin,
        enabled,
        csrf: web::csrf_token(&raw),
        rows,
        page,
        has_next,
    })
}

#[derive(Deserialize)]
struct DetailQuery {
    q: Option<String>,
    page: Option<i64>,
}
#[derive(Template)]
#[template(path = "debug_detail.html")]
struct DetailPage {
    nav: web::SiteChrome,
    id: String,
    context: String,
    email: String,
    csrf: String,
    entries: Vec<DetailEntry>,
    q: String,
    page: i64,
    has_next: bool,
    finished: bool,
    incomplete: bool,
}
struct DetailEntry {
    kind: String,
    elapsed_ms: i64,
    line: String,
}
struct SessionDetail {
    context: String,
    email: String,
    finished: bool,
    incomplete: bool,
    entries: Vec<DetailEntry>,
}

async fn detail_page(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Query(query): Query<DetailQuery>,
) -> Result<Response, ApiError> {
    let (account, raw) = browser(&state, &headers).await?;
    let store = state.store.clone();
    let q = query
        .q
        .unwrap_or_default()
        .chars()
        .take(100)
        .collect::<String>();
    let page = query.page.unwrap_or(0).clamp(0, 100_000);
    let needle = format!("%{}%", q.replace('%', "\\%").replace('_', "\\_"));
    let id_for_db = id.clone();
    let result = run_db(move || -> anyhow::Result<Option<SessionDetail>> {
        let connection = store.connection()?;
        let Some((context,email,finished,incomplete)) = connection.query_row(
            "SELECT ds.context_json,u.email,ds.finished_s IS NOT NULL,ds.incomplete FROM debug_sessions ds JOIN users u ON u.id=ds.account_id
             WHERE ds.id=?1 AND (?2=1 OR ds.account_id=?3)", params![id_for_db,account.admin,account.id],
            |row| Ok((row.get::<_,String>(0)?,row.get::<_,String>(1)?,row.get::<_,bool>(2)?,row.get::<_,bool>(3)?)),
        ).optional()? else { return Ok(None); };
        let mut statement = connection.prepare("SELECT kind,elapsed_ms,line FROM debug_entries WHERE session_id=?1 AND line LIKE ?2 ESCAPE '\\'
            ORDER BY seq,item LIMIT 201 OFFSET ?3")?;
        let entries = statement.query_map(params![id_for_db,needle,page*200], |row| Ok(DetailEntry {
            kind: row.get(0)?,elapsed_ms: row.get(1)?,line: row.get(2)?,
        }))?.collect::<Result<Vec<_>,_>>()?;
        Ok(Some(SessionDetail { context, email, finished, incomplete, entries }))
    }).await?;
    let Some(SessionDetail {
        context,
        email,
        finished,
        incomplete,
        mut entries,
    }) = result
    else {
        return Err(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"));
    };
    let has_next = entries.len() > 200;
    entries.truncate(200);
    html(&DetailPage {
        nav: web::SiteChrome::account(account.admin, web::csrf_token(&raw)),
        id,
        context,
        email,
        csrf: web::csrf_token(&raw),
        entries,
        q,
        page,
        has_next,
        finished,
        incomplete,
    })
}

async fn download(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((id, kind)): Path<(String, String)>,
) -> Result<Response, ApiError> {
    if !matches!(kind.as_str(), "recording" | "logs" | "context") {
        return Err(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"));
    }
    let (account, _) = browser(&state, &headers).await?;
    let store = state.store.clone();
    let kind_for_db = kind.clone();
    let id_for_db = id.clone();
    let bytes = run_db(move || -> anyhow::Result<Option<Vec<u8>>> {
        let connection = store.connection()?;
        let context = connection
            .query_row(
                "SELECT context_json FROM debug_sessions WHERE id=?1 AND (?2=1 OR account_id=?3)",
                params![id_for_db, account.admin, account.id],
                |row| row.get::<_, String>(0),
            )
            .optional()?;
        let Some(context) = context else {
            return Ok(None);
        };
        if account.admin {
            store.audit(account.id, "debug_download", &id_for_db)?;
        }
        if kind_for_db == "context" {
            return Ok(Some(context.into_bytes()));
        }
        let wanted = if kind_for_db == "recording" {
            "event"
        } else {
            "log"
        };
        let mut query = connection.prepare(
            "SELECT line FROM debug_entries WHERE session_id=?1 AND kind=?2 ORDER BY seq,item",
        )?;
        let mut lines = query.query(params![id_for_db, wanted])?;
        if kind_for_db == "recording" {
            let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
            encoder.write_all(b"# blind-driver trip v1\n")?;
            while let Some(row) = lines.next()? {
                writeln!(encoder, "{}", row.get::<_, String>(0)?)?;
            }
            Ok(Some(encoder.finish()?))
        } else {
            let mut output = Vec::new();
            while let Some(row) = lines.next()? {
                writeln!(output, "{}", row.get::<_, String>(0)?)?;
            }
            Ok(Some(output))
        }
    })
    .await?;
    let bytes = bytes.ok_or(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"))?;
    let (suffix, content_type) = match kind.as_str() {
        "recording" => ("rec.gz", "application/gzip"),
        "logs" => ("log", "text/plain; charset=utf-8"),
        _ => ("json", "application/json"),
    };
    Ok((
        [
            (header::CONTENT_TYPE, content_type.to_owned()),
            (header::CACHE_CONTROL, "private, no-store".to_owned()),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff".to_owned()),
            (
                header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"debug-{id}.{suffix}\""),
            ),
        ],
        bytes,
    )
        .into_response())
}

#[derive(Deserialize)]
struct CsrfForm {
    csrf: String,
}
#[derive(Deserialize)]
struct DeleteAllForm {
    csrf: String,
    confirm: String,
}

async fn delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Form(form): Form<CsrfForm>,
) -> Result<Response, ApiError> {
    let (account, raw) = browser(&state, &headers).await?;
    csrf(&raw, &form.csrf)?;
    let _gate = state.write_gate.write().await;
    let store = state.store.clone();
    run_db(move || {
        let affected = store.connection()?.execute(
            "DELETE FROM debug_sessions WHERE id=?1 AND (?2=1 OR account_id=?3)",
            params![id, account.admin, account.id],
        )?;
        if affected > 0 && account.admin {
            store.audit(account.id, "debug_delete", &id)?;
        }
        Ok(())
    })
    .await?;
    Ok(redirect("/debug"))
}

async fn delete_all(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<DeleteAllForm>,
) -> Result<Response, ApiError> {
    let (account, raw) = browser(&state, &headers).await?;
    csrf(&raw, &form.csrf)?;
    if form.confirm != "DELETE" {
        return Err(ApiError(StatusCode::BAD_REQUEST, "CONFIRM_DELETE"));
    }
    let _gate = state.write_gate.write().await;
    let store = state.store.clone();
    run_db(move || {
        store.connection()?.execute(
            "DELETE FROM debug_sessions WHERE account_id=?1",
            [account.id],
        )?;
        Ok(())
    })
    .await?;
    Ok(redirect("/debug"))
}

#[derive(Deserialize)]
struct EnableForm {
    csrf: String,
    enabled: String,
}

async fn set_enabled(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<EnableForm>,
) -> Result<Response, ApiError> {
    let (account, raw) = browser(&state, &headers).await?;
    if !account.admin {
        return Err(ApiError(StatusCode::FORBIDDEN, "FORBIDDEN"));
    }
    csrf(&raw, &form.csrf)?;
    let enabled = form.enabled == "1";
    let store = state.store.clone();
    let _gate = state.write_gate.write().await;
    run_db(move || {
        store.connection()?.execute(
            "INSERT INTO server_settings(key,value) VALUES ('debug_upload_enabled',?1)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            [if enabled { "1" } else { "0" }],
        )?;
        store.audit(
            account.id,
            "debug_upload_enabled",
            if enabled { "1" } else { "0" },
        )?;
        Ok(())
    })
    .await?;
    Ok(redirect("/debug"))
}

async fn browser(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<(auth::Account, String), ApiError> {
    web::web_account(state, headers)
        .await?
        .ok_or(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))
}
fn csrf(raw: &str, supplied: &str) -> Result<(), ApiError> {
    if web::csrf_token(raw) == supplied {
        Ok(())
    } else {
        Err(ApiError(StatusCode::FORBIDDEN, "FORBIDDEN"))
    }
}
fn html(template: &impl Template) -> Result<Response, ApiError> {
    let body = template
        .render()
        .map_err(|error| ApiError::from(anyhow::Error::new(error)))?;
    Ok(([(header::CONTENT_TYPE,"text/html; charset=utf-8"),
        (header::CONTENT_SECURITY_POLICY,"default-src 'none'; style-src 'self'; script-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"),
        (header::CACHE_CONTROL,"no-store")],body).into_response())
}
fn redirect(path: &'static str) -> Response {
    (StatusCode::SEE_OTHER, [(header::LOCATION, path)]).into_response()
}
