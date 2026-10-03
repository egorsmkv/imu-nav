use crate::admin;
use crate::{
    CellKey, CellStore, CellTower, Consensus, CsvDecodeError, Policy, PolicyError, Radio,
    ServerEvent, decode_towers, encode_towers,
};
use axum::body::Bytes;
use axum::extract::ws::{Message, WebSocket};
use axum::extract::{ConnectInfo, Path, Query, State, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet, VecDeque};
use std::net::SocketAddr;
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::sync::broadcast;

const MAX_UPLOAD_BYTES: usize = 20 * 1024 * 1024;
const HOUR: Duration = Duration::from_secs(60 * 60);
const DAY: Duration = Duration::from_secs(24 * 60 * 60);

/// Runtime server settings not stored in SQLite.
#[derive(Clone)]
pub struct ServerConfig {
    pub api_key: Option<String>,
    pub policy: Policy,
    /// Honor the first `X-Forwarded-For` address. Enable only behind a trusted reverse proxy.
    pub trust_proxy: bool,
}

/// Shared application state used by HTTP requests and WebSocket connections.
#[derive(Clone)]
pub struct AppState {
    pub(crate) store: CellStore,
    pub(crate) config: ServerConfig,
    events: broadcast::Sender<ServerEvent>,
    limits: Arc<Mutex<Limits>>,
}

impl AppState {
    /// Build state after validating every policy invariant used by request handlers.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid limits or consensus thresholds.
    pub fn new(store: CellStore, config: ServerConfig) -> Result<Self, PolicyError> {
        config.policy.validate()?;
        let (events, _) = broadcast::channel(1_024);
        Ok(Self {
            store,
            config,
            events,
            limits: Arc::new(Mutex::new(Limits::default())),
        })
    }
}

