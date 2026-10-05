mod limits;

#[cfg(test)]
use limits::{DAY, HOUR, enforce_limits_at};
use limits::{Limits, enforce_limits};
#[cfg(test)]
use std::time::Duration;

use crate::admin;
use crate::auth::{self, AuthRateLimits, MailConfig, SharedAuthLimits};
use crate::web;
use crate::{
    CellKey, CellStore, CellTower, Consensus, CsvDecodeError, Policy, Radio, ServerEvent,
    decode_towers, encode_towers,
};
use axum::body::Bytes;
use axum::extract::ws::{Message, WebSocket};
use axum::extract::{ConnectInfo, MatchedPath, Path, Query, Request, State, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::net::SocketAddr;
use std::str::FromStr;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use tokio::sync::broadcast;

const MAX_UPLOAD_BYTES: usize = 20 * 1024 * 1024;

/// Startup settings; policy is the initial value until a saved policy overrides it.
#[derive(Clone)]
pub struct ServerConfig {
    pub mail: Option<MailConfig>,
    pub policy: Policy,
    /// Honor the first `X-Forwarded-For` address. Enable only behind a trusted reverse proxy.
    pub trust_proxy: bool,
}

/// Shared application state used by HTTP requests and WebSocket connections.
#[derive(Clone)]
pub struct AppState {
    pub(crate) store: CellStore,
    pub(crate) config: ServerConfig,
    pub(crate) events: broadcast::Sender<ServerEvent>,
    limits: Arc<Mutex<Limits>>,
    pub(crate) auth_limits: SharedAuthLimits,
    pub(crate) policy: Arc<RwLock<Policy>>,
    pub(crate) write_gate: Arc<tokio::sync::RwLock<()>>,
    pub(crate) activation_gate: Arc<tokio::sync::RwLock<()>>,
    pub(crate) job: Arc<Mutex<admin::JobState>>,
}

impl AppState {
    /// Build state after validating every policy invariant used by request handlers.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid limits or consensus thresholds.
    pub fn new(store: CellStore, config: ServerConfig) -> anyhow::Result<Self> {
        config.policy.validate()?;
        let active_policy = store
            .stored_policy()?
            .unwrap_or_else(|| config.policy.clone());
        active_policy.validate()?;
        let prior_job = store.recover_admin_job()?;
        let (events, _) = broadcast::channel(1_024);
        Ok(Self {
            store,
            config,
            events,
            limits: Arc::new(Mutex::new(Limits::default())),
            auth_limits: Arc::new(Mutex::new(AuthRateLimits::default())),
            policy: Arc::new(RwLock::new(active_policy)),
            write_gate: Arc::new(tokio::sync::RwLock::new(())),
            activation_gate: Arc::new(tokio::sync::RwLock::new(())),
            job: Arc::new(Mutex::new(admin::JobState::recovered(prior_job))),
        })
    }

    pub(crate) fn policy(&self) -> Policy {
        self.policy.read().expect("policy lock poisoned").clone()
    }
}

/// Construct all compatibility, management, and real-time routes.
pub fn router(state: AppState) -> Router {
    Router::new()
        .merge(admin::router())
        .merge(auth::router())
        .merge(web::router())
        .route("/health", get(health))
        .route("/v1/cells", post(upload_cells))
        .route("/v1/cells.csv.gz", get(download_cells))
        .route("/v1/cells/removals.csv", get(download_removals))
        .route("/v1/towers", get(list_towers))
        .route(
            "/v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}",
            get(get_tower).put(put_tower).delete(delete_tower),
        )
        .route(
            "/v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}/quarantine",
            post(quarantine_tower),
        )
        .route("/v1/events", get(websocket_events))
        .layer(axum::extract::DefaultBodyLimit::max(MAX_UPLOAD_BYTES))
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            web::audit_impersonated_requests,
        ))
        .layer(axum::middleware::from_fn(log_request))
        .with_state(state)
}

/// Record route-level outcomes without exposing query strings or submitted data.
async fn log_request(request: Request, next: Next) -> Response {
    let method = request.method().clone();
    let route = request
        .extensions()
        .get::<MatchedPath>()
        .map_or("<unmatched>", MatchedPath::as_str)
        .to_owned();
    let started = Instant::now();
    let response = next.run(request).await;
    let status = response.status().as_u16();
    let elapsed_ms = started.elapsed().as_millis();
    if status >= 500 {
        tracing::warn!(%method, %route, status, elapsed_ms, "request completed");
    } else if route == "/health" {
        tracing::trace!(%method, %route, status, elapsed_ms, "request completed");
    } else {
        tracing::debug!(%method, %route, status, elapsed_ms, "request completed");
    }
    response
}