/// Construct all compatibility, management, and real-time routes.
pub fn router(state: AppState) -> Router {
    Router::new()
        .merge(admin::router())
        .route("/health", get(health))
        .route("/v1/cells", post(upload_cells))
        .route("/v1/cells.csv.gz", get(download_cells))
        .route("/v1/towers", get(list_towers))
        .route(
            "/v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}",
            get(get_tower).put(put_tower).delete(delete_tower),
        )
        .route("/v1/events", get(websocket_events))
        .layer(axum::extract::DefaultBodyLimit::max(MAX_UPLOAD_BYTES))
        .with_state(state)
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
    let policy = state.config.policy.clone();
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
    authorize(&headers, state.config.api_key.as_deref())?;
    let ip = client_ip(peer, &headers, state.config.trust_proxy);
    let device = headers
        .get("x-device-id")
        .and_then(|value| value.to_str().ok())
        .filter(|value| valid_device_id(value))
        .map_or_else(|| format!("ip:{ip}"), str::to_owned);
    if device == "seed" {
        return Err(ApiError(StatusCode::BAD_REQUEST, "BAD_DEVICE"));
    }
    enforce_limits(&state, &ip, &device)?;
    let row_limit = state.config.policy.max_rows_per_upload;
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
    let policy = state.config.policy.clone();
    let device_for_log = device.clone();
    let (result, changed) =
        run_db(move || store.contribute(&device, &towers, now_s(), &policy)).await?;
    for tower in changed {
        let _ = state.events.send(ServerEvent::TowerUpserted { tower });
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
    let store = state.store.clone();
    let policy = state.config.policy.clone();
    let mccs = query.mccs()?;
    let since = query.since.unwrap_or(0).max(0);
    let body = run_db(move || {
        let towers = store.query(mccs.as_ref(), since, None, &policy)?;
        Ok(encode_towers(&towers)?)
    })
    .await?;
    Ok(([(header::CONTENT_TYPE, "application/gzip")], body).into_response())
}

#[derive(Serialize)]
struct TowerList {
    towers: Vec<Consensus>,
}

async fn list_towers(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<TowerQuery>,
) -> Result<Json<TowerList>, ApiError> {
    authorize(&headers, state.config.api_key.as_deref())?;
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
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
) -> Result<Json<Consensus>, ApiError> {
    authorize(&headers, state.config.api_key.as_deref())?;
    let key = path_key(path)?;
    let store = state.store.clone();
    let tower = run_db(move || store.consensus(&key))
        .await?
        .ok_or(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"))?;
    Ok(Json(tower))
}

async fn put_tower(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Json(update): Json<TowerUpdate>,
) -> Result<Json<Consensus>, ApiError> {
    authorize(&headers, state.config.api_key.as_deref())?;
    let key = path_key(path)?;
    let tower = CellTower {
        key,
        lat: update.lat,
        lon: update.lon,
        range_m: update.range_m,
        samples: update.samples,
    };
    let store = state.store.clone();
    let policy = state.config.policy.clone();
    let (_, changed) = run_db(move || store.seed(&[tower], now_s(), &policy)).await?;
    let consensus = changed
        .into_iter()
        .next()
        .ok_or(ApiError(StatusCode::BAD_REQUEST, "INVALID_TOWER"))?;
    let _ = state.events.send(ServerEvent::TowerUpserted {
        tower: consensus.clone(),
    });
    Ok(Json(consensus))
}

async fn delete_tower(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
) -> Result<StatusCode, ApiError> {
    authorize(&headers, state.config.api_key.as_deref())?;
    let key = path_key(path)?;
    let store = state.store.clone();
    let deleted = run_db({
        let key = key.clone();
        move || store.delete(&key)
    })
    .await?;
    if !deleted {
        return Err(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"));
    }
    let _ = state.events.send(ServerEvent::TowerDeleted { key });
    Ok(StatusCode::NO_CONTENT)
}

async fn websocket_events(
    State(state): State<AppState>,
    headers: HeaderMap,
    websocket: WebSocketUpgrade,
) -> Result<Response, ApiError> {
    authorize(&headers, state.config.api_key.as_deref())?;
    Ok(websocket
        .on_upgrade(move |socket| stream_events(socket, state))
        .into_response())
}

async fn stream_events(mut socket: WebSocket, state: AppState) {
    // Subscribe before the initial count so updates committed during that query remain queued.
    let mut events = state.events.subscribe();
    let policy = state.config.policy.clone();
    let store = state.store.clone();
    let published = run_db(move || store.counts(&policy))
        .await
        .map_or(0, |counts| counts.0);
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
}

async fn send_event(socket: &mut WebSocket, event: &ServerEvent) -> anyhow::Result<()> {
    let json = serde_json::to_string(event)?;
    socket.send(Message::Text(json.into())).await?;
    Ok(())
}

fn authorize(headers: &HeaderMap, api_key: Option<&str>) -> Result<(), ApiError> {
    let Some(api_key) = api_key.filter(|value| !value.is_empty()) else {
        return Ok(());
    };
    let expected = format!("Bearer {api_key}");
    let actual = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok());
    (actual == Some(expected.as_str()))
        .then_some(())
        .ok_or(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))
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

#[derive(Default)]
struct Limits {
    by_device: HashMap<String, VecDeque<Instant>>,
    by_ip: HashMap<String, VecDeque<Instant>>,
    devices_by_ip: HashMap<String, HashMap<String, Instant>>,
    last_cleanup: Option<Instant>,
}

impl Limits {
    fn cleanup_if_due(&mut self, now: Instant) {
        let cleanup_due = self
            .last_cleanup
            .is_none_or(|last| now.saturating_duration_since(last) >= HOUR);
        if !cleanup_due {
            return;
        }
        self.by_device.retain(|_, queue| {
            prune(queue, now, HOUR);
            !queue.is_empty()
        });
        self.by_ip.retain(|_, queue| {
            prune(queue, now, HOUR);
            !queue.is_empty()
        });
        self.devices_by_ip.retain(|_, devices| {
            devices.retain(|_, seen| now.saturating_duration_since(*seen) <= DAY);
            !devices.is_empty()
        });
        self.last_cleanup = Some(now);
    }
}

fn enforce_limits(state: &AppState, ip: &str, device: &str) -> Result<(), ApiError> {
    enforce_limits_at(state, ip, device, Instant::now())
}

// Explicit time keeps expiry boundaries deterministic in tests.
fn enforce_limits_at(
    state: &AppState,
    ip: &str,
    device: &str,
    now: Instant,
) -> Result<(), ApiError> {
    let mut limits = state
        .limits
        .lock()
        .map_err(|_| ApiError(StatusCode::INTERNAL_SERVER_ERROR, "SERVER_ERROR"))?;
    limits.cleanup_if_due(now);
    let policy = &state.config.policy;
    let devices = limits.devices_by_ip.entry(ip.to_owned()).or_default();
    devices.retain(|_, seen| now.saturating_duration_since(*seen) <= DAY);
    if !devices.contains_key(device) && devices.len() >= policy.max_devices_per_ip_per_day {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "TOO_MANY_DEVICES"));
    }
    devices.insert(device.to_owned(), now);
    if !allow(
        &mut limits.by_ip,
        ip,
        now,
        policy.max_uploads_per_hour_per_ip,
    ) || !allow(
        &mut limits.by_device,
        device,
        now,
        policy.max_uploads_per_hour_per_device,
    ) {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED"));
    }
    Ok(())
}

fn allow(
    hits: &mut HashMap<String, VecDeque<Instant>>,
    key: &str,
    now: Instant,
    maximum: usize,
) -> bool {
    let queue = hits.entry(key.to_owned()).or_default();
    prune(queue, now, HOUR);
    if queue.len() >= maximum {
        return false;
    }
    queue.push_back(now);
    true
}

fn prune(queue: &mut VecDeque<Instant>, now: Instant, window: Duration) {
    while queue
        .front()
        .is_some_and(|time| now.saturating_duration_since(*time) > window)
    {
        queue.pop_front();
    }
}

fn valid_device_id(value: &str) -> bool {
    (8..=64).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
}

fn client_ip(peer: SocketAddr, headers: &HeaderMap, trust_proxy: bool) -> String {
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