#[derive(Debug)]
pub(crate) struct ApiError(pub(crate) StatusCode, pub(crate) &'static str);

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (
            self.0,
            Json(serde_json::json!({ "status": "error", "message": self.1 })),
        )
            .into_response()
    }
}

impl From<anyhow::Error> for ApiError {
    fn from(error: anyhow::Error) -> Self {
        tracing::error!(error = %error, "request failed");
        Self(StatusCode::INTERNAL_SERVER_ERROR, "SERVER_ERROR")
    }
}

impl From<tokio::task::JoinError> for ApiError {
    fn from(error: tokio::task::JoinError) -> Self {
        tracing::error!(error = %error, "blocking task failed");
        Self(StatusCode::INTERNAL_SERVER_ERROR, "SERVER_ERROR")
    }
}

async fn health(State(state): State<AppState>) -> Result<String, ApiError> {
    let _visibility = state.activation_gate.read().await;
    let policy = state.policy();
    let counts = run_db(move || state.store.counts(&policy)).await?;
    Ok(format!("ok {}\n", counts.0))
}

#[derive(Serialize)]
struct UploadResponse {
    status: &'static str,
    accepted: usize,
    rejected: usize,
}

async fn upload_cells(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<UploadResponse>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    check_upload_permission(&state, account.id).await?;
    let ip = client_ip(peer, &headers, state.config.trust_proxy);
    let device = headers
        .get("x-device-id")
        .and_then(|value| value.to_str().ok())
        .filter(|value| valid_device_id(value))
        .map_or_else(|| format!("ip:{ip}"), str::to_owned);
    let device = format!("account:{}:{device}", account.id);
    if device == "seed" {
        return Err(ApiError(StatusCode::BAD_REQUEST, "BAD_DEVICE"));
    }
    enforce_limits(&state, &ip, &device)?;
    let row_limit = state.policy().max_rows_per_upload;
    let towers = tokio::task::spawn_blocking(move || decode_towers(&body, row_limit))
        .await
        .map_err(ApiError::from)?
        .map_err(|error| {
            if matches!(
                error,
                CsvDecodeError::TooManyRows { .. } | CsvDecodeError::DecompressedBodyTooLarge
            ) {
                ApiError(StatusCode::PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE")
            } else {
                ApiError(StatusCode::BAD_REQUEST, "BAD_BODY")
            }
        })?;
    let store = state.store.clone();
    let _write_guard = state.write_gate.read().await;
    check_upload_permission(&state, account.id).await?;
    let policy = state.policy();
    let device_for_log = device.clone();
    let (result, changed) =
        run_db(move || store.contribute(&device, &towers, now_s(), &policy)).await?;
    for tower in changed {
        if !state.store.quarantined(&tower.tower.key)? {
            let _ = state.events.send(ServerEvent::TowerUpserted { tower });
        }
    }
    tracing::info!(
        device = device_for_log,
        ip,
        accepted = result.accepted,
        rejected = result.rejected,
        "upload"
    );
    Ok(Json(UploadResponse {
        status: "ok",
        accepted: result.accepted,
        rejected: result.rejected,
    }))
}

async fn check_upload_permission(state: &AppState, account_id: i64) -> Result<(), ApiError> {
    let store = state.store.clone();
    let (sharing, verified) = run_db(move || store.account_sharing_status(account_id)).await?;
    if !verified {
        return Err(ApiError(StatusCode::FORBIDDEN, "EMAIL_UNVERIFIED"));
    }
    if !sharing {
        return Err(ApiError(StatusCode::FORBIDDEN, "SHARING_DISABLED"));
    }
    Ok(())
}

#[derive(Clone, Debug, Default, Deserialize)]
struct TowerQuery {
    mcc: Option<String>,
    since: Option<i64>,
    limit: Option<usize>,
}

impl TowerQuery {
    fn mccs(&self) -> Result<Option<HashSet<i64>>, ApiError> {
        let Some(value) = &self.mcc else {
            return Ok(None);
        };
        let mccs = value
            .split(',')
            .map(str::trim)
            .map(str::parse::<i64>)
            .collect::<Result<HashSet<_>, _>>()
            .map_err(|_| ApiError(StatusCode::BAD_REQUEST, "BAD_MCC"))?;
        if mccs.is_empty() || mccs.iter().any(|mcc| !(1..=999).contains(mcc)) {
            return Err(ApiError(StatusCode::BAD_REQUEST, "BAD_MCC"));
        }
        Ok(Some(mccs))
    }
}

async fn download_cells(
    State(state): State<AppState>,
    Query(query): Query<TowerQuery>,
) -> Result<Response, ApiError> {
    let _visibility = state.activation_gate.read().await;
    let store = state.store.clone();
    let policy = state.policy();
    let mccs = query.mccs()?;
    let since = query.since.unwrap_or(0).max(0);
    let body = run_db(move || {
        let towers = store.query(mccs.as_ref(), since, None, &policy)?;
        Ok(encode_towers(&towers)?)
    })
    .await?;
    Ok(([(header::CONTENT_TYPE, "application/gzip")], body).into_response())
}

async fn download_removals(
    State(state): State<AppState>,
    Query(query): Query<TowerQuery>,
) -> Result<Response, ApiError> {
    let _visibility = state.activation_gate.read().await;
    let sync_time = auth::now_s();
    let store = state.store.clone();
    let mccs = query.mccs()?;
    let since = query.since.unwrap_or(0).max(0);
    let body = run_db(move || {
        let mut writer = csv::Writer::from_writer(Vec::new());
        writer.write_record(["radio", "mcc", "mnc", "area", "cid"])?;
        for key in store.removals(mccs.as_ref(), since)? {
            writer.write_record([
                key.radio.to_string(),
                key.mcc.to_string(),
                key.mnc.to_string(),
                key.area.to_string(),
                key.cid.to_string(),
            ])?;
        }
        Ok(writer.into_inner()?)
    })
    .await?;
    let mut response = ([(header::CONTENT_TYPE, "text/csv; charset=utf-8")], body).into_response();
    response.headers_mut().insert(
        "x-cell-sync-time",
        sync_time.to_string().parse().expect("epoch seconds header"),
    );
    Ok(response)
}

#[derive(Serialize)]
struct TowerList {
    towers: Vec<Consensus>,
}

async fn list_towers(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Query(query): Query<TowerQuery>,
) -> Result<Json<TowerList>, ApiError> {
    auth::admin_account(&state, &headers, peer).await?;
    let limit = query.limit.unwrap_or(500).clamp(1, 5_000);
    let store = state.store.clone();
    let mccs = query.mccs()?;
    let since = query.since.unwrap_or(0).max(0);
    let towers = run_db(move || store.query_all(mccs.as_ref(), since, Some(limit))).await?;
    Ok(Json(TowerList { towers }))
}

#[derive(Deserialize)]
struct TowerUpdate {
    lat: f64,
    lon: f64,
    range_m: f64,
    samples: i64,
}

async fn get_tower(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
) -> Result<Json<Consensus>, ApiError> {
    auth::admin_account(&state, &headers, peer).await?;
    let key = path_key(path)?;
    let store = state.store.clone();
    let tower = run_db(move || store.consensus(&key))
        .await?
        .ok_or(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"))?;
    Ok(Json(tower))
}

async fn put_tower(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Json(update): Json<TowerUpdate>,
) -> Result<Json<Consensus>, ApiError> {
    let account = auth::admin_account(&state, &headers, peer).await?;
    let key = path_key(path)?;
    let tower = CellTower {
        key,
        lat: update.lat,
        lon: update.lon,
        range_m: update.range_m,
        samples: update.samples,
    };
    let store = state.store.clone();
    let _write_guard = state.write_gate.read().await;
    let policy = state.policy();
    let consensus = run_db(move || store.correct_tower(account.id, &tower, &policy))
        .await?
        .ok_or(ApiError(StatusCode::BAD_REQUEST, "INVALID_TOWER"))?;
    if !state.store.quarantined(&consensus.tower.key)? {
        let _ = state.events.send(ServerEvent::TowerUpserted {
            tower: consensus.clone(),
        });
    }
    Ok(Json(consensus))
}

async fn delete_tower(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
) -> Result<StatusCode, ApiError> {
    let account = auth::admin_account(&state, &headers, peer).await?;
    let key = path_key(path)?;
    let store = state.store.clone();
    let _guard = state.write_gate.read().await;
    let deleted = run_db({
        let key = key.clone();
        move || store.delete_quarantined(account.id, &key)
    })
    .await?;
    if !deleted {
        return Err(ApiError(StatusCode::CONFLICT, "QUARANTINE_REQUIRED"));
    }
    let _ = state.events.send(ServerEvent::TowerDeleted { key });
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct QuarantineUpdate {
    quarantined: bool,
}

async fn quarantine_tower(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Json(update): Json<QuarantineUpdate>,
) -> Result<StatusCode, ApiError> {
    let account = auth::admin_account(&state, &headers, peer).await?;
    let key = path_key(path)?;
    let _guard = state.write_gate.read().await;
    let store = state.store.clone();
    let target = key.clone();
    let policy = state.policy();
    if !run_db(move || store.set_quarantined(account.id, &target, update.quarantined, &policy))
        .await?
    {
        return Err(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"));
    }
    if update.quarantined {
        let _ = state.events.send(ServerEvent::TowerDeleted { key });
    } else if let Some(tower) = state.store.consensus(&key)? {
        let _ = state.events.send(ServerEvent::TowerUpserted { tower });
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn websocket_events(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    websocket: WebSocketUpgrade,
) -> Result<Response, ApiError> {
    let account = auth::admin_account(&state, &headers, peer).await?;
    tracing::info!(account_id = account.id, "admin event stream connected");
    Ok(websocket
        .on_upgrade(move |socket| stream_events(socket, state))
        .into_response())
}

async fn stream_events(mut socket: WebSocket, state: AppState) {
    // Subscribe before the initial count so updates committed during that query remain queued.
    let mut events = state.events.subscribe();
    let visibility = state.activation_gate.read().await;
    let policy = state.policy();
    let store = state.store.clone();
    let published = run_db(move || store.counts(&policy))
        .await
        .map_or(0, |counts| counts.0);
    drop(visibility);
    if send_event(&mut socket, &ServerEvent::Ready { published })
        .await
        .is_err()
    {
        return;
    }
    loop {
        tokio::select! {
            event = events.recv() => match event {
                Ok(event) if send_event(&mut socket, &event).await.is_err() => break,
                Ok(_) => {}
                Err(broadcast::error::RecvError::Lagged(missed)) => {
                    if send_event(&mut socket, &ServerEvent::ResyncRequired { missed }).await.is_err() {
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Closed) => break,
            },
            incoming = socket.next() => match incoming {
                Some(Ok(Message::Close(_)) | Err(_)) | None => break,
                _ => {}
            }
        }
    }
    tracing::info!("admin event stream disconnected");
}

async fn send_event(socket: &mut WebSocket, event: &ServerEvent) -> anyhow::Result<()> {
    let json = serde_json::to_string(event)?;
    socket.send(Message::Text(json.into())).await?;
    Ok(())
}

pub(crate) fn path_key(
    (radio, mcc, mnc, area, cid): (String, i64, i64, i64, i64),
) -> Result<CellKey, ApiError> {
    if !(1..=999).contains(&mcc) || !(0..=999).contains(&mnc) || area < 0 || cid < 0 {
        return Err(ApiError(StatusCode::BAD_REQUEST, "BAD_CELL_KEY"));
    }
    Ok(CellKey {
        radio: Radio::from_str(&radio)
            .map_err(|_| ApiError(StatusCode::BAD_REQUEST, "BAD_RADIO"))?,
        mcc,
        mnc,
        area,
        cid,
    })
}

pub(crate) async fn run_db<T: Send + 'static>(
    operation: impl FnOnce() -> anyhow::Result<T> + Send + 'static,
) -> Result<T, ApiError> {
    tokio::task::spawn_blocking(operation)
        .await
        .map_err(ApiError::from)?
        .map_err(ApiError::from)
}

fn valid_device_id(value: &str) -> bool {
    (8..=64).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
}

pub(crate) fn client_ip(peer: SocketAddr, headers: &HeaderMap, trust_proxy: bool) -> String {
    if trust_proxy {
        let forwarded = headers
            .get("x-forwarded-for")
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.split(',').next())
            .map(str::trim)
            .and_then(|value| value.parse::<std::net::IpAddr>().ok());
        if let Some(forwarded) = forwarded {
            return forwarded.to_string();
        }
    }
    peer.ip().to_string()
}

fn now_s() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_secs()).ok())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests;
